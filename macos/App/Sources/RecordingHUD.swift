import AppKit
import Combine
import CoreGraphics
import SwiftUI

// MARK: - Shared session flag (menu bar + overlays)

/// Single source of truth for “OpenWispr is dictating right now.”
/// The menu-bar icon observes this so there is always a system-chrome signal even if
/// an overlay panel is somehow occluded.
@MainActor
final class DictationIndicatorCenter: ObservableObject {
    static let shared = DictationIndicatorCenter()

    enum Phase: Equatable {
        case idle
        case listening(level: Float)
        case transcribing
        case inserted
        case message(String)
        case error(String)
    }

    @Published private(set) var phase: Phase = .idle

    var isActive: Bool {
        if case .idle = phase { return false }
        return true
    }

    /// SF Symbol for the menu-bar extra — changes the instant a session starts.
    var menuBarSymbol: String {
        switch phase {
        case .idle: return "mic.fill"
        case .listening: return "waveform.circle.fill"
        case .transcribing: return "ellipsis.circle.fill"
        case .inserted: return "checkmark.circle.fill"
        case .message: return "doc.on.clipboard.fill"
        case .error: return "exclamationmark.triangle.fill"
        }
    }

    fileprivate func setPhase(_ phase: Phase) {
        self.phase = phase
    }
}

// MARK: - Overlay controller (replaces RecordingHUD)

/// On-screen dictation indicator. Built to be unmissable:
/// - One panel **per display** (no more “HUD on the other monitor”)
/// - Window level above ordinary app / Electron chrome
/// - High-contrast coral bar near the bottom of every screen
/// - Keep-alive timer re-orders panels while a session is active
/// - Hidden **only** when `AppSettings.showDictationIndicator` is off, or the session ends
@MainActor
final class DictationIndicator {
    let center = DictationIndicatorCenter.shared

    var onCancel: () -> Void = {}
    var onStop: () -> Void = {}

    private var panels: [CGDirectDisplayID: IndicatorPanel] = [:]
    private var keepAlive: Timer?
    private var screenObserver: NSObjectProtocol?
    private var settingsCancellable: AnyCancellable?

    init() {
        screenObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.rebuildForScreens() }
        }
        settingsCancellable = AppSettings.shared.$showDictationIndicator
            .receive(on: RunLoop.main)
            .sink { [weak self] enabled in
                guard let self else { return }
                if !enabled {
                    self.tearDownPanels()
                } else if self.center.isActive {
                    self.present(self.center.phase)
                }
            }
    }

    deinit {
        if let screenObserver {
            NotificationCenter.default.removeObserver(screenObserver)
        }
    }

    // MARK: Public API (what DictationCoordinator calls)

    func presentListening(level: Float = 0) {
        present(.listening(level: level))
    }

    func presentTranscribing() {
        present(.transcribing)
    }

    func presentInserted() {
        present(.inserted)
    }

    func presentMessage(_ text: String) {
        present(.message(text))
    }

    func presentError(_ text: String) {
        present(.error(text))
    }

    func updateLevel(_ level: Float) {
        guard case .listening = center.phase else { return }
        center.setPhase(.listening(level: level))
        // Don't rebuild frames on every amplitude tick — SwiftUI observes `center`.
    }

    /// Hide overlays and return menu bar to idle. Call when a session is fully done.
    func dismiss() {
        center.setPhase(.idle)
        stopKeepAlive()
        tearDownPanels()
    }

    // MARK: Internals

    private func present(_ phase: DictationIndicatorCenter.Phase) {
        center.setPhase(phase)
        guard AppSettings.shared.showDictationIndicator else {
            tearDownPanels()
            return
        }
        rebuildForScreens()
        startKeepAlive()
    }

    private func rebuildForScreens() {
        guard center.isActive, AppSettings.shared.showDictationIndicator else {
            tearDownPanels()
            return
        }

        let screens = NSScreen.screens
        var seen: Set<CGDirectDisplayID> = []

        for screen in screens {
            let id = screen.openWisprDisplayID
            seen.insert(id)
            if let existing = panels[id] {
                existing.reposition(on: screen)
                existing.orderFront()
            } else {
                let panel = IndicatorPanel(
                    screen: screen,
                    center: center,
                    onStop: { [weak self] in self?.onStop() },
                    onCancel: { [weak self] in self?.onCancel() }
                )
                panels[id] = panel
                panel.orderFront()
            }
        }

        // Drop panels for disconnected displays.
        for id in panels.keys where !seen.contains(id) {
            panels[id]?.close()
            panels[id] = nil
        }
    }

    private func tearDownPanels() {
        for (_, panel) in panels { panel.close() }
        panels.removeAll()
    }

    private func startKeepAlive() {
        keepAlive?.invalidate()
        let timer = Timer(timeInterval: 0.35, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.center.isActive else { return }
                guard AppSettings.shared.showDictationIndicator else { return }
                if self.panels.isEmpty || self.panels.count != NSScreen.screens.count {
                    self.rebuildForScreens()
                } else {
                    for (_, panel) in self.panels { panel.orderFront() }
                }
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        keepAlive = timer
    }

    private func stopKeepAlive() {
        keepAlive?.invalidate()
        keepAlive = nil
    }
}

// MARK: - Per-screen panel

@MainActor
private final class IndicatorPanel {
    private let panel: NSPanel
    private let screen: NSScreen

    init(
        screen: NSScreen,
        center: DictationIndicatorCenter,
        onStop: @escaping () -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.screen = screen

        let root = IndicatorBarView(center: center, onStop: onStop, onCancel: onCancel)
        let hosting = NSHostingView(rootView: root)
        hosting.autoresizingMask = [.width, .height]

        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 460, height: 100),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        // Above almost everything users run (incl. Electron fullscreen). Not so high that
        // the system menu bar itself is covered.
        panel.level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.popUpMenuWindow)) + 1)
        panel.isFloatingPanel = true
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = false
        panel.isMovableByWindowBackground = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        panel.isReleasedWhenClosed = false
        panel.hasShadow = true
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.alphaValue = 1
        panel.ignoresMouseEvents = false
        panel.contentView = hosting

        self.panel = panel
        reposition(on: screen)
    }

    func reposition(on screen: NSScreen) {
        let size = NSSize(width: 460, height: 100)
        let visible = screen.visibleFrame
        let x = visible.midX - size.width / 2
        let y = visible.minY + 72
        panel.setFrame(NSRect(x: x, y: y, width: size.width, height: size.height), display: true)
        panel.contentView?.frame = NSRect(origin: .zero, size: size)
    }

    func orderFront() {
        panel.alphaValue = 1
        panel.level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.popUpMenuWindow)) + 1)
        panel.orderFrontRegardless()
    }

    func close() {
        panel.orderOut(nil)
    }
}

// MARK: - SwiftUI bar

private struct IndicatorBarView: View {
    @ObservedObject var center: DictationIndicatorCenter
    var onStop: () -> Void
    var onCancel: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            leading
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 16, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                Text(subtitle)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.9))
            }
            Spacer(minLength: 4)
            if case .listening = center.phase {
                Button(action: onStop) {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(Color(hex: 0x1A1410))
                        .frame(width: 34, height: 34)
                        .background(Color.white, in: Circle())
                }
                .buttonStyle(.plain)
                .help("Stop & insert")
            }
            if showsCancel {
                Button(action: onCancel) {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 30, height: 30)
                        .background(Color.white.opacity(0.22), in: Circle())
                }
                .buttonStyle(.plain)
                .help("Cancel")
            }
        }
        .padding(.horizontal, 22)
        .frame(width: 460, height: 100)
        .background(
            LinearGradient(
                colors: [Color(hex: 0xE05A36), Color(hex: 0x9B2F1C)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: 24, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .strokeBorder(Color.white.opacity(0.45), lineWidth: 1.5)
        )
        .shadow(color: Color(hex: 0xE05A36).opacity(0.65), radius: 28, x: 0, y: 10)
        .shadow(color: .black.opacity(0.4), radius: 18, x: 0, y: 8)
    }

    private var showsCancel: Bool {
        switch center.phase {
        case .listening, .transcribing: return true
        default: return false
        }
    }

    private var title: String {
        switch center.phase {
        case .idle: return ""
        case .listening: return "Listening"
        case .transcribing: return "Transcribing…"
        case .inserted: return "Inserted"
        case .message: return "Copied"
        case .error: return "Something went wrong"
        }
    }

    private var subtitle: String {
        switch center.phase {
        case .idle: return ""
        case .listening: return "OpenWispr is dictating · on-device"
        case .transcribing: return "Turning speech into text"
        case .inserted: return "Pasted into the active app"
        case .message(let t), .error(let t): return t
        }
    }

    @ViewBuilder
    private var leading: some View {
        switch center.phase {
        case .idle:
            EmptyView()
        case .listening(let level):
            ZStack {
                Circle().fill(Color.white.opacity(0.22))
                IndicatorWaveform(level: level)
            }
            .frame(width: 44, height: 44)
        case .transcribing:
            ProgressView().controlSize(.regular).tint(.white)
                .frame(width: 44, height: 44)
        case .inserted:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 28)).foregroundStyle(.white)
                .frame(width: 44, height: 44)
        case .message:
            Image(systemName: "doc.on.clipboard.fill")
                .font(.system(size: 22)).foregroundStyle(.white)
                .frame(width: 44, height: 44)
        case .error:
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 22)).foregroundStyle(.white)
                .frame(width: 44, height: 44)
        }
    }
}

private struct IndicatorWaveform: View {
    var level: Float
    private let bases: [CGFloat] = [0.42, 0.7, 0.9, 0.6, 0.38]

    var body: some View {
        TimelineView(.animation) { timeline in
            let t = timeline.date.timeIntervalSinceReferenceDate
            HStack(spacing: 3) {
                ForEach(0..<bases.count, id: \.self) { i in
                    let wobble = (sin(t * 6 + Double(i) * 0.7) + 1) / 2
                    let lvl = CGFloat(min(level * 2.5, 1))
                    let h = (bases[i] * (0.45 + 0.55 * CGFloat(wobble))) * (0.6 + 0.4 * lvl)
                    Capsule()
                        .fill(.white)
                        .frame(width: 3.5, height: max(5, 24 * h))
                }
            }
        }
    }
}

// MARK: - Compatibility shim
//
// Older call sites / docs referred to `RecordingHUD`. Keep a thin alias so any stray
// reference still compiles while the coordinator migrates to `DictationIndicator`.

typealias RecordingHUD = DictationIndicator

private extension NSScreen {
    var openWisprDisplayID: CGDirectDisplayID {
        (deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value ?? 0
    }
}
