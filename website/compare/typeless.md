---
title: "OpenWispr vs Typeless — free MIT + on-device vs $12-30/mo, closed source"
description: "OpenWispr vs Typeless on Android: Typeless ships a real Android IME keyboard across four platforms, but is closed source, $12-30/mo past a free cap, and multiple reviewers report it's cloud-only despite marketing that reads as local."
canonical: "https://openwispr.dev/compare/typeless.html"
language: "en"
---


# OpenWispr vs Typeless

Typeless is the widest-reach dictation app in this research — macOS, Windows, iOS, and Android, with a real IME keyboard. It's closed source, $12–30/mo past a free cap, and its "on-device" marketing doesn't match what independent reviewers found.

*The short version*

## Typeless reaches more platforms. The transcription location claim doesn't check out.

Typeless ships on macOS, Windows, iOS, and Android — including a real Android IME keyboard (`com.typeless.mobile`), which is more platform reach than any single competitor covered in this research. Its free tier caps at 8,000 words/week; Pro is $12/mo billed annually or $30/mo monthly. It's closed source.

The specific claim worth scrutinizing: Typeless's marketing ("Zero cloud data retention," "On-device history storage") reads as local processing, but multiple independent reviewers report it's cloud-only with no on-device mode. Those reviews come from competitor-adjacent blogs, so treat the specific permission-audit claims in them as plausible, not verified — but Typeless's own pricing page and homepage genuinely never state where transcription actually runs, which is itself the finding. On a dropped connection, cloud-only means dictation stops.

## OpenWispr vs Typeless, feature by feature

|  | OpenWispr | Typeless |
| --- | --- | --- |
| Where transcription runs | Your device, always, verifiably (open source) | Never stated by Typeless themselves; multiple independent (competitor-adjacent) reviews report cloud-only despite marketing that reads as local |
| Works with no internet connection | Yes, fully | Unclear — if the reviews are right, dictation stops on a dropped connection |
| Price | Free, no cap, ever | Free up to 8,000 words/week; Pro $12/mo annual or $30/mo monthly |
| Open source | Yes — MIT, every line on GitHub | No — closed source |
| Android integration | Standard app + accessibility-based insert | A real IME keyboard (com.typeless.mobile) — a deeper OS-level integration |
| Platform coverage | macOS + Android | macOS, Windows, iOS, Android — the widest reach in this research |
| Account required | Never | Yes |
| Where the transcription location claim is disclosed | In the open-source code itself | Not disclosed by Typeless's own site — the absence is the finding, per this research |

Sourced from the competitor's own documentation, changelog, and public statements. Figures can change — check the linked sources for the latest.

*Read this before choosing Typeless for privacy*

## "Zero cloud data retention" is not the same claim as "on-device"

Typeless's marketing copy — "Zero cloud data retention," "On-device history storage" — is the kind of language that reads as local processing without actually stating it. This research found multiple independent reviews (competitor-adjacent blogs, so weight accordingly) reporting Typeless is cloud-only with no on-device transcription mode at all. What's independently verifiable without trusting those reviews: Typeless's own pricing page and homepage never state where transcription runs, for a product actively marketed on privacy language. That absence, on its own, is worth knowing before you rely on it for anything sensitive.

"Works on a plane" is a real, demonstrable, screenshottable claim Typeless cannot make if the cloud-only reports are accurate — dictation would simply stop with no connection. OpenWispr can make that claim because there's no cloud path to lose.

*Where Typeless is ahead*

## Platform reach and a real keyboard integration

Typeless covers macOS, Windows, iOS, and Android — more platforms than OpenWispr today — and its Android IME keyboard is a deeper integration than an accessibility-based insert, working inside any text field the same way a normal keyboard app does.

If you specifically need iOS or Windows dictation right now, or want a keyboard-level Android integration rather than an overlay, Typeless covers both and OpenWispr doesn't yet.

## Questions

**Is Typeless actually private and on-device, like its marketing suggests?**

Its marketing ("Zero cloud data retention," "On-device history storage") reads that way, but multiple independent reviews report it's cloud-only with no local mode. Those reviews are competitor-adjacent, so treat specifics cautiously — but Typeless's own site never states where transcription runs, which is itself notable. OpenWispr's on-device processing is checkable in its open-source code.

**How much does Typeless cost?**

Free up to 8,000 words/week; Pro is $12/mo billed annually or $30/mo billed monthly. OpenWispr is free with no word cap and no paid tier.

**Is Typeless open source?**

No — closed source. OpenWispr is MIT-licensed, with the full source on GitHub.

**Does Typeless work without an internet connection?**

Unclear from their own materials. If the independent reports of cloud-only processing are accurate, dictation would stop on a dropped connection. OpenWispr works fully offline after the initial model download, which is verifiable rather than a marketing claim.

**Does Typeless have a real Android keyboard, not just an app?**

Yes — a dedicated IME keyboard (com.typeless.mobile), which is a deeper OS-level integration than OpenWispr's current accessibility-based approach.

## Read next

- [OpenWispr vs Amical (Android on-device status undisclosed)](/compare/amical.html)
- [On-device Whisper dictation for Android](/android/on-device-whisper-dictation-android.html)
- [Free, open-source voice dictation, no subscription](/android/free-open-source-voice-dictation.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/compare/typeless.html.
