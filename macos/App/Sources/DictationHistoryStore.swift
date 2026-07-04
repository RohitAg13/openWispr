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

/// All-time usage totals shown in the Home stat band. Mirrors the Android `DictationStats` so the
/// two platforms report the same numbers with the same model. Derived from cumulative counters that
/// survive the (capped) feed, not from the feed itself.
struct DictationStats: Equatable {
    var totalWords: Int
    var timeSavedMinutes: Double
    var streakDays: Int
    var acceptRate: Int   // 0…100, -1 = no data
    var onDeviceRate: Int // 0…100, -1 = no data

    static let empty = DictationStats(totalWords: 0, timeSavedMinutes: 0, streakDays: 0,
                                      acceptRate: -1, onDeviceRate: -1)

    /// "12,480" — grouped thousands.
    var wordsLabel: String {
        let f = NumberFormatter(); f.numberStyle = .decimal
        return f.string(from: NSNumber(value: totalWords)) ?? "\(totalWords)"
    }

    /// "14.2h" for ≥1 h, "37m" otherwise, "0m" for none. Mirrors Android's `formatSaved`.
    var timeSavedLabel: String {
        if timeSavedMinutes < 1 { return "0m" }
        return timeSavedMinutes >= 60
            ? String(format: "%.1fh", timeSavedMinutes / 60.0)
            : "\(Int(timeSavedMinutes))m"
    }

    /// "97%" or "—" when there's no data yet.
    static func rateLabel(_ pct: Int) -> String { pct < 0 ? "—" : "\(pct)%" }
}

/// Persistent dictation history shared by **both** dictation flows — the hands-free hotkey
/// coordinator and the Home/popover controller — so every dictation shows up in one list that
/// survives relaunch. JSON-backed under `~/Library/Application Support/OpenWispr/history.json`,
/// newest-first, ring-capped.
@MainActor
final class DictationHistoryStore: ObservableObject {
    static let shared = DictationHistoryStore()

    @Published private(set) var records: [DictationRecord] = []
    /// All-time usage totals for the Home stat band. Recomputed from the persisted counters after
    /// every recorded dictation.
    @Published private(set) var stats: DictationStats = .empty

    private let maxRecords = 200

    // Time-saved model: typing ~40 wpm vs effective speaking ~150 wpm (matches Android).
    private let typingWPM = 40.0
    private let speakingWPM = 150.0

    /// Cumulative stat counters, persisted separately from the (capped) feed so all-time totals
    /// survive pruning and a feed clear. Mirrors the Android `SharedPreferences` counters.
    private let defaults = UserDefaults.standard
    private enum StatKey {
        static let count = "owstat.count"
        static let words = "owstat.words"
        static let accepted = "owstat.accepted"
        static let onDevice = "owstat.ondevice"
        static let streak = "owstat.streak"
        static let lastDay = "owstat.lastDay"
    }

    private var fileURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("OpenWispr", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("history.json")
    }

    init() {
        load()
        backfillCountersIfNeeded()
        recomputeStats()
    }

    /// Seed the cumulative counters from the existing feed the first time stats run (e.g. after an
    /// app update that adds them), so the stat band reflects prior usage instead of starting at zero.
    /// Only runs when no counters exist yet.
    private func backfillCountersIfNeeded() {
        guard defaults.object(forKey: StatKey.count) == nil, !records.isEmpty else { return }
        let totalWords = records.reduce(0) { $0 + $1.text.split(whereSeparator: { $0.isWhitespace }).count }
        defaults.set(records.count, forKey: StatKey.count)
        defaults.set(totalWords, forKey: StatKey.words)
        defaults.set(records.count, forKey: StatKey.accepted) // no per-entry edit flag historically
        defaults.set(records.count, forKey: StatKey.onDevice) // macOS recognizes on-device
        let days = Set(records.map { Self.dayKey($0.ts) }).sorted(by: >)
        if let latest = days.first {
            var streak = 1
            var cursor = latest
            for d in days.dropFirst() {
                if Self.isYesterday(d, cursor) { streak += 1; cursor = d } else { break }
            }
            defaults.set(streak, forKey: StatKey.streak)
            defaults.set(latest, forKey: StatKey.lastDay)
        }
    }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? JSONDecoder().decode([DictationRecord].self, from: data) else { return }
        records = decoded
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(records) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }

    /// Prepend a new dictation (newest-first), cap, persist, and bump the all-time stat counters.
    /// Returns its id so a later edit (learn-from-edit) can update the same row. No-op for blank text.
    ///
    /// `accepted` = inserted without a manual edit; `onDevice` = recognized by an on-device engine.
    /// These feed the accept-rate / on-device-rate stats (both default true — the hands-free path
    /// auto-inserts and macOS recognizes on-device).
    @discardableResult
    func add(_ text: String, at ts: Double = Date().timeIntervalSince1970,
             accepted: Bool = true, onDevice: Bool = true) -> UUID? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let record = DictationRecord(text: trimmed, ts: ts)
        records.insert(record, at: 0)
        if records.count > maxRecords { records = Array(records.prefix(maxRecords)) }
        save()
        bumpCounters(text: trimmed, at: ts, accepted: accepted, onDevice: onDevice)
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

    /// Clear the feed. All-time counters are left intact — the stat band reflects lifetime usage,
    /// which shouldn't reset just because the visible list was cleared (matches Android).
    func clear() {
        records = []
        save()
    }

    // MARK: - Stats

    /// Bump the cumulative counters for one dictation, then republish `stats`.
    private func bumpCounters(text: String, at ts: Double, accepted: Bool, onDevice: Bool) {
        let words = text.split(whereSeparator: { $0.isWhitespace }).count
        defaults.set(defaults.integer(forKey: StatKey.count) + 1, forKey: StatKey.count)
        defaults.set(defaults.integer(forKey: StatKey.words) + words, forKey: StatKey.words)
        if accepted { defaults.set(defaults.integer(forKey: StatKey.accepted) + 1, forKey: StatKey.accepted) }
        if onDevice { defaults.set(defaults.integer(forKey: StatKey.onDevice) + 1, forKey: StatKey.onDevice) }

        // Streak: consecutive calendar days with at least one dictation.
        let today = Self.dayKey(ts)
        let lastDay = defaults.string(forKey: StatKey.lastDay)
        if lastDay != today {
            let streak = defaults.integer(forKey: StatKey.streak)
            let next = (lastDay != nil && Self.isYesterday(lastDay!, today)) ? streak + 1 : 1
            defaults.set(next, forKey: StatKey.streak)
            defaults.set(today, forKey: StatKey.lastDay)
        } else if defaults.integer(forKey: StatKey.streak) == 0 {
            defaults.set(1, forKey: StatKey.streak)
            defaults.set(today, forKey: StatKey.lastDay)
        }

        recomputeStats()
    }

    private func recomputeStats() {
        let total = defaults.integer(forKey: StatKey.count)
        let words = defaults.integer(forKey: StatKey.words)
        let saved = Double(words) * (1.0 / typingWPM - 1.0 / speakingWPM)
        let accept = total > 0
            ? Int((Double(defaults.integer(forKey: StatKey.accepted)) * 100.0 / Double(total)).rounded())
            : -1
        let onDev = total > 0
            ? Int((Double(defaults.integer(forKey: StatKey.onDevice)) * 100.0 / Double(total)).rounded())
            : -1
        stats = DictationStats(totalWords: words, timeSavedMinutes: saved,
                               streakDays: defaults.integer(forKey: StatKey.streak),
                               acceptRate: accept, onDeviceRate: onDev)
    }

    private static func dayKey(_ ts: Double) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f.string(from: Date(timeIntervalSince1970: ts))
    }

    private static func isYesterday(_ prev: String, _ today: String) -> Bool {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        guard let todayDate = f.date(from: today),
              let dayBefore = Calendar.current.date(byAdding: .day, value: -1, to: todayDate)
        else { return false }
        return f.string(from: dayBefore) == prev
    }
}
