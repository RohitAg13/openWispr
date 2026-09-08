---
title: "Privacy Policy — OpenWispr"
description: "What OpenWispr processes on your device, what an optional cloud provider would see, and what the project never receives."
canonical: "https://openwispr.dev/privacy.html"
language: "en"
---

# Privacy Policy

Last updated: July 12, 2026

**The short version:** OpenWispr runs entirely on your device by default. Your voice recordings and the text you dictate stay on your device and are not sent to us or anyone else — unless *you* explicitly turn on a cloud provider. We have no servers, no accounts, no analytics, and no ads. We never see your data.

OpenWispr ("the app") is open-source voice-dictation software for Android and macOS. This policy explains what the app does with your information. It applies to the OpenWispr apps published by the OpenWispr project.

## 1. Data processed on your device

By default, everything the app does happens locally on your device:

- **Voice recordings.** When you dictate, the app records audio from your microphone and transcribes it using a speech model that runs **on your device**. The audio is held only for as long as needed to produce the transcript and is then discarded. It is not uploaded anywhere by default.
- **Transcripts and text.** Your transcribed text is cleaned up and (optionally) refined by a language model that also runs **on your device**. The finished text is inserted into whatever app you are using.
- **Dictation history.** If you keep history enabled, past dictations are stored locally on your device so you can review them. You can clear individual items, wipe all history, or turn history off entirely in Settings.
- **Personal dictionary & style memory.** Names, terms, and writing-style examples the app learns from your use are stored on your device and are **never uploaded**.
- **Contacts (optional).** If you choose to import contacts, contact names are matched **on your device** to help the app spell names correctly. This is opt-in, and your contacts are not uploaded.

## 2. Optional cloud providers

OpenWispr lets you optionally use a third-party cloud service for speech-to-text and/or text refinement instead of the on-device models. This is **off by default** and only happens if you choose it and enter your own API key. If you enable a cloud provider:

- The relevant audio or text is sent directly from your device to the provider you selected (for example Groq, OpenAI, Anthropic, OpenRouter, or a custom endpoint you configure) so it can be processed and returned.
- That data is handled under **that provider's** privacy policy and terms, not ours. We are not an intermediary — the request goes from your device to them.
- Your API keys are stored on your device and are used only to talk to the provider you chose.

You can switch back to fully on-device processing at any time in Settings.

## 3. Permissions and how they're used

- **Microphone** — to capture your voice when you dictate.
- **Accessibility service** — used for one purpose only: to type your finished, dictated or rewritten text into the text field you are currently focused on. It is **not** used to read your screen for any other purpose, and it does not collect, store, log, or transmit the content of your screen or other apps. You can decline it and paste your text manually instead.
- **Display over other apps (overlay)** — to show the floating tap-to-talk bubble.
- **Notifications** — to show the status of the dictation service.
- **Contacts** (optional) — as described above; opt-in and processed on-device.
- **Internet** — to download the speech and language models on first use, and, if you enable a cloud provider, to send requests to that provider.

## 4. What we do not do

- We do **not** run any servers that receive your data.
- We do **not** require an account or sign-in.
- We do **not** include analytics, tracking, or advertising SDKs.
- We do **not** sell or share your personal data. There is nothing to sell — your data stays on your device.

## 5. Data retention and deletion

Because your data lives on your device, you are in control of it. You can clear your dictation history, personal dictionary, and learned style at any time from within the app's Settings. Uninstalling the app removes all of its locally stored data from your device.

## 6. Children

OpenWispr is a general-purpose productivity tool and is not directed to children under 13. We do not knowingly collect personal information from children.

## 7. Changes to this policy

If we make material changes to this policy, we will update the "Last updated" date above and publish the revised policy at this URL. Because OpenWispr is open source, the full history of this document is also available in the project's public repository.

## 8. Contact

OpenWispr is developed in the open. If you have any questions about this policy or the app's privacy practices, please open an issue on our [GitHub repository](https://github.com/RohitAg13/openWispr).

---

Markdown mirror of https://openwispr.dev/privacy.html.
