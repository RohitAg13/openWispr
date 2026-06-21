package com.voicerewriter.textproc

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the value-swap backtracking heuristic and the conservative inline/restart repairs. */
class SelfCorrectionDetectorTest {

    @Test fun numericBacktrack() {
        assertEquals("let's meet at 3", SelfCorrectionDetector.detectAndResolve("let's meet at 2 actually 3"))
    }

    @Test fun numericBacktrackWithPm() {
        assertEquals("the meeting is at 3pm", SelfCorrectionDetector.detectAndResolve("the meeting is at 2pm no 3pm"))
    }

    @Test fun standaloneRestartDropsPrefix() {
        assertEquals(
            "send it to john",
            SelfCorrectionDetector.detectAndResolve("send it to mark, scratch that, send it to john"),
        )
    }

    @Test fun inlineIMeanCorrectsTheFragment() {
        assertEquals("send it to john", SelfCorrectionDetector.detectAndResolve("send it to mark, I mean john"))
    }

    @Test fun gatedMarkerDoesNotFalseTrigger() {
        // "sorry" is a gated marker; without a clause break before it, nothing should change.
        val s = "I'm sorry to hear that"
        assertEquals(s, SelfCorrectionDetector.detectAndResolve(s))
    }

    @Test fun proseIsNotSwapped() {
        // The value-swap needs values on both sides, so plain prose is untouched.
        val s = "I actually like this plan"
        assertEquals(s, SelfCorrectionDetector.detectAndResolve(s))
    }
}
