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
     *
     * [language] is the dictation language. Parakeet (NVIDIA TDT 0.6B) is English-only: it has
     * no Hindi, and no amount of settings will give it one. Rather than let a user pick Hindi
     * and silently get English-shaped output from an English-only transducer, a non-English
     * language routes to Whisper, whose ggml builds are multilingual. This is the one place
     * that decision lives, so every call site (dictation, warm-up, readiness) agrees.
     *
     * Whisper's largest downloaded size is preferred, because Whisper's non-English accuracy
     * falls off much faster than its English does — `tiny` in Hindi is not worth shipping if
     * `small` is already on disk.
     */
    @JvmOverloads
    fun resolveModel(modelId: String, language: String = DictationLanguage.DEFAULT): String {
        val known = isParakeet(modelId) || WhisperModelManager.MODELS.any { it.id == modelId }
        val id = if (known) modelId else Defaults.STT_PROVIDERS.getValue("local").defaultModel
        if (DictationLanguage.isEnglish(language) || !isParakeet(id)) return id
        return WhisperModelManager.DEFAULT_MODEL
    }

    /**
     * Same as [resolveModel] but able to see what is actually on disk, so a Hindi user who has
     * `small` downloaded gets `small` rather than the registry default. Falls back to the
     * default when nothing better is present — the caller still has to handle "not downloaded".
     */
    fun resolveModel(context: Context, modelId: String, language: String): String {
        val id = resolveModel(modelId, language)
        if (DictationLanguage.isEnglish(language) || !isParakeet(modelId)) return id
        // Biggest-first: MODELS is ordered tiny -> small, and accuracy tracks size.
        return WhisperModelManager.MODELS.lastOrNull { WhisperModelManager.isReady(context, it.id) }?.id ?: id
    }

    /**
     * Is the engine this user would actually transcribe with downloaded? [language] matters:
     * a Hindi user with Parakeet selected is really waiting on a Whisper model, and answering
     * about Parakeet would send them into a dictation that cannot run.
     */
    @JvmOverloads
    fun isReady(context: Context, modelId: String, language: String = DictationLanguage.DEFAULT): Boolean =
        resolveModel(context, modelId, language).let { id ->
            if (isParakeet(id)) ParakeetModelManager.isReady(context)
            else WhisperModelManager.isReady(context, id)
        }

    suspend fun transcribe(context: Context, settings: Settings, samples: FloatArray, biasPrompt: String? = null): String {
        val id = resolveModel(context, settings.sttModel, settings.sttLanguage)
        val s = if (id != settings.sttModel) settings.copy(sttModel = id) else settings
        return if (isParakeet(id)) LocalParakeetStt.transcribe(context, s, samples, biasPrompt)
        else LocalWhisperStt.transcribe(context, s, samples, biasPrompt)
    }

    @JvmOverloads
    suspend fun warm(
        context: Context,
        modelId: String,
        biasPrompt: String? = null,
        language: String = DictationLanguage.DEFAULT,
    ) = resolveModel(context, modelId, language).let { id ->
        if (isParakeet(id)) LocalParakeetStt.warm(context, biasPrompt)
        else LocalWhisperStt.warm(context, id)
    }
}
