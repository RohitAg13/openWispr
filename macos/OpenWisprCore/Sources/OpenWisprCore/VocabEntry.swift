import Foundation

/// One personal-vocabulary term. The recognizer mishears proper nouns and jargon
/// ("Rohit" -> "row hit", "Silero" -> "Silyro"); no cleanup can recover that, so we
/// snap near-misses back to `canonical` after transcription (see `VocabCorrector`).
///
/// - `canonical`: the correct spelling to insert ("Rohit", "Kubernetes").
/// - `aliases`: explicit known mishearings ("row hit", "silyro") — always matched.
///   Fuzzy/phonetic matching also catches unlisted mishearings.
///
/// Faithful port of the Kotlin `VocabEntry` data class (value-type semantics).
public struct VocabEntry: Equatable, Codable {
    public let canonical: String
    public let aliases: [String]
    /// Optional text-expansion. When set, this is a *snippet*: speaking `canonical`
    /// (or an alias) inserts `expansion` verbatim instead — e.g. "my email" →
    /// "you@example.com". Matched exactly only.
    public let expansion: String?
    /// Where this entry came from ("manual" or "contact") — affects bias priority.
    public let source: String
    /// Subset of `aliases` added automatically by the inline-edit learn loop.
    /// Tracked separately so the user can review and undo just the auto-learned ones.
    public let learnedAliases: [String]

    public init(
        _ canonical: String,
        aliases: [String] = [],
        expansion: String? = nil,
        source: String = "manual",
        learnedAliases: [String] = []
    ) {
        self.canonical = canonical
        self.aliases = aliases
        self.expansion = expansion
        self.source = source
        self.learnedAliases = learnedAliases
    }

    /// Kotlin `!expansion.isNullOrBlank()`.
    public var isSnippet: Bool {
        guard let expansion = expansion else { return false }
        return !expansion.ktIsBlank
    }

    /// All spoken forms to match against, longest (most tokens) first.
    public func matchPhrases() -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for p in ([canonical] + aliases).map({ $0.ktTrim() }) where !p.isEmpty {
            if seen.insert(p).inserted { out.append(p) }
        }
        return out
    }
}
