---
title: "OpenWispr vs FluidVoice — free MIT + Android vs GPL, macOS 15+ only"
description: "OpenWispr vs FluidVoice: FluidVoice trained its own local enhancement model (Fluid-1) and relicensed to GPL-3.0 in 2026. It's macOS 15+ only with iOS and Windows still on a waitlist. OpenWispr ships Android today."
canonical: "https://openwispr.dev/compare/fluidvoice.html"
language: "en"
---


# OpenWispr vs FluidVoice

FluidVoice is the most-mentioned dictation alternative in the research behind this comparison — a genuinely on-device app with a custom-trained local cleanup model. It's macOS 15+ only; iOS and Windows are still waitlisted.

*The short version*

## FluidVoice trained its own model. That's the real differentiator.

FluidVoice (8,997 ★) is local-first with cloud strictly opt-in, and it's the app most named by professionals in the source thread this research is built on. Its standout feature is Fluid-1, a custom-trained ~3.5 GB on-device model that handles formatting, capitalization, and per-app tone — no cloud, no API key. Nobody else in this research trained their own local cleanup model; everyone else wires up an existing LLM.

It relicensed from Apache-2.0 to GPL-3.0 in February 2026, described in the research as a commercially-defensive move. It currently ships macOS 15+ only — iOS and Windows support are on a waitlist, not shipped. OpenWispr already ships on Android, which FluidVoice doesn't have at all.

## OpenWispr vs FluidVoice, feature by feature

|  | OpenWispr | FluidVoice (8,997 ★) |
| --- | --- | --- |
| Android app | Yes — on Google Play | No — not shipped, not waitlisted |
| macOS support | Yes — Intel and Apple Silicon | macOS 15+ required |
| iOS / Windows | Not on the roadmap yet | Both on a waitlist — not shipped |
| Local cleanup model | Fine-tuned local model, or an optional cloud LLM | Fluid-1 — a custom-trained ~3.5 GB local model for formatting, capitalization, per-app tone |
| Price | Free, one tier | Free |
| Open source license | MIT — permissive | GPL-3.0 since Feb 2026 (was Apache-2.0) |
| Model choice at onboarding with published sizes | Not shown at onboarding today | Yes — Apple Speech (0 MB) to Nemotron (~670 MB) to Whisper Large (2.9 GB), sizes published upfront |
| GitHub stars | New project | 8,997 |

Sourced from the competitor's own documentation, changelog, and public statements. Figures can change — check the linked sources for the latest.

*Where FluidVoice is ahead*

## A genuinely novel local model, and clear onboarding

Fluid-1 is a real differentiator: a purpose-trained local model for cleanup, formatting, and per-app tone, running with no cloud call and no API key. Most competitors, OpenWispr included, use an existing model rather than training their own. FluidVoice's onboarding is also a pattern worth naming on its own merits — model choice is presented up front with published download sizes (Apple Speech at 0 MB up to Whisper Large at 2.9 GB), so a user picks a tradeoff deliberately instead of discovering it after the fact.

It's also the single most-mentioned alternative in the primary research thread behind this comparison — three separate mentions, more than any other product — which is a real signal of mindshare among the exact audience this research targeted.

*Where OpenWispr is ahead*

## Android, and a lower macOS floor

FluidVoice has no Android app, shipped or waitlisted. If you need dictation on a phone, it isn't an option today. OpenWispr's Android app is live on Google Play.

FluidVoice also requires macOS 15+; OpenWispr supports both Intel and Apple Silicon Macs without that floor. And FluidVoice's GPL-3.0 license carries a copyleft obligation for distributed derivative works that OpenWispr's MIT license doesn't.

## Questions

**Does FluidVoice have an Android app?**

No — not shipped, and not on a public waitlist either, unlike their iOS and Windows plans. OpenWispr's Android app is live on Google Play today.

**What is Fluid-1?**

FluidVoice's own custom-trained, ~3.5 GB on-device model for text cleanup — formatting, capitalization, and per-app tone — with no cloud call. It's a genuinely novel piece of engineering; OpenWispr's cleanup uses a fine-tuned local model or an optional cloud LLM, not a custom-trained model of that scale.

**What macOS version does FluidVoice need?**

macOS 15 or later. OpenWispr supports both Intel and Apple Silicon Macs without that specific version floor.

**Is FluidVoice open source?**

Yes — GPL-3.0 as of February 2026 (previously Apache-2.0). OpenWispr is MIT-licensed, which is more permissive and carries no copyleft obligation.

**Why was FluidVoice mentioned more than other alternatives in the research behind this page?**

It was named three separate times in the LinkedIn thread this research started from — more than any other single product — suggesting real mindshare among professionals actively looking for a Wispr Flow alternative.

## Read next

- [OpenWispr vs Handy (27.7k★, no mobile)](/compare/handy.html)
- [OpenWispr vs Muesli (model table, meeting notes)](/compare/muesli.html)
- [One free, open-source app for macOS and Android](/use-cases/mac-and-android-dictation.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/compare/fluidvoice.html.
