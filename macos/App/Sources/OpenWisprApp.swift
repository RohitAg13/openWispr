import SwiftUI

/// Menu-bar entry point. OpenWispr lives in the status bar (LSUIElement, no Dock
/// icon); clicking the mic opens a window with the controls. The dictation
/// pipeline (audio → STT → cleanup → insert) will hang off this shell; for now it
/// surfaces the deterministic cleanup core so the wiring to OpenWisprCore is real.
@main
struct OpenWisprApp: App {
    var body: some Scene {
        MenuBarExtra("OpenWispr", systemImage: "mic.fill") {
            CleanupDemoView()
                .frame(width: 440)
        }
        .menuBarExtraStyle(.window)
    }
}
