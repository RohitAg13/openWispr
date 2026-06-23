import Foundation

/// Deterministic fixes for spoken entities (names, acronyms) that STT renders as
/// separated letters. Conservative and cue-gated to avoid false positives.
enum EntityNormalizer {

    /// Join a run of single letters that follows a spelling cue into one word.
    /// "her name is spelled k a y l a" -> "her name is spelled Kayla".
    static func joinSpelledLetters(_ text: String) -> String {
        let regex = KRegex(
            "\\b(spelled|spelt|spell it|spell that)\\s+((?:[a-zA-Z]\\s+){1,}[a-zA-Z])\\b",
            ignoreCase: true
        )
        return regex.replace(text) { m in
            let cue = m.groupValues[1]
            let letters = m.groupValues[2].ktSplitWhitespace().filter { !$0.isEmpty }
            // A name (>=3 letters) is Title-cased; a short run (likely an acronym) is upper-cased.
            let joined = letters.map { $0.lowercased() }.joined()
            let word = letters.count >= 3
                ? joined.ktReplaceFirstCharUppercase()
                : joined.uppercased()
            return "\(cue) \(word)"
        }
    }
}
