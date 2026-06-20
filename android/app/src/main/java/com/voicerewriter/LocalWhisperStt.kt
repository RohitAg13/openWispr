package com.voicerewriter

import android.content.Context
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device speech-to-text via whisper.cpp (vendored `:lib`). Lazily loads the
 * downloaded model into a cached WhisperContext (reused across dictations) and
 * transcribes 16 kHz mono float samples fully offline.
 *
 * Same role as [SttEngine] but local; the caller picks based on the STT provider.
 */
object LocalWhisperStt {

    private val loadLock = Mutex()
    @Volatile private var ctx: WhisperContext? = null
    @Volatile private var loadedId: String? = null

    /** Transcribe [samples] (16 kHz mono, normalized -1..1). Suspends on heavy CPU work. */
    suspend fun transcribe(context: Context, settings: Settings, samples: FloatArray): String {
        val id = settings.sttModel.ifBlank { WhisperModelManager.DEFAULT_MODEL }
        if (!WhisperModelManager.isReady(context, id)) {
            throw IllegalStateException("On-device model not downloaded. Open Settings → Voice → Download model.")
        }
        val whisper = loadLock.withLock {
            if (ctx == null || loadedId != id) {
                ctx?.let { runCatching { it.release() } }
                ctx = withContext(Dispatchers.IO) {
                    WhisperContext.createContextFromFile(WhisperModelManager.modelFile(context, id).absolutePath)
                }
                loadedId = id
            }
            ctx!!
        }
        // transcribeData runs on whisper's own single-thread dispatcher internally.
        val raw = whisper.transcribeData(samples, printTimestamp = false)
        return cleanTranscript(raw)
    }

    /** Strip whisper's bracketed non-speech markers (e.g. [BLANK_AUDIO], [Music]). */
    private fun cleanTranscript(s: String): String =
        s.replace(Regex("\\[[^\\]]*]"), "").trim()
}
