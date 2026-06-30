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

/**
 * On-device speech-to-text via the NVIDIA Parakeet-TDT-0.6b-v2 (int8) transducer, served by
 * sherpa-onnx (vendored AAR). Faster and more accurate than whisper.cpp on the S25 (p50 ~238ms,
 * prose WER below whisper-small) and — being a transducer — its latency stays flat with clip
 * length, so the disfluent tail no longer blows up the p95.
 *
 * Mirrors [LocalWhisperStt]: a lazily-built recognizer cached across dictations and a [warm]
 * hook to pay the load cost off the critical path.
 *
 * NOTE on [biasPrompt]: whisper biases decoding via initial_prompt; transducers do it via
 * sherpa "hotwords", which for Parakeet's BPE tokenizer need modelingUnit="bpe" + a BPE vocab
 * file — NOT shipped in this model bundle. Wiring hotwords without it makes sherpa's
 * EncodeHotwords fail on an empty modeling_unit and crash the decoder, so biasing is disabled
 * for now (greedy decoding). The personal-vocab glossary still snaps near-misses post-STT via
 * VocabCorrector. TODO: ship the BPE vocab + set modelingUnit to re-enable hotwords.
 */
object LocalParakeetStt {

    private val loadLock = Mutex()
    @Volatile private var recognizer: OfflineRecognizer? = null

    private fun buildRecognizer(context: Context): OfflineRecognizer {
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
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = model,
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(config = config)
    }

    /** Transcribe [samples] (16 kHz mono, normalized -1..1). [biasPrompt] is currently unused (see class note). */
    @Suppress("UNUSED_PARAMETER")
    suspend fun transcribe(context: Context, settings: Settings, samples: FloatArray, biasPrompt: String? = null): String {
        if (!ParakeetModelManager.isReady(context)) {
            throw IllegalStateException("Parakeet model not downloaded. Open Settings → Voice → Download model.")
        }
        val seconds = samples.size / AudioRecorder.SAMPLE_RATE.toFloat()
        val t0 = System.nanoTime()
        val rec = loadLock.withLock {
            recognizer ?: withContext(Dispatchers.IO) { buildRecognizer(context) }.also { recognizer = it }
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
    @Suppress("UNUSED_PARAMETER")
    suspend fun warm(context: Context, biasPrompt: String? = null) {
        if (!ParakeetModelManager.isReady(context)) return
        loadLock.withLock {
            if (recognizer != null) return
            val t0 = System.nanoTime()
            recognizer = withContext(Dispatchers.IO) { buildRecognizer(context) }
            Log.i("LocalParakeetStt", "warm load=${(System.nanoTime() - t0) / 1_000_000}ms")
        }
    }
}
