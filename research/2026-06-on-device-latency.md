# On-device dictation latency — GPU offload + bottleneck map (June 2026)

Research goal: cut OpenWispr on-device dictation latency toward ~2s p50 on a Samsung S25 Ultra
(SM8750 / Adreno 830) **without losing accuracy**. Pipeline: Whisper STT → deterministic textproc →
gated LLM cleanup (Qwen3-0.6B finetune). Eval = fixed 51-case text set (+ 22 audio clips), gate =
keep-rate ≥95% / hallucination ≤5% / no WER regression / 100% coverage.

## TL;DR

**The p50 goal is met.** GPU LLM + gating + CPU `tiny` STT ⇒ **e2e p50 ≈ 1058 ms (< 2s)**.

| Lever | Verdict | Headline |
|---|---|---|
| LLM cleanup → Adreno GPU (MLC-LLM, dlight M-tile fix) | **KEEP** | prefill 11→75 tok/s (6.8×); per-call 1607 ms vs CPU 3213 ms (2×); gated p95 1778 ms |
| Gating (skip LLM when deterministic output is clean) | KEEP | median LLM cost → 0 ms (33/51 gated) |
| STT thread bump (4→6/8) | DISCARD | Android cpuset oversubscription + ggml spin-barriers → 30–50× stall |
| STT → Adreno GPU (ggml-vulkan) | DISCARD (blocked) | Qualcomm driver rejects ggml `mul_mat_vec` shaders |

## 1. LLM cleanup on the Adreno GPU (KEEP)

The earlier MLC-LLM GPU attempt was discarded as prefill-bound (~9.4s/call, 3.3× *slower* than CPU).
Root-caused via `mlc_llm compile --debug-dump`: the ~110-token prefill matmul
(`fused_dequantize_NT_matmul`, the seq>8 branch of `LowBatchGemvSpecialize`) is scheduled by
`dl.gpu.Matmul()`'s OpenCL Adreno `Config` in `s_tir/dlight/gpu/matmul.py`, which shipped with
`use_shared=False` and an **M-tile of 8 rows/block** → the weight tile is re-streamed from global
memory ~`seq/8`≈14× = memory-bound ~11 tok/s.

**Fix** (`research/adreno-prefill-mtile-fix.patch`, pure-Python dlight edit, no TVM C++ rebuild):
`block_size_y=8, micro_size_y=4` (M-tile 8→32, 4× fewer weight re-reads) + `use_shared=True,
storage_align=True` (stages an 8 KB dequant-weight tile in shared, ~9 KB < 16 KB budget).
`use_shared` *alone* did nothing — it's weight-reuse-bound, so the **M-tile is the lever**.

Measured (S25, same-day, Tier-3): prefill **11→75 tok/s**; per-call (all-LLM) **1607 ms vs CPU 3213 ms**;
gated production **e2e p50 0 ms / p95 1778 ms**, GATE=PASS (keep 91.2%, WER 0.043, names 100%).
Caveat: gate-OFF GPU keep 88% < CPU 96.5% (MLC-q4f16_1 clean-case quality gap — gated away in prod).

**Gotcha:** `mlc_llm package` caches the compiled lib by the *schedule-independent* model_lib hash —
must `rm ~/.cache/mlc_llm/model_lib/*.tar` + `MLC_JIT_POLICY=REDO`, or schedule edits are silently
ignored (cost one false-negative measurement).

## 2. STT is now the latency floor (CPU-only)

Once the LLM is on GPU + gated, **Whisper STT (whisper.cpp, CPU NEON, no GPU backend) dominates.**
On-device e2e measured via a new `mode=e2e` harness (WAV→STT→det→gated GPU LLM):

| STT model @ 4 threads | STT p50 | e2e p50 |
|---|---|---|
| **tiny (prod default)** | **959 ms** | **1058 ms** |
| base | 2096 ms | 2133 ms |
| small | 7464 ms | 7505 ms |

> ⚠️ The test device was mis-set to `small` STT (~7.5s/utterance). Production default is `tiny`.

### STT thread bump — DISCARD
The SM8750 is all-big-core (2 prime + 6 perf, no little cores), so >4 threads seemed promising — but
forcing 6/8 threads made the **first clip jump to ~33s**. Cause: ggml uses spin-wait thread barriers,
and Android grants a foreground service only a subset of cores (cpuset); oversubscription → spinning
threads starve workers → 30–50× slowdown. whisper.cpp's `coerceIn(2,4)` cap is correct.

### STT → Adreno GPU via ggml-vulkan — DISCARD (blocked)
PoC: built upstream whisper.cpp 0.15.3 with `GGML_VULKAN=ON` for arm64 (host `glslc`/shaderc +
`vulkan-headers`/`spirv-headers` + NDK `libvulkan.so`). `whisper-cli` runs on the S25 and detects the
**Adreno 830** (fp16:1, warp 64, 32 KB shared, **no matrix cores**) — but **aborts at
`createComputePipeline: ErrorUnknown` for the entire `mul_mat_vec` shader family** (both
`mul_mat_vec_f16_f32_f32` and `mul_mat_vec_q8_0_q8_1_f32`). No env toggle avoids it
(DISABLE_F16/COOPMAT/COOPMAT2/BFLOAT16/INTEGER_DOT/MMVQ/…). Qualcomm's Adreno Vulkan driver rejects
ggml's subgroup-based mat-vec SPIR-V. Reviving needs deep shader patching with uncertain payoff (no
matrix cores, 32 KB shared) — not worth it with p50 already met. CPU `-ng` path works (924 ms / 12s clip).

## Reproduce

- GPU LLM fix: apply `research/adreno-prefill-mtile-fix.patch` to the pinned tvm
  (`mlc-llm 2c68d792` + tvm `bfd7787a`, pre-tirx), then `MLC_JIT_POLICY=REDO mlc_llm package`,
  copy the `.so` into `android/mlc4j/output/arm64-v8a/`, rebuild.
- e2e/STT sweeps: `am broadcast -a com.voicerewriter.RUN_EVAL_DUMP -n com.voicerewriter/.EvalReceiver
  --es mode e2e --ez gate true --es engine gpu --es stt <tiny|base|small> [--ei threads N]`.
- Vulkan PoC: `~/Documents/Personal/whisper.cpp`, `build-vk-android` (see this doc for cmake flags).
