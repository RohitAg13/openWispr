# mlc4j native runtime (libtvm4j_runtime_packed.so) — how to regenerate

The 128MB `output/arm64-v8a/libtvm4j_runtime_packed.so` is gitignored (regenerable build artifact).
It bundles the TVM/OpenCL runtime + the statically-linked compiled Qwen3-0.6B model lib
(`system://qwen3_q4f16_1_240bc40143...`). To rebuild:

1. Pin mlc-llm to the pre-tirx commit and build the host toolchain (see the research memory
   `mlc-llm-adreno-probe`): mlc-llm `2c68d792` + tvm submodule `bfd7787a`, `USE_LLVM=ON`,
   `apache-tvm-ffi==0.1.9`.
2. `mlc_llm package --package-config <pkg-ft.json> --output dist-ft` where the model is the
   finetuned q4f16_1 MLC weights. This emits `dist-ft/lib/mlc4j/output/...`.
3. Copy `dist-ft/lib/mlc4j/output/` here.

Model weights (~331MB, also not committed) are pushed to the device at
`/sdcard/Android/data/com.voicerewriter/files/ow-ft-q4f16_1/`.

STATUS: experiment concluded DISCARD — Adreno prefill ~10 tok/s makes per-call ~9.4s vs CPU 2862ms
for this prefill-bound workload. Kept for resurrection if a faster mobile-GPU prefill path appears.
