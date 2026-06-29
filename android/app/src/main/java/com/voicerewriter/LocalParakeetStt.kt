package com.voicerewriter

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.whispercpp.whisper.WhisperCpuConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device speech-to-text via the NVIDIA Parakeet-TDT-0.6b-v2 (int8) transducer, served by
 * sherpa-onnx (vendored AAR). Faster and more accurate than whisper.cpp on the S25 (p50 ~238ms,
 * prose WER below whisper-small) and — being a transducer — its latency stays flat with clip
 * length, so the disfluent tail no longer blows up the p95.
 *
 * Mirrors [LocalWhisperStt]: a lazily-built recognizer cached across dictations, a [warm] hook
 * to pay the load cost off the critical path, and a [transcribe] that takes 16 kHz mono floats.
 *
 * [biasPrompt] is wired by [withBias] (hotwords contextual biasing) — see the recognizer config.
 */
object LocalParakeetStt {

    private val loadLock = Mutex()
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedBias: String? = null

    private fun buildRecognizer(context: Context, biasPrompt: String?): OfflineRecognizer {
        val model = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = ParakeetModelManager.encoderPath(context),
                decoder = ParakeetModelManager.decoderPath(context),
                joiner = ParakeetModelManager.joinerPath(context),
            ),
            tokens = ParakeetModelManager.tokensPath(context),
            numThreads = WhisperCpuConfig.preferredThreadCount, // same 2..4 core budget as whisper
            modelType = "nemo_transducer",
            debug = false,
        )
        // Hotwords need modified_beam_search; plain dictation uses greedy (fastest). A user
        // vocab glossary is written to a hotwords file by withBias() and boosts those spellings.
        val hotwords = hotwordsFile(context, biasPrompt)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = model,
            decodingMethod = if (hotwords != null) "modified_beam_search" else "greedy_search",
            hotwordsFile = hotwords?.absolutePath ?: "",
            hotwordsScore = 1.5f,
        )
        return OfflineRecognizer(config = config)
    }

    /**
     * Transcribe [samples] (16 kHz mono, normalized -1..1). [biasPrompt] is the user's personal
     * vocabulary (one term per line is written to a hotwords file); blank disables biasing.
     */
    suspend fun transcribe(context: Context, settings: Settings, samples: FloatArray, biasPrompt: String? = null): String {
        if (!ParakeetModelManager.isReady(context)) {
            throw IllegalStateException("Parakeet model not downloaded. Open Settings → Voice → Download model.")
        }
        val seconds = samples.size / AudioRecorder.SAMPLE_RATE.toFloat()
        val bias = biasPrompt?.ifBlank { null }
        val t0 = System.nanoTime()
        val rec = loadLock.withLock {
            if (recognizer == null || loadedBias != bias) {
                recognizer?.let { runCatching { it.release() } }
                recognizer = withContext(Dispatchers.IO) { buildRecognizer(context, bias) }
                loadedBias = bias
            }
            recognizer!!
        }
        val tLoaded = System.nanoTime()
        val text = withContext(Dispatchers.Default) {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, 16_000)
                rec.decode(stream)
                rec.getResult(stream).text
            } finally {
                stream.release()
            }
        }
        val tDone = System.nanoTime()
        Log.i(
            "LocalParakeetStt",
            "audio=${"%.1f".format(seconds)}s load=${(tLoaded - t0) / 1_000_000}ms " +
                "infer=${(tDone - tLoaded) / 1_000_000}ms threads=${WhisperCpuConfig.preferredThreadCount}",
        )
        return text.trim()
    }

    /** Preload the recognizer ahead of the first dictation (e.g. on service start). Best-effort. */
    suspend fun warm(context: Context, biasPrompt: String? = null) {
        if (!ParakeetModelManager.isReady(context)) return
        val bias = biasPrompt?.ifBlank { null }
        loadLock.withLock {
            if (recognizer != null && loadedBias == bias) return
            val t0 = System.nanoTime()
            recognizer?.let { runCatching { it.release() } }
            recognizer = withContext(Dispatchers.IO) { buildRecognizer(context, bias) }
            loadedBias = bias
            Log.i("LocalParakeetStt", "warm load=${(System.nanoTime() - t0) / 1_000_000}ms")
        }
    }

    /**
     * Write the bias glossary to a hotwords file (one entry per line, as sherpa expects) and
     * return it, or null when there's nothing to bias. Transducers have no whisper-style
     * initial_prompt, so contextual biasing (hotwords) is how we keep names spelled correctly.
     */
    private fun hotwordsFile(context: Context, biasPrompt: String?): File? {
        val terms = biasPrompt?.split(",", "\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (terms.isEmpty()) return null
        val f = File(ParakeetModelManager.modelDir(context), "hotwords.txt")
        f.writeText(terms.joinToString("\n"))
        return f
    }
}
