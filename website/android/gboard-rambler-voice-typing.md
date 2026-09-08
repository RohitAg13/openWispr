---
title: "Gboard Rambler needs a Pixel 11 and a connection — what to use otherwise (2026)"
description: "Google's Rambler voice input is genuinely good AI dictation, and per Google's own help page it requires Pixel 11 series devices and an internet connection for its full feature set. Here's what that leaves for everyone else, and how on-device dictation compares."
canonical: "https://openwispr.dev/android/gboard-rambler-voice-typing.html"
language: "en"
---


# Gboard's AI dictation is very good. It needs a Pixel 11.

Google's Rambler turns rambling speech into clean text, free, inside the keyboard you already have. Its own help page lists two prerequisites: Pixel 11 series devices, and a connection for the full feature set. This page is about what that means for every other Android phone.

*Start here*

## If you have a Pixel 11, stop reading and use Rambler

This is not a page arguing that Google's dictation is bad. Rambler is Gemini-powered voice input built into Gboard: it removes filler words, punctuates and formats as you speak, follows mid-sentence corrections, handles rewrites by voice ("make this sound more professional"), and switches languages mid-sentence across Arabic, English, French, German, Hindi and other Indian languages, Italian, Japanese, Korean, Portuguese, Russian and Spanish. It costs nothing and it is already installed. On a supported phone it is the obvious first thing to try, and we would rather you found that out here than after a download.

The reason this page exists is the **Prerequisites** section of [Google's own Rambler help page](https://support.google.com/gboard/answer/17468539). As of 7 September 2026 it lists, verbatim: **Pixel 11 series devices**, the latest Gboard set as your default keyboard, microphone permission, and **"an active internet connection for full features of Rambler. Offline is supported but with limited features."**

That is a narrower gate than the launch coverage suggested. When Rambler was announced at Android Show: I/O Edition on 12 May 2026, [TechCrunch reported](https://techcrunch.com/2026/05/12/google-adds-gemini-powered-dictation-to-gboard-which-could-be-bad-news-for-dictation-startups/) the features would be "limited to Samsung Galaxy and Google Pixel phones for an initial summer rollout but will eventually reach other Android devices." Four months later, Google's own documentation names one device family. It will presumably widen; today it hasn't.

*What offline actually means here*

## Rambler offline is a reduced Rambler, and it says so

Google documents the offline behaviour plainly, which is more than most: "When your device has limited network connectivity or offline, Rambler still processes voice input, but without the full set of features. Basic cleanup, punctuation, and capitalization remain active. Advanced stylistic rewrites and complex conversational voice editing resume seamlessly once an active network connection is restored." If you lose connectivity mid-dictation, the documented flow is a **Retry** button you tap after you finish.

On the privacy side Google is equally direct, and the wording is worth reading closely: "When you use Rambler, your text, audio input, and your corrections to voice output will be temporarily processed by Google… They're never saved, stored, or shared, and they're deleted immediately after your text is delivered." At the launch briefing, Android Core Experiences director Ben Greenwood described the approach as "a combination of on-device and cloud-based processing."

So Rambler's guarantee is a *retention policy*: your audio goes to Google and is deleted immediately. That is a real and meaningful commitment, and it is a categorically different thing from audio that never leaves the phone, because one depends on a company keeping a promise and the other depends on there being no network call to make. Which of those you need is a genuine judgment call and depends entirely on what you dictate.

One more documented limit worth knowing before you build a habit on it: Google's help page states that "Rambler is subject to usage limits," designed "to ensure an optimal experience for everyone." No number is published.

*The other phones*

## What ordinary Gboard voice typing does on everything else

If your phone isn't in the Rambler list, Gboard falls back to standard voice typing — the word-for-word transcription that has been there for years. It is fast and it is free, and it does not do the thing people actually want from AI dictation: it transcribes your "um, so basically" instead of writing what you meant.

There is a second Gboard tier below Rambler, and it is Pixel-gated too. "Advanced voice typing features" — automatic punctuation as you speak, voice commands like "delete last word", emoji by voice — requires **Pixel 6 or up** per [Google's help page](https://support.google.com/gboard/answer/11197787), which also states that with it on, "the text you speak stays on your device and isn't sent to Google servers *except when you use the 'Fix it' or detailed edits features*." So Google does ship genuinely on-device dictation on Android — on its own recent phones. And the smartest part of it, "Fix it", carries its own eligibility list: Pixel 8 or 8 Pro and later, **English only, United States, and network connectivity**.

Which leaves the rest of the world's Android phones on plain word-for-word voice typing, much of it recognised on Google's servers. That is the actual gap on Android in 2026, and it is not the one the marketing fights over. It isn't "cloud AI versus on-device AI" — Google has both. It is that every good version is gated on buying a recent Pixel, and the phone in most people's pocket is not that.

*Where OpenWispr fits*

## The same job, on the phone you already own, with no round trip

OpenWispr does the same two-stage job — transcribe, then clean up — with both stages running on the phone. Speech recognition is Parakeet or Whisper depending on the device; the cleanup pass is a small language model fine-tuned for exactly this task. Filler words go, punctuation and capitalisation arrive, spoken lists become lists. After the one-time model download there is no network call in the path at all, which is checkable: turn on airplane mode and dictate.

It runs on Android 7.0 and up, and OpenWispr chooses its models from your phone's memory and free storage on first run rather than handing every device the same 1GB download — a mid-range phone gets a smaller on-device model instead of an out-of-memory error. There is no usage limit, no account, and no paid tier; it is MIT-licensed, so the on-device claim is one you can verify in the [source](https://github.com/RohitAg13/openWispr) rather than take on trust.

The honest gaps, because you'll find them anyway: OpenWispr is not a keyboard. It works through a floating bubble and Android's accessibility API, so you keep Gboard for typing and reach for OpenWispr to dictate — some people prefer that, others would rather have one place for everything, and if you're in the second group a keyboard-based tool suits you better. Rambler's conversational editing ("change Saturday to Sunday", "make this shorter") has no equivalent here. And Google has a distribution advantage no independent app can answer: Gboard is already installed on your phone, and OpenWispr is a deliberate download.

The case for it is narrow and specific: dictation that doesn't depend on which phone you bought, doesn't depend on a connection, doesn't meter you, and never sends your voice anywhere to begin with.

## Questions

**Which phones support Gboard Rambler?**

Google's Rambler help page lists Pixel 11 series devices under Prerequisites, along with the latest Gboard as your default keyboard and microphone permission (checked 7 September 2026). At announcement in May 2026, TechCrunch reported an initial rollout to Samsung Galaxy and Pixel phones with wider Android support to follow, so the supported list is likely to grow — check Google's page for the current state.

**Does Gboard Rambler work offline?**

Partly, and Google documents exactly how much. Offline, "basic cleanup, punctuation, and capitalization remain active," while "advanced stylistic rewrites and complex conversational voice editing resume seamlessly once an active network connection is restored." The Prerequisites section asks for "an active internet connection for full features." If connectivity drops mid-dictation you get a Retry button to process the input after you finish.

**Does Rambler send my voice to Google?**

Yes, and Google states the terms: "your text, audio input, and your corrections to voice output will be temporarily processed by Google… They're never saved, stored, or shared, and they're deleted immediately after your text is delivered." The company describes the architecture as a combination of on-device and cloud processing. That is a retention commitment rather than an architectural guarantee — the difference matters only for some people and some content, but it is a real difference.

**Is there an AI dictation option for a non-Pixel or older Android phone?**

Yes — that's the gap OpenWispr is built for. It runs speech recognition and AI cleanup entirely on the device, on Android 7.0 and up, and picks a model sized to your phone's memory rather than assuming a flagship. It is free and MIT-licensed. It is not a keyboard, though: it dictates through a floating bubble alongside whatever keyboard you already use.

**Is Rambler free?**

Yes, on a supported device — it's part of Gboard. Google's help page does note that "Rambler is subject to usage limits," without publishing a number.

**Rambler or OpenWispr?**

If you have a Pixel 11 and a normal connection, try Rambler first: it's already installed, it's free, and its conversational editing goes further than OpenWispr's. Choose OpenWispr if your phone isn't supported, if you dictate somewhere with no signal, if you'd rather your voice never left the device at all, or if you want a tool whose privacy claim you can read the source of.

## Read next

- [Will on-device dictation actually run on my phone?](/android/will-on-device-dictation-run-on-my-phone.html)
- [On-device Whisper dictation for Android — genuinely offline](/android/on-device-whisper-dictation-android.html)
- [A Wispr Flow alternative for Android](/compare/wispr-flow-alternative-android.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/android/gboard-rambler-voice-typing.html.
