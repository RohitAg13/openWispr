package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The language list is generated from whisper.cpp's own `g_lang` table, so these tests are
 * really about the coercion boundary: a stored preference is untrusted input, and a code the
 * vendored decoder doesn't know would make `whisper_full` fail at dictation time rather than
 * at the point the bad value was introduced.
 */
class DictationLanguageTest {

    @Test fun `hindi is offered, which is the whole point`() {
        val hi = DictationLanguage.ALL.firstOrNull { it.code == "hi" }
        assertEquals("Hindi", hi?.label)
    }

    @Test fun `english is first, because it is the default and the common case`() {
        assertEquals("en", DictationLanguage.ALL.first().code)
    }

    @Test fun `the major indic languages are all reachable`() {
        // The reason this feature exists. Whisper ships them; we were just never asking.
        val codes = DictationLanguage.ALL.map { it.code }.toSet()
        for (c in listOf("hi", "bn", "ta", "te", "mr", "gu", "kn", "ml", "pa", "ur", "ne")) {
            assertTrue("missing $c", codes.contains(c))
        }
    }

    @Test fun `codes are unique`() {
        val codes = DictationLanguage.ALL.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test fun `an unknown or empty stored value falls back to english`() {
        for (bad in listOf("", "  ", "klingon", "xx", "en-GB")) {
            assertEquals("en", DictationLanguage.normalize(bad))
        }
        assertEquals("en", DictationLanguage.normalize(null))
    }

    @Test fun `normalize accepts case and whitespace from an older write`() {
        assertEquals("hi", DictationLanguage.normalize(" HI "))
    }

    @Test fun `isEnglish is what gates the english-only cleanup chain`() {
        assertTrue(DictationLanguage.isEnglish("en"))
        assertTrue("an unknown value must not silently disable cleanup", DictationLanguage.isEnglish("nonsense"))
        assertFalse(DictationLanguage.isEnglish("hi"))
    }

    @Test fun `every offered label is non-blank`() {
        assertTrue(DictationLanguage.ALL.all { it.label.isNotBlank() })
    }
}
