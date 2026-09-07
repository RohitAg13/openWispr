package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading RAM and free space needs a device; choosing what to download from those two numbers
 * does not, and that is where a wrong answer costs a user their first run. [DeviceFit.planFor]
 * is the pure half, so these cover the thresholds and — more importantly — the guarantee that
 * every path lands on a real, downloadable model pair rather than an error state.
 */
class DeviceFitTest {

    private val gib = 1024L * 1024L * 1024L
    private val roomy = 8 * gib

    private fun plan(ramGb: Double, lowRam: Boolean = false, freeBytes: Long = roomy) =
        DeviceFit.planFor((ramGb * gib).toLong(), lowRam, freeBytes)

    @Test
    fun `a flagship gets the full pair`() {
        val p = plan(7.4) // an 8GB phone, as Android reports it
        assertEquals(DeviceFit.Tier.FULL, p.tier)
        assertEquals(ParakeetModelManager.MODEL_ID, p.sttModel)
        assertEquals(LlmModelManager.DEFAULT_MODEL, p.llmModel)
        assertTrue(p.usesParakeet)
    }

    @Test
    fun `a 4GB phone gets the compact pair instead of a 1GB download`() {
        val p = plan(3.6) // what a "4GB" device actually reports
        assertEquals(DeviceFit.Tier.COMPACT, p.tier)
        assertEquals("base", p.sttModel)
        assertEquals("gemma3-270m", p.llmModel)
    }

    @Test
    fun `a 3GB phone gets the smallest pair`() {
        val p = plan(2.8)
        assertEquals(DeviceFit.Tier.MINIMAL, p.tier)
        assertEquals("tiny", p.sttModel)
    }

    @Test
    fun `Android's own low-RAM flag overrides a healthy-looking total`() {
        // Go-edition devices can report plenty of total RAM while the platform is already
        // trimming hard. The flag wins.
        val p = plan(6.0, lowRam = true)
        assertEquals(DeviceFit.Tier.MINIMAL, p.tier)
    }

    @Test
    fun `an unreadable ActivityManager assumes the phone is capable`() {
        val p = DeviceFit.planFor(0L, lowRam = false, freeBytes = roomy)
        assertEquals(DeviceFit.Tier.FULL, p.tier)
    }

    @Test
    fun `free space can only push the tier down, never up`() {
        // 900MB: enough for the compact pair (383MB of weights plus headroom), not for the
        // full one (~1GB of weights).
        val p = plan(7.4, freeBytes = 900L * 1024 * 1024)
        assertEquals(DeviceFit.Tier.COMPACT, p.tier)
        // RAM was never the problem here, and the copy has to be able to say so.
        assertEquals(DeviceFit.Tier.FULL, p.ramTier)
        assertEquals(0, p.storageShortMb)
    }

    @Test
    fun `a nearly full phone is told how much it is short by, before the download starts`() {
        val p = plan(7.4, freeBytes = 100L * 1024 * 1024)
        assertEquals(DeviceFit.Tier.MINIMAL, p.tier)
        assertTrue("expected a shortfall, got ${p.storageShortMb}MB", p.storageShortMb > 0)
        assertNotNull(DeviceFit.explain(p))
    }

    @Test
    fun `every tier resolves to a real model that can actually be downloaded`() {
        // The whole point is that a smaller tier is a smaller model, never a dead end. If a
        // plan ever names an id the registries don't know, OnDeviceStt silently falls back to
        // Parakeet — which would put the 631MB download back on the phone that couldn't take it.
        for (ram in listOf(0.0, 1.5, 2.8, 3.6, 5.5, 12.0)) {
            for (low in listOf(true, false)) {
                val p = plan(ram, lowRam = low)
                assertEquals(
                    "stt id must survive resolveModel for ram=$ram low=$low",
                    p.sttModel,
                    OnDeviceStt.resolveModel(p.sttModel),
                )
                assertEquals(
                    "llm id must be a known model for ram=$ram low=$low",
                    p.llmModel,
                    LlmModelManager.model(p.llmModel).id,
                )
                assertTrue(p.downloadMb > 0)
            }
        }
    }

    @Test
    fun `a phone that gets the full pair is told nothing at all`() {
        assertNull(DeviceFit.explain(plan(7.4)))
    }
}
