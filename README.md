# OpenWispr

> **Speak. It types.** A Wispr Flow-style voice tool that turns speech into clean,
> in-your-voice text and inserts it straight into the field you're in — with the whole
> pipeline able to run **fully on-device**.

OpenWispr listens, transcribes, cleans up the transcript deterministically, optionally
polishes it with a small on-device LLM, and auto-inserts the result. It learns your
vocabulary and style the more you use it, and keeps everything on your device unless you
choose otherwise.

## Platforms

| Platform | Status | Location |
|---|---|---|
| Android | Active | [`android/`](android/) |
| macOS | Planned | [`macos/`](macos/) |
| iOS / others | Future | — |

Native ports share one pipeline contract and one eval harness so behavior stays in sync
across platforms (see [`shared/prompts/`](shared/prompts/) and [`eval/`](eval/)).

## How it works

```
mic → VAD → Whisper STT → personal-vocab correction → deterministic cleanup
    → (optional) on-device LLM polish → accessibility auto-insert
```

- **Deterministic cleanup** (pure, zero-latency): fillers, spoken forms, numbers,
  self-corrections, lists, capitalization. The strong default.
- **LLM polish** (opt-in, Off/Light/Medium/Full): a small local model (or a cloud
  provider) refines further, guarded against over-editing.
- **Personalization**: corrections feed a personal dictionary + Whisper bias, your
  closest past edits become few-shot examples, and the corpus can be exported to retrain
  the cleanup model. See [`docs/personalization.md`](docs/personalization.md).

Both STT and the LLM can run on-device (whisper.cpp + llama.cpp) or via cloud providers —
mix or go fully offline.

## Repository layout

```
openwispr/
├── android/   Android app (Kotlin / Jetpack Compose)
├── macos/     macOS port (planned)
├── shared/    cross-platform prompt + tone contract (source of truth)
├── eval/      Python eval harness — single-sources the deterministic
│              pipeline from android/ so scores reflect the real on-device code
├── docs/      personalization, names/emails plan, Wispr usage learnings
└── design_assets/  brand identity
```

Model fine-tuning lives in a **separate repo**, `openwispr-finetune` (heavy training deps
and private usage data are kept out of this tree).

## Build & test (Android)

```bash
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Run the eval harness against the real pipeline: see [`eval/README.md`](eval/README.md).

## License

MIT — see [LICENSE](LICENSE).
