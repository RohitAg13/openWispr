import Foundation

/// One past dictation, persisted for the Home "Recent dictations" list.
struct DictationRecord: Codable, Identifiable, Equatable {
    let id: UUID
    var text: String
    let ts: Double // epoch seconds

    init(id: UUID = UUID(), text: String, ts: Double) {
        self.id = id
        self.text = text
        self.ts = ts
    }
}

/// Persistent dictation history shared by **both** dictation flows — the hands-free hotkey
/// coordinator and the Home/popover controller — so every dictation shows up in one list that
/// survives relaunch. JSON-backed under `~/Library/Application Support/OpenWispr/history.json`,
/// newest-first, ring-capped.
@MainActor
final class DictationHistoryStore: ObservableObject {
    static let shared = DictationHistoryStore()

    @Published private(set) var records: [DictationRecord] = []

    private let maxRecords = 200

    private var fileURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("OpenWispr", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("history.json")
    }

    init() { load() }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? JSONDecoder().decode([DictationRecord].self, from: data) else { return }
        records = decoded
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(records) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }

    /// Prepend a new dictation (newest-first), cap, persist. Returns its id so a later edit
    /// (learn-from-edit) can update the same row. No-op for blank text.
    @discardableResult
    func add(_ text: String, at ts: Double = Date().timeIntervalSince1970) -> UUID? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let record = DictationRecord(text: trimmed, ts: ts)
        records.insert(record, at: 0)
        if records.count > maxRecords { records = Array(records.prefix(maxRecords)) }
        save()
        return record.id
    }

    /// Update a record's text in place (e.g. after the user edits & teaches a result).
    func update(id: UUID, text: String) {
        guard let i = records.firstIndex(where: { $0.id == id }) else { return }
        records[i].text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        save()
    }

    func remove(id: UUID) {
        records.removeAll { $0.id == id }
        save()
    }

    func clear() {
        records = []
        save()
    }
}
