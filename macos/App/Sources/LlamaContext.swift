import Foundation
import LlamaFramework
import OpenWisprCore

/// Low-level llama.cpp wrapper — a focused port of the official `llama.swiftui` `LibLlama.swift`
/// (build b9786) adapted for **batch** chat completion (we don't stream to the UI; polish is a
/// one-shot rewrite of a finished transcript). All llama C calls are funnelled through this
/// `actor` because a llama context is single-threaded.
///
/// Adds what `LibLlama` lacks for instruct models: chat-template formatting via
/// `llama_chat_apply_template` (so the model sees its real `<|im_start|>…` framing), greedy
/// sampling (deterministic, faithful cleanup), and an early stop when the output starts looping.
actor LlamaContext {

    enum LlamaError: Error { case couldNotLoad }

    private let model: OpaquePointer
    private let context: OpaquePointer
    private let vocab: OpaquePointer
    private var sampling: UnsafeMutablePointer<llama_sampler>
    private var batch: llama_batch
    private let nCtx: Int32

    private init(model: OpaquePointer, context: OpaquePointer) {
        self.model = model
        self.context = context
        self.vocab = llama_model_get_vocab(model)
        self.nCtx = Int32(llama_n_ctx(context))
        self.batch = llama_batch_init(2048, 0, 1)
        // Greedy: deterministic, no rambling — exactly what a faithful cleanup wants.
        let sparams = llama_sampler_chain_default_params()
        self.sampling = llama_sampler_chain_init(sparams)
        llama_sampler_chain_add(sampling, llama_sampler_init_greedy())
    }

    deinit {
        llama_sampler_free(sampling)
        llama_batch_free(batch)
        llama_free(context)
        llama_model_free(model)
        // NOTE: deliberately NOT calling `llama_backend_free()` — the backend is process-global
        // and shared with any context we build next (model switches); freeing it here would pull
        // it out from under a live context. We init it once (below) and leave it for the process.
    }

    /// Initialize the global llama backend exactly once for the process.
    private static let backendInit: Void = { llama_backend_init() }()

    /// Load a GGUF model and build a context. Metal GPU offload is on by default (Apple Silicon).
    static func create(path: String) throws -> LlamaContext {
        _ = backendInit
        var modelParams = llama_model_default_params()
        // n_gpu_layers defaults to offloading everything; Metal handles it on macOS.

        guard let model = llama_model_load_from_file(path, modelParams) else {
            throw LlamaError.couldNotLoad
        }
        let nThreads = Int32(max(1, min(8, ProcessInfo.processInfo.processorCount - 2)))
        var ctxParams = llama_context_default_params()
        ctxParams.n_ctx = 4096
        ctxParams.n_threads = nThreads
        ctxParams.n_threads_batch = nThreads
        guard let context = llama_init_from_model(model, ctxParams) else {
            llama_model_free(model)
            throw LlamaError.couldNotLoad
        }
        return LlamaContext(model: model, context: context)
    }

    /// Run one completion: format `system` + `user` with the model's chat template, decode the
    /// prompt, then greedily generate up to `maxTokens`, stopping at end-of-generation or once
    /// the output begins repeating itself. Returns the raw generated text (post-process with
    /// `PolishGuards.cleanOutput`).
    func complete(system: String, user: String, maxTokens: Int) -> String {
        let prompt = formatChat(system: system, user: user)
        var promptTokens = tokenize(prompt, addSpecial: true, parseSpecial: true)

        // Guard against an over-long prompt (leave room for the generation).
        let maxPrompt = Int(nCtx) - maxTokens - 8
        if promptTokens.count > maxPrompt, maxPrompt > 0 {
            promptTokens = Array(promptTokens.suffix(maxPrompt))
        }
        if promptTokens.isEmpty { return "" }

        // Reset the KV cache so this completion starts at position 0. The context is reused warm
        // across takes (model cached in `LocalLLMEngine`); without this, the previous run's tokens
        // remain in the cache and `llama_decode` rejects the new prompt with non-consecutive
        // positions ("the tokens ... have inconsistent sequence positions"), so every completion
        // after the first would fail and silently fall back to the un-polished text.
        llama_memory_clear(llama_get_memory(context), true)

        // Decode the prompt in one batch; only the last token needs logits.
        clearBatch()
        for (i, tok) in promptTokens.enumerated() {
            addToBatch(tok, pos: Int32(i), logits: false)
        }
        batch.logits[Int(batch.n_tokens) - 1] = 1
        if llama_decode(context, batch) != 0 { return "" }

        var nCur = batch.n_tokens
        var pieces: [CChar] = []
        var output = ""
        var generated = 0

        while generated < maxTokens && nCur < nCtx {
            let tokenId = llama_sampler_sample(sampling, context, batch.n_tokens - 1)
            if llama_vocab_is_eog(vocab, tokenId) { break }

            pieces.append(contentsOf: tokenToPiece(tokenId))
            // Flush whatever is now valid UTF-8 into `output`.
            if let s = String(validatingUTF8: pieces + [0]) {
                output += s
                pieces.removeAll(keepingCapacity: true)
            }

            // Early stop: tiny models loop on their own output, or drift into a hallucinated
            // bracket/checkbox form ("Capitalization responses: [] [] []"). Check occasionally.
            if generated % 8 == 0 && (PolishGuards.looksRepeating(output) || PolishGuards.looksLikeScaffold(output)) { break }

            clearBatch()
            addToBatch(tokenId, pos: nCur, logits: true)
            nCur += 1
            generated += 1
            if llama_decode(context, batch) != 0 { break }
        }
        return output
    }

    // MARK: - Chat template

    /// Format the system+user messages with the model's built-in chat template (falling back to
    /// ChatML, which the default Qwen models use). `add_ass` appends the assistant turn opener.
    private func formatChat(system: String, user: String) -> String {
        let messages = [("system", system), ("user", user)]
        let tmpl = llama_model_chat_template(model, nil) // model's own template, or nil

        // Duplicate the role/content C strings for the duration of the call.
        var dups: [UnsafeMutablePointer<CChar>] = []
        var cmsgs: [llama_chat_message] = []
        for (role, content) in messages {
            let r = strdup(role)!
            let c = strdup(content)!
            dups.append(r); dups.append(c)
            cmsgs.append(llama_chat_message(role: UnsafePointer(r), content: UnsafePointer(c)))
        }
        defer { dups.forEach { free($0) } }

        var buf = [CChar](repeating: 0, count: 8192)
        var n = llama_chat_apply_template(tmpl, cmsgs, cmsgs.count, true, &buf, Int32(buf.count))
        if n > Int32(buf.count) {
            buf = [CChar](repeating: 0, count: Int(n))
            n = llama_chat_apply_template(tmpl, cmsgs, cmsgs.count, true, &buf, n)
        }
        if n <= 0 {
            return chatMLFallback(system: system, user: user)
        }
        let bytes = buf.prefix(Int(n)).map { UInt8(bitPattern: $0) }
        return String(decoding: bytes, as: UTF8.self)
    }

    /// ChatML, matching the default Qwen2.5 template — used only if the model exposes no template.
    private func chatMLFallback(system: String, user: String) -> String {
        "<|im_start|>system\n\(system)<|im_end|>\n"
            + "<|im_start|>user\n\(user)<|im_end|>\n"
            + "<|im_start|>assistant\n"
    }

    // MARK: - Tokenize / detokenize

    private func tokenize(_ text: String, addSpecial: Bool, parseSpecial: Bool) -> [llama_token] {
        let utf8Count = text.utf8.count
        let capacity = utf8Count + (addSpecial ? 1 : 0) + 2
        let tokens = UnsafeMutablePointer<llama_token>.allocate(capacity: capacity)
        defer { tokens.deallocate() }
        let n = llama_tokenize(vocab, text, Int32(utf8Count), tokens, Int32(capacity), addSpecial, parseSpecial)
        guard n > 0 else { return [] }
        return (0..<Int(n)).map { tokens[$0] }
    }

    private func tokenToPiece(_ token: llama_token) -> [CChar] {
        let buf = UnsafeMutablePointer<CChar>.allocate(capacity: 16)
        buf.initialize(repeating: 0, count: 16)
        defer { buf.deallocate() }
        let n = llama_token_to_piece(vocab, token, buf, 16, 0, false)
        if n < 0 {
            let big = UnsafeMutablePointer<CChar>.allocate(capacity: Int(-n))
            big.initialize(repeating: 0, count: Int(-n))
            defer { big.deallocate() }
            let n2 = llama_token_to_piece(vocab, token, big, -n, 0, false)
            return Array(UnsafeBufferPointer(start: big, count: Int(n2)))
        }
        return Array(UnsafeBufferPointer(start: buf, count: Int(n)))
    }

    // MARK: - Batch helpers (ported from LibLlama free functions)

    private func clearBatch() { batch.n_tokens = 0 }

    private func addToBatch(_ id: llama_token, pos: llama_pos, logits: Bool) {
        let i = Int(batch.n_tokens)
        batch.token[i] = id
        batch.pos[i] = pos
        batch.n_seq_id[i] = 1
        batch.seq_id[i]![0] = 0
        batch.logits[i] = logits ? 1 : 0
        batch.n_tokens += 1
    }
}
