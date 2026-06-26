import Foundation
import OpenWisprCore

/// High-level on-device LLM polish: takes the deterministic-cleaned transcript and rewrites it
/// per the chosen `PolishLevel`, guarded against the ways tiny local models misbehave. The
/// llama context is warm-cached across takes and rebuilt only when the model changes.
///
/// Same role as Android's `LocalLlmEngine`, minus streaming (polish is one-shot here). If the
/// model can't load, or the rewrite trips an over-edit guard, we return the input unchanged —
/// the user always gets at least the deterministic cleanup.
@MainActor
final class LocalLLMEngine {
    static let shared = LocalLLMEngine()

    private var cached: (path: String, ctx: LlamaContext)?

    /// Cap generation to roughly the input size so a looping model can't ramble (mirrors
    /// Android `predictLengthFor`).
    private func predictLength(for text: String) -> Int {
        let words = text.split { $0 == " " || $0 == "\n" || $0 == "\t" }.count
        return min(512, max(48, words * 2 + 48))
    }

    /// Polish `text` at `level` (the app's settings enum) for the focused-app `category`, using
    /// the GGUF at `modelPath`. Returns the rewrite, or `text` unchanged on any failure / guard
    /// trip. `level == .off` returns `text` immediately.
    func polish(
        _ text: String,
        level: PolishLevel,
        category: AppContext.Category,
        modelPath: String,
        isFinetune: Bool = false
    ) async -> String {
        // Map the app's settings enum onto the pure core level used by the prompt builder.
        let coreLevel = OpenWisprCore.PolishLevel(rawValue: level.rawValue) ?? .off
        guard coreLevel.usesLLM else { return text }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return text }

        guard let ctx = await context(for: modelPath) else { return text }

        // The fine-tune was trained on its own SYSTEM (+ per-app tone) and a bare transcript;
        // feed exactly that — the leveled polish prompt is off-distribution for it.
        let system = isFinetune
            ? LlmPolish.finetuneSystemPrompt(category: category)
            : LlmPolish.systemPrompt(level: coreLevel, category: category)
        let user = LlmPolish.userContent(trimmed)
        let raw = await ctx.complete(system: system, user: user, maxTokens: predictLength(for: trimmed))

        let cleaned = PolishGuards.cleanOutput(raw)
        guard !cleaned.isEmpty else { return text }

        // Over-edit guard: reject a rewrite that ballooned or dropped too much (relaxed when the
        // speaker self-corrected). On rejection, keep the deterministic cleanup.
        let relaxed = PolishGuards.hasSelfCorrection(trimmed)
        guard PolishGuards.preservesContent(input: trimmed, output: cleaned, relaxed: relaxed) else {
            return text
        }
        return cleaned
    }

    /// Whether a usable model is loaded/loadable for `modelPath` right now.
    func isReady(modelPath: String) -> Bool {
        cached?.path == modelPath || FileManager.default.fileExists(atPath: modelPath)
    }

    /// Get (or build) the warm context for `modelPath`. Loading is heavy, so it runs off the
    /// main actor; a model switch frees the previous context.
    private func context(for modelPath: String) async -> LlamaContext? {
        if let cached, cached.path == modelPath { return cached.ctx }
        let ctx = await Task.detached(priority: .userInitiated) {
            try? LlamaContext.create(path: modelPath)
        }.value
        guard let ctx else { return nil }
        cached = (modelPath, ctx)
        return ctx
    }

    /// Drop the warm context (e.g. the model was deleted), freeing its memory.
    func unload() { cached = nil }
}
