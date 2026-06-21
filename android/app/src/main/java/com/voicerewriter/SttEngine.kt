package com.voicerewriter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Speech-to-text over an OpenAI-compatible /v1/audio/transcriptions endpoint
 * (Groq Whisper by default, OpenAI Whisper, or any custom endpoint). Uploads the
 * recorded audio as multipart/form-data and returns the transcript.
 *
 * Kept deliberately small and interface-stable so an on-device Whisper backend
 * can replace the network call later without touching callers.
 */
object SttEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // transcription is a single blocking response
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val M4A = "audio/m4a".toMediaType()

    /**
     * Transcribe [audio] using the configured STT provider. Suspends on IO.
     *
     * [biasPrompt] is the OpenAI/Groq `prompt` field: a hint that biases decoding
     * toward given spellings (our personal-vocab glossary), the cloud equivalent of
     * whisper.cpp's initial_prompt. Optional; ignored when blank.
     */
    suspend fun transcribe(settings: Settings, audio: File, biasPrompt: String? = null): String = withContext(Dispatchers.IO) {
        if (settings.sttKey.isBlank()) {
            throw IllegalStateException("No speech-to-text key set. Open OpenWispr settings.")
        }
        val url = settings.sttEndpointResolved
        if (url.isEmpty()) throw IllegalStateException("Speech-to-text endpoint not configured.")
        val model = settings.sttModelResolved
        if (model.isEmpty()) throw IllegalStateException("No speech-to-text model configured.")

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody(M4A))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .apply { if (!biasPrompt.isNullOrBlank()) addFormDataPart("prompt", biasPrompt) }
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.sttKey}")
            .post(body)
            .build()

        client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw IllegalStateException("STT ${res.code}: ${text.take(300).ifEmpty { res.message }}")
            }
            // OpenAI/Groq return {"text":"..."} for response_format=json.
            val transcript = try {
                JSONObject(text).optString("text", "")
            } catch (_: Exception) {
                text // some endpoints return raw text
            }
            transcript.trim()
        }
    }
}
