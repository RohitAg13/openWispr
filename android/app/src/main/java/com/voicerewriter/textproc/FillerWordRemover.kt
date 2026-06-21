package com.voicerewriter.textproc

/**
 * Removes filler words using word-boundary-aware regex. Kotlin port of the cleanup pipeline's
 * FillerWordRemover (github.com/openwispr).
 *
 * Handles single-word fillers ("um", "uh") and phrases ("you know"), then cleans
 * up the double spaces and orphaned commas removal leaves behind. Context-sensitive
 * phrases like "you know" are guarded: when preceded by an auxiliary verb they're a
 * real verb phrase ("do you know where it is?") and kept.
 */
object FillerWordRemover {

    /** Filler -> preceding words that mark it as a verb phrase (so keep it). */
    private val guardedFillers: Map<String, Set<String>> = mapOf(
        "you know" to setOf(
            "do", "did", "didn't", "don't", "doesn't",
            "if", "whether", "that",
            "could", "would", "should", "can", "will", "might", "may",
            "won't", "couldn't", "wouldn't", "shouldn't", "shall",
        ),
    )

    /**
     * Hesitation sounds and their repeated-letter variants — always noise. Covers
     * "um/umm/ummm", "uh/uhh", "uhm", "hm/hmm", "mm/mmm", "mhm", "er/erm/ermm".
     * Word-boundaried + comma-absorbing; "mm"/"er" require the safe forms (>=2 m,
     * exact "er") so single letters and real words aren't touched.
     */
    private val hesitation = Regex(
        "(,\\s*)?\\b(um+|uh+|uhm+|hm+|mm+|mhm+|erm+|er)\\b(\\s*,)?",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Verbal-tic fillers that are only removed when comma/clause-bounded — so the
     * meaningful uses ("kind of blue", "I guess so") are left alone, but the tic
     * uses ("it's done, kind of", "you know what I mean,") are stripped.
     */
    private val edgeFillers = listOf("you know what i mean", "i guess", "kind of", "sort of", "or whatever")

    fun removeFillers(text: String, fillerWords: List<String>): String {
        if (text.isEmpty()) return text

        // 1. Hesitation sounds (independent of the configurable list).
        var result = hesitation.replace(text, "")

        // 2. Comma/clause-bounded verbal tics (the meaningful uses have no comma).
        for (f in edgeFillers) {
            val e = Regex.escape(f)
            result = Regex(",\\s*$e\\b", RegexOption.IGNORE_CASE).replace(result, "")  // "done, kind of"
            result = Regex("\\b$e\\s*,", RegexOption.IGNORE_CASE).replace(result, "")  // "kind of, it's…"
        }

        // 3. Configured phrase fillers, longest-first ("you know" before "you").
        for (filler in fillerWords.sortedByDescending { it.length }) {
            val escaped = Regex.escape(filler)
            val regex = Regex("(,\\s*)?\\b$escaped\\b(\\s*,)?", RegexOption.IGNORE_CASE)
            val guard = guardedFillers[filler.lowercase()]
            result = if (guard != null) removeWithGuards(result, regex, guard)
            else regex.replace(result, "")
        }
        return cleanUp(result)
    }

    /** Remove matches only when NOT preceded by a guard word. */
    private fun removeWithGuards(text: String, regex: Regex, guardWords: Set<String>): String {
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return text

        val sb = StringBuilder()
        var cursor = 0
        for (m in matches) {
            val precedingWord = text.substring(0, m.range.first)
                .split(Regex("\\s+")).lastOrNull { it.isNotEmpty() }
                ?.lowercase()?.trim('.', ',', '!', '?', ';', ':', '"', '\'')
            if (precedingWord != null && guardWords.contains(precedingWord)) continue // keep it
            sb.append(text, cursor, m.range.first)
            cursor = m.range.last + 1
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }

    private fun cleanUp(text: String): String {
        var r = text
        r = r.replace(Regex("\\s{2,}"), " ")            // collapse runs of spaces
        r = r.replace(Regex("\\s+([.,!?;:])"), "$1")     // no space before punctuation
        r = r.replace(Regex("^\\s*,\\s*"), "")            // orphaned leading comma
        r = r.replace(Regex(",\\s*,"), ",")               // double commas from adjacent removals
        return r.trim()
    }
}
