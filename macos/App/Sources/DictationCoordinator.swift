import AppKit
import Combine
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

    /// Shared, persisted settings. The hotkey + VAD sensitivity are read from here and
    /// re-applied live via the Combine subscriptions below.
    private let settings = AppSettings.shared
    /// `var` (not `let`) so we can rebuild it when VAD sensitivity changes between sessions.
    private var audio: AudioCapture
    /// Resolved per-session from settings (Apple Speech or warm-cached Whisper).
    private let hud = RecordingHUD()
    private var hotKey: HotKey?
    private var cancellables: Set<AnyCancellable> = []

    private var state: State = .idle
    /// The app/field we'll insert back into — captured at session start. The HUD is
    /// non-activating so this stays the user's app for the whole session.
    private var targetApp: NSRunningApplication?
    /// Drives the HUD level bar from `audio.amplitude`.
    private var levelTimer: Timer?
    /// Guards `finish()` against the hotkey + VAD auto-stop both firing.
    private var isFinishing = false

    /// Backstop: force-finish a session that runs too long (e.g. if VAD never detects a pause).
    private var maxDurationTimer: Timer?
    private let maxSessionSeconds: TimeInterval = 30

    init() {
        // Build the capture with the persisted VAD sensitivity.
        let ratios = settings.vadRatios
        audio = AudioCapture(
            vad: EnergyVAD(config: VADConfig(), lowRatio: ratios.low, highRatio: ratios.high),
            config: VADConfig()
        )

        hud.state.onCancel = { [weak self] in self?.cancel() }
        hud.state.onStop = { [weak self] in self?.finish() }

        // Register the global hotkey from the user's binding; toggles a session.
        registerHotKey()
        observeSettings()
    }

    // MARK: - Live settings (Combine)

    /// Re-register the hotkey whenever the binding changes, and rebuild the VAD when
    /// sensitivity changes (only while idle — never mid-session).
    private func observeSettings() {
        // Re-register on either keycode or modifier change. `dropFirst` skips the initial
        // value publish so we don't immediately re-register what `init` already set.
        Publishers.CombineLatest(settings.$hotKeyCode, settings.$hotKeyModifiers)
            .dropFirst()
            .receive(on: RunLoop.main)
            .sink { [weak self] _, _ in self?.registerHotKey() }
            .store(in: &cancellables)

        settings.$vadSensitivity
            .dropFirst()
            .receive(on: RunLoop.main)
            .sink { [weak self] sensitivity in self?.rebuildVAD(for: sensitivity) }
            .store(in: &cancellables)
    }

    /// Drop the old `HotKey` (its `deinit` unregisters the Carbon hotkey) and register a
    /// fresh one from the current binding.
    private func registerHotKey() {
        hotKey = nil
        hotKey = HotKey(
            keyCode: settings.hotKeyCode,
            modifiers: settings.hotKeyModifiers
        ) { [weak self] in self?.toggle() }
    }

    /// Rebuild `audio` from the new sensitivity ratios. Only safe while idle — swapping the
    /// capture mid-session would discard in-flight samples, so if we're listening we skip;
    /// the next session picks up the new VAD because `start()` reuses this `audio`.
    private func rebuildVAD(for sensitivity: VADSensitivity) {
        guard state == .idle else { return }
        let ratios = sensitivity.ratios
        audio = AudioCapture(
            vad: EnergyVAD(config: VADConfig(), lowRatio: ratios.low, highRatio: ratios.high),
            config: VADConfig()
        )
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
            guard mic else {
                hud.update(.error("Enable Microphone in System Settings."))
                hud.show()
                autoHide(after: 2.5)
                return
            }
            // Speech permission is only needed for the Apple Speech provider (incl. the
            // Whisper-model-not-downloaded fallback). Whisper itself needs only the mic.
            if STTFactory.usesAppleSpeech() {
                let speech = await AppleSpeechSTT.requestAuthorization()
                guard speech else {
                    hud.update(.error("Enable Speech Recognition in System Settings."))
                    hud.show()
                    autoHide(after: 2.5)
                    return
                }
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
                startMaxDurationTimer()
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

        let stt = STTFactory.make()
        Task { @MainActor in
            do {
                let raw = try await stt.transcribe(samples, sampleRate: 16000)
                let cleaned = TextProcessor.process(raw)
                // Read the user's polish preference now so step 3 can branch on it. For
                // non-`.off` levels there's no LLM yet, so we behave as `.off` (and those
                // levels are disabled in the UI anyway).
                let polish = settings.polishLevel
                _ = polish
                // TODO(step 3): apply LLM polish for .light/.medium/.full
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
        maxDurationTimer?.invalidate()
        maxDurationTimer = nil
    }

    /// Force-finish after [maxSessionSeconds] so a session can't hang if VAD never fires.
    private func startMaxDurationTimer() {
        maxDurationTimer?.invalidate()
        let timer = Timer(timeInterval: maxSessionSeconds, repeats: false) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.state == .listening else { return }
                self.finish()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        maxDurationTimer = timer
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
