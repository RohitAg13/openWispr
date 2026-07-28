package com.voicerewriter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two rules that make a failed dictation recoverable: retention never touches a
 * recording the user hasn't got their words back from, and "no result yet" survives a
 * round-trip through disk as *absence* rather than as an empty string.
 */
class PendingAudioTest {

    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_700_000_000_000L

    private fun rec(id: String, ageDays: Long, result: String? = "ok") = PendingRecording(
        id = id, timestamp = now - ageDays * day, durationSec = 3,
        appPackage = "com.example", appLabel = "Example",
        sttProvider = "local", sttModel = "parakeet", result = result,
    )

    @Test fun neverExpiresAnUnsettledRecording() {
        // Ancient, and far past any window — but the user never got these words back.
        val old = listOf(rec("a", ageDays = 999, result = null), rec("b", ageDays = 999, result = null))
        assertTrue(PendingAudio.expired(old, keepDays = 7, now = now).isEmpty())
        assertTrue(PendingAudio.expired(old, keepDays = 1, now = now).isEmpty())
    }

    @Test fun expiresSettledRecordingsPastTheWindow() {
        val records = listOf(rec("fresh", ageDays = 2), rec("stale", ageDays = 40))
        assertEquals(listOf("stale"), PendingAudio.expired(records, keepDays = 30, now = now))
    }

    @Test fun keepDaysZeroKeepsEverything() {
        val records = listOf(rec("ancient", ageDays = 5_000), rec("fresh", ageDays = 0))
        assertTrue(PendingAudio.expired(records, keepDays = 0, now = now).isEmpty())
    }

    @Test fun caps_theStoreEvenWhenKeepingForever() {
        val records = (1..260).map { rec("r$it", ageDays = it.toLong()) }
        val victims = PendingAudio.expired(records, keepDays = 0, now = now)
        // The 200 newest survive; the rest go, oldest included.
        assertEquals(60, victims.size)
        assertFalse(victims.contains("r1"))
        assertTrue(victims.contains("r260"))
    }

    @Test fun capCountsOnlySettledRows() {
        // Unsettled rows must not push settled ones over the cap, nor be counted for it.
        val unsettled = (1..300).map { rec("u$it", ageDays = it.toLong(), result = null) }
        val settled = listOf(rec("s1", ageDays = 1))
        assertTrue(PendingAudio.expired(unsettled + settled, keepDays = 0, now = now).isEmpty())
    }

    // ---- I2: absence is the only "not yet" ----

    @Test fun aRecordingWithNoResultRoundTripsAsAbsent() {
        val pending = rec("p", ageDays = 0, result = null)
        val back = PendingRecording.fromJson(JSONObject(pending.toJson().toString()))
        assertNull(back.result)
        assertFalse(back.settled)
        assertEquals(pending, back)
    }

    @Test fun anEmptyResultIsStillTerminal() {
        // "" is a legitimate (if useless) outcome and must not decay into "not yet" —
        // otherwise a settled dictation would keep re-offering itself for retry forever.
        val back = PendingRecording.fromJson(JSONObject(rec("e", ageDays = 0, result = "").toJson().toString()))
        assertEquals("", back.result)
        assertTrue(back.settled)
    }

    @Test fun settledRecordingRoundTripsIntact() {
        val settled = rec("s", ageDays = 0, result = "hello there")
        assertEquals(settled, PendingRecording.fromJson(JSONObject(settled.toJson().toString())))
    }
}
