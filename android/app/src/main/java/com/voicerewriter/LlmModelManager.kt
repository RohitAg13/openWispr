package com.voicerewriter

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages on-device LLM model files (GGUF) for llama.cpp: a small registry of
 * supported small models, one-time download with progress, and readiness checks.
 * Mirrors [WhisperModelManager]; downloads to a `.part` file and renames on success.
 */
object LlmModelManager {

    data class LlmModel(
        val id: String,
        val label: String,
        val fileName: String,
        val url: String,
        val sizeLabel: String,
    )

    val MODELS = listOf(
        LlmModel(
            id = "gemma3-270m",
            label = "Gemma 3 270M",
            fileName = "gemma-3-270m-qat-Q4_0.gguf",
            url = "https://huggingface.co/ggml-org/gemma-3-270m-qat-GGUF/resolve/main/gemma-3-270m-qat-Q4_0.gguf",
            sizeLabel = "~241MB",
        ),
        LlmModel(
            id = "qwen3-0.6b",
            label = "Qwen3 0.6B",
            fileName = "Qwen3-0.6B-Q8_0.gguf",
            url = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
            sizeLabel = "~639MB",
        ),
        // Fine-tuned for dictation cleanup (Phase 2). Trained on the FINETUNE prompt+tone;
        // LocalLlmEngine feeds it that exact prompt shape when selected.
        LlmModel(
            id = FINETUNE_MODEL_ID,
            label = "OpenWispr Cleanup (Qwen3 0.6B)",
            fileName = "openwispr-cleanup-qwen3-0.6b-Q4_K_M.gguf",
            url = "https://huggingface.co/rohitag13/openwispr-cleanup-qwen3-0.6b-GGUF/resolve/main/qwen3-0.6b.Q4_K_M.gguf",
            sizeLabel = "~397MB",
        ),
    )

    const val DEFAULT_MODEL = "gemma3-270m"
    /** The dictation-cleanup fine-tune — gets the training-time prompt in LocalLlmEngine. */
    const val FINETUNE_MODEL_ID = "openwispr-qwen3-0.6b"
    private const val MIN_VALID_BYTES = 50L * 1024 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun model(id: String): LlmModel = MODELS.firstOrNull { it.id == id } ?: MODELS.first()

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
