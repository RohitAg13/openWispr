import Foundation

/// Converts spoken number words into digit form. Port of the cleanup pipeline's NumberNormalizer.
enum NumberNormalizer {

    static func normalize(_ text: String) -> String {
        if text.isEmpty { return text }
        let words = text.ktSplitWhitespace().filter { !$0.isEmpty }
        if words.isEmpty { return text }

        var result: [String] = []
        var i = 0
        while i < words.count {
            let (consumed, replacement) = tryConsumeNumber(words, i)
            if consumed > 0 && shouldDigitize(words, i, consumed, replacement) {
                result.append(replacement)
                i += consumed
            } else if consumed > 0 {
                for k in 0..<consumed { result.append(words[i + k]) }
                i += consumed
            } else {
                result.append(words[i])
                i += 1
            }
        }
        return result.joined(separator: " ")
    }

    private static func strip(_ s: String?) -> String? {
        s?.lowercased().ktTrim(",", ".", "!", "?", ";", ":", "\"", "'", "(", ")")
    }

    private static let numericContext: Set<String> = [
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
    ]

    private static let pronounOneDeterminers: Set<String> = [
        "the", "this", "that", "other", "another", "no", "any", "each", "every",
        "which", "some", "such", "only", "latest", "last", "next", "first", "best",
    ]

    private static func shouldDigitize(_ words: [String], _ start: Int, _ consumed: Int, _ replacement: String) -> Bool {
        if replacement.contains(".") { return true }       // decimals: "3.14"
        if consumed >= 2 { return true }                    // "twenty three", "one hundred ..."
        let prev = strip(words.ktGetOrNull(start - 1))
        let next = strip(words.ktGetOrNull(start + consumed))
        let word = strip(words[start]) ?? ""
        // Lone "one" as pronoun/article: never digitize.
        if word == "one" && ((prev != nil && pronounOneDeterminers.contains(prev!)) || next == "of") { return false }
        // Adjacent numeric context (unit/label/currency) digitizes even small numbers.
        if (prev != nil && numericContext.contains(prev!)) || (next != nil && numericContext.contains(next!)) { return true }
        if prev == "$" || prev == "#" || next == "%" { return true }
        // Style convention: spell out one–nine, digitize ten and above.
        guard let value = Int(replacement.prefix { $0.isNumber }) else { return false }
        return value >= 10
    }

    // ---- lookups ----

    private static let ones: [String: Int] = [
        "zero": 0, "one": 1, "two": 2, "three": 3, "four": 4,
        "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9,
        "ten": 10, "eleven": 11, "twelve": 12, "thirteen": 13,
        "fourteen": 14, "fifteen": 15, "sixteen": 16, "seventeen": 17,
        "eighteen": 18, "nineteen": 19,
    ]

    private static let tens: [String: Int] = [
        "twenty": 20, "thirty": 30, "forty": 40, "fifty": 50,
        "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90,
    ]

    private static let multipliers: [String: Int] = [
        "hundred": 100, "thousand": 1_000,
        "million": 1_000_000, "billion": 1_000_000_000,
    ]

    private static let ordinalOnes: [String: (Int, String)] = [
        "first": (1, "st"), "second": (2, "nd"), "third": (3, "rd"),
        "fourth": (4, "th"), "fifth": (5, "th"), "sixth": (6, "th"),
        "seventh": (7, "th"), "eighth": (8, "th"), "ninth": (9, "th"),
        "tenth": (10, "th"), "eleventh": (11, "th"), "twelfth": (12, "th"),
        "thirteenth": (13, "th"), "fourteenth": (14, "th"),
        "fifteenth": (15, "th"), "sixteenth": (16, "th"),
        "seventeenth": (17, "th"), "eighteenth": (18, "th"),
        "nineteenth": (19, "th"),
    ]

    private static let ordinalTens: [String: (Int, String)] = [
        "twentieth": (20, "th"), "thirtieth": (30, "th"),
        "fortieth": (40, "th"), "fiftieth": (50, "th"),
        "sixtieth": (60, "th"), "seventieth": (70, "th"),
        "eightieth": (80, "th"), "ninetieth": (90, "th"),
    ]

    private static let ordinalMultipliers: [String: (Int, String)] = [
        "hundredth": (100, "th"), "thousandth": (1_000, "th"),
    ]

    /// Returns (wordsConsumed, replacement); (0, "") when no number is found.
    private static func tryConsumeNumber(_ words: [String], _ startIndex: Int) -> (Int, String) {
        var i = startIndex
        var total = 0
        var current = 0
        var consumed = 0
        var isOrdinal = false
        var ordinalSuffix = ""
        var hasDecimal = false
        var decimalDigits: [Int] = []
        var hasNumberWord = false
        var lastWasBareOnes = false

        while i < words.count {
            let word = words[i].lowercased()

            // Skip "and" between number words (only once we've started).
            if word == "and" && hasNumberWord {
                i += 1; consumed += 1; continue
            }

            // "a hundred", "a thousand"
            if word == "a" && !hasNumberWord {
                if i + 1 < words.count && multipliers[words[i + 1].lowercased()] != nil {
                    current = 1; hasNumberWord = true; i += 1; consumed += 1; continue
                }
                break
            }

            // Decimal: after "point", collect single digits.
            if word == "point" && hasNumberWord && !hasDecimal {
                hasDecimal = true; i += 1; consumed += 1
                while i < words.count {
                    if let v = ones[words[i].lowercased()], v <= 9 {
                        decimalDigits.append(v); i += 1; consumed += 1
                    } else { break }
                }
                if decimalDigits.isEmpty { hasDecimal = false; consumed -= 1; i -= 1 }
                break
            }

            // Ordinal multipliers: "hundredth", "thousandth" — terminate the sequence.
            if let ordMult = ordinalMultipliers[word] {
                if current == 0 { current = 1 }
                total += current * ordMult.0; current = 0
                isOrdinal = true; ordinalSuffix = ordMult.1; hasNumberWord = true
                consumed += 1; i += 1
                break
            }

            // Ordinal tens: "twentieth" — ones cannot precede them.
            if let ordTen = ordinalTens[word] {
                if lastWasBareOnes { break }
                current += ordTen.0; isOrdinal = true; ordinalSuffix = ordTen.1
                hasNumberWord = true; consumed += 1; i += 1
                break
            }

            // Ordinal ones: "first", "second" — terminate the sequence.
            if let ordOne = ordinalOnes[word] {
                current += ordOne.0; isOrdinal = true; ordinalSuffix = ordOne.1
                hasNumberWord = true; consumed += 1; i += 1
                break
            }

            if let mult = multipliers[word] {
                if current == 0 { current = 1 }
                if mult >= 1_000 { total = (total + current) * mult; current = 0 }
                else { current *= mult }
                hasNumberWord = true; lastWasBareOnes = false; consumed += 1; i += 1; continue
            }

            // Tens — ones cannot precede tens without a multiplier.
            if let tenVal = tens[word] {
                if lastWasBareOnes { break }
                current += tenVal; hasNumberWord = true; lastWasBareOnes = false; consumed += 1; i += 1; continue
            }

            if let oneVal = ones[word] {
                // Two bare ones/teens in a row is approximate speech.
                if lastWasBareOnes { break }
                current += oneVal; hasNumberWord = true; lastWasBareOnes = true; consumed += 1; i += 1; continue
            }

            break
        }

        if !hasNumberWord { return (0, "") }

        // Back off a trailing "and".
        while consumed > 0 && words[startIndex + consumed - 1].lowercased() == "and" { consumed -= 1 }
        if consumed <= 0 { return (0, "") }

        total += current

        if hasDecimal {
            return (consumed, "\(total).\(decimalDigits.map(String.init).joined())")
        } else if isOrdinal {
            return (consumed, "\(total)\(ordinalSuffix)")
        } else {
            return (consumed, "\(total)")
        }
    }
}
