import Foundation

/// Small helpers that mirror the Kotlin `String`/collection API used by the ports,
/// so the Swift translations read close to the Kotlin originals.
extension String {

    /// Kotlin `trim(vararg chars: Char)` — trims the given characters from both ends.
    func ktTrim(_ chars: Character...) -> String {
        let set = Set(chars)
        var s = Substring(self)
        while let f = s.first, set.contains(f) { s = s.dropFirst() }
        while let l = s.last, set.contains(l) { s = s.dropLast() }
        return String(s)
    }

    /// Kotlin `trimStart(vararg chars: Char)`.
    func ktTrimStart(_ chars: Character...) -> String {
        let set = Set(chars)
        var s = Substring(self)
        while let f = s.first, set.contains(f) { s = s.dropFirst() }
        return String(s)
    }

    /// Kotlin `trimEnd(vararg chars: Char)`.
    func ktTrimEnd(_ chars: Character...) -> String {
        let set = Set(chars)
        var s = Substring(self)
        while let l = s.last, set.contains(l) { s = s.dropLast() }
        return String(s)
    }

    /// Kotlin `trim()` (no args) — trims whitespace from both ends.
    /// Kotlin's `Char.isWhitespace()` includes Unicode whitespace; `.whitespacesAndNewlines`
    /// is the matching Foundation set.
    func ktTrim() -> String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Kotlin `trimStart()` (no args).
    func ktTrimStart() -> String {
        var s = Substring(self)
        while let f = s.first, f.isWhitespace { s = s.dropFirst() }
        return String(s)
    }

    /// Kotlin `trimEnd()` (no args).
    func ktTrimEnd() -> String {
        var s = Substring(self)
        while let l = s.last, l.isWhitespace { s = s.dropLast() }
        return String(s)
    }

    /// Kotlin `replaceFirstChar { it.uppercase() }` — uppercases the first character.
    /// Kotlin's `Char.uppercase()` can expand to multiple chars; `String.uppercased()`
    /// on the first character matches that.
    func ktReplaceFirstCharUppercase() -> String {
        guard let first = first else { return self }
        return String(first).uppercased() + dropFirst()
    }

    /// Kotlin `s.isBlank()` — empty or only whitespace.
    var ktIsBlank: Bool {
        allSatisfy { $0.isWhitespace }
    }

    /// Kotlin `s.split(Regex("\\s+")).filter { it.isNotEmpty() }` — split on whitespace runs,
    /// dropping empty fragments.
    func ktSplitWhitespace() -> [String] {
        split { $0.isWhitespace }.map(String.init)
    }
}

extension Array {
    /// Kotlin `list.getOrNull(index)`.
    func ktGetOrNull(_ index: Int) -> Element? {
        (index >= 0 && index < count) ? self[index] : nil
    }
}
