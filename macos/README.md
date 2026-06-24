# OpenWispr — macOS

The macOS port. The foundational layer — the deterministic text-cleanup pipeline — is
implemented and tested; the audio/STT/LLM/insert layers and the app shell are next.

## What's here

### `OpenWisprCore/` — deterministic pipeline (done)

A Swift Package (Foundation-only, no deps) that is a **faithful 1:1 port of the Android
deterministic pipeline** in `../android/.../textproc`. Same stages, same order, same
behavior — pinned by the Android unit tests ported verbatim to XCTest (32 tests).

```bash
cd macos/OpenWisprCore
swift build
swift test          # 32 tests, mirrors the Android pins
```

Public API mirrors Android:
```swift
import OpenWisprCore
let clean = TextProcessor.process("let's meet at 2 actually 3")   // "Let's meet at 3"
TextProcessor.process("cd slash usr slash bin", isCodeContext: true)  // unchanged in code fields
```

Stages (in order): `EntityNormalizer` → `SelfCorrectionDetector` → `FillerWordRemover` →
`SpokenFormNormalizer` → `NumberNormalizer` → `ListFormatter` → `Capitalizer`. `KRegex.swift`
wraps `NSRegularExpression` to mirror Kotlin's `Regex` so the ports track the source
line-for-line; keep them in sync with Android (see [`../shared/prompts`](../shared/prompts)
for the prompt/tone contract, and the Android `textproc` for the pipeline contract).

### Personal vocabulary + context (done)

Also in `OpenWisprCore`, ported faithfully from Android:
- `VocabEntry` + `VocabCorrector` — snaps mis-heard names/jargon back to their canonical
  spelling after STT (exact alias match always; Soundex + edit-distance fuzzy match for
  entries with aliases; guarded against common words), plus `biasPrompt` (frequency-ranked
  glossary to bias Whisper decoding — learned mishearings first).
- `CodeContext` — decides code/terminal handling (so "dot"/"slash"/"dash" survive as words).
- `AppContext` — classifies the focused app into a category (email/chat/social/notes/code/
  generic) for tone, and holds the per-category `DEFAULT_TONE`.

Pinned by 29 new XCTest cases (Soundex codes, fuzzy/exact matching, common-word guards,
bias ranking, code-mode detection, category mapping). 61 tests total.

### `App/` — menu-bar app shell (scaffolded)

A SwiftUI menu-bar app (`MenuBarExtra`, `LSUIElement` — no Dock icon) that depends on
`OpenWisprCore`. **Non-sandboxed** on purpose: the Accessibility API can't drive other
apps' text fields from inside the App Sandbox (same as the cleanup pipeline). Today it shows a small live
demo of the deterministic cleanup, proving the core is wired into the app bundle; the real
dictation UI replaces it as the audio/STT/insert layers land.

```bash
cd macos/App
brew install xcodegen     # once
./generate.sh             # regenerate OpenWispr.xcodeproj from project.yml
open OpenWispr.xcodeproj   # or: xcodebuild -scheme OpenWispr build
```

The generated `OpenWispr.xcodeproj` is committed, so you can open/build without XcodeGen;
run `./generate.sh` only after editing `project.yml`. Bundle id `com.openwispr.mac`; local
builds use ad-hoc signing ("Sign to Run Locally") — no Apple Developer account needed.

**First run needs manual permission grants** (macOS privacy / TCC, which can't be scripted):
the microphone prompt on first capture, and adding OpenWispr under **System Settings →
Privacy & Security → Accessibility** for auto-insert.

### Audio capture + VAD (done; Silero pending)

- `OpenWisprCore`: `VAD` protocol, `EnergyVAD` (RMS-based, no model), and `SpeechSegmenter`
  — the auto-stop state machine + speech-region trim, a faithful port of Android's
  `AudioRecorder.updateVad`/`stopSamples` (16 kHz, 512-sample frames, start/end probs
  0.5/0.35, 0.8 s hangover, 0.2 s/0.3 s pad). Unit-tested (7 tests; 68 total).
- `App/Sources/AudioCapture.swift`: `AVAudioEngine` capture → `AVAudioConverter` to 16 kHz
  mono Float32 → 512-sample framing → VAD + segmenter, with amplitude + `onAutoStop` and a
  trimmed-samples result. The `VAD` is injected, so **Silero VAD (ONNX) drops in later
  behind the same protocol** without touching the segmenter or capture.

### STT + end-to-end dictation (done; Apple Speech first provider)

- `OpenWisprCore`: `STT` protocol + `STTError` — the Foundation-only seam.
- `App`: `AppleSpeechSTT` (Apple Speech framework, on-device when supported — no library to
  vendor) and a real `DictationView`/`DictationController`: **Listen → AudioCapture (VAD
  auto-stop) → transcribe → `TextProcessor` cleanup → show raw/cleaned + Copy**. Requests
  Microphone + Speech Recognition at first use. `whisper.cpp` will slot in behind `STT` later
  for fully-offline transcription.

## Roadmap (next)

Mirroring the Android stack and the the cleanup pipeline blueprint:
- **Auto-insert** — macOS Accessibility API (AX) to type the cleaned text into the focused
  field (completes the core loop; replaces Copy). Requires the app to be non-sandboxed (it is).
- **whisper.cpp STT** — fully-offline on-device transcription behind the `STT` protocol.
- **Silero VAD** — replace `EnergyVAD` with the ONNX Silero v5 model, same `VAD` protocol.
- **LLM polish** — llama.cpp on-device (Off/Light/Medium/Full), with the same over-edit
  guards and the personalization corpus / few-shot (see
  [`../docs/personalization.md`](../docs/personalization.md)).
- **App polish** — overlay/HUD, brand from [`../design_assets`](../design_assets).
- **Eval bridge** — a macOS analogue of Android's `EvalDumpReceiver` so `../eval` scores
  this port on the same datasets.

## Reference

Android is the reference implementation: [`../android`](../android). Architecture and
learnings: [`../docs`](../docs).
