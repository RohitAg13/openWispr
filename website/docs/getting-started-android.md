---
title: "Getting started with OpenWispr on Android"
description: "Install OpenWispr on Android, grant the four permissions it can ask for, and learn the difference between tapping the bubble and holding it. Requirements, the first-run model download, and what each permission actually unlocks."
canonical: "https://openwispr.dev/docs/getting-started-android.html"
language: "en"
---


# Getting started on Android

Install, one model download sized to your phone, and up to four permissions — only two of which dictation strictly needs.

*Requirements*

## What your phone needs

The app declares a minimum SDK of 24, which is Android 7.0. Below that it will not install. It compiles and targets SDK 36.

Beyond the Android version, the real requirement is memory, because a speech model has to be resident to transcribe anything. OpenWispr reads your device's total RAM and free storage before the first download and picks a model pair that fits — between roughly 316 MB and just over 1 GB of model files depending on the phone. The full explanation, including the exact thresholds, is on [Models and device fit](/docs/models.html).

*Install*

## Install and first run

Install from [Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter). There is no account step and no sign-in at any point.

On first run, onboarding downloads the model pair chosen for your device and writes the corresponding entries into settings, so the recommended configuration is what you actually end up with rather than something you have to go and select. The download resumes if it is interrupted rather than starting again from zero, and every file is checked against a hash the server supplies before it is used. If your phone does not have room even for the smallest pair, the setup screen says how many megabytes short you are instead of starting a transfer that cannot finish.

You can replay the whole walkthrough later from Settings → General → **Replay onboarding**.

*Permissions*

## The four things Android may ask for

Only the first two are needed to dictate at all. The other two make the experience better and the app keeps working without them, in a degraded but explained way.

The settings screen shows a status card at the top listing **Microphone**, **Floating bubble** and **Auto-insert** with an Enable button beside anything missing. Once all three are on, the card collapses to *"You're all set"* and the rows disappear.

| Permission | Needed for | Without it |
| --- | --- | --- |
| **Microphone** `RECORD_AUDIO` | Recording your voice | Nothing works. Requested at the moment you first try to record. |
| **Display over other apps** `SYSTEM_ALERT_WINDOW` | The floating bubble | No bubble, so no way to start a dictation from another app. Granted through the system's "Display over other apps" screen, not a normal permission prompt. |
| **Accessibility service** ("Auto-insert") | Typing the text into the field you are in, and hiding the bubble when you are not in a text field | Dictation still works; the result goes to your clipboard instead of the field, and the bubble becomes permanently visible. |
| **Notifications** `POST_NOTIFICATIONS` | The bubble's ongoing notification and the post-dictation "fix a word" tap | On Android 13 and up the follow-up notification is silently skipped. Dictation is unaffected. |

*Accessibility*

## Why an accessibility service, and what it does

OpenWispr uses Android's accessibility API for the same reason any dictation tool that types into other apps does: it is the only supported way to put text into a field that belongs to someone else's app. The service's own description, shown by Android on the enable screen, reads: *"Lets OpenWispr show the dictation bubble when you tap a text field, and type dictated and rewritten text directly into it."*

Before the app deep-links you into the accessibility settings, it shows a consent dialog explaining what the service will be able to do. That disclosure is required for an app that uses accessibility for something other than assisting a disability, and OpenWispr does not claim to be an accessibility tool — it uses the API to insert text.

The service is configured to watch focus, selection, window-state and window-content events with a 50 ms notification timeout. It does not read or transmit the content of your screen anywhere; what it does with those events is decide whether a text field has focus, and where your cursor is. The mechanics are on [Text insertion and permissions](/docs/text-insertion.html).

*Using it*

## Tap for long-form, hold to talk

The bubble is a 56 dp circle you can drag anywhere on screen; its position is remembered across restarts, and dragging it onto the target at the bottom of the screen turns it off. It has two gestures, and they behave differently on purpose.

The threshold between them is 450 ms, and moving your finger more than about 12 dp cancels the long press and turns the gesture into a drag instead.

- **Tap** — a single short tap starts a long-form recording. There is no time limit; you talk for as long as you want. It stops when you tap the bubble again, when you press Done in the recording sheet, or, if **Auto-stop on pause** is on, after about 1.6 seconds of silence.
- **Hold** — press and keep holding. After 450 ms you feel a double haptic tick, which is how you know the hold registered rather than a tap. Speak while holding; release to send. Auto-stop is deliberately disabled during a hold, because your finger is already saying when you are finished.

*Using it*

## The review sheet, and the countdown

When transcription and cleanup finish, the result appears in a sheet with a countdown bar. If you do nothing, it is inserted automatically when the bar runs out. The window is two seconds plus 110 ms per word, clamped to between two and six seconds — short results go in almost immediately, long ones give you time to read them.

Tapping **Insert** puts it in straight away and **Discard** throws it away. Tapping into the text to edit stops the countdown. Anything you edit is remembered as an example of how you like your dictation cleaned up, which is what feeds the polish stage later; that is covered under [Cleanup and polish](/docs/cleanup-and-polish.html).

Afterwards you may see a quiet *"Dictated ✓ — Got a name wrong? Tap to fix."* notification. Tapping it shows the words from that dictation so you can correct a mis-heard name; the correction is saved into your personal dictionary so it is right next time. It does not re-insert anything.

*Keeping it alive*

## If the bubble keeps disappearing

The bubble runs as a foreground service with a minimum-importance ongoing notification titled "OpenWispr", reading *"Tap to dictate · hold to talk, release to send"*. Aggressive battery managers on Samsung, Xiaomi, OnePlus and Oppo devices will still kill it.

Two things push back on that. The app restores the bubble whenever you open it or return to settings, and it restarts the bubble after a reboot or an app update. Settings → Reliability → **Auto-start helper** opens your manufacturer's auto-start screen directly where the app can find it, and the app-info screen otherwise. More on this on [Troubleshooting](/docs/troubleshooting.html).

*Other ways in*

## Rewriting text you have already typed

OpenWispr also registers itself in Android's text-selection toolbar. Select some text in almost any app, tap the overflow menu, and choose **OpenWispr**; the app opens with that text loaded, and when you accept the result the host app replaces the selection itself. No clipboard and no accessibility service are involved on that path. If the field is read-only, the result is copied to your clipboard instead, because there is nothing to replace.

## Questions

**What is the minimum Android version?**

Android 7.0, API level 24. The app targets API 36.

**Do I have to enable the accessibility service?**

No. Without it OpenWispr still records, transcribes and cleans up; the finished text goes to your clipboard for you to paste. What you lose is automatic insertion at your cursor, and the ability for the bubble to hide itself when you are not in a text field.

**Is there a limit on how long I can talk?**

The Android app enforces no maximum recording length. A held recording lasts as long as you hold the bubble; a tapped one lasts until you stop it, or until auto-stop fires after a pause if you have that switched on.

**Does OpenWispr work offline?**

Yes, once the models are downloaded. The default configuration is fully on-device — the app opens no network connection during a dictation unless you have deliberately switched to a cloud speech or polish provider.

**Why does the bubble stay visible even though 'Only on text fields' is on?**

Because the bubble can only tell whether a text field has focus through the accessibility service. If that service has been turned off — which OEM battery managers and Android's restricted-settings protection both do — the app falls back to showing the bubble always, rather than hiding a bubble it can no longer bring back. The setting's own subtitle changes to say so.

**Where do the model files live, and can I delete them?**

In the app's private storage. Settings lists every model with its size and a delete button on the ones you are not currently using; the active model cannot be deleted. Clearing the app's data removes them all, and they download again on next use.

## Read next

- [Models and device fit](/docs/models.html)
- [Text insertion and permissions](/docs/text-insertion.html)
- [Settings reference](/docs/settings.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/docs/getting-started-android.html.
