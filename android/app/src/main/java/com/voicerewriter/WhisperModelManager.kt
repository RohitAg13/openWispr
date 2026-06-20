package com.voicerewriter

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages the on-device Whisper model file: one-time download (multilingual
 * `small`, ~488MB) into app storage, with progress, and a readiness check.
 * Downloads to a `.part` file and renames on success so a present final file
 * always means a complete model.
 */
object WhisperModelManager {

    // Multilingual `small` ggml model. `ggml-small-q5_1.bin` (~190MB) is a lighter
    // near-equal-accuracy swap if the full download is too big.
    const val MODEL_FILE = "ggml-small.bin"
    private const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
    private const val MIN_VALID_BYTES = 100L * 1024 * 1024 // sanity floor

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models").apply { mkdirs() }, MODEL_FILE)

    fun isReady(context: Context): Boolean =
        modelFile(context).let { it.exists() && it.length() > MIN_VALID_BYTES }

    /**
     * Download the model, reporting progress 0f..1f. Resumes nothing — a partial
     * `.part` from a previous failure is overwritten. Throws on network error.
     */
    suspend fun download(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = modelFile(context)
        if (isReady(context)) { onProgress(1f); return@withContext }
        val part = File(target.parentFile, "$MODEL_FILE.part")
        part.delete()

        val request = Request.Builder().url(MODEL_URL).build()
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("Model download failed: HTTP ${res.code}")
            val body = res.body ?: throw IllegalStateException("Empty model response")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    var downloaded = 0L
                    var lastReported = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total != null) {
                            val pct = ((downloaded * 100) / total).toInt()
                            if (pct != lastReported) { lastReported = pct; onProgress(pct / 100f) }
                        }
                    }
                }
            }
        }
        if (part.length() < MIN_VALID_BYTES) {
            part.delete()
            throw IllegalStateException("Downloaded model looks incomplete.")
        }
        if (!part.renameTo(target)) throw IllegalStateException("Couldn't finalize the model file.")
        onProgress(1f)
    }
}
