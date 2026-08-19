package com.voicerewriter

import org.junit.Assert.assertEquals

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The onboarding try-it step advertises features based on what the cleanup actually did to the
 * user's sentence, so a wrong label here is a visible lie about the product. The scripted line
 * is pinned in particular: it's the one nearly every new user reads aloud.
 */
class PolishHighlightsTest {

    /** Matches TRY_IT_SCRIPT in OnboardingActivity. */
    private val script = "um so send it to mark, I mean john, tomorrow at 2 period"

    @Test
    fun `onboarding script surfaces the three headline transformations`() {
        assertEquals(
            listOf("Caught your correction", "Filler removed", "Punctuation added"),
            PolishHighlights.labels(script),
        )
    }

    @Test
    fun `onboarding script mentions the capabilities it did not exercise`() {
        val also = PolishHighlights.alsoHandles(script)
        assertEquals("It also lays out spoken lists and formats spoken numbers.", also)
    }

    @Test
    fun `blank input yields nothing to show`() {
        assertEquals(emptyList<String>(), PolishHighlights.labels(""))
        assertEquals(emptyList<String>(), PolishHighlights.labels("   "))
    }

    @Test
    fun `clean text produces no labels but still names the features`() {
        // Already-tidy input: nothing for the pipeline to fix, so the step falls back to
        // describing capabilities rather than claiming it did something.
        val labels = PolishHighlights.labels("Send it to John.")
        assertTrue("expected no transformations, got $labels", labels.isEmpty())
        assertTrue(PolishHighlights.alsoHandles("Send it to John.")!!.startsWith("It also "))
    }

    @Test
    fun `a spoken list is detected and then not re-advertised`() {
        val spoken = "first buy milk second call sam third finish the deck"
        assertTrue(
            "expected list formatting, got ${PolishHighlights.labels(spoken)}",
            PolishHighlights.labels(spoken).contains("Laid out as a list"),
        )
        assertTrue(
            "list should not appear in the also-handles line",
            PolishHighlights.alsoHandles(spoken)?.contains("lays out spoken lists") != true,
        )
    }

    @Test
    fun `labels never exceed what fits under the result card`() {
        // Four-plus chips crowds the payoff moment; the ordering exists to keep the best ones
        // first, so guard the count rather than letting the list grow silently.
        val busy = "um so first buy milk second call sam, I mean sarah, at two thirty period"
        assertTrue(PolishHighlights.labels(busy).size <= 4)
    }

    @Test
    fun `nothing fired still names capabilities rather than going silent`() {
        // Blank input means no transformation ran, so the step has nothing to claim credit
        // for — but the features are still worth mentioning.
        assertEquals(
            "It also fixes mid-sentence corrections and strips filler words",
            PolishHighlights.alsoHandles("")?.removeSuffix("."),
        )
    }
}
