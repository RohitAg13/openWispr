import Foundation
import OpenWisprCore
import WhisperFramework

/// Fully-offline `STT` backed by whisper.cpp (the vendored `whisper.xcframework`). Transcribes
/// a finished take of 16 kHz mono Float32 samples — exactly what `AudioCapture` produces and
/// exactly the format `whisper_full` wants, so no resampling/conversion is needed.
///
/// whisper's context is **not** thread-safe, so all C calls are funnelled through a dedicated
/// `actor` (`Engine`). The heavy model load happens lazily on the first transcribe and the
/// context is kept warm for subsequent takes; swapping the selected model rebuilds it.
///
/// Lives in the app target (not `OpenWisprCore`) because it links the native xcframework, which
/// only resolves under an Xcode build — `OpenWisprCore`'s `swift test` stays pure Foundation.
final class WhisperSTT: STT {

    private let model: WhisperModel
    private let modelPath: String
    private let engine: Engine

    /// Build a provider for `model`. `modelPath` must point at an existing ggml `.bin`
    /// (resolve it via `WhisperModelManager.fileURL(for:)` and ensure it's downloaded first).
    init(model: WhisperModel, modelPath: String) {
        self.model = model
        self.modelPath = modelPath
        self.engine = Engine(modelPath: modelPath)
    }

    func transcribe(_ samples: [Float], sampleRate: Int) async throws -> String {
        guard !samples.isEmpty else { throw STTError.noSpeechDetected }
        // whisper requires 16 kHz mono. AudioCapture already delivers that; guard anyway so a
        // future caller can't silently feed the wrong rate.
        guard sampleRate == 16_000 else {
            throw STTError.failed("Whisper needs 16 kHz audio (got \(sampleRate) Hz).")
        }
        let text = try await engine.transcribe(samples)
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { throw STTError.noSpeechDetected }
        return trimmed
    }

    /// Serializes every whisper C call (the context is single-threaded) and owns the model's
    /// lifetime. The context is created on first use and freed on `deinit`.
    private actor Engine {
        private let modelPath: String
        private var ctx: OpaquePointer?
        /// Reasonable thread count: leave a couple of cores for the UI / audio.
        private let threadCount: Int32

        init(modelPath: String) {
            self.modelPath = modelPath
            let cores = ProcessInfo.processInfo.activeProcessorCount
            self.threadCount = Int32(max(1, min(8, cores - 2)))
        }

        deinit {
            if let ctx { whisper_free(ctx) }
        }

        /// Lazily load the model into a whisper context (kept warm across takes).
        private func loadIfNeeded() throws {
            if ctx != nil { return }
            var cparams = whisper_context_default_params()
            // Metal GPU on Apple Silicon; harmless to request on Intel (falls back to CPU).
            cparams.use_gpu = true
            cparams.flash_attn = true
            let loaded = modelPath.withCString { cString in
                whisper_init_from_file_with_params(cString, cparams)
            }
            guard let loaded else {
                throw STTError.failed("Couldn't load the Whisper model. Try re-downloading it.")
            }
            ctx = loaded
        }

        func transcribe(_ samples: [Float]) throws -> String {
            try loadIfNeeded()
            guard let ctx else { throw STTError.unavailable }

            var params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY)
            params.print_realtime   = false
            params.print_progress   = false
            params.print_timestamps = false
            params.print_special    = false
            params.translate        = false
            params.no_context       = true
            params.single_segment   = false
            params.suppress_blank   = true
            params.n_threads        = threadCount
            // English-only models; pinning the language skips detection and is faster/steadier.
            let lang = "en"

            let status = lang.withCString { langPtr -> Int32 in
                params.language = langPtr
                return samples.withUnsafeBufferPointer { buf in
                    whisper_full(ctx, params, buf.baseAddress, Int32(buf.count))
                }
            }
            guard status == 0 else {
                throw STTError.failed("Whisper transcription failed (code \(status)).")
            }

            var out = ""
            let n = whisper_full_n_segments(ctx)
            for i in 0..<n {
                if let cstr = whisper_full_get_segment_text(ctx, i) {
                    out += String(cString: cstr)
                }
            }
            return out
        }
    }
}
