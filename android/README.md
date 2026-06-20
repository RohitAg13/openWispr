# OpenWispr — Android

A Wispr Flow-style voice tool plus the original text-rewrite flow, native on
Android. **Tap the floating bubble → speak → the cleaned-up / rewritten text is
typed straight into the field you're in.** Or select text in any app → tap
**Rewrite** in the selection toolbar → pick an action → the rewrite replaces the
selection in place.

It reuses the extension's exact prompts, voice profile, anti-AI guardrails, and
OpenAI-compatible gateway logic (Vercel AI Gateway / OpenRouter / Anthropic /
custom). Speech-to-text uses any OpenAI-compatible Whisper endpoint (Groq /
OpenAI / custom). This is **v1 with cloud STT + cloud LLM**; the engines are
isolated so on-device Whisper and a local SLM can drop in later.

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
- **`SttEngine`** — multipart upload to `/v1/audio/transcriptions`, returns the
  transcript.

## Build

Requires JDK 17 (Android Studio bundles it) and the Android SDK.

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or open the `android/` folder in Android Studio and Run.

## Install (sideload, no Play Store)

With the phone connected over USB (USB debugging on):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and tap it (allow "install unknown apps" once).
A debug-signed APK is fine for personal use indefinitely.

## First run

1. Open **OpenWispr** from the launcher.
2. **LLM:** pick a provider, paste your API key, set the model, optionally write
   a voice profile.
3. **Voice:** under **Voice**, pick a speech-to-text provider (Groq is the
   default — free tier, fast), paste its key (Groq keys start with `gsk_`; get
   one at console.groq.com → API Keys), optionally set a model. Choose your
   default mode and the dictation-cleanup toggle. **Save**.
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
- The six prompts are the extension defaults (not yet editable in the UI —
  edit `Defaults.DEFAULT_PROMPTS` to change them).
- The API key lives in app-private DataStore. For a personal sideloaded build
  that's fine; swap to EncryptedSharedPreferences if you want at-rest encryption.
