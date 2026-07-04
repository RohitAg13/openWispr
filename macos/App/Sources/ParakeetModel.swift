import Foundation

/// The on-device NeMo Parakeet-TDT-0.6b-v2 model (int8), run via sherpa-onnx. Unlike Whisper
/// there's a single quality tier, so this is a fixed file set rather than a selectable enum:
/// an encoder/decoder/joiner ONNX trio plus the token table. ~661 MB on disk.
///
/// Files mirror the official sherpa-onnx release on Hugging Face
/// (`csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8`).
enum ParakeetModel {
    /// Directory name under `…/OpenWispr/models/`.
    static let directoryName = "parakeet"

    /// Files to fetch, with their approximate sizes (for the aggregate progress bar). The encoder
    /// dominates; the others are tiny.
    static let files: [(name: String, approxBytes: Int64)] = [
        ("encoder.int8.onnx", 652_000_000),
        ("decoder.int8.onnx", 7_260_000),
        ("joiner.int8.onnx", 1_740_000),
        ("tokens.txt", 9_380),
    ]

    static let approxSize = "~661 MB"

    /// Hugging Face repo that hosts the weights. `resolve/main` streams the raw file.
    private static let repoBase =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"

    static func downloadURL(for fileName: String) -> URL {
        URL(string: "\(repoBase)/\(fileName)?download=true")!
    }
}

/// Locates and downloads the Parakeet model files. They live under
/// `~/Library/Application Support/OpenWispr/models/parakeet/`; the app bundles none (they're
/// large), so the first use of the Parakeet engine downloads the set once.
///
/// `@MainActor` so the `@Published` download state drives SwiftUI directly. The actual network
/// transfer runs off-main via `URLSession`'s async bytes API. Mirrors `WhisperModelManager`.
@MainActor
final class ParakeetModelManager: ObservableObject {
    static let shared = ParakeetModelManager()

    /// Aggregate download progress across all files, 0...1, or `nil` when idle.
    @Published private(set) var downloadProgress: Double?
    /// Whether a download is in flight.
    @Published private(set) var isDownloading: Bool = false
    /// Last download error message, surfaced in the UI; cleared when a new download starts.
    @Published private(set) var lastError: String?
    /// Bumped after a successful download/removal so views recompute `isDownloaded`.
    @Published private(set) var revision: Int = 0

    private var activeTask: Task<Void, Error>?

    /// `~/Library/Application Support/OpenWispr/models/parakeet/`, created on demand.
    var modelDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("OpenWispr", isDirectory: true)
            .appendingPathComponent("models", isDirectory: true)
            .appendingPathComponent(ParakeetModel.directoryName, isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    /// The on-disk location for a model file (whether or not it exists yet).
    func fileURL(for fileName: String) -> URL {
        modelDirectory.appendingPathComponent(fileName)
    }

    /// Whether every model file is present and non-empty.
    var isDownloaded: Bool {
        ParakeetModel.files.allSatisfy { file in
            guard let size = try? FileManager.default
                .attributesOfItem(atPath: fileURL(for: file.name).path)[.size] as? Int else { return false }
            return size > 0
        }
    }

    /// Download every missing model file, publishing aggregate progress. Idempotent: if all files
    /// are present, returns immediately. Streams each file to a temp path and atomically moves it
    /// into place so a cancelled/failed transfer never leaves a half-written ONNX graph that would
    /// crash sherpa's recognizer init.
    @discardableResult
    func download() async -> Bool {
        if isDownloaded { return true }
        if isDownloading, let task = activeTask {
            return (try? await task.value) != nil
        }

        lastError = nil
        isDownloading = true
        downloadProgress = 0

        let totalBytes = ParakeetModel.files.reduce(Int64(0)) { $0 + $1.approxBytes }

        let task = Task<Void, Error> {
            var completedBytes: Int64 = 0
            for file in ParakeetModel.files {
                let destination = fileURL(for: file.name)
                // Skip files already on disk (resume a partial set).
                if let size = try? FileManager.default
                    .attributesOfItem(atPath: destination.path)[.size] as? Int, size > 0 {
                    completedBytes += file.approxBytes
                    continue
                }
                try await downloadFile(
                    from: ParakeetModel.downloadURL(for: file.name),
                    to: destination,
                    fileBytes: file.approxBytes,
                    priorBytes: completedBytes,
                    totalBytes: totalBytes)
                completedBytes += file.approxBytes
            }
        }
        activeTask = task

        do {
            try await task.value
            downloadProgress = nil
            isDownloading = false
            activeTask = nil
            revision += 1
            return true
        } catch {
            downloadProgress = nil
            isDownloading = false
            activeTask = nil
            if !(error is CancellationError) {
                lastError = (error as NSError).localizedDescription
            }
            return false
        }
    }

    /// Download one file to disk via a native `URLSessionDownloadTask` (chunked, disk-backed —
    /// fast), reporting aggregate progress as `(priorBytes + bytesIntoThisFile) / totalBytes`.
    ///
    /// The earlier implementation iterated `URLSession.bytes` one `UInt8` at a time, which for the
    /// ~652 MB encoder meant hundreds of millions of async iterations — CPU-bound and glacially slow
    /// regardless of network speed. The download task streams in OS-sized chunks instead.
    private func downloadFile(
        from url: URL, to destination: URL,
        fileBytes: Int64, priorBytes: Int64, totalBytes: Int64
    ) async throws {
        try await ModelDownload.file(from: url, to: destination) { [weak self] fileFraction in
            // Scale this file's bytes to its share of the (approx) total.
            let done = Double(priorBytes) + fileFraction * Double(fileBytes)
            let p = done / Double(totalBytes)
            Task { @MainActor in self?.downloadProgress = min(0.999, p) }
        }
    }

    /// Cancel an in-flight download.
    func cancelDownload() {
        activeTask?.cancel()
    }

    /// Delete the downloaded model directory from disk.
    func delete() {
        try? FileManager.default.removeItem(at: modelDirectory)
        revision += 1
    }
}
