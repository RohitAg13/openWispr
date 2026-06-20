package com.voicerewriter

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Captures raw 16 kHz mono PCM16 from the mic with AudioRecord. The single
 * recording feeds either backend:
 *  - on-device Whisper wants a FloatArray of 16 kHz mono samples  → [stopToFloats]
 *  - cloud Whisper wants an audio file                            → [stopToWav]
 *
 * Requires the RECORD_AUDIO permission (the activity checks before start()).
 */
class AudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16_000 // Whisper is trained at 16 kHz
    }

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var recording = false
    @Volatile private var lastPeak = 0 // most recent buffer peak, for the waveform
    private val chunks = ArrayList<ShortArray>()

    val isRecording: Boolean get() = recording

    /** Current peak amplitude (0..32767) for waveform visualization. */
    fun amplitude(): Int = lastPeak

    @SuppressLint("MissingPermission")
    fun start() {
        if (recording) return
        chunks.clear()
        lastPeak = 0
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE) // ~0.5s of headroom
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Couldn't initialize the microphone.")
        }
        record = rec
        recording = true
        rec.startRecording()
        worker = Thread {
            val buf = ShortArray(minBuf)
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    var peak = 0
                    for (i in 0 until n) {
                        val a = abs(buf[i].toInt())
                        if (a > peak) peak = a
                    }
                    lastPeak = peak
                    synchronized(chunks) { chunks.add(buf.copyOf(n)) }
                }
            }
        }.also { it.start() }
    }

    /** Stop and return all captured samples (null if nothing usable was recorded). */
    private fun stopSamples(): ShortArray? {
        if (!recording && record == null) return null
        recording = false
        try { worker?.join(500) } catch (_: InterruptedException) {}
        worker = null
        record?.let { r ->
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
        record = null
        val all = synchronized(chunks) { chunks.toList() }
        chunks.clear()
        val total = all.sumOf { it.size }
        if (total < SAMPLE_RATE / 4) return null // < ~0.25s: nothing meaningful
        val out = ShortArray(total)
        var off = 0
        for (c in all) { c.copyInto(out, off); off += c.size }
        return out
    }

    /** Stop → normalized float samples for on-device Whisper. */
    fun stopToFloats(): FloatArray? {
        val s = stopSamples() ?: return null
        return FloatArray(s.size) { s[it] / 32768f }
    }

    /** Stop → a 16 kHz mono WAV file for cloud transcription upload. */
    fun stopToWav(): File? {
        val s = stopSamples() ?: return null
        val file = File(context.cacheDir, "dictation_${System.nanoTime()}.wav")
        FileOutputStream(file).use { out ->
            writeWavHeader(out, s.size)
            val bytes = ByteArray(s.size * 2)
            var j = 0
            for (sample in s) {
                bytes[j++] = (sample.toInt() and 0xFF).toByte()
                bytes[j++] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
        }
        return file
    }

    fun cancel() {
        stopSamples()
    }

    private fun writeWavHeader(out: FileOutputStream, numSamples: Int) {
        val channels = 1
        val bits = 16
        val byteRate = SAMPLE_RATE * channels * bits / 8
        val dataSize = numSamples * bits / 8
        val totalSize = 36 + dataSize
        fun int(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte(),
        )
        fun short(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        out.write("RIFF".toByteArray()); out.write(int(totalSize)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(int(16)); out.write(short(1)) // PCM
        out.write(short(channels)); out.write(int(SAMPLE_RATE)); out.write(int(byteRate))
        out.write(short(channels * bits / 8)); out.write(short(bits))
        out.write("data".toByteArray()); out.write(int(dataSize))
    }
}
