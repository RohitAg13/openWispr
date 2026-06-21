package com.voicerewriter.textproc

import kotlin.math.max

/**
 * Snaps mis-transcribed names/terms back to their canonical spelling using a
 * personal vocabulary. Runs after STT, before the cleanup pipeline. Pure and
 * deterministic so it's JVM-testable and never hallucinates outside the list.
 *
 * Matching, conservative by design:
 *  - Exact alias/canonical match (case-insensitive, whole word) -> always replace.
 *  - Fuzzy match -> Soundex phonetic equality + small edit distance, gated against
 *    common English words and short tokens, so "right"/"to"/"one" are never touched.
 *
 * Phase 2 of docs/names-emails-plan.md.
 */
object VocabCorrector {

    private const val FUZZY_THRESHOLD = 0.84
    private const val MIN_FUZZY_LEN = 4

    private data class Target(val canonical: String, val tokens: List<String>)
    private data class Tok(val norm: String, val start: Int, val end: Int)
    private data class Hit(val start: Int, val end: Int, val replacement: String, val score: Double, val span: Int)

    /**
     * A compact glossary string to bias Whisper's decoding (initial_prompt). Only
     * canonical spellings — never aliases (those are mishearings we don't want
     * emitted). Capped to stay within Whisper's prompt-token budget (~224 tokens).
     */
    fun biasPrompt(vocab: List<VocabEntry>, maxChars: Int = 200): String {
        if (vocab.isEmpty()) return ""
        val sb = StringBuilder("Glossary: ")
        for (e in vocab) {
            val c = e.canonical.trim()
            if (c.isEmpty()) continue
            if (sb.length + c.length + 2 > maxChars) break
            if (!sb.endsWith(": ")) sb.append(", ")
            sb.append(c)
        }
        return if (sb.endsWith(": ")) "" else sb.append(".").toString()
    }

    fun correct(text: String, vocab: List<VocabEntry>): String {
        if (text.isBlank() || vocab.isEmpty()) return text
        val toks = tokenize(text)
        if (toks.isEmpty()) return text

        // Build match targets, longest phrase first so "row hit" beats "hit".
        val targets = ArrayList<Target>()
        for (e in vocab) {
            val canon = e.canonical.trim()
            if (canon.isEmpty()) continue
            for (phrase in e.matchPhrases()) {
                val ptoks = phrase.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (ptoks.isNotEmpty()) targets.add(Target(canon, ptoks))
            }
        }
        targets.sortByDescending { it.tokens.size }

        val hits = ArrayList<Hit>()
        for (t in targets) {
            val span = t.tokens.size
            var i = 0
            while (i + span <= toks.size) {
                val window = toks.subList(i, i + span)
                val score = matchScore(window.map { it.norm }, t.tokens)
                if (score >= FUZZY_THRESHOLD) {
                    hits.add(Hit(window.first().start, window.last().end, t.canonical, score, span))
                }
                i++
            }
        }
        if (hits.isEmpty()) return text

        // Greedy: prefer higher score, then longer span; apply non-overlapping.
        hits.sortWith(compareByDescending<Hit> { it.score }.thenByDescending { it.span })
        val taken = ArrayList<Hit>()
        for (h in hits) {
            if (taken.none { it.start < h.end && h.start < it.end }) taken.add(h)
        }
        taken.sortByDescending { it.start } // apply right-to-left so offsets stay valid

        val sb = StringBuilder(text)
        for (h in taken) sb.replace(h.start, h.end, h.replacement)
        return sb.toString()
    }

    // ---- matching ----

    /** 0..1 similarity of an input window to a target phrase (token-wise). */
    private fun matchScore(window: List<String>, phrase: List<String>): Double {
        if (window.size != phrase.size) return 0.0
        if (window == phrase) return 1.0 // exact alias/canonical
        var sum = 0.0
        for (k in window.indices) {
            val a = window[k]; val b = phrase[k]
            if (a == b) { sum += 1.0; continue }
            // Guard: don't fuzzy-correct common words or short tokens.
            if (a in COMMON_WORDS || a.length < MIN_FUZZY_LEN || b.length < MIN_FUZZY_LEN) return 0.0
            val dist = levenshtein(a, b)
            val editSim = 1.0 - dist.toDouble() / max(a.length, b.length)
            val s = if (soundex(a) == soundex(b)) 0.7 + 0.3 * editSim else editSim
            if (s < FUZZY_THRESHOLD) return 0.0
            sum += s
        }
        return sum / window.size
    }

    private fun tokenize(text: String): List<Tok> {
        val out = ArrayList<Tok>()
        for (m in Regex("[\\p{L}\\p{N}'’]+").findAll(text)) {
            out.add(Tok(m.value.lowercase().trim('\'', '’'), m.range.first, m.range.last + 1))
        }
        return out
    }

    // ---- phonetics / distance ----

    /** Classic Soundex (first letter + 3 digits). Good enough for name matching. */
    fun soundex(s: String): String {
        val up = s.uppercase().filter { it in 'A'..'Z' }
        if (up.isEmpty()) return "0000"
        fun code(c: Char): Char = when (c) {
            'B', 'F', 'P', 'V' -> '1'
            'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> '2'
            'D', 'T' -> '3'
            'L' -> '4'
            'M', 'N' -> '5'
            'R' -> '6'
            else -> '0' // vowels + H,W,Y
        }
        val sb = StringBuilder().append(up[0])
        var prev = code(up[0])
        for (i in 1 until up.length) {
            val c = code(up[i])
            if (c != '0' && c != prev) sb.append(c)
            // H and W don't reset the "previous code" gate; vowels do.
            if (up[i] != 'H' && up[i] != 'W') prev = c
            if (sb.length >= 4) break
        }
        return (sb.toString() + "000").substring(0, 4)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val cur = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
        }
        return dp[b.length]
    }

    /** Frequent words we never fuzzy-correct into a vocab term. */
    private val COMMON_WORDS = setOf(
        "the", "and", "for", "are", "you", "your", "with", "this", "that", "have",
        "from", "they", "what", "when", "will", "would", "there", "their", "right",
        "write", "here", "hear", "one", "two", "ten", "now", "not", "but", "can",
        "all", "any", "out", "our", "was", "his", "her", "him", "she", "who", "how",
        "why", "yes", "let", "set", "get", "got", "see", "way", "day", "new", "use",
        "make", "like", "just", "need", "want", "into", "over", "then", "than",
    )
}
