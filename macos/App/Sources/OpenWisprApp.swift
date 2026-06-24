import AppKit
import SwiftUI

/// Owns the app-scope hands-free dictation session. Created at launch and retained for
/// the app's lifetime; its `init` registers the global ⌃⌥Space hotkey.
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var coordinator: DictationCoordinator?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // @MainActor coordinator; we're on the main thread in this delegate callback.
        MainActor.assumeIsolated {
            coordinator = DictationCoordinator()
        }
    }
}

/// Menu-bar entry point. OpenWispr lives in the status bar (LSUIElement, no Dock
/// icon); clicking the mic opens a window with the controls. `DictationView` drives the
/// real end-to-end flow: record (VAD auto-stop) → Apple Speech STT → deterministic cleanup.
///
/// In addition to the popover, an `AppDelegate` wires up a global hotkey (⌃⌥Space) for
/// hands-free dictation from any app via a non-activating HUD (`DictationCoordinator`).
@main
struct OpenWisprApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        MenuBarExtra("OpenWispr", systemImage: "mic.fill") {
            DictationView()
        }
        .menuBarExtraStyle(.window)
    }
}
