package com.voicerewriter

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages on-device Whisper model files (ggml) for whisper.cpp: a registry of
 * multilingual sizes, one-time download with progress, and readiness checks.
 *
 * `small` (~488MB) is accurate but heavy/slow on phones (memory + CPU), so the
 * default is `base` — a good accuracy/speed balance; `tiny` is the fastest.
 */
object WhisperModelManager {

    data class WhisperModel(
        val id: String,
        val label: String,
        val fileName: String,
        val url: String,
        val sizeLabel: String,
        /**
         * The language whisper.cpp must decode with, for a fine-tune that maps speech in one
         * language onto *another script*. Null for the stock multilingual builds, which decode
         * in whatever language the user picked.
         *
         * The Hinglish model is trained to emit romanized Hindi, and its own model card is
         * explicit that it must be decoded as `en`: forcing `hi` makes it output Devanagari
         * again and the romanization collapses. So the user's dictation language cannot be the
         * thing we hand the decoder, and this field is where that divergence lives.
         */
        val decodeLanguage: String? = null,
    ) {
        /**
         * True for a transliterating fine-tune — one whose output is neither the stock
         * language's script nor English prose. Two things key off it:
         *
         *  1. The English-shaped deterministic cleanup must not run (see RewriteActivity):
         *     romanized Hinglish looks English enough to slip past a language check, but
         *     sentence-casing and filler-hunting it degrades correct output.
         *  2. The vocab glossary must not be used as whisper's `initial_prompt` — measured
         *     2026-10-06, it failed to fix the name it was given *and* broke `office mein`
         *     into `officemen`. An English glossary drags this decoder toward English word
         *     shapes. Post-hoc [VocabCorrector.correct] still applies and still works.
         */
        val isTransliterating: Boolean get() = decodeLanguage != null
    }

    private fun hf(file: String) = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"

    const val HINGLISH_MODEL = "hinglish"

    val MODELS = listOf(
        WhisperModel("tiny", "Tiny (fastest)", "ggml-tiny.bin", hf("ggml-tiny.bin"), "~75MB"),
        WhisperModel("base", "Base (balanced)", "ggml-base.bin", hf("ggml-base.bin"), "~142MB"),
        WhisperModel("small", "Small (most accurate)", "ggml-small.bin", hf("ggml-small.bin"), "~488MB"),
        // Oriserve/Whisper-Hindi2Hinglish-Swift (Apache 2.0), a whisper-base fine-tune that
        // transcribes Hindi and code-switched Hindi-English straight into Roman script:
        // "Kal office mein meeting thi", not "कल ऑफिस में मीटिंग थी". Our own ggml conversion
        // of their safetensors, quantized q5_1 — not a third-party ggml build, of which there
        // are several on the Hub with no provenance.
        //
        // Measured on a Galaxy S25 Ultra: 57MB on disk, 197MB peak RSS, 47-113ms to load,
        // ~1.3s for a real 4-second take. The 0.8B sibling (Apex) was also converted and
        // measured, and was rejected: 25s per take on the same phone for byte-identical output.
        WhisperModel(
            id = HINGLISH_MODEL,
            // Labelled beta on purpose. It has been tested on one speaker, and its weak spots
            // are known: very short takes, and Hinglish having no settled spelling, so
            // "yeh"/"yah" are both defensible and the model will not always pick yours.
            label = "Hinglish (beta)",
            fileName = "ggml-hinglish-swift-q5_1.bin",
            url = "https://huggingface.co/rohitag13/whisper-hindi2hinglish-swift-GGUF/resolve/main/ggml-hinglish-swift-q5_1.bin",
            sizeLabel = "~57MB · Hindi + English, Roman script",
            decodeLanguage = "en",
        ),
    )

    /**
     * The stock multilingual size ladder, without the language-specific fine-tunes.
     *
     * Use this wherever the question is "which general-purpose Whisper should we fall back to",
     * because a transliterating model is not a substitute for one: picking the Hinglish model
     * for a Tamil dictation, or for English, would silently produce the wrong thing. [MODELS]
     * stays the full set, so the download whitelist and the Settings picker see everything.
     */
    val GENERIC: List<WhisperModel> get() = MODELS.filter { !it.isTransliterating }

    const val DEFAULT_MODEL = "tiny"
    private const val MIN_VALID_BYTES = 30L * 1024 * 1024


    fun model(id: String): WhisperModel =
        MODELS.firstOrNull { it.id == id } ?: MODELS.first { it.id == DEFAULT_MODEL }

    fun modelFile(context: Context, id: String): File =
        File(File(context.filesDir, "models").apply { mkdirs() }, model(id).fileName)

    fun isReady(context: Context, id: String): Boolean =
        modelFile(context, id).let { it.exists() && it.length() > MIN_VALID_BYTES }

    /**
     * Delete model [id] from disk, freeing its bytes. Returns bytes reclaimed (0 if it wasn't
     * there). Callers are responsible for not deleting the model currently selected — the
     * Settings UI only offers this on a downloaded-but-inactive model, per issue #53.
     */
    suspend fun delete(context: Context, id: String): Long = withContext(Dispatchers.IO) {
        val freed = ModelDownloader.deleteWithSidecars(modelFile(context, id))
        // The download flows landed in #55, so a stale "done" can now outlive the file it
        // referred to. Clear it here rather than leaving onboarding to trust it.
        if (_downloadState.value == "done") _downloadState.value = "idle"
        freed
    }

    /** Download model [id], reporting progress 0f..1f. Throws on network error. */
    suspend fun download(context: Context, id: String, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            if (isReady(context, id)) { onProgress(1f); return@withContext }
            ModelDownloader.fetch(model(id).url, modelFile(context, id), onProgress)
        }

    // --- Lifecycle-independent download, mirroring [ParakeetModelManager] ---
    // Onboarding now picks its speech engine per device ([DeviceFit]), so a Whisper size can be
    // the model the first-run flow is waiting on. That flow observes state rather than owning
    // the coroutine, because the Activity closing must not cancel a half-finished download.

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloadState = MutableStateFlow("idle") // "idle" | "downloading" | "done" | "error"
    val downloadState: StateFlow<String> = _downloadState.asStateFlow()
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()
    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    /** Idempotent: no-ops if [id] is already downloaded or a download is already in flight. */
    fun ensureDownloading(context: Context, id: String) {
        if (_downloadState.value == "downloading") return
        val appContext = context.applicationContext
        if (isReady(appContext, id)) { _downloadState.value = "done"; return }
        _downloadState.value = "downloading"; _downloadProgress.value = 0f; _downloadError.value = null
        managerScope.launch {
            try {
                download(appContext, id) { p -> _downloadProgress.value = p }
                _downloadState.value = "done"
            } catch (t: Throwable) {
                Log.w("WhisperModel", "download failed", t)
                _downloadError.value = t.message ?: "Download failed"
                _downloadState.value = "error"
            }
        }
    }
}
