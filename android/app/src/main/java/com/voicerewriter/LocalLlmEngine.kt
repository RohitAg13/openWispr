package com.voicerewriter

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * On-device LLM rewrite via llama.cpp (vendored `:llm` / ARM aichat). Loads a GGUF
 * model once, sets the (stable) system prompt, then streams tokens for each rewrite.
 * Same role as the cloud path in [RewriteEngine] — picked by the LLM provider.
 *
 * The native engine is a stateful singleton with a strict lifecycle, so a mutex
 * serializes load/reload; the system prompt only changes when settings change.
 */
object LocalLlmEngine {

    private val mutex = Mutex()
    @Volatile private var loadedPath: String? = null
    @Volatile private var loadedSystem: String? = null

    /** Cap output to roughly the input size so a looping model can't ramble. */
    private fun predictLengthFor(text: String): Int {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        return (words * 2 + 48).coerceIn(48, 512)
    }

    /** Stream the rewrite for [prompt] applied to [text]. Mirrors RewriteEngine.streamWithPrompt. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun streamWithPrompt(context: Context, settings: Settings, prompt: String, text: String): Flow<String> = flow {
        val file = LlmModelManager.modelFile(context, settings.model)
        if (!file.exists()) {
            throw IllegalStateException("On-device model not downloaded. Open Settings → On-device LLM → Download.")
        }
        // Lean prompt + marker-free user turn: tiny models loop on the full guardrails.
        val system = RewriteEngine.buildLocalSystemPrompt(settings)
        val user = RewriteEngine.buildLocalUserContent(prompt, text)
        val engine = AiChat.getInferenceEngine(context.applicationContext)

        mutex.withLock {
            awaitReady(engine)
            if (loadedPath != file.absolutePath || loadedSystem != system) {
                if (engine.state.value is InferenceEngine.State.ModelReady) {
                    engine.cleanUp()
                    awaitInitialized(engine)
                }
                engine.loadModel(file.absolutePath)
                engine.setSystemPrompt(system) // must be right after load
                loadedPath = file.absolutePath
                loadedSystem = system
            }
        }
        // Stop early once the model starts repeating its own output — tiny models
        // that don't emit a stop token otherwise loop until the length cap.
        val acc = StringBuilder()
        emitAll(
            engine.sendUserPrompt(user, predictLength = predictLengthFor(text))
                .transformWhile { token ->
                    acc.append(token)
                    val loop = acc.contains("<<<") || acc.contains("TEXT>>>") ||
                        RewriteEngine.looksRepeating(acc)
                    if (!loop) emit(token)
                    !loop
                }
        )
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
