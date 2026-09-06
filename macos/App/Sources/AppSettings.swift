import AppKit
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

    /// Bare engine name, for tight spots like a "Retry on Whisper" button.
    var shortLabel: String {
        switch self {
        case .appleSpeech: return "Apple Speech"
        case .whisper:     return "Whisper"
        case .parakeet:    return "Parakeet"
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
        case .off:    return "Deterministic cleanup only, no LLM rewrite."
        case .light:  return "Light touch. Fixes obvious slips, keeps your words."
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
        // Legacy (migrated on first launch after upgrade).
        static let triggerKind = "triggerKind"
        static let hotKeyCode = "hotKeyCode"
        static let hotKeyModifiers = "hotKeyModifiers"
        static let doubleClickEnabled = "doubleClickEnabled"
        static let doubleClickKeyCode = "doubleClickKeyCode"
        static let doubleClickKeyModifiers = "doubleClickKeyModifiers"
        static let pushToTalkEnabled = "pushToTalkEnabled"
        static let pttKeyCode = "pttKeyCode"
        static let pttKeyModifiers = "pttKeyModifiers"
        static let cutoffOnSpeechPause = "cutoffOnSpeechPause"
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
        static let showDictationIndicator = "showDictationIndicator"
        static let holdToTalk = "holdToTalk"
        static let showMenuBarIcon = "showMenuBarIcon"
        static let dictationLanguage = "dictationLanguage"
    }

    // MARK: - Persisted properties

    /// Independent toggle for hands-free double-click mode.
    @Published var doubleClickEnabled: Bool {
        didSet { defaults.set(doubleClickEnabled, forKey: Key.doubleClickEnabled) }
    }

    @Published var doubleClickKeyCode: UInt32 {
        didSet { defaults.set(Int(doubleClickKeyCode), forKey: Key.doubleClickKeyCode) }
    }

    @Published var doubleClickKeyModifiers: UInt32 {
        didSet { defaults.set(Int(doubleClickKeyModifiers), forKey: Key.doubleClickKeyModifiers) }
    }

    /// Independent toggle for hold-to-talk mode.
    @Published var pushToTalkEnabled: Bool {
        didSet { defaults.set(pushToTalkEnabled, forKey: Key.pushToTalkEnabled) }
    }

    @Published var pttKeyCode: UInt32 {
        didSet { defaults.set(Int(pttKeyCode), forKey: Key.pttKeyCode) }
    }

    @Published var pttKeyModifiers: UInt32 {
        didSet { defaults.set(Int(pttKeyModifiers), forKey: Key.pttKeyModifiers) }
    }

    /// When true, VAD auto-stop ends a hands-free session on speech pauses (using mic sensitivity).
    @Published var cutoffOnSpeechPause: Bool {
        didSet { defaults.set(cutoffOnSpeechPause, forKey: Key.cutoffOnSpeechPause) }
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

    /// Legacy notch-placement preference. Kept so existing UserDefaults values aren't lost;
    /// the on-screen indicator is now controlled solely by `showDictationIndicator`.
    @Published var useNotchHud: Bool {
        didSet { defaults.set(useNotchHud, forKey: Key.useNotchHud) }
    }

    /// Master switch for the dictation indicator (menu-bar icon change + on-screen bar on
    /// every display). **On by default.** Turning this off is the only way to suppress it.
    @Published var showDictationIndicator: Bool {
        didSet { defaults.set(showDictationIndicator, forKey: Key.showDictationIndicator) }
    }

    /// Legacy preference retained for migration. With the fn trigger, hold-to-talk is always on
    /// (double-click = hands-free toggle); this no longer gates behavior.
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

        // Dictation triggers — migrate from the old single-choice `triggerKind` on first launch.
        if defaults.object(forKey: Key.doubleClickEnabled) != nil {
            doubleClickEnabled = defaults.bool(forKey: Key.doubleClickEnabled)
            doubleClickKeyCode = Self.loadKeyCode(defaults, key: Key.doubleClickKeyCode,
                                                  default: Self.defaultDoubleClickKeyCode)
            doubleClickKeyModifiers = Self.loadKeyCode(defaults, key: Key.doubleClickKeyModifiers, default: 0)
            pushToTalkEnabled = defaults.bool(forKey: Key.pushToTalkEnabled)
            pttKeyCode = Self.loadKeyCode(defaults, key: Key.pttKeyCode, default: Self.defaultPTTKeyCode)
            pttKeyModifiers = Self.loadKeyCode(defaults, key: Key.pttKeyModifiers, default: 0)
        } else {
            let legacyKind = defaults.string(forKey: Key.triggerKind)
            let legacyCode = Self.loadKeyCode(defaults, key: Key.hotKeyCode, default: HotKey.defaultKeyCode)
            let legacyMods = Self.loadKeyCode(defaults, key: Key.hotKeyModifiers, default: HotKey.defaultModifiers)
            let migratedDoubleClickEnabled: Bool
            let migratedDoubleClickKeyCode: UInt32
            let migratedDoubleClickKeyModifiers: UInt32
            let migratedPushToTalkEnabled: Bool
            if legacyKind == "hotkey" {
                migratedDoubleClickEnabled = true
                migratedDoubleClickKeyCode = legacyCode
                migratedDoubleClickKeyModifiers = legacyMods
                migratedPushToTalkEnabled = false
            } else {
                migratedDoubleClickEnabled = true
                migratedDoubleClickKeyCode = Self.defaultDoubleClickKeyCode
                migratedDoubleClickKeyModifiers = 0
                migratedPushToTalkEnabled = true
            }
            let migratedPttKeyCode = Self.defaultPTTKeyCode
            let migratedPttKeyModifiers: UInt32 = 0
            doubleClickEnabled = migratedDoubleClickEnabled
            doubleClickKeyCode = migratedDoubleClickKeyCode
            doubleClickKeyModifiers = migratedDoubleClickKeyModifiers
            pushToTalkEnabled = migratedPushToTalkEnabled
            pttKeyCode = migratedPttKeyCode
            pttKeyModifiers = migratedPttKeyModifiers
            defaults.set(migratedDoubleClickEnabled, forKey: Key.doubleClickEnabled)
            defaults.set(Int(migratedDoubleClickKeyCode), forKey: Key.doubleClickKeyCode)
            defaults.set(Int(migratedDoubleClickKeyModifiers), forKey: Key.doubleClickKeyModifiers)
            defaults.set(migratedPushToTalkEnabled, forKey: Key.pushToTalkEnabled)
            defaults.set(Int(migratedPttKeyCode), forKey: Key.pttKeyCode)
            defaults.set(Int(migratedPttKeyModifiers), forKey: Key.pttKeyModifiers)
        }
        cutoffOnSpeechPause = defaults.object(forKey: Key.cutoffOnSpeechPause) as? Bool ?? false

        // On-device defaults: a fresh install gets Parakeet (the fastest on-device engine) for
        // STT and the OpenWispr fine-tune for polish. The `?? default` only applies when nothing
        // is stored, so any prior explicit user choice is preserved on upgrade.
        sttProvider = defaults.string(forKey: Key.sttProvider)
            .flatMap(STTProvider.init(rawValue:)) ?? .parakeet
        whisperModel = defaults.string(forKey: Key.whisperModel)
            .flatMap(WhisperModel.init(rawValue:)) ?? .base
        polishLevel = defaults.string(forKey: Key.polishLevel)
            .flatMap(PolishLevel.init(rawValue:)) ?? .full
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
        useNotchHud = defaults.object(forKey: Key.useNotchHud) as? Bool ?? true
        // Always default ON — missing key means show the indicator every session.
        showDictationIndicator = defaults.object(forKey: Key.showDictationIndicator) as? Bool ?? true
        holdToTalk = defaults.object(forKey: Key.holdToTalk) as? Bool ?? false
        showMenuBarIcon = defaults.object(forKey: Key.showMenuBarIcon) as? Bool ?? true
        dictationLanguage = defaults.string(forKey: Key.dictationLanguage) ?? "English"
    }

    // MARK: - Helpers

    static let defaultDoubleClickKeyCode: UInt32 = UInt32(kVK_Function)
    static let defaultPTTKeyCode: UInt32 = UInt32(kVK_RightOption)

    func resetDoubleClickKeyToDefault() {
        doubleClickKeyCode = Self.defaultDoubleClickKeyCode
        doubleClickKeyModifiers = 0
    }

    func resetPTTKeyToDefault() {
        pttKeyCode = Self.defaultPTTKeyCode
        pttKeyModifiers = 0
    }

    /// The current VAD ratios for the selected sensitivity.
    var vadRatios: (low: Float, high: Float) { vadSensitivity.ratios }

    var doubleClickDisplay: String {
        Self.display(keyCode: doubleClickKeyCode, modifiers: doubleClickKeyModifiers)
    }

    var pttDisplay: String {
        Self.display(keyCode: pttKeyCode, modifiers: pttKeyModifiers)
    }

    /// Whether both enabled modes share the same key binding.
    var triggerBindingsConflict: Bool {
        guard doubleClickEnabled, pushToTalkEnabled else { return false }
        return doubleClickKeyCode == pttKeyCode && doubleClickKeyModifiers == pttKeyModifiers
    }

    /// Short label for the primary trigger(s) shown in Home / onboarding.
    var triggerDisplay: String {
        if doubleClickEnabled && pushToTalkEnabled {
            return "\(doubleClickDisplay) · \(pttDisplay)"
        }
        if doubleClickEnabled { return doubleClickDisplay }
        if pushToTalkEnabled { return pttDisplay }
        return "No trigger"
    }

    /// One-line instruction for how to invoke dictation with the active trigger(s).
    var triggerHint: String {
        var parts: [String] = []
        if doubleClickEnabled {
            parts.append("Double-click \(doubleClickDisplay) to toggle hands-free")
        }
        if pushToTalkEnabled {
            parts.append("hold \(pttDisplay) to talk")
        }
        if parts.isEmpty {
            return "Enable a trigger mode in Settings"
        }
        return parts.joined(separator: " · ")
    }

    /// Dedicated trigger keys may be bound without extra modifiers (Globe/Fn, Right ⌥, etc.).
    static func isDedicatedTriggerKey(_ code: UInt32) -> Bool {
        if code == UInt32(kVK_Function) { return true }
        return isStandaloneModifierTriggerKey(code)
    }

    /// Whether a standalone modifier key is currently pressed in a flagsChanged event.
    static func modifierKeyIsPressed(code: UInt32, flags: NSEvent.ModifierFlags) -> Bool {
        switch code {
        case UInt32(kVK_Shift), UInt32(kVK_RightShift):       return flags.contains(.shift)
        case UInt32(kVK_Control), UInt32(kVK_RightControl):   return flags.contains(.control)
        case UInt32(kVK_Option), UInt32(kVK_RightOption):     return flags.contains(.option)
        case UInt32(kVK_Command), UInt32(kVK_RightCommand):   return flags.contains(.command)
        case UInt32(kVK_Function):                            return flags.contains(.function)
        default:                                               return false
        }
    }
    static func isStandaloneModifierTriggerKey(_ code: UInt32) -> Bool {
        switch code {
        case UInt32(kVK_Shift), UInt32(kVK_RightShift),
             UInt32(kVK_Control), UInt32(kVK_RightControl),
             UInt32(kVK_Option), UInt32(kVK_RightOption),
             UInt32(kVK_Command), UInt32(kVK_RightCommand):
            return true
        default:
            return false
        }
    }

    /// Keys that must never be mapped as dictation triggers.
    static func isBlockedKey(code: UInt32, modifiers: UInt32) -> Bool {
        let blockedCodes: Set<UInt32> = [
            UInt32(kVK_Escape), UInt32(kVK_Tab), UInt32(kVK_Return), UInt32(kVK_ANSI_KeypadEnter),
            UInt32(kVK_Delete), UInt32(kVK_ForwardDelete),
            UInt32(kVK_UpArrow), UInt32(kVK_DownArrow), UInt32(kVK_LeftArrow), UInt32(kVK_RightArrow),
            UInt32(kVK_VolumeUp), UInt32(kVK_VolumeDown), UInt32(kVK_Mute),
            // F14/F15 are the brightness keys on Apple keyboards (no kVK_Brightness* in HIToolbox).
            UInt32(kVK_F14), UInt32(kVK_F15),
        ]
        if blockedCodes.contains(code) { return true }
        // Bare modifier only — no stable key-up target.
        if modifiers != 0 && isModifierKeyCode(code) { return true }
        // Non-dedicated keys require at least one modifier.
        if modifiers == 0 && !isDedicatedTriggerKey(code) { return true }
        return false
    }

    private static func isModifierKeyCode(_ code: UInt32) -> Bool {
        let modifierCodes: Set<UInt32> = [
            UInt32(kVK_Shift), UInt32(kVK_RightShift),
            UInt32(kVK_Control), UInt32(kVK_RightControl),
            UInt32(kVK_Option), UInt32(kVK_RightOption),
            UInt32(kVK_Command), UInt32(kVK_RightCommand),
            UInt32(kVK_CapsLock), UInt32(kVK_Function),
        ]
        return modifierCodes.contains(code)
    }

    private static func loadKeyCode(_ defaults: UserDefaults, key: String, default defaultValue: UInt32) -> UInt32 {
        if defaults.object(forKey: key) != nil {
            return UInt32(defaults.integer(forKey: key))
        }
        return defaultValue
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
        kVK_Function: "Fn",
        kVK_RightOption: "Right ⌥",
        kVK_Option: "⌥",
        kVK_RightShift: "Right ⇧",
        kVK_RightControl: "Right ⌃",
        kVK_RightCommand: "Right ⌘",
    ]
}
