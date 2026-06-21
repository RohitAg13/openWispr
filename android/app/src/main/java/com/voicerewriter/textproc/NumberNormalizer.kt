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
            if (consumed > 0 && shouldDigitize(words, i, consumed, replacement)) {
                result.add(replacement)
                i += consumed
            } else if (consumed > 0) {
                // Keep the original spelled-out words (prose convention: small
                // standalone numbers like "one", "two", "ten", "first" stay words).
                for (k in 0 until consumed) result.add(words[i + k])
                i += consumed
            } else {
                result.add(words[i])
                i += 1
            }
        }
        return result.joinToString(" ")
    }

    private fun strip(s: String?): String? =
        s?.lowercase()?.trim(',', '.', '!', '?', ';', ':', '"', '\'', '(', ')')

    /** Units/labels that signal a number is really numeric ("twenty minutes", "room four"). */
    private val numericContext = setOf(
        // units
        "dollars", "dollar", "cents", "cent", "bucks", "rupees", "rupee", "euros", "euro",
        "pounds", "pound", "kg", "kgs", "kilograms", "kilogram", "grams", "gram", "mg", "lbs",
        "miles", "mile", "km", "kilometers", "meters", "meter", "cm", "mm",
        "minutes", "minute", "mins", "min", "hours", "hour", "hrs", "seconds", "second", "secs",
        "days", "day", "weeks", "week", "months", "month", "years", "year", "am", "pm", "o'clock",
        "gb", "mb", "kb", "tb", "px", "percent", "degrees", "degree", "x",
        // labels
        "room", "page", "pages", "chapter", "step", "steps", "version", "floor", "level",
        "number", "line", "lines", "apartment", "unit", "grade", "rank", "item", "items",
        "question", "port", "figure", "table", "section", "phase", "part", "age", "id",
    )

    private val pronounOneDeterminers = setOf(
        "the", "this", "that", "other", "another", "no", "any", "each", "every",
        "which", "some", "such", "only", "latest", "last", "next", "first", "best",
    )

    /**
     * Whether a consumed number should become digits. Conservative to match prose:
     * multi-token numbers and decimals always convert; a lone small number converts
     * only when a unit/label sits next to it. Keeps "Two Tower", "Epic One",
     * "the latest one", "Ten things" as words.
     */
    private fun shouldDigitize(words: List<String>, start: Int, consumed: Int, replacement: String): Boolean {
        if (replacement.contains('.')) return true       // decimals: "3.14"
        if (consumed >= 2) return true                    // "twenty three", "one hundred ..."
        val prev = strip(words.getOrNull(start - 1))
        val next = strip(words.getOrNull(start + consumed))
        val word = strip(words[start]) ?: ""
        // Lone "one" as pronoun/article: never digitize ("the other one", "one of them").
        if (word == "one" && (prev in pronounOneDeterminers || next == "of")) return false
        // Adjacent numeric context (unit/label/currency) digitizes even small numbers.
        if (prev in numericContext || next in numericContext) return true
        if (prev == "$" || prev == "#" || next == "%") return true
        // Style convention: spell out one–nine, digitize ten and above.
        val value = replacement.takeWhile { it.isDigit() }.toIntOrNull() ?: return false
        return value >= 10
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
                // Two bare ones/teens in a row ("ten eleven", "five six") is approximate
                // speech, not a composite number — stop so each stays separate.
                if (lastWasBareOnes) break
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
