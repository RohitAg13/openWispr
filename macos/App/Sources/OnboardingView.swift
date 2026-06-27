import AVFoundation
import SwiftUI

/// First-run guided setup. An LSUIElement app has no Dock icon and drives other apps' text
/// fields, so a new user has two non-obvious gates (Microphone + Accessibility) and a choice
/// of dictation engine before anything works. This walks them through it in the brand's
/// "paper" style, then hands off to the main window.
///
/// Shown by `OnboardingWindowController` at launch when `!settings.hasCompletedOnboarding`.
/// `onFinish` is called once the user completes (or skips to the end); it persists the flag
/// and opens the main window.
struct OnboardingView: View {
    @ObservedObject var settings: AppSettings
    var onFinish: () -> Void

    enum Step: Int, CaseIterable {
        case welcome, microphone, accessibility, engine, ready
    }

    @State private var step: Step = .welcome

    // Live permission state (Accessibility can't be observed, so we poll it on a timer).
    @State private var micGrant: Grant = .unknown
    @State private var axTrusted: Bool = TextInserter.isTrusted
    private let axPollTimer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    enum Grant { case unknown, granted, denied }

    var body: some View {
        VStack(spacing: 0) {
            progressHeader
            Divider().overlay(OW.divider)

            // The step body, in a fixed-height area so the footer doesn't jump between steps.
            Group {
                switch step {
                case .welcome:       welcomeStep
                case .microphone:    microphoneStep
                case .accessibility: accessibilityStep
                case .engine:        engineStep
                case .ready:         readyStep
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.horizontal, 44)

            Divider().overlay(OW.divider)
            footer
        }
        .frame(width: 640, height: 560)
        .background(OW.bg)
        .onAppear { refreshMicStatus() }
        .onReceive(axPollTimer) { _ in axTrusted = TextInserter.isTrusted }
    }

    // MARK: - Header

    private var progressHeader: some View {
        HStack(spacing: 8) {
            ForEach(Step.allCases, id: \.self) { s in
                Capsule()
                    .fill(s.rawValue <= step.rawValue ? AnyShapeStyle(OW.coral) : AnyShapeStyle(OW.border))
                    .frame(width: s == step ? 22 : 7, height: 7)
                    .animation(.easeInOut(duration: 0.2), value: step)
            }
        }
        .frame(maxWidth: .infinity, alignment: .center)
        .padding(.vertical, 18)
    }

    // MARK: - Steps

    private var welcomeStep: some View {
        VStack(spacing: 18) {
            Spacer()
            OWOrb(size: 92, breathing: true)
            VStack(spacing: 10) {
                Text("Welcome to OpenWispr")
                    .font(OW.ui(26, weight: .semibold))
                    .foregroundStyle(OW.text)
                Text("Press a key, speak, and your words appear as clean text in any app —\nall on your Mac. Nothing is sent to the cloud.")
                    .font(OW.ui(14))
                    .foregroundStyle(OW.textDim)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
            }
            Spacer()
        }
    }

    private var microphoneStep: some View {
        stepScaffold(
            icon: "mic.fill",
            title: "Allow the microphone",
            subtitle: "OpenWispr needs your microphone to hear you. Audio is transcribed on-device and never leaves your Mac."
        ) {
            switch micGrant {
            case .granted:
                statusPill(ok: true, text: "Microphone access granted")
            case .denied:
                VStack(spacing: 10) {
                    statusPill(ok: false, text: "Access was denied")
                    Button("Open System Settings") {
                        openPrivacyPane("Privacy_Microphone")
                    }
                    .buttonStyle(OWSecondaryButtonStyle())
                }
            case .unknown:
                Button("Allow Microphone") {
                    Task {
                        let ok = await AppleSpeechSTT.requestMicrophoneAccess()
                        micGrant = ok ? .granted : .denied
                    }
                }
                .buttonStyle(OWPrimaryButtonStyle(large: true))
            }
        }
    }

    private var accessibilityStep: some View {
        stepScaffold(
            icon: "keyboard.fill",
            title: "Enable auto-insert",
            subtitle: "To type cleaned text straight into the app you're using, OpenWispr needs Accessibility access. Without it, dictations are copied to the clipboard instead."
        ) {
            if axTrusted {
                statusPill(ok: true, text: "Accessibility access granted")
            } else {
                VStack(spacing: 12) {
                    Button("Open Accessibility Settings") {
                        TextInserter.requestAccess()
                        openPrivacyPane("Privacy_Accessibility")
                    }
                    .buttonStyle(OWPrimaryButtonStyle(large: true))
                    Text("Toggle OpenWispr on in the list, then come back here.")
                        .font(OW.ui(12))
                        .foregroundStyle(OW.textMuted)
                }
            }
        }
    }

    private var engineStep: some View {
        stepScaffold(
            icon: "waveform",
            title: "Choose a dictation engine",
            subtitle: "Apple Speech works instantly. Whisper runs a small model fully offline for higher accuracy — it downloads once."
        ) {
            VStack(spacing: 16) {
                OWSegmented(
                    selection: $settings.sttProvider,
                    options: [(.appleSpeech, "Apple Speech"), (.whisper, "Whisper (offline)")]
                )
                .frame(width: 320)

                if settings.sttProvider == .whisper {
                    WhisperModelDownload(settings: settings)
                } else {
                    Text("No download needed — you're ready to go.")
                        .font(OW.ui(12))
                        .foregroundStyle(OW.textMuted)
                }
            }
        }
    }

    private var readyStep: some View {
        VStack(spacing: 20) {
            Spacer()
            OWOrb(size: 76, breathing: true)
            VStack(spacing: 10) {
                Text("You're all set")
                    .font(OW.ui(24, weight: .semibold))
                    .foregroundStyle(OW.text)
                Text("Press your shortcut anywhere, speak, then press it again (or just pause).")
                    .font(OW.ui(14))
                    .foregroundStyle(OW.textDim)
                    .multilineTextAlignment(.center)
            }

            // The shortcut, shown as a key cap.
            Text(settings.hotKeyDisplay)
                .font(OW.mono(20, weight: .semibold))
                .foregroundStyle(OW.text)
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .background(OW.card, in: RoundedRectangle(cornerRadius: OW.rChip))
                .overlay(RoundedRectangle(cornerRadius: OW.rChip).strokeBorder(OW.border, lineWidth: 1))

            Text("Tip: turn on AI polish and personalize your vocabulary in Settings.")
                .font(OW.ui(12))
                .foregroundStyle(OW.textMuted)
                .multilineTextAlignment(.center)
            Spacer()
        }
    }

    // MARK: - Footer

    private var footer: some View {
        HStack {
            if step != .welcome {
                Button("Back") { advance(-1) }
                    .buttonStyle(OWGhostButtonStyle())
            }
            Spacer()
            if step != .ready {
                Button("Skip setup") { onFinish() }
                    .buttonStyle(OWGhostButtonStyle())
            }
            Button(primaryTitle) {
                if step == .ready { onFinish() } else { advance(1) }
            }
            .buttonStyle(OWPrimaryButtonStyle())
            .disabled(!canAdvance)
        }
        .padding(.horizontal, 28)
        .padding(.vertical, 16)
    }

    private var primaryTitle: String {
        switch step {
        case .welcome: return "Get started"
        case .ready:   return "Start dictating"
        default:       return "Continue"
        }
    }

    /// The microphone step is the one hard gate — you can't dictate without it. Everything else
    /// is skippable (Accessibility falls back to clipboard; the engine has an instant default).
    private var canAdvance: Bool {
        step != .microphone || micGrant == .granted
    }

    // MARK: - Helpers

    private func advance(_ delta: Int) {
        let next = step.rawValue + delta
        guard let s = Step(rawValue: next) else { return }
        withAnimation(.easeInOut(duration: 0.18)) { step = s }
    }

    private func refreshMicStatus() {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:   micGrant = .granted
        case .denied, .restricted: micGrant = .denied
        default:            micGrant = .unknown
        }
    }

    private func openPrivacyPane(_ anchor: String) {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?\(anchor)") {
            NSWorkspace.shared.open(url)
        }
    }

    /// Shared scaffold for the permission/engine steps: icon badge, title, subtitle, then the
    /// step's interactive content.
    @ViewBuilder
    private func stepScaffold<Content: View>(
        icon: String, title: String, subtitle: String, @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(spacing: 18) {
            Spacer()
            ZStack {
                Circle().fill(OW.coralPill.opacity(0.5)).frame(width: 64, height: 64)
                Image(systemName: icon)
                    .font(.system(size: 26, weight: .medium))
                    .foregroundStyle(OW.coralDeep)
            }
            VStack(spacing: 10) {
                Text(title)
                    .font(OW.ui(22, weight: .semibold))
                    .foregroundStyle(OW.text)
                Text(subtitle)
                    .font(OW.ui(14))
                    .foregroundStyle(OW.textDim)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            content()
                .padding(.top, 6)
            Spacer()
        }
    }

    private func statusPill(ok: Bool, text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                .foregroundStyle(ok ? OW.success : OW.danger)
            Text(text)
                .font(OW.ui(13, weight: .medium))
                .foregroundStyle(OW.text)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .background(OW.card, in: RoundedRectangle(cornerRadius: OW.rPill))
        .overlay(RoundedRectangle(cornerRadius: OW.rPill).strokeBorder(OW.border, lineWidth: 1))
    }
}

/// The Whisper model picker + one-tap download used inside the onboarding engine step. Reads
/// live download progress from `WhisperModelManager`.
private struct WhisperModelDownload: View {
    @ObservedObject var settings: AppSettings
    @ObservedObject private var manager = WhisperModelManager.shared

    var body: some View {
        VStack(spacing: 12) {
            OWMenuPicker(
                selection: $settings.whisperModel,
                options: WhisperModel.allCases.map { ($0, "\($0.label) · \($0.approxSize)") }
            )

            let model = settings.whisperModel
            if manager.isDownloaded(model) {
                HStack(spacing: 7) {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(OW.success)
                    Text("Downloaded — ready offline").font(OW.ui(12, weight: .medium)).foregroundStyle(OW.text)
                }
            } else if manager.downloadingModel == model {
                VStack(spacing: 6) {
                    ProgressView(value: manager.downloadProgress ?? 0)
                        .frame(width: 240)
                        .tint(OW.coral)
                    Text("Downloading… \(Int((manager.downloadProgress ?? 0) * 100))%")
                        .font(OW.mono(11)).foregroundStyle(OW.textDim)
                }
            } else {
                Button("Download \(model.label)") {
                    Task { await manager.download(model) }
                }
                .buttonStyle(OWPrimaryButtonStyle())
                if let err = manager.lastError {
                    Text(err).font(OW.ui(11)).foregroundStyle(OW.danger).lineLimit(2)
                }
            }
        }
        // Recompute when a download finishes (manager bumps `revision`).
        .id(manager.revision)
    }
}
