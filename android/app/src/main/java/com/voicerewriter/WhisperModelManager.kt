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
        /** Exact download size, from the HF content-length. Used for the readiness floor. */
        val sizeBytes: Long,
        /** The unquantized blob this build replaces, if any — reclaimed by [reclaimSuperseded]. */
        val legacyFileName: String? = null,
    )

    private fun hf(file: String) = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"

    // Quantized (q5) builds, from the same upstream repo as the f16 ones they replace. Whisper
    // ships these itself; we were simply downloading the full-precision weights. The saving is
    // large and one-sided — `small` goes 488MB -> 181MB, and `medium`, which used to be far too
    // big for a phone, now costs about what our old `small` did. Quality loss at q5 is slight;
    // the gap between `small` and `medium` is not, especially outside English.
    val MODELS = listOf(
        WhisperModel(
            "tiny", "Tiny (fastest)", "ggml-tiny-q5_1.bin", hf("ggml-tiny-q5_1.bin"),
            "~31MB", 32_152_673L, legacyFileName = "ggml-tiny.bin",
        ),
        WhisperModel(
            "base", "Base (balanced)", "ggml-base-q5_1.bin", hf("ggml-base-q5_1.bin"),
            "~57MB", 59_707_625L, legacyFileName = "ggml-base.bin",
        ),
        WhisperModel(
            "small", "Small (most accurate)", "ggml-small-q5_1.bin", hf("ggml-small-q5_1.bin"),
            "~181MB", 190_085_487L, legacyFileName = "ggml-small.bin",
        ),
        // Not offered by [DeviceFit]: it is a deliberate choice, not a recommendation. Whisper's
        // non-English accuracy climbs steeply from small to medium, which matters now that the
        // dictation language is selectable, but it is markedly slower on a phone.
        WhisperModel(
            "medium", "Medium (best for other languages, slow)", "ggml-medium-q5_0.bin",
            hf("ggml-medium-q5_0.bin"), "~514MB", 539_212_467L,
        ),
    )

    const val DEFAULT_MODEL = "tiny"


    fun model(id: String): WhisperModel =
        MODELS.firstOrNull { it.id == id } ?: MODELS.first { it.id == DEFAULT_MODEL }

    fun modelFile(context: Context, id: String): File =
        File(File(context.filesDir, "models").apply { mkdirs() }, model(id).fileName)

    /**
     * A truncated download is not a model. The floor is per-model now rather than one global
     * 30MB constant: quantized `tiny` is only 31MB, so a single constant would have sat within
     * a megabyte of a real file and called a half-finished download ready. 90% leaves room for
     * upstream re-quantizing a build slightly without stranding everyone who has it.
     */
    fun isReady(context: Context, id: String): Boolean {
        val m = model(id)
        val f = File(File(context.filesDir, "models"), m.fileName)
        return f.exists() && f.length() >= m.sizeBytes / 10 * 9
    }

    /**
     * Delete the unquantized weights that the q5 builds replaced, for users upgrading from a
     * version that downloaded them. Only removes a legacy blob once its replacement is fully
     * downloaded, so an interrupted upgrade never leaves someone with no usable model at all.
     * Returns bytes reclaimed. Safe to call repeatedly; a no-op on a fresh install.
     *
     * Without this the old files would be exactly the orphans described in #60: invisible to
     * the UI, untouched by [ModelDownloader.sweepOrphans] (which only sweeps sidecars), and
     * unreachable by any delete the user can perform — up to 705MB of them.
     */
    suspend fun reclaimSuperseded(context: Context): Long = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "models")
        var freed = 0L
        for (m in MODELS) {
            val legacy = m.legacyFileName ?: continue
            if (!isReady(context, m.id)) continue
            freed += ModelDownloader.deleteWithSidecars(File(dir, legacy))
        }
        if (freed > 0) Log.i("WhisperModel", "reclaimed ${freed / (1024 * 1024)}MB of superseded weights")
        freed
    }

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
