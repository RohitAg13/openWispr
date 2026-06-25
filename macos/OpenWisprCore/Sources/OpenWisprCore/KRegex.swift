import Foundation

/// A thin wrapper over `NSRegularExpression` that mirrors Kotlin's `kotlin.text.Regex`
/// API closely enough that the deterministic text-processing ports translate almost
/// line-for-line. All offsets are UTF-16 / `NSString` offsets so they align with
/// `NSString` substring operations used in the index-heavy ports.
struct KRegex {

    /// A single regex match. `groupValues[0]` is the whole match (like Kotlin's
    /// `m.groupValues`); `start`/`end` are UTF-16 offsets where `end` = location+length
    /// (matching Kotlin's `m.range.last + 1`).
    struct Match {
        /// Group values, index 0 = whole match. Unmatched optional groups are "".
        let groupValues: [String]
        /// UTF-16 offset of the match start (Kotlin `m.range.first` / NSRange.location).
        let start: Int
        /// UTF-16 offset one past the match end (Kotlin `m.range.last + 1`).
        let end: Int
    }

    private let regex: NSRegularExpression
    let pattern: String

    init(_ pattern: String, ignoreCase: Bool = false) {
        self.pattern = pattern
        var options: NSRegularExpression.Options = []
        if ignoreCase { options.insert(.caseInsensitive) }
        // Kotlin/Java default: `.` does not match line terminators; `^`/`$` match
        // string start/end. That's NSRegularExpression's default too, so no extra
        // options are needed.
        // swiftlint:disable:next force_try
        self.regex = try! NSRegularExpression(pattern: pattern, options: options)
    }

    /// Template replacement using NSRegularExpression template syntax (`$1`, `$2`).
    func replace(_ input: String, _ template: String) -> String {
        let ns = input as NSString
        let range = NSRange(location: 0, length: ns.length)
        return regex.stringByReplacingMatches(in: input, options: [], range: range, withTemplate: template)
    }

    /// Closure replacement form, mirroring Kotlin `regex.replace(input) { m -> ... }`.
    func replace(_ input: String, _ transform: (Match) -> String) -> String {
        let ns = input as NSString
        let matches = regex.matches(in: input, options: [], range: NSRange(location: 0, length: ns.length))
        if matches.isEmpty { return input }
        let result = NSMutableString()
        var cursor = 0
        for m in matches {
            let r = m.range
            result.append(ns.substring(with: NSRange(location: cursor, length: r.location - cursor)))
            result.append(transform(makeMatch(m, ns)))
            cursor = r.location + r.length
        }
        result.append(ns.substring(with: NSRange(location: cursor, length: ns.length - cursor)))
        return result as String
    }

    /// All matches, in text order (like Kotlin `regex.findAll(input)`).
    func findAll(_ input: String) -> [Match] {
        let ns = input as NSString
        let matches = regex.matches(in: input, options: [], range: NSRange(location: 0, length: ns.length))
        return matches.map { makeMatch($0, ns) }
    }

    private func makeMatch(_ m: NSTextCheckingResult, _ ns: NSString) -> Match {
        var groups: [String] = []
        for i in 0..<m.numberOfRanges {
            let r = m.range(at: i)
            if r.location == NSNotFound {
                groups.append("")
            } else {
                groups.append(ns.substring(with: r))
            }
        }
        let whole = m.range
        return Match(groupValues: groups, start: whole.location, end: whole.location + whole.length)
    }

    /// First match, or `nil` (like Kotlin `regex.find(input)`).
    func find(_ input: String) -> Match? {
        let ns = input as NSString
        guard let m = regex.firstMatch(
            in: input, options: [], range: NSRange(location: 0, length: ns.length)
        ) else { return nil }
        return makeMatch(m, ns)
    }

    /// Split on the regex, mirroring Kotlin `regex.split(input)`. Kotlin keeps a trailing
    /// empty string when the input ends with a delimiter (unlike `components`), so this does
    /// too — callers that don't want empties filter them.
    func split(_ input: String) -> [String] {
        let ns = input as NSString
        let matches = regex.matches(in: input, options: [], range: NSRange(location: 0, length: ns.length))
        if matches.isEmpty { return [input] }
        var parts: [String] = []
        var cursor = 0
        for m in matches {
            let r = m.range
            // Zero-width matches can't advance the cursor; skip them to mirror Kotlin.
            if r.length == 0 { continue }
            parts.append(ns.substring(with: NSRange(location: cursor, length: r.location - cursor)))
            cursor = r.location + r.length
        }
        parts.append(ns.substring(with: NSRange(location: cursor, length: ns.length - cursor)))
        return parts
    }

    /// Mirrors Kotlin `Regex.escape(...)`.
    static func escape(_ literal: String) -> String {
        NSRegularExpression.escapedPattern(for: literal)
    }
}
