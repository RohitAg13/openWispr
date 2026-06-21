package com.voicerewriter

import android.content.Context
import android.util.Log
import com.voicerewriter.textproc.AppContext
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * On-device LLM rewrite via llama.cpp (vendored `:llm` / ARM aichat). Each rewrite
 * loads the model fresh, sets the system prompt, and streams tokens. Same role as
 * the cloud path in [RewriteEngine] — picked by the LLM provider.
 *
 * We reload per call on purpose: the native engine accumulates chat history, but a
 * rewrite must be independent of previous ones. A mutex serializes the lifecycle.
 */
object LocalLlmEngine {

    private val mutex = Mutex()

    /** Cap output to roughly the input size so a looping model can't ramble. */
    private fun predictLengthFor(text: String): Int {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        return (words * 2 + 48).coerceIn(48, 512)
    }

    /** Stream the rewrite for [prompt] applied to [text]. Mirrors RewriteEngine.streamWithPrompt. */
    fun streamWithPrompt(context: Context, settings: Settings, prompt: String, text: String): Flow<String> = flow {
        val file = LlmModelManager.modelFile(context, settings.model)
        if (!file.exists()) {
            throw IllegalStateException("On-device model not downloaded. Open Settings → On-device LLM → Download.")
        }
        val isFinetune = settings.model == LlmModelManager.FINETUNE_MODEL_ID
        val system: String
        val user: String
        if (isFinetune) {
            // The fine-tune was trained on its own SYSTEM (with /no_think) + per-app tone,
            // and a bare transcript as the user turn. Feed exactly that — anything else
            // (the lean prompt, a DICTATION_PROMPT prefix) is off-distribution.
            val category = AppContext.categoryFor(OpenWisprAccessibilityService.lastHostPackage, text).key
            system = RewriteEngine.buildFinetuneSystemPrompt(category)
            user = text
        } else {
            // Lean prompt + marker-free user turn: tiny models loop on the full guardrails.
            system = RewriteEngine.buildLocalSystemPrompt(settings)
            // Qwen3 has a reasoning mode on by default; "/no_think" disables it so we get
            // the answer directly instead of (capped) chain-of-thought.
            user = RewriteEngine.buildLocalUserContent(prompt, text) +
                if (settings.model.startsWith("qwen")) " /no_think" else ""
        }
        val engine = AiChat.getInferenceEngine(context.applicationContext)

        mutex.withLock {
            awaitReady(engine)
            // Fresh load every time → independent rewrites + known-good engine state.
            if (engine.state.value is InferenceEngine.State.ModelReady) {
                engine.cleanUp()
                awaitInitialized(engine)
            }
            engine.loadModel(file.absolutePath)
            engine.setSystemPrompt(system) // must be right after load
        }

        Log.i("LocalLlmEngine", "model=${settings.model} userLen=${user.length} userTail='${user.takeLast(160)}'")
        // Suppress emission once the model starts repeating, but DON'T cancel the
        // upstream — cancelling surfaces as a CancellationException and the caller
        // would never see normal completion. Let generation finish (it's capped).
        val acc = StringBuilder()
        var stopped = false
        emitAll(
            engine.sendUserPrompt(user, predictLength = predictLengthFor(text))
                .transform { token ->
                    if (stopped) return@transform
                    acc.append(token)
                    if (acc.contains("<<<") || acc.contains("TEXT>>>") || RewriteEngine.looksRepeating(acc)) {
                        stopped = true
                    } else {
                        emit(token)
                    }
                }
        )
        Log.i("LocalLlmEngine", "raw='${acc.toString().take(240)}'")
    }

    /** Wait until the engine is idle (initialized or a model is ready). */
    private suspend fun awaitReady(engine: InferenceEngine) {
        val s = engine.state.first {
            it is InferenceEngine.State.Initialized ||
                it is InferenceEngine.State.ModelReady ||
                it is InferenceEngine.State.Error
        }
        if (s is InferenceEngine.State.Error) throw s.exception
    }

    private suspend fun awaitInitialized(engine: InferenceEngine) {
        val s = engine.state.first {
            it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
        }
        if (s is InferenceEngine.State.Error) throw s.exception
    }
}
