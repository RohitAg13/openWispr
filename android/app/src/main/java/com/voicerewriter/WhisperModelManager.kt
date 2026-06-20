package com.voicerewriter

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

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

    const val DEFAULT_MODEL = "base"
    private const val MIN_VALID_BYTES = 30L * 1024 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun model(id: String): WhisperModel =
        MODELS.firstOrNull { it.id == id } ?: MODELS.first { it.id == DEFAULT_MODEL }

    fun modelFile(context: Context, id: String): File =
        File(File(context.filesDir, "models").apply { mkdirs() }, model(id).fileName)

    fun isReady(context: Context, id: String): Boolean =
        modelFile(context, id).let { it.exists() && it.length() > MIN_VALID_BYTES }

    /** Download model [id], reporting progress 0f..1f. Throws on network error. */
    suspend fun download(context: Context, id: String, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val target = modelFile(context, id)
            if (isReady(context, id)) { onProgress(1f); return@withContext }
            val part = File(target.parentFile, "${target.name}.part")
            part.delete()

            val request = Request.Builder().url(model(id).url).build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("Model download failed: HTTP ${res.code}")
                val body = res.body ?: throw IllegalStateException("Empty model response")
                val total = body.contentLength().takeIf { it > 0 }
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(1 shl 16)
                        var downloaded = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            if (total != null) {
                                val pct = ((downloaded * 100) / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                            }
                        }
                    }
                }
            }
            if (part.length() < MIN_VALID_BYTES) { part.delete(); throw IllegalStateException("Downloaded model looks incomplete.") }
            if (!part.renameTo(target)) throw IllegalStateException("Couldn't finalize the model file.")
            onProgress(1f)
        }
}
