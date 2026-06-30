import Foundation
import OpenWisprCore
import SherpaOnnxFramework

/// Fully-offline `STT` backed by the NeMo Parakeet-TDT-0.6b-v2 transducer (int8), run through
/// sherpa-onnx + ONNX Runtime. Transcribes a finished take of 16 kHz mono Float32 samples —
/// exactly what `AudioCapture` produces and what sherpa's feature front-end expects, so no
/// resampling/conversion is needed.
///
/// Parakeet is sub-second on Apple Silicon and matches whisper-small accuracy, with strong
/// inverse text normalization (numbers, money, dates rendered as digits). It has no
/// `initial_prompt`-style biasing hook in the greedy path, so the personal-vocab `bias` terms
/// are applied downstream by the deterministic vocab corrector rather than here.
///
/// The recognizer isn't guaranteed thread-safe, so all decode calls are funnelled through a
/// dedicated `actor` (`Engine`). The heavy graph load happens lazily on the first transcribe and
/// the recognizer is kept warm for subsequent takes.
///
/// Lives in the app target (not `OpenWisprCore`) because it links the native xcframework, which
/// only resolves under an Xcode build — `OpenWisprCore`'s `swift test` stays pure Foundation.
final class ParakeetSTT: STT {

    private let engine: Engine

    /// Build a provider from the model directory (resolve it via `ParakeetModelManager` and ensure
    /// the files are downloaded first).
    init(modelDirectory: URL) {
        self.engine = Engine(modelDirectory: modelDirectory)
    }

    func transcribe(_ samples: [Float], sampleRate: Int) async throws -> String {
        guard !samples.isEmpty else { throw STTError.noSpeechDetected }
        // sherpa's Parakeet front-end expects 16 kHz mono. AudioCapture already delivers that;
        // guard anyway so a future caller can't silently feed the wrong rate.
        guard sampleRate == 16_000 else {
            throw STTError.failed("Parakeet needs 16 kHz audio (got \(sampleRate) Hz).")
        }
        let text = try await engine.transcribe(samples)
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { throw STTError.noSpeechDetected }
        return trimmed
    }

    /// Serializes every sherpa decode call and owns the recognizer's lifetime. The recognizer is
    /// created on first use and kept warm across takes; it's released when the provider is.
    private actor Engine {
        private let modelDirectory: URL
        private var recognizer: SherpaParakeet?
        private let threadCount: Int

        init(modelDirectory: URL) {
            self.modelDirectory = modelDirectory
            let cores = ProcessInfo.processInfo.activeProcessorCount
            self.threadCount = max(1, min(8, cores - 2))
        }

        /// Lazily build the recognizer (kept warm across takes).
        private func loadIfNeeded() throws {
            if recognizer != nil { return }
            let fm = FileManager.default
            func path(_ name: String) throws -> String {
                let url = modelDirectory.appendingPathComponent(name)
                guard fm.fileExists(atPath: url.path) else {
                    throw STTError.failed("Parakeet model file missing: \(name). Re-download it in Settings.")
                }
                return url.path
            }
            recognizer = try SherpaParakeet(
                encoder: path("encoder.int8.onnx"),
                decoder: path("decoder.int8.onnx"),
                joiner: path("joiner.int8.onnx"),
                tokens: path("tokens.txt"),
                numThreads: threadCount)
        }

        func transcribe(_ samples: [Float]) throws -> String {
            try loadIfNeeded()
            guard let recognizer else { throw STTError.unavailable }
            return recognizer.transcribe(samples, sampleRate: 16_000)
        }
    }
}
