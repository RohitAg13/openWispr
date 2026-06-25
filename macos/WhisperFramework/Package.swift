// swift-tools-version:5.10
import PackageDescription

// Thin wrapper package around the official whisper.cpp xcframework. The binary is fetched
// as a remote `binaryTarget` (no large artifact committed to git); Xcode/SwiftPM downloads
// and caches the ~50 MB zip once, keyed by the checksum below.
//
// NOTE: a `.binaryTarget` xcframework only resolves under an **Xcode** build — the plain
// SwiftPM CLI (`swift build`/`swift test`) can't link xcframeworks. That's why this lives in
// its own package consumed only by the app target, and never by `OpenWisprCore` (whose unit
// tests run via `swift test`).
//
// To bump the version: change both the `url` tag and the `checksum`. Compute the new
// checksum with `swift package compute-checksum whisper-<ver>-xcframework.zip` (equivalently
// `shasum -a 256`). The macOS slice is `macos-arm64_x86_64` with min-OS 13.3 — keep the app's
// deployment target ≥ 13.3.
let package = Package(
    name: "WhisperFramework",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "WhisperFramework", targets: ["WhisperFramework"]),
    ],
    targets: [
        .binaryTarget(
            name: "whisper",
            url: "https://github.com/ggml-org/whisper.cpp/releases/download/v1.9.1/whisper-v1.9.1-xcframework.zip",
            checksum: "8c3ecbe73f48b0cb9318fc3058264f951ab336fd530e82c4ccdd2298d1311a4c"
        ),
        // Re-exports the C `whisper` module so the app imports one Swift module. The
        // framework's module map already links c++, Accelerate, Metal, and Foundation.
        .target(name: "WhisperFramework", dependencies: ["whisper"]),
    ]
)
