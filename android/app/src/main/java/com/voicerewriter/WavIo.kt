package com.voicerewriter

import java.io.File
import java.io.FileOutputStream

/**
 * Minimal 16 kHz mono PCM16 WAV reader/writer — the on-disk form of a dictation take.
 *
 * Deliberately hand-rolled and tiny: the only consumer is [PendingAudio], and the format
 * has to stay byte-identical to what the cloud STT endpoints accept as an upload while
 * still round-tripping back to the sample array the on-device engines want.
 */
object WavIo {

    private const val HEADER_BYTES = 44

    /** Write [samples] as a 16 kHz mono PCM16 WAV. Overwrites [file]. */
    fun write(file: File, samples: ShortArray, sampleRate: Int = AudioRecorder.SAMPLE_RATE) {
        FileOutputStream(file).use { out ->
            out.write(header(samples.size, sampleRate))
            val bytes = ByteArray(samples.size * 2)
            var j = 0
            for (s in samples) {
                bytes[j++] = (s.toInt() and 0xFF).toByte()
                bytes[j++] = ((s.toInt() shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
        }
    }

    /**
     * Read the PCM16 payload back. Assumes a file this object wrote (fixed 44-byte header) —
     * returns null for anything shorter, truncated or unreadable rather than throwing, because
     * callers reach for this on the recovery path where a hard failure helps nobody.
     */
    fun read(file: File): ShortArray? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size <= HEADER_BYTES) return null
        val count = (bytes.size - HEADER_BYTES) / 2
        ShortArray(count) { i ->
            val lo = bytes[HEADER_BYTES + i * 2].toInt() and 0xFF
            val hi = bytes[HEADER_BYTES + i * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort()
        }
    }.getOrNull()

    /** Normalized float samples, the form the on-device engines take. */
    fun toFloats(samples: ShortArray): FloatArray = FloatArray(samples.size) { samples[it] / 32768f }

    private fun header(numSamples: Int, sampleRate: Int): ByteArray {
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val dataSize = numSamples * bits / 8
        fun int(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte(),
        )
        fun short(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        return byteArrayOf().plus("RIFF".toByteArray()).plus(int(36 + dataSize)).plus("WAVE".toByteArray())
            .plus("fmt ".toByteArray()).plus(int(16)).plus(short(1)) // PCM
            .plus(short(channels)).plus(int(sampleRate)).plus(int(byteRate))
            .plus(short(channels * bits / 8)).plus(short(bits))
            .plus("data".toByteArray()).plus(int(dataSize))
    }
}
