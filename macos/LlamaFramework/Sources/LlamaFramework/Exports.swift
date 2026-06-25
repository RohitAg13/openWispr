// Re-export the C `llama` module (from the xcframework binary target) so the app target gets
// the whole llama.cpp C API — `llama_model_load_from_file`, `llama_init_from_model`,
// `llama_tokenize`, `llama_decode`, `llama_sampler_*`, `llama_chat_apply_template`, etc. — via
// a single `import LlamaFramework`.
@_exported import llama
