import SwiftUI

/// The OpenWispr settings window. Binds to the shared `AppSettings`; every change
/// persists immediately and the coordinator (observing the same store) applies it live.
struct SettingsView: View {
    @ObservedObject var settings: AppSettings
    @ObservedObject private var models = WhisperModelManager.shared
    @ObservedObject private var llmModels = LlmModelManager.shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                // Brand header
                HStack(spacing: 10) {
                    OWOrb(size: 30)
                    Text("Settings")
                        .font(OW.ui(26, weight: .bold))
                        .foregroundStyle(OW.text)
                }
                .padding(.top, 4)

                // MARK: Global hotkey
                settingsGroup(header: "Shortcut") {
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
                        rowNote("Click the field, then press a combo (needs at least one modifier). Esc cancels.")
                    }
                    .padding(14)
                }

                // MARK: Speech-to-text
                settingsGroup(header: "Speech-to-text") {
                    VStack(alignment: .leading, spacing: 10) {
                        settingRow("Engine") {
                            Picker("", selection: $settings.sttProvider) {
                                ForEach(STTProvider.allCases, id: \.self) { provider in
                                    Text(provider.isAvailable
                                        ? provider.label
                                        : "\(provider.label) (coming soon)")
                                        .tag(provider)
                                }
                            }
                            .labelsHidden()
                            .pickerStyle(.menu)
                            .fixedSize()
                        }

                        if settings.sttProvider == .whisper {
                            Rectangle().fill(OW.divider).frame(height: 1)
                            whisperModelControls
                            rowNote("Whisper runs fully on-device and offline. The model downloads once.")
                        }
                    }
                    .padding(14)
                }

                // MARK: Cleanup / polish
                settingsGroup(header: "Cleanup / polish") {
                    VStack(alignment: .leading, spacing: 10) {
                        settingRow("Polish") {
                            Picker("", selection: $settings.polishLevel) {
                                ForEach(PolishLevel.allCases, id: \.self) { level in
                                    Text(level.label).tag(level)
                                }
                            }
                            .labelsHidden()
                            .pickerStyle(.menu)
                            .fixedSize()
                        }

                        if settings.polishLevel == .off {
                            rowNote("Deterministic cleanup only — no LLM.")
                        } else {
                            Rectangle().fill(OW.divider).frame(height: 1)
                            llmModelControls
                            rowNote("Polish rewrites the transcript fully on-device. The model downloads once.")
                        }
                    }
                    .padding(14)
                }

                // MARK: Mic sensitivity
                settingsGroup(header: "Mic sensitivity") {
                    VStack(alignment: .leading, spacing: 10) {
                        Picker("", selection: $settings.vadSensitivity) {
                            ForEach(VADSensitivity.allCases, id: \.self) { level in
                                Text(level.label).tag(level)
                            }
                        }
                        .labelsHidden()
                        .pickerStyle(.segmented)
                        rowNote("Higher = stops sooner on pauses.")
                    }
                    .padding(14)
                }

                rowNote("Changes apply immediately — the new hotkey works right away.")
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.bottom, 8)
            }
            .padding(20)
        }
        .frame(width: 380)
        .frame(minHeight: 420)
        .background(OW.bg)
    }

    // MARK: - Brand layout helpers

    /// A grouped section: a mono uppercase header above a card.
    @ViewBuilder
    private func settingsGroup<Content: View>(
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

    /// A label + trailing control row.
    private func settingRow<Trailing: View>(
        _ label: String,
        @ViewBuilder trailing: () -> Trailing
    ) -> some View {
        HStack {
            Text(label)
                .font(OW.ui(14, weight: .medium))
                .foregroundStyle(OW.text)
            Spacer()
            trailing()
        }
    }

    private func rowNote(_ text: String) -> some View {
        Text(text)
            .font(OW.ui(11))
            .foregroundStyle(OW.textMuted)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// Whisper model picker + download/remove controls. `models.revision` is read implicitly via
    /// `isDownloaded`, so the row updates as soon as a download finishes or a model is removed.
    @ViewBuilder
    private var whisperModelControls: some View {
        settingRow("Model") {
            Picker("", selection: $settings.whisperModel) {
                ForEach(WhisperModel.allCases) { model in
                    Text("\(model.label) · \(model.approxSize)").tag(model)
                }
            }
            .labelsHidden()
            .pickerStyle(.menu)
            .fixedSize()
            .disabled(models.isDownloading)
        }

        let model = settings.whisperModel
        let downloading = models.downloadingModel == model

        HStack(spacing: 8) {
            if downloading {
                ProgressView(value: models.downloadProgress ?? 0)
                    .tint(OW.coral)
                    .frame(maxWidth: .infinity)
                Text(progressLabel)
                    .font(OW.mono(12))
                    .foregroundStyle(OW.textDim)
                Button("Cancel") { models.cancelDownload() }
                    .buttonStyle(OWSecondaryButtonStyle())
            } else if models.isDownloaded(model) {
                Label("Downloaded", systemImage: "checkmark.circle.fill")
                    .font(OW.ui(12, weight: .medium)).foregroundStyle(OW.success)
                Spacer()
                Button("Remove") { models.delete(model) }
                    .buttonStyle(OWSecondaryButtonStyle())
            } else {
                Label("Not downloaded", systemImage: "arrow.down.circle")
                    .font(OW.ui(12)).foregroundStyle(OW.textMuted)
                Spacer()
                Button("Download \(model.approxSize)") {
                    Task { await models.download(model) }
                }
                .buttonStyle(OWPrimaryButtonStyle())
                .disabled(models.isDownloading)
            }
        }

        if let error = models.lastError {
            Text(error).font(OW.ui(11)).foregroundStyle(OW.danger)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var progressLabel: String {
        let pct = Int((models.downloadProgress ?? 0) * 100)
        return "\(pct)%"
    }

    /// On-device LLM model picker + download/remove controls, parallel to `whisperModelControls`.
    @ViewBuilder
    private var llmModelControls: some View {
        settingRow("Model") {
            Picker("", selection: $settings.llmModel) {
                ForEach(LlmModel.allCases) { model in
                    Text("\(model.label) · \(model.approxSize)").tag(model)
                }
            }
            .labelsHidden()
            .pickerStyle(.menu)
            .fixedSize()
            .disabled(llmModels.isDownloading)
        }

        let model = settings.llmModel
        let downloading = llmModels.downloadingModel == model

        HStack(spacing: 8) {
            if downloading {
                ProgressView(value: llmModels.downloadProgress ?? 0)
                    .tint(OW.coral)
                    .frame(maxWidth: .infinity)
                Text("\(Int((llmModels.downloadProgress ?? 0) * 100))%")
                    .font(OW.mono(12))
                    .foregroundStyle(OW.textDim)
                Button("Cancel") { llmModels.cancelDownload() }
                    .buttonStyle(OWSecondaryButtonStyle())
            } else if llmModels.isDownloaded(model) {
                Label("Downloaded", systemImage: "checkmark.circle.fill")
                    .font(OW.ui(12, weight: .medium)).foregroundStyle(OW.success)
                Spacer()
                Button("Remove") { llmModels.delete(model) }
                    .buttonStyle(OWSecondaryButtonStyle())
            } else {
                Label("Not downloaded", systemImage: "arrow.down.circle")
                    .font(OW.ui(12)).foregroundStyle(OW.textMuted)
                Spacer()
                Button("Download \(model.approxSize)") {
                    Task { await llmModels.download(model) }
                }
                .buttonStyle(OWPrimaryButtonStyle())
                .disabled(llmModels.isDownloading)
            }
        }

        if let error = llmModels.lastError {
            Text(error).font(OW.ui(11)).foregroundStyle(OW.danger)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
