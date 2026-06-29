package com.voicerewriter

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

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

    // Files inside the bundle dir; names match the sherpa-onnx int8 release.
    private val FILES = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
    // Encoder is the big one; guard against truncated downloads.
    private const val MIN_ENCODER_BYTES = 400L * 1024 * 1024

    private fun hf(file: String) =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main/$file"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Directory holding the bundle: filesDir/models/parakeet/. */
    fun modelDir(context: Context): File =
        File(File(context.filesDir, "models"), MODEL_ID).apply { mkdirs() }

    fun file(context: Context, name: String): File = File(modelDir(context), name)

    fun encoderPath(context: Context) = file(context, "encoder.int8.onnx").absolutePath
    fun decoderPath(context: Context) = file(context, "decoder.int8.onnx").absolutePath
    fun joinerPath(context: Context) = file(context, "joiner.int8.onnx").absolutePath
    fun tokensPath(context: Context) = file(context, "tokens.txt").absolutePath

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        if (FILES.any { !File(dir, it).exists() }) return false
        return File(dir, "encoder.int8.onnx").length() > MIN_ENCODER_BYTES
    }

    /**
     * Download the four bundle files, reporting overall progress 0f..1f weighted by byte size
     * (the encoder is ~98% of the bytes). Throws on network error.
     */
    suspend fun download(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (isReady(context)) { onProgress(1f); return@withContext }
        val dir = modelDir(context)
        // Fetch the small files first (cheap), then the encoder with live progress.
        for (name in listOf("tokens.txt", "decoder.int8.onnx", "joiner.int8.onnx", "encoder.int8.onnx")) {
            val target = File(dir, name)
            if (target.exists() && (name != "encoder.int8.onnx" || target.length() > MIN_ENCODER_BYTES)) continue
            val part = File(dir, "$name.part").also { it.delete() }
            val isEncoder = name == "encoder.int8.onnx"
            client.newCall(Request.Builder().url(hf(name)).build()).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("Parakeet download ($name) failed: HTTP ${res.code}")
                val body = res.body ?: throw IllegalStateException("Empty response for $name")
                val total = body.contentLength().takeIf { it > 0 }
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(1 shl 16)
                        var downloaded = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            output.write(buf, 0, n); downloaded += n
                            if (isEncoder && total != null) {
                                val pct = ((downloaded * 100) / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                            }
                        }
                    }
                }
            }
            if (!part.renameTo(target)) throw IllegalStateException("Couldn't finalize $name")
        }
        if (!isReady(context)) throw IllegalStateException("Parakeet bundle looks incomplete after download.")
        onProgress(1f)
    }
}
