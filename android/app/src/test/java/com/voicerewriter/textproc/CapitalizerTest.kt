package com.voicerewriter.textproc

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins sentence capitalization at obvious boundaries without touching mid-token dots. */
class CapitalizerTest {

    @Test fun capitalizesFirstAndAfterPeriod() {
        assertEquals("Hello. World", Capitalizer.capitalizeSentences("hello. world"))
    }

    @Test fun capitalizesAfterNewline() {
        assertEquals("Hello\nWorld", Capitalizer.capitalizeSentences("hello\nworld"))
    }

    @Test fun fastAiIsNotASentenceBoundary() {
        assertEquals("Fast.ai is great", Capitalizer.capitalizeSentences("fast.ai is great"))
    }

    @Test fun decimalIsNotASentenceBoundary() {
        assertEquals("Pi is 3.14 ok", Capitalizer.capitalizeSentences("pi is 3.14 ok"))
    }

    @Test fun preservesMidSentenceCasing() {
        assertEquals("The new iPhone is here", Capitalizer.capitalizeSentences("the new iPhone is here"))
    }

    @Test fun skipsLeadingQuote() {
        assertEquals("\"Hello\" world", Capitalizer.capitalizeSentences("\"hello\" world"))
    }
}
