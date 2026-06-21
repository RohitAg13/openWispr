package com.voicerewriter.textproc

/**
 * Turns explicit spoken structure into real newlines:
 *  - "new line" -> "\n", "new paragraph" -> "\n\n".
 *  - explicit numbered enumerations ("for 1. apples 2. bananas 3. oranges", or
 *    "first … second … third …") -> a newline-separated list.
 *
 * Conservative on purpose: a list only forms from a run of enumerators that starts
 * at 1/"first" and increases consecutively (≥2 for digit markers, ≥3 for ordinal
 * words, which are ambiguous). Natural enumerations without markers ("apples,
 * bananas and oranges") are left for the fine-tuned LLM.
 *
 * Runs after NumberNormalizer so digits are settled and no earlier stage flattens
 * the newlines it introduces.
 */
object ListFormatter {

    private val ordinals = linkedMapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
        "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10,
    )

    fun format(text: String): String {
        if (text.isBlank()) return text
        val withBreaks = convertNewlineMarks(text)
        if (withBreaks.contains('\n')) return withBreaks // explicit breaks win; don't also list-ify
        return formatNumberedList(withBreaks)
    }

    private fun convertNewlineMarks(text: String): String {
        var r = text
        r = Regex("\\bnew\\s+paragraph\\b", RegexOption.IGNORE_CASE).replace(r, "\n\n")
        r = Regex("\\bnew\\s+lines?\\b|\\bnewline\\b", RegexOption.IGNORE_CASE).replace(r, "\n")
        r = r.replace(Regex("[ \\t]*\\n[ \\t]*"), "\n") // tidy spaces around inserted breaks
        return r.trim()
    }

    /** (markerStart, markerEnd, value) for each enumerator found, in text order. */
    private fun formatNumberedList(text: String): String {
        // Digit markers: "1. " / "2) " — separator + following whitespace (so "2.0" / times don't match).
        val digit = Regex("(?<=^|\\s)(\\d+)\\s*[.)]\\s+").findAll(text)
            .map { Triple(it.range.first, it.range.last + 1, it.groupValues[1].toInt()) }.toList()
        buildList(text, digit, minRun = 2)?.let { return it }

        // Ordinal words at a clause start ("First, … Second, …"). Ambiguous → require ≥3.
        val ords = Regex("(?<=^|[.,;:]\\s)(${ordinals.keys.joinToString("|")})\\b,?\\s+", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { m -> ordinals[m.groupValues[1].lowercase()]?.let { Triple(m.range.first, m.range.last + 1, it) } }
            .toList()
        buildList(text, ords, minRun = 3)?.let { return it }

        return text
    }

    private fun buildList(text: String, markers: List<Triple<Int, Int, Int>>, minRun: Int): String? {
        if (markers.size < minRun) return null
        val start = markers.indexOfFirst { it.third == 1 }
        if (start < 0) return null
        var end = start
        var expected = 2
        var j = start + 1
        while (j < markers.size && markers[j].third == expected) { end = j; expected++; j++ }
        if (end - start + 1 < minRun) return null

        val run = markers.subList(start, end + 1)
        val sb = StringBuilder()
        val leadIn = text.substring(0, run.first().first).trim()
        if (leadIn.isNotEmpty()) sb.append(leadIn).append("\n")
        for ((k, m) in run.withIndex()) {
            val itemEnd = if (k + 1 < run.size) run[k + 1].first else text.length
            val item = text.substring(m.second, itemEnd).trim().trimEnd(',', ';')
            if (item.isEmpty()) return null
            sb.append("${m.third}. ").append(item)
            if (k < run.size - 1) sb.append("\n")
        }
        return sb.toString()
    }
}
