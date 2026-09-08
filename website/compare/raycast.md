---
title: "OpenWispr vs Raycast Dictation — on-device + Android vs cloud-only, unnamed model"
description: "OpenWispr vs Raycast Dictation: a launcher feature, cloud-only, with no named speech model and no Android. Free during beta, with post-beta pricing unannounced."
canonical: "https://openwispr.dev/compare/raycast.html"
language: "en"
---


# OpenWispr vs Raycast Dictation

Raycast Dictation is a feature of the Raycast launcher, not a standalone app — cloud-only, with no disclosed speech model, currently free during a beta with unannounced pricing after.

*The short version*

## A launcher add-on, not a dedicated dictation product.

Raycast Dictation ships inside the Raycast launcher on macOS and Windows (since June 2026), plus iOS through the Raycast Keyboard. It's cloud-only — Raycast does not publicly name its speech-to-text partner or model, and there's no user model choice. It's free during the current beta, with pricing after the beta unannounced. It has a 20-minute session cap and no Android build.

It does ship genuinely good ideas worth naming on their own merits: Auto Styling that picks a tone based on the app or website you're dictating into, Custom Vocabulary, Custom Instructions, single-tap modifier hotkeys, and team-shared Dictation Styles. Transcriptions and usage statistics are stored locally and never sent as telemetry — a real, specific claim, distinct from where the audio itself is processed.

## OpenWispr vs Raycast Dictation, feature by feature

|  | OpenWispr | Raycast Dictation |
| --- | --- | --- |
| Android app | Yes — on Google Play | No — macOS, Windows, and iOS (via Raycast Keyboard) only |
| On-device transcription | Always | No — cloud only, with no user model choice |
| Speech model / subprocessor disclosed | Yes — NVIDIA Parakeet-TDT (default) or Whisper, named and selectable | No — Raycast does not publicly name its STT partner or model |
| Works with no internet connection | Yes, fully | No — cloud dependency, connection lost means dictation stops |
| Price | Free, no tier, ever | Free during beta; post-beta pricing unannounced |
| Open source | Yes — MIT | No |
| Session length cap | None documented | 20-minute cap per session |
| Custom vocabulary / auto-styling / team-shared styles | Personal dictionary planned, not shipped today | Yes — Custom Vocabulary, Auto Styling, Custom Instructions, team-shared Dictation Styles |

Sourced from the competitor's own documentation, changelog, and public statements. Figures can change — check the linked sources for the latest.

*Where Raycast Dictation is ahead*

## Polish around the edges of the actual transcription

Auto Styling — automatically adapting tone based on the app or website you're dictating into — is a genuinely useful idea, as are Custom Vocabulary and Custom Instructions for steering output without a settings menu. Team-shared Dictation Styles is a distinctly B2B feature nothing else in this research offers. None of that requires knowing what model does the actual transcription, which is precisely the part Raycast doesn't disclose.

It's also worth crediting explicitly and separately: Raycast states transcriptions and usage statistics are stored locally and never sent as telemetry. That's a real, narrower privacy claim, distinct from — and not a substitute for — where the audio itself is processed, which is their cloud.

*Where OpenWispr is ahead*

## It's Android, works offline, and tells you what model is running

Raycast Dictation has no Android build, no offline mode, and doesn't name its transcription model or subprocessor publicly — you're trusting a launcher plugin with your voice without knowing what processes it. OpenWispr runs on Android, works with no internet connection at all, and its model (Parakeet-TDT by default, or Whisper) is named, selectable, and checkable in open-source code.

Raycast's pricing is also an open question: free during the current beta, with no stated plan for after. OpenWispr's pricing has one state: free, permanently, because there's no cloud infrastructure cost driving a future price.

## Questions

**Is Raycast Dictation free?**

Currently, during its beta. Pricing after the beta ends is unannounced. OpenWispr is free with no tier and no cloud infrastructure cost that would require a future price.

**Does Raycast Dictation work offline?**

No — it's cloud-only, so a lost connection stops dictation. OpenWispr works fully offline after the initial model download.

**What speech model does Raycast Dictation use?**

Not publicly disclosed. A widely repeated claim that it's "OpenAI GPT-5.4 mini" traces back to a competitor's marketing page (superwhisper's), not to Raycast — treat it as unverified. OpenWispr names its model: NVIDIA Parakeet-TDT by default, or Whisper via whisper.cpp, selectable.

**Does Raycast Dictation have an Android app?**

No — it's a feature of the Raycast launcher on macOS and Windows, plus iOS via the Raycast Keyboard. There's no Android build. OpenWispr's Android app is live on Google Play.

**Is Raycast Dictation open source?**

No. OpenWispr is MIT-licensed, with the full source on GitHub.

## Read next

- [OpenWispr vs FreeFlow (cloud by default)](/compare/freeflow.html)
- [OpenWispr vs Wispr Flow (cloud-only)](/compare/wispr-flow.html)
- [Dictation software your IT team won't need to block](/use-cases/dictation-it-wont-block.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/compare/raycast.html.
