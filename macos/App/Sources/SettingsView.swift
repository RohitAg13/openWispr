import SwiftUI

/// The OpenWispr settings window. Binds to the shared `AppSettings`; every change
/// persists immediately and the coordinator (observing the same store) applies it live.
struct SettingsView: View {
    @ObservedObject var settings: AppSettings
    @ObservedObject private var models = WhisperModelManager.shared

    var body: some View {
        Form {
            // MARK: Global hotkey
            Section {
                HStack(spacing: 10) {
                    HotKeyRecorder(
                        keyCode: $settings.hotKeyCode,
                        modifiers: $settings.hotKeyModifiers
                    )
                    .frame(width: 150, height: 26)

                    Text(settings.hotKeyDisplay)
                        .font(.system(.body, design: .monospaced))
                        .foregroundStyle(.secondary)

                    Spacer()

                    Button("Reset to default") {
                        settings.resetHotKeyToDefault()
                    }
                }
                Text("Click the field, then press a combo (needs at least one modifier). Esc cancels.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } header: {
                Text("Global hotkey")
            }

            // MARK: Speech-to-text
            Section {
                Picker("Engine", selection: $settings.sttProvider) {
                    ForEach(STTProvider.allCases, id: \.self) { provider in
                        Text(provider.isAvailable
                            ? provider.label
                            : "\(provider.label) (coming soon)")
                            .tag(provider)
                    }
                }
                .pickerStyle(.menu)

                if settings.sttProvider == .whisper {
                    whisperModelControls
                }
            } header: {
                Text("Speech-to-text")
            } footer: {
                if settings.sttProvider == .whisper {
                    Text("Whisper runs fully on-device and offline. The model downloads once.")
                        .font(.caption2).foregroundStyle(.secondary)
                }
            }

            // MARK: Cleanup / polish
            Section {
                Picker("Polish", selection: $settings.polishLevel) {
                    ForEach(PolishLevel.allCases, id: \.self) { level in
                        Text(level.label).tag(level)
                    }
                }
                .pickerStyle(.menu)
                Text("On-device polish coming next.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } header: {
                Text("Cleanup / polish")
            }

            // MARK: Mic sensitivity
            Section {
                Picker("Sensitivity", selection: $settings.vadSensitivity) {
                    ForEach(VADSensitivity.allCases, id: \.self) { level in
                        Text(level.label).tag(level)
                    }
                }
                .pickerStyle(.segmented)
                Text("Higher = stops sooner on pauses.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } header: {
                Text("Mic sensitivity")
            }
        }
        .formStyle(.grouped)
        .frame(width: 360)
        .fixedSize(horizontal: false, vertical: true)
        .safeAreaInset(edge: .bottom) {
            Text("Changes apply immediately — the new hotkey works right away.")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
        }
    }

    /// Whisper model picker + download/remove controls. `models.revision` is read implicitly via
    /// `isDownloaded`, so the row updates as soon as a download finishes or a model is removed.
    @ViewBuilder
    private var whisperModelControls: some View {
        Picker("Model", selection: $settings.whisperModel) {
            ForEach(WhisperModel.allCases) { model in
                Text("\(model.label) · \(model.approxSize)").tag(model)
            }
        }
        .pickerStyle(.menu)
        .disabled(models.isDownloading)

        let model = settings.whisperModel
        let downloading = models.downloadingModel == model

        HStack(spacing: 8) {
            if downloading {
                ProgressView(value: models.downloadProgress ?? 0)
                    .frame(maxWidth: .infinity)
                Text(progressLabel)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                Button("Cancel") { models.cancelDownload() }
            } else if models.isDownloaded(model) {
                Label("Downloaded", systemImage: "checkmark.circle.fill")
                    .font(.caption).foregroundStyle(.green)
                Spacer()
                Button("Remove") { models.delete(model) }
            } else {
                Label("Not downloaded", systemImage: "arrow.down.circle")
                    .font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button("Download \(model.approxSize)") {
                    Task { await models.download(model) }
                }
                .buttonStyle(.borderedProminent)
                .disabled(models.isDownloading)
            }
        }

        if let error = models.lastError {
            Text(error).font(.caption2).foregroundStyle(.red)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var progressLabel: String {
        let pct = Int((models.downloadProgress ?? 0) * 100)
        return "\(pct)%"
    }
}
