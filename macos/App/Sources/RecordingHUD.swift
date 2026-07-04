import AppKit
import SwiftUI

/// State for the hands-free dictation overlay. `@MainActor` ObservableObject so the
/// SwiftUI content updates on the main thread as the coordinator drives the session.
@MainActor
final class HUDState: ObservableObject {
    enum Phase: Equatable {
        /// The resting "Tap to talk" notch pill (persistent notch mode only).
        case idle
        case listening(level: Float)
        case transcribing
        case inserted
        /// A short status line (e.g. "Copied — grant Accessibility to auto-insert").
        case message(String)
        case error(String)
    }

    @Published var phase: Phase = .idle
    /// Invoked by tapping the resting notch pill; wired up by the coordinator to start a session.
    var onStart: () -> Void = {}
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
    /// Notch mode: the resting "Tap to talk" pill stays visible and expands during a session. When
    /// off, the HUD is transient (shown only while dictating, near the Dock).
    private var persistent = false

    /// Turn the persistent notch on/off (the "Show indicator in the notch" setting). When on, the
    /// resting pill is shown immediately; when off, the panel is hidden until a session starts.
    func configure(persistent: Bool) {
        self.persistent = persistent
        if panel == nil { buildPanel() }
        if persistent {
            if !isSessionPhase { state.phase = .idle }
            resizeAndPosition()
            panel?.orderFrontRegardless()
        } else if !isSessionPhase {
            panel?.orderOut(nil)
        }
    }

    /// Show the panel for a starting session (ensure visible + positioned).
    func show() {
        if panel == nil { buildPanel() }
        resizeAndPosition()
        panel?.orderFrontRegardless()
    }

    func update(_ phase: HUDState.Phase) {
        state.phase = phase
        resizeAndPosition()
    }

    /// End of a session: in notch mode fall back to the resting pill (stay visible); otherwise hide.
    func hide() {
        if persistent {
            state.phase = .idle
            resizeAndPosition()
            panel?.orderFrontRegardless()
        } else {
            panel?.orderOut(nil)
        }
    }

    private var isSessionPhase: Bool {
        if case .idle = state.phase { return false }
        return true
    }

    // MARK: - Panel construction

    private func buildPanel() {
        let hosting = NSHostingView(rootView: RecordingHUDView(state: state))
        hosting.translatesAutoresizingMaskIntoConstraints = true

        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 210, height: 34),
            styleMask: [.nonactivatingPanel, .hudWindow, .utilityWindow],
            backing: .buffered,
            defer: false
        )
        panel.level = .floating
        panel.isFloatingPanel = true
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.isMovableByWindowBackground = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.isReleasedWhenClosed = false
        panel.hasShadow = true
        panel.backgroundColor = .clear
        panel.contentView = hosting

        self.panel = panel
    }

    /// Size the panel for the current phase (compact resting pill vs. expanded session panel) and
    /// anchor it top-center under the notch — or, when notch mode is off, just above the Dock.
    private func resizeAndPosition() {
        guard let panel = panel else { return }
        let size: NSSize
        if case .idle = state.phase {
            size = NSSize(width: 200, height: 30)
        } else {
            size = NSSize(width: 440, height: 96)
        }

        let screen = NSScreen.screens.first { $0.frame.contains(NSEvent.mouseLocation) }
            ?? NSScreen.main
        guard let screen = screen else { return }

        let visible = screen.visibleFrame
        let x = visible.midX - size.width / 2
        let y: CGFloat
        if AppSettings.shared.useNotchHud {
            // Hang flush from the top-center, tucked right under the menu bar. A `.floating` panel
            // sits below menu-bar level, so anchoring to the physical top would hide it behind the
            // bar; `visibleFrame.maxY` is the menu bar's bottom edge — the flat top meets it and only
            // the bottom corners are rounded, so it reads as descending from the bar like a notch.
            y = visible.maxY - size.height
        } else {
            y = visible.minY + 80 // a little above the Dock
        }
        panel.setFrame(NSRect(x: x, y: y, width: size.width, height: size.height), display: true)
    }
}

/// Compact SwiftUI content for the overlay, styled to the OpenWispr brand design:
/// a dark warm gradient pill with the gradient "orb" + live waveform while listening,
/// and brand-colored status states for transcribing / inserted / error.
private struct RecordingHUDView: View {
    @ObservedObject var state: HUDState

    @ViewBuilder
    var body: some View {
        if case .idle = state.phase {
            idlePill
        } else {
            activePanel
        }
    }

    /// Near-black notch background (design: oklch(0.06 0.004 60)).
    private static let notchBG = Color(hex: 0x0E0B0A)
    /// Muted warm-gray label on the dark notch (design: oklch(0.78 0.02 70)).
    private static let notchLabel = Color(hex: 0xBBB0A2)

    /// The resting "Tap to talk" notch pill — hangs flush from the top edge (bottom corners rounded
    /// only); the whole pill is tappable to start a session.
    private var idlePill: some View {
        Button { state.onStart() } label: {
            HStack(spacing: 9) {
                OWOrb(size: 15, breathing: true)
                MonoLabel(text: "Tap to talk", color: Self.notchLabel, size: 9, tracking: 1.6)
                Spacer(minLength: 6)
                HStack(alignment: .bottom, spacing: 2.5) {
                    Capsule().fill(Color(hex: 0x7C7264)).frame(width: 2.5, height: 5)
                    Capsule().fill(Color(hex: 0x968A78)).frame(width: 2.5, height: 9)
                    Capsule().fill(Color(hex: 0x7C7264)).frame(width: 2.5, height: 7)
                }
            }
            .padding(.leading, 15)
            .padding(.trailing, 14)
            .frame(width: 200, height: 30)
            .background(
                Self.notchBG,
                in: UnevenRoundedRectangle(bottomLeadingRadius: 15, bottomTrailingRadius: 15)
            )
            .shadow(color: .black.opacity(0.4), radius: 8, x: 0, y: 3)
        }
        .buttonStyle(.plain)
        .help("Tap to talk")
    }

    private var activePanel: some View {
        HStack(spacing: 12) {
            leading
            content
            Spacer(minLength: 4)
            if case .listening = state.phase {
                // Primary: finish now → transcribe + insert.
                Button {
                    state.onStop()
                } label: {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(OW.text)
                        .frame(width: 30, height: 30)
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
        .padding(.horizontal, 20)
        .frame(width: 440, height: 96)
        .background(
            Self.notchBG,
            in: UnevenRoundedRectangle(bottomLeadingRadius: 26, bottomTrailingRadius: 26)
        )
        .shadow(color: .black.opacity(0.5), radius: 20, x: 0, y: 8)
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
        case .idle:
            EmptyView()
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
        case .idle:
            EmptyView()
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
