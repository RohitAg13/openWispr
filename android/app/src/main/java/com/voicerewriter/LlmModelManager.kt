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
        val recommended: Boolean = false,
    )

    val MODELS = listOf(
        // Recommended on-device polish model: fine-tuned for dictation cleanup (Phase 2).
        // Trained on the FINETUNE prompt+tone; LocalLlmEngine feeds it that exact prompt
        // shape when selected. Listed first and made the local provider's default.
        LlmModel(
            id = FINETUNE_MODEL_ID,
            label = "OpenWispr Cleanup (Qwen3 0.6B)",
            fileName = "openwispr-cleanup-qwen3-0.6b-Q4_K_M.gguf",
            url = "https://huggingface.co/rohitag13/openwispr-cleanup-qwen3-0.6b-GGUF/resolve/main/qwen3-0.6b.Q4_K_M.gguf",
            sizeLabel = "~397MB",
            recommended = true,
        ),
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
    )

    /** The dictation-cleanup fine-tune — gets the training-time prompt in LocalLlmEngine. */
    const val FINETUNE_MODEL_ID = "openwispr-qwen3-0.6b"
    const val DEFAULT_MODEL = FINETUNE_MODEL_ID
    private const val MIN_VALID_BYTES = 50L * 1024 * 1024


    fun model(id: String): LlmModel = MODELS.firstOrNull { it.id == id } ?: MODELS.first()

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

    // --- Lifecycle-independent download — see ParakeetModelManager's for rationale. ---

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloadState = MutableStateFlow("idle") // "idle" | "downloading" | "done" | "error"
    val downloadState: StateFlow<String> = _downloadState.asStateFlow()
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()
    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    /** Idempotent: no-ops if already downloaded or a download is already in flight. */
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
                Log.w("LlmModel", "download failed", t)
                _downloadError.value = t.message ?: "Download failed"
                _downloadState.value = "error"
            }
        }
    }
}
