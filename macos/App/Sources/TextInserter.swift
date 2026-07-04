import AppKit
import ApplicationServices

/// Inserts cleaned dictation text into the focused field of the frontmost app the user was
/// working in (not our menu-bar popover). Inserts via a synthesized ⌘V paste, which lands at the
/// caret (replacing any selection) universally — native Cocoa fields, web content (browsers),
/// terminals, and Electron apps alike.
///
/// We deliberately do *not* use the AX `kAXSelectedTextAttribute` write: it only works in native
/// Cocoa text and, worse, many non-native apps (browsers, terminals) return `.success` from the
/// write while silently dropping the text — so it can't even be used as a trustworthy primary with
/// a paste fallback. Paste is the one path that works everywhere the user could paste by hand.
///
/// Requires the app to be (a) non-sandboxed and (b) granted Accessibility access by the user in
/// System Settings ▸ Privacy & Security ▸ Accessibility (needed to post the synthetic keystroke).
/// Never throws; degrades to `false`.
enum TextInserter {

    /// Whether this process is currently trusted for Accessibility.
    static var isTrusted: Bool { AXIsProcessTrusted() }

    /// Trigger the system Accessibility prompt (and deep-link to the pane). Safe to call
    /// repeatedly; macOS only shows the prompt once per app until reset.
    static func requestAccess() {
        let options = [kAXTrustedCheckOptionPrompt.takeRetainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
    }

    /// Insert `text` into the focused field of `app`. Returns `true` if the paste was dispatched,
    /// `false` if we can't act (e.g. not trusted, or the keystroke couldn't be synthesized).
    @discardableResult
    static func insert(_ text: String, into app: NSRunningApplication?) -> Bool {
        guard isTrusted else { return false }
        guard !text.isEmpty else { return false }

        // Bring the target app forward and let focus settle before we paste into it.
        if let app = app, !app.isActive {
            app.activate(options: [])
            spin(milliseconds: 100)
        }

        // Paste at the caret (replacing any selection), preserving the user's pasteboard.
        return insertViaPaste(text)
    }

    // MARK: - Paste

    private static func insertViaPaste(_ text: String) -> Bool {
        let pasteboard = NSPasteboard.general
        // Save the current string contents so we can restore them after the paste lands.
        let previous = pasteboard.string(forType: .string)

        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)

        guard postCommandV() else {
            // Couldn't synthesize the event; restore immediately and report failure.
            restore(previous, to: pasteboard)
            return false
        }

        // Let the paste complete before restoring; the target reads the pasteboard async.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) {
            restore(previous, to: pasteboard)
        }
        return true
    }

    private static func restore(_ previous: String?, to pasteboard: NSPasteboard) {
        pasteboard.clearContents()
        if let previous = previous {
            pasteboard.setString(previous, forType: .string)
        }
    }

    /// Synthesize a ⌘V key press to the HID event tap. keycode 9 == 'v'.
    private static func postCommandV() -> Bool {
        let source = CGEventSource(stateID: .combinedSessionState)
        let vKey: CGKeyCode = 9

        guard
            let keyDown = CGEvent(keyboardEventSource: source, virtualKey: vKey, keyDown: true),
            let keyUp = CGEvent(keyboardEventSource: source, virtualKey: vKey, keyDown: false)
        else {
            return false
        }
        keyDown.flags = .maskCommand
        keyUp.flags = .maskCommand
        keyDown.post(tap: .cghidEventTap)
        keyUp.post(tap: .cghidEventTap)
        return true
    }

    // MARK: - Helpers

    /// Spin the run loop briefly so an app activation / focus change can settle, without
    /// blocking the main thread outright.
    private static func spin(milliseconds: Int) {
        let deadline = Date().addingTimeInterval(Double(milliseconds) / 1000.0)
        RunLoop.current.run(until: deadline)
    }
}
