package com.voicerewriter.textproc

/**
 * Resolves spoken self-corrections, keeping only the corrected version. A
 * conservative Kotlin subset of the cleanup pipeline's SelfCorrectionDetector
 * (github.com/openwispr) — it ports the two high-value, low-risk
 * behaviors and deliberately errs toward leaving text unchanged when unsure:
 *
 *  1. **Standalone restarts** ("scratch that", "actually no", "never mind") that
 *     act as their own clause discard everything before them.
 *  2. **Inline repairs** ("send it to mark, I mean john") splice the correction
 *     over the erroneous fragment using first-token overlap.
 *
 * The full the cleanup pipeline detector has far more nuance (weak-overlap copula checks,
 * idiom handling, per-marker punctuation gating); we keep the safe core.
 */
object SelfCorrectionDetector {

    /** Restart markers: when they form their own clause, drop everything before. */
    private val restartMarkers = listOf(
        "let me start over", "let me rephrase", "scratch all that", "scratch that",
        "actually no", "never mind", "nevermind", "forget that", "forget it",
        "start over", "no no no", "no no",
    )

    /** A value (digit/time or small number word) for the "X … actually Y" swap heuristic. */
    private const val VAL =
        "(?:\\d+(?::\\d+)?(?:\\s?[ap]\\.?m\\.?)?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"

    /**
     * Numeric mind-changes: "meet at 2 actually 3" / "2pm no 3pm" / "two, make that
     * three" -> keep only the corrected value. Both sides must be values, so prose
     * isn't touched. Worded/multi-word corrections are left for the LLM.
     */
    private val valueSwap = Regex(
        "\\b($VAL)[\\s,.…]*(?:actually|no,?\\s+make that|make that|or rather|i mean|no)\\s+($VAL)(?=\\W|$)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Inline repair markers: the text after them replaces a just-spoken fragment.
     * `gated` markers (bare common words) only fire after a comma/clause break to
     * avoid false positives ("I'm sorry to hear that" must not trigger).
     */
    private data class Inline(val phrase: String, val gated: Boolean)
    private val inlineMarkers = listOf(
        Inline("oops i meant", false), Inline("on second thought", false),
        Inline("wait hold on", false), Inline("no make that", false),
        Inline("make that", false), Inline("let's make it", false),
        Inline("or rather", false), Inline("no wait", false),
        Inline("i mean", true), Inline("actually", true),
        Inline("sorry", true), Inline("wait", true),
    )

    /** Words that introduce a brand-new full clause (a total replacement). */
    private val clauseStarters = setOf(
        "i", "i'm", "i've", "i'd", "i'll", "it", "it's",
        "we", "we're", "we've", "we'd", "we'll",
        "you", "you're", "you've", "you'd", "you'll",
        "he", "he's", "she", "she's", "they", "they're",
        "there", "here", "let's", "please",
    )

    /** Filler lead-ins that may sit between a clause break and a restart marker. */
    private val leadIns = setOf("oh", "uh", "um", "erm", "well", "so", "sorry", "hmm", "hm", "oops")

    fun detectAndResolve(text: String): String {
        if (text.isEmpty()) return text
        var t = valueSwap.replace(text) { it.groupValues[2] }  // "2 actually 3" -> "3"
        t = resolveStandaloneRestarts(t)
        t = splitIntoSentences(t).joinToString(" ") { resolveInline(it) }
        return t.trim()
    }

    // ---- standalone restarts ----

    /**
     * If a restart marker appears as its own clause (preceded by a clause break or
     * only filler lead-ins) with content after it, drop the prefix. We take the
     * LAST such marker so chained restarts collapse to the final intent.
     */
    private fun resolveStandaloneRestarts(text: String): String {
        var result = text
        var changed = true
        // Re-scan after each cut so "a, scratch that, b, no no, c" -> "c".
        while (changed) {
            changed = false
            var best = -1
            var bestEnd = -1
            for (marker in restartMarkers) {
                val regex = Regex("\\b${Regex.escape(marker)}\\b", RegexOption.IGNORE_CASE)
                for (m in regex.findAll(result)) {
                    if (!isClauseStart(result, m.range.first)) continue
                    val after = result.substring(m.range.last + 1)
                        .trimStart(' ', ',', '.', '!', '?', ';', ':')
                    if (after.isBlank()) continue // nothing to keep -> not a restart
                    if (m.range.first >= best) { best = m.range.first; bestEnd = m.range.last }
                }
            }
            if (best >= 0) {
                result = result.substring(bestEnd + 1)
                    .trimStart(' ', ',', '.', '!', '?', ';', ':')
                    .trim()
                changed = true
            }
        }
        return result
    }

    /** True if the position is the start of a clause (text start, after punctuation, or only lead-ins before it). */
    private fun isClauseStart(text: String, index: Int): Boolean {
        val prefix = text.substring(0, index).trimEnd()
        if (prefix.isEmpty()) return true
        if (prefix.last() in ",.!?;:") return true
        // Allow a short run of filler lead-ins after the last clause break.
        val lastBreak = prefix.indexOfLast { it in ",.!?;:" }
        val tail = prefix.substring(lastBreak + 1).trim()
        if (tail.isEmpty()) return true
        return tail.split(Regex("\\s+")).all { leadIns.contains(it.lowercase()) }
    }

    // ---- inline repairs ----

    private fun resolveInline(sentence: String): String {
        val words = sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 3) return sentence

        // Find the last inline marker occurrence as a word span.
        var markerStart = -1
        var markerLen = 0
        for (marker in inlineMarkers) {
            val parts = marker.phrase.split(" ")
            var i = 0
            while (i + parts.size <= words.size) {
                val window = (0 until parts.size).all {
                    words[i + it].lowercase().trim(',', '.', '!', '?', ';', ':') == parts[it]
                }
                if (window && i > 0 && i >= markerStart) {
                    // Gated markers require a clause break right before them.
                    if (marker.gated && !words[i - 1].endsWith(",") &&
                        words[i - 1].lastOrNull() !in listOf('.', ';', ':')) { i++; continue }
                    markerStart = i; markerLen = parts.size
                }
                i++
            }
        }
        if (markerStart <= 0) return sentence

        val before = words.subList(0, markerStart).toMutableList()
        val after = words.subList(markerStart + markerLen, words.size).toList()
        if (after.isEmpty()) {
            // "...john, I mean" -> just drop the dangling marker.
            return before.joinToString(" ")
        }

        // A correction that starts a fresh clause replaces the whole sentence.
        val afterHead = after[0].lowercase().trim(',', '.', '!', '?', ';', ':')
        if (after.size > 6 || clauseStarters.contains(afterHead)) {
            return after.joinToString(" ").replaceFirstChar { it.uppercase() }
        }

        // Fragment correction: splice `after` over the erroneous tail of `before`.
        // Strip a trailing comma the marker left on the last before-word.
        val cleanedBefore = before.toMutableList()
        cleanedBefore[cleanedBefore.size - 1] =
            cleanedBefore.last().trimEnd(',', '.', '!', '?', ';', ':')

        val anchor = afterHead
        // Prefer first-token overlap ("to mark" / "to john" share "to").
        val overlapIdx = cleanedBefore.indexOfLast {
            it.lowercase().trim(',', '.', '!', '?', ';', ':') == anchor
        }
        val kept: List<String> = if (overlapIdx >= 0) {
            // Shared first token ("to mark" / "to john") -> replace from the anchor.
            cleanedBefore.subList(0, overlapIdx)
        } else {
            // No shared anchor: assume the speaker corrected just the last token
            // ("send it to mark, I mean john@..." -> drop only "mark"). Dropping a
            // length-matched span here would wrongly eat the verb when the
            // correction is long (e.g. an email or phrase).
            cleanedBefore.subList(0, (cleanedBefore.size - 1).coerceAtLeast(0))
        }
        return (kept + after).joinToString(" ")
    }

    // ---- sentence splitting ----

    private fun splitIntoSentences(text: String): List<String> {
        // Split only on a terminator followed by whitespace, so mid-token dots stay
        // intact: "fast.ai", "resume.pdf", "3.14" are NOT sentence boundaries.
        return text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
