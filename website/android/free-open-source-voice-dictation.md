---
title: "Free, Open-Source Voice Dictation App (MIT License) — macOS & Android | OpenWispr"
description: "OpenWispr is a free, MIT-licensed voice dictation app with no word caps, no subscription, and no account — because it runs on your device instead of a server you'd have to pay for."
canonical: "https://openwispr.dev/android/free-open-source-voice-dictation.html"
language: "en"
---


# A voice dictation app that's actually free — not free-until-you-hit-a-cap

OpenWispr is MIT-licensed with one tier: free. No word limits, no subscription, no account to create. Here's how that compares to the rest of the category.

*What "free" usually means in this category*

## Most "free" dictation apps are free up to a limit

Wispr Flow's desktop free tier caps out at 2,000 words a week (1,000 on iPhone); its Pro tier is $15/month, or $12/month billed annually ($144/year), to remove the cap. superwhisper's free tier works, but is limited to its smaller local models — roughly $8.49/month unlocks the larger on-device models and cloud options. VoiceInk is open source (GPL-3.0) and buildable free from source, but its packaged app is a one-time $29–69 depending on tier, to unlock auto-updates and support.

None of that is a criticism of those products — running cloud transcription costs real money per user, and that cost has to be recovered somehow. It's the structural reason a subscription (or a word cap, or a paid unlock) shows up almost everywhere in this category.

*Why OpenWispr doesn't have that structure*

## No server, no per-user cost, no subscription to fund one

OpenWispr has no backend. Transcription runs on your own device using your own compute, so there's no per-user cloud bill scaling with usage that a subscription would need to cover. That's the entire mechanism — not a promotional "free forever" claim, but a direct consequence of the architecture.

It's also MIT-licensed, which is more permissive than the GPL-3.0 license some open-source competitors ship under: you can read the full source, modify it, fork it, or build your own product on top of it — commercially or not — without a copyleft obligation to release your changes.

## Questions

**Is OpenWispr really 100% free, with no hidden paid tier?**

Yes. There's a single tier: free, no word cap, no subscription, no account. Because it has no servers, there's no per-user cost that a paid tier would exist to recover.

**What does the MIT license actually let me do?**

Read the full source, modify it, fork it, or ship your own build — commercially or not — with no copyleft obligation to release your changes back. That's more permissive than GPL-licensed alternatives in this category.

**How does OpenWispr's pricing compare to the rest of the category?**

Wispr Flow's desktop free tier caps at 2,000 words/week ($15/mo, or $12/mo annually, removes the cap). superwhisper's free tier is limited to smaller local models (~$8.49/mo unlocks larger ones). VoiceInk is open source but its packaged app costs $29–69 one-time. OpenWispr has one tier: free, uncapped.

**If it's free, how is OpenWispr funded or sustained?**

It runs on your device, so there's no ongoing cloud cost to cover per user. It's an open-source project — see the roadmap and contribute on GitHub.

**Since there's no server, is there anything to self-host or audit?**

There's nothing to self-host — that's the architectural point. Everything you'd want to verify (what data leaves the device, if any) is checkable in the public MIT-licensed source, rather than something you have to trust a privacy policy about.

## Read next

- [On-device Whisper dictation for Android](/android/on-device-whisper-dictation-android.html)
- [A Wispr Flow alternative for Android](/compare/wispr-flow-alternative-android.html)
- [OpenWispr vs Wispr Flow ($15/mo, capped free tier)](/compare/wispr-flow.html)
- [OpenWispr vs superwhisper (paid tier for larger models)](/compare/superwhisper.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/android/free-open-source-voice-dictation.html.
