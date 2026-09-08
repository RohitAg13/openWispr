---
title: "OpenWispr vs Amical — confirmed on-device Android vs an architecture Amical doesn't disclose"
description: "OpenWispr vs Amical on Android: both MIT-licensed, both live on Google Play. Amical's own docs never state whether Android transcription runs on-device or in the cloud — this page doesn't guess, and neither should you."
canonical: "https://openwispr.dev/compare/amical.html"
language: "en"
---


# OpenWispr vs Amical

Amical is the most direct Android competitor in this research — MIT-licensed, live on Google Play, with a genuinely useful context-aware formatting feature. One thing it doesn't disclose: whether its Android build transcribes on-device or in the cloud.

*The short version, and an honesty note up front*

## We don't know how Amical's Android app processes your voice — and neither, publicly, does anyone outside Amical.

Amical (MIT, TypeScript/Electron, 1,468 ★) is genuinely on-device on macOS and Windows via Ollama, with cloud as an option there. On Android — where it's the most direct competitor to OpenWispr in this research — its architecture is different in one specific, unresolved way: neither amical.ai, its beta page, nor its quick-start docs state whether Android transcription happens on the device or in the cloud. Their marketing mentions cloud models "for increased accuracy and speed on mobile," which hints at cloud, but that's an inference, not a confirmed fact. This page won't guess, and it won't claim OpenWispr is "the only" on-device Android option because of that open question.

What is confirmed either way: both apps are MIT-licensed, both ship on Google Play, and Amical has a real differentiator — context-aware output formatting that detects the frontmost app and adapts tone (professional in Gmail, casual in Slack, clean in an IDE) across 100+ languages. iOS is invite-only TestFlight, not public.

## OpenWispr vs Amical, feature by feature

|  | OpenWispr | Amical (1,468 ★) |
| --- | --- | --- |
| Android transcription location | Confirmed on-device — checkable in open-source code | Undisclosed by Amical's own docs; marketing hints at cloud "for increased accuracy and speed on mobile" but doesn't confirm it |
| iOS availability | Not shipped | Invite-only TestFlight beta — no public release |
| Context-aware output formatting by app | Not shipped today | Yes — adapts tone per app (Gmail vs Slack vs IDE) across 100+ languages |
| macOS / Windows on-device | macOS: yes, confirmed | Yes, via Ollama — confirmed for desktop specifically |
| Price | Free, one tier | Free |
| Open source | Yes — MIT | Yes — MIT |
| GitHub stars | New project | 1,468 |
| Hotkey design | Single hotkey | Fn for push-to-talk, Fn+Space for hands-free continuous — a two-tier model |

Sourced from the competitor's own documentation, changelog, and public statements. Figures can change — check the linked sources for the latest.

*The open question, stated plainly*

## Why this page won't call Amical "cloud-based" or "on-device" on Android

This is the single most consequential unresolved question in the research behind this comparison. Amical's site, beta page, and quick-start docs never state where Android transcription runs. Their marketing copy about cloud models improving accuracy and speed "on mobile" is suggestive, not conclusive — it's consistent with cloud transcription, but it's also consistent with an optional cloud enhancement layered over a local base. Absent an APK teardown or a direct statement from Amical, calling it either way would be asserting something this research doesn't actually know.

This matters because it's the same trap this page is trying not to fall into on OpenWispr's behalf: the claim "the only free, MIT-licensed, genuinely on-device dictation app on Android" is a strong claim, and it would be false if said carelessly while Amical's status is unresolved. So it isn't made here.

*Where Amical is ahead*

## A real formatting feature, and an iOS beta

Context-aware formatting — detecting the app you're dictating into and adjusting tone accordingly, across 100+ languages — is a shipped, genuinely useful feature OpenWispr doesn't have an equivalent for today. Amical also has an invite-only iOS TestFlight beta in motion; OpenWispr has no iOS build at all yet, in beta or otherwise.

Its two-tier hotkey pattern — Fn for push-to-talk, Fn+Space for hands-free continuous dictation — is also a well-designed piece of UX worth naming on its own merits, independent of the platform question above.

## Questions

**Does Amical's Android app run on-device or in the cloud?**

This isn't publicly confirmed either way. Amical's site, beta page, and quick-start docs never state it, and their marketing mentions cloud models "for increased accuracy and speed on mobile" — which hints at cloud without confirming it. This page doesn't guess, and it doesn't claim OpenWispr is "the only" on-device Android option as a result.

**Is Amical open source?**

Yes — MIT-licensed, same as OpenWispr, at 1,468 GitHub stars.

**Does Amical have an iOS app?**

An invite-only TestFlight beta, not a public release. OpenWispr has no iOS build yet at all.

**What is Amical's context-aware formatting?**

It detects the frontmost app you're dictating into and adjusts tone accordingly — more professional in Gmail, casual in Slack, clean in an IDE — across 100+ languages. It's a real, shipped feature OpenWispr doesn't currently match.

**Is OpenWispr confirmed on-device on Android, unlike Amical?**

Yes — OpenWispr's Android transcription is on-device and checkable in its open-source code. The comparison here isn't "OpenWispr is on-device and Amical isn't" — it's that OpenWispr's status is confirmable and Amical's currently isn't, which is itself worth knowing.

## Read next

- [OpenWispr vs Typeless (reportedly cloud-only, closed source)](/compare/typeless.html)
- [On-device Whisper dictation for Android](/android/on-device-whisper-dictation-android.html)
- [One free, open-source app for macOS and Android](/use-cases/mac-and-android-dictation.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/compare/amical.html.
