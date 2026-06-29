package com.voicerewriter

import ai.mlc.mlcllm.MLCEngine
import ai.mlc.mlcllm.OpenAIProtocol
import android.content.Context
import android.util.Log
import com.voicerewriter.textproc.AppContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * MLC-LLM GPU (Adreno / OpenCL) decode path — the GPU analogue of [LocalLlmEngine].
 *
 * Mirrors `LocalLlmEngine.streamWithPrompt(context, settings, prompt, text): Flow<String>` so the
 * existing dispatch (RewriteActivity.streamFor, EvalService) can route to it by selecting
 * provider == "local-gpu" without any other change. Reuses the same RewriteEngine prompt builders.
 *
 * Unlike the llama.cpp path (which reloads the GGUF per call — ~free because it mmaps), the MLC
 * engine uploads weights to the GPU, so we load ONCE and `reset()` between rewrites. Per-call cost
 * is therefore prefill+decode only — the same thing the CPU 2862ms/call number measures.
 */
object LocalGpuLlmEngine {
    private const val TAG = "LocalGpuLlmEngine"

    private val mutex = Mutex()
    private var engine: MLCEngine? = null
    private var loadedModelId: String? = null

    // Last generation's on-device throughput, e.g. "prefill: X tok/s, decode: Y tok/s".
    @Volatile var lastStats: String = ""
        private set

    private fun predictLengthFor(text: String): Int {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        return (words * 2 + 48).coerceIn(48, 512)
    }

    /** (model_id, model_lib) from the bundled mlc-app-config.json (merged from the mlc4j module). */
    private fun appConfig(context: Context): Pair<String, String> {
        val json = context.assets.open("mlc-app-config.json").bufferedReader().use { it.readText() }
        val m = JSONObject(json).getJSONArray("model_list").getJSONObject(0)
        return m.getString("model_id") to m.getString("model_lib")
    }

    /** Ensure the engine exists and the model is loaded (once). Caller holds [mutex]. */
    private fun ensureLoaded(context: Context): String {
        val (modelId, modelLib) = appConfig(context)
        val eng = engine ?: MLCEngine().also { engine = it }
        if (loadedModelId != modelId) {
            // Weights live in the app external files dir under <model_id>/ (pushed via adb for the
            // eval; or downloaded/extracted in production).
            val modelDir = java.io.File(context.getExternalFilesDir(""), modelId)
            check(modelDir.exists()) {
                "MLC model dir not found: ${modelDir.absolutePath} (push weights there)."
            }
            eng.unload()
            eng.reload(modelDir.absolutePath, modelLib)
            loadedModelId = modelId
            Log.i(TAG, "loaded MLC model $modelId lib=$modelLib from ${modelDir.absolutePath}")
        }
        return modelId
    }

    fun streamWithPrompt(context: Context, settings: Settings, prompt: String, text: String): Flow<String> = flow {
        val isFinetune = settings.model == LlmModelManager.FINETUNE_MODEL_ID
        val system: String
        val user: String
        if (isFinetune) {
            val category = AppContext.categoryFor(OpenWisprAccessibilityService.lastHostPackage, text).key
            system = RewriteEngine.buildFinetuneSystemPrompt(category)
            user = text
        } else {
            system = RewriteEngine.buildLocalSystemPrompt(settings)
            user = RewriteEngine.buildLocalUserContent(prompt, text) +
                if (settings.model.startsWith("qwen")) " /no_think" else ""
        }

        val acc = StringBuilder()
        var stopped = false

        mutex.withLock {
            ensureLoaded(context.applicationContext)
            val eng = engine!!
            eng.reset() // independent rewrite — no chat history carryover

            val messages = listOf(
                OpenAIProtocol.ChatCompletionMessage(
                    role = OpenAIProtocol.ChatCompletionRole.system, content = system
                ),
                OpenAIProtocol.ChatCompletionMessage(
                    role = OpenAIProtocol.ChatCompletionRole.user, content = user
                ),
            )
            Log.i(TAG, "model=${settings.model} userLen=${user.length} userTail='${user.takeLast(160)}'")

            val channel = eng.chat.completions.create(
                messages = messages,
                temperature = 0.3f,
                max_tokens = predictLengthFor(text),
                stream_options = OpenAIProtocol.StreamOptions(include_usage = true),
            )
            for (response in channel) {
                val usage = response.usage
                if (usage != null) {
                    lastStats = usage.extra?.asTextLabel().orEmpty()
                    Log.i(TAG, "stats: $lastStats")
                    continue
                }
                if (stopped || response.choices.isEmpty()) continue
                val tok = response.choices[0].delta.content?.asText().orEmpty()
                if (tok.isEmpty()) continue
                acc.append(tok)
                if (acc.contains("<<<") || acc.contains("TEXT>>>") || RewriteEngine.looksRepeating(acc)) {
                    // Stop emitting; keep draining the channel so it closes (no public abort API).
                    stopped = true
                } else {
                    emit(tok)
                }
            }
        }
        Log.i(TAG, "raw='${acc.toString().take(240)}'")
    }
}
