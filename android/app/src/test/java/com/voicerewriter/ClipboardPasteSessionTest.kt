package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClipboardPasteSessionTest {
    @Test
    fun `previously empty clipboard is empty again after paste`() {
        var clipboard: String? = null
        val session = ClipboardPasteSession({ clipboard = it }, { clipboard = null }, { throw it })
        assertTrue(session.paste("dictation") { true })
        assertEquals(null, clipboard)
    }

    @Test
    fun `accepted paste retains temporary text until host has time to read it`() {
        var clipboard = "original"
        var restoreLater: (() -> Unit)? = null
        val session = ClipboardPasteSession(
            { clipboard = it }, { clipboard = "original" }, { throw it },
            { restoreLater = it },
        )
        assertTrue(session.paste("dictation") { true })
        assertEquals("dictation", clipboard)
        restoreLater!!()
        assertEquals("original", clipboard)
    }

    @Test
    fun `successful paste borrows clipboard and restores original`() {
        var clipboard = "previously copied"
        val session = ClipboardPasteSession({ clipboard = it }, { clipboard = "previously copied" }, { throw it })
        assertTrue(session.paste("dictated text") {
            assertEquals("dictated text", clipboard)
            true
        })
        assertEquals("previously copied", clipboard)
    }

    @Test
    fun `rejected paste does not leave text behind for later direct insertion`() {
        var clipboard = "original"
        val session = ClipboardPasteSession({ clipboard = it }, { clipboard = "original" }, { throw it })
        repeat(2) {
            assertFalse(session.paste("dictation") { false })
            assertEquals("original", clipboard)
        }
    }

    @Test
    fun `paste exception still restores clipboard`() {
        var clipboard = "original"
        val failure = IllegalStateException("paste failed")
        val session = ClipboardPasteSession({ clipboard = it }, { clipboard = "original" }, { throw it })
        try {
            session.paste("dictation") { throw failure }
            fail("Expected paste failure")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }
        assertEquals("original", clipboard)
    }

    @Test
    fun `restore failure cannot turn successful paste into a duplicate insertion retry`() {
        val failure = IllegalStateException("restore failed")
        var reported: Exception? = null
        val session = ClipboardPasteSession({}, { throw failure }, { reported = it })
        assertTrue(session.paste("dictation") { true })
        assertSame(failure, reported)
    }
}
