# OpenWispr — Android

A Wispr Flow-style voice tool with a text-rewrite flow, native on
Android. **Tap the floating bubble → speak → the cleaned-up / rewritten text is
typed straight into the field you're in.** Or select text in any app → tap
**Rewrite** in the selection toolbar → pick an action → the rewrite replaces the
selection in place.

It ships a tuned set of prompts, a voice profile, anti-AI guardrails, and
OpenAI-compatible gateway logic (Vercel AI Gateway / OpenRouter / Anthropic /
custom). Both stages can run **fully on-device**: speech-to-text via whisper.cpp and the
rewrite/cleanup via llama.cpp (a small local model) — or via the cloud
(OpenAI-compatible Whisper + Anthropic/OpenRouter/custom). Each is selectable in
Settings, so you can mix (e.g. local STT + cloud LLM) or go fully offline.

## How it works

- **Voice flow** (the bubble → `RewriteActivity` voice sheet) — tap the bubble,
  pick a mode, tap the mic and speak. Audio is recorded (`AudioRecorder`),
  transcribed by `SttEngine` (cloud Whisper), then:
  - **Dictate fresh** — the transcript is cleaned up by the LLM (filler,
    punctuation, grammar) and inserted at the cursor.
  - **Rewrite clipboard** — copy text first, then *speak a command* ("make this
    formal"); the LLM rewrites the clipboard text and replaces the selection.
  **Insert** writes the result into the focused field via the accessibility
  service (`OpenWisprAccessibilityService`, clipboard `ACTION_PASTE`). If the
  service is off or no field is focused, it falls back to copying.
- **`PROCESS_TEXT` intent** (`RewriteActivity`) — Android shows "Rewrite" in the
  text-selection toolbar in every app. The activity returns the rewritten text
  via `EXTRA_PROCESS_TEXT`, replacing the selection in editable fields.
- **Floating bubble** (`BubbleService`) — always-on draggable overlay button
  (foreground service + "Display over other apps"). Tapping it opens the voice
  sheet.
- **`SettingsActivity`** — LLM provider / key / model / voice profile / anti-AI /
  temperature, **STT provider / key / model**, default voice mode, dictation
  cleanup toggle, accessibility enable, and the bubble switch — stored in DataStore.
- **`RewriteEngine`** — builds the system prompt, streams from the gateway over
  SSE (OkHttp), cleans output. Adds `streamDictationCleanup` and
  `streamInstruction` paths for the voice modes.
- **Speech-to-text — two backends behind one seam:**
  - **Cloud** (`SttEngine`) — multipart upload to an OpenAI-compatible
    `/v1/audio/transcriptions` (Groq / OpenAI / custom).
  - **On-device** (`LocalWhisperStt` + `:lib`) — whisper.cpp running locally, fully
    offline and private. `WhisperModelManager` downloads the multilingual `small`
    model (~488MB) once into app storage. Audio is captured as raw 16 kHz mono PCM
    (`AudioRecorder`) and fed directly to whisper as float samples (cloud gets a WAV).
  The active backend is the **STT provider** chosen in Settings.

## Build

Requires JDK 17 (Android Studio bundles it) and the Android SDK. The on-device
Whisper module (`:lib`) compiles native code, so you also need the **NDK** and
**CMake** (install via Android Studio → SDK Manager → SDK Tools). The native build
targets **arm64-v8a** only (see `lib/build.gradle.kts` `abiFilters`).

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or open the `android/` folder in Android Studio and Run.

The `:lib` module vendors a pinned copy of whisper.cpp (v1.7.4, CPU-only ggml
backend) under `lib/src/main/jni/whispercpp/`. The `:llm` module vendors
llama.cpp + ARM's `aichat` JNI under `llm/src/main/cpp/`, built with **NDK 29 +
CMake 3.31** (install both via SDK Manager) for arm64-v8a, CPU-only.

## Install (sideload, no Play Store)

With the phone connected over USB (USB debugging on):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and tap it (allow "install unknown apps" once).
A debug-signed APK is fine for personal use indefinitely.

## First run

1. Open **OpenWispr** from the launcher.
2. **LLM:** pick a provider. For **On-device (Gemma/Qwen)**, choose a model
   (Gemma-3-270M default) and tap **Download** (one time). For cloud providers,
   paste your API key and model. Optionally write a voice profile.
3. **Voice:** under **Voice**, pick a speech-to-text provider:
   - **On-device (Whisper)** — fully offline, no key. Tap **Download** to fetch the
     model once (~488MB); wait for "Ready".
   - **Groq / OpenAI / custom** — cloud; paste the provider key (Groq keys start
     with `gsk_`; get one at console.groq.com → API Keys).
   Choose your default mode and the dictation-cleanup toggle. **Save**.
4. **Auto-insert:** tap **Enable** next to "Auto-insert (accessibility)" and turn
   on *OpenWispr* under Settings → Accessibility. Without it, results are
   copied to the clipboard instead of typed in.
5. Turn on the **Floating bubble** (grant "Display over other apps"). The first
   mic tap also asks for the microphone permission.
6. **Use it:** tap the bubble → tap the mic → speak → **Insert**. Or in any app,
   select text → **Rewrite** in the selection toolbar → pick an action → **Accept**.

## Floating bubble

Settings → toggle **Floating bubble** on. The first time it asks for "Display
over other apps" — allow it. A blue bubble appears; drag it anywhere.

Gestures:
- **Single tap** → starts dictation immediately. The bubble turns red and shows a
  live **waveform** while you speak. **Tap again** (or tap anywhere) to stop; the
  rewrite is transcribed, cleaned up, and you tap **Insert**.
- **Long-press** → starts a **Rewrite clipboard** recording (speak a command like
  "make this formal" to rewrite whatever you copied).
- **Drag** → reposition the bubble. Drag it onto the **✕ target** at the bottom
  center to dismiss it (re-enable later from Settings → Floating bubble).

With accessibility on, the result types into your focused field; otherwise it's
copied to paste.

The bubble survives until you toggle it off or reboot. (No boot-autostart yet;
Android restricts starting overlays from boot — add a `BOOT_COMPLETED` receiver
if you want it back automatically.)

**"I don't see the bubble":** overlays do **not** render over the lock screen or
Always-On Display — unlock the phone first. To start/stop it without the UI
(testing, automation, a future Quick Settings tile):

```bash
adb shell am broadcast -a com.voicerewriter.START_BUBBLE -n com.voicerewriter/.BubbleControlReceiver
adb shell am broadcast -a com.voicerewriter.STOP_BUBBLE  -n com.voicerewriter/.BubbleControlReceiver
```

## Notes / limits

- In-place replace works in editable fields. Some apps restrict the action or
  mark selections read-only; there you get clipboard copy.
- The six prompts are the built-in defaults (not yet editable in the UI —
  edit `Defaults.DEFAULT_PROMPTS` to change them).
- The API key lives in app-private DataStore. For a personal sideloaded build
  that's fine; swap to EncryptedSharedPreferences if you want at-rest encryption.
