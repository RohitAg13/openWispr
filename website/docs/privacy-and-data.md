---
title: "What leaves your device when you use OpenWispr"
description: "Every host the OpenWispr apps can contact and the exact condition that makes them do it, what is stored locally and for how long, and a source-level confirmation that neither app bundles an analytics, telemetry, or crash-reporting SDK."
canonical: "https://openwispr.dev/docs/privacy-and-data.html"
language: "en"
---


# What leaves your device

On a default install, nothing but the one-time model download. This page names every host the app can reach and the exact condition under which it does — because "private" is a claim, and a list is checkable.

*The short version*

## Three pieces of code can open a socket

In the entire Android app there are exactly three components that can make a network request: the model downloader, the rewrite engine, and the speech engine. There is no fourth. The last two are inert on a default install, because both the speech provider and the polish provider default to on-device, and both refuse to run without an API key you supplied yourself.

So on a fresh install, after the models have downloaded, dictating opens no connection at all. Not a heartbeat, not a check-in, not a usage ping. That is a structural property of the code rather than a policy that could be quietly changed.

This page is a technical description of the apps. The [privacy policy](/privacy.html) is the legal document, and it also covers this website, which is a separate matter — see the last section.

*Downloads*

## The one thing that always needs the network

Models are large and are not shipped inside the app, so they are fetched once, from Hugging Face, when you install them. Nothing about your speech, your text, or your device is sent in the process; these are ordinary file downloads.

The complete list of what can be fetched, on Android:

| Repository on huggingface.co | What |
| --- | --- |
| `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8` | The Parakeet speech model, four files |
| `ggerganov/whisper.cpp` | Whisper tiny, base or small |
| `rohitag13/openwispr-cleanup-qwen3-0.6b-GGUF` | The project's own cleanup fine-tune |
| `ggml-org/gemma-3-270m-qat-GGUF` | Gemma 3 270M, the small-device cleanup model |
| `Qwen/Qwen3-0.6B-GGUF` | Qwen3 0.6B, if you choose it |

*Opt-in*

## The cloud providers, and the two gates in front of them

Android — and only Android; the Mac app has no cloud path at all — can be pointed at a cloud provider for speech, for polish, or for both. Nothing about this is a default and nothing about it is silent.

There are two independent gates. The provider must be changed from "local" to something else, *and* a key must be present. Without a key, the request throws before a socket is opened, so selecting a provider and not finishing the setup sends nothing. When a cloud speech provider is selected, the settings screen states plainly that audio is sent to that provider, and offers a one-tap line to switch back to on-device.

When you do enable one, the traffic goes from your device straight to the provider you configured, using your own API key. It does not pass through anything belonging to this project, and the project has no account, no server, and no way to see it.

| Stage | Providers | What is sent |
| --- | --- | --- |
| **Speech** | Groq · OpenAI · any OpenAI-compatible endpoint you name | The recorded audio file, plus your vocabulary bias prompt |
| **Polish** | Anthropic · Vercel AI Gateway · OpenRouter · any OpenAI-compatible endpoint you name | The cleaned-up text |

*Telemetry*

## There is no analytics SDK, and here is what that is based on

The Android app's declared dependencies are: AndroidX core, activity-compose and lifecycle; Jetpack Compose with Material 3; Google's Material components; DataStore; OkHttp; Kotlin coroutines; ONNX Runtime; a local sherpa-onnx package; and the project's own whisper.cpp and llama.cpp modules. The macOS app declares four local Swift packages and nothing else.

No Firebase, no Crashlytics, no Sentry, no Amplitude, no Mixpanel, no PostHog, no Bugsnag, no App Center, no Google Analytics, no Datadog. Not disabled — not present.

The honest scope of that statement: it is a reading of the source and the dependency declarations in the public repository, not a teardown of the published binary. It is checkable by anyone, which is rather the point of the app being MIT-licensed.

*Feedback*

## The feedback buttons do not send anything

Settings → Feedback has three rows, and none of them transmits anything by itself. Each hands off to another app: your mail client, your browser on a GitHub issue, or Google Play. You see the message and you send it, or you do not.

The diagnostics block the app prepares to save you typing contains exactly four things: the app version, your device manufacturer and model, and your Android version and SDK level. No identifier, no history, no transcript.

*On the device*

## What is stored locally, and for how long

The privacy story is not only about what is sent — it is also about what accumulates. All of the following is in the app's private storage, none of it is uploaded, and all of it can be deleted from Settings → Privacy → Clear all data.

| What | Retention | Why it exists |
| --- | --- | --- |
| **Dictation history** | The most recent 100 entries. Off in one tap. | Powers the personalisation features. |
| **Pending audio** | 30 days by default; 7, 90 or unlimited also available. Capped at 200 recordings regardless. | So a dictation that failed can be run again rather than lost. Turning history off purges it immediately. |
| **Correction corpus** | The most recent 500 edits. Not written at all when history is off. | The two style examples shown to the polish model. |
| **Personal dictionary and learned aliases** | Until you delete them | Biasing the speech engine towards your names and terms. |
| **Per-app tone overrides** | Until you delete them | Your own tone instructions per category. |
| **API keys** | Until you clear them | Only exist if you configured a cloud provider. |

*On the device*

## One thing worth knowing about the keys

If you configure a cloud provider, your API key is stored in the app's private DataStore preferences as plain text. It is not wrapped in the Android Keystore and it is not encrypted at rest. On a normal, unrooted device with no backup extraction it is private to the app — but "private to the app" is not the same as "encrypted", and this page would rather name the difference than let you infer the stronger claim.

The macOS app stores no API keys at all, because it has no cloud provider option.

*The website*

## The apps and this site are not the same thing

This distinction is load-bearing and blurring it would be exactly the kind of thing this page exists to avoid. **The apps ship no analytics of any kind.** This website does: it runs a self-hosted Umami session recorder, which is more than page counting — it replays pointer movement, scrolling, clicks and page changes.

Section 5 of the [privacy policy](/privacy.html) says so in those terms rather than softening it into "anonymous usage data". If you would rather read the site without any of that, every page here is also served as Markdown at the same address with a `.md` extension — this one is at `/docs/privacy-and-data.md` — and those run no scripts at all.

## Questions

**Does OpenWispr send my voice anywhere?**

Not on a default install. Speech recognition runs on your device, and the app opens no connection during a dictation. Audio is only sent if you deliberately switch the speech engine to Groq, OpenAI, or a custom endpoint and supply your own API key — and the settings screen says so on the same screen where you switch.

**Can I use OpenWispr fully offline?**

Yes, once the models are downloaded. The download is the only thing the default configuration ever needs the network for.

**Does the app contain any analytics or crash reporting?**

No. Neither the Android nor the macOS app declares any analytics, telemetry or crash-reporting dependency. This is a source-level statement about the public repository rather than a binary teardown — and the source is MIT-licensed, so it is checkable.

**What can the OpenWispr developers see?**

Nothing about your usage. There is no account, no server belonging to the project, and no path by which a transcript or a recording could reach it. If you enable a cloud provider, that traffic goes directly from your device to that provider with your key; it does not pass through anything the project runs.

**Are my recordings kept?**

Recordings are kept on the device for 30 days by default so a failed dictation can be retried, capped at the 200 most recent. You can set that to 7, 90, or unlimited, and turning off history purges them all immediately.

**Is the website tracked?**

Yes, and it is worth separating from the apps. This site runs a self-hosted Umami session recorder, which replays pointer movement and clicks rather than only counting page views. The apps carry no analytics at all. Every page is also available as a Markdown mirror that runs no scripts.

## Read next

- [Settings reference](/docs/settings.html)
- [Privacy policy](/privacy.html)
- [Dictation with no server to send your keystrokes to](/use-cases/dictation-it-wont-block.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/docs/privacy-and-data.html.
