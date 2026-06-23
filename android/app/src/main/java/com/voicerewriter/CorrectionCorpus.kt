package com.voicerewriter

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * One accepted dictation, kept as personalization fuel: the text the pipeline *would*
 * insert ([cleaned]) paired with what the user actually kept ([final]). When the two
 * differ the user corrected something — the highest-value signal for "learn my style".
 */
data class CorrectionSample(
    val ts: Long,
    val category: String,
    val cleaned: String,
    val final: String,
    val edited: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ts", ts).put("category", category)
        .put("cleaned", cleaned).put("final", final).put("edited", edited)

    companion object {
        fun fromJson(o: JSONObject) = CorrectionSample(
            ts = o.optLong("ts"),
            category = o.optString("category", "generic"),
            cleaned = o.optString("cleaned"),
            final = o.optString("final"),
            edited = o.optBoolean("edited", false),
        )
    }
}

/**
 * On-device corpus of accepted dictations — the backbone of "the more you use it, the
 * better it gets". It feeds three personalization layers:
 *
 *  - L1: word-level corrections become personal-vocabulary aliases + Whisper bias.
 *  - L3: the closest past examples are injected as few-shot into the LLM polish prompt
 *        so the model cleans text the way *this* user likes (no retraining).
 *  - L4: [exportJsonl] dumps the corpus for offline fine-tuning in the openwispr-finetune
 *        repo (training stays out of the app).
 *
 * Never uploaded. Gated by the same "Keep history" switch as the visible feed, and
 * ring-capped so it can't grow without bound.
 */
object CorrectionCorpus {
    private const val FILE = "correction_corpus.jsonl"
    private const val MAX_ENTRIES = 500

    private fun file(c: Context) = File(c.applicationContext.filesDir, FILE)

    /** Append an accepted dictation (newest-first). No-op when history is disabled. */
    fun record(c: Context, sample: CorrectionSample) {
        if (!DictationHistory.keepHistory(c)) return
        if (sample.final.isBlank()) return
        runCatching {
            val f = file(c)
            val existing = if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
            val trimmed = (listOf(sample.toJson().toString()) + existing).take(MAX_ENTRIES)
            f.writeText(trimmed.joinToString("\n"))
        }
    }

    /** Newest-first samples. */
    fun all(c: Context): List<CorrectionSample> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines().filter { it.isNotBlank() }.map { CorrectionSample.fromJson(JSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun clear(c: Context) { runCatching { file(c).delete() } }

    /**
     * The [k] most relevant past corrections for polishing [query] in [category] — used
     * as few-shot examples. Pure ranking lives in [rank] so it's unit-testable.
     */
    fun similar(c: Context, query: String, category: String, k: Int = 2): List<CorrectionSample> =
        rank(all(c), query, category, k)

    /**
     * Export the corpus as fine-tuning JSONL ({context, input, output}) for the
     * openwispr-finetune repo. Only rows the user actually corrected — those carry the
     * signal; verbatim accepts would just teach the model to echo its own output.
     */
    fun exportJsonl(c: Context): String = all(c)
        .filter { it.edited && it.cleaned.isNotBlank() && it.final.isNotBlank() && it.cleaned != it.final }
        .joinToString("\n") {
            JSONObject().put("context", it.category).put("input", it.cleaned).put("output", it.final).toString()
        }

    // ---- pure ranking (testable) ----

    /** Tokens for overlap scoring: lowercased word set. */
    internal fun tokens(s: String): Set<String> =
        Regex("[\\p{L}\\p{N}']+").findAll(s.lowercase()).map { it.value }.filter { it.length > 1 }.toSet()

    private const val MIN_JACCARD = 0.2 // real word overlap, not a coincidental stopword

    /**
     * Relevance of a sample to [query]: token Jaccard, then small boosts for same-category
     * and actually-corrected rows. The boosts only re-order genuine matches — a sample below
     * [MIN_JACCARD] scores 0 so a shared "the" can never be rescued into the few-shot set.
     */
    internal fun score(query: Set<String>, s: CorrectionSample, category: String): Double {
        if (query.isEmpty()) return 0.0
        val st = tokens(s.cleaned)
        if (st.isEmpty()) return 0.0
        val inter = query.count { it in st }.toDouble()
        val jaccard = inter / (query.size + st.size - inter)
        if (jaccard < MIN_JACCARD) return 0.0
        var score = jaccard
        if (s.category == category) score += 0.15      // same app context is more relevant
        if (s.edited) score += 0.10                     // a real correction teaches more than a verbatim accept
        return score
    }

    internal fun rank(samples: List<CorrectionSample>, query: String, category: String, k: Int): List<CorrectionSample> {
        val q = tokens(query)
        if (q.isEmpty()) return emptyList()
        return samples
            .asSequence()
            .filter { it.final.isNotBlank() && it.cleaned.isNotBlank() }
            .map { it to score(q, it, category) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
            .toList()
    }
}
