# Contributing to OpenWispr

Thanks for your interest in OpenWispr! Contributions of all kinds are welcome — bug reports,
fixes, features, docs, and platform work.

## Ground rules

- Be respectful. This project follows our [Code of Conduct](CODE_OF_CONDUCT.md).
- Keep changes focused. One logical change per pull request.
- Match the surrounding code's style, naming, and comment density.

## Project layout

- `android/` — the Android app (Kotlin / Jetpack Compose).
- `macos/` — the macOS app (SwiftUI menu-bar) and `OpenWisprCore` (the deterministic pipeline
  as a Swift package).
- `shared/prompts/` — the cross-platform prompt + tone contract. If you change a prompt, update
  **every** mirror listed there in the same commit.
- `docs/` — design notes (e.g. personalization).

The deterministic cleanup pipeline is the heart of the app and is intentionally duplicated
across Android (Kotlin `textproc`) and macOS (`OpenWisprCore`). Changes to one should be
mirrored in the other so behavior stays in sync.

## Building & testing

**Android** (JDK 17; NDK + CMake for the on-device STT/LLM modules):

```bash
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

**macOS** (Xcode 15+, macOS 13.3+):

```bash
cd macos/App && ./generate.sh && open OpenWispr.xcodeproj   # build & run
cd macos/OpenWisprCore && swift test                        # pipeline unit tests
```

Please run the relevant tests before opening a PR. New cleanup behavior should come with a unit
test in both `android/.../textproc` tests and `macos/OpenWisprCore` tests where applicable.

## Pull requests

1. Fork and create a branch from `main`.
2. Make your change with tests.
3. Ensure builds and tests pass on the platform(s) you touched.
4. Open a PR describing the change and the motivation.

## What lives elsewhere

The cleanup model's training pipeline, datasets, and evaluation are maintained in a separate
repository (`openwispr-finetune`) and are out of scope for this repo. This repo ships the apps
and references the published model.
