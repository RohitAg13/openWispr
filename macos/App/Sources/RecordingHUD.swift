import AppKit
import SwiftUI

/// State for the hands-free dictation overlay. `@MainActor` ObservableObject so the
/// SwiftUI content updates on the main thread as the coordinator drives the session.
@MainActor
final class HUDState: ObservableObject {
    enum Phase: Equatable {
        case listening(level: Float)
        case transcribing
        case inserted
        /// A short status line (e.g. "Copied — grant Accessibility to auto-insert").
        case message(String)
        case error(String)
    }

    @Published var phase: Phase = .listening(level: 0)
    /// Invoked by the Cancel button; wired up by the coordinator.
    var onCancel: () -> Void = {}
}

/// A floating overlay that shows dictation status **without stealing focus**.
///
/// Focus is preserved by:
///  - using an `NSPanel` with `.nonactivatingPanel` (clicks don't activate our app),
///  - `level = .floating`, `isFloatingPanel = true`, `hidesOnDeactivate = false`,
///  - showing with `orderFrontRegardless()` — never `makeKeyAndOrderFront(_:)`, which
///    would key the window and activate the app, pulling focus from the user's field.
///
/// Controls (the Cancel button) still receive mouse clicks in a non-activating panel:
/// the panel accepts mouse events and routes them to its content view without becoming
/// the active app — exactly the behaviour we want.
@MainActor
final class RecordingHUD {
    let state = HUDState()
    private var panel: NSPanel?

    func show() {
        if panel == nil {
            buildPanel()
        }
        position()
        panel?.orderFrontRegardless()
    }

    func update(_ phase: HUDState.Phase) {
        state.phase = phase
    }

    func hide() {
        panel?.orderOut(nil)
    }

    // MARK: - Panel construction

    private func buildPanel() {
        let hosting = NSHostingView(rootView: RecordingHUDView(state: state))
        hosting.translatesAutoresizingMaskIntoConstraints = true

        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 260, height: 64),
            styleMask: [.nonactivatingPanel, .hudWindow, .utilityWindow],
            backing: .buffered,
            defer: false
        )
        panel.level = .floating
        panel.isFloatingPanel = true
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.isMovableByWindowBackground = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient]
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.isReleasedWhenClosed = false
        panel.hasShadow = true
        panel.backgroundColor = .clear
        panel.contentView = hosting

        self.panel = panel
    }

    /// Bottom-center of the screen that currently has the cursor / key — fall back to main.
    private func position() {
        guard let panel = panel else { return }
        let screen = NSScreen.screens.first { $0.frame.contains(NSEvent.mouseLocation) }
            ?? NSScreen.main
        guard let visible = screen?.visibleFrame else { return }

        let size = panel.frame.size
        let x = visible.midX - size.width / 2
        let y = visible.minY + 80 // a little above the Dock
        panel.setFrameOrigin(NSPoint(x: x, y: y))
    }
}

/// Compact SwiftUI content for the overlay. Mirrors the popover's visual language
/// (mic glyph + level bar while listening, spinner while transcribing).
private struct RecordingHUDView: View {
    @ObservedObject var state: HUDState

    var body: some View {
        HStack(spacing: 12) {
            icon
            content
            Spacer(minLength: 4)
            if showsCancel {
                Button {
                    state.onCancel()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 16))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .help("Cancel")
            }
        }
        .padding(.horizontal, 14)
        .frame(width: 260, height: 64)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
    }

    private var showsCancel: Bool {
        switch state.phase {
        case .listening, .transcribing: return true
        default: return false
        }
    }

    @ViewBuilder
    private var icon: some View {
        switch state.phase {
        case .listening:
            Image(systemName: "mic.fill").foregroundStyle(.red)
        case .transcribing:
            ProgressView().controlSize(.small)
        case .inserted:
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
        case .message:
            Image(systemName: "doc.on.clipboard").foregroundStyle(.secondary)
        case .error:
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.yellow)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch state.phase {
        case .listening(let level):
            VStack(alignment: .leading, spacing: 5) {
                Text("Listening…").font(.caption.bold()).foregroundStyle(.secondary)
                levelBar(level: level)
            }
        case .transcribing:
            Text("Transcribing…").font(.caption).foregroundStyle(.secondary)
        case .inserted:
            Text("Inserted ✓").font(.caption.bold()).foregroundStyle(.green)
        case .message(let text):
            Text(text).font(.caption).foregroundStyle(.secondary)
                .lineLimit(2).fixedSize(horizontal: false, vertical: true)
        case .error(let text):
            Text(text).font(.caption).foregroundStyle(.red)
                .lineLimit(2).fixedSize(horizontal: false, vertical: true)
        }
    }

    private func levelBar(level: Float) -> some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3).fill(.quaternary)
                RoundedRectangle(cornerRadius: 3)
                    .fill(.red)
                    .frame(width: max(2, geo.size.width * CGFloat(min(level * 2.5, 1))))
            }
        }
        .frame(width: 130, height: 6)
        .animation(.linear(duration: 0.05), value: level)
    }
}
