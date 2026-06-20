package com.voicerewriter

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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

    private const val PREDICT_LENGTH = 768

    private val mutex = Mutex()
    @Volatile private var loadedPath: String? = null
    @Volatile private var loadedSystem: String? = null

    /** Stream the rewrite for [prompt] applied to [text]. Mirrors RewriteEngine.streamWithPrompt. */
    fun streamWithPrompt(context: Context, settings: Settings, prompt: String, text: String): Flow<String> = flow {
        val file = LlmModelManager.modelFile(context, settings.model)
        if (!file.exists()) {
            throw IllegalStateException("On-device model not downloaded. Open Settings → On-device LLM → Download.")
        }
        val system = RewriteEngine.buildSystemPrompt(settings)
        val user = RewriteEngine.buildUserContent(prompt, text)
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
        emitAll(engine.sendUserPrompt(user, predictLength = PREDICT_LENGTH))
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
