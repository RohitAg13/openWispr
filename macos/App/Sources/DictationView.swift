import AppKit
import OpenWisprCore
import SwiftUI

/// End-to-end dictation: record with VAD auto-stop, batch-transcribe via Apple Speech, then
/// run the deterministic cleanup. Owns the audio + STT and drives the `DictationView` UI.
/// `@MainActor` so all `@Published` mutation happens on the main thread.
@MainActor
final class DictationController: ObservableObject {

    /// UI state machine for the dictation flow.
    enum Phase: Equatable {
        case idle
        case listening
        case transcribing
        case done
        case error(String)
    }

    @Published var phase: Phase = .idle
    @Published var raw: String = ""
    @Published var cleaned: String = ""
    @Published var amplitude: Float = 0
    /// Set briefly after a successful insert so the UI can show "Inserted ✓".
    @Published var didInsert: Bool = false
    /// Whether Accessibility is granted. Polled (AXIsProcessTrusted isn't observable), so the
    /// label flips to "on" right after the user grants it — no app restart, no stale UI.
    @Published var accessibilityGranted: Bool = TextInserter.isTrusted
    /// The id of the history record for the current result, so an inline edit (learn-from-edit)
    /// updates the same row. The persisted list lives in `DictationHistoryStore.shared`.
    private var currentRecordID: UUID?

    // MARK: - Learn-from-edit (additive)

    /// The user-editable copy of `cleaned` shown in the Home transcript card, so they can
    /// correct it and "Save & teach". Seeded from `cleaned` when a result lands.
    @Published var editableCleaned: String = ""
    /// Set briefly after a successful teach so the UI can show "Learned ✓".
    @Published var didTeach: Bool = false

    // Silero VAD (energy fallback) at the persisted sensitivity, mirroring the capture pipeline.
    private let audio: AudioCapture = {
        let built = VADFactory.make(sensitivity: AppSettings.shared.vadSensitivity)
        return AudioCapture(vad: built.vad, config: built.config)
    }()

    /// Polls `audio.amplitude` for the live level bar while listening.
    private var levelTimer: Timer?
    /// Polls Accessibility-trust so the status updates after the user grants it.
    private var trustTimer: Timer?

    /// The most recent frontmost app that wasn't us — the field we insert back into.
    private(set) var lastTargetApp: NSRunningApplication?
    private var activationObserver: NSObjectProtocol?

    init() {
        startTrackingFrontmostApp()
        // Re-check trust every ~1.2s so granting Accessibility flips the UI without a restart.
        let t = Timer(timeInterval: 1.2, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            Task { @MainActor in self.refreshAccessibility() }
        }
        RunLoop.main.add(t, forMode: .common)
        trustTimer = t
    }

    deinit {
        if let token = activationObserver {
            NSWorkspace.shared.notificationCenter.removeObserver(token)
        }
        trustTimer?.invalidate()
    }

    /// Whether Accessibility access is currently granted (drives the Insert vs. Enable UI).
    var canInsert: Bool { accessibilityGranted }

    /// Re-read AXIsProcessTrusted and publish it if it changed.
    func refreshAccessibility() {
        let v = TextInserter.isTrusted
        if v != accessibilityGranted { accessibilityGranted = v }
    }

    /// Observe app activations and remember the last frontmost app that isn't OpenWispr, so
    /// opening our popover (which activates us) never overwrites the real target.
    private func startTrackingFrontmostApp() {
        // Seed with the current frontmost app if it isn't us.
        if let current = NSWorkspace.shared.frontmostApplication,
            current.bundleIdentifier != Bundle.main.bundleIdentifier {
            lastTargetApp = current
        }
        activationObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification,
            object: nil,
            queue: .main
        ) { [weak self] note in
            guard let self = self else { return }
            guard
                let app = note.userInfo?[NSWorkspace.applicationUserInfoKey]
                    as? NSRunningApplication
            else { return }
            // Ignore our own app so the popover doesn't clobber the target.
            guard app.bundleIdentifier != Bundle.main.bundleIdentifier else { return }
            Task { @MainActor in self.lastTargetApp = app }
        }
    }

    /// Insert the cleaned text into the remembered target app's focused field.
    func insertCleaned() {
        guard !cleaned.isEmpty else { return }
        let ok = TextInserter.insert(cleaned, into: lastTargetApp)
        if ok {
            didInsert = true
            // Clear the confirmation after a beat.
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                self.didInsert = false
            }
        }
    }

    /// Trigger the system Accessibility prompt / open the pane.
    func requestAccessibility() {
        TextInserter.requestAccess()
    }

    /// Whether the user's edited copy differs from what the pipeline produced — the
    /// signal that there's something worth teaching.
    var hasEdits: Bool {
        editableCleaned.trimmingCharacters(in: .whitespacesAndNewlines)
            != cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// "Save & teach": record the correction in the style corpus and learn any
    /// term-level aliases from the edit so future dictations improve. Additive — leaves
    /// the existing dictation flow untouched. Adopts the edited text as the new `cleaned`
    /// so Insert/Copy use it.
    func teach() {
        let original = cleaned
        let edited = editableCleaned.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !edited.isEmpty, edited != original.trimmingCharacters(in: .whitespacesAndNewlines) else { return }

        let cat = AppContext.categoryFor(lastTargetApp?.bundleIdentifier, edited).key
        CorpusStore.shared.record(
            cleaned: original,
            kept: edited,
            category: cat,
            edited: true,
            at: Date().timeIntervalSince1970
        )

        for pair in CorrectionCorpus.learnFromEditPairs(original: original, edited: edited) {
            VocabStore.shared.learnAlias(wrong: pair.wrong, right: pair.right)
        }

        // Adopt the edit so Insert/Copy operate on the corrected text, and update the
        // matching history row.
        cleaned = edited
        if let id = currentRecordID {
            DictationHistoryStore.shared.update(id: id, text: edited)
        }

        didTeach = true
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            self.didTeach = false
        }
    }

    var isListening: Bool { phase == .listening }
    var isBusy: Bool { phase == .listening || phase == .transcribing }

    /// Listen / Stop toggle target.
    func toggle() {
        switch phase {
        case .listening:
            stopAndTranscribe()
        default:
            startListening()
        }
    }

    private func startListening() {
        raw = ""
        cleaned = ""
        editableCleaned = ""
        didInsert = false
        didTeach = false
        Task {
            // Mic is always required; Speech only for the Apple provider (or Whisper fallback).
            let mic = await AppleSpeechSTT.requestMicrophoneAccess()
            guard mic else {
                phase = .error(
                    "Microphone access is needed. Enable it in System Settings ▸ "
                        + "Privacy & Security ▸ Microphone, then try again."
                )
                return
            }
            if STTFactory.usesAppleSpeech() {
                let speech = await AppleSpeechSTT.requestAuthorization()
                guard speech else {
                    phase = .error(
                        "Speech Recognition access is needed for Apple Speech. Enable it in "
                            + "System Settings ▸ Privacy & Security, then try again."
                    )
                    return
                }
            }

            do {
                try audio.start(vadAutoStop: true) { [weak self] in
                    // Fired once on the main queue when the speaker pauses.
                    Task { @MainActor in self?.stopAndTranscribe() }
                }
                phase = .listening
                startLevelTimer()
            } catch {
                phase = .error("Couldn't start the microphone: \(error.localizedDescription)")
            }
        }
    }

    private func stopAndTranscribe() {
        guard phase == .listening else { return } // guard against manual + auto-stop racing
        stopLevelTimer()

        let samples = audio.stop()
        guard let samples = samples else {
            phase = .error("Didn't catch anything.")
            return
        }

        phase = .transcribing
        let stt = STTFactory.make()
        Task {
            do {
                let rawText = try await stt.transcribe(samples, sampleRate: 16000)
                // Deterministic cleanup (user-toggleable); vocab correction is empty for now.
                let cleanedText = AppSettings.shared.smartCleanup
                    ? TextProcessor.process(rawText) : rawText
                raw = rawText
                cleaned = cleanedText
                editableCleaned = cleanedText
                phase = .done
                // Honor the Privacy ▸ Keep history toggle.
                if AppSettings.shared.keepHistory {
                    currentRecordID = DictationHistoryStore.shared.add(cleanedText)
                }
            } catch let error as STTError {
                phase = .error(Self.message(for: error))
            } catch {
                phase = .error(error.localizedDescription)
            }
        }
    }

    private static func message(for error: STTError) -> String {
        switch error {
        case .unavailable:
            return "Speech recognition isn't available right now."
        case .notAuthorized:
            return "Speech recognition access was denied. Enable it in System Settings."
        case .noSpeechDetected:
            return "Didn't catch any speech."
        case .failed(let reason):
            return "Transcription failed: \(reason)"
        }
    }

    /// Sample the current capture level into the published amplitude. Called on the main
    /// thread by the level timer (the controller is @MainActor).
    func sampleLevel() {
        amplitude = audio.amplitude
    }

    private func startLevelTimer() {
        levelTimer?.invalidate()
        // Timer fires on the main run loop; hop to the actor to read + publish the level.
        let timer = Timer(timeInterval: 0.05, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            Task { @MainActor in self.sampleLevel() }
        }
        RunLoop.main.add(timer, forMode: .common)
        levelTimer = timer
    }

    private func stopLevelTimer() {
        levelTimer?.invalidate()
        levelTimer = nil
        amplitude = 0
    }
}

/// Menu-bar popover UI for dictation: a Listen/Stop button, a live level bar while listening,
/// and the raw + cleaned transcripts with Copy / Quit once done.
struct DictationView: View {
    @StateObject private var controller = DictationController()
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            hotkeyHint
            controlRow
            statusArea
            actionRow
        }
        .padding(18)
        .frame(width: 440)
        .background(OW.bg)
        .onAppear { controller.refreshAccessibility() }
    }

    // MARK: - Header (brand + status pill)

    private var header: some View {
        HStack(spacing: 10) {
            OWOrb(size: 30)
            Text("OpenWispr")
                .font(OW.ui(19, weight: .semibold))
                .foregroundStyle(OW.text)
            Spacer()
            statusPill
        }
    }

    /// A small "READY / LISTENING / …" pill mirroring the design's status indicator.
    private var statusPill: some View {
        HStack(spacing: 7) {
            Circle()
                .fill(statusDotColor)
                .frame(width: 7, height: 7)
            MonoLabel(text: statusText, color: OW.textDim, size: 10, tracking: 1.0)
        }
    }

    private var statusText: String {
        switch controller.phase {
        case .idle:         return controller.canInsert ? "Ready" : "Setup"
        case .listening:    return "Listening"
        case .transcribing: return "Working"
        case .done:         return "Done"
        case .error:        return "Error"
        }
    }

    private var statusDotColor: Color {
        switch controller.phase {
        case .listening:    return OW.coral
        case .error:        return OW.danger
        case .done:         return OW.success
        default:            return controller.canInsert ? OW.coral : OW.textMuted
        }
    }

    private var hotkeyHint: some View {
        HStack(spacing: 8) {
            keyCap(AppSettings.shared.hotKeyDisplay)
            Text("to dictate anywhere")
                .font(OW.ui(12))
                .foregroundStyle(OW.textMuted)
            Spacer()
            HStack(spacing: 5) {
                Image(systemName: controller.canInsert ? "checkmark.shield.fill" : "shield.slash")
                    .font(.system(size: 11))
                    .foregroundStyle(controller.canInsert ? OW.success : OW.textMuted)
                Text(controller.canInsert ? "auto-insert on" : "Accessibility off")
                    .font(OW.ui(11))
                    .foregroundStyle(controller.canInsert ? OW.success : OW.textMuted)
            }
            .help(controller.canInsert
                ? "Accessibility granted — auto-insert enabled"
                : "Accessibility not granted")
        }
    }

    private func keyCap(_ text: String) -> some View {
        Text(text)
            .font(OW.mono(11, weight: .medium))
            .foregroundStyle(OW.textDim)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(OW.chip, in: RoundedRectangle(cornerRadius: 6))
            .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(OW.border, lineWidth: 1))
    }

    /// Insert / Copy / Quit. The primary action depends on whether Accessibility is granted.
    @ViewBuilder
    private var actionRow: some View {
        VStack(alignment: .leading, spacing: 10) {
            Rectangle().fill(OW.divider).frame(height: 1)

            if controller.didInsert {
                Label("Inserted into the active app", systemImage: "checkmark.circle.fill")
                    .font(OW.ui(12, weight: .semibold))
                    .foregroundStyle(OW.success)
            } else if !controller.canInsert {
                Text(
                    "Grant OpenWispr access under System Settings ▸ Privacy & Security ▸ "
                        + "Accessibility, then try Insert."
                )
                .font(OW.ui(11))
                .foregroundStyle(OW.textMuted)
                .fixedSize(horizontal: false, vertical: true)
            }

            HStack(spacing: 8) {
                if controller.canInsert {
                    Button {
                        controller.insertCleaned()
                    } label: {
                        Label("Insert", systemImage: "text.insert")
                    }
                    .buttonStyle(OWPrimaryButtonStyle())
                    .disabled(controller.cleaned.isEmpty)
                } else {
                    Button {
                        controller.requestAccessibility()
                    } label: {
                        Label("Enable auto-insert", systemImage: "lock.shield")
                    }
                    .buttonStyle(OWPrimaryButtonStyle())
                }

                Button("Copy") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(controller.cleaned, forType: .string)
                }
                .buttonStyle(OWSecondaryButtonStyle())
                .disabled(controller.cleaned.isEmpty)

                Spacer()
                Button("Settings…") {
                    openWindow(id: "settings")
                    NSApp.activate(ignoringOtherApps: true)
                }
                .buttonStyle(OWGhostButtonStyle())
                Button("Quit") { NSApp.terminate(nil) }
                    .buttonStyle(OWGhostButtonStyle())
            }
        }
    }

    private var controlRow: some View {
        Button(action: controller.toggle) {
            HStack(spacing: 8) {
                Image(systemName: controller.isListening ? "stop.fill" : "mic.fill")
                Text(controller.isListening ? "Stop" : "Listen")
            }
            .font(OW.ui(15, weight: .semibold))
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(OWPrimaryButtonStyle(large: true))
        .disabled(controller.phase == .transcribing)
    }

    @ViewBuilder
    private var statusArea: some View {
        switch controller.phase {
        case .idle:
            Text("Press Listen and start speaking. It stops automatically when you pause.")
                .font(OW.ui(12))
                .foregroundStyle(OW.textMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(OW.card, in: RoundedRectangle(cornerRadius: OW.rCard))
                .overlay(RoundedRectangle(cornerRadius: OW.rCard).strokeBorder(OW.border, lineWidth: 1))

        case .listening:
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 7) {
                    Circle().fill(OW.coral).frame(width: 6, height: 6)
                    MonoLabel(text: "Listening", color: OW.coralDeep, size: 10, tracking: 1.4)
                }
                levelBar
                MonoLabel(text: "On-device · nothing uploaded", color: OW.textMuted, size: 9, tracking: 0.8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(OW.card, in: RoundedRectangle(cornerRadius: OW.rCard))
            .overlay(RoundedRectangle(cornerRadius: OW.rCard).strokeBorder(OW.border, lineWidth: 1))

        case .transcribing:
            HStack(spacing: 10) {
                ProgressView().controlSize(.small).tint(OW.coral)
                MonoLabel(text: "Transcribing", color: OW.textDim, size: 10, tracking: 1.4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(OW.card, in: RoundedRectangle(cornerRadius: OW.rCard))
            .overlay(RoundedRectangle(cornerRadius: OW.rCard).strokeBorder(OW.border, lineWidth: 1))

        case .done:
            VStack(alignment: .leading, spacing: 12) {
                transcript(label: "Raw", text: controller.raw, mono: true, faint: true)
                Rectangle().fill(OW.divider).frame(height: 1)
                transcript(label: "Cleaned", text: controller.cleaned, mono: false, faint: false)
            }
            .padding(14)
            .background(OW.card, in: RoundedRectangle(cornerRadius: OW.rCard))
            .overlay(RoundedRectangle(cornerRadius: OW.rCard).strokeBorder(OW.border, lineWidth: 1))

        case .error(let message):
            HStack(alignment: .top, spacing: 9) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(OW.danger)
                    .font(.system(size: 13))
                Text(message)
                    .font(OW.ui(12))
                    .foregroundStyle(OW.text)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(OW.danger.opacity(0.08), in: RoundedRectangle(cornerRadius: OW.rCard))
            .overlay(RoundedRectangle(cornerRadius: OW.rCard).strokeBorder(OW.danger.opacity(0.3), lineWidth: 1))
        }
    }

    private var levelBar: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(OW.track)
                Capsule()
                    .fill(OW.orbGradient)
                    .frame(width: max(3, geo.size.width * CGFloat(min(controller.amplitude * 2.5, 1))))
            }
        }
        .frame(height: 8)
        .animation(.linear(duration: 0.05), value: controller.amplitude)
    }

    private func transcript(label: String, text: String, mono: Bool, faint: Bool) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            MonoLabel(text: label, color: OW.textDim, size: 9, tracking: 1.4)
            Text(text.isEmpty ? "—" : text)
                .font(mono ? OW.mono(13) : OW.ui(14))
                .foregroundStyle(faint ? OW.textFaint : OW.text)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .topLeading)
        }
    }
}

// MARK: - Brand button styles

/// Solid coral primary button (the design's main CTA).
struct OWPrimaryButtonStyle: ButtonStyle {
    var large: Bool = false
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(OW.ui(large ? 15 : 13, weight: .semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, large ? 18 : 14)
            .padding(.vertical, large ? 11 : 7)
            .background(
                (configuration.isPressed ? OW.coralDeep : OW.coral)
                    .opacity(isEnabled ? 1 : 0.4),
                in: RoundedRectangle(cornerRadius: OW.rPill)
            )
            .contentShape(RoundedRectangle(cornerRadius: OW.rPill))
    }
}

/// Outlined "paper" secondary button.
struct OWSecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(OW.ui(13, weight: .medium))
            .foregroundStyle(OW.text.opacity(isEnabled ? 1 : 0.4))
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .background(
                (configuration.isPressed ? OW.chip : OW.card),
                in: RoundedRectangle(cornerRadius: OW.rPill)
            )
            .overlay(RoundedRectangle(cornerRadius: OW.rPill).strokeBorder(OW.border, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: OW.rPill))
    }
}

/// Text-only ghost button (Settings / Quit).
struct OWGhostButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(OW.ui(12, weight: .medium))
            .foregroundStyle(configuration.isPressed ? OW.coralDeep : OW.textDim)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
    }
}
