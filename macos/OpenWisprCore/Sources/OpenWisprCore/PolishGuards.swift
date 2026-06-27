import Foundation

/// Over-edit / runaway guards for the on-device LLM polish step. A faithful port of the
/// guard half of Android `RewriteEngine`:
/// tiny local models invent content, balloon short fragments, and loop on their own output,
/// so these decide when to trust a rewrite and how to salvage a looped one. Pure & deterministic.
public enum PolishGuards {

    // MARK: - Self-correction

    private static let selfCorrectionHints = [
        "actually", "scratch that", "i mean", "make that", "no wait", "rather",
    ]

    /// Self-correction markers in the RAW transcript relax the content-preservation guard
    /// (the speaker deliberately dropped words, so a shorter rewrite is expected).
    public static func hasSelfCorrection(_ raw: String) -> Bool {
        let lower = raw.lowercased()
        return selfCorrectionHints.contains { lower.contains($0) }
    }

    // MARK: - Content preservation

    private static let nonWord = KRegex("[^\\p{L}\\p{N}]+")

    private static func contentWords(_ s: String) -> [String] {
        nonWord.split(s.lowercased()).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    /// True if `output` is a safe cleanup of `input`: it didn't balloon with invented content
    /// and kept enough of the input's words. Applies a content-drop guard (≥60% of
    /// input words kept, relaxed to 40% on self-correction) plus a length-blowup check.
    public static func preservesContent(input: String, output: String, relaxed: Bool) -> Bool {
        let inW = contentWords(input)
        if inW.isEmpty { return true }
        let outW = contentWords(output)
        if outW.count > inW.count * 2 + 12 { return false } // ballooned → likely invented
        let outSet = Set(outW)
        let kept = Float(inW.filter { outSet.contains($0) }.count) / Float(inW.count)
        return kept >= (relaxed ? 0.40 : 0.60)
    }

    // MARK: - Repetition detection

    private static let firstSentence = KRegex("(?s)^\\s*(.{12,}?[.!?\\n])")

    /// True once the generated text has started repeating its first sentence — tiny models
    /// that don't emit a stop token loop on their own output. Used to halt generation early.
    public static func looksRepeating(_ text: String) -> Bool {
        let s = text
        guard let m = firstSentence.find(s) else { return false }
        let unit = m.groupValues[1].trimmingCharacters(in: .whitespacesAndNewlines)
        let unitNS = unit as NSString
        if unitNS.length < 12 { return false }

        let sNS = s as NSString
        let afterRaw = sNS.substring(from: m.end)
        let after = String(afterRaw.drop(while: { $0.isWhitespace }))
        if after.isEmpty { return false }
        let afterNS = after as NSString

        // The next sentence is starting to retype the first one → stop early.
        let take = min(afterNS.length, unitNS.length)
        if take >= 6 {
            let unitPrefix = unitNS.substring(to: take)
            let afterPrefix = afterNS.substring(to: take)
            if unitPrefix.compare(afterPrefix, options: .caseInsensitive) == .orderedSame {
                return true
            }
        }
        // Otherwise: does the (case-sensitive) unit recur anywhere after the first sentence?
        let searchRange = NSRange(location: m.end, length: sNS.length - m.end)
        return sNS.range(of: unit, options: [], range: searchRange).location != NSNotFound
    }

    // MARK: - Salvage

    private static let whitespaceRun = KRegex("\\s+")
    private static let sentenceSplit = KRegex("(?<=[.!?])\\s+|\\n+")

    /// Collapse a model that looped on its own output, keeping one clean copy. First tries a
    /// punctuation-independent KMP minimal-period collapse (one unit repeated, with an optional
    /// partial trailing copy), then a sentence-level dedupe for near-duplicate repeats.
    static func collapseRepetition(_ raw: String) -> String {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)

        let norm = whitespaceRun.replace(t, " ")
        let normNS = norm as NSString
        let n = normNS.length
        if n >= 16 {
            // KMP failure function over UTF-16 units (NSString) for offset consistency.
            var f = [Int](repeating: 0, count: n)
            var k = 0
            for i in 1..<n {
                while k > 0 && normNS.character(at: i) != normNS.character(at: k) { k = f[k - 1] }
                if normNS.character(at: i) == normNS.character(at: k) { k += 1 }
                f[i] = k
            }
            let period = n - f[n - 1]
            if period >= 1 && period < n && n - period >= 8 {
                return normNS.substring(to: period).trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }

        // Fallback: sentence-level dedupe for near-duplicate (not byte-identical) repeats.
        let parts = sentenceSplit.split(t)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        if parts.count < 2 { return t }
        var seen = Set<String>()
        var kept: [String] = []
        for p in parts {
            let nrm = whitespaceRun.replace(p.lowercased(), " ")
            if seen.contains(nrm) { break }
            seen.insert(nrm)
            kept.append(p)
        }
        return kept.count == parts.count ? t : kept.joined(separator: " ")
    }

    // MARK: - Output cleanup

    private static let thinkClosed = KRegex("(?s)<think>.*?</think>")
    private static let thinkOpen = KRegex("(?s)<think>.*")
    private static let codeFence = KRegex("^```[a-zA-Z]*\\n([\\s\\S]*?)\\n```$")

    /// Strip reasoning blocks, echoed markers, a code fence, and a single wrapping quote pair
    /// from a raw model output, then collapse any self-repetition. Port of `cleanOutput`.
    public static func cleanOutput(_ s: String) -> String {
        var t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        // Reasoning blocks (Qwen etc.): closed first, then any unclosed `<think>` left when
        // generation was cut mid-thought.
        t = thinkClosed.replace(t, "").trimmingCharacters(in: .whitespacesAndNewlines)
        t = thinkOpen.replace(t, "").trimmingCharacters(in: .whitespacesAndNewlines)
        // Tiny models sometimes echo our markers and repeat output; cut at the first marker.
        for marker in ["<<<TEXT", "TEXT>>>", "<<<"] {
            if let r = t.range(of: marker) {
                t = String(t[..<r.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        if let fence = codeFence.find(t) {
            t = fence.groupValues[1].trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let pairs: [(Character, Character)] = [("\"", "\""), ("\u{201C}", "\u{201D}"), ("'", "'")]
        for (l, r) in pairs {
            if t.count >= 2, t.first == l, t.last == r,
               !t.dropFirst().dropLast().contains(l) {
                t = String(t.dropFirst().dropLast())
                break
            }
        }
        // Tiny local models loop on their own output; keep the unique leading run.
        t = collapseRepetition(t)
        return t
    }
}
