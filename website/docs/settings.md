---
title: "OpenWispr settings reference (Android)"
description: "Every setting on OpenWispr's Android settings screen, section by section, with its default, its options, and what it actually changes. Includes the settings that only appear under certain conditions, and where each one is stored."
canonical: "https://openwispr.dev/docs/settings.html"
language: "en"
---


# Settings reference

Eight sections, in the order the app shows them. Every default here is the value the app ships with, and every option is one the screen actually offers.

*How it behaves*

## There is no Save button

Every change persists the moment you make it. That is a deliberate design choice, not an omission — nothing on this screen needs confirming, and nothing is lost by backing out of it.

The screen also has no content until settings have loaded, so a fraction of a second of blank on a cold open is expected rather than a stall. This page covers the Android app; the macOS settings are a smaller set and are described under [Getting started on macOS](/docs/getting-started-macos.html).

*Top of the screen*

## The setup card

Above the first section is a status card. While anything is missing it reads **Finish setup** — *"A couple of quick permissions"* — and lists whichever of **Microphone**, **Floating bubble** and **Auto-insert** is not yet on, each with an **Enable** or **Manage** button. Once all three are on it collapses to **You're all set** — *"Microphone · Bubble · Auto-insert all active"* — and the rows disappear entirely.

These are operating-system permission states, not stored settings. See [Text insertion and permissions](/docs/text-insertion.html) for what each one unlocks.

*Section 1*

## Voice · transcription

**Engine** chooses where speech recognition happens: **On-device**, **Groq**, **OpenAI** or **Custom**. The default is On-device, and the other three are opt-in cloud services that need an API key you supply. Selecting Groq or OpenAI immediately fills in that provider's model name for you; selecting Custom leaves the endpoint and model for you to enter.

Choosing a cloud engine replaces the model list with an API key field and a plain note that audio is sent to that provider for transcription, alongside a one-tap line reading *"Switch to on-device to keep everything private."*

With On-device selected, you get the model list instead, headed by the line *"Models download once, then run fully offline"* and a hint naming your device's memory and the model recommended for it.

| Setting | Default | Options / effect |
| --- | --- | --- |
| **Engine** | On-device | On-device · Groq · OpenAI · Custom |
| **Speech model** *(on-device)* | Chosen for your device during onboarding | Parakeet ~631 MB · Tiny ~75 MB · Base ~142 MB · Small ~488 MB. The one recommended for your device carries a badge. |
| **API key** / **Endpoint** / **Model id** | empty | Only shown for a cloud engine. Endpoint and model id appear only for Custom. |
| **Auto-stop on pause** | On | "End recording when you stop talking." Applies to a tapped recording; a held one ends when you let go. |
| **Vocab-biased decoding (experimental)** | Off | Only shown with Parakeet selected. Uses a decode mode with a known upstream bug that occasionally returns blank or wrong text — the app's own wording. Leave it off unless you are testing it. |

*Section 2*

## Cleanup & polish

**Smart cleanup** is the deterministic stage: *"Fillers, punctuation, numbers, backtracking · on-device, instant."* It is on by default and it involves no model at all.

**AI polish** is the optional model pass on top, at one of four strengths. Under the selector sits a fixed line that describes a real guarantee rather than a reassurance: *"Polish always keeps your words and meaning. It falls back to the clean text if it strays."* The app measures how much of your wording survived and discards the model's answer if too little did. The mechanics are on [Cleanup and polish](/docs/cleanup-and-polish.html).

| Setting | Default | Options / effect |
| --- | --- | --- |
| **Smart cleanup** | On | The deterministic chain. No model, no latency worth measuring. |
| **AI polish** | Full | **Off** — deterministic only. **Light** — capitalisation, spacing and punctuation only. **Medium** — also splits run-on sentences and fixes small grammar. **Full** — fuller cleanup with one small rewrite for clarity if needed. |
| **Advanced polish model** | collapsed | A disclosure holding everything below. Hidden entirely when AI polish is Off. |
| **Polish model** | OpenWispr Cleanup (Qwen3 0.6B) | Also Gemma 3 270M ~241 MB and Qwen3 0.6B ~639 MB. |
| **Creativity** | 0.7 | A 0 to 1 slider — the sampling temperature. **Cloud only:** it is sent to an OpenAI-compatible provider, omitted for Anthropic (whose recent models reject it), and not used at all by the on-device models. |
| **Anti-AI phrasing** | On | A long list of guardrails against stock model phrasing, appended to the polish prompt. **Cloud only** — the on-device models get a deliberately short prompt instead, because sub-1B models loop on long guardrails. |
| **Use a cloud model instead** | collapsed, and off | Anthropic (Claude) · Vercel AI Gateway · OpenRouter · Custom (OpenAI-compatible). Each needs your own API key; Custom also needs an endpoint URL. |

*Section 3*

## Bubble

Two settings, and the second one changes its own description when it cannot do what it says.

**Show bubble** — *"The floating tap-to-talk button"* — is off until you turn it on, which is also what the setup card's Enable button does. Turning it on asks for the notification permission on Android 13 and up, then the overlay permission if it is missing.

**Only on text fields** — *"Appear only when you can type"* — is on by default and makes the bubble behave like a contextual keyboard key, appearing when a text field takes focus and fading out when it loses it. It depends entirely on the accessibility service, so if that service is off the subtitle changes to *"Needs auto-insert. The bubble stays visible until you turn it back on"* and the bubble reverts to being always visible. That is the app explaining itself rather than silently ignoring your setting.

*Section 4*

## Privacy

**Keep history** — *"Stored on this device · powers personalization"* — is on by default. Turning it off also immediately purges any retained audio, because keeping recordings for a history that no longer exists would be indefensible.

**Keep audio** appears only while history is on. Its description is the reason it exists: *"Recordings stay on this device so a dictation that fails can be run again. Never uploaded."* The options are 7 days, 30 days, 90 days, or Forever, defaulting to 30 days. "Forever" is time-unbounded but still capped at the most recent 200 recordings.

**Clear all data** is the last row, in red. It deletes every history entry and purges all retained audio, and confirms with *"On-device history cleared"*. What is stored, and what none of it is used for, is on [What leaves your device](/docs/privacy-and-data.html).

*Section 5*

## Personalization

Five rows, each opening a screen of its own rather than holding a value. All five operate on data that stays on the device.

- **Personal dictionary** — *"Names & terms you taught it"*. Terms fed to the speech engine as a bias prompt before it decodes, not just corrected afterwards.
- **Learned from edits** — *"Corrections it remembered"*. Aliases the app worked out from the edits you made to its output.
- **Style memory** — *"On-device examples · never uploaded"*. The examples that feed the polish stage.
- **Tone by app** — *"Email, chat, code, notes"*. Per-category tone overrides for the polish model.
- **Import from contacts** — *"Matched on-device · never uploaded"*. The one feature that uses the contacts permission, and the only thing that permission is for.

*Sections 6–8*

## Reliability, Feedback and General

**Reliability** holds one row: **Auto-start helper** — *"For Samsung, Xiaomi, OnePlus, Oppo"* — which opens your manufacturer's auto-start screen where the app can find one and the app-info screen otherwise. Aggressive battery management is the single most common reason the bubble goes missing.

**Feedback** holds three: **Send feedback** (opens your mail app), **Report a problem** (opens a GitHub issue in a browser) and **Rate OpenWispr** (opens Google Play). None of them sends anything by itself; each hands off to another app with the message for you to send.

**General** holds **Replay onboarding**, which walks the setup flow again, and a **Version** row reading the installed version followed by *"open source"* — read from the package manager, so it is the version you actually have rather than a number typed into the screen.

*Conditional*

## Settings that only appear sometimes

Several rows are hidden until something else makes them relevant. If you are looking for one of these and cannot find it, this is why:

| Hidden setting | Appears when |
| --- | --- |
| The on-device model list, and the device hint | Engine is On-device |
| API key, endpoint URL, model id, and the cloud privacy note | Engine is a cloud provider. Endpoint and model id are Custom-only. |
| Vocab-biased decoding (experimental) | Engine is On-device *and* the resolved model is Parakeet |
| Advanced polish model, and everything under it | AI polish is not Off, and you expand the disclosure |
| Cloud polish provider fields | You expand "Use a cloud model instead" and pick a non-local provider |
| Keep audio | Keep history is on |
| A model's delete button | That model is downloaded and is not the one currently in use |
| The setup card's permission rows | At least one of microphone, bubble or auto-insert is missing |

*Storage*

## Where these settings live

Not everything on this screen is stored in the same place, which matters if you are clearing app data selectively or reasoning about backups. Most settings are in the app's DataStore preferences. The bubble's on/off state and its position on screen are separate, as are the history switch and the audio retention period, each in its own small preferences file.

One consequence worth knowing: API keys for cloud providers are stored as plain text in the app's private preferences. They are not wrapped in the Android Keystore. On an unrooted device with no backup extraction that is private to the app, but it is not encrypted at rest and this page would rather say so than let you assume otherwise. The macOS app stores no API keys at all, because it has no cloud provider option.

## Questions

**What is the default AI polish level?**

Full. If the stored value is missing or unrecognised, it also resolves to Full.

**What does 'Anti-AI phrasing' do?**

It appends a long block of guardrails to the polish prompt — avoid inflated vocabulary, drop the "not X, but Y" pattern, no signposting, no chatbot sycophancy, and so on. It applies to the cloud polish path only. The on-device models get a much shorter prompt regardless of this setting, because sub-1B models loop when given guardrails that long. The app gives the toggle no subtitle, which is why it is easy to assume it does more than it does.

**Why can't I find the polish model picker?**

It is inside the collapsed "Advanced polish model" disclosure, which is itself hidden entirely when AI polish is set to Off. Set a polish level, then expand the disclosure.

**Why can't I delete a model?**

The delete button only appears on downloaded models that are not the one currently in use. Switch to a different model first, and the button appears on the old one. Deleting tells you how much space it freed, and the model can be downloaded again at any time from the same screen.

**Are my API keys encrypted?**

No. They are stored in the app's private DataStore preferences as plain text, not wrapped in the Android Keystore. They are private to the app on a normal device but they are not encrypted at rest.

**Do cloud providers work without a key?**

No, and the app does not try. Both the cloud speech and cloud polish paths check for a key and fail before opening any connection, so selecting a provider without entering a key never sends anything anywhere.

## Read next

- [Cleanup and polish](/docs/cleanup-and-polish.html)
- [What leaves your device](/docs/privacy-and-data.html)
- [Models and device fit](/docs/models.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/docs/settings.html.
