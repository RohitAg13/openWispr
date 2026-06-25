// swift-tools-version:5.10
import PackageDescription

// Thin wrapper package around the official llama.cpp xcframework, mirroring `WhisperFramework`.
// The binary is fetched as a remote `binaryTarget` (no ~200 MB artifact committed); Xcode/
// SwiftPM downloads & caches it once, keyed by the checksum below.
//
// NOTE: a `.binaryTarget` xcframework only resolves under an **Xcode** build — keep this out of
// `OpenWisprCore` (whose `swift test` can't link xcframeworks).
//
// To bump the build: change both the `url` tag (e.g. `b9786`) and the `checksum`
// (`swift package compute-checksum llama-<tag>-xcframework.zip`). The macOS slice is
// `macos-arm64_x86_64` with min-OS 13.3 — keep the app's deployment target ≥ 13.3.
let package = Package(
    name: "LlamaFramework",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "LlamaFramework", targets: ["LlamaFramework"]),
    ],
    targets: [
        .binaryTarget(
            name: "llama",
            url: "https://github.com/ggml-org/llama.cpp/releases/download/b9786/llama-b9786-xcframework.zip",
            checksum: "f4f97927ac6f352d06cc3039e51086b4e38594fab6272ebc59e65069e5603df8"
        ),
        // Re-exports the C `llama` module. The framework's module map links c++, Accelerate,
        // Metal, and Foundation.
        .target(name: "LlamaFramework", dependencies: ["llama"]),
    ]
)
