---
title: "OpenWispr troubleshooting"
description: "The bubble disappearing, dictated text landing on the clipboard, recordings cut short, model downloads that fail, and Gatekeeper blocking the Mac app — what causes each and what to do about it."
canonical: "https://openwispr.dev/docs/troubleshooting.html"
language: "en"
---


# Troubleshooting

Most of these have a specific cause in the app's behaviour rather than a vague one, so each entry says what is actually happening before saying what to do.

*The bubble*

## The bubble has disappeared

Three different causes look identical from the outside, so work through them in this order.

**Field gating is doing its job.** With **Only on text fields** on — the default — the bubble is only visible while a text field has focus. Tap into any text field and it should fade in. If you would rather have it always visible, turn that setting off.

**The overlay permission was revoked.** Without "Display over other apps", the bubble cannot be drawn at all. Settings shows this on the setup card at the top as **Floating bubble**, with an Enable button.

**Your phone killed the service.** This is the common one on Samsung, Xiaomi, OnePlus and Oppo devices, whose battery managers stop background services aggressively. The app restores the bubble whenever you open it or return to Settings, and brings it back after a reboot or an app update — but the durable fix is Settings → Reliability → **Auto-start helper**, which opens your manufacturer's own auto-start screen where the app can find one.

*The bubble*

## The bubble is always visible even though "Only on text fields" is on

This is not the setting being ignored. Gating depends entirely on the accessibility service — that is the only way the app can know whether a text field has focus. When that service is off, the app cannot hide the bubble, because it would then have no way to bring it back. So it reverts to always-visible on purpose.

The setting's own subtitle changes to say so: *"Needs auto-insert. The bubble stays visible until you turn it back on."* Re-enabling **Auto-insert** from the setup card restores gating.

The most common reason for the service being off without you touching it: Android's restricted-settings protection on sideloaded builds, or an OEM battery manager. Both silently disable accessibility services.

*Insertion*

## The text lands on my clipboard instead of the field

Three possible reasons, in descending order of likelihood.

**Auto-insert is not enabled.** Without the accessibility service the app cannot type into another app's field, so it copies instead. This path is deliberately silent — no toast — because being told on every single dictation would be nagging.

**The field would not report a cursor position.** Some fields, particularly ones drawn in a WebView, do not expose where your cursor is. Rather than guess and overwrite the wrong text, the app pastes instead, which necessarily involves the clipboard. Nothing is broken; this is the middle rung of the fallback chain.

**No field was found in time.** The app retries for about 2.3 seconds and then gives up and copies. This happens when the target app takes a long time to restore focus. On the normal path the clipboard is never touched, so if you are seeing your clipboard change on every dictation, one of the above is the reason. Full details on [Text insertion and permissions](/docs/text-insertion.html).

*Recording*

## It stops recording before I've finished speaking

The **Auto-stop on pause** setting ends a tapped recording when the voice-activity model has heard about 1.6 seconds of silence. That figure was deliberately raised from a shorter one because the shorter one cut people off mid-thought — but 1.6 seconds is still less than some people pause for.

Two fixes. Turn **Auto-stop on pause** off, and end recordings yourself by tapping the bubble again or pressing Done. Or use hold-to-talk instead: auto-stop is disabled entirely while you are holding, because your finger is already saying when you are done.

*Recording*

## Nothing was transcribed at all

If the recording was shorter than a quarter of a second, it is discarded rather than sent to the speech engine — there is nothing usable in it. This is most often a mis-fired tap.

If you were using hold-to-talk and released almost immediately, there is a related case: a very quick press-and-release can finish before the recording screen has even come up. The app detects this and cancels cleanly rather than leaving a session running. Hold for a moment longer — the hold does not register at all until 450 ms, and you get a double haptic tick when it does.

If you have the **Vocab-biased decoding (experimental)** toggle on with Parakeet, turn it off. The app's own description says it uses a decode mode with a known upstream bug that occasionally returns blank or wrong text, and it is off by default for exactly this reason.

*Models*

## A model download failed, or will not finish

There is no retry loop — that is a design decision, not a gap. A failed download leaves its partial file on disk on purpose and stops, and starting the download again resumes from where it stopped rather than from zero. So the fix for a flaky connection is simply to tap Get again.

Two failures are not connection problems and behave differently:

- **"Not enough space"**, naming a number of megabytes. The download refuses to start unless there is room for what remains plus a 64 MB margin. Free that much and retry. On first run, the setup screen checks earlier still and tells you how far short you are before offering a download at all.
- **"failed its checksum; discarded"**. The completed file did not match the SHA-256 the host published for it. The partial and everything recorded about it are deleted, deliberately, because resuming from bytes that are not this file would splice garbage forever. Retry from scratch; if it recurs, that is worth [reporting](https://github.com/RohitAg13/openWispr/issues).

*Models*

## Dictation hangs, then errors, right after install

If you dictate while a model is still downloading, the app waits for it — but only for 25 seconds, after which it reports an error rather than hanging indefinitely. On a slow connection with a 631 MB model that is easy to hit.

Let the download finish first. Settings shows each model's state, and the download resumes if you leave and come back.

*Text*

## The cleanup did something I didn't want

**It rewrote more than I expected.** Lower the polish level, or set it to Off — the deterministic cleanup alone handles fillers, punctuation, capitalisation, spoken corrections and numbers with no model involved at all.

**It capitalised or expanded punctuation in a terminal.** On Android, a terminal utterance is only treated as code if it looks like a command: ten words or fewer, starting with a known command verb or a path, or containing a CLI flag. A longer, sentence-shaped utterance is treated as prose on purpose, because most long dictation into a terminal turns out to be a prompt rather than a command. On macOS this does not apply at all — the Mac app does not pass code context into the cleanup chain, so it always runs in prose mode.

**It keeps mis-hearing a name.** Add it to Settings → Personalization → **Personal dictionary**. Terms there are fed to the speech engine before it decodes, not just corrected afterwards, which works considerably better. The *"Dictated ✓ — Got a name wrong?"* notification after a dictation is a shortcut to the same thing.

**It nearly always produces the same wrong shape.** Edit the result in the review sheet before inserting it. Those edits are stored on the device and two of them are shown to the polish model as examples of how you like your dictation cleaned, at Medium and Full.

*macOS*

## The Mac app will not open

macOS saying the app is damaged, or from an unidentified developer, is a signing message rather than a corruption message: the build is self-signed and not notarized. Right-click the app and choose **Open**, then confirm once — macOS remembers thereafter. Or clear the quarantine flag directly with `xattr -dr com.apple.quarantine /Applications/OpenWispr.app`.

If it opens but nothing appears, that is expected — it is a menu-bar agent with no Dock icon and no main window. Look in the menu bar.

If dictation records but never inserts, grant Accessibility in System Settings → Privacy & Security → Accessibility. It is the one hard requirement on macOS. If you chose the Apple Speech engine and it will not run, that engine additionally needs the Speech Recognition permission; the two on-device engines do not.

*Still stuck*

## Where to report something

Settings → Feedback has three routes: **Send feedback** opens your mail app, **Report a problem** opens a GitHub issue in your browser, and **Rate OpenWispr** opens Google Play. None of them sends anything on its own — each hands the message to another app for you to send.

If you are filing an issue, the app prepares a small diagnostics block for you containing the app version, your device manufacturer and model, and your Android version. That is all of it: no identifier, no history, no transcript.

Issues can also be filed directly at [github.com/RohitAg13/openWispr](https://github.com/RohitAg13/openWispr/issues).

## Questions

**Why does the bubble keep vanishing on my Samsung or Xiaomi phone?**

Its battery manager is stopping the foreground service. Settings → Reliability → Auto-start helper opens the manufacturer's own auto-start screen where the app can find one. The app also restores the bubble whenever you open it, and after a reboot or app update.

**Why is my clipboard being overwritten every time I dictate?**

It should not be. The normal insertion path does not touch the clipboard. If yours is changing every time, either auto-insert is off, or the fields you are dictating into do not report a cursor position and the app is pasting instead of splicing.

**Can I stop the recording ending on a pause?**

Yes — turn off "Auto-stop on pause" in Settings, or use hold-to-talk, where auto-stop is disabled while your finger is down.

**A download failed. Will I have to start over?**

No. The partial file is kept deliberately and the next attempt resumes from it. The exception is a checksum failure, which discards the file on purpose because the bytes on disk are not the file you asked for.

**Why does the app say it needs more space when I have plenty?**

It requires headroom beyond the model files themselves — 64 MB before a single download, and 350 MB when choosing which model pair to install on first run — so that a download cannot fill the last of your storage.

**Nothing happens when I quickly tap and release the bubble while holding.**

A hold does not register until 450 ms, and you get a double haptic tick when it does. A release before that is treated as a tap, which starts a long-form recording instead. If the release lands before the recording screen exists, the app cancels the session cleanly rather than leaving a recording running.

## Read next

- [Text insertion and permissions](/docs/text-insertion.html)
- [Settings reference](/docs/settings.html)
- [Models and device fit](/docs/models.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/docs/troubleshooting.html.
