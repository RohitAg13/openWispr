import Foundation

/// Small `public` facade over the (internal) sherpa-onnx Swift helper, purpose-built for the
/// offline NeMo Parakeet-TDT transducer. Keeps the verbose `SherpaOnnx.swift` wrapper internal
/// and exposes only what the app needs — primitive in, `String` out — so no sherpa C types leak
/// across the module boundary.
///
/// The recognizer is created once (it memory-maps the encoder/decoder/joiner ONNX graphs) and
/// reused across takes. sherpa's `OfflineRecognizer` is internally thread-safe per `decode`
/// call (each take gets a fresh stream), but callers should still serialize takes — the app's
/// `ParakeetSTT` actor does.
public final class SherpaParakeet {

    private let recognizer: SherpaOnnxOfflineRecognizer

    /// Build a recognizer from the on-disk Parakeet model files.
    ///
    /// - Parameters:
    ///   - encoder/decoder/joiner: paths to the `*.onnx` graphs (int8 variants are fine).
    ///   - tokens: path to `tokens.txt`.
    ///   - numThreads: ONNX Runtime intra-op threads. Leave a couple of cores for UI/audio.
    public init(
        encoder: String,
        decoder: String,
        joiner: String,
        tokens: String,
        numThreads: Int = 2
    ) {
        let transducer = sherpaOnnxOfflineTransducerModelConfig(
            encoder: encoder, decoder: decoder, joiner: joiner)
        // model_type left to auto-detect (sherpa recognizes the NeMo transducer from the graphs);
        // provider "cpu" is the only EP the vendored onnxruntime build ships on macOS.
        let modelConfig = sherpaOnnxOfflineModelConfig(
            tokens: tokens,
            transducer: transducer,
            numThreads: numThreads,
            provider: "cpu",
            modelType: "")
        let featConfig = sherpaOnnxFeatureConfig(sampleRate: 16_000, featureDim: 80)
        var config = sherpaOnnxOfflineRecognizerConfig(
            featConfig: featConfig,
            modelConfig: modelConfig,
            decodingMethod: "greedy_search")
        recognizer = SherpaOnnxOfflineRecognizer(config: &config)
    }

    /// Transcribe mono Float32 samples (normalized -1…1). `sampleRate` must be 16 kHz to match
    /// the model's feature front-end. Returns the recognized text (may be empty).
    public func transcribe(_ samples: [Float], sampleRate: Int = 16_000) -> String {
        recognizer.decode(samples: samples, sampleRate: sampleRate).text
    }
}
