import Foundation

/// Speech-to-text provider. Implementations transcribe a batch of audio samples to plain
/// text. The first provider wraps Apple's Speech framework (in the app target); a
/// `whisper.cpp` provider lands later behind this same protocol.
///
/// Foundation-only by design — no `Speech`/`AVFoundation` here so the core stays pure and
/// portable. Concrete providers live in the app target where the system frameworks live.
public protocol STT {
    /// Transcribe mono Float32 samples (normalized -1…1) at `sampleRate` Hz. Returns plain text.
    func transcribe(_ samples: [Float], sampleRate: Int) async throws -> String
}

/// Errors a `STT` provider may surface.
public enum STTError: Error {
    /// The recognizer is missing or temporarily unavailable on this device/locale.
    case unavailable
    /// Speech recognition permission was not granted.
    case notAuthorized
    /// Audio was processed but no speech was recognized.
    case noSpeechDetected
    /// Recognition failed; payload carries a human-readable reason.
    case failed(String)
}
