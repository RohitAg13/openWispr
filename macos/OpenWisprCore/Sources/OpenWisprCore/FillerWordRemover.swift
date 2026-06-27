import Foundation

/// Removes filler words using word-boundary-aware regex. Swift port of the Android
/// `FillerWordRemover`.
enum FillerWordRemover {

    /// Filler -> preceding words that mark it as a verb phrase (so keep it).
    private static let guardedFillers: [String: Set<String>] = [
        "you know": [
            "do", "did", "didn't", "don't", "doesn't",
            "if", "whether", "that",
            "could", "would", "should", "can", "will", "might", "may",
            "won't", "couldn't", "wouldn't", "shouldn't", "shall",
        ],
    ]

    /// Hesitation sounds and their repeated-letter variants — always noise.
    private static let hesitation = KRegex(
        "(,\\s*)?\\b(um+|uh+|uhm+|hm+|mm+|mhm+|erm+|er)\\b(\\s*,)?",
        ignoreCase: true
    )

    /// Verbal-tic fillers that are only removed when comma/clause-bounded.
    private static let edgeFillers = ["you know what i mean", "i guess", "kind of", "sort of", "or whatever"]

    static func removeFillers(_ text: String, fillerWords: [String]) -> String {
        if text.isEmpty { return text }

        // 1. Hesitation sounds (independent of the configurable list).
        var result = hesitation.replace(text, "")

        // 2. Comma/clause-bounded verbal tics (the meaningful uses have no comma).
        for f in edgeFillers {
            let e = KRegex.escape(f)
            result = KRegex(",\\s*\(e)\\b", ignoreCase: true).replace(result, "")  // "done, kind of"
            result = KRegex("\\b\(e)\\s*,", ignoreCase: true).replace(result, "")  // "kind of, it's…"
        }

        // 3. Configured phrase fillers, longest-first ("you know" before "you").
        for filler in fillerWords.sorted(by: { $0.count > $1.count }) {
            let escaped = KRegex.escape(filler)
            let regex = KRegex("(,\\s*)?\\b\(escaped)\\b(\\s*,)?", ignoreCase: true)
            if let guardWords = guardedFillers[filler.lowercased()] {
                result = removeWithGuards(result, regex, guardWords)
            } else {
                result = regex.replace(result, "")
            }
        }
        return cleanUp(result)
    }

    /// Remove matches only when NOT preceded by a guard word.
    private static func removeWithGuards(_ text: String, _ regex: KRegex, _ guardWords: Set<String>) -> String {
        let matches = regex.findAll(text)
        if matches.isEmpty { return text }

        let ns = text as NSString
        let sb = NSMutableString()
        var cursor = 0
        for m in matches {
            let precedingWord = ns.substring(to: m.start)
                .ktSplitWhitespace()
                .last { !$0.isEmpty }?
                .lowercased().ktTrim(".", ",", "!", "?", ";", ":", "\"", "'")
            if let pw = precedingWord, guardWords.contains(pw) { continue } // keep it
            sb.append(ns.substring(with: NSRange(location: cursor, length: m.start - cursor)))
            cursor = m.end
        }
        sb.append(ns.substring(from: cursor))
        return sb as String
    }

    private static func cleanUp(_ text: String) -> String {
        var r = text
        r = KRegex("\\s{2,}").replace(r, " ")            // collapse runs of spaces
        r = KRegex("\\s+([.,!?;:])").replace(r, "$1")     // no space before punctuation
        r = KRegex("^\\s*,\\s*").replace(r, "")            // orphaned leading comma
        r = KRegex(",\\s*,").replace(r, ",")               // double commas from adjacent removals
        return r.ktTrim()
    }
}

extension Array {
    /// Kotlin `lastOrNull { predicate }`.
    func last(where predicate: (Element) -> Bool) -> Element? {
        var i = count - 1
        while i >= 0 {
            if predicate(self[i]) { return self[i] }
            i -= 1
        }
        return nil
    }
}
