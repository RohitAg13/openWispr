package com.voicerewriter

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Durable, write-ahead store for dictation audio.
 *
 * The failure this exists to prevent: audio used to live only as a `FloatArray` in RAM on the
 * on-device path, so a transcription error, the activity being destroyed, or the process being
 * killed took a five-minute monologue with it. Nothing the user could do got it back.
 *
 * Two rules make that impossible now:
 *
 *  1. **Write-ahead.** The WAV is on `filesDir` *before* the first transcription attempt, on
 *     both the on-device and the cloud path. Its lifetime is not coupled to that attempt —
 *     nothing here is ever deleted from an error handler. `filesDir`, not `cacheDir`: Android
 *     reclaims the cache under storage pressure with no warning, which is precisely the moment
 *     a user would want their recording back.
 *
 *  2. **Only terminal outcomes are persisted.** A sidecar JSON holds the facts known at record
 *     time plus, once the dictation lands, its `result`. There is no "transcribing" flag on
 *     disk, so a crash can't leave a lie behind and there is no startup repair pass to write.
 *     "In progress" is derived from [inFlight], which is in-memory by construction: if the
 *     process died, it is empty, and the recording correctly reads as unfinished/retryable.
 *
 * Read rule: `result != null` → terminal · `result == null` + in-flight → running ·
 * `result == null` + not in-flight → offer retry.
 */
data class PendingRecording(
    val id: String,
    val timestamp: Long,
    val durationSec: Int,
    val appPackage: String,
    val appLabel: String,
    /** The engine that was tried — lets a retry pick a different one. */
    val sttProvider: String,
    val sttModel: String,
    /** The delivered text, or null for "not yet, or interrupted". Never a running marker. */
    val result: String?,
) {
    val settled: Boolean get() = result != null

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("ts", timestamp).put("dur", durationSec)
        .put("pkg", appPackage).put("app", appLabel)
        .put("sttProvider", sttProvider).put("sttModel", sttModel)
        .apply { if (result != null) put("result", result) }

    companion object {
        fun fromJson(o: JSONObject) = PendingRecording(
            id = o.optString("id"),
            timestamp = o.optLong("ts"),
            durationSec = o.optInt("dur"),
            appPackage = o.optString("pkg"),
            appLabel = o.optString("app"),
            sttProvider = o.optString("sttProvider"),
            sttModel = o.optString("sttModel"),
            // `has` rather than `optString`: absence is the meaningful state, and "" is a
            // legitimate (if useless) terminal result we must not confuse with it.
            result = if (o.has("result")) o.optString("result") else null,
        )
    }
}

object PendingAudio {

    private const val DIR = "pending_audio"
    private const val PREFS = "pending_audio"

    /** Retention default: generous, because the audio never leaves the device. */
    const val DEFAULT_KEEP_DAYS = 30

    /** Hard ceiling regardless of the day window, so the store can't grow without bound. */
    private const val MAX_KEEP = 200

    /**
     * Ids of recordings a *live* transcription is working on. In-memory on purpose — this is
     * the whole of [PendingAudio]'s "running" state (see I2 in the class doc). Process death
     * empties it, which is exactly the answer we want after a crash.
     */
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dir(c: Context): File =
        File(c.applicationContext.filesDir, DIR).apply { mkdirs() }

    /** The WAV blob for [id]. Public so the cloud STT upload can hand it straight to OkHttp. */
    fun wavFile(c: Context, id: String): File = File(dir(c), "$id.wav")

    private fun metaFile(c: Context, id: String): File = File(dir(c), "$id.json")

    /** How many days of settled audio to keep. 0 means "keep everything". */
    fun keepDays(c: Context): Int = prefs(c).getInt("keepDays", DEFAULT_KEEP_DAYS)

    fun setKeepDays(c: Context, days: Int) {
        prefs(c).edit().putInt("keepDays", days).apply()
        prune(c)
    }

    /**
     * Persist a take and mark it in-flight, returning the row — or null if the write failed
     * (out of space, say), which callers must treat as "no durable copy exists".
     *
     * The blob is written to a temp name and renamed, so a half-written WAV is never visible
     * as a recording; the metadata lands only once the audio it describes is complete.
     */
    fun begin(
        c: Context,
        samples: ShortArray,
        durationSec: Int,
        appPackage: String,
        appLabel: String,
        sttProvider: String,
        sttModel: String,
    ): PendingRecording? = runCatching {
        val id = "${System.currentTimeMillis()}-${System.nanoTime() and 0xffff}"
        val wav = wavFile(c, id)
        val tmp = File(dir(c), "$id.wav.part")
        WavIo.write(tmp, samples)
        if (!tmp.renameTo(wav)) { tmp.delete(); return null }
        val rec = PendingRecording(
            id = id,
            timestamp = System.currentTimeMillis(),
            durationSec = durationSec,
            appPackage = appPackage,
            appLabel = appLabel,
            sttProvider = sttProvider,
            sttModel = sttModel,
            result = null,
        )
        metaFile(c, id).writeText(rec.toJson().toString())
        inFlight.add(id)
        rec
    }.getOrNull()

    /** Note that a live attempt has picked this recording up again (retry from disk). */
    fun claim(id: String) { inFlight.add(id) }

    /**
     * Let go of a recording without recording an outcome — the activity went away, or a retry
     * was handed to another screen. Writes nothing: absence of a result is already the correct
     * on-disk state, and the row becomes retryable again the moment nothing is working on it.
     */
    fun release(id: String) { inFlight.remove(id) }

    /**
     * Record the terminal outcome. Called only after the text has been delivered (inserted or
     * put on the clipboard) *and* logged to history — never speculatively.
     */
    fun settle(c: Context, id: String, result: String) {
        inFlight.remove(id)
        runCatching {
            val rec = get(c, id) ?: return
            metaFile(c, id).writeText(rec.copy(result = result).toJson().toString())
        }
        // Honour the user's intent: history off means nothing lingers on disk.
        if (!DictationHistory.keepHistory(c)) delete(c, id) else prune(c)
    }

    /**
     * The user explicitly threw this dictation away. An intentional discard is a terminal
     * outcome like any other, so the audio goes with it — unlike a failure, where keeping it
     * is the entire point.
     */
    fun discard(c: Context, id: String) {
        inFlight.remove(id)
        delete(c, id)
    }

    fun get(c: Context, id: String): PendingRecording? = runCatching {
        val f = metaFile(c, id)
        if (!f.exists()) null else PendingRecording.fromJson(JSONObject(f.readText()))
    }.getOrNull()

    /** Samples for [id], or null if the blob is gone/unreadable. */
    fun samples(c: Context, id: String): ShortArray? {
        val f = wavFile(c, id)
        return if (f.exists()) WavIo.read(f) else null
    }

    /** Every stored recording, newest first. */
    fun all(c: Context): List<PendingRecording> = runCatching {
        dir(c).listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { runCatching { PendingRecording.fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.filter { it.id.isNotEmpty() }
            ?.sortedByDescending { it.timestamp }
            .orEmpty()
    }.getOrDefault(emptyList())

    /**
     * Recordings with no result that nothing is currently transcribing — the ones to offer the
     * user a retry for. Derived, never stored.
     */
    fun unfinished(c: Context): List<PendingRecording> =
        all(c).filter { !it.settled && it.id !in inFlight }

    /**
     * Blob first, then the metadata row, so an interrupted prune never leaves a row pointing
     * at audio that isn't there.
     */
    fun prune(c: Context) {
        expired(all(c), keepDays(c), System.currentTimeMillis()).forEach { delete(c, it) }
    }

    /**
     * Retention policy, as a pure function of the rows so it can be pinned by a test.
     *
     * Only ever returns *settled* recordings. An unsettled one is a dictation the user hasn't
     * got their words back from yet, and retention is not allowed to be the thing that finally
     * loses it. [keepDays] of 0 means "keep everything", still bounded by [MAX_KEEP].
     */
    fun expired(records: List<PendingRecording>, keepDays: Int, now: Long): List<String> {
        val settled = records.filter { it.settled }.sortedByDescending { it.timestamp }
        val cutoff = if (keepDays <= 0) Long.MIN_VALUE else now - keepDays * 24L * 60L * 60L * 1000L
        return settled.filterIndexed { i, rec -> rec.timestamp < cutoff || i >= MAX_KEEP }.map { it.id }
    }

    /**
     * Remove everything. Used when the user turns "Keep history" off — the promise there is
     * "nothing is saved to disk", and retained audio would quietly break it.
     */
    fun purgeAll(c: Context) {
        all(c).forEach { delete(c, it.id) }
    }

    /** Blob first, then the row — never the other way round. */
    private fun delete(c: Context, id: String) {
        runCatching { wavFile(c, id).delete() }
        runCatching { metaFile(c, id).delete() }
    }
}
