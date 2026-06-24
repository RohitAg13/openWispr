import AVFoundation
import Foundation
import OpenWisprCore
import Speech

/// `STT` provider backed by Apple's Speech framework. Batch-transcribes a finished take of
/// 16 kHz mono Float32 samples (what `AudioCapture` returns) by feeding them through an
/// `SFSpeechAudioBufferRecognitionRequest`. Prefers on-device recognition when supported so
/// dictation works offline and keeps audio local; falls back to server otherwise.
///
/// No native libraries to vendor — this is the stepping stone before the `whisper.cpp`
/// provider lands behind the same protocol.
final class AppleSpeechSTT: STT {

    func transcribe(_ samples: [Float], sampleRate: Int) async throws -> String {
        guard !samples.isEmpty else { throw STTError.noSpeechDetected }

        // Default-locale recognizer; nil/unavailable means we can't transcribe right now.
        guard let recognizer = SFSpeechRecognizer() else { throw STTError.unavailable }
        guard recognizer.isAvailable else { throw STTError.unavailable }

        // Wrap the samples in a PCM buffer matching the capture format (mono Float32).
        guard let format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Double(sampleRate),
            channels: 1,
            interleaved: false
        ), let buffer = AVAudioPCMBuffer(
            pcmFormat: format,
            frameCapacity: AVAudioFrameCount(samples.count)
        ), let channel = buffer.floatChannelData?[0] else {
            throw STTError.failed("Couldn't build the audio buffer for recognition.")
        }
        samples.withUnsafeBufferPointer { src in
            channel.update(from: src.baseAddress!, count: src.count)
        }
        buffer.frameLength = AVAudioFrameCount(samples.count)

        let request = SFSpeechAudioBufferRecognitionRequest()
        // On-device keeps audio local and works offline; not every locale/device supports it.
        request.requiresOnDeviceRecognition = recognizer.supportsOnDeviceRecognition
        request.shouldReportPartialResults = false
        request.append(buffer)
        request.endAudio()

        // The result handler can fire multiple times (and on an arbitrary queue). We resume
        // the continuation exactly once, guarded by `didResume` so a late partial/error after
        // a final result can't double-resume (which would crash). The lock serializes the
        // check-and-set across whatever queue the handler runs on.
        let lock = NSLock()
        var didResume = false
        // Hold the task for its lifetime so it isn't cancelled before producing a result.
        var task: SFSpeechRecognitionTask?

        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
            task = recognizer.recognitionTask(with: request) { result, error in
                func resumeOnce(_ body: () -> Void) {
                    lock.lock()
                    let alreadyResumed = didResume
                    if !alreadyResumed { didResume = true }
                    lock.unlock()
                    if !alreadyResumed { body() }
                }

                if let error = error {
                    resumeOnce { continuation.resume(throwing: STTError.failed(error.localizedDescription)) }
                    return
                }
                guard let result = result else { return }
                guard result.isFinal else { return }

                let text = result.bestTranscription.formattedString
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                if text.isEmpty {
                    resumeOnce { continuation.resume(throwing: STTError.noSpeechDetected) }
                } else {
                    resumeOnce { continuation.resume(returning: text) }
                }
            }
            _ = task // silence unused warning; the task is retained until the closure outlives it
        }
    }

    /// Request Speech recognition authorization. Returns `true` only when `.authorized`.
    static func requestAuthorization() async -> Bool {
        await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status == .authorized)
            }
        }
    }

    /// Request microphone access. On macOS 13 `AVCaptureDevice.requestAccess(for:)` is the
    /// available API (`AVAudioApplication.requestRecordPermission` is macOS 14+). Returns the
    /// current grant status if already decided.
    static func requestMicrophoneAccess() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            return true
        case .notDetermined:
            return await AVCaptureDevice.requestAccess(for: .audio)
        default:
            return false
        }
    }
}
