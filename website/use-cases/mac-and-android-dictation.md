---
title: "One Free, Open-Source Dictation App for macOS and Android | OpenWispr"
description: "The dictation app landscape splits cleanly into desktop apps with no mobile build and mobile apps that are closed-source or cloud-only. OpenWispr is free, MIT-licensed, and on-device on both macOS and Android."
canonical: "https://openwispr.dev/use-cases/mac-and-android-dictation.html"
language: "en"
---


# One free, open-source dictation app for both macOS and Android

The dictation landscape splits into two clusters that don't overlap: mature desktop apps with no mobile build, and mobile apps that are closed-source, subscription-priced, or vague about where transcription actually runs. OpenWispr is the same free, MIT-licensed, on-device app on both.

*Two clusters, and a gap between them*

## The desktop cluster and the Android cluster don't overlap

The desktop on-device dictation cluster is genuinely strong: Handy (27.7k stars, mac/Windows/Linux, no cloud path at all), FluidVoice (9.0k stars, macOS 15+ with a custom-trained local cleanup model), VoiceInk (GPL, Apple Silicon only, the best failure-recovery UX found in this research), and Muesli (Apple Silicon only, dictation plus local meeting notes). None of them ship an Android app — not in beta, not waitlisted.

The Android side looks different. Typeless reaches Android with a real IME keyboard, but it's closed source and $12–30/month past a free cap, and multiple independent reviews report it's cloud-only despite marketing that reads as local. Amical is MIT and live on Google Play, but its own docs never state whether Android transcription runs on-device or in the cloud — an open question this research couldn't resolve either way. Wispr Flow and superwhisper, the two best-known desktop names, are cloud-only and have no Android app respectively.

*Where OpenWispr sits*

## Same app, same architecture, same license, on both platforms

OpenWispr ships on macOS and Android as one project, MIT-licensed, with the same on-device architecture on both: NVIDIA Parakeet-TDT by default, or Whisper via whisper.cpp, selectable. Neither build has a cloud transcription path to fall back to or opt into — the source is public, so that's a checkable claim rather than a marketing one.

This isn't a claim that OpenWispr beats every desktop competitor on macOS, or every Android competitor on Android — several comparisons on this site show specific places OpenWispr is behind, from VoiceInk's recovery UX to Amical's context-aware formatting. The claim here is narrower and structural: one habit, one license, one architecture, following you from a laptop to a phone, without switching to a different product with a different privacy model on each device.

## Questions

**Is OpenWispr really the same app on macOS and Android, or two different products?**

One project, two platform builds, sharing the same on-device architecture and MIT license — NVIDIA Parakeet-TDT by default or Whisper via whisper.cpp on both. Neither build has a cloud transcription path.

**Which desktop dictation apps also have an Android build?**

Among the apps covered in this site's comparisons, none of the desktop-first cluster — Handy, FluidVoice, VoiceInk, Muesli, superwhisper, Spokenly — ship on Android, shipped or waitlisted. OpenWispr and Amical are the exceptions with a live Android app; Wispr Flow also has Android but is cloud-only.

**Which Android dictation apps are also genuinely on-device?**

This is a shorter list than it should be. Typeless reaches Android but multiple independent reviews report it's cloud-only despite marketing that reads as local. Amical's Android architecture isn't disclosed either way by their own docs. OpenWispr's Android build is confirmed on-device, checkable in open-source code.

**Do I need an account to sync between the macOS and Android builds?**

No — OpenWispr has no account system and no cloud sync service between devices, because there's no server. Each install is independent.

**Why doesn't OpenWispr support Windows or iOS yet?**

Windows and Linux support are in progress for the desktop side; there's no iOS build yet. macOS and Android were built first — see the comparison pages for exactly where that leaves OpenWispr behind platform-complete competitors today.

## Read next

- [OpenWispr vs Handy (27.7k★, no mobile at all)](/compare/handy.html)
- [OpenWispr vs Amical (Android on-device status undisclosed)](/compare/amical.html)
- [On-device Whisper dictation for Android](/android/on-device-whisper-dictation-android.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/use-cases/mac-and-android-dictation.html.
