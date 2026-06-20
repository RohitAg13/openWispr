package com.voicerewriter

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Thin MediaRecorder wrapper: records microphone audio to an AAC/.m4a file in
 * cacheDir, which the cloud Whisper endpoints accept as multipart upload.
 *
 * Requires the RECORD_AUDIO permission (requested by the activity before start).
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null

    /** Current peak amplitude (0..32767) for waveform visualization, or 0. */
    fun amplitude(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (_: Exception) {
        0
    }

    /** Begin recording. Throws if the mic is unavailable or permission is missing. */
    fun start() {
        if (recorder != null) return
        val file = File(context.cacheDir, "dictation_${System.nanoTime()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(16_000) // Whisper is trained at 16 kHz
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = rec
        outputFile = file
    }

    /**
     * Stop recording and return the recorded file, or null if nothing was
     * captured / the recording was too short to be valid.
     */
    fun stop(): File? {
        val rec = recorder ?: return null
        recorder = null
        val file = outputFile
        outputFile = null
        return try {
            rec.stop()
            rec.release()
            file?.takeIf { it.exists() && it.length() > 0 }
        } catch (_: Exception) {
            // stop() throws if stopped almost immediately (no valid data).
            rec.release()
            file?.delete()
            null
        }
    }

    /** Abort without keeping the file (e.g. on cancel/discard). */
    fun cancel() {
        val rec = recorder ?: return
        recorder = null
        val file = outputFile
        outputFile = null
        try { rec.stop() } catch (_: Exception) {}
        rec.release()
        file?.delete()
    }
}
