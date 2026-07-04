import Foundation

/// Downloads a single file with a native, chunked `URLSessionDownloadTask` and reports 0...1
/// progress.
///
/// This replaces the previous approach of iterating `URLSession.bytes` one `UInt8` at a time: for a
/// multi-hundred-MB model that meant hundreds of millions of async iterations, which was CPU-bound
/// and glacially slow no matter how fast the network was. A download task streams in OS-sized chunks
/// straight to disk instead. The async `download(from:)` also cancels its underlying task when the
/// enclosing Swift `Task` is cancelled, so caller-side cancellation keeps working.
enum ModelDownload {
    /// Download `url` to `destination` (atomic move into place). `onProgress` receives this file's
    /// completion fraction (0...1) as chunks arrive.
    static func file(from url: URL, to destination: URL,
                     onProgress: @escaping (Double) -> Void) async throws {
        let delegate = ProgressDelegate(onProgress: onProgress)
        let session = URLSession(configuration: .default, delegate: delegate, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        let (tempURL, response) = try await session.download(from: url)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }

        if FileManager.default.fileExists(atPath: destination.path) {
            try FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.moveItem(at: tempURL, to: destination)
    }

    /// Reports per-file progress. Only implements `didWriteData` (plus a no-op finish) so it composes
    /// with the async `download(from:)`, which owns the finished-file handoff.
    private final class ProgressDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
        private let onProgress: (Double) -> Void
        init(onProgress: @escaping (Double) -> Void) { self.onProgress = onProgress }

        func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                        didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                        totalBytesExpectedToWrite: Int64) {
            guard totalBytesExpectedToWrite > 0 else { return }
            onProgress(Double(totalBytesWritten) / Double(totalBytesExpectedToWrite))
        }

        func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                        didFinishDownloadingTo location: URL) {}
    }
}
