package com.voicerewriter

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.whispercpp.whisper.WhisperCpuConfig
import java.io.File
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
 * NOTE on [biasPrompt] / hotwords: whisper biases decoding via initial_prompt; transducers do
 * it via sherpa "hotwords", which for Parakeet's BPE tokenizer need modelingUnit="bpe" + a BPE
 * vocab file — not shipped in the sherpa-onnx int8 export we download. We ship our own
 * (`assets/parakeet_bpe.vocab`, generated offline from the original NVIDIA checkpoint's
 * SentencePiece tokenizer and verified token-for-token against the shipped tokens.txt).
 *
 * That's necessary but not sufficient: hotwords also require switching decodingMethod from
 * "greedy_search" to "modified_beam_search", and that decode path has an **open upstream bug**
 * on NeMo-TDT models (k2-fsa/sherpa-onnx#3267) — roughly 1 in 5 requests come back as a
 * hallucinated "Yeah." or empty text, vs. greedy_search which is unaffected. So this is gated
 * behind [Settings.parakeetHotwordsExperimental] (off by default) rather than replacing greedy
 * decoding outright. Do not default it on until that upstream issue is resolved and this has
 * been eval'd against real dictation audio — the failure mode is silent (wrong/blank text), not
 * a crash, which is worse.
 */
object LocalParakeetStt {

    private const val HOTWORDS_SCORE = 2.0f
    private const val BPE_VOCAB_ASSET = "parakeet_bpe.vocab"
    private const val MAX_HOTWORDS = 50

    private val loadLock = Mutex()
    @Volatile private var recognizer: OfflineRecognizer? = null

    // Identifies which config the cached [recognizer] was built with, so a mid-session toggle
    // of the experimental flag (or a vocab change) rebuilds it instead of silently reusing a
    // stale greedy/hotwords recognizer.
    @Volatile private var recognizerKey: String? = null

    private fun bpeVocabPath(context: Context): String {
        val out = File(context.filesDir, BPE_VOCAB_ASSET)
        if (!out.exists()) {
            context.assets.open(BPE_VOCAB_ASSET).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        return out.absolutePath
    }

    /**
     * [biasPrompt] is the same "Glossary: a, b, c." string built for Whisper's initial_prompt
     * (see [textproc.VocabCorrector.biasPrompt]). Reparsed here into one term per line — the
     * format sherpa's hotwords file wants — rather than threading a second vocab-shaped
     * parameter through every call site for a single consumer.
     */
    private fun hotwordsFromBiasPrompt(biasPrompt: String?): List<String> {
        if (biasPrompt.isNullOrBlank()) return emptyList()
        val body = biasPrompt.removePrefix("Glossary: ").removeSuffix(".")
        return body.split(", ")
            .map { it.trim().lowercase() } // Parakeet's tokenizer/output is lowercase; match it.
            .filter { it.isNotEmpty() }
            .take(MAX_HOTWORDS)
    }

    /** Writes the hotwords file for this dictation, or null (and clears any stale file) if there's nothing to bias. */
    private fun writeHotwordsFile(context: Context, terms: List<String>): File? {
        val out = File(context.filesDir, "parakeet_hotwords.txt")
        if (terms.isEmpty()) {
            out.delete()
            return null
        }
        out.writeText(terms.joinToString("\n"))
        return out
    }

    private fun buildRecognizer(context: Context, hotwords: File?): OfflineRecognizer {
        val model = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = ParakeetModelManager.encoderPath(context),
                decoder = ParakeetModelManager.decoderPath(context),
                joiner = ParakeetModelManager.joinerPath(context),
            ),
            tokens = ParakeetModelManager.tokensPath(context),
            numThreads = WhisperCpuConfig.preferredThreadCount, // same 2..4 core budget as whisper
            modelType = "nemo_transducer",
            modelingUnit = if (hotwords != null) "bpe" else "",
            bpeVocab = if (hotwords != null) bpeVocabPath(context) else "",
            debug = false,
        )
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = model,
            decodingMethod = if (hotwords != null) "modified_beam_search" else "greedy_search",
            hotwordsFile = hotwords?.absolutePath.orEmpty(),
            hotwordsScore = HOTWORDS_SCORE,
        )
        return OfflineRecognizer(config = config)
    }

    /** Transcribe [samples] (16 kHz mono, normalized -1..1). [biasPrompt] is the shared vocab glossary (see class note). */
    suspend fun transcribe(context: Context, settings: Settings, samples: FloatArray, biasPrompt: String? = null): String {
        if (!ParakeetModelManager.isReady(context)) {
            throw IllegalStateException("Parakeet model not downloaded. Open Settings → Voice → Download model.")
        }
        val terms = if (settings.parakeetHotwordsExperimental) hotwordsFromBiasPrompt(biasPrompt) else emptyList()
        val hotwords = writeHotwordsFile(context, terms)
        val key = hotwords?.let { "hotwords:" + terms.joinToString(",") }
        val seconds = samples.size / AudioRecorder.SAMPLE_RATE.toFloat()
        val t0 = System.nanoTime()
        val rec = loadLock.withLock {
            val cached = recognizer
            if (cached != null && recognizerKey == key) {
                cached
            } else {
                withContext(Dispatchers.IO) { buildRecognizer(context, hotwords) }
                    .also { recognizer = it; recognizerKey = key }
            }
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
                "infer=${(tDone - tLoaded) / 1_000_000}ms threads=${WhisperCpuConfig.preferredThreadCount} " +
                "hotwords=${terms.size}",
        )
        return text.trim()
    }

    /** Preload the recognizer ahead of the first dictation (e.g. on service start). Best-effort, greedy-only (no vocab yet at this point). */
    @Suppress("UNUSED_PARAMETER")
    suspend fun warm(context: Context, biasPrompt: String? = null) {
        if (!ParakeetModelManager.isReady(context)) return
        loadLock.withLock {
            if (recognizer != null) return
            val t0 = System.nanoTime()
            recognizer = withContext(Dispatchers.IO) { buildRecognizer(context, hotwords = null) }
            recognizerKey = null
            Log.i("LocalParakeetStt", "warm load=${(System.nanoTime() - t0) / 1_000_000}ms")
        }
    }
}
