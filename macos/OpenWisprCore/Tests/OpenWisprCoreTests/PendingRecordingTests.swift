import XCTest
@testable import OpenWisprCore

/// Pins the two rules that make a failed dictation recoverable: retention never touches a
/// recording the user hasn't got their words back from, and "no result yet" survives a
/// round-trip through disk as *absence* rather than as an empty string.
final class PendingRecordingTests: XCTestCase {

    private let now: Double = 1_700_000_000
    private let day: Double = 86_400

    private func rec(_ id: UUID = UUID(), ageDays: Double, result: String? = "ok") -> PendingRecording {
        PendingRecording(id: id, ts: now - ageDays * day, durationSec: 3,
                         appBundleID: "com.example", appName: "Example",
                         engine: "parakeet", result: result)
    }

    // MARK: - Retention

    func testNeverExpiresAnUnsettledRecording() {
        // Ancient, and far past any window — but the user never got these words back.
        let old = [rec(ageDays: 999, result: nil), rec(ageDays: 999, result: nil)]
        XCTAssertTrue(AudioRetention.expired(old, keepDays: 7, now: now).isEmpty)
        XCTAssertTrue(AudioRetention.expired(old, keepDays: 1, now: now).isEmpty)
    }

    func testExpiresSettledRecordingsPastTheWindow() {
        let stale = UUID()
        let records = [rec(ageDays: 2), rec(stale, ageDays: 40)]
        XCTAssertEqual(AudioRetention.expired(records, keepDays: 30, now: now), [stale])
    }

    func testKeepDaysZeroKeepsEverything() {
        let records = [rec(ageDays: 5_000), rec(ageDays: 0)]
        XCTAssertTrue(AudioRetention.expired(records, keepDays: 0, now: now).isEmpty)
    }

    func testCapsTheStoreEvenWhenKeepingForever() {
        let records = (1...260).map { rec(ageDays: Double($0)) }
        let victims = AudioRetention.expired(records, keepDays: 0, now: now)
        XCTAssertEqual(victims.count, 60) // the 200 newest survive
        XCTAssertFalse(victims.contains(records[0].id))
        XCTAssertTrue(victims.contains(records[259].id))
    }

    func testCapCountsOnlySettledRows() {
        // Unsettled rows must not push settled ones over the cap, nor be counted for it.
        let unsettled = (1...300).map { rec(ageDays: Double($0), result: nil) }
        XCTAssertTrue(AudioRetention.expired(unsettled + [rec(ageDays: 1)], keepDays: 0, now: now).isEmpty)
    }

    // MARK: - Only terminal outcomes are persisted

    func testARecordingWithNoResultRoundTripsAsAbsent() {
        let pending = rec(ageDays: 0, result: nil)
        let data = try! JSONEncoder().encode(pending)
        let back = try! JSONDecoder().decode(PendingRecording.self, from: data)
        XCTAssertNil(back.result)
        XCTAssertFalse(back.settled)
        XCTAssertEqual(back, pending)
    }

    func testAnEmptyResultIsStillTerminal() {
        // "" is a legitimate (if useless) outcome and must not decay into "not yet" —
        // otherwise a settled dictation would keep re-offering itself for retry forever.
        let data = try! JSONEncoder().encode(rec(ageDays: 0, result: ""))
        let back = try! JSONDecoder().decode(PendingRecording.self, from: data)
        XCTAssertEqual(back.result, "")
        XCTAssertTrue(back.settled)
    }
}

/// The write-ahead copy is only worth having if it reads back as the same audio.
final class WavFileTests: XCTestCase {

    func testRoundTripsSamples() {
        let samples: [Float] = (0..<4000).map { Float(sin(Double($0) * 0.05)) * 0.8 }
        let decoded = WavFile.decode(WavFile.write(samples)!)!
        XCTAssertEqual(decoded.count, samples.count)
        for (a, b) in zip(samples, decoded) {
            XCTAssertEqual(a, b, accuracy: 1e-4) // PCM16 quantization only
        }
    }

    func testPreservesTheExtremes() {
        let decoded = WavFile.decode(WavFile.write([0, 1, -1, 0.5, -0.5])!)!
        XCTAssertEqual(decoded[0], 0, accuracy: 1e-6)
        XCTAssertEqual(decoded[1], 1, accuracy: 1e-4)
        XCTAssertEqual(decoded[2], -1, accuracy: 1e-4)
        XCTAssertEqual(decoded[3], 0.5, accuracy: 1e-4)
        XCTAssertEqual(decoded[4], -0.5, accuracy: 1e-4)
    }

    func testClampsOutOfRangeInputRatherThanOverflowing() {
        // Some capture paths can hand back slightly-over-unity samples; an Int16 overflow
        // here would trap at runtime and take the write-ahead copy with it.
        let decoded = WavFile.decode(WavFile.write([2.5, -3.0])!)!
        XCTAssertEqual(decoded[0], 1, accuracy: 1e-4)
        XCTAssertEqual(decoded[1], -1, accuracy: 1e-4)
    }

    func testWritesA44ByteRiffHeader() {
        let data = WavFile.write(Array(repeating: Float(0.1), count: 100))!
        XCTAssertEqual(data.count, 44 + 200)
        XCTAssertEqual(String(decoding: data[0..<4], as: UTF8.self), "RIFF")
        XCTAssertEqual(String(decoding: data[8..<12], as: UTF8.self), "WAVE")
        XCTAssertEqual(String(decoding: data[36..<40], as: UTF8.self), "data")
    }

    func testDecodeReturnsNilForATruncatedFile() {
        XCTAssertNil(WavFile.decode(Data(repeating: 0, count: 20)))
    }

    func testRoundTripsThroughAFileOnDisk() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("openwispr-wavfile-\(UUID().uuidString).wav")
        defer { try? FileManager.default.removeItem(at: url) }
        let samples: [Float] = [0.25, -0.25, 0.75]
        XCTAssertTrue(WavFile.write(samples, to: url))
        let back = try XCTUnwrap(WavFile.read(url))
        XCTAssertEqual(back.count, samples.count)
        for (a, b) in zip(samples, back) { XCTAssertEqual(a, b, accuracy: 1e-4) }
    }
}
