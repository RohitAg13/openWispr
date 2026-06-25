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
        }
    }

    /// Whether the next take will use Apple Speech (so the caller knows to request Speech
    /// recognition permission). True for the Apple provider and for the Whisper fallback.
    static func usesAppleSpeech() -> Bool {
        let settings = AppSettings.shared
        switch settings.sttProvider {
        case .appleSpeech:
            return true
        case .whisper:
            return !WhisperModelManager.shared.isDownloaded(settings.whisperModel)
        }
    }
}
