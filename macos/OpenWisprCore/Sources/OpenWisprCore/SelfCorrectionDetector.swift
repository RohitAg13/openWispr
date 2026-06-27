import Foundation

/// Resolves spoken self-corrections, keeping only the corrected version. A
/// conservative Swift port of the Android `SelfCorrectionDetector`.
enum SelfCorrectionDetector {

    /// Restart markers: when they form their own clause, drop everything before.
    private static let restartMarkers = [
        "let me start over", "let me rephrase", "scratch all that", "scratch that",
        "actually no", "never mind", "nevermind", "forget that", "forget it",
        "start over", "no no no", "no no",
    ]

    /// A value (digit/time or small number word) for the "X … actually Y" swap heuristic.
    private static let VAL =
        "(?:\\d+(?::\\d+)?(?:\\s?[ap]\\.?m\\.?)?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"

    /// Numeric mind-changes: keep only the corrected value.
    private static let valueSwap = KRegex(
        "\\b(\(VAL))[\\s,.…]*(?:actually|no,?\\s+make that|make that|or rather|i mean|no)\\s+(\(VAL))(?=\\W|$)",
        ignoreCase: true
    )

    private struct Inline {
        let phrase: String
        let gated: Bool
    }

    private static let inlineMarkers = [
        Inline(phrase: "oops i meant", gated: false), Inline(phrase: "on second thought", gated: false),
        Inline(phrase: "wait hold on", gated: false), Inline(phrase: "no make that", gated: false),
        Inline(phrase: "make that", gated: false), Inline(phrase: "let's make it", gated: false),
        Inline(phrase: "or rather", gated: false), Inline(phrase: "no wait", gated: false),
        Inline(phrase: "i mean", gated: true), Inline(phrase: "actually", gated: true),
        Inline(phrase: "sorry", gated: true), Inline(phrase: "wait", gated: true),
    ]

    /// Words that introduce a brand-new full clause (a total replacement).
    private static let clauseStarters: Set<String> = [
        "i", "i'm", "i've", "i'd", "i'll", "it", "it's",
        "we", "we're", "we've", "we'd", "we'll",
        "you", "you're", "you've", "you'd", "you'll",
        "he", "he's", "she", "she's", "they", "they're",
        "there", "here", "let's", "please",
    ]

    /// Filler lead-ins that may sit between a clause break and a restart marker.
    private static let leadIns: Set<String> = ["oh", "uh", "um", "erm", "well", "so", "sorry", "hmm", "hm", "oops"]

    static func detectAndResolve(_ text: String) -> String {
        if text.isEmpty { return text }
        var t = valueSwap.replace(text) { $0.groupValues[2] }  // "2 actually 3" -> "3"
        t = resolveStandaloneRestarts(t)
        t = splitIntoSentences(t).map { resolveInline($0) }.joined(separator: " ")
        return t.ktTrim()
    }

    // ---- standalone restarts ----

    private static func resolveStandaloneRestarts(_ text: String) -> String {
        var result = text
        var changed = true
        while changed {
            changed = false
            var best = -1
            var bestEnd = -1
            for marker in restartMarkers {
                let regex = KRegex("\\b\(KRegex.escape(marker))\\b", ignoreCase: true)
                for m in regex.findAll(result) {
                    if !isClauseStart(result, m.start) { continue }
                    let ns = result as NSString
                    let after = ns.substring(from: m.end)
                        .ktTrimStart(" ", ",", ".", "!", "?", ";", ":")
                    if after.ktIsBlank { continue } // nothing to keep -> not a restart
                    if m.start >= best { best = m.start; bestEnd = m.end - 1 }
                }
            }
            if best >= 0 {
                let ns = result as NSString
                result = ns.substring(from: bestEnd + 1)
                    .ktTrimStart(" ", ",", ".", "!", "?", ";", ":")
                    .ktTrim()
                changed = true
            }
        }
        return result
    }

    /// True if the position is the start of a clause.
    private static func isClauseStart(_ text: String, _ index: Int) -> Bool {
        let ns = text as NSString
        let prefix = ns.substring(to: index).ktTrimEnd()
        if prefix.isEmpty { return true }
        if let last = prefix.last, ",.!?;:".contains(last) { return true }
        // Allow a short run of filler lead-ins after the last clause break.
        let lastBreak = lastIndexOfAny(prefix, ",.!?;:")
        let pns = prefix as NSString
        let tail = pns.substring(from: lastBreak + 1).ktTrim()
        if tail.isEmpty { return true }
        return tail.ktSplitWhitespace().allSatisfy { leadIns.contains($0.lowercased()) }
    }

    /// Kotlin `prefix.indexOfLast { it in chars }`, returns -1 if none.
    /// Operates on UTF-16 offsets to stay consistent with NSString substring usage.
    private static func lastIndexOfAny(_ s: String, _ chars: String) -> Int {
        let ns = s as NSString
        let set = Set(chars)
        var i = ns.length - 1
        while i >= 0 {
            let c = Character(UnicodeScalar(ns.character(at: i))!)
            if set.contains(c) { return i }
            i -= 1
        }
        return -1
    }

    // ---- inline repairs ----

    private static func resolveInline(_ sentence: String) -> String {
        let words = sentence.ktSplitWhitespace().filter { !$0.isEmpty }
        if words.count < 3 { return sentence }

        // Find the last inline marker occurrence as a word span.
        var markerStart = -1
        var markerLen = 0
        for marker in inlineMarkers {
            let parts = marker.phrase.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
            var i = 0
            while i + parts.count <= words.count {
                let window = (0..<parts.count).allSatisfy {
                    words[i + $0].lowercased().ktTrim(",", ".", "!", "?", ";", ":") == parts[$0]
                }
                if window && i > 0 && i >= markerStart {
                    // Gated markers require a clause break right before them.
                    if marker.gated && !words[i - 1].hasSuffix(",") &&
                        !(words[i - 1].last.map { ".;:".contains($0) } ?? false) {
                        i += 1; continue
                    }
                    markerStart = i; markerLen = parts.count
                }
                i += 1
            }
        }
        if markerStart <= 0 { return sentence }

        let before = Array(words[0..<markerStart])
        let after = Array(words[(markerStart + markerLen)..<words.count])
        if after.isEmpty {
            // "...john, I mean" -> just drop the dangling marker.
            return before.joined(separator: " ")
        }

        // A correction that starts a fresh clause replaces the whole sentence.
        let afterHead = after[0].lowercased().ktTrim(",", ".", "!", "?", ";", ":")
        if after.count > 6 || clauseStarters.contains(afterHead) {
            return after.joined(separator: " ").ktReplaceFirstCharUppercase()
        }

        // Fragment correction: splice `after` over the erroneous tail of `before`.
        var cleanedBefore = before
        cleanedBefore[cleanedBefore.count - 1] =
            cleanedBefore[cleanedBefore.count - 1].ktTrimEnd(",", ".", "!", "?", ";", ":")

        let anchor = afterHead
        // Prefer first-token overlap ("to mark" / "to john" share "to").
        let overlapIdx = cleanedBefore.lastIndex {
            $0.lowercased().ktTrim(",", ".", "!", "?", ";", ":") == anchor
        } ?? -1
        let kept: [String]
        if overlapIdx >= 0 {
            kept = Array(cleanedBefore[0..<overlapIdx])
        } else {
            kept = Array(cleanedBefore[0..<max(cleanedBefore.count - 1, 0)])
        }
        return (kept + after).joined(separator: " ")
    }

    // ---- sentence splitting ----

    private static func splitIntoSentences(_ text: String) -> [String] {
        let regex = KRegex("(?<=[.!?])\\s+")
        return regex.splitKotlin(text).map { $0.ktTrim() }.filter { !$0.isEmpty }
    }
}

extension KRegex {
    /// Kotlin `Regex.split(input)` — splits around matches, keeping trailing empties
    /// only as Kotlin does. (Kotlin keeps a trailing empty string when the input ends
    /// with a delimiter; the callers here filter empties, so behavior is faithful.)
    func splitKotlin(_ input: String) -> [String] {
        let ns = input as NSString
        let matches = findAll(input)
        if matches.isEmpty { return [input] }
        var result: [String] = []
        var cursor = 0
        for m in matches {
            result.append(ns.substring(with: NSRange(location: cursor, length: m.start - cursor)))
            cursor = m.end
        }
        result.append(ns.substring(from: cursor))
        return result
    }
}

extension Array {
    /// Kotlin `indexOfLast { predicate }` returning nil if none (caller maps to -1).
    func lastIndex(where predicate: (Element) -> Bool) -> Int? {
        var i = count - 1
        while i >= 0 {
            if predicate(self[i]) { return i }
            i -= 1
        }
        return nil
    }
}
