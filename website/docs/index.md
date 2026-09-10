---
title: "OpenWispr documentation — how the app actually works"
description: "Reference documentation for OpenWispr: what happens between pressing the button and the text appearing, which models run on which device, every setting, and what leaves your device. Written from the source, not from the marketing page."
canonical: "https://openwispr.dev/docs/index.html"
language: "en"
---


# OpenWispr documentation

What the app does between the moment you press the button and the moment the words appear in the field you were typing in — described from the code that does it.

*Start here*

## What OpenWispr is

OpenWispr is a dictation app for Android and macOS. You trigger it, speak, and the text is written into whatever field you were already in. Speech recognition and text cleanup both run on your device by default: on a fresh install there is no account, no server, and nothing to sign in to, and after the one-time model download the app does not need a network connection to transcribe anything.

It is MIT-licensed, and the whole of it is on [GitHub](https://github.com/RohitAg13/openWispr). Everything on these pages was written by reading that source. Where the app's behaviour is surprising, these pages say so rather than smoothing it over, and where the code does not settle a question — model language coverage is the clearest example — they say that too instead of guessing.

*End to end*

## What happens when you dictate

The same pipeline runs on both platforms. Each stage is documented in more detail on the page linked beside it.

- **Trigger.** On Android, the floating bubble: tap it for a long-form take, or hold it and speak and release to send. On macOS, hold the fn/globe key, or use a global shortcut you record yourself. See [Android setup](/docs/getting-started-android.html) and [macOS setup](/docs/getting-started-macos.html).
- **Record.** 16 kHz mono PCM, with a Silero voice-activity model watching the stream so a tap-to-start recording can end itself when you stop talking.
- **Transcribe, on the device.** Parakeet by default, or one of three Whisper sizes. Your personal dictionary is fed to the recognizer as a bias prompt before it decodes, not only applied afterwards. See [Models and device fit](/docs/models.html).
- **Clean up, deterministically.** Seven ordered stages of plain code — no model — that remove hesitations, resolve spoken self-corrections, expand spoken punctuation, digitise numbers, build lists, and capitalise. See [Cleanup and polish](/docs/cleanup-and-polish.html).
- **Polish, optionally.** A small on-device language model refines the cleaned text, at one of four strengths, with guards that throw its answer away and keep the deterministic text if it strays too far from what you said.
- **Insert.** The accessibility service splices the text in at your cursor. If it cannot reach the field, it falls back to the clipboard and tells you nothing was lost. See [Text insertion and permissions](/docs/text-insertion.html).

*The pages*

## Where to go next

Nine pages, each answering a different question. The sidebar carries the same list on every docs page.

| Page | Answers |
| --- | --- |
| [Getting started on Android](/docs/getting-started-android.html) | Install, the four permissions, and how the bubble's tap and hold gestures differ |
| [Getting started on macOS](/docs/getting-started-macos.html) | The unsigned-DMG first launch, the fn-key trigger, and which system permissions macOS asks for |
| [Models and device fit](/docs/models.html) | Every model the app can download, its real size, and which pair your phone gets and why |
| [Settings reference](/docs/settings.html) | Every switch on the Android settings screen, its default, and what it changes |
| [Text insertion and permissions](/docs/text-insertion.html) | How text reaches the field, the fallback chain, and what each permission is for |
| [Cleanup and polish](/docs/cleanup-and-polish.html) | The seven deterministic stages, the four polish levels, and when the model is skipped |
| [What leaves your device](/docs/privacy-and-data.html) | Every host the app can contact, under what condition, and what is stored locally |
| [Troubleshooting](/docs/troubleshooting.html) | The bubble disappearing, text landing on the clipboard, downloads failing, dictation cut short |

*About these docs*

## How to read them, and how they can be wrong

Every number on these pages — a threshold in milliseconds, a model size, a default — was taken from the source rather than from memory or from the app's own marketing copy. That makes them accurate about the code in the repository at the time they were written, which is not the same as accurate about the build on your phone. If a figure here disagrees with what the app tells you, believe the app, and please [open an issue](https://github.com/RohitAg13/openWispr/issues) so the page gets fixed.

There is no separate FAQ page. Questions live at the bottom of whichever page owns the subject, which is where you are already looking when you have them.

Every page here is also served as Markdown at the same address with a `.md` extension — this one is at `/docs/index.md`. Both are generated from the same source, so they cannot disagree with each other.

## Questions

**Does OpenWispr need an internet connection?**

Not to dictate. The speech and cleanup models are downloaded once, over the network, and after that the default configuration transcribes and cleans up entirely on the device. A connection is needed only to download or change models, or if you deliberately switch to a cloud provider. See [What leaves your device](/docs/privacy-and-data.html).

**Is it really free?**

Yes. MIT-licensed, no subscription, no account, no usage limit, and no paid tier. The Android app is on Google Play and the macOS app is a download from GitHub Releases.

**Which platforms are supported?**

Android 7.0 and newer, and macOS 13.3 (Ventura) and newer on both Apple Silicon and Intel. There is no Windows, iOS, or Linux build.

**Is OpenWispr a keyboard?**

No. On Android it is a floating bubble plus an accessibility service, not an input method — you keep your own keyboard and OpenWispr writes into the field alongside it. It also appears in the text-selection toolbar for rewriting text you have already selected.

**Where does the documentation come from?**

From reading the Android and macOS source in the public repository. Where the code does not state something — for example, which languages the Parakeet model covers — these pages say it is not documented rather than repeating a plausible answer.

## Read next

- [Getting started on Android](/docs/getting-started-android.html)
- [Getting started on macOS](/docs/getting-started-macos.html)
- [Models and device fit](/docs/models.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/docs/index.html.
