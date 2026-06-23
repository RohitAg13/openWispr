import Foundation

/// Snaps mis-transcribed names/terms back to their canonical spelling using a
/// personal vocabulary. Runs after STT, before the cleanup pipeline. Pure and
/// deterministic so it's testable and never hallucinates outside the list.
///
/// Matching, conservative by design:
///  - Exact alias/canonical match (case-insensitive, whole word) -> always replace.
///  - Fuzzy match -> Soundex phonetic equality + small edit distance, gated against
///    common English words and short tokens, so "right"/"to"/"one" are never touched.
///
/// Faithful port of the Kotlin `VocabCorrector` object.
public enum VocabCorrector {

    private static let FUZZY_THRESHOLD = 0.84
    private static let MIN_FUZZY_LEN = 4

    private struct Target {
        let replacement: String
        let tokens: [String]
        let fuzzy: Bool
    }
    private struct Tok {
        let norm: String
        let start: Int
        let end: Int
    }
    private struct Hit {
        let start: Int
        let end: Int
        let replacement: String
        let score: Double
        let span: Int
    }

    /// A compact glossary string to bias Whisper's decoding (initial_prompt). Only
    /// canonical spellings — never aliases. Capped to stay within the prompt budget.
    public static func biasPrompt(_ vocab: [VocabEntry], maxChars: Int = 200) -> String {
        if vocab.isEmpty { return "" }
        var sb = "Glossary: "
        // Names only. Priority for the capped budget: terms the user actually had to
        // correct (learnedAliases — proven mishearings) first, then manual, then contacts.
        let ranked = stableSorted(vocab.filter { !$0.isSnippet }) { a, b in
            // compareByDescending { learnedAliases.isNotEmpty() }.thenBy { source != "manual" }
            let al = !a.learnedAliases.isEmpty
            let bl = !b.learnedAliases.isEmpty
            if al != bl { return al && !bl } // descending: true sorts first
            let am = a.source != "manual"
            let bm = b.source != "manual"
            if am != bm { return !am && bm }  // ascending: false ("manual") sorts first
            return nil
        }
        for e in ranked {
            let c = e.canonical.ktTrim()
            if c.isEmpty { continue }
            if sb.count + c.count + 2 > maxChars { break }
            if !sb.hasSuffix(": ") { sb += ", " }
            sb += c
        }
        return sb.hasSuffix(": ") ? "" : sb + "."
    }

    public static func correct(_ text: String, _ vocab: [VocabEntry]) -> String {
        if text.ktIsBlank || vocab.isEmpty { return text }
        let toks = tokenize(text)
        if toks.isEmpty { return text }

        // Build match targets, longest phrase first so "row hit" beats "hit".
        var targets: [Target] = []
        for e in vocab {
            // Snippets expand to their text; names correct to the canonical spelling.
            let replacement = e.isSnippet ? e.expansion!.ktTrim() : e.canonical.ktTrim()
            if replacement.isEmpty { continue }
            // Fuzzy matching only applies to entries that carry an explicit mis-hearing
            // alias. A bare canonical with no alias — every contact import — matches
            // EXACTLY only, so noisy contacts can't phonetically grab ordinary words.
            let fuzzy = !e.isSnippet && !e.aliases.isEmpty
            for phrase in e.matchPhrases() {
                let ptoks = phrase.lowercased().ktSplitWhitespace()
                if !ptoks.isEmpty {
                    targets.append(Target(replacement: replacement, tokens: ptoks, fuzzy: fuzzy))
                }
            }
        }
        // sortByDescending { tokens.size } — Kotlin's sort is stable.
        targets = stableSorted(targets) { a, b in
            if a.tokens.count != b.tokens.count { return a.tokens.count > b.tokens.count }
            return nil
        }

        var hits: [Hit] = []
        for t in targets {
            let span = t.tokens.count
            var i = 0
            while i + span <= toks.count {
                let window = Array(toks[i..<(i + span)])
                let score = matchScore(window.map { $0.norm }, t.tokens)
                // Snippets and contacts fire on an exact match only; deliberate names fuzzy.
                let ok = t.fuzzy ? score >= FUZZY_THRESHOLD : score >= 0.999
                if ok {
                    hits.append(Hit(start: window.first!.start, end: window.last!.end,
                                    replacement: t.replacement, score: score, span: span))
                }
                i += 1
            }
        }
        if hits.isEmpty { return text }

        // Greedy: prefer higher score, then longer span; apply non-overlapping.
        hits = stableSorted(hits) { a, b in
            if a.score != b.score { return a.score > b.score }
            if a.span != b.span { return a.span > b.span }
            return nil
        }
        var taken: [Hit] = []
        for h in hits {
            if !taken.contains(where: { $0.start < h.end && h.start < $0.end }) {
                taken.append(h)
            }
        }
        // apply right-to-left so offsets stay valid
        taken = stableSorted(taken) { a, b in
            if a.start != b.start { return a.start > b.start }
            return nil
        }

        let ns = NSMutableString(string: text)
        for h in taken {
            ns.replaceCharacters(in: NSRange(location: h.start, length: h.end - h.start),
                                 with: h.replacement)
        }
        return ns as String
    }

    // ---- matching ----

    /// 0..1 similarity of an input window to a target phrase (token-wise).
    private static func matchScore(_ window: [String], _ phrase: [String]) -> Double {
        if window.count != phrase.count { return 0.0 }
        // A single token that's a common English word is never a vocab match — even
        // exactly — so a contact named "Will"/"Mark" can't corrupt "i will mark it".
        if window.count == 1 && COMMON_WORDS.contains(window[0]) { return 0.0 }
        if window == phrase { return 1.0 } // exact alias/canonical
        var sum = 0.0
        for k in window.indices {
            let a = window[k]
            let b = phrase[k]
            if a == b { sum += 1.0; continue }
            // Guard: don't fuzzy-correct common words or short tokens.
            if COMMON_WORDS.contains(a) || a.count < MIN_FUZZY_LEN || b.count < MIN_FUZZY_LEN {
                return 0.0
            }
            let dist = levenshtein(a, b)
            let editSim = 1.0 - Double(dist) / Double(max(a.count, b.count))
            let s = soundex(a) == soundex(b) ? 0.7 + 0.3 * editSim : editSim
            if s < FUZZY_THRESHOLD { return 0.0 }
            sum += s
        }
        return sum / Double(window.count)
    }

    private static let tokenRegex = KRegex("[\\p{L}\\p{N}'’]+")

    private static func tokenize(_ text: String) -> [Tok] {
        var out: [Tok] = []
        for m in tokenRegex.findAll(text) {
            let norm = m.groupValues[0].lowercased().ktTrim("'", "’")
            out.append(Tok(norm: norm, start: m.start, end: m.end))
        }
        return out
    }

    // ---- phonetics / distance ----

    /// Classic Soundex (first letter + 3 digits). Good enough for name matching.
    public static func soundex(_ s: String) -> String {
        let up = String(s.uppercased().unicodeScalars.filter { $0 >= "A" && $0 <= "Z" })
        if up.isEmpty { return "0000" }
        let chars = Array(up)

        func code(_ c: Character) -> Character {
            switch c {
            case "B", "F", "P", "V": return "1"
            case "C", "G", "J", "K", "Q", "S", "X", "Z": return "2"
            case "D", "T": return "3"
            case "L": return "4"
            case "M", "N": return "5"
            case "R": return "6"
            default: return "0" // vowels + H,W,Y
            }
        }

        var sb = String(chars[0])
        var prev = code(chars[0])
        var i = 1
        while i < chars.count {
            let c = code(chars[i])
            if c != "0" && c != prev { sb.append(c) }
            // H and W don't reset the "previous code" gate; vowels do.
            if chars[i] != "H" && chars[i] != "W" { prev = c }
            if sb.count >= 4 { break }
            i += 1
        }
        return String((sb + "000").prefix(4))
    }

    private static func levenshtein(_ a: String, _ b: String) -> Int {
        let ac = Array(a)
        let bc = Array(b)
        var dp = Array(0...bc.count)
        for i in 1...max(ac.count, 1) where !ac.isEmpty {
            var prev = dp[0]
            dp[0] = i
            for j in 1...bc.count where !bc.isEmpty {
                let cur = dp[j]
                dp[j] = Swift.min(dp[j] + 1, dp[j - 1] + 1,
                                  prev + (ac[i - 1] == bc[j - 1] ? 0 : 1))
                prev = cur
            }
        }
        return dp[bc.count]
    }

    /// Frequent words we never match as a single-token vocab term. Includes common
    /// English words AND words that double as first names ("Will", "Mark", "Rose").
    private static let COMMON_WORDS: Set<String> = [
        "the", "and", "for", "are", "you", "your", "with", "this", "that", "have",
        "from", "they", "what", "when", "will", "would", "there", "their", "right",
        "write", "here", "hear", "one", "two", "ten", "now", "not", "but", "can",
        "all", "any", "out", "our", "was", "his", "her", "him", "she", "who", "how",
        "why", "yes", "let", "set", "get", "got", "see", "way", "day", "new", "use",
        "make", "like", "just", "need", "want", "into", "over", "then", "than",
        // common English words that are also names
        "mark", "rose", "grace", "hope", "bill", "art", "max", "ray", "dawn", "jack",
        "drew", "rich", "faith", "joy", "may", "june", "guy", "frank", "grant", "page",
        "lane", "reed", "hunter", "chase", "cash", "dale", "rob", "bob", "sunny", "victor",
    ]
}

/// Stable sort matching Kotlin's `sortWith`/`sortedWith` semantics. The comparator
/// returns `true`/`false` for a strict ordering decision, or `nil` for "equal"
/// (preserve original relative order).
private func stableSorted<T>(_ array: [T], _ less: (T, T) -> Bool?) -> [T] {
    return array.enumerated().sorted { lhs, rhs in
        if let r = less(lhs.element, rhs.element) { return r }
        return lhs.offset < rhs.offset
    }.map { $0.element }
}
