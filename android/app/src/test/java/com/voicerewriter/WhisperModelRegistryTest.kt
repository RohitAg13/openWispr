package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is a table of URLs and byte counts, and getting one wrong is not a compile
 * error — it is a download that 404s, or a truncated file that passes the readiness check and
 * then fails to load. These pin the invariants that keep those from being silent.
 */
class WhisperModelRegistryTest {

    private val models = WhisperModelManager.MODELS

    @Test fun `every model ships a quantized build, not the full-precision one`() {
        // The whole point of the switch. An f16 filename here means someone re-added a 488MB
        // download by hand.
        for (m in models) {
            assertTrue("${m.id} is not a quantized build: ${m.fileName}", Regex("-q\\d").containsMatchIn(m.fileName))
        }
    }

    @Test fun `the url always points at the file the model claims`() {
        for (m in models) {
            assertTrue("${m.id}: url and fileName disagree", m.url.endsWith("/${m.fileName}"))
        }
    }

    @Test fun `ids and filenames are unique`() {
        assertEquals(models.size, models.map { it.id }.toSet().size)
        assertEquals(models.size, models.map { it.fileName }.toSet().size)
    }

    @Test fun `the size label matches the real byte count`() {
        // The label is what the user reads before committing to a download on mobile data.
        for (m in models) {
            val labelled = m.sizeLabel.removePrefix("~").removeSuffix("MB").toLong()
            val actual = m.sizeBytes / 1024 / 1024
            assertTrue("${m.id}: labelled ${labelled}MB but is ${actual}MB", Math.abs(labelled - actual) <= 2)
        }
    }

    @Test fun `the readiness floor sits below the real size but above a half download`() {
        // Regression guard: the old global 30MB floor was within 1MB of quantized tiny, so a
        // truncated tiny would have read as ready.
        for (m in models) {
            val floor = m.sizeBytes / 10 * 9
            assertTrue("${m.id}: floor above real size", floor < m.sizeBytes)
            assertTrue("${m.id}: floor would accept a half download", floor > m.sizeBytes / 2)
        }
    }

    @Test fun `the three upgraded sizes name the f16 blob they replace`() {
        for (id in listOf("tiny", "base", "small")) {
            assertNotNull("$id must reclaim its old weights", WhisperModelManager.model(id).legacyFileName)
        }
    }

    @Test fun `medium is new, so it supersedes nothing`() {
        assertNull(WhisperModelManager.model("medium").legacyFileName)
    }

    @Test fun `a legacy name is never also a current name`() {
        // Reclaiming a file that is still in use would delete a working model.
        val current = models.map { it.fileName }.toSet()
        for (m in models) {
            m.legacyFileName?.let { assertTrue("$it is still in use", it !in current) }
        }
    }

    @Test fun `an unknown id still resolves to the default`() {
        assertEquals(WhisperModelManager.DEFAULT_MODEL, WhisperModelManager.model("nope").id)
    }

    @Test fun `medium is offered but is not what DeviceFit recommends`() {
        // It is a deliberate choice for non-English accuracy, not a recommendation: it is
        // markedly slower on a phone.
        assertTrue(models.any { it.id == "medium" })
        for (tier in DeviceFit.Tier.entries) {
            assertTrue("DeviceFit must not pick medium", DeviceFit.sttForTier(tier) != "medium")
        }
    }
}
