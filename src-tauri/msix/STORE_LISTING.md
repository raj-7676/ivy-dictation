# Ivy Dictation: Microsoft Store listing

Paste these into Partner Center > Ivy Dictation > submission. Keep them in sync with README.md and the website.

## Properties

- Category: **Productivity** (subcategory: none)
- Privacy policy URL: https://raj-7676.github.io/ivy-dictation/privacy.html
- Website: https://raj-7676.github.io/ivy-dictation/
- Support contact: https://github.com/raj-7676/ivy-dictation/issues
- System requirements: Microphone (required). About 3 GB of free disk space. A graphics driver (NVIDIA, AMD or Intel; every current driver includes Vulkan). A graphics card makes Ivy much faster; it also runs on the CPU alone.
- Product declarations: does NOT access, collect or transmit personal information.

## Pricing and availability

- Price: **Free**. Markets: all.

## Age ratings

Questionnaire answers: no violence, no sexual content, no gambling, no user-to-user communication, no purchases, does not share location or personal info. Expected result: rated for everyone (3+).

## Description

Talk, and clean text appears wherever your cursor is.

Hold a key, speak naturally, let go. Ivy turns your speech into clean, ready-to-send text and pastes it into whatever you were typing in: email, chat, documents, code editors, browsers, anything.

It writes what you meant, not just what you said. Say "Let's meet on Tuesday, sorry, I mean Thursday" and Ivy types "Let's meet on Thursday." It also drops fillers like um and uh, removes stutters, adds punctuation and capital letters, and writes numbers, money, times and dates the way you'd type them.

Everything happens on your own computer. No account, no subscription, no internet connection, no cloud. Your voice never leaves your PC.

One small speech model (a fine-tune of Qwen3-ASR-1.7B) hears you and cleans up in a single pass, so it stays fast: about a third of a second for a 15-second dictation on a graphics card, about 3 seconds on the processor alone.

Free to use, with all of its source code public on GitHub.

## Features (one per line)

- Hold Alt + Space (or Ctrl + Shift), talk, let go: clean text appears in any app
- Understands self-corrections: "Tuesday, sorry, I mean Thursday" becomes "Thursday"
- Drops ums and uhs, removes stutters, adds punctuation
- Three tones: Casual, Standard, Professional, set per app automatically
- Numbers typed right: ₹500, 3 PM, 18 lakhs, 2.5 crores, 2 million
- Hands-free mode for dictations up to 5 minutes
- Personal dictionary and snippets for names, jargon and saved text
- Touch Up: one click fixes spelling without rewriting your sentences
- 100% offline: no account, no cloud, history deletes itself after 24 hours
- Runs on any NVIDIA, AMD or Intel graphics card, or on the CPU alone

## Search terms (max 7)

dictation, speech to text, voice typing, offline, transcription, voice to text, private

## Short description

Offline voice dictation that writes what you meant. Hold a key, talk, and clean text appears in any app.

## Additional testing information (for certification)

Ivy is a desktop voice-dictation app. To test: open Ivy, finish the short setup, then click into any text field (for example Notepad), hold Alt + Space, say a sentence, and release. The text is pasted where the cursor is.

runFullTrust justification: Ivy registers a system-wide dictation hotkey and pastes text into the app the user is typing in, which a sandboxed app cannot do. It makes no network connections: the speech model ships inside the package and runs locally.

## Screenshots needed

At least 1 (best 4), 1920x1080 PNG, from the installed Store build: main window, dictation bar over an app, Settings with tones, setup wizard.
