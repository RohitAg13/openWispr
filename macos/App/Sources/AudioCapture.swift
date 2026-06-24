import AVFoundation
import Foundation
import OpenWisprCore

/// Mic capture for dictation. Mirrors the Android `AudioRecorder`: one take of 16 kHz
/// mono Float32 samples that feeds the VAD auto-stop state machine and, on `stop()`,
/// returns the speech-trimmed sample buffer.
///
/// `AVAudioEngine`'s `inputNode` delivers buffers in the hardware's native format, so
/// we install a tap with that native format and use an `AVAudioConverter` to resample
/// to 16 kHz mono Float32 (`frameSamples` chunks). The tap fires on a render thread;
/// shared buffers are guarded by a serial queue.
///
/// The VAD provider is injectable (default `EnergyVAD`) so the Silero model can replace
/// it later without touching capture. No ML model is loaded here.
final class AudioCapture {

    /// Target format: 16 kHz mono Float32, deinterleaved — what Whisper/Silero expect.
    private static let targetFormat = AVAudioFormat(
        commonFormat: .pcmFormatFloat32,
        sampleRate: 16000,
        channels: 1,
        interleaved: false
    )!

    private let config: VADConfig
    private let vad: VAD
    private let segmenter: SpeechSegmenter

    private let engine = AVAudioEngine()
    private var converter: AVAudioConverter?

    /// Serializes access to the shared capture buffers below (tap runs off-thread).
    private let lock = NSLock()

    /// The full take, all converted 16 kHz mono samples.
    private var samples: [Float] = []
    /// Carry-over converted samples not yet forming a full `frameSamples` frame.
    private var frameBuffer: [Float] = []
    /// Cumulative converted samples fed to the segmenter (mirrors Android `totalSamples`).
    private var totalSamples = 0

    private var running = false
    private var vadAutoStop = false
    private var onAutoStop: (() -> Void)?

    /// Most recent converted-frame peak amplitude (0...1), for waveform UI.
    private var _amplitude: Float = 0
    /// Recent peak/RMS amplitude of the converted audio, for UI.
    var amplitude: Float {
        lock.lock(); defer { lock.unlock() }
        return _amplitude
    }

    init(vad: VAD = EnergyVAD(), config: VADConfig = VADConfig()) {
        self.config = config
        self.vad = vad
        self.segmenter = SpeechSegmenter(config: config)
    }

    /// Begin capturing. When `vadAutoStop` is true, `onAutoStop` is invoked once (on the
    /// main queue) after the speaker pauses. Throws if the engine can't start.
    func start(vadAutoStop: Bool, onAutoStop: (() -> Void)?) throws {
        if running { return }

        lock.lock()
        samples.removeAll(keepingCapacity: true)
        frameBuffer.removeAll(keepingCapacity: true)
        totalSamples = 0
        _amplitude = 0
        lock.unlock()

        segmenter.reset()
        vad.reset()
        self.vadAutoStop = vadAutoStop
        self.onAutoStop = onAutoStop

        let input = engine.inputNode
        let inputFormat = input.inputFormat(forBus: 0)

        guard let converter = AVAudioConverter(from: inputFormat, to: Self.targetFormat) else {
            throw NSError(
                domain: "AudioCapture", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Couldn't create the audio converter."]
            )
        }
        self.converter = converter

        // Tap the native input format; convert inside the tap. A ~0.1s buffer keeps the
        // VAD responsive without thrashing the render thread.
        let tapFrames = AVAudioFrameCount(max(inputFormat.sampleRate / 10, 256))
        input.installTap(onBus: 0, bufferSize: tapFrames, format: inputFormat) { [weak self] buffer, _ in
            self?.handle(buffer: buffer)
        }

        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            self.converter = nil
            throw error
        }
        running = true
    }

    /// Stop capturing and return the speech-trimmed samples (full buffer when no trim
    /// applies), or `nil` if the take is too short (< sampleRate/4), mirroring Android.
    func stop() -> [Float]? {
        if running {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
            running = false
        }
        converter = nil

        lock.lock()
        let all = samples
        lock.unlock()

        let total = all.count
        if total < config.sampleRate / 4 { return nil } // < ~0.25 s: nothing meaningful

        if let range = segmenter.trimRange(totalSamples: total) {
            return Array(all[range])
        }
        return all
    }

    // MARK: - Tap handling

    /// Convert one native-format buffer to 16 kHz mono Float32 and feed the pipeline.
    private func handle(buffer: AVAudioPCMBuffer) {
        guard let converter = converter else { return }

        // Output capacity scaled by the sample-rate ratio (+ slack for rounding).
        let ratio = Self.targetFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 16
        guard capacity > 0,
              let outBuffer = AVAudioPCMBuffer(pcmFormat: Self.targetFormat, frameCapacity: capacity)
        else { return }

        var supplied = false
        var error: NSError?
        let status = converter.convert(to: outBuffer, error: &error) { _, outStatus in
            if supplied {
                outStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            outStatus.pointee = .haveData
            return buffer
        }

        guard status != .error, outBuffer.frameLength > 0,
              let channel = outBuffer.floatChannelData?[0]
        else { return }

        let n = Int(outBuffer.frameLength)
        let converted = Array(UnsafeBufferPointer(start: channel, count: n))
        ingest(converted)
    }

    /// Append converted samples, re-chunk into fixed `frameSamples` frames, and run the
    /// VAD + segmenter on each full frame. Called from the render thread.
    private func ingest(_ converted: [Float]) {
        let frameSize = config.frameSamples

        lock.lock()
        samples.append(contentsOf: converted)
        frameBuffer.append(contentsOf: converted)

        var framesToProcess: [[Float]] = []
        while frameBuffer.count >= frameSize {
            let frame = Array(frameBuffer[0..<frameSize])
            frameBuffer.removeFirst(frameSize)
            totalSamples += frameSize
            framesToProcess.append(frame)
        }
        // Snapshot the endSample for each frame under the lock.
        let baseEnd = totalSamples - framesToProcess.count * frameSize
        lock.unlock()

        var fired = false
        for (i, frame) in framesToProcess.enumerated() {
            let endSample = baseEnd + (i + 1) * frameSize

            // Peak amplitude of this frame for the UI.
            var peak: Float = 0
            for s in frame { peak = max(peak, abs(s)) }
            lock.lock(); _amplitude = peak; lock.unlock()

            let prob = vad.process(frame)
            if segmenter.process(probability: prob, endSample: endSample) {
                fired = true
            }
        }

        if fired && vadAutoStop {
            let cb = onAutoStop
            self.onAutoStop = nil // fire once
            DispatchQueue.main.async { cb?() }
        }
    }
}
