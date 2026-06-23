# OpenWispr — macOS (planned)

The macOS port. Not implemented yet — this directory is a placeholder so the monorepo
structure is in place.

## Intended approach

- **Reuse the pipeline contract, don't re-derive it.** The deterministic cleanup
  algorithm is single-sourced for eval from the Android Kotlin in
  `../android/app/src/main/java/com/voicerewriter/textproc`; the macOS port should mirror
  the same stages and the prompt/tone contract in [`../shared/prompts`](../shared/prompts).
- **Reuse the eval harness.** `../eval` scores the real pipeline; a macOS bridge analogous
  to the Android `EvalDumpReceiver` lets the same datasets validate this port.
- **On-device first.** Whisper (whisper.cpp) for STT and a small local model for polish,
  mirroring the Android stack, with the macOS accessibility APIs for auto-insert.

See the architecture and learnings in [`../docs`](../docs) and the reference
implementation in [`../android`](../android).
