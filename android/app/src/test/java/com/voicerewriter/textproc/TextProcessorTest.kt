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

    // --- capitalizationEnabled = false (Settings → "Capitalize sentences" off) ---

    private val noCaps = TextProcessingConfig(capitalizationEnabled = false)

    @Test fun capitalizationOffLeavesCaseAlone() {
        assertEquals(
            "send it to bob, then wait. see you tomorrow",
            TextProcessor.process("send it to bob comma then wait period see you tomorrow", noCaps),
        )
    }

    @Test fun capitalizationOffStillCleansEverythingElse() {
        // The point of the setting is case only: fillers, spoken forms, numbers and
        // self-corrections must all still run, or it becomes a cleanup off-switch.
        assertEquals("let's meet at 3", TextProcessor.process("let's meet at 2 actually 3", noCaps))
        assertEquals("hello\nworld", TextProcessor.process("hello new line world", noCaps))
    }

    @Test fun capitalizationOffDoesNotRecapitalizeAHalvedSelfCorrection() {
        // A correction that drops more than half the text re-uppercases the survivor, which
        // is a *second* path into capitalization and was not gated by the setting.
        assertEquals(
            "to john",
            TextProcessor.process("send it to mark, scratch that, to john", noCaps),
        )
        // Same input with the setting on keeps the old behaviour.
        assertEquals(
            "To john",
            TextProcessor.process("send it to mark, scratch that, to john"),
        )
    }
}
