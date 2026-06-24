import AppKit
import OpenWisprCore
import SwiftUI

/// App-scope orchestrator for the **hands-free** dictation flow: a global hotkey
/// toggles a session, audio is captured with VAD auto-stop, transcribed, cleaned, and
/// auto-inserted into whatever field had focus — all through a non-activating HUD so
/// the user's text field never loses focus.
///
/// Deliberately separate from `DictationController` (the menu-bar popover flow): they
/// own their own `AudioCapture`/`AppleSpeechSTT` so a popover session and a hotkey
/// session can't stomp each other.
///
/// State machine:
///
///     idle ──hotkey──▶ listening ──┬─ VAD auto-stop ─┐
///       ▲   (toggle)               └─ hotkey (toggle)─┴─▶ finish()
///       │                                                     │
///       │                          cancel (Cancel button)     ▼
///       └──────────────────────────────────────────────▶ transcribing
///                                                              │
///                                          ┌───────────────────┤
///                                          ▼                   ▼
///                                       inserted/message     error
///                                          │                   │
///                                          └──── auto-hide ─────┴──▶ idle
///
/// `finish()` is reached by either the hotkey (manual stop) or the VAD auto-stop
/// callback; an `isFinishing` guard makes whichever fires first win and the other a
/// no-op. `cancel()` aborts a `listening` session without transcribing.
@MainActor
final class DictationCoordinator {

    private enum State {
        case idle
        case listening
        case transcribing
    }

    private let audio = AudioCapture(vad: EnergyVAD(), config: VADConfig())
    private let stt = AppleSpeechSTT()
    private let hud = RecordingHUD()
    private var hotKey: HotKey?

    private var state: State = .idle
    /// The app/field we'll insert back into — captured at session start. The HUD is
    /// non-activating so this stays the user's app for the whole session.
    private var targetApp: NSRunningApplication?
    /// Drives the HUD level bar from `audio.amplitude`.
    private var levelTimer: Timer?
    /// Guards `finish()` against the hotkey + VAD auto-stop both firing.
    private var isFinishing = false

    init() {
        hud.state.onCancel = { [weak self] in self?.cancel() }
        // Register the default ⌃⌥Space global hotkey; toggles a session.
        hotKey = HotKey { [weak self] in self?.toggle() }
    }

    // MARK: - Hotkey entry point

    /// Idle → start; listening → manual stop. Ignored while transcribing.
    private func toggle() {
        switch state {
        case .idle:        start()
        case .listening:   finish()
        case .transcribing: break // busy; ignore taps
        }
    }

    // MARK: - Session lifecycle

    private func start() {
        // Capture the target field's app now; our non-activating HUD won't change it.
        targetApp = NSWorkspace.shared.frontmostApplication

        Task { @MainActor in
            let mic = await AppleSpeechSTT.requestMicrophoneAccess()
            let speech = await AppleSpeechSTT.requestAuthorization()
            guard mic && speech else {
                hud.update(.error("Enable Microphone + Speech in System Settings."))
                hud.show()
                autoHide(after: 2.5)
                return
            }

            do {
                isFinishing = false
                try audio.start(vadAutoStop: true) { [weak self] in
                    // Fired on the main queue when the speaker pauses.
                    Task { @MainActor in self?.finish() }
                }
                state = .listening
                hud.update(.listening(level: 0))
                hud.show()
                startLevelTimer()
            } catch {
                state = .idle
                hud.update(.error("Couldn't start the microphone."))
                hud.show()
                autoHide(after: 2.5)
            }
        }
    }

    /// Stop capture and run transcribe → clean → insert. Reached by manual stop
    /// (hotkey) or VAD auto-stop; guarded so only the first call proceeds.
    private func finish() {
        guard state == .listening, !isFinishing else { return }
        isFinishing = true
        stopLevelTimer()

        let samples = audio.stop()
        guard let samples = samples, !samples.isEmpty else {
            state = .idle
            hud.update(.error("Didn't catch anything."))
            autoHide(after: 1.8)
            return
        }

        state = .transcribing
        hud.update(.transcribing)

        Task { @MainActor in
            do {
                let raw = try await stt.transcribe(samples, sampleRate: 16000)
                let cleaned = TextProcessor.process(raw)
                deliver(cleaned)
            } catch let error as STTError {
                state = .idle
                hud.update(.error(Self.message(for: error)))
                autoHide(after: 2.0)
            } catch {
                state = .idle
                hud.update(.error(error.localizedDescription))
                autoHide(after: 2.0)
            }
        }
    }

    /// Insert (when trusted) or copy (when not), then auto-hide and return to idle.
    private func deliver(_ cleaned: String) {
        if cleaned.isEmpty {
            state = .idle
            hud.update(.error("Didn't catch anything."))
            autoHide(after: 1.5)
            return
        }

        if TextInserter.isTrusted {
            TextInserter.insert(cleaned, into: targetApp)
            hud.update(.inserted)
            autoHide(after: 1.0)
        } else {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(cleaned, forType: .string)
            hud.update(.message("Copied — grant Accessibility to auto-insert."))
            autoHide(after: 2.5)
        }
        state = .idle
    }

    /// Cancel button: abandon a listening session without transcribing.
    func cancel() {
        guard state == .listening else { return }
        isFinishing = true
        stopLevelTimer()
        _ = audio.stop()
        state = .idle
        hud.hide()
    }

    // MARK: - Helpers

    private func autoHide(after seconds: Double) {
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            // Only hide if we haven't started a new session in the meantime.
            if state == .idle { hud.hide() }
        }
    }

    private func startLevelTimer() {
        levelTimer?.invalidate()
        let timer = Timer(timeInterval: 0.05, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.state == .listening else { return }
                self.hud.update(.listening(level: self.audio.amplitude))
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        levelTimer = timer
    }

    private func stopLevelTimer() {
        levelTimer?.invalidate()
        levelTimer = nil
    }

    private static func message(for error: STTError) -> String {
        switch error {
        case .unavailable:      return "Speech recognition isn't available."
        case .notAuthorized:    return "Speech access denied — enable in Settings."
        case .noSpeechDetected: return "Didn't catch any speech."
        case .failed(let reason): return "Transcription failed: \(reason)"
        }
    }
}
