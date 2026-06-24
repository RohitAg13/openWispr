import Carbon.HIToolbox
import Foundation

/// Thin Swift wrapper over Carbon's process-wide global hotkey API
/// (`RegisterEventHotKey`). Unlike `CGEventTap`-based capture, this needs **no**
/// Input-Monitoring / Accessibility permission — the system delivers a Carbon
/// event when the combo is pressed in any app.
///
/// The Carbon callback is a C function pointer that can't capture Swift context,
/// so we route by id: every `HotKey` registers under a unique `EventHotKeyID` and
/// registers itself in a static `registry` keyed by that id. The single shared
/// event handler looks the id up and calls the matching instance's handler.
///
/// macOS-13 note: `RegisterEventHotKey` / `InstallEventHandler` are old Carbon
/// APIs but are **not deprecated** and remain the supported way to get a global
/// hotkey without input-monitoring entitlements. We keep all Carbon usage isolated
/// here. Registration failures are logged and no-op'd so the app never crashes.
final class HotKey {

    /// The app's default dictation combo: ⌃⌥Space (control+option+space).
    /// Change here to re-map the global hotkey everywhere.
    static let defaultKeyCode: UInt32 = UInt32(kVK_Space) // 49
    static let defaultModifiers: UInt32 = UInt32(controlKey | optionKey)

    // MARK: - Static dispatch registry

    /// Maps an `EventHotKeyID.id` to the owning instance. The C callback can't
    /// capture `self`, so it looks the instance up here.
    private static var registry: [UInt32: HotKey] = [:]
    /// Monotonic id source so every registered hotkey is unique.
    private static var nextID: UInt32 = 1
    /// The single process-wide Carbon event handler (installed lazily, once).
    private static var sharedHandler: EventHandlerRef?

    /// Four-char-code "signature" shared by all our hotkeys (id disambiguates them).
    private static let signature: OSType = {
        // 'OWHK' — OpenWispr HotKey.
        let chars: [UInt8] = Array("OWHK".utf8)
        return chars.reduce(0) { ($0 << 8) + OSType($1) }
    }()

    // MARK: - Instance state

    private let handler: () -> Void
    private let id: UInt32
    private var hotKeyRef: EventHotKeyRef?

    /// Register a global hotkey. `keyCode` is a Carbon virtual key code (`kVK_*`),
    /// `modifiers` is an OR of Carbon mod masks (`cmdKey`, `optionKey`, …).
    init(keyCode: UInt32 = HotKey.defaultKeyCode,
         modifiers: UInt32 = HotKey.defaultModifiers,
         handler: @escaping () -> Void) {
        self.handler = handler
        self.id = HotKey.nextID
        HotKey.nextID += 1
        HotKey.registry[self.id] = self

        HotKey.installSharedHandlerIfNeeded()

        let hotKeyID = EventHotKeyID(signature: HotKey.signature, id: self.id)
        var ref: EventHotKeyRef?
        let status = RegisterEventHotKey(
            keyCode,
            modifiers,
            hotKeyID,
            GetApplicationEventTarget(),
            0,
            &ref
        )
        if status == noErr {
            self.hotKeyRef = ref
        } else {
            NSLog("HotKey: RegisterEventHotKey failed (status \(status)); hotkey disabled.")
            HotKey.registry[self.id] = nil
        }
    }

    deinit {
        if let ref = hotKeyRef {
            UnregisterEventHotKey(ref)
        }
        HotKey.registry[id] = nil
        // We intentionally leave the shared handler installed for the app's lifetime;
        // it's a single cheap handler and re-installing per-hotkey would be churn.
    }

    // MARK: - Carbon plumbing

    /// Install the single shared `kEventHotKeyPressed` handler exactly once.
    private static func installSharedHandlerIfNeeded() {
        guard sharedHandler == nil else { return }

        var spec = EventTypeSpec(
            eventClass: OSType(kEventClassKeyboard),
            eventKind: UInt32(kEventHotKeyPressed)
        )

        let callback: EventHandlerUPP = { _, event, _ -> OSStatus in
            guard let event = event else { return OSStatus(eventNotHandledErr) }

            var hkID = EventHotKeyID()
            let status = GetEventParameter(
                event,
                EventParamName(kEventParamDirectObject),
                EventParamType(typeEventHotKeyID),
                nil,
                MemoryLayout<EventHotKeyID>.size,
                nil,
                &hkID
            )
            guard status == noErr else { return status }

            // Hop to main: handlers (start/stop dictation, show HUD) touch UI.
            if let hotKey = HotKey.registry[hkID.id] {
                DispatchQueue.main.async { hotKey.handler() }
                return noErr
            }
            return OSStatus(eventNotHandledErr)
        }

        var ref: EventHandlerRef?
        let status = InstallEventHandler(
            GetApplicationEventTarget(),
            callback,
            1,
            &spec,
            nil,
            &ref
        )
        if status == noErr {
            sharedHandler = ref
        } else {
            NSLog("HotKey: InstallEventHandler failed (status \(status)).")
        }
    }
}
