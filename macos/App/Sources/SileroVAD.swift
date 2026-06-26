import Foundation
import OpenWisprCore
import WhisperFramework

/// Real neural voice-activity detection: the Silero v5 model, run through the **already-vendored
/// `whisper.xcframework`'s** built-in VAD API (`whisper_vad_*`) — so it needs no extra dependency,
/// just the tiny (~865 KB) `ggml-silero-v5.1.2.bin` bundled in the app.
///
/// A drop-in `VAD` (same protocol as `EnergyVAD`): each 512-sample frame is fed to the streaming
/// `whisper_vad_detect_speech_no_reset` (which keeps the LSTM state across frames), and we return
/// the model's speech probability for that window. `SpeechSegmenter` consumes those probabilities
/// exactly as before, so its start/end hysteresis and auto-stop are unchanged.
///
/// Used sequentially from `AudioCapture`'s frame loop (one instance per capture), matching how
/// the whisper VAD context — which is single-threaded — must be driven.
final class SileroVAD: VAD {

    let frameSamples: Int
    private var vctx: OpaquePointer?

    /// The bundled Silero model, if present in the app bundle.
    static var bundledModelPath: String? {
        Bundle.main.url(forResource: "ggml-silero-v5.1.2", withExtension: "bin")?.path
    }

    /// Build a Silero VAD from a ggml model file. Returns `nil` if the model can't load (the
    /// caller then falls back to `EnergyVAD`).
    init?(modelPath: String, frameSamples: Int = 512) {
        self.frameSamples = frameSamples
        var params = whisper_vad_default_context_params()
        params.use_gpu = true
        let ctx = modelPath.withCString { whisper_vad_init_from_file_with_params($0, params) }
        guard let ctx else { return nil }
        self.vctx = ctx
    }

    deinit {
        if let vctx { whisper_vad_free(vctx) }
    }

    func reset() {
        if let vctx { whisper_vad_reset_state(vctx) }
    }

    func process(_ frame: [Float]) -> Float {
        guard let vctx, !frame.isEmpty else { return 0 }
        let ok = frame.withUnsafeBufferPointer { buf in
            whisper_vad_detect_speech_no_reset(vctx, buf.baseAddress, Int32(buf.count))
        }
        guard ok else { return 0 }
        let n = whisper_vad_n_probs(vctx)
        guard n > 0, let probs = whisper_vad_probs(vctx) else { return 0 }
        // The most recent window's probability (this frame).
        return probs[Int(n) - 1]
    }
}
