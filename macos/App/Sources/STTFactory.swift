import Foundation
import OpenWisprCore

/// Resolves the `STT` provider for the current settings. Both dictation flows (the menu-bar
/// popover and the global-hotkey coordinator) go through here so the engine choice is honored
/// everywhere and the heavy Whisper model stays loaded across takes.
///
/// Graceful fallback: if Whisper is selected but its model isn't downloaded yet, we fall back
/// to Apple Speech so dictation still works — the Settings UI is where the user downloads the
/// model. `usesAppleSpeech(for:)` lets a caller know whether to also request Speech permission.
@MainActor
enum STTFactory {

    /// The last-built Whisper provider, kept warm so we don't reload the (hundreds-of-MB) model
    /// every session. Rebuilt only when the selected model changes.
    private static var cachedWhisper: (model: WhisperModel, stt: WhisperSTT)?

    /// The Parakeet provider, kept warm so we don't reload the (~660 MB) ONNX graphs every
    /// session. Built once on first use of the Parakeet engine.
    private static var cachedParakeet: ParakeetSTT?

    /// The provider to use for the next take.
    static func make() -> STT {
        let settings = AppSettings.shared
        switch settings.sttProvider {
        case .appleSpeech:
            return AppleSpeechSTT()
        case .whisper:
            let manager = WhisperModelManager.shared
            let model = settings.whisperModel
            guard manager.isDownloaded(model) else {
                // Not downloaded — fall back; Settings prompts the download.
                return AppleSpeechSTT()
            }
            if let cached = cachedWhisper, cached.model == model {
                return cached.stt
            }
            let stt = WhisperSTT(model: model, modelPath: manager.fileURL(for: model).path)
            cachedWhisper = (model, stt)
            return stt
        case .parakeet:
            let manager = ParakeetModelManager.shared
            guard manager.isDownloaded else {
                // Not downloaded — fall back; Settings prompts the download.
                return AppleSpeechSTT()
            }
            if let cached = cachedParakeet { return cached }
            let stt = ParakeetSTT(modelDirectory: manager.modelDirectory)
            cachedParakeet = stt
            return stt
        }
    }

    /// Build a specific provider, bypassing the user's setting. Used only by "retry on a
    /// different engine": Whisper and Parakeet mis-hear different things, so re-running saved
    /// audio on the other one genuinely rescues transcripts, and on-device it costs nothing.
    /// Returns nil when the engine's model isn't downloaded.
    static func make(_ provider: STTProvider) -> STT? {
        switch provider {
        case .appleSpeech:
            return AppleSpeechSTT()
        case .whisper:
            let manager = WhisperModelManager.shared
            let model = AppSettings.shared.whisperModel
            guard manager.isDownloaded(model) else { return nil }
            if let cached = cachedWhisper, cached.model == model { return cached.stt }
            let stt = WhisperSTT(model: model, modelPath: manager.fileURL(for: model).path)
            cachedWhisper = (model, stt)
            return stt
        case .parakeet:
            let manager = ParakeetModelManager.shared
            guard manager.isDownloaded else { return nil }
            if let cached = cachedParakeet { return cached }
            let stt = ParakeetSTT(modelDirectory: manager.modelDirectory)
            cachedParakeet = stt
            return stt
        }
    }

    /// The engine a take will actually run on, accounting for the not-downloaded fallback.
    /// Recorded alongside a pending recording so a retry knows what already failed.
    static func resolvedProvider() -> STTProvider {
        usesAppleSpeech() ? .appleSpeech : AppSettings.shared.sttProvider
    }

    /// A downloaded on-device engine that *isn't* `provider`, for a second attempt at saved
    /// audio. Nil when the user has nothing else installed.
    static func alternative(to provider: STTProvider) -> STTProvider? {
        let candidates: [STTProvider] = [.parakeet, .whisper]
        return candidates.first { candidate in
            guard candidate != provider else { return false }
            switch candidate {
            case .parakeet: return ParakeetModelManager.shared.isDownloaded
            case .whisper:  return WhisperModelManager.shared.isDownloaded(AppSettings.shared.whisperModel)
            case .appleSpeech: return false
            }
        }
    }

    /// Whether the next take will use Apple Speech (so the caller knows to request Speech
    /// recognition permission). True for the Apple provider and for the on-device fallbacks.
    static func usesAppleSpeech() -> Bool {
        let settings = AppSettings.shared
        switch settings.sttProvider {
        case .appleSpeech:
            return true
        case .whisper:
            return !WhisperModelManager.shared.isDownloaded(settings.whisperModel)
        case .parakeet:
            return !ParakeetModelManager.shared.isDownloaded
        }
    }
}
