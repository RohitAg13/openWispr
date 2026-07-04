import Foundation

/// A downloadable whisper.cpp ggml model. English-only variants — dictation is English-first
/// and the `.en` models are smaller and more accurate for English than the multilingual ones.
/// Sizes are the on-disk `.bin` sizes; pick the trade-off in Settings.
enum WhisperModel: String, CaseIterable, Identifiable {
    case tiny  = "tiny.en"
    case base  = "base.en"
    case small = "small.en"

    var id: String { rawValue }

    /// The ggml filename as published on Hugging Face / used on disk.
    var fileName: String { "ggml-\(rawValue).bin" }

    /// Approximate download / on-disk size, for the UI.
    var approxSize: String {
        switch self {
        case .tiny:  return "~75 MB"
        case .base:  return "~142 MB"
        case .small: return "~466 MB"
        }
    }

    var label: String {
        switch self {
        case .tiny:  return "Tiny (fastest)"
        case .base:  return "Base (recommended)"
        case .small: return "Small (most accurate)"
        }
    }

    /// Official ggml weights mirror. `resolve/main` streams the raw file.
    var downloadURL: URL {
        URL(string:
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/\(fileName)?download=true")!
    }
}

/// Locates, validates, and downloads whisper ggml models. Models live under
/// `~/Library/Application Support/OpenWispr/models/`; the app bundles none (they're large),
/// so the first use of the Whisper engine downloads the selected model once.
///
/// `@MainActor` so the `@Published` download state drives SwiftUI directly. The actual
/// network transfer runs off-main via `URLSession`'s async bytes API.
@MainActor
final class WhisperModelManager: ObservableObject {
    static let shared = WhisperModelManager()

    /// Download progress for the in-flight model, 0...1, or `nil` when idle.
    @Published private(set) var downloadProgress: Double?
    /// The model currently downloading, if any.
    @Published private(set) var downloadingModel: WhisperModel?
    /// Last download error message, surfaced in the UI; cleared when a new download starts.
    @Published private(set) var lastError: String?
    /// Bumped after a successful download/removal so views recompute `isDownloaded`.
    @Published private(set) var revision: Int = 0

    private var activeTask: Task<URL, Error>?

    /// `~/Library/Application Support/OpenWispr/models/`, created on demand.
    private var modelsDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("OpenWispr", isDirectory: true)
            .appendingPathComponent("models", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    /// The on-disk location for a model (whether or not it exists yet).
    func fileURL(for model: WhisperModel) -> URL {
        modelsDirectory.appendingPathComponent(model.fileName)
    }

    /// Whether the model's weights are already on disk and non-empty.
    func isDownloaded(_ model: WhisperModel) -> Bool {
        let url = fileURL(for: model)
        guard let size = try? FileManager.default
            .attributesOfItem(atPath: url.path)[.size] as? Int else { return false }
        return size > 0
    }

    var isDownloading: Bool { downloadingModel != nil }

    /// Download `model` to disk, publishing progress. Idempotent: if already present, returns
    /// immediately. Streams to a temp file and atomically moves it into place so a cancelled or
    /// failed transfer never leaves a half-written model that would crash `whisper_init`.
    @discardableResult
    func download(_ model: WhisperModel) async -> Bool {
        if isDownloaded(model) { return true }
        // Coalesce: if this model is already downloading, await the in-flight task.
        if downloadingModel == model, let task = activeTask {
            return (try? await task.value) != nil
        }

        lastError = nil
        downloadingModel = model
        downloadProgress = 0

        let destination = fileURL(for: model)
        let url = model.downloadURL

        let task = Task<URL, Error> {
            // Native chunked download task — not per-byte `URLSession.bytes` (see `ModelDownload`).
            try await ModelDownload.file(from: url, to: destination) { fraction in
                Task { @MainActor in self.downloadProgress = min(0.999, fraction) }
            }
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
            // Clean up any partial file so a retry starts fresh.
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

    /// Cancel an in-flight download.
    func cancelDownload() {
        activeTask?.cancel()
    }

    /// Delete a downloaded model from disk.
    func delete(_ model: WhisperModel) {
        try? FileManager.default.removeItem(at: fileURL(for: model))
        revision += 1
    }
}
