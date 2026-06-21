package com.voicerewriter.textproc

/**
 * Converts spoken number words into digit form. Kotlin port of the cleanup pipeline's
 * NumberNormalizer (github.com/openwispr).
 *
 * Handles cardinals ("one hundred twenty three" -> "123"), ordinals
 * ("twenty first" -> "21st"), and decimals ("three point one four" -> "3.14").
 * Zero-latency and deterministic — fixes spoken numbers that tiny LLMs fumble.
 */
object NumberNormalizer {

    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return text

        val result = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            val (consumed, replacement) = tryConsumeNumber(words, i)
            // Don't convert a lone pronoun/article "one" ("the other one", "no one",
            // "one of them") — only as part of a larger number ("twenty one").
            if (consumed == 1 && words[i].lowercase().trim(',', '.', '!', '?', ';', ':') == "one" &&
                isPronounOne(result.lastOrNull(), words.getOrNull(i + 1))) {
                result.add(words[i]); i += 1; continue
            }
            if (consumed > 0) {
                result.add(replacement)
                i += consumed
            } else {
                result.add(words[i])
                i += 1
            }
        }
        return result.joinToString(" ")
    }

    private val oneDeterminers = setOf(
        "the", "this", "that", "other", "another", "no", "any", "each", "every",
        "which", "some", "such", "only",
    )

    private fun isPronounOne(prev: String?, next: String?): Boolean {
        val p = prev?.lowercase()?.trim(',', '.', '!', '?', ';', ':', '"', '\'')
        if (p != null && p in oneDeterminers) return true
        return next?.lowercase()?.trim(',', '.', '!', '?', ';', ':') == "of"
    }

    // ---- lookups ----

    private val ones = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
    )

    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    private val multipliers = mapOf(
        "hundred" to 100, "thousand" to 1_000,
        "million" to 1_000_000, "billion" to 1_000_000_000,
    )

    private val ordinalOnes = mapOf(
        "first" to (1 to "st"), "second" to (2 to "nd"), "third" to (3 to "rd"),
        "fourth" to (4 to "th"), "fifth" to (5 to "th"), "sixth" to (6 to "th"),
        "seventh" to (7 to "th"), "eighth" to (8 to "th"), "ninth" to (9 to "th"),
        "tenth" to (10 to "th"), "eleventh" to (11 to "th"), "twelfth" to (12 to "th"),
        "thirteenth" to (13 to "th"), "fourteenth" to (14 to "th"),
        "fifteenth" to (15 to "th"), "sixteenth" to (16 to "th"),
        "seventeenth" to (17 to "th"), "eighteenth" to (18 to "th"),
        "nineteenth" to (19 to "th"),
    )

    private val ordinalTens = mapOf(
        "twentieth" to (20 to "th"), "thirtieth" to (30 to "th"),
        "fortieth" to (40 to "th"), "fiftieth" to (50 to "th"),
        "sixtieth" to (60 to "th"), "seventieth" to (70 to "th"),
        "eightieth" to (80 to "th"), "ninetieth" to (90 to "th"),
    )

    private val ordinalMultipliers = mapOf(
        "hundredth" to (100 to "th"), "thousandth" to (1_000 to "th"),
    )

    /** Returns (wordsConsumed, replacement); (0, "") when no number is found. */
    private fun tryConsumeNumber(words: List<String>, startIndex: Int): Pair<Int, String> {
        var i = startIndex
        var total = 0
        var current = 0
        var consumed = 0
        var isOrdinal = false
        var ordinalSuffix = ""
        var hasDecimal = false
        val decimalDigits = ArrayList<Int>()
        var hasNumberWord = false
        var lastWasBareOnes = false // current holds a ones value with no multiplier after it

        while (i < words.size) {
            val word = words[i].lowercase()

            // Skip "and" between number words (only once we've started).
            if (word == "and" && hasNumberWord) {
                i += 1; consumed += 1; continue
            }

            // "a hundred", "a thousand"
            if (word == "a" && !hasNumberWord) {
                if (i + 1 < words.size && multipliers[words[i + 1].lowercase()] != null) {
                    current = 1; hasNumberWord = true; i += 1; consumed += 1; continue
                }
                break
            }

            // Decimal: after "point", collect single digits.
            if (word == "point" && hasNumberWord && !hasDecimal) {
                hasDecimal = true; i += 1; consumed += 1
                while (i < words.size) {
                    val v = ones[words[i].lowercase()]
                    if (v != null && v <= 9) { decimalDigits.add(v); i += 1; consumed += 1 } else break
                }
                if (decimalDigits.isEmpty()) { hasDecimal = false; consumed -= 1; i -= 1 }
                break
            }

            // Ordinal multipliers: "hundredth", "thousandth" — terminate the sequence.
            val ordMult = ordinalMultipliers[word]
            if (ordMult != null) {
                if (current == 0) current = 1
                total += current * ordMult.first; current = 0
                isOrdinal = true; ordinalSuffix = ordMult.second; hasNumberWord = true
                consumed += 1; i += 1
                break
            }

            // Ordinal tens: "twentieth" — ones cannot precede them (same rule as tens).
            val ordTen = ordinalTens[word]
            if (ordTen != null) {
                if (lastWasBareOnes) break
                current += ordTen.first; isOrdinal = true; ordinalSuffix = ordTen.second
                hasNumberWord = true; consumed += 1; i += 1
                break
            }

            // Ordinal ones: "first", "second" — terminate the sequence.
            val ordOne = ordinalOnes[word]
            if (ordOne != null) {
                current += ordOne.first; isOrdinal = true; ordinalSuffix = ordOne.second
                hasNumberWord = true; consumed += 1; i += 1
                break
            }

            val mult = multipliers[word]
            if (mult != null) {
                if (current == 0) current = 1
                if (mult >= 1_000) { total = (total + current) * mult; current = 0 }
                else current *= mult
                hasNumberWord = true; lastWasBareOnes = false; consumed += 1; i += 1; continue
            }

            // Tens — ones cannot precede tens without a multiplier ("two thirty" stays split).
            val tenVal = tens[word]
            if (tenVal != null) {
                if (lastWasBareOnes) break
                current += tenVal; hasNumberWord = true; lastWasBareOnes = false; consumed += 1; i += 1; continue
            }

            val oneVal = ones[word]
            if (oneVal != null) {
                current += oneVal; hasNumberWord = true; lastWasBareOnes = true; consumed += 1; i += 1; continue
            }

            break
        }

        if (!hasNumberWord) return 0 to ""

        // Back off a trailing "and".
        while (consumed > 0 && words[startIndex + consumed - 1].lowercase() == "and") consumed -= 1
        if (consumed <= 0) return 0 to ""

        total += current

        return when {
            hasDecimal -> consumed to "$total.${decimalDigits.joinToString("")}"
            isOrdinal -> consumed to "$total$ordinalSuffix"
            else -> consumed to "$total"
        }
    }
}
