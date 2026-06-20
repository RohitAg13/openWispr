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

    /** Transcribe [samples] (16 kHz mono, normalized -1..1). Suspends on heavy CPU work. */
    suspend fun transcribe(context: Context, samples: FloatArray): String {
        if (!WhisperModelManager.isReady(context)) {
            throw IllegalStateException("On-device model not downloaded. Open Settings → Voice → Download model.")
        }
        val whisper = loadLock.withLock {
            ctx ?: withContext(Dispatchers.IO) {
                WhisperContext.createContextFromFile(WhisperModelManager.modelFile(context).absolutePath)
            }.also { ctx = it }
        }
        // transcribeData runs on whisper's own single-thread dispatcher internally.
        val raw = whisper.transcribeData(samples, printTimestamp = false)
        return cleanTranscript(raw)
    }

    /** Strip whisper's bracketed non-speech markers (e.g. [BLANK_AUDIO], [Music]). */
    private fun cleanTranscript(s: String): String =
        s.replace(Regex("\\[[^\\]]*]"), "").trim()
}
