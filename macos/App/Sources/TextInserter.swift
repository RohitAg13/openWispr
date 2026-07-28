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
/// Never throws; reports what happened via `Outcome` and never leaves the text nowhere.
enum TextInserter {

    /// What actually happened to the text. Three cases, not two: "we dispatched a ⌘V but
    /// couldn't see it land" is a real and common outcome (the target exposes no usable AX,
    /// or is slow, or the grant was revoked mid-flight), and collapsing it into "inserted"
    /// is what used to silently destroy dictations — we'd restore the previous pasteboard
    /// 120 ms later over a paste that never happened, leaving the user with nothing at all.
    enum Outcome {
        /// Verified: the focused field changed right after our keystroke.
        case inserted
        /// Dispatched, but unconfirmable. The transcript is left on the pasteboard.
        case unverified
        /// Couldn't even dispatch. The transcript is left on the pasteboard.
        case failed
    }

    /// Whether this process is currently trusted for Accessibility.
    static var isTrusted: Bool { AXIsProcessTrusted() }

    /// Trigger the system Accessibility prompt (and deep-link to the pane). Safe to call
    /// repeatedly; macOS only shows the prompt once per app until reset.
    static func requestAccess() {
        let options = [kAXTrustedCheckOptionPrompt.takeRetainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
    }

    /// Insert `text` into the focused field of `app`.
    ///
    /// The caller must handle every `Outcome`: on anything but `.inserted` the text is sitting
    /// on the pasteboard and the user needs to be told so.
    @discardableResult
    static func insert(_ text: String, into app: NSRunningApplication?) -> Outcome {
        guard !text.isEmpty else { return .failed }
        guard isTrusted else { return .failed }

        // Bring the target app forward and let focus settle before we paste into it.
        if let app = app, !app.isActive {
            app.activate(options: [])
            spin(milliseconds: 100)
        }

        return insertViaPaste(text, into: app)
    }

    // MARK: - Paste

    private static func insertViaPaste(_ text: String, into app: NSRunningApplication?) -> Outcome {
        let pasteboard = NSPasteboard.general
        // Save the current string contents so we can put them back — but *only* if we can see
        // the paste land. Losing the user's dictation is worse than a one-off clipboard write
        // they can clear, so an unconfirmed paste keeps the transcript where they can reach it.
        let previous = pasteboard.string(forType: .string)
        let lengthBefore = focusedTextLength(of: app)

        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)

        guard postCommandV() else { return .failed }

        guard didFieldChange(from: lengthBefore, in: app) else { return .unverified }

        restore(previous, to: pasteboard)
        return .inserted
    }

    /// Watch the focused field for a beat and report whether it changed after our keystroke.
    ///
    /// This is the only evidence available that works across native fields, browsers, terminals
    /// and Electron alike — `postCommandV` returning true means the *event was constructed*, not
    /// that anything received it. When the target exposes no readable length (plenty don't) we
    /// return false, i.e. "couldn't confirm", which is the safe answer.
    private static func didFieldChange(from before: Int?, in app: NSRunningApplication?) -> Bool {
        guard let before = before else { return false }
        // ~360 ms total: long enough for a slow Electron app to consume the paste, short enough
        // that the user doesn't notice us waiting.
        for _ in 0..<9 {
            spin(milliseconds: 40)
            if let now = focusedTextLength(of: app), now != before { return true }
        }
        return false
    }

    /// Character count of the target app's focused element, or nil when it doesn't say.
    /// `kAXNumberOfCharacters` is cheap and widely supported; `kAXValue` is the fallback.
    private static func focusedTextLength(of app: NSRunningApplication?) -> Int? {
        guard let pid = app?.processIdentifier else { return nil }
        let appElement = AXUIElementCreateApplication(pid)

        var focused: CFTypeRef?
        guard
            AXUIElementCopyAttributeValue(appElement, kAXFocusedUIElementAttribute as CFString, &focused) == .success,
            let element = focused, CFGetTypeID(element) == AXUIElementGetTypeID()
        else { return nil }
        // swiftlint:disable:next force_cast
        let field = element as! AXUIElement

        var count: CFTypeRef?
        if AXUIElementCopyAttributeValue(field, kAXNumberOfCharactersAttribute as CFString, &count) == .success,
           let n = count as? Int {
            return n
        }
        var value: CFTypeRef?
        if AXUIElementCopyAttributeValue(field, kAXValueAttribute as CFString, &value) == .success,
           let s = value as? String {
            return s.count
        }
        return nil
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
