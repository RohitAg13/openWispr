package com.voicerewriter.textproc

/**
 * Deterministic sentence capitalization: capitalize the first letter of the text,
 * the first letter after a sentence terminator (`.` `!` `?` followed by whitespace),
 * and the first letter after a newline. Never lowercases anything, so existing
 * casing ("iPhone", proper nouns) is preserved. Mid-token dots ("fast.ai", "3.14")
 * are not treated as terminators because they aren't followed by whitespace.
 *
 * Inferred *commas* / sentence boundaries are out of scope here (that's the
 * fine-tuned LLM's job); this only fixes capitalization at obvious boundaries.
 */
object Capitalizer {

    // Leading characters to skip over when looking for the first letter of a sentence.
    private val leadingMarks = setOf('"', '“', '\'', '‘', '(', '[')

    fun capitalizeSentences(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text)
        var capNext = true
        for (i in sb.indices) {
            val c = sb[i]
            when {
                c == '\n' -> capNext = true
                c.isWhitespace() || c in leadingMarks -> { /* keep waiting for the first letter */ }
                capNext && c.isLetter() -> { sb[i] = c.uppercaseChar(); capNext = false }
                else -> capNext = false // sentence content has begun
            }
            // A terminator starts a new sentence only when followed by whitespace/end.
            if (c == '.' || c == '!' || c == '?') {
                val next = if (i + 1 < sb.length) sb[i + 1] else ' '
                if (next.isWhitespace()) capNext = true
            }
        }
        return sb.toString()
    }
}
