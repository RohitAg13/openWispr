import SwiftUI

/// The OpenWispr settings surface, redesigned to the "OpenWispr Mac Settings" sidebar layout: a
/// compact left nav (Voice · Cleanup & Polish · Trigger & Shortcut · Privacy · Personalization ·
/// General) and a scrolling content pane of "paper" cards. Binds to the shared `AppSettings` and
/// the model managers; every change persists immediately and the coordinator (observing the same
/// store) applies it live.
struct SettingsView: View {
    @ObservedObject var settings: AppSettings
    @ObservedObject private var models = WhisperModelManager.shared
    @ObservedObject private var parakeet = ParakeetModelManager.shared
    @ObservedObject private var llmModels = LlmModelManager.shared
    @ObservedObject private var history = DictationHistoryStore.shared
    @ObservedObject private var corpus = CorpusStore.shared
    @ObservedObject private var vocab = VocabStore.shared

    @State private var pane: Pane = .voice
    @State private var showAdvancedPolish = false

    // Launch-at-login is owned by the system (`SMAppService`), not UserDefaults — seed from it.
    @State private var launchAtLogin = LaunchAtLogin.isEnabled

    // Live permission state for the Trigger section — polled (none are KVO-observable).
    @State private var axTrusted = MacPermissions.accessibilityGranted
    @State private var inputMonitoring = MacPermissions.inputMonitoring
    private let pollTimer = Timer.publish(every: 1.5, on: .main, in: .common).autoconnect()

    /// Sidebar panes.
    enum Pane: String, CaseIterable, Identifiable {
        case voice, cleanup, trigger, privacy, personalization, general
        var id: String { rawValue }

        var title: String {
            switch self {
            case .voice:           return "Voice"
            case .cleanup:         return "Cleanup & Polish"
            case .trigger:         return "Trigger & Shortcut"
            case .privacy:         return "Privacy"
            case .personalization: return "Personalization"
            case .general:         return "General"
            }
        }
        var icon: String {
            switch self {
            case .voice:           return "waveform"
            case .cleanup:         return "sparkles"
            case .trigger:         return "command"
            case .privacy:         return "lock.shield"
            case .personalization: return "person.text.rectangle"
            case .general:         return "gearshape"
            }
        }
    }

    var body: some View {
        HStack(spacing: 0) {
            sidebar
            Rectangle().fill(OW.border).frame(width: 1)
            content
        }
        .frame(minWidth: 640, minHeight: 560)
        .background(OW.bg)
        .onReceive(pollTimer) { _ in
            axTrusted = MacPermissions.accessibilityGranted
            inputMonitoring = MacPermissions.inputMonitoring
        }
    }

    // MARK: - Sidebar

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                OWOrb(size: 28, breathing: false)
                Text("Settings")
                    .font(OW.ui(18, weight: .bold))
                    .foregroundStyle(OW.text)
            }
            .padding(.horizontal, 14)
            .padding(.top, 18)
            .padding(.bottom, 16)

            VStack(spacing: 3) {
                ForEach(Pane.allCases) { item in
                    navRow(item)
                }
            }
            .padding(.horizontal, 9)

            Spacer()

            VStack(alignment: .leading, spacing: 8) {
                Button("Replay setup…") { OpenWisprWindows.replayOnboarding?() }
                    .buttonStyle(OWGhostButtonStyle())
                MonoLabel(text: "OpenWispr \(Self.appVersion)", color: OW.textMuted, size: 9, tracking: 1.0)
                    .padding(.leading, 4)
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 14)
        }
        .frame(width: 198)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(OW.bgSunk)
    }

    private func navRow(_ item: Pane) -> some View {
        let isSelected = pane == item
        return Button {
            pane = item
        } label: {
            HStack(spacing: 10) {
                Image(systemName: item.icon)
                    .font(.system(size: 12))
                    .frame(width: 18)
                    .foregroundStyle(isSelected ? OW.coralDeep : OW.textDim)
                Text(item.title)
                    .font(OW.ui(13.5, weight: isSelected ? .semibold : .medium))
                    .foregroundStyle(isSelected ? OW.text : OW.textDim)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 11)
            .padding(.vertical, 8)
            .background(
                isSelected ? AnyShapeStyle(OW.coralPill.opacity(0.6)) : AnyShapeStyle(Color.clear),
                in: RoundedRectangle(cornerRadius: OW.rChip)
            )
            .contentShape(RoundedRectangle(cornerRadius: OW.rChip))
        }
        .buttonStyle(.plain)
    }

    // MARK: - Content pane

    private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                Text(pane.title)
                    .font(OW.ui(24, weight: .bold))
                    .foregroundStyle(OW.text)
                    .padding(.bottom, 2)

                switch pane {
                case .voice:           voiceSection
                case .cleanup:         cleanupSection
                case .trigger:         triggerSection
                case .privacy:         privacySection
                case .personalization: personalizationSection
                case .general:         generalSection
                }
            }
            .padding(.horizontal, 30)
            .padding(.vertical, 28)
            // Cap the reading width, then center the column so a wide window doesn't leave the
            // content hugging the left with a large empty void on the right.
            .frame(maxWidth: 680, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .background(OW.bg)
    }

    // MARK: - 1 · Voice

    @ViewBuilder
    private var voiceSection: some View {
        group(header: "Engine") {
            VStack(spacing: 8) {
                // On-device Parakeet (sherpa-onnx) is the fastest engine — sub-second and as
                // accurate as whisper-small; Whisper is the alternate on-device recognizer and
                // Apple Speech the built-in one. All three are selectable.
                engineRow(.parakeet, title: "On-device (Parakeet)",
                          subtitle: "Private, offline. Fastest — sub-second, accurate.", recommended: true)
                engineRow(.whisper, title: "On-device (Whisper)",
                          subtitle: "Private, offline. Runs entirely on this Mac.", recommended: false)
                engineRow(.appleSpeech, title: "Apple Speech",
                          subtitle: "The built-in macOS recognizer. No download.", recommended: false)
                // Cloud engines from the design are not implemented on macOS yet (the app is
                // on-device first) — shown disabled so the roadmap is visible.
                comingSoonEngineRow(title: "Groq", subtitle: "Cloud — fastest hosted Whisper")
                comingSoonEngineRow(title: "OpenAI", subtitle: "Cloud — gpt-4o-transcribe")
                comingSoonEngineRow(title: "Custom endpoint", subtitle: "Bring your own API")
            }
            .padding(14)
        }

        switch settings.sttProvider {
        case .parakeet:
            group(header: "On-device model") {
                VStack(spacing: 8) {
                    parakeetModelRow
                }
                .padding(14)
            }
            if let error = parakeet.lastError {
                infoNote(error, tone: .danger)
            }
            infoNote("Parakeet runs fully on-device and offline. The model downloads once, then stays cached.")
        case .whisper:
            group(header: "On-device models") {
                VStack(spacing: 8) {
                    ForEach(WhisperModel.allCases) { model in
                        whisperModelRow(model)
                    }
                }
                .padding(14)
            }
            if let error = models.lastError {
                infoNote(error, tone: .danger)
            }
            infoNote("Whisper runs fully on-device and offline. Each model downloads once, then stays cached.")
        case .appleSpeech:
            infoNote("Apple Speech uses the macOS recognizer. The first hands-free dictation will ask for Speech Recognition access.")
        }
    }

    private func engineRow(_ provider: STTProvider, title: String, subtitle: String, recommended: Bool) -> some View {
        let selected = settings.sttProvider == provider
        return Button {
            settings.sttProvider = provider
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 7) {
                        Text(title)
                            .font(OW.ui(14, weight: .semibold)).foregroundStyle(OW.text)
                        if recommended {
                            OWStatusChip(text: "Recommended", tone: .ok)
                        }
                    }
                    Text(subtitle)
                        .font(OW.ui(11.5)).foregroundStyle(OW.textMuted)
                }
                Spacer()
                Circle()
                    .strokeBorder(selected ? OW.coral : OW.border, lineWidth: selected ? 4 : 1.5)
                    .frame(width: 13, height: 13)
            }
            .padding(12)
            .background(selected ? OW.chip : OW.card, in: RoundedRectangle(cornerRadius: OW.rChip))
            .overlay(RoundedRectangle(cornerRadius: OW.rChip)
                .strokeBorder(selected ? OW.coral : OW.border, lineWidth: selected ? 1.5 : 1))
            .contentShape(RoundedRectangle(cornerRadius: OW.rChip))
        }
        .buttonStyle(.plain)
    }

    private func comingSoonEngineRow(title: String, subtitle: String) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(OW.ui(14, weight: .semibold)).foregroundStyle(OW.textMuted)
                Text(subtitle).font(OW.ui(11.5)).foregroundStyle(OW.textFaint)
            }
            Spacer()
            OWStatusChip(text: "Coming soon", tone: .neutral)
        }
        .padding(12)
        .background(OW.bgSunk, in: RoundedRectangle(cornerRadius: OW.rChip))
        .overlay(RoundedRectangle(cornerRadius: OW.rChip).strokeBorder(OW.border, lineWidth: 1))
        .opacity(0.7)
    }

    /// A Whisper model row with download / Use / Active states.
    @ViewBuilder
    private func whisperModelRow(_ model: WhisperModel) -> some View {
        let isActive = settings.sttProvider == .whisper && settings.whisperModel == model
        let downloading = models.downloadingModel == model
        let downloaded = models.isDownloaded(model)

        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(model.label).font(OW.ui(14, weight: .semibold)).foregroundStyle(OW.text)
                Text(model.approxSize).font(OW.mono(11)).foregroundStyle(OW.textMuted)
            }
            Spacer()

            if downloading {
                ProgressView(value: models.downloadProgress ?? 0)
                    .tint(OW.coral).frame(width: 90)
                Text("\(Int((models.downloadProgress ?? 0) * 100))%")
                    .font(OW.mono(11)).foregroundStyle(OW.textDim)
                Button("Cancel") { models.cancelDownload() }
                    .buttonStyle(OWSecondaryButtonStyle())
            } else if downloaded {
                if isActive {
                    OWStatusChip(text: "Active", tone: .ok, systemImage: "checkmark")
                } else {
                    Button("Use") {
                        settings.sttProvider = .whisper
                        settings.whisperModel = model
                    }
                    .buttonStyle(OWSecondaryButtonStyle())
                }
                Button {
                    models.delete(model)
                } label: { Image(systemName: "trash") }
                    .buttonStyle(OWSecondaryButtonStyle())
                    .help("Remove download")
            } else {
                Button("Download \(model.approxSize)") {
                    settings.whisperModel = model
                    Task { await models.download(model) }
                }
                .buttonStyle(OWPrimaryButtonStyle())
                .disabled(models.isDownloading)
            }
        }
        .padding(12)
        .background(isActive ? OW.chip : OW.card, in: RoundedRectangle(cornerRadius: OW.rChip))
        .overlay(RoundedRectangle(cornerRadius: OW.rChip)
            .strokeBorder(isActive ? OW.coral : OW.border, lineWidth: isActive ? 1.5 : 1))
    }

    /// The Parakeet model row with download / Active / delete states. Unlike Whisper there's a
    /// single quality tier, so it's one row driving the whole file set.
    @ViewBuilder
    private var parakeetModelRow: some View {
        let isActive = settings.sttProvider == .parakeet && parakeet.isDownloaded
        let downloading = parakeet.isDownloading
        let downloaded = parakeet.isDownloaded

        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text("Parakeet-TDT 0.6B").font(OW.ui(14, weight: .semibold)).foregroundStyle(OW.text)
                Text("\(ParakeetModel.approxSize) · fastest, most accurate")
                    .font(OW.mono(11)).foregroundStyle(OW.textMuted)
            }
            Spacer()

            if downloading {
                ProgressView(value: parakeet.downloadProgress ?? 0)
                    .tint(OW.coral).frame(width: 90)
                Text("\(Int((parakeet.downloadProgress ?? 0) * 100))%")
                    .font(OW.mono(11)).foregroundStyle(OW.textDim)
                Button("Cancel") { parakeet.cancelDownload() }
                    .buttonStyle(OWSecondaryButtonStyle())
            } else if downloaded {
                OWStatusChip(text: "Active", tone: .ok, systemImage: "checkmark")
                Button {
                    parakeet.delete()
                } label: { Image(systemName: "trash") }
                    .buttonStyle(OWSecondaryButtonStyle())
                    .help("Remove download")
            } else {
                Button("Download \(ParakeetModel.approxSize)") {
                    Task { await parakeet.download() }
                }
                .buttonStyle(OWPrimaryButtonStyle())
                .disabled(parakeet.isDownloading)
            }
        }
        .padding(12)
        .background(isActive ? OW.chip : OW.card, in: RoundedRectangle(cornerRadius: OW.rChip))
        .overlay(RoundedRectangle(cornerRadius: OW.rChip)
            .strokeBorder(isActive ? OW.coral : OW.border, lineWidth: isActive ? 1.5 : 1))
    }

    // MARK: - 2 · Cleanup & Polish

    @ViewBuilder
    private var cleanupSection: some View {
        group(header: "Cleanup") {
            VStack(alignment: .leading, spacing: 10) {
                toggleRow("Smart cleanup",
                          subtitle: "Fix capitalization, punctuation, filler words and spoken commands — on-device, no LLM.",
                          isOn: $settings.smartCleanup)
            }
            .padding(14)
        }

        group(header: "AI polish") {
            VStack(alignment: .leading, spacing: 12) {
                OWSegmented(
                    selection: $settings.polishLevel,
                    options: PolishLevel.allCases.map { ($0, $0.shortLabel) }
                )
                Text(settings.polishLevel.blurb)
                    .font(OW.ui(11.5)).foregroundStyle(OW.textMuted)
                    .fixedSize(horizontal: false, vertical: true)

                if settings.polishLevel != .off {
                    Rectangle().fill(OW.divider).frame(height: 1)
                    advancedDisclosure
                }
            }
            .padding(14)
        }

        if settings.polishLevel != .off {
            infoNote("Polish rewrites the transcript with a small on-device model. The model downloads once and runs offline.")
        }
    }

    @ViewBuilder
    private var advancedDisclosure: some View {
        Button {
            withAnimation(.easeInOut(duration: 0.18)) { showAdvancedPolish.toggle() }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: showAdvancedPolish ? "chevron.down" : "chevron.right")
                    .font(.system(size: 10, weight: .semibold)).foregroundStyle(OW.coral)
                MonoLabel(text: "Advanced", color: OW.textDim, size: 10, tracking: 1.4)
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)

        if showAdvancedPolish {
            VStack(alignment: .leading, spacing: 12) {
                // Polish model — the OpenWispr fine-tune is the recommended default and listed top.
                MonoLabel(text: "Polish model", color: OW.textMuted, size: 9, tracking: 1.4)
                VStack(spacing: 8) {
                    ForEach(LlmModel.allCases) { model in
                        polishModelRow(model)
                    }
                }
                if let error = llmModels.lastError {
                    Text(error).font(OW.ui(11)).foregroundStyle(OW.danger)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Rectangle().fill(OW.divider).frame(height: 1)

                // Creativity slider (persisted intent; greedy sampling today — see AppSettings).
                HStack {
                    MonoLabel(text: "Creativity", color: OW.textMuted, size: 9, tracking: 1.4)
                    Spacer()
                    Text(creativityLabel).font(OW.mono(11)).foregroundStyle(OW.textDim)
                }
                Slider(value: $settings.llmCreativity, in: 0...1).tint(OW.coral)

                Rectangle().fill(OW.divider).frame(height: 1)

                toggleRow("Anti-AI guardrails",
                          subtitle: "Keep your own words and tone — never let it sound like a chatbot.",
                          isOn: $settings.antiAiGuardrails)
            }
            .padding(.top, 2)
        }
    }

    @ViewBuilder
    private func polishModelRow(_ model: LlmModel) -> some View {
        let isActive = settings.llmModel == model
        let downloading = llmModels.downloadingModel == model
        let downloaded = llmModels.isDownloaded(model)

        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 7) {
                    Text(model.label).font(OW.ui(13.5, weight: .semibold)).foregroundStyle(OW.text)
                    if model.isFinetune {
                        OWStatusChip(text: "Recommended", tone: .ok)
                    }
                }
                Text(model.approxSize).font(OW.mono(11)).foregroundStyle(OW.textMuted)
            }
            Spacer()

            if downloading {
                ProgressView(value: llmModels.downloadProgress ?? 0).tint(OW.coral).frame(width: 80)
                Button("Cancel") { llmModels.cancelDownload() }
                    .buttonStyle(OWSecondaryButtonStyle())
            } else if downloaded {
                if isActive {
                    OWStatusChip(text: "Active", tone: .ok, systemImage: "checkmark")
                } else {
                    Button("Use") { settings.llmModel = model }
                        .buttonStyle(OWSecondaryButtonStyle())
                }
                Button { llmModels.delete(model) } label: { Image(systemName: "trash") }
                    .buttonStyle(OWSecondaryButtonStyle())
                    .help("Remove download")
            } else {
                Button("Download") {
                    settings.llmModel = model
                    Task { await llmModels.download(model) }
                }
                .buttonStyle(OWPrimaryButtonStyle())
                .disabled(llmModels.isDownloading)
            }
        }
        .padding(11)
        .background(isActive ? OW.chip : OW.card, in: RoundedRectangle(cornerRadius: OW.rChip))
        .overlay(RoundedRectangle(cornerRadius: OW.rChip)
            .strokeBorder(isActive ? OW.coral : OW.border, lineWidth: isActive ? 1.5 : 1))
    }

    private var creativityLabel: String {
        switch settings.llmCreativity {
        case ..<0.25: return "Faithful"
        case ..<0.55: return "Balanced"
        case ..<0.8:  return "Loose"
        default:      return "Free"
        }
    }

    // MARK: - 3 · Trigger & Shortcut

    @ViewBuilder
    private var triggerSection: some View {
        group(header: "Trigger") {
            VStack(alignment: .leading, spacing: 12) {
                OWSegmented(
                    selection: $settings.triggerKind,
                    options: TriggerKind.allCases.map { ($0, $0.label) }
                )

                switch settings.triggerKind {
                case .fnKey:
                    infoNote("Hold the 🌐 fn key to talk — release to insert. Double-tap fn for hands-free (it keeps listening; tap fn again, or pause, to stop).")
                case .hotkey:
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 10) {
                            HotKeyRecorder(
                                keyCode: $settings.hotKeyCode,
                                modifiers: $settings.hotKeyModifiers
                            )
                            .frame(width: 150, height: 26)

                            Text(settings.hotKeyDisplay)
                                .font(OW.mono(13, weight: .medium))
                                .foregroundStyle(OW.textDim)

                            Spacer()

                            Button("Reset") { settings.resetHotKeyToDefault() }
                                .buttonStyle(OWSecondaryButtonStyle())
                        }
                        infoNote("Click the field, then press a combo (needs at least one modifier). Press it anywhere to start and stop dictation. Esc cancels.")
                    }
                }
            }
            .padding(14)
        }

        group(header: "Permissions") {
            VStack(alignment: .leading, spacing: 12) {
                permissionRow(
                    title: "Accessibility",
                    subtitle: "Required to type finished text into the app you're in.",
                    granted: axTrusted,
                    grantedText: "Granted",
                    action: { MacPermissions.requestAccessibility() }
                )
                Rectangle().fill(OW.divider).frame(height: 1)
                inputMonitoringRow
            }
            .padding(14)
        }

        group(header: "Capture") {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("Mic sensitivity")
                            .font(OW.ui(14, weight: .medium)).foregroundStyle(OW.text)
                        Spacer()
                    }
                    OWSegmented(
                        selection: $settings.vadSensitivity,
                        options: VADSensitivity.allCases.map { ($0, $0.label) }
                    )
                    Text("Higher stops sooner on pauses.")
                        .font(OW.ui(11.5)).foregroundStyle(OW.textMuted)
                }
                Rectangle().fill(OW.divider).frame(height: 1)
                toggleRow("Show indicator in the notch",
                          subtitle: "Place the listening indicator at the top-center (notch area) instead of above the Dock.",
                          isOn: $settings.useNotchHud)
            }
            .padding(14)
        }
    }

    private var inputMonitoringRow: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text("Input Monitoring")
                    .font(OW.ui(14, weight: .medium)).foregroundStyle(OW.text)
                Text("Lets OpenWispr see the fn key from other apps — required for fn hold / double-tap to work everywhere. A custom shortcut works without it.")
                    .font(OW.ui(11.5)).foregroundStyle(OW.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            switch inputMonitoring {
            case .granted:
                OWStatusChip(text: "Granted", tone: .ok, systemImage: "checkmark")
            case .denied, .unknown:
                Button("Enable") {
                    MacPermissions.requestInputMonitoring()
                    MacPermissions.openInputMonitoringPane()
                }
                .buttonStyle(OWSecondaryButtonStyle())
            }
        }
    }

    private func permissionRow(title: String, subtitle: String, granted: Bool, grantedText: String, action: @escaping () -> Void) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(OW.ui(14, weight: .medium)).foregroundStyle(OW.text)
                Text(subtitle).font(OW.ui(11.5)).foregroundStyle(OW.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            if granted {
                OWStatusChip(text: grantedText, tone: .ok, systemImage: "checkmark")
            } else {
                Button("Grant") { action() }
                    .buttonStyle(OWSecondaryButtonStyle())
            }
        }
    }

    // MARK: - 4 · Privacy

    @ViewBuilder
    private var privacySection: some View {
        group(header: "History") {
            VStack(alignment: .leading, spacing: 10) {
                toggleRow("Keep history",
                          subtitle: "Save finished dictations to the on-device list shown on Home.",
                          isOn: $settings.keepHistory)
            }
            .padding(14)
        }

        group(header: "Clear data") {
            VStack(alignment: .leading, spacing: 10) {
                clearRow("Dictation history", count: history.records.count) { history.clear() }
                Rectangle().fill(OW.divider).frame(height: 1)
                clearRow("Style memory", count: corpus.samples.count) { corpus.clear() }
                Rectangle().fill(OW.divider).frame(height: 1)
                clearRow("Personal dictionary", count: vocab.entries.count) { vocab.clear() }
            }
            .padding(14)
        }

        infoNote("Everything OpenWispr stores stays on this Mac. Nothing is uploaded.")
    }

    private func clearRow(_ label: String, count: Int, action: @escaping () -> Void) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(OW.ui(14, weight: .medium)).foregroundStyle(OW.text)
                Text(count == 1 ? "1 item" : "\(count) items")
                    .font(OW.mono(11)).foregroundStyle(OW.textMuted)
            }
            Spacer()
            Button("Clear") { action() }
                .buttonStyle(OWSecondaryButtonStyle())
                .disabled(count == 0)
        }
    }

    // MARK: - 5 · Personalization

    @ViewBuilder
    private var personalizationSection: some View {
        group(header: "Personal dictionary") {
            PersonalDictionaryView()
        }

        group(header: "Style memory") {
            StyleMemoryView()
        }

        group(header: "Tone") {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Per-app tone")
                        .font(OW.ui(14, weight: .medium)).foregroundStyle(OW.text)
                    Text("Polish adapts automatically — casual in chat, tidy in mail and docs.")
                        .font(OW.ui(11.5)).foregroundStyle(OW.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
                OWStatusChip(text: "Automatic", tone: .ok, systemImage: "checkmark")
            }
            .padding(14)
        }

        group(header: "Contacts") {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Import contact names")
                        .font(OW.ui(14, weight: .medium)).foregroundStyle(OW.text)
                    Text("Teach OpenWispr the names of people you message so it spells them right.")
                        .font(OW.ui(11.5)).foregroundStyle(OW.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
                OWStatusChip(text: "Coming soon", tone: .neutral)
            }
            .padding(14)
        }
    }

    // MARK: - 6 · General

    @ViewBuilder
    private var generalSection: some View {
        group(header: "Startup") {
            VStack(alignment: .leading, spacing: 12) {
                toggleRow("Launch at login",
                          subtitle: "Start OpenWispr automatically when you sign in.",
                          isOn: Binding(
                            get: { launchAtLogin },
                            set: { launchAtLogin = $0; LaunchAtLogin.setEnabled($0) }
                          ))
                Rectangle().fill(OW.divider).frame(height: 1)
                toggleRow("Show menu-bar icon",
                          subtitle: "Keep the OpenWispr mic in the menu bar.",
                          isOn: $settings.showMenuBarIcon)
            }
            .padding(14)
        }

        group(header: "Language") {
            HStack {
                Text("Dictation language")
                    .font(OW.ui(14, weight: .medium)).foregroundStyle(OW.text)
                Spacer()
                Text(settings.dictationLanguage)
                    .font(OW.ui(13, weight: .medium)).foregroundStyle(OW.textDim)
                OWStatusChip(text: "More soon", tone: .neutral)
            }
            .padding(14)
        }

        group(header: "About") {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("Version").font(OW.ui(14, weight: .medium)).foregroundStyle(OW.text)
                    Spacer()
                    Text(Self.appVersion).font(OW.mono(12)).foregroundStyle(OW.textDim)
                }
                Rectangle().fill(OW.divider).frame(height: 1)
                HStack {
                    Text("Re-run the first-run setup walkthrough.")
                        .font(OW.ui(13)).foregroundStyle(OW.textDim)
                    Spacer()
                    Button("Replay setup") { OpenWisprWindows.replayOnboarding?() }
                        .buttonStyle(OWSecondaryButtonStyle())
                }
            }
            .padding(14)
        }
    }

    // MARK: - Shared building blocks

    /// A grouped section: a mono uppercase header above a "paper" card.
    @ViewBuilder
    private func group<Content: View>(
        header: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            MonoLabel(text: header, color: OW.textDim, size: 10, tracking: 1.6)
                .padding(.leading, 4)
            content()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(OW.card, in: RoundedRectangle(cornerRadius: OW.rCard))
                .overlay(RoundedRectangle(cornerRadius: OW.rCard).strokeBorder(OW.border, lineWidth: 1))
        }
    }

    /// A label (+ subtitle) on the left, an `OWToggle` on the right.
    private func toggleRow(_ label: String, subtitle: String, isOn: Binding<Bool>) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(label).font(OW.ui(14, weight: .medium)).foregroundStyle(OW.text)
                Text(subtitle).font(OW.ui(11.5)).foregroundStyle(OW.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            OWToggle(isOn: isOn)
        }
    }

    private enum NoteTone { case muted, danger }

    private func infoNote(_ text: String, tone: NoteTone = .muted) -> some View {
        Text(text)
            .font(OW.ui(11.5))
            .foregroundStyle(tone == .danger ? OW.danger : OW.textMuted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 4)
    }

    /// Marketing version + build, from the bundle (set in `project.yml`).
    private static var appVersion: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.1.0"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(v) (\(b))"
    }
}
