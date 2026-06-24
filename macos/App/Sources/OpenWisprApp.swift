import SwiftUI

/// Menu-bar entry point. OpenWispr lives in the status bar (LSUIElement, no Dock
/// icon); clicking the mic opens a window with the controls. `DictationView` drives the
/// real end-to-end flow: record (VAD auto-stop) → Apple Speech STT → deterministic cleanup.
@main
struct OpenWisprApp: App {
    var body: some Scene {
        MenuBarExtra("OpenWispr", systemImage: "mic.fill") {
            DictationView()
        }
        .menuBarExtraStyle(.window)
    }
}
