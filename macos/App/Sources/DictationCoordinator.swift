import AppKit
import Carbon.HIToolbox
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
///     idle ──trigger──▶ listening ──┬─ VAD auto-stop (when cutoff ON) ─┐
///       ▲   (toggle / PTT)           └─ trigger (toggle / release) ────┴─▶ finish()
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
/// `cancel()` aborts a listening or transcribing session without saving or inserting.
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
    /// On-screen + menu-bar dictation indicator (always shown unless the user turns it off).
    private let indicator = DictationIndicator()
    /// Global key monitor — double-click toggle + push-to-talk.
    private var triggerMonitor: FnTriggerMonitor?
    /// Escape → discard the active session immediately (listening or transcribing).
    private var escapeGlobalMonitor: Any?
    private var escapeLocalMonitor: Any?
    private var cancellables: Set<AnyCancellable> = []

    private enum SessionTrigger { case doubleClick, pushToTalk }
    private var sessionTrigger: SessionTrigger?

    private var state: State = .idle
    /// A finish requested before the (async) `start()` went live; applied once it does.
    private var pendingFinish = false
    /// The app/field we'll insert back into — captured at session start. The indicator is
    /// non-activating so this stays the user's app for the whole session.
    private var targetApp: NSRunningApplication?
    /// Drives the indicator level bar from `audio.amplitude`.
    private var levelTimer: Timer?
    /// Guards `finish()` against the hotkey + VAD auto-stop both firing.
    private var isFinishing = false

    /// The durable recording this session is working on (see `PendingAudioStore`). Cleared on
    /// delivery; handed back on failure so Home can offer a retry.
    private var pendingID: UUID?

    /// Safety backstop timer — only used when cutoff-on hands-free is active (see
    /// `startMaxDurationTimer`). Cleared in `stopLevelTimer`.
    private var maxDurationTimer: Timer?
    /// Invalidates an in-flight async `start()` when the user releases PTT early.
    private var startToken = 0
    /// Invalidates an in-flight transcribe → polish → deliver pipeline after Escape.
    private var transcribeToken = 0

    init() {
        // Build the capture with the persisted VAD sensitivity (Silero, energy fallback).
        let built = VADFactory.make(sensitivity: settings.vadSensitivity)
        audio = AudioCapture(vad: built.vad, config: built.config)

        indicator.onCancel = { [weak self] in self?.cancel() }
        indicator.onStop = { [weak self] in self?.finish() }

        // Install whichever trigger the user picked (fn double-click, or a toggle hotkey).
        installTrigger()
        installEscapeMonitor()
        observeSettings()
    }

    // MARK: - Escape (global discard)

    private var sessionActive: Bool {
        state == .listening || state == .transcribing
    }

    private func installEscapeMonitor() {
        escapeGlobalMonitor = NSEvent.addGlobalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard event.keyCode == UInt16(kVK_Escape), !event.isARepeat else { return }
            MainActor.assumeIsolated { self?.cancel() }
        }
        escapeLocalMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self, event.keyCode == UInt16(kVK_Escape), !event.isARepeat else { return event }
            if self.sessionActive {
                self.cancel()
                return nil
            }
            return event
        }
    }

    // MARK: - Live settings (Combine)

    /// Re-install triggers whenever mode flags or bindings change, and rebuild the VAD
    /// when sensitivity changes (only while idle — never mid-session).
    private func observeSettings() {
        Publishers.CombineLatest4(
            settings.$doubleClickEnabled,
            settings.$pushToTalkEnabled,
            settings.$doubleClickKeyCode,
            settings.$doubleClickKeyModifiers
        )
        .dropFirst()
        .receive(on: RunLoop.main)
        .sink { [weak self] _, _, _, _ in self?.installTrigger() }
        .store(in: &cancellables)

        Publishers.CombineLatest(settings.$pttKeyCode, settings.$pttKeyModifiers)
            .dropFirst()
            .receive(on: RunLoop.main)
            .sink { [weak self] _, _ in self?.installTrigger() }
            .store(in: &cancellables)

        settings.$vadSensitivity
            .dropFirst()
            .receive(on: RunLoop.main)
            .sink { [weak self] sensitivity in self?.rebuildVAD(for: sensitivity) }
            .store(in: &cancellables)
    }

    /// Tear down and reinstall the global trigger monitor from current settings.
    private func installTrigger() {
        triggerMonitor?.stop()
        triggerMonitor = nil

        guard settings.doubleClickEnabled || settings.pushToTalkEnabled else { return }

        let monitor = FnTriggerMonitor()
        monitor.doubleClickEnabled = settings.doubleClickEnabled
        monitor.doubleClickKeyCode = settings.doubleClickKeyCode
        monitor.doubleClickKeyModifiers = settings.doubleClickKeyModifiers
        monitor.pushToTalkEnabled = settings.pushToTalkEnabled
        monitor.pttKeyCode = settings.pttKeyCode
        monitor.pttKeyModifiers = settings.pttKeyModifiers
        monitor.onDoubleClickToggle = { [weak self] in
            self?.toggleHandsFree()
        }
        monitor.onPTTStart = { [weak self] in
            self?.startPushToTalk()
        }
        monitor.onPTTFinish = { [weak self] in
            self?.finishPushToTalk()
        }
        monitor.start()
        triggerMonitor = monitor
    }

    /// Rebuild `audio` from the new sensitivity ratios. Only safe while idle — swapping the
    /// capture mid-session would discard in-flight samples, so if we're listening we skip;
    /// the next session picks up the new VAD because `start()` reuses this `audio`.
    private func rebuildVAD(for sensitivity: VADSensitivity) {
        guard state == .idle else { return }
        let built = VADFactory.make(sensitivity: sensitivity)
        audio = AudioCapture(vad: built.vad, config: built.config)
    }

    // MARK: - Trigger entry points

    /// Double-click hands-free: idle → start; listening → manual stop.
    private func toggleHandsFree() {
        switch state {
        case .idle:
            sessionTrigger = .doubleClick
            start(vadAutoStop: settings.cutoffOnSpeechPause)
        case .listening where sessionTrigger == .doubleClick:
            finish()
        case .listening, .transcribing:
            break
        }
    }

    /// Push-to-talk: key down (after hold guard) → start; key up → finish.
    private func startPushToTalk() {
        guard state == .idle else { return }
        sessionTrigger = .pushToTalk
        start(vadAutoStop: false)
    }

    private func finishPushToTalk() {
        guard sessionTrigger == .pushToTalk else { return }
        // Released before the async mic bring-up finished — discard, don't transcribe a blip.
        if state == .idle {
            startToken += 1
            pendingFinish = false
            sessionTrigger = nil
            triggerMonitor?.sessionDidEnd()
            return
        }
        finish()
    }

    // MARK: - Session lifecycle

    private func start(vadAutoStop: Bool) {
        guard state == .idle else { return }
        pendingFinish = false
        targetApp = NSWorkspace.shared.frontmostApplication
        startToken += 1
        let token = startToken

        // Indicator first — menu bar + overlays — before any async mic work.
        indicator.presentListening(level: 0)

        Task { @MainActor in
            let mic = await AppleSpeechSTT.requestMicrophoneAccess()
            guard token == startToken else { return }
            guard sessionTrigger != nil else {
                if state == .idle { indicator.dismiss() }
                return
            }
            guard mic else {
                sessionTrigger = nil
                indicator.presentError("Enable Microphone in System Settings.")
                autoHide(after: 2.5)
                return
            }
            if STTFactory.usesAppleSpeech() {
                let speech = await AppleSpeechSTT.requestAuthorization()
                guard token == startToken else { return }
                guard sessionTrigger != nil else {
                    if state == .idle { indicator.dismiss() }
                    return
                }
                guard speech else {
                    sessionTrigger = nil
                    indicator.presentError("Enable Speech Recognition in System Settings.")
                    autoHide(after: 2.5)
                    return
                }
            }

            guard token == startToken else { return }
            guard sessionTrigger != nil else {
                if state == .idle { indicator.dismiss() }
                return
            }

            do {
                isFinishing = false
                let onPause: (() -> Void)? = vadAutoStop ? { [weak self] in
                    Task { @MainActor in self?.finishFromVAD() }
                } : nil
                try audio.start(vadAutoStop: vadAutoStop, onAutoStop: onPause)
                state = .listening
                indicator.presentListening(level: 0)
                startLevelTimer()
                startMaxDurationTimer(vadAutoStop: vadAutoStop)
                if pendingFinish {
                    pendingFinish = false
                    if sessionTrigger == .pushToTalk {
                        returnToIdle()
                        indicator.dismiss()
                    } else {
                        finish()
                    }
                }
            } catch {
                sessionTrigger = nil
                returnToIdle()
                indicator.presentError("Couldn't start the microphone.")
                autoHide(after: 2.5)
            }
        }
    }

    /// VAD auto-stop — only honor when cutoff is enabled for this hands-free session.
    private func finishFromVAD() {
        guard sessionTrigger == .doubleClick, settings.cutoffOnSpeechPause else { return }
        finish()
    }

    /// Stop capture and run transcribe → clean → insert. Reached by manual stop
    /// (hotkey) or VAD auto-stop; guarded so only the first call proceeds.
    private func finish() {
        guard !isFinishing else { return }
        guard state == .listening else {
            if state == .idle {
                if sessionTrigger == .pushToTalk {
                    startToken += 1
                    pendingFinish = false
                    sessionTrigger = nil
                    triggerMonitor?.sessionDidEnd()
                } else {
                    pendingFinish = true
                }
            }
            return
        }
        isFinishing = true
        stopLevelTimer()

        let samples = audio.stop()
        guard let samples = samples, !samples.isEmpty else {
            returnToIdle()
            indicator.presentError("Didn't catch anything.")
            autoHide(after: 1.8)
            return
        }

        // Write-ahead: the take is on disk before the first transcription attempt, so a failure
        // from here on leaves a recording the user can run again instead of an error toast and
        // nothing else. Not coupled to this attempt's success — nothing below deletes it.
        let recording = PendingAudioStore.shared.begin(
            samples: samples,
            durationSec: max(1, samples.count / WavFile.sampleRate),
            app: targetApp.map { NSRunningApplicationLike(bundleID: $0.bundleIdentifier, localizedName: $0.localizedName) },
            engine: STTFactory.resolvedProvider().rawValue
        )
        pendingID = recording?.id

        state = .transcribing
        indicator.presentTranscribing()
        transcribe(samples, using: STTFactory.make())
    }

    /// Transcribe → clean → polish → deliver. Split out of `finish()` so a retry from saved
    /// audio runs the identical pipeline rather than a second, subtly different one.
    private func transcribe(_ samples: [Float], using stt: STT) {
        transcribeToken += 1
        let token = transcribeToken
        let bias = VocabStore.shared.biasTerms
        let vocab = VocabStore.shared.entries
        Task { @MainActor in
            do {
                let raw = try await stt.transcribe(samples, sampleRate: 16000, bias: bias)
                guard token == transcribeToken, state == .transcribing else { return }
                let corrected = VocabCorrector.correct(raw, vocab)
                let cleaned = settings.smartCleanup ? TextProcessor.process(corrected) : corrected
                let category = AppContext.categoryFor(targetApp?.bundleIdentifier, cleaned)
                let polished = await applyPolish(cleaned, category: category)
                guard token == transcribeToken, state == .transcribing else { return }
                CorpusStore.shared.record(
                    cleaned: polished, kept: polished, category: category.key,
                    edited: false, at: Date().timeIntervalSince1970
                )
                deliver(polished)
            } catch is CancellationError {
                return
            } catch let error as STTError {
                guard token == transcribeToken, state == .transcribing else { return }
                fail(Self.message(for: error))
            } catch {
                guard token == transcribeToken, state == .transcribing else { return }
                fail(error.localizedDescription)
            }
        }
    }

    /// A failed attempt. The saved recording is deliberately left alone — this is precisely
    /// what it was written for — and handed back so Home lists it as unfinished with a retry.
    private func fail(_ message: String) {
        returnToIdle()
        if let id = pendingID {
            PendingAudioStore.shared.release(id)
            pendingID = nil
            indicator.presentError("\(message) Your recording is saved. Retry it from OpenWispr.")
            autoHide(after: 3.5)
        } else {
            indicator.presentError(message)
            autoHide(after: 2.0)
        }
    }

    /// Run the on-device LLM polish over the deterministic-cleaned text, if the user enabled a
    /// polish level and its model is downloaded. Falls back to the input on any miss (no model,
    /// load failure, or an over-edit guard trip — handled inside `LocalLLMEngine`). The focused
    /// app sets the tone category.
    private func applyPolish(_ text: String, category: AppContext.Category) async -> String {
        let level = settings.polishLevel
        guard level != .off else { return text }
        let manager = LlmModelManager.shared
        let model = settings.llmModel
        guard manager.isDownloaded(model) else { return text }
        // L3: inject the closest past corrections as few-shot examples.
        let fewShot = CorrectionCorpus.fewShotBlock(
            CorpusStore.shared.similar(query: text, category: category.key, k: 2)
        )
        return await LocalLLMEngine.shared.polish(
            text, level: level, category: category,
            modelPath: manager.fileURL(for: model).path,
            isFinetune: model.isFinetune,
            fewShot: fewShot
        )
    }

    /// Insert (when trusted) or copy (when not), then auto-hide and return to idle.
    ///
    /// History is written *before* the insert is attempted and regardless of how it goes: the
    /// transcript's survival must not depend on a synthetic keystroke landing in someone else's
    /// app. When the paste can't be confirmed the text is left on the clipboard and the HUD
    /// says so — a clipboard the user can clear beats words they can never get back.
    private func deliver(_ cleaned: String) {
        guard state == .transcribing else { return }
        if cleaned.isEmpty {
            fail("Didn't catch anything.")
            return
        }

        if settings.keepHistory {
            DictationHistoryStore.shared.add(cleaned)
        }

        switch TextInserter.isTrusted ? TextInserter.insert(cleaned, into: targetApp) : .failed {
        case .inserted, .unverified:
            // Unverified means ⌘V was posted but AX couldn't confirm (common in Electron /
            // browsers) — treat as success and get the indicator out of the way immediately.
            indicator.presentInserted()
            autoHide(after: 0.45)
        case .failed:
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(cleaned, forType: .string)
            indicator.presentMessage(
                TextInserter.isTrusted ? "Copied to your clipboard."
                                       : "Copied. Grant Accessibility to auto-insert."
            )
            autoHide(after: 1.2)
        }

        // Delivered and in history — only now may the recording be settled (and so become
        // eligible for retention pruning).
        if let id = pendingID {
            PendingAudioStore.shared.settle(id, result: cleaned)
            pendingID = nil
        }
        returnToIdle()
    }

    /// Escape, HUD Cancel, or discard: abort immediately — no history, no insert, no saved audio.
    func cancel() {
        if sessionActive {
            startToken += 1
            transcribeToken += 1
            pendingFinish = false
            isFinishing = true
            stopLevelTimer()

            if state == .listening {
                _ = audio.stop()
            }

            if let id = pendingID {
                PendingAudioStore.shared.discard(id)
                pendingID = nil
            }

            returnToIdle()
            isFinishing = false
            indicator.dismiss()
            return
        }

        // Dismiss a lingering post-session toast (inserted / message / error).
        if indicator.center.isActive {
            indicator.dismiss()
        }
    }

    // MARK: - Helpers

    /// Return to idle and tell the trigger monitor the session is over.
    private func returnToIdle() {
        state = .idle
        pendingFinish = false
        sessionTrigger = nil
        triggerMonitor?.sessionDidEnd()
    }

    private func autoHide(after seconds: Double) {
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            // Only hide if we haven't started a new session in the meantime.
            if state == .idle { indicator.dismiss() }
        }
    }

    private func startLevelTimer() {
        levelTimer?.invalidate()
        let timer = Timer(timeInterval: 0.05, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.state == .listening else { return }
                self.indicator.updateLevel(self.audio.amplitude)
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

    /// Optional safety backstop for a hands-free session someone left running unattended.
    /// Normal rambling sessions are ended by the user (toggle / PTT release / Escape) or by
    /// VAD when "Cutoff on speech pauses" is on — not by an arbitrary short time limit.
    private func startMaxDurationTimer(vadAutoStop: Bool) {
        maxDurationTimer?.invalidate()
        maxDurationTimer = nil

        // PTT ends on key release; hands-free without cutoff ends on manual toggle.
        if sessionTrigger == .pushToTalk { return }
        if sessionTrigger == .doubleClick && !vadAutoStop { return }

        // Cutoff-on: VAD should finish on pause. Only guard against a forgotten mic left hot.
        let limit: TimeInterval = 4 * 3600
        let timer = Timer(timeInterval: limit, repeats: false) { [weak self] _ in
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
        case .notAuthorized:    return "Speech access denied. Enable in Settings."
        case .noSpeechDetected: return "Didn't catch any speech."
        case .failed(let reason): return "Transcription failed: \(reason)"
        }
    }
}
