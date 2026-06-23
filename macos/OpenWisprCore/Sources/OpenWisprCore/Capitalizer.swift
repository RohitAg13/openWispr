import Foundation

/// Deterministic sentence capitalization. Port of the Kotlin `Capitalizer`.
enum Capitalizer {

    // Leading characters to skip over when looking for the first letter of a sentence.
    private static let leadingMarks: Set<Character> = ["\"", "\u{201C}", "'", "\u{2018}", "(", "["]

    static func capitalizeSentences(_ text: String) -> String {
        if text.isEmpty { return text }
        var sb = Array(text)
        var capNext = true
        for i in sb.indices {
            let c = sb[i]
            if c == "\n" {
                capNext = true
            } else if c.isWhitespace || leadingMarks.contains(c) {
                // keep waiting for the first letter
            } else if capNext && c.isLetter {
                sb[i] = uppercaseChar(c); capNext = false
            } else {
                capNext = false // sentence content has begun
            }
            // A terminator starts a new sentence only when followed by whitespace/end.
            if c == "." || c == "!" || c == "?" {
                let next: Character = (i + 1 < sb.count) ? sb[i + 1] : " "
                if next.isWhitespace { capNext = true }
            }
        }
        return String(sb)
    }

    /// Mirrors Kotlin `Char.uppercaseChar()` — single-character uppercase.
    private static func uppercaseChar(_ c: Character) -> Character {
        let up = String(c).uppercased()
        return up.first ?? c
    }
}
