---
title: "Will on-device dictation actually run on my phone? (Android, 2026)"
description: "On-device speech models are hundreds of megabytes and they have to fit in your phone's memory, not just its storage. Here's what OpenWispr downloads on a flagship versus a 4GB or 3GB phone, and why it checks before it downloads anything."
canonical: "https://openwispr.dev/android/will-on-device-dictation-run-on-my-phone.html"
language: "en"
---


# Will on-device dictation actually run on my phone?

Offline dictation means the speech model lives on your phone — so the honest requirement isn't an Android version, it's memory. OpenWispr reads how much your phone has before it downloads anything, and picks a model that fits.

*The real constraint*

## It's RAM, not the Android version, and almost nobody says so

Every on-device dictation app has the same shape: download a speech model once, then run it locally. The download is the part people are warned about. The part that actually decides whether the app works for you is what happens next — that model has to be loaded into memory to transcribe anything, alongside whatever else your phone is running.

A large speech model is roughly 600MB of weights. Add a cleanup model to turn "um so can you send me the doc by thursday" into "Can you send me the doc by Thursday?", and you are asking a phone for around a gigabyte of resident memory in an app that is not the foreground game. On a recent flagship that is unremarkable. On a 3GB budget phone, Android will kill it, and what the user sees is a dictation that never returns.

The failure mode this creates is worth naming precisely, because it is the single worst thing an on-device app can do: it happens *during setup*. The download succeeds, the progress bar fills, everything looks fine — and then the first dictation fails on a phone that never had the memory for it. The user has spent 600MB of data and ten minutes to arrive at an error. This is not hypothetical; it is what a competitor's one-star reviews describe, in the words "needs more free memory right now", after restarting the phone twice.

*What OpenWispr does*

## Check the phone first, then choose the model

On first run, before any download starts, OpenWispr reads two numbers from Android: total device memory (and Android's own low-memory-device flag, which manufacturers set on Go-edition and budget builds), and free storage in the app's own directory. Those two decide which pair of models gets downloaded.

Roughly 6GB of RAM and up gets the large pair: the Parakeet speech model plus OpenWispr's fine-tuned cleanup model, about 1GB together. Around 4GB gets a lighter pair, Whisper base plus a 270M-parameter cleanup model — about 383MB. Below that, or on any phone Android itself flags as low-memory, the smallest pair: Whisper tiny plus the same small cleanup model, about 316MB in total. Free storage can push you down a tier but never up: a phone with plenty of RAM and 400MB free gets the smaller pair, because the larger one cannot land.

Every tier is a real, fully on-device pair. A smaller tier is a smaller model, not a cloud fallback and not a stripped-down mode — the smallest configuration still transcribes and still cleans up, entirely on the phone, with no account and no network. The tradeoff is accuracy on long, fast, or noisy speech, which is a genuine cost and one we would rather state than hide.

If the phone is genuinely short on space for even the smallest pair, OpenWispr says how many megabytes short, on the setup screen, before starting a download that was going to stop partway. And none of this is a lock: Settings lists every speech model with its size, marks the one recommended for your device, and lets you pick a different one in either direction.

*Practical answers*

## What that means for the phone in your hand

**A recent flagship (8GB+):** the full pair, and the fastest, most accurate setup OpenWispr has. Nothing to think about.

**A mid-range phone (4-6GB):** the lighter pair. Dictation works, offline, in every app; you'll notice the difference most on long unbroken passages and in noisy places. Storage cost is about a third of the full download.

**A budget or older phone (3GB or less, or Android Go):** the smallest pair, around 316MB. Best with short, clear dictation — a message, a reply, a note — which is most phone dictation anyway.

**Very old Android:** the app requires Android 7.0 or newer. Below that it won't install.

The thing worth taking away, whichever app you end up using: an on-device dictation app that offers you one model regardless of your phone has made a decision about which phones it serves, whether or not it says so on the listing.

## Questions

**How much RAM do I need for offline dictation?**

For the large models most apps ship, realistically 6GB or more. OpenWispr doesn't require that: it detects what your phone has and installs a smaller on-device pair below roughly 6GB, and a smaller one again below roughly 4GB or on any device Android flags as low-memory. All of them run entirely on the phone.

**How much storage does OpenWispr need?**

Between about 316MB and 1GB of model files, depending on which tier your phone gets, plus the app itself. OpenWispr checks free space before downloading and drops to a smaller pair rather than starting a transfer that can't finish — and if even the smallest pair won't fit, it tells you how much space to clear first.

**Will a cheap Android phone give worse transcription?**

Yes, and it's better to say so. A smaller speech model is less accurate on long, fast, accented, or noisy speech. It's still fully on-device, still offline, still free — and on the short messages most phone dictation is used for, the gap is small. If your phone can handle more, Settings lets you switch up at any time.

**Can I choose the model myself instead?**

Yes. Settings → speech lists every on-device model with its download size and marks the one recommended for your device. You can pick a larger one on a modest phone if you want to try it, or a smaller one on a flagship if you'd rather save the space.

**What's the minimum Android version?**

Android 7.0 (API 24). The app targets the current Android API level and is tested on modern releases, but it installs and runs from 7.0 up.

**Does the model download again if I clear the app or switch phones?**

Yes — the models live in the app's own storage, so clearing app data or moving to a new phone means downloading again. The download resumes if it's interrupted rather than restarting from zero.

## Read next

- [On-device Whisper dictation for Android — genuinely offline](/android/on-device-whisper-dictation-android.html)
- [Gboard's AI dictation is very good. It needs a Pixel 11.](/android/gboard-rambler-voice-typing.html)
- [Free, open-source voice dictation, no subscription](/android/free-open-source-voice-dictation.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/android/will-on-device-dictation-run-on-my-phone.html.
