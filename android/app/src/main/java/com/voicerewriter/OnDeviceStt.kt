package com.voicerewriter

import android.content.Context

/**
 * Routes on-device transcription to the right local engine based on the selected [Settings.sttModel]:
 * the Parakeet transducer (sherpa-onnx) for `"parakeet"`, otherwise whisper.cpp (tiny/base/small).
 * Keeps call sites (RewriteActivity, EvalService, BubbleService) engine-agnostic.
 */
object OnDeviceStt {

    fun isParakeet(modelId: String): Boolean = modelId == ParakeetModelManager.MODEL_ID

    /**
     * Resolve a stored [Settings.sttModel] to a concrete on-device model id. A blank or
     * unrecognized value (e.g. a fresh install, where the field is empty) falls back to the
     * local provider's default — Parakeet — so the recommended engine is used by default.
     */
    fun resolveModel(modelId: String): String {
        val known = isParakeet(modelId) || WhisperModelManager.MODELS.any { it.id == modelId }
        return if (known) modelId else Defaults.STT_PROVIDERS.getValue("local").defaultModel
    }

    fun isReady(context: Context, modelId: String): Boolean = resolveModel(modelId).let { id ->
        if (isParakeet(id)) ParakeetModelManager.isReady(context)
        else WhisperModelManager.isReady(context, id)
    }

    suspend fun transcribe(context: Context, settings: Settings, samples: FloatArray, biasPrompt: String? = null): String {
        val id = resolveModel(settings.sttModel)
        val s = if (id != settings.sttModel) settings.copy(sttModel = id) else settings
        return if (isParakeet(id)) LocalParakeetStt.transcribe(context, s, samples, biasPrompt)
        else LocalWhisperStt.transcribe(context, s, samples, biasPrompt)
    }

    suspend fun warm(context: Context, modelId: String, biasPrompt: String? = null) = resolveModel(modelId).let { id ->
        if (isParakeet(id)) LocalParakeetStt.warm(context, biasPrompt)
        else LocalWhisperStt.warm(context, id)
    }
}
