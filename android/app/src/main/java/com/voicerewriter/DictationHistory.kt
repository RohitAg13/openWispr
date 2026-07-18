package com.voicerewriter

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Append-only log of completed dictations plus cumulative stat counters, backing
 * the Home screen (stat band + "Recent" feed). Self-contained: the feed lives in a
 * JSONL file capped to the most recent entries, while all-time totals (words, time
 * saved, streak, accept/on-device rates) live in SharedPreferences so they survive
 * pruning. Nothing is written when the user turns history off.
 */
data class DictationEntry(
    val id: String,
    val timestamp: Long,
    val appPackage: String,
    val appLabel: String,
    val durationSec: Int,
    val words: Int,
    val accepted: Boolean,   // inserted without an edit
    val onDevice: Boolean,   // speech recognized on-device
    val before: String,      // raw transcript
    val after: String,       // text inserted
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("ts", timestamp)
        .put("pkg", appPackage).put("app", appLabel)
        .put("dur", durationSec).put("words", words)
        .put("accepted", accepted).put("onDevice", onDevice)
        .put("before", before).put("after", after)

    companion object {
        fun fromJson(o: JSONObject) = DictationEntry(
            id = o.optString("id"),
            timestamp = o.optLong("ts"),
            appPackage = o.optString("pkg"),
            appLabel = o.optString("app"),
            durationSec = o.optInt("dur"),
            words = o.optInt("words"),
            accepted = o.optBoolean("accepted", true),
            onDevice = o.optBoolean("onDevice", true),
            before = o.optString("before"),
            after = o.optString("after"),
        )
    }
}

/** All-time totals, derived independently of the (capped) feed. */
data class DictationStats(
    val totalWords: Long,
    val timeSavedMinutes: Double,
    val streakDays: Int,
    val acceptRate: Int,   // 0..100, -1 = no data
    val onDeviceRate: Int, // 0..100, -1 = no data
)

object DictationHistory {
    private const val PREFS = "dictation_history"
    private const val FILE = "dictation_history.jsonl"
    private const val MAX_ENTRIES = 100

    // Time-saved model: typing ~40 wpm vs effective speaking ~150 wpm.
    private const val TYPING_WPM = 40.0
    private const val SPEAKING_WPM = 150.0

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun file(c: Context) = File(c.applicationContext.filesDir, FILE)
    private fun dayKey(ts: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))

    fun keepHistory(c: Context): Boolean = prefs(c).getBoolean("keep", true)

    fun setKeepHistory(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("keep", on).apply()
    }

    /** Record a finished dictation. No-op (except counters stay untouched) when history is off. */
    fun record(c: Context, entry: DictationEntry) {
        // Counters track all-time value even if the feed itself is disabled? No — when the
        // user opts out we record nothing at all, matching the "nothing saved to disk" promise.
        if (!keepHistory(c)) return
        bumpCounters(c, entry)
        runCatching {
            val f = file(c)
            val existing = if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
            val trimmed = (listOf(entry.toJson().toString()) + existing).take(MAX_ENTRIES)
            f.writeText(trimmed.joinToString("\n"))
        }
    }

    /** Newest-first feed entries. */
    fun all(c: Context): List<DictationEntry> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines().filter { it.isNotBlank() }.map { DictationEntry.fromJson(JSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun delete(c: Context, id: String) {
        val f = file(c)
        if (!f.exists()) return
        runCatching {
            val kept = f.readLines().filter { it.isNotBlank() }
                .filter { runCatching { JSONObject(it).optString("id") }.getOrNull() != id }
            f.writeText(kept.joinToString("\n"))
        }
    }

    /**
     * Replace the kept text of one entry (found by [id]), recomputing its word count.
     * Used when the user corrects a past dictation from the Recent feed. The all-time
     * counters in SharedPreferences are left untouched — they were tallied at record
     * time and shouldn't shift on a later edit.
     */
    fun update(c: Context, id: String, newAfter: String) {
        val f = file(c)
        if (!f.exists()) return
        runCatching {
            val lines = f.readLines().filter { it.isNotBlank() }.map { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@map line
                if (o.optString("id") != id) return@map line
                DictationEntry.fromJson(o).copy(
                    after = newAfter,
                    words = newAfter.trim().split(Regex("\\s+")).count { it.isNotBlank() },
                ).toJson().toString()
            }
            f.writeText(lines.joinToString("\n"))
        }
    }

    /** Clear the feed (counters are left intact — all-time totals shouldn't reset on a feed clear). */
    fun clearFeed(c: Context) {
        runCatching { file(c).delete() }
    }

    fun stats(c: Context): DictationStats {
        val p = prefs(c)
        val total = p.getInt("count", 0)
        val words = p.getLong("words", 0L)
        val saved = words * (1.0 / TYPING_WPM - 1.0 / SPEAKING_WPM)
        val accept = if (total > 0) Math.round(p.getInt("accepted", 0) * 100.0 / total).toInt() else -1
        val onDev = if (total > 0) Math.round(p.getInt("ondevice", 0) * 100.0 / total).toInt() else -1
        return DictationStats(words, saved, p.getInt("streak", 0), accept, onDev)
    }

    private fun bumpCounters(c: Context, e: DictationEntry) {
        val p = prefs(c)
        val ed = p.edit()
        ed.putInt("count", p.getInt("count", 0) + 1)
        ed.putLong("words", p.getLong("words", 0L) + e.words)
        if (e.accepted) ed.putInt("accepted", p.getInt("accepted", 0) + 1)
        if (e.onDevice) ed.putInt("ondevice", p.getInt("ondevice", 0) + 1)

        // Streak: consecutive calendar days with at least one dictation.
        val today = dayKey(e.timestamp)
        val lastDay = p.getString("lastDay", null)
        if (lastDay != today) {
            val streak = p.getInt("streak", 0)
            ed.putInt("streak", if (lastDay != null && isYesterday(lastDay, today)) streak + 1 else 1)
            ed.putString("lastDay", today)
        } else if (p.getInt("streak", 0) == 0) {
            ed.putInt("streak", 1)
            ed.putString("lastDay", today)
        }
        ed.apply()
    }

    private fun isYesterday(prev: String, today: String): Boolean = runCatching {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance().apply { time = fmt.parse(today)!! }
        cal.add(Calendar.DAY_OF_YEAR, -1)
        fmt.format(cal.time) == prev
    }.getOrDefault(false)
}
