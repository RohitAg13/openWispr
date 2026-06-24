import XCTest
@testable import OpenWisprCore

final class VADTests: XCTestCase {

    // MARK: EnergyVAD

    func testEnergyVADSilenceIsLowProbability() {
        let vad = EnergyVAD()
        let silent = [Float](repeating: 0, count: 512)
        let p = vad.process(silent)
        XCTAssertLessThan(p, VADConfig().speechEndProb)
        XCTAssertEqual(p, 0, accuracy: 1e-6)
    }

    func testEnergyVADLoudFrameIsHighProbability() {
        let vad = EnergyVAD()
        // Constant 0.3 amplitude → RMS 0.3, well above speechLevel 0.08 → prob 1.
        let loud = [Float](repeating: 0.3, count: 512)
        let p = vad.process(loud)
        XCTAssertGreaterThanOrEqual(p, VADConfig().speechStartProb)
        XCTAssertEqual(p, 1, accuracy: 1e-6)
    }

    func testEnergyVADSineWaveAboveSpeechStart() {
        let vad = EnergyVAD()
        // 0.3-amplitude sine → RMS = 0.3 / sqrt(2) ≈ 0.212, above speechLevel.
        var frame = [Float](repeating: 0, count: 512)
        for i in 0..<512 {
            frame[i] = 0.3 * sin(2 * .pi * Float(i) / 16)
        }
        let p = vad.process(frame)
        XCTAssertGreaterThanOrEqual(p, VADConfig().speechStartProb)
    }

    // MARK: SpeechSegmenter — no false fire

    func testSegmenterNeverFiresWithoutSpeech() {
        let seg = SpeechSegmenter()
        var endSample = 0
        for _ in 0..<200 {
            endSample += 512
            let fired = seg.process(probability: 0.0, endSample: endSample)
            XCTAssertFalse(fired)
        }
        XCTAssertFalse(seg.hasSpeech)
        XCTAssertNil(seg.trimRange(totalSamples: endSample))
    }

    // MARK: SpeechSegmenter — fires exactly once at the computed frame

    func testSegmenterFiresExactlyOnceAtComputedFrame() {
        let config = VADConfig()
        let seg = SpeechSegmenter(config: config)
        let frame = config.frameSamples // 512

        // 10 speech frames @ prob 0.9. After the 10th, lastSpeechSample = 10*512 = 5120.
        let speechFrames = 10
        var endSample = 0
        for _ in 0..<speechFrames {
            endSample += frame
            XCTAssertFalse(seg.process(probability: 0.9, endSample: endSample))
        }
        XCTAssertTrue(seg.hasSpeech)
        let lastSpeech = speechFrames * frame // 5120

        // Hangover is 12800 = 25 frames. The silence frame whose endSample satisfies
        // (endSample - lastSpeech) >= 12800 fires. That is endSample = 5120 + 12800 =
        // 17920 = 35 * 512, i.e. the 25th silence frame (frame index 35 overall).
        let expectedFireEndSample = lastSpeech + config.hangoverSamples // 17920
        XCTAssertEqual(expectedFireEndSample, 17920)
        XCTAssertEqual(expectedFireEndSample / frame, 35)

        var fireCount = 0
        var firstFireEndSample = -1
        // Run plenty of silence frames past the threshold.
        for _ in 0..<60 {
            endSample += frame
            if seg.process(probability: 0.0, endSample: endSample) {
                fireCount += 1
                if firstFireEndSample < 0 { firstFireEndSample = endSample }
            }
        }
        XCTAssertEqual(fireCount, 1, "auto-stop must fire exactly once")
        XCTAssertEqual(firstFireEndSample, expectedFireEndSample)
    }

    // MARK: trimRange matches a hand-computed range

    func testTrimRangeMatchesComputedRange() {
        let config = VADConfig()
        let seg = SpeechSegmenter(config: config)
        let frame = config.frameSamples // 512

        // First speech frame: endSample = 512. firstSpeechSample =
        //   max(0, 512 - 512 - 3200) = max(0, -3200) = 0.
        XCTAssertFalse(seg.process(probability: 0.9, endSample: 512))

        // Advance speech to endSample 5120 (10th frame); lastSpeechSample = 5120.
        var endSample = 512
        for _ in 0..<9 {
            endSample += frame // up to 5120
            XCTAssertFalse(seg.process(probability: 0.9, endSample: endSample))
        }
        XCTAssertEqual(endSample, 5120)

        // totalSamples larger than lastSpeech + postPad so end is clamped by padding.
        let totalSamples = 30000
        // start = clamp(0, 0...30000) = 0
        // end = min(30000, 5120 + 4800) = min(30000, 9920) = 9920
        let range = seg.trimRange(totalSamples: totalSamples)
        XCTAssertEqual(range, 0..<9920)

        // When totalSamples is smaller than lastSpeech + postPad, end clamps to total.
        let smallRange = seg.trimRange(totalSamples: 6000)
        XCTAssertEqual(smallRange, 0..<6000)
    }

    func testTrimRangeNilWhenRegionTooShort() {
        let config = VADConfig()
        let seg = SpeechSegmenter(config: config)
        // One speech frame near the very end of a tiny buffer.
        XCTAssertTrue(seg.process(probability: 0.9, endSample: 512) == false)
        // firstSpeechSample = 0, lastSpeechSample = 512. With totalSamples = 512:
        //   start = 0, end = min(512, 512 + 4800) = 512 → length 512 >= 4000? no.
        XCTAssertNil(seg.trimRange(totalSamples: 512))
    }
}
