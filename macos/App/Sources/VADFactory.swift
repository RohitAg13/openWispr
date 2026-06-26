import Foundation
import OpenWisprCore

/// Builds the voice-activity detector for a capture session. Prefers the bundled **Silero**
/// neural VAD (via the whisper framework); falls back to the model-free `EnergyVAD` if the
/// Silero model can't load. Both flows return a matching `VADConfig` so `SpeechSegmenter`'s
/// hysteresis is tuned to the detector in use.
@MainActor
enum VADFactory {

    /// The VAD + its config for `sensitivity`. With Silero, sensitivity maps to the segmenter's
    /// speech start/end probability thresholds; with the energy fallback, to its ramp ratios.
    static func make(sensitivity: VADSensitivity) -> (vad: VAD, config: VADConfig) {
        if let path = SileroVAD.bundledModelPath, let silero = SileroVAD(modelPath: path) {
            var config = VADConfig()
            let probs = sensitivity.sileroProbs
            config.speechStartProb = probs.start
            config.speechEndProb = probs.end
            return (silero, config)
        }
        let ratios = sensitivity.ratios
        return (
            EnergyVAD(config: VADConfig(), lowRatio: ratios.low, highRatio: ratios.high),
            VADConfig()
        )
    }
}
