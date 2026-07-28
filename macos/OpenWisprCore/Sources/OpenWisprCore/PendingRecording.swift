import Foundation

/// One recorded dictation take, persisted before anything is allowed to fail on it.
///
/// `result` is the entire state machine: a delivered transcript, or `nil` for "not yet, or
/// interrupted". There is deliberately no "transcribing" case — a running attempt lives only
/// in memory, so a crash can't leave a lie on disk and there is nothing to repair at launch.
/// The app-side store (`PendingAudioStore`) owns the files; this type and `AudioRetention`
/// hold the parts that are pure, and therefore testable.
public struct PendingRecording: Codable, Identifiable, Equatable, Sendable {
    public let id: UUID
    public let ts: Double        // epoch seconds
    public let durationSec: Int
    public let appBundleID: String?
    public let appName: String?
    /// Which engine the attempt used, so a retry can pick a different one.
    public let engine: String
    public var result: String?

    public init(
        id: UUID = UUID(), ts: Double, durationSec: Int,
        appBundleID: String? = nil, appName: String? = nil,
        engine: String, result: String? = nil
    ) {
        self.id = id
        self.ts = ts
        self.durationSec = durationSec
        self.appBundleID = appBundleID
        self.appName = appName
        self.engine = engine
        self.result = result
    }

    /// A terminal outcome exists. Absence — not a flag — is what means "still to come".
    public var settled: Bool { result != nil }
}

/// Which saved recordings may be deleted. Separated out so the one rule that must never bend
/// can be read (and tested) on its own.
public enum AudioRetention {

    /// Keep this many recordings at most, whatever the day window says, so the store can't
    /// grow without bound.
    public static let maxKeep = 200

    /// Days of settled audio to keep by default. Generous on purpose: the audio never leaves
    /// the machine, so retention costs only disk.
    public static let defaultKeepDays = 30

    /// The ids safe to delete.
    ///
    /// Only ever *settled* recordings. An unsettled one is a dictation the user hasn't got
    /// their words back from yet, and retention is not allowed to be the thing that finally
    /// loses it. `keepDays == 0` means "keep everything", still bounded by `maxKeep`.
    public static func expired(
        _ records: [PendingRecording],
        keepDays: Int,
        maxKeep: Int = AudioRetention.maxKeep,
        now: Double
    ) -> [UUID] {
        let settled = records.filter { $0.settled }.sorted { $0.ts > $1.ts }
        let cutoff = keepDays <= 0 ? -Double.greatestFiniteMagnitude : now - Double(keepDays) * 86_400
        return settled.enumerated()
            .filter { $0.offset >= maxKeep || $0.element.ts < cutoff }
            .map { $0.element.id }
    }
}

/// Minimal 16 kHz mono PCM16 WAV reader/writer — the on-disk form of a take. Matches the
/// Android `WavIo` byte for byte, so the two platforms' recordings are interchangeable.
public enum WavFile {

    public static let sampleRate = 16_000
    private static let headerBytes = 44

    @discardableResult
    public static func write(_ samples: [Float], to url: URL) -> Bool {
        write(samples).flatMap { try? $0.write(to: url, options: .atomic) } != nil
    }

    /// The encoded bytes, exposed so a round-trip can be checked without touching disk.
    public static func write(_ samples: [Float]) -> Data? {
        var data = Data(header(sampleCount: samples.count))
        data.reserveCapacity(headerBytes + samples.count * 2)
        for sample in samples {
            let value = Int16(max(-1, min(1, sample)) * 32767)
            data.append(UInt8(truncatingIfNeeded: value))
            data.append(UInt8(truncatingIfNeeded: value >> 8))
        }
        return data
    }

    /// Read the PCM16 payload back as normalized floats. Returns nil rather than throwing:
    /// callers reach for this on the recovery path, where a hard failure helps nobody.
    public static func read(_ url: URL) -> [Float]? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return decode(data)
    }

    public static func decode(_ data: Data) -> [Float]? {
        guard data.count > headerBytes else { return nil }
        let count = (data.count - headerBytes) / 2
        var out = [Float]()
        out.reserveCapacity(count)
        for i in 0..<count {
            let lo = UInt16(data[data.startIndex + headerBytes + i * 2])
            let hi = UInt16(data[data.startIndex + headerBytes + i * 2 + 1])
            out.append(Float(Int16(bitPattern: (hi << 8) | lo)) / 32768)
        }
        return out
    }

    private static func header(sampleCount: Int) -> [UInt8] {
        let channels = 1, bits = 16
        let byteRate = sampleRate * channels * bits / 8
        let dataSize = sampleCount * bits / 8
        func int32(_ v: Int) -> [UInt8] {
            [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 24) & 0xFF)]
        }
        func int16(_ v: Int) -> [UInt8] { [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF)] }
        return Array("RIFF".utf8) + int32(36 + dataSize) + Array("WAVE".utf8)
            + Array("fmt ".utf8) + int32(16) + int16(1) // PCM
            + int16(channels) + int32(sampleRate) + int32(byteRate)
            + int16(channels * bits / 8) + int16(bits)
            + Array("data".utf8) + int32(dataSize)
    }
}
