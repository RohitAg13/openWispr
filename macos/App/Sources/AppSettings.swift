import Carbon.HIToolbox
import Combine
import Foundation

/// STT backend choice. Only `.appleSpeech` is wired up today; `.whisper` is scaffolding
/// for a later whisper.cpp step.
enum STTProvider: String, CaseIterable {
    case appleSpeech
    case whisper

    var label: String {
        switch self {
        case .appleSpeech: return "Apple Speech"
        case .whisper:     return "Whisper (on-device)"
        }
    }

    /// Whether this provider is selectable. Whisper is now wired up — but it still needs its
    /// model downloaded before a session can use it (the Settings UI prompts for that).
    var isAvailable: Bool {
        switch self {
        case .appleSpeech: return true
        case .whisper:     return true
        }
    }
}

/// How aggressively to clean up / rewrite the transcript. Only `.off` (deterministic-only)
/// works today; the LLM-backed levels are scaffolding for a later step.
enum PolishLevel: String, CaseIterable {
    case off
    case light
    case medium
    case full

    var label: String {
        switch self {
        case .off:    return "Off (deterministic only)"
        case .light:  return "Light"
        case .medium: return "Medium"
        case .full:   return "Full"
        }
    }

    /// Whether this level is selectable. All levels are wired now (the LLM-backed ones still
    /// need their model downloaded — the Settings UI prompts for that).
    var isAvailable: Bool { true }
}

/// Microphone VAD sensitivity preset. Maps to the energy-VAD ramp ratios; higher
/// sensitivity stops sooner on pauses (lower ratios = speech detected at lower energy).
enum VADSensitivity: String, CaseIterable {
    case low
    case medium
    case high

    var label: String {
        switch self {
        case .low:    return "Low"
        case .medium: return "Medium"
        case .high:   return "High"
        }
    }

    var isAvailable: Bool { true }

    /// Energy-VAD ramp ratios (`lowRatio`, `highRatio`) for this preset.
    var ratios: (low: Float, high: Float) {
        switch self {
        case .low:    return (3.5, 10)
        case .medium: return (2.5, 8)
        case .high:   return (1.8, 6)
        }
    }

    /// Silero VAD speech start/end probability thresholds for this preset. Higher sensitivity =
    /// lower end-threshold, so a pause reads as silence sooner (stops sooner). Defaults
    /// (medium) match the segmenter's 0.5 / 0.35 hysteresis.
    var sileroProbs: (start: Float, end: Float) {
        switch self {
        case .low:    return (0.60, 0.45)
        case .medium: return (0.50, 0.35)
        case .high:   return (0.45, 0.28)
        }
    }
}

/// Persisted, observable app settings. Single shared instance; `@Published` properties
/// load from `UserDefaults` in `init` and write back on `didSet`. The coordinator and the
/// settings UI both bind to `.shared`, so a change in one is seen everywhere.
@MainActor
final class AppSettings: ObservableObject {
    static let shared = AppSettings()

    private let defaults: UserDefaults

    private enum Key {
        static let hotKeyCode = "hotKeyCode"
        static let hotKeyModifiers = "hotKeyModifiers"
        static let sttProvider = "sttProvider"
        static let whisperModel = "whisperModel"
        static let polishLevel = "polishLevel"
        static let llmModel = "llmModel"
        static let vadSensitivity = "vadSensitivity"
    }

    // MARK: - Persisted properties

    @Published var hotKeyCode: UInt32 {
        didSet { defaults.set(Int(hotKeyCode), forKey: Key.hotKeyCode) }
    }

    @Published var hotKeyModifiers: UInt32 {
        didSet { defaults.set(Int(hotKeyModifiers), forKey: Key.hotKeyModifiers) }
    }

    @Published var sttProvider: STTProvider {
        didSet { defaults.set(sttProvider.rawValue, forKey: Key.sttProvider) }
    }

    /// Which whisper.cpp model the Whisper engine uses. Only relevant when
    /// `sttProvider == .whisper`.
    @Published var whisperModel: WhisperModel {
        didSet { defaults.set(whisperModel.rawValue, forKey: Key.whisperModel) }
    }

    @Published var polishLevel: PolishLevel {
        didSet { defaults.set(polishLevel.rawValue, forKey: Key.polishLevel) }
    }

    /// Which on-device LLM the polish step uses. Only relevant when `polishLevel != .off`.
    @Published var llmModel: LlmModel {
        didSet { defaults.set(llmModel.rawValue, forKey: Key.llmModel) }
    }

    @Published var vadSensitivity: VADSensitivity {
        didSet { defaults.set(vadSensitivity.rawValue, forKey: Key.vadSensitivity) }
    }

    // MARK: - Init

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults

        // Hotkey: stored as Int (UserDefaults has no UInt32). 0 / absent → default.
        if defaults.object(forKey: Key.hotKeyCode) != nil {
            hotKeyCode = UInt32(defaults.integer(forKey: Key.hotKeyCode))
        } else {
            hotKeyCode = HotKey.defaultKeyCode
        }
        if defaults.object(forKey: Key.hotKeyModifiers) != nil {
            hotKeyModifiers = UInt32(defaults.integer(forKey: Key.hotKeyModifiers))
        } else {
            hotKeyModifiers = HotKey.defaultModifiers
        }

        sttProvider = defaults.string(forKey: Key.sttProvider)
            .flatMap(STTProvider.init(rawValue:)) ?? .appleSpeech
        whisperModel = defaults.string(forKey: Key.whisperModel)
            .flatMap(WhisperModel.init(rawValue:)) ?? .base
        polishLevel = defaults.string(forKey: Key.polishLevel)
            .flatMap(PolishLevel.init(rawValue:)) ?? .off
        llmModel = defaults.string(forKey: Key.llmModel)
            .flatMap(LlmModel.init(rawValue:)) ?? .qwen15
        vadSensitivity = defaults.string(forKey: Key.vadSensitivity)
            .flatMap(VADSensitivity.init(rawValue:)) ?? .medium
    }

    // MARK: - Helpers

    /// Reset the global hotkey to the app default (⌃⌥Z).
    func resetHotKeyToDefault() {
        hotKeyCode = HotKey.defaultKeyCode
        hotKeyModifiers = HotKey.defaultModifiers
    }

    /// The current VAD ratios for the selected sensitivity.
    var vadRatios: (low: Float, high: Float) { vadSensitivity.ratios }

    /// Human-readable combo, e.g. "⌃⌥Z". Modifiers in the conventional ⌃⌥⇧⌘ order,
    /// then the key character (letters/digits/space mapped; otherwise "Key <code>").
    var hotKeyDisplay: String {
        Self.display(keyCode: hotKeyCode, modifiers: hotKeyModifiers)
    }

    /// Render an arbitrary keycode+Carbon-modifier mask as a combo string.
    static func display(keyCode: UInt32, modifiers: UInt32) -> String {
        var out = ""
        if modifiers & UInt32(controlKey) != 0 { out += "⌃" }
        if modifiers & UInt32(optionKey)  != 0 { out += "⌥" }
        if modifiers & UInt32(shiftKey)   != 0 { out += "⇧" }
        if modifiers & UInt32(cmdKey)     != 0 { out += "⌘" }
        out += keyName(for: keyCode)
        return out
    }

    /// Map a hardware virtual keycode to a printable name. Covers letters, digits, and
    /// space; falls back to "Key <code>" for anything else.
    static func keyName(for code: UInt32) -> String {
        if let name = keyNames[Int(code)] { return name }
        return "Key \(code)"
    }

    /// kVK_* virtual keycode → character. Hardware codes are not in ASCII order.
    private static let keyNames: [Int: String] = [
        kVK_ANSI_A: "A", kVK_ANSI_B: "B", kVK_ANSI_C: "C", kVK_ANSI_D: "D",
        kVK_ANSI_E: "E", kVK_ANSI_F: "F", kVK_ANSI_G: "G", kVK_ANSI_H: "H",
        kVK_ANSI_I: "I", kVK_ANSI_J: "J", kVK_ANSI_K: "K", kVK_ANSI_L: "L",
        kVK_ANSI_M: "M", kVK_ANSI_N: "N", kVK_ANSI_O: "O", kVK_ANSI_P: "P",
        kVK_ANSI_Q: "Q", kVK_ANSI_R: "R", kVK_ANSI_S: "S", kVK_ANSI_T: "T",
        kVK_ANSI_U: "U", kVK_ANSI_V: "V", kVK_ANSI_W: "W", kVK_ANSI_X: "X",
        kVK_ANSI_Y: "Y", kVK_ANSI_Z: "Z",
        kVK_ANSI_0: "0", kVK_ANSI_1: "1", kVK_ANSI_2: "2", kVK_ANSI_3: "3",
        kVK_ANSI_4: "4", kVK_ANSI_5: "5", kVK_ANSI_6: "6", kVK_ANSI_7: "7",
        kVK_ANSI_8: "8", kVK_ANSI_9: "9",
        kVK_Space: "Space",
    ]
}
