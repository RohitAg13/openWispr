import AppKit
import IOKit.hid
import ServiceManagement

/// Live macOS permission + launch-at-login state shared by the onboarding flow and the
/// Settings "Trigger & Shortcut" / "General" sections. None of these can be observed with KVO,
/// so callers poll them on a timer.
///
/// IMPORTANT — Input Monitoring: OpenWispr's global shortcut uses Carbon's
/// `RegisterEventHotKey` (see `HotKey.swift`), which the system delivers **without** Input
/// Monitoring or Accessibility. We surface Input Monitoring state only because the design calls
/// for it and a future `CGEventTap`-based trigger (e.g. push-to-talk) would need it — it is
/// presented as an optional enhancement and never gates the flow. The one hard permission for
/// the shortcut→insert pipeline is **Accessibility** (text insertion), handled separately.
enum MacPermissions {

    enum Access {
        case granted, denied, unknown
    }

    // MARK: - Accessibility (text insertion)

    /// Whether this process is trusted for the Accessibility API (drives auto-insert).
    static var accessibilityGranted: Bool { AXIsProcessTrusted() }

    /// Trigger the system Accessibility prompt and deep-link to the pane.
    static func requestAccessibility() {
        TextInserter.requestAccess()
        openPane("Privacy_Accessibility")
    }

    static func openAccessibilityPane() { openPane("Privacy_Accessibility") }

    // MARK: - Input Monitoring (optional)

    /// Current Input Monitoring (HID "listen event") authorization. Optional for OpenWispr today
    /// (the Carbon hotkey doesn't require it); shown for transparency.
    static var inputMonitoring: Access {
        switch IOHIDCheckAccess(kIOHIDRequestTypeListenEvent) {
        case kIOHIDAccessTypeGranted: return .granted
        case kIOHIDAccessTypeDenied:  return .denied
        default:                      return .unknown
        }
    }

    /// Ask the system to grant Input Monitoring (shows the prompt once, then no-ops).
    @discardableResult
    static func requestInputMonitoring() -> Bool {
        IOHIDRequestAccess(kIOHIDRequestTypeListenEvent)
    }

    static func openInputMonitoringPane() { openPane("Privacy_ListenEvent") }

    // MARK: - Helpers

    private static func openPane(_ anchor: String) {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?\(anchor)") {
            NSWorkspace.shared.open(url)
        }
    }
}

/// Launch-at-login toggle backed by `SMAppService` (macOS 13+). The system owns the truth, so
/// there's no UserDefaults mirror — we read `status` and register/unregister on toggle.
@MainActor
enum LaunchAtLogin {
    /// Whether the app is currently registered to launch at login.
    static var isEnabled: Bool {
        SMAppService.mainApp.status == .enabled
    }

    /// Register or unregister the main app as a login item. Failures are logged and swallowed so
    /// the UI toggle never throws into SwiftUI.
    static func setEnabled(_ enabled: Bool) {
        do {
            if enabled {
                if SMAppService.mainApp.status != .enabled {
                    try SMAppService.mainApp.register()
                }
            } else {
                if SMAppService.mainApp.status == .enabled {
                    try SMAppService.mainApp.unregister()
                }
            }
        } catch {
            NSLog("LaunchAtLogin: failed to set \(enabled): \(error.localizedDescription)")
        }
    }
}
