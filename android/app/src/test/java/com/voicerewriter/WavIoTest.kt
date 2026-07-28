package com.voicerewriter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The write-ahead copy is only worth having if it reads back as the same audio, so this pins
 * the round-trip — including the negative samples a sign-extension slip would corrupt.
 */
class WavIoTest {

    private fun tempFile(): File =
        File.createTempFile("wavio", ".wav").also { it.deleteOnExit() }

    @Test fun roundTripsSamplesExactly() {
        val samples = ShortArray(4000) { (it * 37 - 20_000).toShort() }
        val f = tempFile()
        WavIo.write(f, samples)
        assertArrayEquals(samples, WavIo.read(f))
    }

    @Test fun preservesTheExtremes() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, -32_000, 32_000.toShort())
        val f = tempFile()
        WavIo.write(f, samples)
        assertArrayEquals(samples, WavIo.read(f))
    }

    @Test fun writesA44ByteRiffHeader() {
        val samples = ShortArray(100) { 7 }
        val f = tempFile()
        WavIo.write(f, samples)
        val bytes = f.readBytes()
        assertEquals(44 + 200, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("data", String(bytes, 36, 4))
    }

    @Test fun readReturnsNullForATruncatedFile() {
        val f = tempFile()
        f.writeBytes(ByteArray(20))
        assertNull(WavIo.read(f))
    }

    @Test fun toFloatsNormalizesIntoRange() {
        val floats = WavIo.toFloats(shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE))
        assertEquals(0f, floats[0], 1e-6f)
        assertTrue(floats[1] > 0.99f && floats[1] <= 1f)
        assertEquals(-1f, floats[2], 1e-6f)
    }
}
