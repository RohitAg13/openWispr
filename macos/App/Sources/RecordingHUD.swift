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
    /// Invoked by the Cancel button (discard); wired up by the coordinator.
    var onCancel: () -> Void = {}
    /// Invoked by the Stop button (finish → transcribe → insert); wired up by the coordinator.
    var onStop: () -> Void = {}
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
            contentRect: NSRect(x: 0, y: 0, width: 320, height: 72),
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

/// Compact SwiftUI content for the overlay, styled to the OpenWispr brand design:
/// a dark warm gradient pill with the gradient "orb" + live waveform while listening,
/// and brand-colored status states for transcribing / inserted / error.
private struct RecordingHUDView: View {
    @ObservedObject var state: HUDState

    var body: some View {
        HStack(spacing: 12) {
            leading
            content
            Spacer(minLength: 4)
            if case .listening = state.phase {
                // Primary: finish now → transcribe + insert (also bound to ⌃⌥D toggle).
                Button {
                    state.onStop()
                } label: {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(OW.text)
                        .frame(width: 28, height: 28)
                        .background(OW.onDark, in: Circle())
                }
                .buttonStyle(.plain)
                .help("Stop & insert")
            }
            if showsCancel {
                Button {
                    state.onCancel()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(OW.onDarkDim)
                        .frame(width: 26, height: 26)
                        .background(.white.opacity(0.08), in: Circle())
                }
                .buttonStyle(.plain)
                .help("Cancel")
            }
        }
        .padding(.horizontal, 14)
        .frame(width: 320, height: 72)
        .background(OW.overlayGradient, in: RoundedRectangle(cornerRadius: 18))
        .overlay(
            RoundedRectangle(cornerRadius: 18)
                .strokeBorder(.white.opacity(0.06), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.35), radius: 18, x: 0, y: 8)
    }

    private var showsCancel: Bool {
        switch state.phase {
        case .listening, .transcribing: return true
        default: return false
        }
    }

    @ViewBuilder
    private var leading: some View {
        switch state.phase {
        case .listening(let level):
            ZStack {
                Circle().fill(OW.orbGradientBright)
                Waveform(level: level)
            }
            .frame(width: 40, height: 40)
            .shadow(color: OW.coral.opacity(0.5), radius: 8, x: 0, y: 4)
        case .transcribing:
            ProgressView().controlSize(.small).tint(OW.onDark)
                .frame(width: 40)
        case .inserted:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 22)).foregroundStyle(OW.onDark)
                .frame(width: 40)
        case .message:
            Image(systemName: "doc.on.clipboard")
                .font(.system(size: 18)).foregroundStyle(OW.onDarkDim)
                .frame(width: 40)
        case .error:
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 18)).foregroundStyle(OW.onDarkDim)
                .frame(width: 40)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch state.phase {
        case .listening:
            VStack(alignment: .leading, spacing: 4) {
                MonoLabel(text: "Listening", color: OW.onDarkDim, size: 10, tracking: 1.6)
                Text("On-device · nothing uploaded")
                    .font(OW.ui(11))
                    .foregroundStyle(OW.onDark.opacity(0.7))
            }
        case .transcribing:
            MonoLabel(text: "Transcribing", color: OW.onDarkDim, size: 11, tracking: 1.6)
        case .inserted:
            Text("Inserted into the active app")
                .font(OW.ui(13, weight: .semibold))
                .foregroundStyle(OW.onDark)
        case .message(let text):
            Text(text).font(OW.ui(12)).foregroundStyle(OW.onDark)
                .lineLimit(2).fixedSize(horizontal: false, vertical: true)
        case .error(let text):
            Text(text).font(OW.ui(12)).foregroundStyle(OW.onDark)
                .lineLimit(2).fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// The five-bar animated waveform from the design's listening orb. The center bars
/// react to the live mic `level`; all bars idle-breathe so it never looks frozen.
private struct Waveform: View {
    var level: Float
    @State private var phase: CGFloat = 0

    // Base heights (relative) mirroring the design's 24/40/50/34/22 pattern.
    private let bases: [CGFloat] = [0.42, 0.7, 0.9, 0.6, 0.38]

    var body: some View {
        TimelineView(.animation) { timeline in
            let t = timeline.date.timeIntervalSinceReferenceDate
            HStack(spacing: 3) {
                ForEach(0..<bases.count, id: \.self) { i in
                    let wobble = (sin(t * 6 + Double(i) * 0.7) + 1) / 2 // 0…1
                    let lvl = CGFloat(min(level * 2.5, 1))
                    let h = (bases[i] * (0.45 + 0.55 * CGFloat(wobble))) * (0.6 + 0.4 * lvl)
                    Capsule()
                        .fill(.white)
                        .frame(width: 3, height: max(4, 22 * h))
                }
            }
        }
    }
}
