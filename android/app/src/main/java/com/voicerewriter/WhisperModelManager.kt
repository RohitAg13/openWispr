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
    )

    private fun hf(file: String) = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"

    val MODELS = listOf(
        WhisperModel("tiny", "Tiny (fastest)", "ggml-tiny.bin", hf("ggml-tiny.bin"), "~75MB"),
        WhisperModel("base", "Base (balanced)", "ggml-base.bin", hf("ggml-base.bin"), "~142MB"),
        WhisperModel("small", "Small (most accurate)", "ggml-small.bin", hf("ggml-small.bin"), "~488MB"),
    )

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
