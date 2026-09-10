---
title: "How OpenWispr inserts text, and what each permission is for"
description: "OpenWispr splices dictated text in at your cursor through Android's accessibility API, falls back to a clipboard paste, and only as a last resort leaves the text on your clipboard. The full chain, the retry timings, and every permission the app declares."
canonical: "https://openwispr.dev/docs/text-insertion.html"
language: "en"
---


# How the text gets in

Three ways to reach the field, tried in order, each one a degradation of the last — and a clipboard that stays untouched whenever the first one works.

*First, what it isn't*

## OpenWispr is not a keyboard

Most Android dictation tools are input methods: you switch keyboards, dictate, and switch back. OpenWispr contains no input method at all. It is a floating bubble plus an accessibility service, which means you keep whatever keyboard you already use, and OpenWispr writes into the field beside it rather than replacing your typing surface.

That choice is the reason the accessibility permission exists, and it is also why the app has a fallback chain at all — an input method is handed a connection to the field, whereas an accessibility service has to go and find it.

*The chain*

## Three attempts, in order

When a dictation is accepted, the finished text is handed to the accessibility service, which tries the following in order. Each step is strictly worse than the one above it, and the app only descends when the step above genuinely could not work.

- **1. Splice at the cursor.** The service finds the focused editable field in the foreground app, reads its current contents and your selection, and writes back the text with yours inserted at the cursor — then moves the cursor to the end of what it inserted. **Your clipboard is not touched on this path.** A successful dictation does not clobber whatever you had copied.
- **2. Clipboard, then paste.** Some fields — WebView-based ones in particular — will not report where the cursor is in a non-empty field. Rather than guess and overwrite the wrong thing, the first step declines, and the service copies the text and asks the field to paste. Cursor position is honoured, but the text necessarily ends up on your clipboard.
- **3. Clipboard only.** If no editable field is found at all within about 2.3 seconds, the attempt is abandoned and the text is left on your clipboard for you to paste. Nothing is lost; it just did not land by itself.
- **And if the service is off entirely,** the app skips straight to the clipboard, deliberately without a toast. You already know you did not enable auto-insert; being told again on every dictation would be nagging, not information.

*The chain*

## Why it retries, and for how long

The field you want is often not ready at the instant the dictation finishes — the review sheet is still dismissing, the keyboard is re-focusing, or the app is still restoring its window. So insertion is not a single attempt.

It retries at 250, 500, 900, 1,400 and 2,000 milliseconds, and gives up at 2,300. On top of that it is event-driven: any focus change, selection change, window change or content change from an app that is not OpenWispr triggers another attempt immediately. In practice the text is usually in before the first scheduled retry, and the schedule exists for the cases where it is not.

When it works, you get a 20-millisecond haptic tick and nothing else. No toast, no banner — the text appearing is the confirmation.

*Other route*

## The text-selection toolbar

There is a second, entirely separate path that involves neither the bubble nor the accessibility service. Select text in almost any app and OpenWispr appears in the selection toolbar; choosing it opens the app with your selection loaded, and accepting the result hands it straight back so the *host app* replaces the selection itself.

This is the cleanest insertion mechanism the platform offers, and it needs no special permissions whatsoever. Its one limitation is that it only works where there is a selection to replace: if the field is read-only, the result goes to your clipboard instead.

*Field gating*

## How the bubble knows to appear

With **Only on text fields** switched on — which is the default — the bubble behaves like a contextual key: it fades in when a text field takes focus and out when it loses it. The animation is 170 ms in and 140 ms out, with a re-check at the end so that focusing another field mid-fade wins rather than leaving the bubble hidden.

The signal comes from the same accessibility events used for insertion, debounced by 120 ms, and it scans all interactive windows rather than only the active one — because during an app switch the active window can briefly be nothing at all. It also ignores OpenWispr's own windows, so the recording sheet does not make the bubble flicker.

The consequence is worth stating plainly, because it looks like a bug and is not: **if the accessibility service is off, gating cannot work, so the bubble becomes permanently visible.** The alternative would be a bubble that hides itself and can never be brought back. The setting's subtitle changes to say exactly this rather than leaving you to work it out.

*Permissions*

## Every permission the Android app declares

Nine, and this is all of them. Two are what dictation actually needs; the rest are narrow and each does one thing.

| Declared permission | What it is for | If denied |
| --- | --- | --- |
| `RECORD_AUDIO` | Recording your voice | Nothing works. Requested at first use. |
| `SYSTEM_ALERT_WINDOW` | Drawing the floating bubble over other apps | No bubble. Granted through the system's "Display over other apps" screen. |
| `INTERNET` | Downloading models, and cloud providers if you enable them | Models cannot be downloaded. On-device dictation itself never needs it. |
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` | Keeping the bubble's service alive | The bubble service cannot run. |
| `POST_NOTIFICATIONS` | The bubble's ongoing notification, and the post-dictation "fix a word" prompt | On Android 13+ the follow-up notification is silently skipped. |
| `VIBRATE` | The haptic ticks: insertion confirmed, hold registered, bubble dismissed | No haptics. Nothing else changes. |
| `RECEIVE_BOOT_COMPLETED` | Bringing the bubble back after a reboot or an app update | The bubble does not return by itself after a restart. |
| `READ_CONTACTS` | Only the optional "Import from contacts" feature, which matches names on-device | That one feature. Dictation is unaffected. |

*Permissions*

## The two that aren't manifest permissions

**Display over other apps** and the **accessibility service** are both special access rather than ordinary runtime permissions, which is why neither is granted by a simple dialog. The app deep-links you to the right system screen for each.

For accessibility, a consent dialog is shown first, every time, before the deep link. That disclosure is a Play policy requirement for an app that uses the accessibility API for something other than assisting a disability, and OpenWispr does not claim otherwise: it uses the API to insert text. The service's system-facing description reads *"Lets OpenWispr show the dictation bubble when you tap a text field, and type dictated and rewritten text directly into it."*

One notification you will see either way: the bubble's own ongoing notification, at minimum importance, titled "OpenWispr" and reading *"Tap to dictate · hold to talk, release to send"*. Android requires a foreground service to be visible; there is no way to run a persistent overlay without it.

*macOS*

## The same idea, one permission

On macOS the equivalent is the Accessibility permission in System Settings → Privacy & Security, and it is the single hard requirement for inserting text into other applications. It is also the reason the Mac app is not sandboxed: an app inside the App Sandbox cannot drive another application's text fields through the Accessibility API at all.

The Mac app also surfaces Input Monitoring during setup, but it is explicitly optional and never gates the dictation flow. See [Getting started on macOS](/docs/getting-started-macos.html).

## Questions

**Does OpenWispr overwrite my clipboard every time I dictate?**

No. On the normal path — splicing the text in at your cursor — the clipboard is not touched at all. It is only written when the field will not report a cursor position and a paste is needed instead, when no field can be found within about 2.3 seconds, or when the accessibility service is off.

**Why does my dictation end up on the clipboard instead of in the field?**

Either the accessibility service is not enabled, or it could not find a focused editable field in the foreground app within about 2.3 seconds. Some apps — particularly ones drawing their fields in a WebView — do not expose a cursor position, in which case the app pastes rather than splices, which also uses the clipboard.

**Can I use OpenWispr as my keyboard?**

No. It contains no input method. You keep your own keyboard and OpenWispr inserts alongside it, through the accessibility service or through the text-selection toolbar.

**Does the accessibility service read my screen?**

It receives focus, selection and window events, which is how it knows whether a text field has focus and where your cursor is, and it reads the contents of the field it is about to write into so it can splice rather than overwrite. It sends nothing anywhere — the app opens no network connection during a dictation unless you have configured a cloud provider yourself.

**Why does the bubble need a permanent notification?**

Because it runs as an Android foreground service, and Android requires those to be visible. It is posted at minimum importance so it sits quietly at the bottom of your shade.

**What is the contacts permission for?**

One optional feature: importing contact names into your personal dictionary so the speech engine stops mis-hearing them. The matching happens on the device and the names are never uploaded. Dictation works fully without ever granting it.

## Read next

- [Getting started on Android](/docs/getting-started-android.html)
- [Troubleshooting](/docs/troubleshooting.html)
- [What leaves your device](/docs/privacy-and-data.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/docs/text-insertion.html.
