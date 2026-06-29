# Vendored sherpa-onnx AAR — how to (re)fetch

`sherpa-onnx-static-link-onnxruntime-1.13.3.aar` (~37MB) is gitignored (a regenerable
prebuilt binary). It provides the on-device Parakeet/Moonshine STT runtime (sherpa-onnx
+ a statically-linked onnxruntime, so it does **not** ship a separate `libonnxruntime.so`
and therefore does not collide with the `com.microsoft.onnxruntime` AAR used by Silero VAD).

There is no official Maven artifact for sherpa-onnx Android, so we vendor the release AAR.
To fetch it:

```sh
cd android
gh api repos/k2-fsa/sherpa-onnx/releases/tags/v1.13.3 \
  --jq '.assets[] | select(.name=="sherpa-onnx-static-link-onnxruntime-1.13.3.aar") | .url' \
  | xargs -I{} curl -sL -H "Accept: application/octet-stream" {} \
      -o app/libs/sherpa-onnx-static-link-onnxruntime-1.13.3.aar
```

`settings.gradle.kts` exposes `app/libs` as a `flatDir` repo and `app/build.gradle.kts`
depends on it by name. The app's `abiFilters` is `arm64-v8a` only; a `pickFirst` on
`**/libonnxruntime.so` handles the AAR's stray x86 copy at merge time.

## Model weights (not in the repo)

The Parakeet-TDT-0.6b-v2 int8 bundle (~631MB: encoder/decoder/joiner `.int8.onnx` + `tokens.txt`)
is downloaded at runtime by `ParakeetModelManager` from
`huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8`, into
`filesDir/models/parakeet/`. For on-device benchmarking you can push the bundle straight there.
