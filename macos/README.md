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
run `./generate.sh` only after editing `project.yml`. Bundle id `com.openwispr.mac`.

### Stable local signing (one-time)

Run **`scripts/setup-signing.sh`** once. It creates a self-signed *code-signing* identity
("OpenWispr Self-Signed") in a dedicated keychain and points `project.yml`'s
`CODE_SIGN_IDENTITY` at it. Why: with ad-hoc signing the app's code identity changes on every
build, so macOS forgets the **Accessibility** grant and you'd re-add it each time. A stable
identity gives a stable Designated Requirement (`identifier "com.openwispr.mac" and certificate
leaf = …`), so the grant persists across rebuilds. Fully non-interactive (the dedicated
keychain's password is set by the script, so `codesign` never prompts), no Apple Developer
account. Undo with `scripts/setup-signing.sh --remove` (then set `CODE_SIGN_IDENTITY` back to
`"-"`).

**First run needs manual permission grants** (macOS privacy / TCC, which can't be scripted):
the microphone prompt on first capture, and adding OpenWispr under **System Settings →
Privacy & Security → Accessibility** for auto-insert. With stable signing you grant
Accessibility **once** and it sticks across future rebuilds.

### Packaging a `.dmg`

**`scripts/package-dmg.sh`** builds a Release `OpenWispr.app` and wraps it in a
drag-to-install disk image (`macos/dist/OpenWispr-<version>.dmg`), signed with the local
self-signed identity. STT/LLM models download at runtime, so the image is small (~7 MB). A
self-signed image runs locally but is **not notarized** — on someone else's Mac, Gatekeeper
blocks it until they right-click → Open (or `xattr -dr com.apple.quarantine`). Real
distribution needs an Apple **Developer ID Application** cert + notarization (a paid Apple
Developer account); the script does it automatically when `DEVELOPER_ID_APP` + `NOTARY_PROFILE`
are set (see the comments at the bottom of the script).

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
  Microphone (always) + Speech Recognition (Apple provider only) at first use.

### whisper.cpp STT — fully offline (done)

A second `STT` provider, `WhisperSTT`, runs [whisper.cpp](https://github.com/ggml-org/whisper.cpp)
fully on-device/offline. Files:
- **`WhisperFramework/`** — a local Swift package that wraps the official
  `whisper.xcframework` via a **remote `binaryTarget`** (url + checksum, pinned to `v1.9.1`).
  SwiftPM downloads & caches the ~50 MB binary; nothing is committed. It re-exports the C
  `whisper` module so the app does one `import WhisperFramework`. Kept separate from
  `OpenWisprCore` so the core's CLI `swift test` (which can't link xcframeworks) stays pure.
- **`App/Sources/WhisperSTT.swift`** — an `actor`-wrapped provider (whisper's context isn't
  thread-safe). Feeds the 16 kHz mono Float32 samples `AudioCapture` already produces straight
  into `whisper_full` (no resampling), keeps the model warm across takes, English-pinned.
- **`App/Sources/WhisperModel.swift`** — `WhisperModel` (`tiny.en`/`base.en`/`small.en`) +
  `WhisperModelManager`: stores ggml weights under `~/Library/Application Support/OpenWispr/
  models/`, downloads the selected model once from Hugging Face (streamed, atomic, with
  progress), and reports availability.
- **`STTFactory`** routes both dictation flows to the chosen engine, warm-caching the Whisper
  context; if Whisper is selected but its model isn't downloaded, it falls back to Apple Speech
  so dictation still works (Settings prompts the download).
- **Settings** gains a model picker + download/remove/progress UI under *Speech-to-text*.

The macOS deployment target is **13.3** (the whisper.xcframework macOS slice's min-OS).

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

### Settings (done)

`App/Sources/{AppSettings,HotKeyRecorder,SettingsView}.swift`: a Settings window (opened from
the popover's "Settings…" button) backed by a persisted `AppSettings.shared` (UserDefaults).
- **Rebindable global hotkey** — a `HotKeyRecorder` captures a key combo (NSEvent→Carbon),
  guards bare keys; the coordinator re-registers the Carbon hotkey live via Combine.
- **Mic sensitivity** (Low/Med/High) — maps to `EnergyVAD` ratios; the capture's VAD is
  rebuilt between sessions.
- **STT provider** + **Cleanup/polish level** selectors — persisted; each with an inline
  model picker + download/remove/progress row when its on-device engine is selected.

### LLM polish — on-device rewrite (done)

An optional on-device LLM rewrites the deterministic-cleaned transcript, graded by the
**Cleanup/polish** setting (`Off` / `Light` / `Medium` / `Full`). Runs [llama.cpp](https://github.com/ggml-org/llama.cpp)
via the vendored `llama.xcframework`. Files:
- **`LlamaFramework/`** — local Swift package wrapping `llama.xcframework` via a remote
  `binaryTarget` (pinned to `b9786` + checksum), same pattern as `WhisperFramework`. ~200 MB
  fetched/cached by SwiftPM, not committed.
- **`OpenWisprCore`** (pure, tested): `PolishLevel` + `LlmPolish` (per-level system prompt —
  a lean descendant of Android's `buildLocalSystemPrompt` — folding in `AppContext` per-app
  tone for `medium`/`full`), and `PolishGuards` (the over-edit guards ported from Android
  `RewriteEngine`: `hasSelfCorrection`, `preservesContent`, `looksRepeating`,
  `collapseRepetition`, `cleanOutput`). Pinned by 21 new XCTests (89 total).
- **`App/Sources/LlamaContext.swift`** — `actor` port of the official `llama.swiftui`
  `LibLlama.swift`, adapted for one-shot chat completion: chat-template formatting via
  `llama_chat_apply_template` (ChatML fallback), greedy sampling, early stop on repetition.
- **`App/Sources/LocalLLMEngine.swift`** — warm-loads the model, builds prompts from the core,
  generates, post-processes with `PolishGuards.cleanOutput`, and **rejects an over-edited
  rewrite** (content-preservation guard) — falling back to the deterministic cleanup so the
  user always gets at least that.
- **`App/Sources/LlmModel.swift`** — `LlmModel` (Qwen2.5 `0.5B`/`1.5B` Instruct, q4_k_m GGUF) +
  `LlmModelManager`: stores weights under `~/Library/Application Support/OpenWispr/llm/`,
  downloads once from Hugging Face (streamed, atomic, progress).
- **`DictationCoordinator`** applies polish after cleanup when a level is set and the model is
  downloaded; the focused app sets the tone category.

The macOS deployment target is **13.3** (both vendored xcframeworks' macOS slice min-OS).

## Roadmap (next)

Mirroring the Android stack and the the cleanup pipeline blueprint:
- **Personalization** — the corpus / few-shot bias (L1–L3, see
  [`../docs/personalization.md`](../docs/personalization.md)) feeding the polish prompt.
- **Silero VAD** — replace `EnergyVAD` with the ONNX Silero v5 model, same `VAD` protocol.
- **App polish** — overlay/HUD, brand from [`../design_assets`](../design_assets).
- **Eval bridge** — a macOS analogue of Android's `EvalDumpReceiver` so `../eval` scores
  this port on the same datasets.

## Reference

Android is the reference implementation: [`../android`](../android). Architecture and
learnings: [`../docs`](../docs).
