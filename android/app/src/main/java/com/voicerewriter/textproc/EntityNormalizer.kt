package com.voicerewriter.textproc

/**
 * Deterministic fixes for spoken entities (names, acronyms) that STT renders as
 * separated letters. Conservative and cue-gated to avoid false positives.
 *
 * Part of Phase 1 of docs/names-emails-plan.md. The riskier entity rules
 * (multi-word email domains, NATO "B as in boy") are intentionally NOT here —
 * they're too ambiguous for a deterministic pass and are deferred to the
 * personal-vocabulary phase.
 */
object EntityNormalizer {

    /**
     * Join a run of single letters that follows a spelling cue into one word.
     * "her name is spelled k a y l a" -> "her name is spelled Kayla".
     * "the code is spelt a b c one" -> only the letter run joins: "ABC" (digits stop it).
     *
     * Gated on an explicit cue ("spelled"/"spelt"/"spell it") so ordinary single
     * letters ("plan B", "grade A") are never touched.
     */
    fun joinSpelledLetters(text: String): String {
        val regex = Regex(
            "\\b(spelled|spelt|spell it|spell that)\\s+((?:[a-zA-Z]\\s+){1,}[a-zA-Z])\\b",
            RegexOption.IGNORE_CASE,
        )
        return regex.replace(text) { m ->
            val cue = m.groupValues[1]
            val letters = m.groupValues[2].split(Regex("\\s+")).filter { it.isNotEmpty() }
            // A name (>=3 letters) is Title-cased; a short run (likely an acronym) is upper-cased.
            val joined = letters.joinToString("") { it.lowercase() }
            val word = if (letters.size >= 3) joined.replaceFirstChar { it.uppercase() }
            else joined.uppercase()
            "$cue $word"
        }
    }
}
