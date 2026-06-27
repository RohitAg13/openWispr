import AppKit
import SwiftUI

/// Owns the app-scope hands-free dictation session and the main "Home" window. Created at
/// launch and retained for the app's lifetime; the coordinator's `init` registers the
/// global ⌃⌥Z hotkey.
///
/// The main window is an AppKit-managed `NSWindow` (hosting the SwiftUI `RootView`) rather
/// than a SwiftUI `Window` scene: for an LSUIElement / `.accessory` app there is no Dock or
/// app menu to open a scene window, and a `Window` scene's content isn't instantiated until
/// it's shown — making a reliable "open at launch + bring forward" awkward. An NSWindow we
/// own lets us reliably create, front, and re-focus it from both launch and the menu bar.
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var coordinator: DictationCoordinator?
    private var mainWindowController: MainWindowController?
    private var onboardingController: OnboardingWindowController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // @MainActor coordinator; we're on the main thread in this delegate callback.
        MainActor.assumeIsolated {
            #if DEBUG
            // Headless Tier-2 benchmark mode: run the datasets through the real pipeline and exit,
            // never surfacing UI. Debug-only; release builds don't compile the bench bridge.
            if let inv = BenchDump.parse(CommandLine.arguments) {
                Task { await BenchDump.run(inv); NSApp.terminate(nil) }
                return
            }
            #endif
            // The hotkey/coordinator and the main window controller exist for the whole session
            // regardless — but on first run we show guided setup first and the main window only
            // once it's done, so a new user isn't dropped into a Home screen that can't dictate.
            coordinator = DictationCoordinator()
            let controller = MainWindowController()
            mainWindowController = controller
            OpenWisprWindows.mainWindowController = controller
            OpenWisprWindows.replayOnboarding = { [weak self] in self?.showOnboarding() }

            // `--onboarding` forces the guided setup (for previewing / re-running it).
            let forceOnboarding = CommandLine.arguments.contains("--onboarding")
            if AppSettings.shared.hasCompletedOnboarding && !forceOnboarding {
                controller.showAndFocus()
            } else {
                showOnboarding()
            }
        }
    }

    /// Present first-run onboarding; on completion, persist the flag and surface the main window.
    @MainActor private func showOnboarding() {
        let onboarding = OnboardingWindowController { [weak self] in
            AppSettings.shared.hasCompletedOnboarding = true
            self?.onboardingController?.close()
            self?.onboardingController = nil
            self?.mainWindowController?.showAndFocus()
        }
        onboardingController = onboarding
        onboarding.showAndFocus()
    }
}

/// Hosts the first-run `OnboardingView` in a centered, fixed-size window. Like the main window
/// it's an AppKit `NSWindow` (an accessory app has no scene to open at launch).
@MainActor
final class OnboardingWindowController {
    private let window: NSWindow

    init(onFinish: @escaping () -> Void) {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 640, height: 560),
            styleMask: [.titled, .closable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.title = "Welcome to OpenWispr"
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
        window.isReleasedWhenClosed = false
        window.center()
        window.contentView = NSHostingView(rootView: OnboardingView(settings: .shared, onFinish: onFinish))
        self.window = window
    }

    func showAndFocus() {
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        window.orderFrontRegardless()
    }

    func close() { window.close() }
}

/// Entry points for opening/focusing the main window from anywhere (launch, menu bar).
enum OpenWisprWindows {
    @MainActor static weak var mainWindowController: MainWindowController?
    /// Set by `AppDelegate` so Settings can re-run the first-run onboarding flow.
    @MainActor static var replayOnboarding: (() -> Void)?

    @MainActor static func openMain() {
        mainWindowController?.showAndFocus()
    }
}

/// AppKit window controller that hosts the SwiftUI `RootView`. A single, reused window.
@MainActor
final class MainWindowController {
    private let window: NSWindow

    init() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 900, height: 640),
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.title = "OpenWispr"
        window.identifier = NSUserInterfaceItemIdentifier("main")
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = false
        window.minSize = NSSize(width: 820, height: 560)
        window.isReleasedWhenClosed = false
        window.center()
        window.setFrameAutosaveName("OpenWisprMain")
        window.contentView = NSHostingView(rootView: RootView())
        // Accessory apps: let the window join spaces and survive app deactivation.
        window.collectionBehavior = [.fullScreenPrimary]
        self.window = window
    }

    /// Show the window, bring it to the front, and activate the app so it surfaces for an
    /// accessory (LSUIElement) app — which otherwise has no Dock icon to click.
    func showAndFocus() {
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        window.orderFrontRegardless()
    }
}

/// Menu-bar entry point. OpenWispr lives in the status bar (LSUIElement, no Dock icon).
/// The popover's "Open OpenWispr" button surfaces the main "Home" window (`RootView`:
/// Home + Settings sidebar); clicking the mic drives the same end-to-end dictation flow.
///
/// An `AppDelegate` owns that window and also wires up a global hotkey (⌃⌥Z) for hands-free
/// dictation from any app via a non-activating HUD (`DictationCoordinator`).
@main
struct OpenWisprApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        MenuBarExtra("OpenWispr", systemImage: "mic.fill") {
            MenuBarContent()
        }
        .menuBarExtraStyle(.window)

        // The standalone Settings window kept for the popover's "Settings…" entry point
        // (the same Settings form is also embedded in the main window's sidebar).
        Window("OpenWispr Settings", id: "settings") {
            SettingsView(settings: .shared)
        }
        .defaultSize(width: 720, height: 760)
        .windowResizability(.contentMinSize)
    }
}

/// Compact menu-bar popover: a button that opens/focuses the main window, plus the live
/// dictation controls. Keeps the existing popover dictation flow intact.
private struct MenuBarContent: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button {
                OpenWisprWindows.openMain()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "macwindow")
                    Text("Open OpenWispr")
                    Spacer()
                }
                .font(OW.ui(13, weight: .semibold))
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(OWPrimaryButtonStyle())
            .padding(.horizontal, 18)
            .padding(.top, 16)

            Rectangle().fill(OW.divider).frame(height: 1)
                .padding(.horizontal, 14)

            DictationView()
        }
        .background(OW.bg)
    }
}
