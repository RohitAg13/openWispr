package com.voicerewriter

import android.content.Context
import kotlinx.coroutines.Dispatchers
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

    /** Download model [id], reporting progress 0f..1f. Throws on network error. */
    suspend fun download(context: Context, id: String, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            if (isReady(context, id)) { onProgress(1f); return@withContext }
            ModelDownloader.fetch(model(id).url, modelFile(context, id), onProgress)
        }
}
