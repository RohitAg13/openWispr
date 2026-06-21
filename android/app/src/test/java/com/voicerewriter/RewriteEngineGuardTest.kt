package com.voicerewriter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the over-edit guards ported from the cleanup pipeline's LocalLLMProcessor: the content-preservation
 * check (drop / balloon) and self-correction detection that relaxes it.
 */
class RewriteEngineGuardTest {

    @Test fun normalCleanupIsPreserved() {
        // Filler removal + punctuation keeps essentially all content words.
        assertTrue(
            RewriteEngine.preservesContent(
                "the api is slow right now",
                "The API is slow right now.",
                relaxed = false,
            ),
        )
    }

    @Test fun inventedEmailBodyIsRejected() {
        // The v1 fine-tune failure: a subject line balloons into a whole email.
        assertFalse(
            RewriteEngine.preservesContent(
                "Subject: Quick question about the invoice",
                "Subject: Quick question about the invoice. Hi, I'm wondering about the invoice I " +
                    "received. I need to confirm if it's valid and whether there are any issues with " +
                    "the payment. I'll check the details and get back to you as soon as possible.",
                relaxed = false,
            ),
        )
    }

    @Test fun heavyRewriteIsRejected() {
        assertFalse(
            RewriteEngine.preservesContent(
                "send it to bob then wait",
                "Per my previous correspondence, kindly forward this to Robert at your convenience.",
                relaxed = false,
            ),
        )
    }

    @Test fun selfCorrectionRelaxesTheThreshold() {
        val input = "one two three four five six seven eight nine ten"
        val output = "one two three four five" // 50% kept
        assertFalse(RewriteEngine.preservesContent(input, output, relaxed = false)) // < 60%
        assertTrue(RewriteEngine.preservesContent(input, output, relaxed = true))   // ≥ 40%
    }

    @Test fun detectsSelfCorrectionMarkers() {
        assertTrue(RewriteEngine.hasSelfCorrection("let's meet at 2 actually 3"))
        assertTrue(RewriteEngine.hasSelfCorrection("send it to john, scratch that, to mark"))
        assertFalse(RewriteEngine.hasSelfCorrection("the plan is ready to ship"))
    }
}
