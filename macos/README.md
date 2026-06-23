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

## Roadmap (next)

Mirroring the Android stack and the the cleanup pipeline blueprint:
- **Audio + VAD** — AVAudioEngine capture + Silero VAD (auto-stop on pause).
- **STT** — whisper.cpp on-device; cloud Whisper optional.
- **LLM polish** — llama.cpp on-device (Off/Light/Medium/Full), with the same over-edit
  guards and the personalization corpus / few-shot (see
  [`../docs/personalization.md`](../docs/personalization.md)).
- **Auto-insert** — macOS Accessibility API (AX) to type into the focused field.
- **App shell** — menu-bar app + overlay, brand from [`../design_assets`](../design_assets).
- **Eval bridge** — a macOS analogue of Android's `EvalDumpReceiver` so `../eval` scores
  this port on the same datasets.

## Reference

Android is the reference implementation: [`../android`](../android). Architecture and
learnings: [`../docs`](../docs).
