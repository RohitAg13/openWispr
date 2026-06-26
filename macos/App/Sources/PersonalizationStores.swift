import Foundation
import OpenWisprCore

/// Persistent personal-vocabulary store (L1) — the canonical spellings + aliases that
/// `VocabCorrector` snaps mis-heard names/jargon back to, and the bias terms fed to STT.
/// JSON-backed under `~/Library/Application Support/OpenWispr/personalization/vocab.json`.
@MainActor
final class VocabStore: ObservableObject {
    static let shared = VocabStore()

    @Published private(set) var entries: [VocabEntry] = []

    private var fileURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("OpenWispr/personalization", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("vocab.json")
    }

    init() { load() }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? JSONDecoder().decode([VocabEntry].self, from: data) else { return }
        entries = decoded
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(entries) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }

    /// Canonical + alias terms to bias STT toward (frequency/priority order isn't critical here).
    var biasTerms: [String] {
        var seen = Set<String>()
        var out: [String] = []
        for e in entries {
            for term in ([e.canonical] + e.aliases) where !term.isEmpty && seen.insert(term.lowercased()).inserted {
                out.append(term)
            }
        }
        return out
    }

    /// Add or replace an entry (matched case-insensitively by canonical).
    func upsert(_ entry: VocabEntry) {
        if let i = entries.firstIndex(where: { $0.canonical.caseInsensitiveCompare(entry.canonical) == .orderedSame }) {
            entries[i] = entry
        } else {
            entries.append(entry)
        }
        save()
    }

    func remove(canonical: String) {
        entries.removeAll { $0.canonical.caseInsensitiveCompare(canonical) == .orderedSame }
        save()
    }

    func clear() {
        entries = []
        save()
    }

    /// Learn an alias from an inline edit: `wrong` was heard, the user kept `right`. Attaches
    /// `wrong` as a *learned* alias of the entry whose canonical is `right` (creating it if
    /// needed). Mirrors Android `VocabRepository.learnAlias` (learned aliases capped at 5).
    func learnAlias(wrong: String, right: String) {
        let wrong = wrong.trimmingCharacters(in: .whitespacesAndNewlines)
        let right = right.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !wrong.isEmpty, !right.isEmpty,
              wrong.caseInsensitiveCompare(right) != .orderedSame else { return }

        if let i = entries.firstIndex(where: { $0.canonical.caseInsensitiveCompare(right) == .orderedSame }) {
            let e = entries[i]
            guard !e.aliases.contains(where: { $0.caseInsensitiveCompare(wrong) == .orderedSame }) else { return }
            var learned = e.learnedAliases
            learned.append(wrong)
            if learned.count > 5 { learned = Array(learned.suffix(5)) }
            entries[i] = VocabEntry(
                e.canonical, aliases: e.aliases + [wrong], expansion: e.expansion,
                source: e.source, learnedAliases: learned
            )
        } else {
            entries.append(VocabEntry(right, aliases: [wrong], source: "learned", learnedAliases: [wrong]))
        }
        save()
    }
}

/// Persistent correction corpus (L2/L3) — accepted dictations kept as personalization fuel,
/// ring-capped at 500, newest-first. JSONL under the personalization directory. Ranking +
/// few-shot formatting live in `OpenWisprCore.CorrectionCorpus`.
@MainActor
final class CorpusStore: ObservableObject {
    static let shared = CorpusStore()

    @Published private(set) var samples: [CorrectionSample] = []

    private let maxEntries = 500

    private var fileURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("OpenWispr/personalization", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("correction_corpus.jsonl")
    }

    init() { load() }

    private func load() {
        guard let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return }
        let decoder = JSONDecoder()
        samples = text.split(separator: "\n").compactMap { line in
            guard let data = line.data(using: .utf8) else { return nil }
            return try? decoder.decode(CorrectionSample.self, from: data)
        }
    }

    private func save() {
        let encoder = JSONEncoder()
        let lines = samples.compactMap { try? encoder.encode($0) }
            .compactMap { String(data: $0, encoding: .utf8) }
        try? lines.joined(separator: "\n").write(to: fileURL, atomically: true, encoding: .utf8)
    }

    /// Append an accepted dictation (newest-first), capped. No-op for an empty kept text.
    func record(cleaned: String, kept: String, category: String, edited: Bool, at ts: Double) {
        guard !kept.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let sample = CorrectionSample(ts: ts, category: category, cleaned: cleaned, kept: kept, edited: edited)
        samples.insert(sample, at: 0)
        if samples.count > maxEntries { samples = Array(samples.prefix(maxEntries)) }
        save()
    }

    /// The `k` most relevant past corrections for polishing `query` in `category`.
    func similar(query: String, category: String, k: Int = 2) -> [CorrectionSample] {
        CorrectionCorpus.rank(samples, query: query, category: category, k: k)
    }

    func clear() {
        samples = []
        save()
    }

    /// Fine-tuning JSONL ({context,input,output}) of the rows the user actually corrected.
    func exportJsonl() -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        return samples
            .filter { $0.edited && !$0.cleaned.isEmpty && !$0.kept.isEmpty && $0.cleaned != $0.kept }
            .compactMap { s -> String? in
                let row = ["context": s.category, "input": s.cleaned, "output": s.kept]
                guard let data = try? encoder.encode(row) else { return nil }
                return String(data: data, encoding: .utf8)
            }
            .joined(separator: "\n")
    }
}
