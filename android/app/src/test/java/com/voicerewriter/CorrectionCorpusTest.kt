package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the pure few-shot retrieval ranking (the rest of CorrectionCorpus is file I/O). */
class CorrectionCorpusTest {

    private fun sample(cleaned: String, final: String, category: String = "chat", edited: Boolean = true) =
        CorrectionSample(ts = 0, category = category, cleaned = cleaned, final = final, edited = edited)

    @Test fun picksTheMostOverlappingExample() {
        val samples = listOf(
            sample("lets grab lunch tomorrow", "Let's grab lunch tomorrow"),
            sample("the deploy script is broken again", "The deploy script is broken again", category = "code"),
        )
        val top = CorrectionCorpus.rank(samples, "lets grab dinner tomorrow", "chat", k = 1)
        assertEquals(1, top.size)
        assertTrue(top[0].cleaned.contains("lunch"))
    }

    @Test fun ignoresExamplesWithNoRealOverlap() {
        val samples = listOf(sample("the deploy script is broken again", "The deploy script is broken again"))
        // A query that shares only a stopword-ish token shouldn't surface anything.
        assertTrue(CorrectionCorpus.rank(samples, "see you at the park", "chat", k = 2).isEmpty())
    }

    @Test fun sameCategoryAndEditedAreBoosted() {
        val q = CorrectionCorpus.tokens("send me the invoice please")
        val sameCatEdited = sample("send me the invoice please", "Send me the invoice, please.", category = "email", edited = true)
        val otherCatVerbatim = sample("send me the invoice please", "send me the invoice please", category = "chat", edited = false)
        assertTrue(
            CorrectionCorpus.score(q, sameCatEdited, "email") >
                CorrectionCorpus.score(q, otherCatVerbatim, "email"),
        )
    }

    @Test fun capsToK() {
        val samples = (1..5).map { sample("meeting notes for project alpha review $it", "Meeting notes for project alpha review $it") }
        assertEquals(2, CorrectionCorpus.rank(samples, "meeting notes for project alpha review", "notes", k = 2).size)
    }
}
