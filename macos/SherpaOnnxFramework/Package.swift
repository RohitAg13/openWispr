// swift-tools-version:5.10
import PackageDescription

// Thin wrapper package around the sherpa-onnx + onnxruntime xcframeworks (the native engine
// behind on-device Parakeet STT). The two xcframeworks are LOCAL `binaryTarget`s under
// `binaries/` — fetched by `fetch-binaries.sh`, not committed to git (see `.gitignore`). They're
// local rather than remote because we have to post-process onnxruntime: both upstream xcframeworks
// ship a top-level `Headers/module.modulemap`, and Xcode copies both to the same `include/`
// directory → "Multiple commands produce include/module.modulemap". sherpa's C API doesn't
// include any ONNX Runtime headers (ORT is a pure link-time dependency), so `fetch-binaries.sh`
// strips onnxruntime's module map, leaving a single module map and resolving the collision.
//
// The binaries are the community SwiftPM distribution of k2-fsa/sherpa-onnx
// (github.com/willwade/sherpa-onnx-spm), pinned to v1.13.3 — the same sherpa-onnx version the
// Android app vendors. `fetch-binaries.sh` verifies each zip's sha256 before unpacking.
//
// NOTE: a `.binaryTarget` xcframework only resolves under an **Xcode** build — the plain SwiftPM
// CLI (`swift build`/`swift test`) can't link xcframeworks. That's why this lives in its own
// package consumed only by the app target, and never by `OpenWisprCore` (whose unit tests run via
// `swift test`). Mirrors `WhisperFramework`/`LlamaFramework`.
let package = Package(
    name: "SherpaOnnxFramework",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "SherpaOnnxFramework", targets: ["SherpaOnnxFramework"]),
    ],
    targets: [
        .binaryTarget(name: "sherpa-onnx", path: "binaries/sherpa-onnx.xcframework"),
        .binaryTarget(name: "onnxruntime", path: "binaries/onnxruntime.xcframework"),
        // Re-exports the C `sherpa_onnx` module (via the vendored SherpaOnnx.swift helper) and
        // adds a small `public` Parakeet facade so the app imports one Swift module. The C/C++
        // runtime + Accelerate are linked here so callers don't have to.
        .target(
            name: "SherpaOnnxFramework",
            dependencies: ["sherpa-onnx", "onnxruntime"],
            linkerSettings: [
                .linkedLibrary("c++"),
                .linkedFramework("Accelerate"),
            ]
        ),
    ]
)
