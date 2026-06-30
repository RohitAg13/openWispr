import Foundation

/// A downloadable on-device LLM (GGUF) for the polish step. Small instruct models that run
/// comfortably on a Mac via llama.cpp/Metal. Qwen2.5-Instruct uses ChatML, which `LlamaContext`
/// formats automatically from the model's embedded template.
enum LlmModel: String, CaseIterable, Identifiable {
    /// OpenWispr's own fine-tune for dictation cleanup (Qwen3-0.6B), trained on the
    /// `LlmPolish.finetuneSystem` prompt + per-app tone. Uses its own prompt path.
    /// Declared first so it's the top, default, and recommended polish model.
    case openwisprCleanup = "openwispr-cleanup-qwen3-0.6b"
    case qwen05 = "qwen2.5-0.5b-instruct"
    case qwen15 = "qwen2.5-1.5b-instruct"

    var id: String { rawValue }

    /// Whether this is the OpenWispr fine-tune (drives the prompt path in `LocalLLMEngine`).
    var isFinetune: Bool { self == .openwisprCleanup }

    /// The GGUF filename on disk / on Hugging Face.
    var fileName: String {
        switch self {
        case .qwen05, .qwen15: return "\(rawValue)-q4_k_m.gguf"
        case .openwisprCleanup: return "qwen3-0.6b.Q4_K_M.gguf"
        }
    }

    var approxSize: String {
        switch self {
        case .qwen05:          return "~491 MB"
        case .qwen15:          return "~1.1 GB"
        case .openwisprCleanup: return "~396 MB"
        }
    }

    var label: String {
        switch self {
        case .openwisprCleanup: return "OpenWispr Cleanup (recommended)"
        case .qwen05:          return "Qwen2.5 0.5B (fastest)"
        case .qwen15:          return "Qwen2.5 1.5B (most accurate)"
        }
    }

    /// `resolve/main` streams the raw GGUF.
    var downloadURL: URL {
        let path: String
        switch self {
        case .qwen05:
            path = "Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/\(fileName)"
        case .qwen15:
            path = "Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/\(fileName)"
        case .openwisprCleanup:
            path = "rohitag13/openwispr-cleanup-qwen3-0.6b-GGUF/resolve/main/\(fileName)"
        }
        return URL(string: "https://huggingface.co/\(path)?download=true")!
    }
}

/// Locates and downloads on-device LLM weights, parallel to `WhisperModelManager`. Models live
/// under `~/Library/Application Support/OpenWispr/llm/`; the first use of a polish level
/// downloads the selected model once.
@MainActor
final class LlmModelManager: ObservableObject {
    static let shared = LlmModelManager()

    @Published private(set) var downloadProgress: Double?
    @Published private(set) var downloadingModel: LlmModel?
    @Published private(set) var lastError: String?
    @Published private(set) var revision: Int = 0

    private var activeTask: Task<URL, Error>?

    private var modelsDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("OpenWispr", isDirectory: true)
            .appendingPathComponent("llm", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    func fileURL(for model: LlmModel) -> URL {
        modelsDirectory.appendingPathComponent(model.fileName)
    }

    func isDownloaded(_ model: LlmModel) -> Bool {
        let url = fileURL(for: model)
        guard let size = try? FileManager.default
            .attributesOfItem(atPath: url.path)[.size] as? Int else { return false }
        return size > 0
    }

    var isDownloading: Bool { downloadingModel != nil }

    /// Download `model` to disk (streamed to a temp file, atomically moved into place), with
    /// progress. Idempotent: returns immediately if already present.
    @discardableResult
    func download(_ model: LlmModel) async -> Bool {
        if isDownloaded(model) { return true }
        if downloadingModel == model, let task = activeTask {
            return (try? await task.value) != nil
        }

        lastError = nil
        downloadingModel = model
        downloadProgress = 0

        let destination = fileURL(for: model)
        let url = model.downloadURL

        let task = Task<URL, Error> {
            let (bytes, response) = try await URLSession.shared.bytes(from: url)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                throw URLError(.badServerResponse)
            }
            let expected = http.expectedContentLength

            let tmp = destination.appendingPathExtension("partial")
            FileManager.default.createFile(atPath: tmp.path, contents: nil)
            let handle = try FileHandle(forWritingTo: tmp)
            defer { try? handle.close() }

            var received: Int64 = 0
            var buffer = Data()
            buffer.reserveCapacity(1 << 20)
            for try await byte in bytes {
                buffer.append(byte)
                if buffer.count >= (1 << 20) {
                    try handle.write(contentsOf: buffer)
                    received += Int64(buffer.count)
                    buffer.removeAll(keepingCapacity: true)
                    if expected > 0 {
                        let p = Double(received) / Double(expected)
                        await MainActor.run { self.downloadProgress = min(0.999, p) }
                    }
                }
                try Task.checkCancellation()
            }
            if !buffer.isEmpty {
                try handle.write(contentsOf: buffer)
            }
            try handle.close()

            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            try FileManager.default.moveItem(at: tmp, to: destination)
            return destination
        }
        activeTask = task

        do {
            _ = try await task.value
            downloadProgress = nil
            downloadingModel = nil
            activeTask = nil
            revision += 1
            return true
        } catch {
            try? FileManager.default.removeItem(at: destination.appendingPathExtension("partial"))
            downloadProgress = nil
            downloadingModel = nil
            activeTask = nil
            if !(error is CancellationError) {
                lastError = (error as NSError).localizedDescription
            }
            return false
        }
    }

    func cancelDownload() { activeTask?.cancel() }

    func delete(_ model: LlmModel) {
        try? FileManager.default.removeItem(at: fileURL(for: model))
        LocalLLMEngine.shared.unload()
        revision += 1
    }
}
