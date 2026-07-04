import AppKit
import Carbon.HIToolbox

/// Watches the **fn / 🌐 (Globe)** key process-wide and turns raw press/release events into the
/// two dictation gestures:
///
///  - **Hold** fn (press and keep it down past `holdMin`) → *push-to-talk*: `onStart` fires on the
///    press, `onFinish` on the release.
///  - **Double-tap** fn (or a single quick tap) → *hands-free*: `onStart` fires on the first press
///    and the session stays open (VAD auto-stop, or the next fn press) — `onFinish` then fires.
///
/// Only the physical fn key (`kVK_Function`, keycode 63) is matched, via `.flagsChanged`. Arrow /
/// F-keys also set the `.function` modifier flag but arrive as `.keyDown` (which we don't observe),
/// so they never trip this. Press vs. release is read from whether `.function` is still set.
///
/// Observing keys in *other* apps needs the app to be trusted for **Accessibility / Input
/// Monitoring** (the same grant dictation already needs to type text). The local monitor covers our
/// own windows so the gesture still works when OpenWispr is focused even before the grant lands.
@MainActor
final class FnTriggerMonitor {

    /// Begin a listening session (idempotent — the coordinator ignores it if already listening).
    var onStart: () -> Void = {}
    /// Finish the session: stop, transcribe, insert.
    var onFinish: () -> Void = {}

    private var globalMonitor: Any?
    private var localMonitor: Any?

    /// Below this the press is a "tap" (→ hands-free); at or above it, a "hold" (→ push-to-talk).
    private let holdMin: TimeInterval = 0.22
    /// A second tap within this window of the first confirms a double-tap; otherwise the lone tap
    /// still latches hands-free once the window lapses.
    private let doubleWindow: TimeInterval = 0.30

    private enum Mode { case idle, active }
    private var mode: Mode = .idle
    /// Hands-free latched: the session ignores fn releases and stops on the next fn press.
    private var locked = false
    private var awaitingSecondTap = false
    private var downTime: TimeInterval = 0
    private var tapTimer: Timer?

    // MARK: - Lifecycle

    func start() {
        stop()
        // NSEvent monitor callbacks are delivered on the main thread, so hop onto the main actor
        // synchronously rather than spawning a Task (which would need to send the non-Sendable event).
        globalMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.flagsChanged]) { [weak self] event in
            MainActor.assumeIsolated { self?.handle(event) }
        }
        localMonitor = NSEvent.addLocalMonitorForEvents(matching: [.flagsChanged]) { [weak self] event in
            MainActor.assumeIsolated { self?.handle(event) }
            return event
        }
    }

    func stop() {
        if let g = globalMonitor { NSEvent.removeMonitor(g); globalMonitor = nil }
        if let l = localMonitor { NSEvent.removeMonitor(l); localMonitor = nil }
        reset()
    }

    /// Called by the coordinator whenever a session ends by any path (VAD auto-stop, error, max
    /// duration) so a latched hands-free session doesn't leave us out of sync — the next fn press
    /// then starts fresh instead of no-op'ing a session that's already gone.
    func sessionDidEnd() { reset() }

    // MARK: - Event routing

    private func handle(_ event: NSEvent) {
        guard event.keyCode == UInt16(kVK_Function) else { return }
        if event.modifierFlags.contains(.function) {
            fnDown(at: event.timestamp)
        } else {
            fnUp(at: event.timestamp)
        }
    }

    private func fnDown(at t: TimeInterval) {
        // A press during a latched hands-free session stops it.
        if mode == .active && locked {
            onFinish()
            reset()
            return
        }
        // Second tap of a double-tap → latch hands-free (session already open from the first press).
        if mode == .active && awaitingSecondTap {
            awaitingSecondTap = false
            tapTimer?.invalidate(); tapTimer = nil
            locked = true
            return
        }
        guard mode == .idle else { return }
        mode = .active
        locked = false
        downTime = t
        onStart()
    }

    private func fnUp(at t: TimeInterval) {
        guard mode == .active, !locked else { return }
        let held = t - downTime
        if held >= holdMin {
            // Push-to-talk release → stop + insert.
            onFinish()
            reset()
        } else {
            // Quick tap: wait one double-tap window. A second press latches immediately (handled in
            // fnDown); if none comes, the lone tap still latches hands-free.
            awaitingSecondTap = true
            tapTimer?.invalidate()
            tapTimer = Timer.scheduledTimer(withTimeInterval: doubleWindow, repeats: false) { [weak self] _ in
                MainActor.assumeIsolated { self?.confirmLoneTapLatch() }
            }
        }
    }

    private func confirmLoneTapLatch() {
        guard mode == .active, awaitingSecondTap else { return }
        awaitingSecondTap = false
        locked = true
    }

    private func reset() {
        mode = .idle
        locked = false
        awaitingSecondTap = false
        tapTimer?.invalidate()
        tapTimer = nil
    }
}
