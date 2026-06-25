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

    // Inject the energy VAD + default VAD config, mirroring the capture pipeline.
    private let audio = AudioCapture(vad: EnergyVAD(), config: VADConfig())
    private let stt = AppleSpeechSTT()

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
        didInsert = false
        Task {
            // Request both permissions before the first capture.
            let mic = await AppleSpeechSTT.requestMicrophoneAccess()
            let speech = await AppleSpeechSTT.requestAuthorization()
            guard mic && speech else {
                phase = .error(
                    "Microphone and Speech Recognition access are needed. Enable them in "
                        + "System Settings ▸ Privacy & Security, then try again."
                )
                return
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
        Task {
            do {
                let rawText = try await stt.transcribe(samples, sampleRate: 16000)
                // Deterministic cleanup; vocab correction is empty for now.
                let cleanedText = TextProcessor.process(rawText)
                raw = rawText
                cleaned = cleanedText
                phase = .done
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
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "mic.fill").foregroundStyle(.tint)
                Text("OpenWispr").font(.headline)
                Spacer()
                Image(systemName: controller.canInsert ? "checkmark.shield.fill" : "shield.slash")
                    .font(.caption2)
                    .foregroundStyle(controller.canInsert ? .green : .secondary)
                    .help(controller.canInsert
                        ? "Accessibility granted — auto-insert enabled"
                        : "Accessibility not granted")
                Text("v0.1 · dictation").font(.caption2).foregroundStyle(.secondary)
            }

            HStack(spacing: 6) {
                Image(systemName: "command").font(.caption2)
                Text("\(AppSettings.shared.hotKeyDisplay) to dictate anywhere")
                Text("·")
                Text(controller.canInsert ? "auto-insert on" : "Accessibility off")
                    .foregroundStyle(controller.canInsert ? .green : .secondary)
            }
            .font(.caption2)
            .foregroundStyle(.secondary)

            controlRow

            statusArea

            Divider()

            actionRow
        }
        .padding(14)
        .frame(width: 440)
        .onAppear { controller.refreshAccessibility() }
    }

    /// Insert / Copy / Quit. The primary action depends on whether Accessibility is granted.
    @ViewBuilder
    private var actionRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            if controller.didInsert {
                Text("Inserted ✓").font(.caption.bold()).foregroundStyle(.green)
            } else if !controller.canInsert {
                Text(
                    "Grant OpenWispr access under System Settings ▸ Privacy & Security ▸ "
                        + "Accessibility, then try Insert."
                )
                .font(.caption2).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            }

            HStack {
                if controller.canInsert {
                    Button {
                        controller.insertCleaned()
                    } label: {
                        Label("Insert", systemImage: "text.insert")
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(controller.cleaned.isEmpty)
                } else {
                    Button {
                        controller.requestAccessibility()
                    } label: {
                        Label("Enable auto-insert", systemImage: "lock.shield")
                    }
                    .buttonStyle(.borderedProminent)
                }

                Button("Copy") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(controller.cleaned, forType: .string)
                }
                .disabled(controller.cleaned.isEmpty)

                Spacer()
                Button("Settings…") {
                    openWindow(id: "settings")
                    NSApp.activate(ignoringOtherApps: true)
                }
                Button("Quit") { NSApp.terminate(nil) }
            }
        }
    }

    private var controlRow: some View {
        HStack(spacing: 10) {
            Button(action: controller.toggle) {
                Label(
                    controller.isListening ? "Stop" : "Listen",
                    systemImage: controller.isListening ? "stop.fill" : "mic.fill"
                )
                .frame(maxWidth: .infinity)
            }
            .controlSize(.large)
            .disabled(controller.phase == .transcribing)
        }
    }

    @ViewBuilder
    private var statusArea: some View {
        switch controller.phase {
        case .idle:
            Text("Press Listen and start speaking. It stops automatically when you pause.")
                .font(.caption).foregroundStyle(.secondary)

        case .listening:
            VStack(alignment: .leading, spacing: 6) {
                Text("Listening…").font(.caption.bold()).foregroundStyle(.secondary)
                levelBar
            }

        case .transcribing:
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Transcribing…").font(.caption).foregroundStyle(.secondary)
            }

        case .done:
            VStack(alignment: .leading, spacing: 10) {
                transcript(label: "RAW", text: controller.raw)
                transcript(label: "CLEANED", text: controller.cleaned)
            }

        case .error(let message):
            Text(message).font(.caption).foregroundStyle(.red)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var levelBar: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 4).fill(.quaternary)
                RoundedRectangle(cornerRadius: 4)
                    .fill(.tint)
                    .frame(width: max(2, geo.size.width * CGFloat(min(controller.amplitude * 2.5, 1))))
            }
        }
        .frame(height: 8)
        .animation(.linear(duration: 0.05), value: controller.amplitude)
    }

    private func transcript(label: String, text: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.caption2.bold()).foregroundStyle(.secondary)
            Text(text.isEmpty ? "—" : text)
                .font(label == "RAW" ? .system(.body, design: .monospaced) : .body)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .topLeading)
        }
    }
}
