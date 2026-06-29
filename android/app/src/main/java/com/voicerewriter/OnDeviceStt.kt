package com.voicerewriter

import android.content.Context

/**
 * Routes on-device transcription to the right local engine based on the selected [Settings.sttModel]:
 * the Parakeet transducer (sherpa-onnx) for `"parakeet"`, otherwise whisper.cpp (tiny/base/small).
 * Keeps call sites (RewriteActivity, EvalService, BubbleService) engine-agnostic.
 */
object OnDeviceStt {

    fun isParakeet(modelId: String): Boolean = modelId == ParakeetModelManager.MODEL_ID

    fun isReady(context: Context, modelId: String): Boolean =
        if (isParakeet(modelId)) ParakeetModelManager.isReady(context)
        else WhisperModelManager.isReady(context, modelId)

    suspend fun transcribe(context: Context, settings: Settings, samples: FloatArray, biasPrompt: String? = null): String =
        if (isParakeet(settings.sttModel)) LocalParakeetStt.transcribe(context, settings, samples, biasPrompt)
        else LocalWhisperStt.transcribe(context, settings, samples, biasPrompt)

    suspend fun warm(context: Context, modelId: String, biasPrompt: String? = null) =
        if (isParakeet(modelId)) LocalParakeetStt.warm(context, biasPrompt)
        else LocalWhisperStt.warm(context, modelId)
}
