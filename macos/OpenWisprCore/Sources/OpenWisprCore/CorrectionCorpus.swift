import Foundation

/// One accepted dictation, kept as personalization fuel: the text the pipeline *would* insert
/// (`cleaned`) paired with what the user actually kept (`kept`). When the two differ the user
/// corrected something — the highest-value signal for "learn my style". Faithful port of the
/// Android `CorrectionSample` (named `kept` instead of `final`, a Swift keyword).
public struct CorrectionSample: Codable, Equatable {
    public var ts: Double          // epoch seconds (stamped by the app store)
    public var category: String
    public var cleaned: String
    public var kept: String
    public var edited: Bool

    public init(ts: Double, category: String, cleaned: String, kept: String, edited: Bool) {
        self.ts = ts
        self.category = category
        self.cleaned = cleaned
        self.kept = kept
        self.edited = edited
    }
}

/// Pure ranking + formatting for the on-device correction corpus — "the more you use it, the
/// better it gets". Persistence (the JSONL ring buffer) lives in the app's `CorpusStore`; this
/// is the unit-testable core (a faithful port of Android `CorrectionCorpus`).
public enum CorrectionCorpus {

    private static let wordToken = KRegex("[\\p{L}\\p{N}']+")
    private static let minJaccard = 0.2 // real word overlap, not a coincidental stopword

    /// Tokens for overlap scoring: lowercased words of length > 1.
    public static func tokens(_ s: String) -> Set<String> {
        Set(wordToken.findAll(s.lowercased()).map { $0.groupValues[0] }.filter { $0.count > 1 })
    }

    /// Relevance of a sample to `query`: token Jaccard, then small boosts for same-category and
    /// actually-corrected rows. Below `minJaccard` scores 0 (a shared "the" can't be rescued).
    public static func score(query: Set<String>, sample: CorrectionSample, category: String) -> Double {
        if query.isEmpty { return 0 }
        let st = tokens(sample.cleaned)
        if st.isEmpty { return 0 }
        let inter = Double(query.filter { st.contains($0) }.count)
        let jaccard = inter / (Double(query.count) + Double(st.count) - inter)
        if jaccard < minJaccard { return 0 }
        var score = jaccard
        if sample.category == category { score += 0.15 } // same app context is more relevant
        if sample.edited { score += 0.10 }               // a real correction teaches more
        return score
    }

    /// The `k` most relevant past corrections for polishing `query` in `category`.
    public static func rank(
        _ samples: [CorrectionSample], query: String, category: String, k: Int
    ) -> [CorrectionSample] {
        let q = tokens(query)
        if q.isEmpty { return [] }
        let scored = samples
            .filter { !$0.kept.ktIsBlank && !$0.cleaned.ktIsBlank }
            .map { (sample: $0, score: score(query: q, sample: $0, category: category)) }
            .filter { $0.score > 0 }
        // Stable, highest-first.
        return stableSortedByScore(scored).prefix(k).map { $0.sample }
    }

    /// Few-shot block injected into the polish system prompt: a handful of (raw → kept) pairs so
    /// the model cleans text the way *this* user likes. Empty when there are no usable samples.
    public static func fewShotBlock(_ samples: [CorrectionSample]) -> String {
        let usable = samples.filter { !$0.cleaned.ktIsBlank && !$0.kept.ktIsBlank }
        if usable.isEmpty { return "" }
        func clip(_ s: String) -> String {
            let t = s.ktTrim()
            return t.count > 240 ? String(t.prefix(240)) + "…" : t
        }
        var out = "Examples of how I like my dictation cleaned (raw, then the version I keep):"
        for s in usable {
            out += "\nRaw: " + clip(s.cleaned)
            out += "\nKept: " + clip(s.kept)
        }
        return out
    }

    /// Position-aligned word substitutions between `original` and `edited` — the `(wrong, right)`
    /// pairs the inline-edit learn loop turns into personal-vocab aliases. Returns `[]` for
    /// anything that isn't a small set of same-position name-like word swaps (a big rewrite would
    /// pollute the vocabulary). Faithful port of `VocabRepository.learnFromEdit`'s diff.
    public static func learnFromEditPairs(original: String, edited: String) -> [(wrong: String, right: String)] {
        let token = KRegex("[\\p{L}\\p{N}'’.@/-]+")
        let a = token.findAll(original).map { $0.groupValues[0] }
        let b = token.findAll(edited).map { $0.groupValues[0] }
        if a.isEmpty || a.count != b.count { return [] } // only position-aligned word fixes
        let nameLike = KRegex("^[\\p{L}][\\p{L}'’-]+$")  // alphabetic, len ≥ 2 (no numbers/@/.)
        var subs: [(String, String)] = []
        for i in a.indices {
            let x = a[i], y = b[i]
            if x.caseInsensitiveCompare(y) != .orderedSame,
               nameLike.find(x) != nil, nameLike.find(y) != nil {
                subs.append((x, y))
            }
        }
        if subs.isEmpty || subs.count > 5 { return [] } // nothing, or a big rewrite
        return subs
    }

    /// Stable descending sort by score (Swift's `sorted` isn't stable; mirror Android order).
    private static func stableSortedByScore(
        _ items: [(sample: CorrectionSample, score: Double)]
    ) -> [(sample: CorrectionSample, score: Double)] {
        items.enumerated()
            .sorted { lhs, rhs in
                if lhs.element.score != rhs.element.score { return lhs.element.score > rhs.element.score }
                return lhs.offset < rhs.offset
            }
            .map { $0.element }
    }
}
