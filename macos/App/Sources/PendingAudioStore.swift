import Foundation
import OpenWisprCore

/// Durable, write-ahead store for dictation audio, mirroring the Android `PendingAudio`.
///
/// The failure this exists to prevent: `AudioCapture.stop()` handed back `[Float]` and nothing
/// else ever touched disk, so a transcription error left the user with an error toast and no
/// way back to the words they'd just spoken. Two rules:
///
///  1. **Write-ahead.** The WAV is in Application Support before the first transcription
///     attempt, and its lifetime is not coupled to that attempt. Nothing here is deleted from
///     an error handler — only on confirmed delivery, an explicit discard, or retention.
///
///  2. **Only terminal outcomes are persisted.** `result` is written once, when the text has
///     been delivered. "In progress" is derived from `inFlight`, which is in-memory by
///     construction: after a crash it's empty, so an interrupted recording correctly reads as
///     unfinished and offers a retry. No repair pass, no stuck rows to fix up on launch.
///
/// Read rule: `result != nil` → terminal · `result == nil` + in-flight → running ·
/// `result == nil` + not in-flight → offer retry.
@MainActor
final class PendingAudioStore: ObservableObject {
    static let shared = PendingAudioStore()

    /// Recordings the user still needs something from. Derived, never stored.
    @Published private(set) var unfinished: [PendingRecording] = []

    /// Ids a live transcription is working on. In-memory on purpose (rule 2 above).
    private var inFlight: Set<UUID> = []

    private let defaults = UserDefaults.standard
    private let keepDaysKey = "audioKeepDays"

    /// How many days of settled audio to keep. 0 means "keep everything".
    var keepDays: Int {
        get { defaults.object(forKey: keepDaysKey) as? Int ?? AudioRetention.defaultKeepDays }
        set { defaults.set(newValue, forKey: keepDaysKey); prune(); refresh() }
    }

    private var dir: URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("OpenWispr/pending", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func wavURL(_ id: UUID) -> URL { dir.appendingPathComponent("\(id.uuidString).wav") }
    private func metaURL(_ id: UUID) -> URL { dir.appendingPathComponent("\(id.uuidString).json") }

    init() { refresh() }

    // MARK: - Lifecycle

    /// Persist a take and mark it in-flight. Returns nil if the write failed, which callers
    /// must treat as "no durable copy exists".
    ///
    /// The audio is written under a temp name and moved into place, so a half-written WAV is
    /// never visible; the metadata lands only once the audio it describes is complete.
    func begin(samples: [Float], durationSec: Int, app: NSRunningApplicationLike?, engine: String) -> PendingRecording? {
        let record = PendingRecording(
            id: UUID(), ts: Date().timeIntervalSince1970, durationSec: durationSec,
            appBundleID: app?.bundleID, appName: app?.localizedName, engine: engine, result: nil
        )
        let tmp = dir.appendingPathComponent("\(record.id.uuidString).wav.part")
        guard WavFile.write(samples, to: tmp) else { return nil }
        do {
            try FileManager.default.moveItem(at: tmp, to: wavURL(record.id))
            try JSONEncoder().encode(record).write(to: metaURL(record.id), options: .atomic)
        } catch {
            try? FileManager.default.removeItem(at: tmp)
            return nil
        }
        inFlight.insert(record.id)
        refresh()
        return record
    }

    /// A live attempt has picked this recording up again (a retry from disk).
    func claim(_ id: UUID) { inFlight.insert(id); refresh() }

    /// Let go without recording an outcome. Writes nothing — absence of a result is already
    /// the correct on-disk state, and the row becomes retryable the moment nothing holds it.
    func release(_ id: UUID) { inFlight.remove(id); refresh() }

    /// Record the terminal outcome. Called only once the text has been delivered *and* logged
    /// to history — never speculatively.
    func settle(_ id: UUID, result: String) {
        inFlight.remove(id)
        if var record = load(id) {
            record.result = result
            try? JSONEncoder().encode(record).write(to: metaURL(id), options: .atomic)
        }
        // Honour the user's intent: history off means nothing lingers on disk.
        if AppSettings.shared.keepHistory { prune() } else { delete(id) }
        refresh()
    }

    /// The user explicitly threw this dictation away — a terminal outcome like any other, so
    /// the audio goes with it. Unlike a failure, where keeping it is the entire point.
    func discard(_ id: UUID) {
        inFlight.remove(id)
        delete(id)
        refresh()
    }

    // MARK: - Reading

    /// Samples for `id`, or nil if the blob is gone or unreadable.
    func samples(for id: UUID) -> [Float]? { WavFile.read(wavURL(id)) }

    func load(_ id: UUID) -> PendingRecording? {
        guard let data = try? Data(contentsOf: metaURL(id)) else { return nil }
        return try? JSONDecoder().decode(PendingRecording.self, from: data)
    }

    func all() -> [PendingRecording] {
        let urls = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        return urls
            .filter { $0.pathExtension == "json" }
            .compactMap { url -> PendingRecording? in
                guard let data = try? Data(contentsOf: url) else { return nil }
                return try? JSONDecoder().decode(PendingRecording.self, from: data)
            }
            .sorted { $0.ts > $1.ts }
    }

    /// Recompute the published `unfinished` list from disk + what's live right now.
    func refresh() {
        unfinished = all().filter { !$0.settled && !inFlight.contains($0.id) }
    }

    // MARK: - Retention

    /// Blob first, then the metadata row, so an interrupted prune never leaves a row pointing
    /// at audio that isn't there. The policy itself lives in `AudioRetention`.
    func prune() {
        for id in AudioRetention.expired(all(), keepDays: keepDays, now: Date().timeIntervalSince1970) {
            delete(id)
        }
    }

    /// Remove everything — used when the user turns "Keep history" off, where the promise is
    /// that nothing is saved to disk and retained audio would quietly break it.
    func purgeAll() {
        for record in all() { delete(record.id) }
        refresh()
    }

    private func delete(_ id: UUID) {
        try? FileManager.default.removeItem(at: wavURL(id))
        try? FileManager.default.removeItem(at: metaURL(id))
    }
}

/// The bits of `NSRunningApplication` the store needs, so the file doesn't drag AppKit in for
/// two strings (and so callers can pass a plain value).
struct NSRunningApplicationLike {
    let bundleID: String?
    let localizedName: String?
}
