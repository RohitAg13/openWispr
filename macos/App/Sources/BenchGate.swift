#if DEBUG
import Foundation

/// Tier-2 mirror of the research-harness gating predicate
/// (openwispr-research `bench/tier1_polish.py` `_needs_polish` / `_finish`).
///
/// "Gating" skips the on-device LLM polish entirely when the deterministic textproc output
/// is already clean — the single biggest honest latency lever (the LLM is the ~10 s on-device
/// bottleneck). This file keeps the Tier-2 predicate byte-faithful to the Tier-1 one so
/// `run_macos.sh` confirms the SAME win in the real app pipeline. Bench-only (`#if DEBUG`);
/// the production version would live in `DictationCoordinator.applyPolish`.
enum BenchGate {
    static let fillerWords: Set<String> = [
        "um", "uh", "erm", "uhm", "hmm", "mm", "mhm", "ah", "eh",
        "basically", "literally", "actually", "anyway", "anyways", "honestly",
        "seriously", "obviously", "essentially", "frankly", "regardless",
    ]
    static let fillerPhrases: [String] = [
        "i mean", "you know", "kind of", "sort of", "you see", "i guess",
        "or something", "or whatever", "i think like", "let me", "scratch that",
        "new line", "new paragraph", "full stop",
    ]
    static let leadingFillers: Set<String> = ["so", "well", "like", "yeah", "okay", "ok", "right", "now", "see"]
    static let spokenSymbols: Set<String> = [
        "slash", "backslash", "dot", "at", "dash", "underscore", "colon",
        "semicolon", "asterisk", "hashtag", "ampersand",
    ]
    static let numberWords: Set<String> = [
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    ]
    static let vowels: Set<Character> = ["a", "e", "i", "o", "u"]

    /// `re.findall(r"[a-z0-9']+", low)` — words of letters/digits/apostrophes.
    static func tokens(_ low: String) -> [String] {
        var out: [String] = []
        var cur = ""
        for ch in low {
            if ch.isLetter || ch.isNumber || ch == "'" {
                cur.append(ch)
            } else if !cur.isEmpty {
                out.append(cur); cur = ""
            }
        }
        if !cur.isEmpty { out.append(cur) }
        return out
    }

    /// True ⇒ the deterministic output still has disfluencies the LLM should fix (do NOT gate).
    /// Conservative: any doubt routes to the model. Computed from the text alone (no eval labels).
    static func needsPolish(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return true }
        let low = t.lowercased()
        let words = tokens(low)
        if words.isEmpty { return false }
        if words.count > 24 { return true }
        for p in fillerPhrases where low.contains(p) { return true }
        if leadingFillers.contains(words[0]) { return true }
        for i in 0..<words.count {
            let w = words[i]
            if fillerWords.contains(w) { return true }
            if spokenSymbols.contains(w) { return true }
            if i + 1 < words.count {
                let nxt = words[i + 1]
                if w == nxt { return true }
                if w == "a", let f = nxt.first, vowels.contains(f) { return true }
                if w == "an", let f = nxt.first, !vowels.contains(f), f.isLetter { return true }
                if w.count == 1, let wc = w.first, wc.isLetter {
                    let nextSingleLetter = nxt.count == 1 && (nxt.first?.isLetter ?? false)
                    let nextDigits = !nxt.isEmpty && nxt.allSatisfy { $0.isNumber }
                    if nextSingleLetter || nextDigits || numberWords.contains(nxt) { return true }
                }
            }
        }
        return false
    }

    /// Must-keep-safe finishing for a gated case: capitalize the first plain-lowercase word,
    /// uppercase lone "i", add a terminal period. WER-norm ignores all of this, so it can't
    /// move the gate; it exists so gated output is genuinely shippable.
    static func finish(_ text: String) -> String {
        var t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return t }
        let firstTok = t.split(separator: " ").first.map(String.init) ?? ""
        if !firstTok.isEmpty && firstTok.allSatisfy({ $0.isLowercase && $0.isLetter }) {
            t = t.prefix(1).uppercased() + t.dropFirst()
        }
        t = t.replacingOccurrences(of: "\\bi\\b", with: "I", options: .regularExpression)
        if let last = t.last, last.isLetter || last.isNumber { t += "." }
        return t
    }
}
#endif
