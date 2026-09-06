import AppKit
import Carbon.HIToolbox

/// Watches user-selected keys process-wide and routes them to dictation:
///
///  - **Double-click** (two presses within `doubleClickInterval`) → toggle hands-free dictation.
///    Single presses are ignored; key release does nothing.
///  - **Push-to-talk** — key down starts after a `pttHoldGuard` hold; key up finishes.
///    Speech pauses while holding do not end the session.
///
/// The 🌐/fn key (`kVK_Function`) is observed via `.flagsChanged`; all other bindings use
/// `.keyDown` / `.keyUp`. Input Monitoring (or the local monitor while focused) is required
/// for global capture.
@MainActor
final class FnTriggerMonitor {

    var onDoubleClickToggle: () -> Void = {}
    var onPTTStart: () -> Void = {}
    var onPTTFinish: () -> Void = {}

    var doubleClickEnabled = false
    var doubleClickKeyCode: UInt32 = AppSettings.defaultDoubleClickKeyCode
    var doubleClickKeyModifiers: UInt32 = 0

    var pushToTalkEnabled = false
    var pttKeyCode: UInt32 = AppSettings.defaultPTTKeyCode
    var pttKeyModifiers: UInt32 = 0

    private var globalMonitor: Any?
    private var localMonitor: Any?

    private let doubleClickInterval: TimeInterval = 0.40
    /// Long enough to avoid accidental PTT from brief Option/Globe taps while typing.
    private let pttHoldGuard: TimeInterval = 0.28

    private var lastClickTime: TimeInterval?
    private var pendingClickTimer: Timer?

    private var pttHoldTimer: Timer?
    /// Monitor-side: PTT hold timer fired and `onPTTStart` was dispatched.
    private var pttActivated = false
    private var pttKeyIsDown = false

    // MARK: - Lifecycle

    func start() {
        stop()
        let mask: NSEvent.EventTypeMask = [.flagsChanged, .keyDown, .keyUp]
        globalMonitor = NSEvent.addGlobalMonitorForEvents(matching: mask) { [weak self] event in
            MainActor.assumeIsolated { self?.handle(event) }
        }
        localMonitor = NSEvent.addLocalMonitorForEvents(matching: mask) { [weak self] event in
            MainActor.assumeIsolated { self?.handle(event) }
            return event
        }
    }

    func stop() {
        if let g = globalMonitor { NSEvent.removeMonitor(g); globalMonitor = nil }
        if let l = localMonitor { NSEvent.removeMonitor(l); localMonitor = nil }
        resetClickState()
        cancelPTTHold()
        pttActivated = false
        pttKeyIsDown = false
    }

    /// Called when a session ends so a pending first press doesn't pair with the next press.
    func sessionDidEnd() {
        resetClickState()
        pttActivated = false
        // Leave `pttKeyIsDown` alone — the user may still be holding the key; key-up clears it.
    }

    // MARK: - Event routing

    private func handle(_ event: NSEvent) {
        let code = UInt32(event.keyCode)
        let carbonMods = HotKeyRecorder.carbonModifiers(from: event.modifierFlags)

        if event.type == .flagsChanged {
            handleFlagsChanged(code: code, modifiers: carbonMods, flags: event.modifierFlags,
                               at: event.timestamp)
            return
        }

        switch event.type {
        case .keyDown:
            guard !event.isARepeat else { return }
            // Option+letter (or any combo) while a PTT hold is pending → typing, not PTT.
            if pttHoldTimer != nil, !isPTTBinding(code: code, modifiers: carbonMods) {
                cancelPendingPTT()
            }
            keyDown(code: code, modifiers: carbonMods, at: event.timestamp)
        case .keyUp:
            keyUp(code: code, modifiers: carbonMods)
        default:
            break
        }
    }

    private func handleFlagsChanged(code: UInt32, modifiers: UInt32, flags: NSEvent.ModifierFlags,
                                    at t: TimeInterval) {
        if code == UInt32(kVK_Function) {
            if flags.contains(.function) {
                keyDown(code: code, modifiers: modifiers, at: t)
            } else {
                keyUp(code: code, modifiers: modifiers)
            }
            return
        }

        guard AppSettings.isStandaloneModifierTriggerKey(code) else { return }
        if AppSettings.modifierKeyIsPressed(code: code, flags: flags) {
            keyDown(code: code, modifiers: modifiers, at: t)
        } else {
            keyUp(code: code, modifiers: modifiers)
        }
    }

    private func keyDown(code: UInt32, modifiers: UInt32, at t: TimeInterval) {
        let pttMatch = isPTTBinding(code: code, modifiers: modifiers)
        let dcMatch = isDoubleClickBinding(code: code, modifiers: modifiers)

        if pushToTalkEnabled, pttMatch {
            guard !pttKeyIsDown else { return }
            pttKeyIsDown = true
            cancelPTTHold()
            pttHoldTimer = Timer.scheduledTimer(withTimeInterval: pttHoldGuard, repeats: false) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self, self.pttKeyIsDown, !self.pttActivated else { return }
                    self.resetClickState()
                    self.pttActivated = true
                    self.onPTTStart()
                }
            }
        }

        // Don't count PTT-key presses toward double-click while a hold is active or PTT is live.
        if doubleClickEnabled, dcMatch, !pttActivated, !(pttMatch && pttKeyIsDown) {
            registerDoubleClick(at: t)
        }
    }

    private func keyUp(code: UInt32, modifiers: UInt32) {
        if pushToTalkEnabled, isPTTBinding(code: code, modifiers: modifiers) {
            let wasActivated = pttActivated
            pttKeyIsDown = false
            cancelPTTHold()
            pttActivated = false
            if wasActivated {
                onPTTFinish()
            }
        }
    }

    private func registerDoubleClick(at t: TimeInterval) {
        if pttActivated { return }

        if let last = lastClickTime, t - last <= doubleClickInterval {
            resetClickState()
            cancelPTTHold()
            onDoubleClickToggle()
            return
        }

        lastClickTime = t
        pendingClickTimer?.invalidate()
        pendingClickTimer = Timer.scheduledTimer(withTimeInterval: doubleClickInterval, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.resetClickState() }
        }
    }

    private func isPTTBinding(code: UInt32, modifiers: UInt32) -> Bool {
        pushToTalkEnabled && matchesBinding(code: code, modifiers: modifiers,
                                            keyCode: pttKeyCode, keyModifiers: pttKeyModifiers)
    }

    private func isDoubleClickBinding(code: UInt32, modifiers: UInt32) -> Bool {
        doubleClickEnabled && matchesBinding(code: code, modifiers: modifiers,
                                             keyCode: doubleClickKeyCode, keyModifiers: doubleClickKeyModifiers)
    }

    private func cancelPendingPTT() {
        cancelPTTHold()
        pttKeyIsDown = false
        pttActivated = false
    }

    private func matchesBinding(code: UInt32, modifiers: UInt32,
                                keyCode: UInt32, keyModifiers: UInt32) -> Bool {
        guard code == keyCode else { return false }
        let relevant: UInt32 = UInt32(controlKey | optionKey | shiftKey | cmdKey)
        let eventMods = stripSelfModifier(code: code, from: modifiers) & relevant
        let bindingMods = stripSelfModifier(code: keyCode, from: keyModifiers) & relevant
        return eventMods == bindingMods
    }

    private func stripSelfModifier(code: UInt32, from mods: UInt32) -> UInt32 {
        var m = mods
        switch code {
        case UInt32(kVK_Option), UInt32(kVK_RightOption): m &= ~UInt32(optionKey)
        case UInt32(kVK_Shift), UInt32(kVK_RightShift):     m &= ~UInt32(shiftKey)
        case UInt32(kVK_Control), UInt32(kVK_RightControl):  m &= ~UInt32(controlKey)
        case UInt32(kVK_Command), UInt32(kVK_RightCommand):  m &= ~UInt32(cmdKey)
        default: break
        }
        return m
    }

    private func resetClickState() {
        lastClickTime = nil
        pendingClickTimer?.invalidate()
        pendingClickTimer = nil
    }

    private func cancelPTTHold() {
        pttHoldTimer?.invalidate()
        pttHoldTimer = nil
    }
}
