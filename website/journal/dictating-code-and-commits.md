---
title: "How We Dictate Commit Messages and PR Descriptions With OpenWispr | OpenWispr Journal"
description: "A first-person account from the OpenWispr project: how we dictate commit messages, PR descriptions and code notes with our own app, and the personal dictionary, code-aware formatting and never-lose-audio features it leans on."
canonical: "https://openwispr.dev/journal/dictating-code-and-commits.html"
language: "en"
---


# How we dictate commit messages and PR descriptions with OpenWispr

We build OpenWispr, and we dictate most of our own commit messages, PR descriptions and code notes with it. Here's what that actually looks like, and which parts of the app it depends on.

> **From the project.** This is a first-person account from the people building OpenWispr, about how we use our own app day to day — not a customer testimonial, and not a fictional user. Every feature named links to the source that implements it.

*Why we bother dictating code notes at all*

## Typing a commit message is a small tax. It adds up.

A lot of a working day writing OpenWispr is short bursts of prose that aren't the code itself: a commit message, a PR description, a comment explaining why a fix works the way it does, a quick note-to-self in an issue. None of it is long. All of it is typed between two much more demanding tasks — reading a diff, or actually writing the fix — which is exactly the kind of context-switch tax that's worth automating away if it can be done without getting in the way.

So a fair amount of this app's own commit history was, in fact, dictated with itself. That's not a claim about how good the transcription is in the abstract — it's the reason a few specific features exist at all: they were built because dictating code-adjacent text surfaced problems that dictating normal prose doesn't.

*Terse in a terminal, fuller in a doc — but not by asking an LLM to guess*

## The terminal doesn't get a "tone," it gets left alone

OpenWispr's optional LLM-polish step can bias its output by which app is focused — a professional, complete-sentence tone by default for email and office apps, a casual, contraction-heavy tone for chat and social apps. But when the focused app is a code editor or a terminal, dictation goes through a different, deterministic path instead of getting an LLM tone at all: a spoken-form normalizer that keeps words like "dot", "dash" and "slash" as literal punctuation rather than expanding them into prose, because in a shell command or a file path those words mean something exact.

The interesting part wasn't deciding that code editors need this — it's how the app decides a *terminal* does. Early internal use turned up that the large majority of what got dictated into a terminal (iTerm, in practice) wasn't a shell command at all — it was a natural-language prompt to an AI coding agent running in that same terminal. Treating every terminal utterance as "this is a shell command" would have mangled those prompts by refusing to expand normal words. So the app looks at the content, not just the app: short utterances (ten words or fewer) that open with a recognized command verb, a path, or a CLI flag get command-style literal formatting; anything longer and more sentence-shaped is left as prose, even inside a terminal. A commit message typed straight after `git commit -m` and a paragraph explaining a bug to an agent in the same window get treated differently, on purpose, because they are different things.

*It learns the repo's own jargon*

## Names and terms it gets wrong once, it stops getting wrong

A codebase has its own vocabulary — library names, internal class names, a competitor's name, an acronym that isn't a real word — and generic speech recognition mishears a lot of it consistently, the same way, every time. OpenWispr's personal dictionary is built around that specific failure mode: when a transcript comes back wrong and you fix it, the app compares your edit against what it produced, word by word, and remembers the fix — up to five aligned corrections per edit, deliberately conservative, so a big rewrite of a sentence doesn't get treated as five vocabulary entries.

The matching isn't just exact string replacement. It uses a phonetic check (Soundex) plus a small edit-distance score, gated more strictly for words that were manually taught than for words learned from an edit, so a mis-transcription that merely *sounds* like a term you've corrected before still gets caught. And because the on-device transcription models take an optional bias prompt, the app doesn't just correct text after the fact — it also feeds the terms you've actually had to fix, ranked by how often you've corrected them, back into the decoder itself, ahead of manually-added terms and imported contacts, inside a small fixed budget. In practice: the first time a commit message about our own on-device speech engine came out wrong, the correction stuck, and it stopped happening.

*What happens when a dictation fails mid-PR-description*

## The recording doesn't disappear because a transcription attempt did

PR descriptions are usually the longest thing dictated in a day, and long recordings are exactly where a slow network, a killed process, or a transcription error used to cost the most: the audio only ever lived as an in-memory buffer, so any failure took the recording down with it. That's the specific bug this app's write-ahead audio store exists to make impossible — the recording is saved to disk before the first transcription attempt is even made, on both the on-device and the cloud path, and it's never deleted from an error handler.

The practical effect while dictating a PR description: if a transcription attempt fails, nothing is lost and nothing has to be re-recorded. The Home screen surfaces it as unfinished, and retrying it explicitly prefers a different engine than the one that just failed — a transcription failure is often deterministic, so simply pressing retry against the identical engine would just fail identically a second time. Choosing an alternate model instead means a genuinely different attempt, not a repeat of the same one.

## Questions

**Does OpenWispr write code for you?**

No. This is about dictating the natural-language text around code — commit messages, PR descriptions, comments, notes to an AI coding agent — not generating code itself.

**How does OpenWispr know a terminal utterance is a shell command and not a sentence?**

It looks at the content of that specific utterance, not just which app is focused: short (ten words or fewer) text starting with a recognized command verb, a path, or a CLI flag is treated as a command; longer, sentence-shaped text is treated as prose, even inside the same terminal app. This exists because a large share of real terminal dictation turned out to be natural-language prompts to an AI agent, not shell commands.

**How many vocabulary corrections does it learn from one edit?**

Up to five position-aligned word fixes per edit, deliberately capped and conservative — a large rewrite of a sentence is treated as too different to learn from, so the personal dictionary doesn't get polluted with noise.

**Does personal dictionary matching require an exact repeat of the same mis-transcription?**

No — matching uses a phonetic check (Soundex) plus a small edit-distance score, so a mis-transcription that sounds similar to (not identical to) a term you've corrected before can still be caught and fixed.

**What happens if a PR description recording fails to transcribe?**

The audio is written to disk before the first transcription attempt starts, on both the on-device and cloud paths, so a failure doesn't take the recording with it. It shows up as unfinished on the Home screen, and retrying it defaults to a different transcription engine than the one that just failed.

## Read next

- [Why we built OpenWispr's retry safety net the way we did](/journal/why-we-never-lose-your-audio.html)
- [OpenWispr vs VoiceInk (failure-recovery UX)](/compare/voiceink.html)
- [On-device Whisper dictation for Android](/android/on-device-whisper-dictation-android.html)

---

OpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), [Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), [macOS from Releases](https://github.com/RohitAg13/openWispr/releases). This page is the Markdown mirror of https://openwispr.dev/journal/dictating-code-and-commits.html.
