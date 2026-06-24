import Foundation

/// Voice-activity-detection layer: pure, deterministic logic ported from the Android
/// `AudioRecorder` VAD state machine (`updateVad` + the trim half of `stopSamples`)
/// and `SileroVad.CHUNK`. The actual Silero model lives behind the `VAD` protocol and
/// is plugged in later; `EnergyVAD` is a model-free fallback so the pipeline runs now.

/// Tunable constants for the VAD pipeline. Defaults mirror the Android constants
/// (`SAMPLE_RATE = 16_000`, `SileroVad.CHUNK = 512`).
public struct VADConfig {
    /// Whisper / Silero sample rate.
    public var sampleRate: Int
    /// Samples per VAD frame (Silero `CHUNK`, ~32 ms @ 16 kHz).
    public var frameSamples: Int
    /// Probability at/above which speech is considered to have started.
    public var speechStartProb: Float
    /// Probability below which a frame counts as silence for hangover.
    public var speechEndProb: Float
    /// Trailing silence (samples) that triggers auto-stop (~0.8 s @ 16 kHz).
    public var hangoverSamples: Int
    /// Padding kept before the first speech frame (~0.2 s @ 16 kHz).
    public var prePadSamples: Int
    /// Padding kept after the last speech frame (~0.3 s @ 16 kHz).
    public var postPadSamples: Int

    public init(
        sampleRate: Int = 16000,
        frameSamples: Int = 512,
        speechStartProb: Float = 0.5,
        speechEndProb: Float = 0.35,
        hangoverSamples: Int = 12800, // 0.8 * 16000
        prePadSamples: Int = 3200,    // 0.2 * 16000
        postPadSamples: Int = 4800    // 0.3 * 16000
    ) {
        self.sampleRate = sampleRate
        self.frameSamples = frameSamples
        self.speechStartProb = speechStartProb
        self.speechEndProb = speechEndProb
        self.hangoverSamples = hangoverSamples
        self.prePadSamples = prePadSamples
        self.postPadSamples = postPadSamples
    }
}

/// A per-frame speech-probability provider. Implementations take one frame of
/// `frameSamples` normalized (-1...1) samples and return a probability 0...1.
/// Silero will implement this later; `EnergyVAD` is the model-free fallback.
public protocol VAD {
    var frameSamples: Int { get }
    func reset()
    func process(_ frame: [Float]) -> Float
}

/// Model-free `VAD`: maps a frame's RMS energy to a pseudo-probability via a
/// noise-floor → speech-level ramp. Deterministic and pure; lets the capture/VAD
/// pipeline run before the Silero model is wired in.
public final class EnergyVAD: VAD {
    public let frameSamples: Int
    /// RMS at/below which the frame is treated as pure noise (prob 0).
    private let noiseFloor: Float
    /// RMS at/above which the frame is treated as full speech (prob 1).
    private let speechLevel: Float

    public init(config: VADConfig = VADConfig(), noiseFloor: Float = 0.01, speechLevel: Float = 0.08) {
        self.frameSamples = config.frameSamples
        self.noiseFloor = noiseFloor
        self.speechLevel = speechLevel
    }

    public func reset() {}

    public func process(_ frame: [Float]) -> Float {
        if frame.isEmpty { return 0 }
        var sumSq: Float = 0
        for s in frame { sumSq += s * s }
        let rms = (sumSq / Float(frame.count)).squareRoot()
        let span = speechLevel - noiseFloor
        guard span > 0 else { return rms >= speechLevel ? 1 : 0 }
        let p = (rms - noiseFloor) / span
        return min(1, max(0, p))
    }
}

/// Auto-stop state machine: a faithful port of Android `AudioRecorder.updateVad`
/// plus the trim half of `stopSamples`. It consumes per-frame probabilities and the
/// running total of captured samples, fires `onAutoStop` once on a speech-end pause,
/// and computes the speech-region trim range.
public final class SpeechSegmenter {
    private let config: VADConfig

    private var speechStarted = false
    private var firstSpeechSample = 0
    private var lastSpeechSample = 0
    private var autoStopFired = false

    public init(config: VADConfig = VADConfig()) {
        self.config = config
    }

    /// Clear all state for a fresh take.
    public func reset() {
        speechStarted = false
        firstSpeechSample = 0
        lastSpeechSample = 0
        autoStopFired = false
    }

    /// Whether any speech has been detected so far.
    public var hasSpeech: Bool { speechStarted }

    /// Feed one frame's probability. `endSample` is the cumulative total of samples
    /// captured *after* this frame (mirrors Android's `totalSamples`). Returns `true`
    /// exactly once — on the frame where the hangover silence threshold is crossed.
    public func process(probability prob: Float, endSample: Int) -> Bool {
        if prob >= config.speechStartProb {
            if !speechStarted {
                speechStarted = true
                firstSpeechSample = max(0, endSample - config.frameSamples - config.prePadSamples)
            }
            lastSpeechSample = endSample
        }
        if speechStarted && !autoStopFired && prob < config.speechEndProb {
            if endSample - lastSpeechSample >= config.hangoverSamples {
                autoStopFired = true
                return true
            }
        }
        return false
    }

    /// Speech-region trim range over a buffer of `totalSamples` (mirrors the trim in
    /// Android `stopSamples`). Returns `nil` when no speech was seen or the region is
    /// shorter than ~0.25 s — the caller should then keep the full audio.
    public func trimRange(totalSamples: Int) -> Range<Int>? {
        guard speechStarted else { return nil }
        let start = min(max(firstSpeechSample, 0), totalSamples)
        let end = min(totalSamples, lastSpeechSample + config.postPadSamples)
        if end - start >= config.sampleRate / 4 {
            return start..<end
        }
        return nil
    }
}
