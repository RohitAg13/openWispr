package com.voicerewriter.textproc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * End-to-end pins on the full deterministic pipeline (stage order + capitalization),
 * mirroring representative cases from eval/datasets/cleanup.jsonl.
 */
class TextProcessorTest {

    @Test fun backtrackEndToEnd() {
        assertEquals("Let's meet at 3", TextProcessor.process("let's meet at 2 actually 3"))
    }

    @Test fun numberedListEndToEnd() {
        // The "1." separators read as sentence terminators, so the items capitalize too.
        assertEquals(
            "Going to the store for\n1. Apples\n2. Bananas\n3. Oranges",
            TextProcessor.process("going to the store for 1. apples 2. bananas 3. oranges"),
        )
    }

    @Test fun newlineMarkEndToEnd() {
        assertEquals("Hello\nWorld", TextProcessor.process("hello new line world"))
    }

    @Test fun punctuationEndToEnd() {
        assertEquals(
            "Send it to bob, then wait. See you tomorrow",
            TextProcessor.process("send it to bob comma then wait period see you tomorrow"),
        )
    }

    @Test fun codeContextSkipsCapsAndSpokenForms() {
        assertEquals(
            "cd slash usr slash bin",
            TextProcessor.process("cd slash usr slash bin", isCodeContext = true),
        )
    }
}
