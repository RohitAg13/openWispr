import AppKit
import Carbon.HIToolbox
import SwiftUI

/// A SwiftUI control that captures the next key-down combo and reports it back as a
/// hardware virtual keycode + Carbon modifier mask — the exact pair `RegisterEventHotKey`
/// (see `HotKey`) wants. Click it to start recording; press a combo (≥1 modifier) to bind,
/// or Escape to cancel.
///
/// `NSEvent.keyCode` is the same hardware virtual keycode numbering Carbon's `kVK_*`
/// constants use, so it can be passed straight through. Only `modifierFlags` needs
/// translating from Cocoa's `NSEvent.ModifierFlags` to the Carbon mask.
struct HotKeyRecorder: NSViewRepresentable {
    @Binding var keyCode: UInt32
    @Binding var modifiers: UInt32

    func makeNSView(context: Context) -> RecorderView {
        let view = RecorderView()
        view.onCapture = { code, mods in
            keyCode = code
            modifiers = mods
        }
        view.refreshTitle(keyCode: keyCode, modifiers: modifiers)
        return view
    }

    func updateNSView(_ view: RecorderView, context: Context) {
        view.onCapture = { code, mods in
            keyCode = code
            modifiers = mods
        }
        // Reflect external changes (e.g. "Reset to default") while not actively recording.
        if !view.isRecording {
            view.refreshTitle(keyCode: keyCode, modifiers: modifiers)
        }
    }

    /// A focusable button-like view. While focused/recording it intercepts key events and
    /// turns the first valid combo into `(keyCode, carbonModifiers)`.
    final class RecorderView: NSView {
        var onCapture: ((UInt32, UInt32) -> Void)?
        private(set) var isRecording = false

        // Brand "paper" palette (mirrors the `OW` SwiftUI tokens, as NSColors).
        private static func owColor(_ hex: UInt32) -> NSColor {
            NSColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255,
                    green: CGFloat((hex >> 8) & 0xFF) / 255,
                    blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        }
        private static let chipColor = owColor(0xF7F1E9)
        private static let borderColor = owColor(0xE6E0D9)
        private static let coralColor = owColor(0xE58361)
        private static let textColor = owColor(0x45332B)
        private static let dimColor = owColor(0x7B675D)

        private let label: NSTextField = {
            let f = NSTextField(labelWithString: "")
            f.alignment = .center
            f.font = .monospacedSystemFont(ofSize: 13, weight: .medium)
            f.textColor = RecorderView.textColor
            f.lineBreakMode = .byTruncatingTail
            f.translatesAutoresizingMaskIntoConstraints = false
            return f
        }()

        private var currentKeyCode: UInt32 = HotKey.defaultKeyCode
        private var currentModifiers: UInt32 = HotKey.defaultModifiers

        override init(frame frameRect: NSRect) {
            super.init(frame: frameRect)
            wantsLayer = true
            layer?.cornerRadius = 10
            layer?.borderWidth = 1
            layer?.borderColor = RecorderView.borderColor.cgColor
            layer?.backgroundColor = RecorderView.chipColor.cgColor

            addSubview(label)
            NSLayoutConstraint.activate([
                label.centerXAnchor.constraint(equalTo: centerXAnchor),
                label.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 8),
                label.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -8),
                label.centerYAnchor.constraint(equalTo: centerYAnchor),
                widthAnchor.constraint(greaterThanOrEqualToConstant: 110),
                heightAnchor.constraint(equalToConstant: 26),
            ])
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

        override var acceptsFirstResponder: Bool { true }

        /// Update the displayed combo when not recording.
        func refreshTitle(keyCode: UInt32, modifiers: UInt32) {
            currentKeyCode = keyCode
            currentModifiers = modifiers
            label.stringValue = AppSettings.display(keyCode: keyCode, modifiers: modifiers)
        }

        // MARK: - Focus / recording

        override func mouseDown(with event: NSEvent) {
            window?.makeFirstResponder(self)
        }

        override func becomeFirstResponder() -> Bool {
            isRecording = true
            label.stringValue = "Press a combo…"
            label.textColor = RecorderView.dimColor
            layer?.borderColor = RecorderView.coralColor.cgColor
            layer?.borderWidth = 2
            return super.becomeFirstResponder()
        }

        override func resignFirstResponder() -> Bool {
            endRecording()
            return super.resignFirstResponder()
        }

        private func endRecording() {
            isRecording = false
            layer?.borderColor = RecorderView.borderColor.cgColor
            layer?.borderWidth = 1
            label.textColor = RecorderView.textColor
            label.stringValue = AppSettings.display(
                keyCode: currentKeyCode, modifiers: currentModifiers)
        }

        override func keyDown(with event: NSEvent) {
            guard isRecording else {
                super.keyDown(with: event)
                return
            }

            // Escape cancels recording without changing the binding.
            if event.keyCode == UInt32(kVK_Escape) {
                window?.makeFirstResponder(nil)
                return
            }

            let carbon = Self.carbonModifiers(from: event.modifierFlags)
            // Require at least one modifier — a bare key would be useless as a global
            // hotkey and would steal the plain keystroke everywhere.
            guard carbon != 0 else {
                NSSound.beep()
                return
            }

            let code = UInt32(event.keyCode)
            currentKeyCode = code
            currentModifiers = carbon
            onCapture?(code, carbon)
            window?.makeFirstResponder(nil) // commit + resign
        }

        /// Translate Cocoa modifier flags to a Carbon modifier mask, keeping only the
        /// four meaningful global-hotkey modifiers.
        static func carbonModifiers(from flags: NSEvent.ModifierFlags) -> UInt32 {
            var mask: UInt32 = 0
            if flags.contains(.control) { mask |= UInt32(controlKey) }
            if flags.contains(.option)  { mask |= UInt32(optionKey) }
            if flags.contains(.command) { mask |= UInt32(cmdKey) }
            if flags.contains(.shift)   { mask |= UInt32(shiftKey) }
            return mask
        }
    }
}
