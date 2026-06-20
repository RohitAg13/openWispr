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

    fun removeFillers(text: String, fillerWords: List<String>): String {
        if (text.isEmpty() || fillerWords.isEmpty()) return text

        var result = text
        // Longest-first so "you know" is matched before "you".
        val sorted = fillerWords.sortedByDescending { it.length }

        for (filler in sorted) {
            val escaped = Regex.escape(filler)
            // Optional surrounding commas absorb commas around a parenthetical filler.
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
