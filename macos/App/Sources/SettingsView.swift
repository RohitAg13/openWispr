import SwiftUI

/// The OpenWispr settings window. Binds to the shared `AppSettings`; every change
/// persists immediately and the coordinator (observing the same store) applies it live.
struct SettingsView: View {
    @ObservedObject var settings: AppSettings

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
            } header: {
                Text("Speech-to-text")
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
}
