import AppKit
import ApplicationServices

/// Inserts cleaned dictation text into the focused field of the frontmost app the user was
/// working in (not our menu-bar popover). Uses the Accessibility API as the primary path and
/// a synthesized ⌘V paste as a fallback for apps that ignore AX `selectedText` writes.
///
/// Requires the app to be (a) non-sandboxed and (b) granted Accessibility access by the user
/// in System Settings ▸ Privacy & Security ▸ Accessibility. Never throws; degrades to `false`.
enum TextInserter {

    /// Whether this process is currently trusted for Accessibility.
    static var isTrusted: Bool { AXIsProcessTrusted() }

    /// Trigger the system Accessibility prompt (and deep-link to the pane). Safe to call
    /// repeatedly; macOS only shows the prompt once per app until reset.
    static func requestAccess() {
        let options = [kAXTrustedCheckOptionPrompt.takeRetainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
    }

    /// Insert `text` into the focused field of `app`. Returns `true` if either the AX path or
    /// the paste fallback was dispatched, `false` if we can't act (e.g. not trusted).
    @discardableResult
    static func insert(_ text: String, into app: NSRunningApplication?) -> Bool {
        guard isTrusted else { return false }
        guard !text.isEmpty else { return false }

        // 1. Bring the target app forward and let focus settle before we touch it.
        if let app = app, !app.isActive {
            app.activate(options: [])
            spin(milliseconds: 100)
        }

        // 2. Primary path: write the AX selected text of the focused element. This inserts at
        //    the caret (or replaces the current selection) without disturbing the pasteboard.
        if insertViaAccessibility(text) {
            return true
        }

        // 3. Fallback: paste via a synthesized ⌘V, preserving the user's pasteboard.
        return insertViaPaste(text)
    }

    // MARK: - AX path

    private static func insertViaAccessibility(_ text: String) -> Bool {
        let systemWide = AXUIElementCreateSystemWide()

        var focused: CFTypeRef?
        let copyResult = AXUIElementCopyAttributeValue(
            systemWide, kAXFocusedUIElementAttribute as CFString, &focused
        )
        guard copyResult == .success, let focusedRef = focused else { return false }
        // CFTypeRef from a UI element attribute is always an AXUIElement here.
        let element = focusedRef as! AXUIElement

        let setResult = AXUIElementSetAttributeValue(
            element, kAXSelectedTextAttribute as CFString, text as CFString
        )
        return setResult == .success
    }

    // MARK: - Paste fallback

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
