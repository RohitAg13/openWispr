import AVFoundation
import SwiftUI

/// First-run guided setup, redesigned to the "OpenWispr Mac Onboarding" flow: a branded warm
/// gradient window holding a centered "paper" card that steps the user through the two
/// non-obvious macOS gates (Microphone + Accessibility), an on-device voice model download, the
/// global-shortcut trigger, a real first dictation, and a recap.
///
/// An LSUIElement app has no Dock icon and drives other apps' text fields, so a new user can't
/// dictate until these are in place. The flow is on-device only — no cloud path, nothing leaves
/// the Mac.
///
/// Shown by `OnboardingWindowController` at launch when `!settings.hasCompletedOnboarding`, and
/// re-launchable from Settings ▸ General. `onFinish` persists the flag and opens the main window.
struct OnboardingView: View {
    @ObservedObject var settings: AppSettings
    var onFinish: () -> Void

    enum Step: Int, CaseIterable {
        case welcome, microphone, voice, accessibility, shortcut, dictate, done
    }

    @State private var step: Step = .welcome

    // Live permission state (polled — none are KVO-observable).
    @State private var micGrant: Grant = .unknown
    @State private var axTrusted: Bool = MacPermissions.accessibilityGranted
    @State private var inputMonitoring: MacPermissions.Access = MacPermissions.inputMonitoring
    private let pollTimer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    // The real end-to-end dictation flow, reused for the "try it once" step.
    @StateObject private var dictation = DictationController()

    enum Grant { case unknown, granted, denied }

    var body: some View {
        ZStack {
            OnboardingBackground()

            // Centered "paper" card holding the active step.
            VStack(spacing: 0) {
                cardTopBar
                Divider().overlay(OW.divider)

                Group {
                    switch step {
                    case .welcome:       welcomeStep
                    case .microphone:    microphoneStep
                    case .voice:         voiceStep
                    case .accessibility: accessibilityStep
                    case .shortcut:      shortcutStep
                    case .dictate:       dictateStep
                    case .done:          doneStep
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.horizontal, 40)
            }
            .frame(width: 600, height: 600)
            .background(OW.bg, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(OW.border, lineWidth: 1))
            .shadow(color: .black.opacity(0.35), radius: 40, x: 0, y: 24)
        }
        .frame(width: 720, height: 720)
        .onAppear {
            refreshMicStatus()
            dictation.refreshAccessibility()
        }
        .onReceive(pollTimer) { _ in
            axTrusted = MacPermissions.accessibilityGranted
            inputMonitoring = MacPermissions.inputMonitoring
        }
    }

    // MARK: - Card top bar (progress + skip)

    private var cardTopBar: some View {
        ZStack {
            if step != .welcome && step != .done {
                GeometryReader { _ in
                    ZStack(alignment: .leading) {
                        Capsule().fill(OW.track).frame(width: 200, height: 5)
                        Capsule().fill(OW.coral).frame(width: 200 * progressFraction, height: 5)
                    }
                    .frame(maxWidth: .infinity, alignment: .center)
                }
                .frame(height: 5)
            }
            HStack {
                Spacer()
                if canSkip {
                    Button("Skip") { advance(1) }
                        .buttonStyle(OWGhostButtonStyle())
                }
            }
        }
        .frame(height: 42)
        .padding(.horizontal, 16)
    }

    private var progressFraction: CGFloat {
        let total = CGFloat(Step.allCases.count - 1)
        return min(1, max(0, CGFloat(step.rawValue) / total))
    }

    /// Accessibility, shortcut, and the first-dictation step are all skippable.
    private var canSkip: Bool {
        step == .accessibility || step == .shortcut || step == .dictate
    }

    // MARK: - 0 · Welcome

    private var welcomeStep: some View {
        VStack(spacing: 0) {
            Spacer()
            OWOrb(size: 104, breathing: true)
                .padding(.bottom, 28)
            Text("Talk to your Mac.\nNothing leaves it.")
                .font(OW.ui(30, weight: .bold))
                .foregroundStyle(OW.text)
                .multilineTextAlignment(.center)
                .lineSpacing(2)
                .padding(.bottom, 14)
            Text("OpenWispr turns speech into clean text in any app — transcribed and polished entirely on this Mac. No cloud, no account.")
                .font(OW.ui(15))
                .foregroundStyle(OW.textDim)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .frame(maxWidth: 380)
                .padding(.bottom, 30)
            Button("Get started") { advance(1) }
                .buttonStyle(OWPrimaryButtonStyle(large: true))
            MonoLabel(text: "100% on-device · open source", color: OW.textMuted, size: 10, tracking: 1.4)
                .padding(.top, 22)
            Spacer()
        }
    }

    // MARK: - 1 · Microphone

    private var microphoneStep: some View {
        stepScaffold(
            icon: "mic.fill",
            title: "Let OpenWispr hear you",
            subtitle: "macOS will ask once. You can also grant it under System Settings ▸ Privacy & Security ▸ Microphone. Audio is transcribed on-device and never saved."
        ) {
            VStack(spacing: 14) {
                switch micGrant {
                case .granted:
                    OWStatusChip(text: "Microphone allowed", tone: .ok, systemImage: "checkmark")
                case .denied:
                    OWStatusChip(text: "Access was denied", tone: .warn, systemImage: "exclamationmark")
                    Button("Open System Settings") { MacPermissions.requestInputMonitoring(); openMicPane() }
                        .buttonStyle(OWSecondaryButtonStyle())
                case .unknown:
                    EmptyView()
                }
            }
        } footer: {
            switch micGrant {
            case .granted:
                Button("Continue") { advance(1) }.buttonStyle(OWPrimaryButtonStyle(large: true))
            case .denied:
                Button("Continue") { advance(1) }.buttonStyle(OWPrimaryButtonStyle(large: true))
            case .unknown:
                Button("Allow microphone") {
                    Task {
                        let ok = await AppleSpeechSTT.requestMicrophoneAccess()
                        micGrant = ok ? .granted : .denied
                    }
                }
                .buttonStyle(OWPrimaryButtonStyle(large: true))
                Button("Set up later") { advance(1) }.buttonStyle(OWGhostButtonStyle())
            }
        }
    }

    // MARK: - 2 · Voice model

    private var voiceStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer().frame(height: 8)
            Text("Get a voice model")
                .font(OW.ui(24, weight: .bold)).foregroundStyle(OW.text)
            Text("Downloads once, then runs fully offline.")
                .font(OW.ui(14)).foregroundStyle(OW.textDim)
                .padding(.top, 4).padding(.bottom, 18)

            VStack(spacing: 10) {
                // Parakeet (sherpa-onnx) is the fastest on-device engine and the recommended
                // default; Whisper base/tiny are the alternates.
                parakeetRow
                voiceModelRow(.base, tagline: "\(WhisperModel.base.approxSize) · Whisper · accurate")
                voiceModelRow(.tiny, tagline: "\(WhisperModel.tiny.approxSize) · Whisper · fastest")
            }

            VoiceOnboardingDownload(settings: settings)
                .padding(.top, 16)

            Spacer()
            HStack {
                Spacer()
                Button(voiceContinueTitle) { advance(1) }
                    .buttonStyle(OWPrimaryButtonStyle(large: true))
            }
            Spacer().frame(height: 8)
        }
    }

    private var voiceContinueTitle: String {
        let ready = settings.sttProvider == .parakeet
            ? ParakeetModelManager.shared.isDownloaded
            : WhisperModelManager.shared.isDownloaded(settings.whisperModel)
        return ready ? "Continue" : "Skip for now"
    }

    private func voiceModelRow(_ model: WhisperModel, tagline: String) -> some View {
        let selected = settings.whisperModel == model
        return Button {
            settings.sttProvider = .whisper
            settings.whisperModel = model
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(whisperTitle(model))
                        .font(OW.ui(14.5, weight: .semibold)).foregroundStyle(OW.text)
                    Text(tagline)
                        .font(OW.mono(11)).foregroundStyle(OW.textMuted)
                }
                Spacer()
                Circle()
                    .strokeBorder(selected ? OW.coral : OW.border, lineWidth: selected ? 4 : 1.5)
                    .frame(width: 13, height: 13)
            }
            .padding(14)
            .background(selected ? OW.chip : OW.card, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12)
                .strokeBorder(selected ? OW.coral : OW.border, lineWidth: selected ? 1.5 : 1))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    private func whisperTitle(_ model: WhisperModel) -> String {
        switch model {
        case .tiny:  return "Whisper Tiny"
        case .base:  return "Whisper Base"
        case .small: return "Whisper Small"
        }
    }

    private var parakeetRow: some View {
        let selected = settings.sttProvider == .parakeet
        return Button {
            settings.sttProvider = .parakeet
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 7) {
                        Text("Parakeet")
                            .font(OW.ui(14.5, weight: .semibold)).foregroundStyle(OW.text)
                        OWStatusChip(text: "Recommended", tone: .ok)
                    }
                    Text("\(ParakeetModel.approxSize) · fastest · sub-second")
                        .font(OW.mono(11)).foregroundStyle(OW.textMuted)
                }
                Spacer()
                Circle()
                    .strokeBorder(selected ? OW.coral : OW.border, lineWidth: selected ? 4 : 1.5)
                    .frame(width: 13, height: 13)
            }
            .padding(14)
            .background(selected ? OW.chip : OW.card, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12)
                .strokeBorder(selected ? OW.coral : OW.border, lineWidth: selected ? 1.5 : 1))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    // MARK: - 3 · Accessibility

    private var accessibilityStep: some View {
        stepScaffold(
            icon: "keyboard.fill",
            title: axTrusted ? "Auto-insert is on" : "Let it type for you",
            subtitle: axTrusted
                ? "Finished text drops straight into whatever you're typing in."
                : "OpenWispr uses Accessibility for one job: pasting finished text into the app you're in. Enable it under Privacy & Security ▸ Accessibility. It never reads your screen, and nothing leaves your Mac."
        ) {
            if axTrusted {
                OWStatusChip(text: "Accessibility granted", tone: .ok, systemImage: "checkmark")
            } else {
                EmptyView()
            }
        } footer: {
            if axTrusted {
                Button("Continue") { advance(1) }.buttonStyle(OWPrimaryButtonStyle(large: true))
            } else {
                Button("Open System Settings") { MacPermissions.requestAccessibility() }
                    .buttonStyle(OWPrimaryButtonStyle(large: true))
                Button("Skip — paste manually") { advance(1) }.buttonStyle(OWGhostButtonStyle())
            }
        }
    }

    // MARK: - 4 · Shortcut / Input Monitoring

    private var shortcutStep: some View {
        stepScaffold(
            icon: "command",
            title: "Your trigger is the shortcut",
            subtitle: "Press \(settings.hotKeyDisplay) anywhere to start and stop dictation. It works system-wide with no extra permission. Input Monitoring is optional — grant it only if you want the system to recognize the keys everywhere."
        ) {
            VStack(spacing: 12) {
                HStack(spacing: 8) {
                    Text(settings.hotKeyDisplay)
                        .font(OW.mono(18, weight: .semibold)).foregroundStyle(OW.text)
                        .padding(.horizontal, 16).padding(.vertical, 9)
                        .background(OW.card, in: RoundedRectangle(cornerRadius: OW.rChip))
                        .overlay(RoundedRectangle(cornerRadius: OW.rChip).strokeBorder(OW.border, lineWidth: 1))
                }
                HStack(spacing: 8) {
                    Text("Input Monitoring")
                        .font(OW.ui(13, weight: .medium)).foregroundStyle(OW.textDim)
                    inputMonitoringChip
                }
            }
        } footer: {
            if inputMonitoring == .granted {
                Button("Continue") { advance(1) }.buttonStyle(OWPrimaryButtonStyle(large: true))
            } else {
                Button("Continue") { advance(1) }.buttonStyle(OWPrimaryButtonStyle(large: true))
                Button("Open Input Monitoring") {
                    MacPermissions.requestInputMonitoring()
                    MacPermissions.openInputMonitoringPane()
                }
                .buttonStyle(OWGhostButtonStyle())
            }
        }
    }

    private var inputMonitoringChip: some View {
        switch inputMonitoring {
        case .granted: return OWStatusChip(text: "Granted", tone: .ok, systemImage: "checkmark")
        case .denied:  return OWStatusChip(text: "Optional", tone: .neutral)
        case .unknown: return OWStatusChip(text: "Optional", tone: .neutral)
        }
    }

    // MARK: - 5 · First dictation

    private var dictateStep: some View {
        VStack(spacing: 0) {
            Spacer().frame(height: 10)
            Text("Try it once")
                .font(OW.ui(24, weight: .bold)).foregroundStyle(OW.text)
            Text("Click below and read this aloud — watch it land as clean text.")
                .font(OW.ui(14)).foregroundStyle(OW.textDim)
                .multilineTextAlignment(.center)
                .padding(.top, 6).padding(.bottom, 16)

            Text("“um so send it to mark, I mean john, tomorrow at 2 period”")
                .font(OW.ui(14)).italic().foregroundStyle(OW.textFaint)
                .multilineTextAlignment(.center)
                .padding(14)
                .frame(maxWidth: .infinity)
                .background(OW.chip, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
                    .foregroundStyle(OW.border))

            Spacer()
            dictateBody
            Spacer()

            if dictation.phase == .done {
                Button("That's the magic — continue") { advance(1) }
                    .buttonStyle(OWPrimaryButtonStyle(large: true))
            }
            Spacer().frame(height: 10)
        }
    }

    @ViewBuilder
    private var dictateBody: some View {
        switch dictation.phase {
        case .idle, .error:
            VStack(spacing: 14) {
                Button(action: dictation.toggle) {
                    OWOrb(size: 84, breathing: true)
                }
                .buttonStyle(.plain)
                MonoLabel(text: "Click to talk", color: OW.textMuted, size: 11, tracking: 1.6)
            }
        case .listening:
            VStack(spacing: 16) {
                ZStack {
                    Circle().fill(OW.orbGradientBright).frame(width: 84, height: 84)
                        .shadow(color: OW.coral.opacity(0.5), radius: 16, x: 0, y: 8)
                    Image(systemName: "waveform").font(.system(size: 30)).foregroundStyle(.white)
                }
                Button(action: dictation.toggle) {
                    MonoLabel(text: "Listening — click to stop", color: OW.coralDeep, size: 11, tracking: 1.4)
                }
                .buttonStyle(.plain)
            }
        case .transcribing:
            VStack(spacing: 12) {
                ProgressView().controlSize(.large).tint(OW.coral)
                MonoLabel(text: "Working", color: OW.textMuted, size: 11, tracking: 1.6)
            }
        case .done:
            VStack(spacing: 10) {
                MonoLabel(text: "Inserted at your cursor", color: OW.textMuted, size: 9, tracking: 1.2)
                Text(dictation.editableCleaned.isEmpty ? dictation.cleaned : dictation.editableCleaned)
                    .font(OW.ui(17)).foregroundStyle(OW.text)
                    .multilineTextAlignment(.center)
                    .padding(16)
                    .frame(maxWidth: .infinity)
                    .background(OW.card, in: RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(OW.border, lineWidth: 1))
            }
        }
    }

    // MARK: - 6 · Done

    private var doneStep: some View {
        VStack(spacing: 0) {
            Spacer()
            OWOrb(size: 88, breathing: true).padding(.bottom, 20)
            Text("You're all set.")
                .font(OW.ui(28, weight: .bold)).foregroundStyle(OW.text)
                .padding(.bottom, 11)
            Text("Press \(settings.hotKeyDisplay) in any app and start talking. Everything stays on this Mac.")
                .font(OW.ui(14.5)).foregroundStyle(OW.textDim)
                .multilineTextAlignment(.center).lineSpacing(3)
                .frame(maxWidth: 340)
                .padding(.bottom, 22)

            VStack(spacing: 0) {
                recapRow("Microphone", ok: micGrant == .granted, alt: nil)
                Divider().overlay(OW.divider)
                recapRow("On-device voice model",
                         ok: WhisperModelManager.shared.isDownloaded(settings.whisperModel), alt: nil)
                Divider().overlay(OW.divider)
                recapRow("Auto-insert", ok: axTrusted, alt: axTrusted ? nil : "Clipboard")
                Divider().overlay(OW.divider)
                recapRow("\(settings.hotKeyDisplay) shortcut", ok: true, alt: nil)
            }
            .background(OW.card, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(OW.border, lineWidth: 1))
            .frame(maxWidth: 400)
            .padding(.bottom, 22)

            HStack(spacing: 16) {
                Button("Replay") { withAnimation { step = .welcome } }
                    .buttonStyle(OWGhostButtonStyle())
                Button("Start using OpenWispr") { onFinish() }
                    .buttonStyle(OWPrimaryButtonStyle(large: true))
            }
            Spacer()
        }
    }

    private func recapRow(_ label: String, ok: Bool, alt: String?) -> some View {
        HStack(spacing: 11) {
            Image(systemName: ok || alt != nil ? "checkmark.circle.fill" : "circle")
                .font(.system(size: 16))
                .foregroundStyle(ok ? OW.success : (alt != nil ? OW.coralDeep : OW.textMuted))
            Text(label).font(OW.ui(14)).foregroundStyle(OW.text)
            Spacer()
            Text(ok ? "On" : (alt ?? "Later"))
                .font(OW.mono(11))
                .foregroundStyle(ok ? OW.success : (alt != nil ? OW.coralDeep : OW.textMuted))
        }
        .padding(.horizontal, 15).padding(.vertical, 12)
    }

    // MARK: - Step scaffold

    /// Shared centered scaffold for the permission/trigger steps: icon badge, title, subtitle,
    /// the step's status content, then a footer of action buttons pinned near the bottom.
    @ViewBuilder
    private func stepScaffold<Content: View, Footer: View>(
        icon: String, title: String, subtitle: String,
        @ViewBuilder content: () -> Content,
        @ViewBuilder footer: () -> Footer
    ) -> some View {
        VStack(spacing: 0) {
            Spacer().frame(height: 14)
            VStack(alignment: .leading, spacing: 20) {
                ZStack {
                    RoundedRectangle(cornerRadius: 18).fill(OW.coralPill.opacity(0.5))
                        .frame(width: 66, height: 66)
                    Image(systemName: icon)
                        .font(.system(size: 28, weight: .medium))
                        .foregroundStyle(OW.coralDeep)
                }
                VStack(alignment: .leading, spacing: 11) {
                    Text(title)
                        .font(OW.ui(24, weight: .bold)).foregroundStyle(OW.text)
                    Text(subtitle)
                        .font(OW.ui(14.5)).foregroundStyle(OW.textDim)
                        .lineSpacing(3)
                        .fixedSize(horizontal: false, vertical: true)
                }
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Spacer()
            HStack(spacing: 16) {
                footer()
                Spacer()
            }
            Spacer().frame(height: 14)
        }
    }

    // MARK: - Navigation + helpers

    private func advance(_ delta: Int) {
        let next = step.rawValue + delta
        guard let s = Step(rawValue: next) else {
            if next > Step.done.rawValue { onFinish() }
            return
        }
        withAnimation(.easeInOut(duration: 0.2)) { step = s }
    }

    private func refreshMicStatus() {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:          micGrant = .granted
        case .denied, .restricted: micGrant = .denied
        default:                   micGrant = .unknown
        }
    }

    private func openMicPane() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone") {
            NSWorkspace.shared.open(url)
        }
    }
}

// MARK: - Branded gradient backdrop

/// The warm amber→coral→deep-brown gradient window backdrop from the onboarding design, with a
/// soft top glow. Translated from the design's OKLCH gradient stops.
private struct OnboardingBackground: View {
    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(hex: 0xD9A05F), // oklch(0.74 0.10 70) warm amber
                    Color(hex: 0xCB7A52), // oklch(0.66 0.13 44) coral
                    Color(hex: 0x8E4736), // oklch(0.52 0.12 24) deep red-brown
                    Color(hex: 0x523026), // oklch(0.36 0.08 28) dark brown
                ],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
            RadialGradient(
                colors: [Color.white.opacity(0.28), .clear],
                center: .top, startRadius: 0, endRadius: 420
            )
        }
        .ignoresSafeArea()
    }
}

/// Compact Whisper model download control used inside the onboarding voice step. Reads live
/// download state from `WhisperModelManager` for the currently selected model.
/// The first-run model download affordance. Adapts to the selected engine: Parakeet (one fixed
/// file set) or the chosen Whisper model.
private struct VoiceOnboardingDownload: View {
    @ObservedObject var settings: AppSettings
    @ObservedObject private var whisper = WhisperModelManager.shared
    @ObservedObject private var parakeet = ParakeetModelManager.shared

    var body: some View {
        if settings.sttProvider == .parakeet {
            parakeetBody.id(parakeet.revision)
        } else {
            whisperBody.id(whisper.revision)
        }
    }

    @ViewBuilder
    private var parakeetBody: some View {
        if parakeet.isDownloaded {
            OWStatusChip(text: "Model ready · works offline", tone: .ok, systemImage: "checkmark")
        } else if parakeet.isDownloading {
            downloadingBar(progress: parakeet.downloadProgress ?? 0)
        } else {
            downloadButton(error: parakeet.lastError) { Task { await parakeet.download() } }
        }
    }

    @ViewBuilder
    private var whisperBody: some View {
        let model = settings.whisperModel
        if whisper.isDownloaded(model) {
            OWStatusChip(text: "Model ready · works offline", tone: .ok, systemImage: "checkmark")
        } else if whisper.downloadingModel == model {
            downloadingBar(progress: whisper.downloadProgress ?? 0)
        } else {
            downloadButton(error: whisper.lastError) { Task { await whisper.download(model) } }
        }
    }

    private func downloadingBar(progress: Double) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack {
                Text("Downloading…").font(OW.ui(13, weight: .semibold)).foregroundStyle(OW.textDim)
                Spacer()
                Text("\(Int(progress * 100))%").font(OW.mono(12)).foregroundStyle(OW.textMuted)
            }
            ProgressView(value: progress).tint(OW.coral)
        }
    }

    private func downloadButton(error: String?, action: @escaping () -> Void) -> some View {
        HStack {
            Button("Download & continue", action: action)
                .buttonStyle(OWPrimaryButtonStyle())
            if let error {
                Text(error).font(OW.ui(11)).foregroundStyle(OW.danger).lineLimit(2)
            }
            Spacer()
        }
    }
}
