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
 * Manages the on-device NVIDIA Parakeet-TDT-0.6b-v2 (int8) transducer bundle for sherpa-onnx
 * — a faster, more accurate alternative to whisper.cpp (on-device S25 STT p50 ~238ms / p95
 * ~491ms, prose WER below whisper-small). The bundle is four files (encoder/decoder/joiner
 * onnx + tokens.txt); the int8 encoder dominates at ~622MB, so the whole set is ~631MB.
 *
 * Same shape as [WhisperModelManager] (download with progress, readiness check) but the model
 * is a directory of files rather than a single ggml blob.
 */
object ParakeetModelManager {

    const val MODEL_ID = "parakeet"
    const val LABEL = "Parakeet (fastest + most accurate)"
    const val SIZE_LABEL = "~631MB"

    const val ENCODER = "encoder.int8.onnx"

    // Files inside the bundle dir; names match the sherpa-onnx int8 release.
    private val FILES = listOf(ENCODER, "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")

    /** Small files first so the long encoder fetch isn't holding up a usable-looking set. */
    private val FILES_IN_FETCH_ORDER =
        listOf("tokens.txt", "decoder.int8.onnx", "joiner.int8.onnx", ENCODER)

    // Encoder is the big one; a floor here catches a truncated file that predates the
    // downloader's exact-length and checksum verification.
    private const val MIN_ENCODER_BYTES = 400L * 1024 * 1024

    private fun hf(file: String) =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main/$file"

    /** The bundle's filenames, so cleanup can tell our leftovers from a retired model's. */
    fun bundleFiles(): List<String> = FILES

    /** Directory holding the bundle: filesDir/models/parakeet/. */
    fun modelDir(context: Context): File =
        File(File(context.filesDir, "models"), MODEL_ID).apply { mkdirs() }

    fun file(context: Context, name: String): File = File(modelDir(context), name)

    fun encoderPath(context: Context) = file(context, ENCODER).absolutePath
    fun decoderPath(context: Context) = file(context, "decoder.int8.onnx").absolutePath
    fun joinerPath(context: Context) = file(context, "joiner.int8.onnx").absolutePath
    fun tokensPath(context: Context) = file(context, "tokens.txt").absolutePath

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        if (FILES.any { !File(dir, it).exists() }) return false
        return File(dir, ENCODER).length() > MIN_ENCODER_BYTES
    }

    /**
     * Download the four bundle files, reporting overall progress 0f..1f weighted by byte size
     * (the encoder is ~98% of the bytes). Throws on network error.
     */
    suspend fun download(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (isReady(context)) { onProgress(1f); return@withContext }
        val dir = modelDir(context)
        // Small files first (cheap), then the encoder, which is ~98% of the bytes and the only
        // one worth reporting progress for. Each resumes independently via ModelDownloader.
        for (name in FILES_IN_FETCH_ORDER) {
            val target = File(dir, name)
            if (target.exists() && (name != ENCODER || target.length() > MIN_ENCODER_BYTES)) continue
            ModelDownloader.fetch(hf(name), target) { p -> if (name == ENCODER) onProgress(p) }
        }
        if (!isReady(context)) throw IllegalStateException("Parakeet bundle looks incomplete after download.")
        onProgress(1f)
    }

    // --- Lifecycle-independent download, so it survives whichever screen started it ---
    // (e.g. onboarding finishing and closing its Activity, which cancels any composition-
    // scoped coroutine). Any screen can observe [downloadState]/[downloadProgress] to show
    // live progress without owning the download itself.

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloadState = MutableStateFlow("idle") // "idle" | "downloading" | "done" | "error"
    val downloadState: StateFlow<String> = _downloadState.asStateFlow()
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()
    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    /** Idempotent: no-ops if already downloaded or a download is already in flight. */
    fun ensureDownloading(context: Context) {
        if (_downloadState.value == "downloading") return
        val appContext = context.applicationContext
        if (isReady(appContext)) { _downloadState.value = "done"; return }
        _downloadState.value = "downloading"; _downloadProgress.value = 0f; _downloadError.value = null
        managerScope.launch {
            try {
                download(appContext) { p -> _downloadProgress.value = p }
                _downloadState.value = "done"
            } catch (t: Throwable) {
                Log.w("ParakeetModel", "download failed", t)
                _downloadError.value = t.message ?: "Download failed"
                _downloadState.value = "error"
            }
        }
    }
}
