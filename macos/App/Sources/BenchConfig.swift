#if DEBUG
import Foundation
import OpenWisprCore

/// Debug-only benchmark configuration for the Tier-2 bridge (`BenchDump`).
///
/// Holds the experiment config parsed from a research-harness `config.json` and exposes a few
/// **global override hooks** that `LlamaContext.create` and `LocalLLMEngine` consult. Those hooks
/// are themselves `#if DEBUG`, and default to `nil` (= today's hardcoded behavior), so a Release
/// build neither compiles this file nor changes any pipeline default.
///
/// The config schema mirrors `openwispr-research/configs/*.json`:
///   { "polish": { "gguf", "prompt_variant", "n_ctx", "n_predict", "n_threads", "warm" },
///     "stt":    { "model_path", "model", "n_threads", "language" },
///     "bench":  { "repeats" } }
enum BenchConfig {

    // MARK: - Global overrides (nil ⇒ use the production default)
    static var llamaNCtx: Int32?
    static var llamaNThreads: Int32?
    static var polishMaxTokens: Int?
    /// When false, the warm llama context is dropped before each case to emulate Android's
    /// reload-per-call cost (so Tier 2 can measure the warm-model win directly).
    static var warmReuse: Bool = true

    // MARK: - Parsed experiment config
    struct Parsed {
        var mode = "polish"                 // polish | stt | e2e
        var repeats = 3
        // polish
        var polishGGUF: String?             // explicit path (bypasses the LlmModel enum)
        var promptVariant = "finetune"      // finetune ⇒ isFinetune true
        var nCtx: Int32?
        var nPredict: Int?
        var nThreads: Int32?
        var warm = true
        var gate = false                    // skip the LLM when the deterministic output is clean
        // stt
        var whisperModelPath: String?
        var whisperModel = "base.en"
    }

    static func load(_ url: URL) -> Parsed {
        var p = Parsed()
        guard let data = try? Data(contentsOf: url),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return p }
        if let bench = obj["bench"] as? [String: Any], let r = bench["repeats"] as? Int { p.repeats = r }
        if let polish = obj["polish"] as? [String: Any] {
            p.polishGGUF = (polish["gguf"] as? String).map(expand)
            if let v = polish["prompt_variant"] as? String { p.promptVariant = v }
            if let n = polish["n_ctx"] as? Int { p.nCtx = Int32(n) }
            if let n = polish["n_predict"] as? Int { p.nPredict = n }
            if let n = polish["n_threads"] as? Int { p.nThreads = Int32(n) }
            if let w = polish["warm"] as? Bool { p.warm = w }
            if let g = polish["gate"] as? Bool { p.gate = g }
        }
        if let stt = obj["stt"] as? [String: Any] {
            p.whisperModelPath = (stt["model_path"] as? String).flatMap { $0.isEmpty ? nil : expand($0) }
            if let m = stt["model"] as? String { p.whisperModel = m }
        }
        return p
    }

    /// Push the parsed knobs into the global override hooks.
    static func apply(_ p: Parsed) {
        llamaNCtx = p.nCtx
        llamaNThreads = p.nThreads
        polishMaxTokens = p.nPredict
        warmReuse = p.warm
    }

    private static func expand(_ path: String) -> String {
        (path as NSString).expandingTildeInPath
    }
}
#endif
