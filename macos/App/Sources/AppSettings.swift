import Carbon.HIToolbox
import Combine
import Foundation

/// STT backend choice. All three are wired up; the on-device engines (Whisper, Parakeet) need
/// their model downloaded before a session can use them (the Settings UI prompts for that).
enum STTProvider: String, CaseIterable {
    case appleSpeech
    case whisper
    case parakeet

    var label: String {
        switch self {
        case .appleSpeech: return "Apple Speech"
        case .whisper:     return "Whisper (on-device)"
        case .parakeet:    return "Parakeet (on-device, fastest)"
        }
    }

    /// Whether this provider is selectable. All are wired up — but the on-device engines still
    /// need their model downloaded before a session can use them (the Settings UI prompts for that).
    var isAvailable: Bool {
        switch self {
        case .appleSpeech: return true
        case .whisper:     return true
        case .parakeet:    return true
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

    /// Compact label for the segmented control.
    var shortLabel: String {
        switch self {
        case .off:    return "Off"
        case .light:  return "Light"
        case .medium: return "Medium"
        case .full:   return "Full"
        }
    }

    /// One-line description of what this level does, shown under the segmented control.
    var blurb: String {
        switch self {
        case .off:    return "Deterministic cleanup only — no LLM rewrite."
        case .light:  return "Light touch — fix obvious slips, keep your words."
        case .medium: return "Tidy sentences and flow while preserving meaning."
        case .full:   return "Fuller rewrite for clear, well-structured text."
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
        static let hasCompletedOnboarding = "hasCompletedOnboarding"
        static let smartCleanup = "smartCleanup"
        static let keepHistory = "keepHistory"
        static let llmCreativity = "llmCreativity"
        static let antiAiGuardrails = "antiAiGuardrails"
        static let useNotchHud = "useNotchHud"
        static let holdToTalk = "holdToTalk"
        static let showMenuBarIcon = "showMenuBarIcon"
        static let dictationLanguage = "dictationLanguage"
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

    /// Whether the first-run onboarding flow has been completed. Drives whether the app shows
    /// the guided setup (permissions, engine, shortcut) or the main window at launch.
    @Published var hasCompletedOnboarding: Bool {
        didSet { defaults.set(hasCompletedOnboarding, forKey: Key.hasCompletedOnboarding) }
    }

    /// Whether the deterministic text-cleanup pass (`TextProcessor`) runs. On by default — the
    /// "Smart cleanup" toggle in Settings ▸ Cleanup & Polish. When off, the raw transcript is
    /// passed through (LLM polish, if enabled, still applies on top).
    @Published var smartCleanup: Bool {
        didSet { defaults.set(smartCleanup, forKey: Key.smartCleanup) }
    }

    /// Whether finished dictations are saved to the on-device history list. On by default — the
    /// "Keep history" toggle in Settings ▸ Privacy.
    @Published var keepHistory: Bool {
        didSet { defaults.set(keepHistory, forKey: Key.keepHistory) }
    }

    /// Polish "creativity" (0 = faithful cleanup, 1 = freer rewrite). Surfaced as the Advanced
    /// creativity slider. NOTE: the on-device polish currently samples greedily
    /// (`LlamaContext`), so this is persisted intent for an upcoming temperature-aware sampler;
    /// it has no runtime effect yet.
    @Published var llmCreativity: Double {
        didSet { defaults.set(llmCreativity, forKey: Key.llmCreativity) }
    }

    /// Whether the anti-AI guardrails (keep the user's own words; never sound like a chatbot) are
    /// enforced. On by default. The guardrails live in the polish system prompt today and are
    /// always applied; this toggle persists the user's intent to relax them in a later step.
    @Published var antiAiGuardrails: Bool {
        didSet { defaults.set(antiAiGuardrails, forKey: Key.antiAiGuardrails) }
    }

    /// Show the recording indicator in the notch area. Persisted preference; the notch HUD
    /// placement is not implemented yet (the HUD floats near the cursor today).
    @Published var useNotchHud: Bool {
        didSet { defaults.set(useNotchHud, forKey: Key.useNotchHud) }
    }

    /// Hold-to-talk (push-to-talk) instead of press-to-toggle. Persisted preference; the hold
    /// trigger is not wired yet (the global hotkey toggles a session today).
    @Published var holdToTalk: Bool {
        didSet { defaults.set(holdToTalk, forKey: Key.holdToTalk) }
    }

    /// Whether the menu-bar icon is shown. Persisted preference (surfaced in General). The
    /// `MenuBarExtra` is always inserted today so an accessory app is never left unreachable.
    @Published var showMenuBarIcon: Bool {
        didSet { defaults.set(showMenuBarIcon, forKey: Key.showMenuBarIcon) }
    }

    /// Dictation language. English today (the bundled on-device Whisper models are English-only);
    /// persisted for when multilingual models land.
    @Published var dictationLanguage: String {
        didSet { defaults.set(dictationLanguage, forKey: Key.dictationLanguage) }
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

        // On-device defaults: a fresh install gets Whisper (`base`, the recommended on-device
        // engine) for STT and the OpenWispr fine-tune for polish. The `?? default` only applies
        // when nothing is stored, so any prior explicit user choice is preserved on upgrade.
        sttProvider = defaults.string(forKey: Key.sttProvider)
            .flatMap(STTProvider.init(rawValue:)) ?? .whisper
        whisperModel = defaults.string(forKey: Key.whisperModel)
            .flatMap(WhisperModel.init(rawValue:)) ?? .base
        polishLevel = defaults.string(forKey: Key.polishLevel)
            .flatMap(PolishLevel.init(rawValue:)) ?? .off
        llmModel = defaults.string(forKey: Key.llmModel)
            .flatMap(LlmModel.init(rawValue:)) ?? .openwisprCleanup
        vadSensitivity = defaults.string(forKey: Key.vadSensitivity)
            .flatMap(VADSensitivity.init(rawValue:)) ?? .medium
        hasCompletedOnboarding = defaults.bool(forKey: Key.hasCompletedOnboarding)

        // New toggles default ON for cleanup/history when unset (`object(forKey:) == nil`),
        // so existing installs keep today's behavior; the rest default to a conservative value.
        smartCleanup = defaults.object(forKey: Key.smartCleanup) as? Bool ?? true
        keepHistory = defaults.object(forKey: Key.keepHistory) as? Bool ?? true
        llmCreativity = defaults.object(forKey: Key.llmCreativity) as? Double ?? 0.2
        antiAiGuardrails = defaults.object(forKey: Key.antiAiGuardrails) as? Bool ?? true
        useNotchHud = defaults.object(forKey: Key.useNotchHud) as? Bool ?? false
        holdToTalk = defaults.object(forKey: Key.holdToTalk) as? Bool ?? false
        showMenuBarIcon = defaults.object(forKey: Key.showMenuBarIcon) as? Bool ?? true
        dictationLanguage = defaults.string(forKey: Key.dictationLanguage) ?? "English"
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
