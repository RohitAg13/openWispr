import AppKit
import SwiftUI
import OpenWisprCore

/// Temporary scaffolding view: type a raw "transcript" and watch the deterministic
/// pipeline clean it live. Proves OpenWisprCore is linked and working inside the app
/// bundle. It will be replaced by the real dictation UI once audio/STT land.
struct CleanupDemoView: View {
    @State private var raw =
        "let's meet at 2 actually 3 new line going to the store for 1. apples 2. bananas 3. oranges"
    @State private var isCode = false

    private var cleaned: String {
        TextProcessor.process(raw, isCodeContext: isCode)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "mic.fill").foregroundStyle(.tint)
                Text("OpenWispr").font(.headline)
                Spacer()
                Text("v0.1 · core demo").font(.caption2).foregroundStyle(.secondary)
            }

            Text("Deterministic cleanup — proof the Swift core is wired into the app.")
                .font(.caption).foregroundStyle(.secondary)

            Text("RAW TRANSCRIPT").font(.caption2.bold()).foregroundStyle(.secondary)
            TextEditor(text: $raw)
                .font(.system(.body, design: .monospaced))
                .frame(height: 88)
                .padding(6)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(.quaternary))

            Toggle("Code / terminal context", isOn: $isCode).font(.caption)

            Divider()

            Text("CLEANED").font(.caption2.bold()).foregroundStyle(.secondary)
            Text(cleaned.isEmpty ? "—" : cleaned)
                .font(.body)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .topLeading)

            Divider()

            HStack {
                Button("Copy") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(cleaned, forType: .string)
                }
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
        }
        .padding(14)
    }
}
