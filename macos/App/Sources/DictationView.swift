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

    // Inject the energy VAD + default VAD config, mirroring the capture pipeline.
    private let audio = AudioCapture(vad: EnergyVAD(), config: VADConfig())
    private let stt = AppleSpeechSTT()

    /// Polls `audio.amplitude` for the live level bar while listening.
    private var levelTimer: Timer?

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

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "mic.fill").foregroundStyle(.tint)
                Text("OpenWispr").font(.headline)
                Spacer()
                Text("v0.1 · dictation").font(.caption2).foregroundStyle(.secondary)
            }

            controlRow

            statusArea

            Divider()

            HStack {
                Button("Copy") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(controller.cleaned, forType: .string)
                }
                .disabled(controller.cleaned.isEmpty)
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
        }
        .padding(14)
        .frame(width: 440)
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
