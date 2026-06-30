# SherpaOnnxFramework — vendored sherpa-onnx for macOS

Local SwiftPM package wrapping the **sherpa-onnx + onnxruntime xcframeworks** that power the
on-device **Parakeet** STT engine on macOS. Mirrors `WhisperFramework`/`LlamaFramework`, but the
binaries are vendored locally (see below) rather than fetched as a remote `binaryTarget`.

## Fetching the binaries (gitignored)

`binaries/{sherpa-onnx,onnxruntime}.xcframework` are large prebuilt binaries, gitignored and
regenerable. Fetch them once before building:

```sh
bash macos/SherpaOnnxFramework/fetch-binaries.sh
```

The script downloads the v1.13.3 release zips from `github.com/willwade/sherpa-onnx-spm` (the
community SwiftPM distribution of k2-fsa/sherpa-onnx — the same sherpa-onnx version the Android app
vendors), verifies each zip's sha256, and unpacks them into `binaries/`.

### Why local, not a remote `binaryTarget`

Both upstream xcframeworks ship a top-level `Headers/module.modulemap`. Xcode copies both into the
same `include/` directory during the build → `error: Multiple commands produce
.../include/module.modulemap`. sherpa's C API (`sherpa-onnx/c-api/c-api.h`) includes **no** ONNX
Runtime headers — ORT is a pure link-time dependency — so `fetch-binaries.sh` strips onnxruntime's
module maps, leaving a single module map (`sherpa_onnx`) and resolving the collision. That
post-processing is why we vendor locally and reference the xcframeworks by `path:`.

CI fetches the binaries in the macOS job of `.github/workflows/release.yml` before building.

## Model weights (not in the repo)

The Parakeet-TDT-0.6b-v2 int8 bundle (~661 MB: encoder/decoder/joiner `.int8.onnx` + `tokens.txt`)
is downloaded at runtime by `ParakeetModelManager` from
`huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8`, into
`~/Library/Application Support/OpenWispr/models/parakeet/`.
