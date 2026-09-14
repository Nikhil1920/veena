---
title: "Getting Started with Veena: First Transcription in 5 Minutes"
description: "Get your first Veena transcription in about five minutes: install the app, pick a language, allow the microphone, record, and copy or share your text."
pubDate: 2026-08-08
tags: ["veena", "tutorial", "getting started"]
draft: false
faq:
  - q: "Do I need an account to use Veena?"
    a: "No. Veena is free and requires no account. There is no signup, no login, and no upload: transcription happens entirely on your device."
  - q: "Does Veena need internet to transcribe?"
    a: "Only once. Veena downloads a recognition model to your device during setup, and from then on transcription works fully offline. Your audio never leaves your device."
  - q: "Which languages can I transcribe with Veena?"
    a: "Veena's engine covers all 22 scheduled languages of India. During setup you download a model: a dedicated one for Telugu, or the multilingual model for the other 21 languages. Each language decodes through its own vocabulary."
  - q: "What is the desktop paste-into-app flow?"
    a: "On Windows, Veena works with push-to-talk or a global shortcut from any application: the transcript is pasted directly into the app you are using, or copied to the clipboard."
---

Veena is a speech to text app that runs entirely on your device: free, no account, no cloud, fully offline. It records your voice and transcribes it locally with an on-device model, so nothing is uploaded anywhere. If you can tap a microphone button and speak, you can get your first transcript in about five minutes.

Here is the whole flow, step by step.

## What you need

Veena runs on Android and iOS phones, and on Windows PCs. Desktop builds for macOS and Linux are in development. You do not need an account or a login. Transcription itself needs no internet; the one-time model download does. Beyond that you need a device, a quiet-ish room, and something to say.

## Step 1: Install Veena

On your phone, install the Veena app from the Google Play store or the App Store. On a computer, download the Windows installer from the [download page](/download/).

That is the whole installation: there is no signup flow to click through afterwards. Open the app and you are at the recorder.

## Step 2: Pick your language

Veena's recognition engine covers all 22 scheduled languages of India, and each language has its own vocabulary and decoding tuned for it. How you select the language depends on your platform.

During setup, Veena downloads the model that matches your language: a dedicated Telugu model, about 125 MB, or the multilingual model, about 480 MB, which covers all 22 languages. The download happens once, over wifi or mobile data. When it finishes you can start recording immediately, with no connection.

Set the app to the language you will actually speak. If you are dictating in Hindi, select Hindi so the output comes out in Devanagari. For the full list, see the [speech to text hub](/speech-to-text/).

## Step 3: Allow microphone permission

The first time you record, your phone or computer asks for microphone access. Allow it. This is the only permission the app prompts for: no contacts, no location, no account, no storage scares. The app needs the microphone because recording audio is the whole job.

## Step 4: Record

Tap the microphone and speak. Veena records 16 kHz mono audio, which is a clean, compact signal for speech recognition, and transcribes it locally.

The mobile app offers two modes. Use hold-to-record when you want a short burst: hold the button while you speak and release when you finish. Use tap-to-record for longer dictation: tap once to start, tap again to stop. If the last recording did not come out as intended, you can replay it to check what was captured.

## Step 5: Review and edit

Your transcript appears in an editable text box. Read it once, fix names and numbers, and add the punctuation the recognizer missed. On the desktop app you can also enable optional post-processing, like filler-word removal, so "um" and "uh" never land in the text, plus custom-word correction for words you use often.

## Step 6: Copy or share

When the text is right, copy it to the clipboard or share it with the app of your choice: your notes app, email, a chat, a document. On mobile, pasting the clipboard into your notes app is the standard finishing move.

## The desktop paste-into-app flow

The desktop app is where Veena feels most like a keyboard replacement. Instead of opening a recorder window, you use push-to-talk or a global shortcut from any application. Speak, and the transcript is pasted directly into the app you are using, whether that is a word processor, an email draft, or a form. No switching windows, no copy-paste dance, no upload.

The desktop app also keeps a recording history with retention settings, so past dictations are findable and their storage is under your control. Everything stays keyboard-driven, which is how fast writers like to work.

## Tips for a clean first transcription

A few things make the first run go well:

- Find a quiet place. Background noise is the main cause of transcription errors.
- Speak at a steady, natural pace. Not rushed, not robotic.
- Say punctuation out loud: "full stop", "comma", "question mark".
- Avoid switching languages mid-sentence. One language per recording gives the model its best chance.

## What Veena does not do

A few honest notes so there are no surprises. Transcription itself is fully on-device, but Veena is not a cloud suite: there is no account sync, no cloud backup of recordings, and no built-in AI rewriting. The desktop app has optional post-processing features, and an optional LLM post-processing step exists, but it requires you to configure your own API provider, so it is not something that runs by default or for free. For everyday use, the transcription is the product: fast, local, private text.

## Next steps

That is the whole loop: install, pick a language, allow the microphone, record, edit, copy or share. On desktop, add the global shortcut and the paste-into-app flow becomes your daily driver.

Want the deeper picture? Read [how Veena works](/features/), meet the [project and its makers](/about/), or browse the language pages to see what dictation sounds like in your language: [Hindi](/speech-to-text/hindi/), [Telugu](/speech-to-text/telugu/), [Bengali](/speech-to-text/bengali/), and the rest of the 22 on the [speech to text hub](/speech-to-text/).
