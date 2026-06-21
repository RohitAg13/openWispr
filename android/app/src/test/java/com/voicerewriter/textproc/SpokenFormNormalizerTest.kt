package com.voicerewriter.textproc

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins spoken-punctuation handling and its noun-sense / code-mode gating. */
class SpokenFormNormalizerTest {

    @Test fun explicitPunctuation() {
        assertEquals(
            "send it to bob, then wait. see you tomorrow",
            SpokenFormNormalizer.normalize("send it to bob comma then wait period see you tomorrow"),
        )
    }

    @Test fun periodAsNounIsNotPunctuation() {
        // "that period" is the noun sense — must not become "that."
        assertEquals(
            "during that period I was calm",
            SpokenFormNormalizer.normalize("during that period I was calm"),
        )
    }

    @Test fun questionMark() {
        assertEquals("is it done?", SpokenFormNormalizer.normalize("is it done question mark"))
    }

    @Test fun dottedFilename() {
        assertEquals("check out next.js today", SpokenFormNormalizer.normalize("check out next dot js today"))
    }

    @Test fun ellipsis() {
        assertEquals("wait... then go", SpokenFormNormalizer.normalize("wait dot dot dot then go"))
    }

    @Test fun pathInProse() {
        assertEquals("cd /usr/bin", SpokenFormNormalizer.normalize("cd slash usr slash bin"))
    }

    @Test fun codeModeKeepsSlashAsWord() {
        assertEquals(
            "cd slash usr slash bin",
            SpokenFormNormalizer.normalize("cd slash usr slash bin", unambiguousOnly = true),
        )
    }
}
