---
title: "OpenWispr models: every model, its real size, and which one your device gets"
description: "The complete OpenWispr model catalogue — Parakeet, three Whisper sizes, and three cleanup models — with real download sizes, and the exact RAM and storage thresholds that decide which pair your phone downloads on first run."
canonical: "https://openwispr.dev/docs/models.html"
language: "en"
---


# Models, and which ones your device gets

Two speech engines, three cleanup models, and a device check that runs before the first download so a phone never ends up with weights it cannot hold in memory.

*Speech*

## Two on-device speech engines

OpenWispr for Android ships two independent speech backends. Parakeet is the default: NVIDIA's Parakeet-TDT 0.6B v2, quantised to int8 and run through sherpa-onnx. Whisper is the alternative, run through whisper.cpp, in three sizes.

The project's own note on why Parakeet is the default, from the code that loads it: on a Galaxy S25 it measures a median on-device transcription of about 238 ms and a 95th percentile of about 491 ms, with a word error rate on prose below that of Whisper small, which is the largest Whisper the app offers. That is the project's own measurement on one device, not an independent benchmark, and it is quoted here as such.

Parakeet arrives as four files rather than one: an encoder, a decoder, a joiner and a token list. The int8 encoder is roughly 98% of the bytes, which is why the download progress you see is essentially the encoder's.

| Model | Engine | Download | Source |
| --- | --- | --- | --- |
| **Parakeet** — the default | sherpa-onnx, Parakeet-TDT 0.6B v2, int8 | ~631 MB across 4 files | Hugging Face: `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8` |
| **Whisper tiny** — fastest | whisper.cpp, ggml | ~75 MB | Hugging Face: `ggerganov/whisper.cpp` |
| **Whisper base** — balanced | whisper.cpp, ggml | ~142 MB | Hugging Face: `ggerganov/whisper.cpp` |
| **Whisper small** — most accurate | whisper.cpp, ggml | ~488 MB | Hugging Face: `ggerganov/whisper.cpp` |

*Cleanup*

## Three polish models

The polish stage — the optional model pass that runs after the deterministic cleanup — has its own small catalogue of GGUF models run through llama.cpp. The default and recommended one is the project's own fine-tune: Qwen3 0.6B, trained specifically on dictation cleanup with the exact prompt the app feeds it at runtime, which is why it ignores the polish-level instructions and simply runs its trained behaviour.

The other two are stock instruct models, offered because a fine-tune is an opinion and some people would rather bring their own.

| Model | Quantisation | Download |
| --- | --- | --- |
| **OpenWispr Cleanup (Qwen3 0.6B)** — default | Q4_K_M | ~397 MB |
| **Gemma 3 270M** | Q4_0, quantisation-aware trained | ~241 MB |
| **Qwen3 0.6B** | Q8_0 | ~639 MB |

*Device fit*

## Why your phone gets a particular pair

The failure mode this exists to prevent is specific and nasty: the download succeeds, the progress bar fills, everything looks fine — and then the first dictation fails on a phone that never had the memory to hold the weights. So before the first download, OpenWispr reads the device's total memory, Android's own low-RAM-device flag, and the free space in the app's own directory, and picks a pair from that.

The thresholds are set just under each nominal tier, because a phone sold as "4 GB" reports roughly 3.6 GiB to Android once the kernel and hardware carve-outs are taken out, and one sold as "6 GB" reports roughly 5.5 GiB.

| Tier | Chosen when | Speech | Cleanup | Total |
| --- | --- | --- | --- | --- |
| **Full** | Total RAM at or above 5.4 GiB — roughly a 6 GB-class phone and up | Parakeet | OpenWispr Cleanup fine-tune | ~1,028 MB |
| **Compact** | Total RAM at or above 3.4 GiB — roughly a 4 GB-class phone | Whisper base | Gemma 3 270M | ~383 MB |
| **Minimal** | Below that, or any device Android itself flags as low-RAM | Whisper tiny | Gemma 3 270M | ~316 MB |

*Device fit*

## The four rules the check follows

The ordering matters as much as the numbers, and each of these is a deliberate decision rather than an accident of the code:

- **RAM decides the tier; storage can only push it down, never up.** A phone with 8 GB of memory and 400 MB free gets the compact pair, because the full pair cannot land. It does not get the full pair on the strength of its memory alone.
- **Android's low-RAM flag overrides the number.** Manufacturers set it on Go-edition and budget builds, and it means the platform is already trimming background processes hard — so a 600 MB native allocation is far more likely to be the one that gets killed, whatever the total says. Any device with that flag gets the minimal pair.
- **An unreadable memory figure is treated as capable, not as small.** If Android's activity manager cannot be read at all, the check assumes the full tier rather than punishing a phone for an unresponsive system service.
- **A 350 MB storage margin is required on top of the model files**, so a download does not fill the last of the device's free space. If even the minimal pair plus that margin will not fit, the setup screen tells you how many megabytes short you are, before starting anything.

*Device fit*

## Every tier is a real, complete, offline setup

A smaller tier is a smaller model, not a cloud fallback and not a cut-down mode. The minimal configuration still transcribes and still cleans up, entirely on the phone, with no account and no network. What you give up is accuracy on long, fast, accented or noisy speech, which is a genuine cost and one worth stating rather than hiding.

None of it is a lock, either. Settings lists every speech model with its size, marks the one recommended for your device with a badge, and lets you move in either direction — a larger model on a modest phone if you want to try it, or a smaller one on a flagship to save the space. Models you are not currently using can be deleted from the same screen; the active one cannot.

*Downloads*

## How a model download actually behaves

Model files are large and phone connections are not reliable, so the download path is built around being interrupted. Everything here is specific and checkable:

- **Resumable.** A partial download is kept beside the target with the server's validator recorded next to it, and a resume sends an HTTP `Range` request with an `If-Range` condition. If the server answers `200` rather than `206` — meaning the range was ignored or the file changed underneath — the partial is thrown away and the download starts over rather than splicing two different files together. A partial with no recorded validator is also discarded, because there is no way to prove it belongs to this file.
- **Verified, when the server offers a hash.** Hugging Face publishes each file's SHA-256, but on its own redirect rather than on the CDN response that serves the bytes, so the downloader follows the redirect chain to find it. When a hash is available, the finished file is hashed and compared; a mismatch deletes the partial and everything recorded about it and fails loudly, rather than resuming from bytes that are not this file. When no hash is offered, the file is size-checked only, and the log says so.
- **Length-checked either way.** A connection that closes cleanly halfway through a body is indistinguishable from success at the stream level, so the bytes written are compared against the declared total and a short file is an error, not a finished download.
- **Space-checked first.** The download refuses to start if free space is less than what remains to fetch plus a 64 MB margin, and the error names the number of megabytes needed.
- **Not automatically retried.** This is worth being precise about: there is no retry loop. A failure leaves the partial file on disk deliberately and stops. Starting the download again — which is what tapping Get does — picks up from where it stopped. The only automatic retrying is the HTTP client's own transport-level reconnection.

*Languages*

## What the code does not say about languages

This is one of the places where the honest answer is that the source does not settle it, so this page will not pretend otherwise.

The Whisper models are described in the app's own code as a registry of multilingual sizes, and the app never pins them to a language. Beyond that, nothing in the repository states language coverage for Parakeet or for any of the cleanup models: there is no language setting, no locale parameter passed to any recogniser, and no per-model language metadata used for anything. If language coverage matters to your decision, check the upstream model cards for the model you intend to use, rather than trusting a claim on this page.

One platform difference that *is* in the code: the macOS app uses the English-only `.en` Whisper builds and pins decoding to English, where Android uses the multilingual ones. See [Getting started on macOS](/docs/getting-started-macos.html).

## Questions

**Which speech model does OpenWispr use by default?**

Parakeet, on both Android and macOS. If the stored model setting is blank or names something the app does not recognise, it resolves to Parakeet as well. On Android, what onboarding actually downloads depends on your device — a 4 GB-class phone is set up with Whisper base instead.

**How much RAM do I need for offline dictation?**

For the full pair, roughly 6 GB. OpenWispr does not require that: below about 5.4 GiB of reported memory it installs Whisper base with Gemma 3 270M, and below about 3.4 GiB — or on any device Android flags as low-RAM — Whisper tiny with the same cleanup model. All three configurations are fully on-device.

**How much storage do the models take?**

Between about 316 MB and about 1,028 MB depending on the tier, plus the app itself. The app also requires 350 MB of headroom beyond the files before it will choose a tier.

**Can I choose a different model than the recommended one?**

Yes, in either direction. The settings screen lists every on-device speech model with its download size and marks the recommended one; picking another downloads it and switches to it. The polish model can be changed the same way.

**What happens if a model download is interrupted?**

The partial file is kept and the next attempt resumes from it with an HTTP range request, provided the server's validator still matches. If it does not, the partial is discarded and the download restarts cleanly rather than mixing two versions of the file.

**Are the downloads verified?**

Yes, when the host publishes a hash. Hugging Face does, on its redirect rather than on the file response, and OpenWispr follows the chain to read it and compares SHA-256 before using the file. A mismatch discards the file and fails rather than retrying from it.

**Which languages do the models support?**

The app's code describes the Whisper models as multilingual and makes no language claim at all for Parakeet or the cleanup models, and it passes no language parameter to any engine. Rather than guess, check the upstream model card for whichever model you plan to use.

## Read next

- [Settings reference](/docs/settings.html)
- [Cleanup and polish](/docs/cleanup-and-polish.html)
- [Will on-device dictation run on my phone?](/android/will-on-device-dictation-run-on-my-phone.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/docs/models.html.
