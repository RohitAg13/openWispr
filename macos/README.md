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

### Accessibility auto-insert (done)

`App/Sources/TextInserter.swift`: inserts the cleaned text into the app you were using.
Tracks the last non-OpenWispr frontmost app (via `NSWorkspace` activation notifications, so
opening our popover doesn't steal the target), reactivates it, then inserts via the
Accessibility API (`AXUIElementCreateSystemWide` → focused element → set `kAXSelectedTextAttribute`)
with a **⌘V clipboard-paste fallback** (save → set → paste → restore) for apps that ignore AX
value-setting. `DictationView` shows an **Insert** button when Accessibility is granted, or an
**Enable auto-insert** button (triggers the system prompt) when it isn't; **Copy** always works.
**Requires the user to grant Accessibility** (System Settings → Privacy & Security → Accessibility).

### Global hotkey + HUD (done) — the hands-free flow

Press **⌃⌥Space** from any app → a **non-activating** overlay appears (the user's text field
keeps focus) → speak → transcribe → clean → auto-insert into that field. Files:
- `HotKey.swift` — Carbon `RegisterEventHotKey` wrapper (system-wide, consumes the combo, **no
  extra permission**; a static id→instance registry routes the C callback back to Swift).
- `RecordingHUD.swift` — `NSPanel` (`.nonactivatingPanel`, floating, `orderFrontRegardless()`)
  hosting a SwiftUI level/transcribing/inserted view with a Cancel button.
- `DictationCoordinator.swift` — app-scope orchestrator (owns the hotkey, `AudioCapture`,
  `AppleSpeechSTT`, HUD); hotkey toggles start/stop, VAD also auto-stops; captures the
  frontmost app at start (the HUD never changes it) and inserts via `TextInserter`.
Wired via an `AppDelegate` (`@NSApplicationDelegateAdaptor`). The menu-bar popover remains as
the manual fallback. Default combo is a constant in `HotKey.swift`, easy to change.

## Roadmap (next)

Mirroring the Android stack and the the cleanup pipeline blueprint:
- **whisper.cpp STT** — fully-offline on-device transcription behind the `STT` protocol.
- **LLM polish** — llama.cpp on-device (Off/Light/Medium/Full) + the personalization
  corpus / few-shot (see [`../docs/personalization.md`](../docs/personalization.md)).
- **Silero VAD**, **settings UI** (hotkey/provider config), **app polish** (brand/HUD design).
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
