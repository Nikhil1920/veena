---
title: "How Speech to Text Works: From Audio to Text, Explained Simply"
description: "How speech to text works under the hood: 16 kHz audio capture, mel features, neural models and CTC decoding, explained simply for non-engineers."
pubDate: 2026-04-18
tags: ["how it works", "ASR"]
draft: false
faq:
  - q: "What does 16 kHz mean in speech to text?"
    a: "It means the audio is sampled 16,000 times per second, in a single mono channel. Speech recognition systems use 16 kHz mono because it captures the frequencies of human speech clearly while keeping the data small enough to process quickly, even on a phone."
  - q: "What is CTC decoding?"
    a: "CTC (Connectionist Temporal Classification) aligns a model's thousands of per-frame predictions to a short sequence of characters, collapsing repetitions and removing blank frames. It is the step that turns a stream of sound probabilities into a string of text."
  - q: "Why does IndicConformer matter for Indian languages?"
    a: "IndicConformer is a family of speech recognition models trained by AI4Bharat on Indian languages. Veena uses IndicConformer-600M-Multilingual, exported to ONNX and quantized so it runs entirely on-device, with a dedicated vocabulary and decoding for each of the 22 scheduled languages of India."
---

When you tap the microphone and speak, a lot happens between your voice and the words that appear on screen. It is easy to treat speech to text as magic, but the pipeline is actually a sequence of well-understood steps: capture the audio, convert it into features a model can read, run a neural network, and decode the result into text. This article walks through each step in plain language, and explains how a model like the one inside Veena — built on IndicConformer — handles Indian languages. The same pipeline powers everything from phone dictation to meeting transcription.

## Step 1: Capture the audio

Everything starts with the microphone. Your phone or laptop converts the pressure waves of your voice into a digital signal by sampling them thousands of times per second. Speech recognition does not need the full frequency range of music, so most systems resample the audio to 16 kHz mono: 16,000 samples per second, single channel. That is a deliberate choice. It is high enough to capture the frequencies that carry human speech clearly, and small enough to keep the data manageable. A one-minute recording at 16 kHz mono is roughly two megabytes of raw audio, which is exactly the size an on-device model can process quickly. Veena records 16 kHz mono audio for every transcription.

## Step 2: Turn sound into features

A neural network cannot work directly with raw audio samples. The next step transforms the waveform into a compact representation that highlights what matters for speech: which frequencies are present at each moment. The standard approach is to compute a mel spectrogram: a picture of sound over time, where the vertical axis is frequency, the horizontal axis is time, and the frequency scale is mapped onto the mel scale, which approximates how human hearing perceives pitch. Short windows of audio, typically 20 to 30 milliseconds, are converted into feature frames. The result is a stream of feature vectors the model can ingest, usually dozens per second. This step is called feature extraction, and it looks the same regardless of language.

## Step 3: Run the neural model

The feature frames go into a neural network trained to map sounds to the building blocks of language. Modern systems use architectures such as Conformers, which are good at capturing both the local detail of a sound and the longer context of a sentence. For Indian languages, AI4Bharat trained the IndicConformer family on large amounts of Indic speech. Veena uses IndicConformer-600M-Multilingual, exported to ONNX and quantized so it runs entirely on-device.

What does the model output? Not words directly, but a sequence of probabilities over a set of units: often characters, sometimes subword units such as character pairs and syllables. For Indian languages this matters. Scripts like Devanagari combine consonants and vowels into complex glyphs, so the model needs units that can represent those combinations cleanly. Each language gets its own vocabulary and decoding tuned for it, which is why the same engine can cover all 22 scheduled languages of India.

## Step 4: Decode the output into text

The probabilities from the model are not yet text. Decoding turns them into the actual string you see. A widely used technique is CTC decoding, short for Connectionist Temporal Classification. Speech is longer than the text it represents: you pronounce the word "hello" over a span of time, not in a single instant. CTC aligns the model's per-frame predictions to a sequence of characters, collapsing repetitions and removing blank frames. The decoder then applies the language's vocabulary and, on many systems, a language model to pick the most likely sequence of words.

This step is where per-language tuning really shows. The same acoustic signal can be decoded very differently for Hindi versus Punjabi, because the vocabularies, scripts and allowed character sequences differ. A decoder tuned for Telugu knows which character sequences appear often and which almost never, and it uses that knowledge to resolve ambiguity. This is also where the model learns to respect the script: you speak Hindi, and you get Devanagari, not a romanized approximation.

## What happens on your device

Not every speech to text system runs these steps locally. Cloud systems send your audio to a server and run the pipeline there. On-device systems compress the model so it fits on a phone or laptop: quantization reduces the numerical precision of the model's weights, trading a little accuracy for much smaller size and faster speed, and everything runs locally. Veena is an on-device system. After a one-time model download, capture, feature extraction, inference and decoding all happen on your device. No internet connection is needed, and your audio never leaves your device. You can read more about Veena on the [about page](/about/), see how it fits into everyday writing in our [complete guide to speech to text in Indian languages](/blog/speech-to-text-in-indian-languages/), or jump straight to the [language hub](/speech-to-text/) to try it in your own language.

## The bottom line

Speech to text is a pipeline, not a single step: capture at 16 kHz, extract mel features, run a neural model, and decode with CTC. Understanding the steps makes it easier to see why some tools work better than others: why a model trained on one language struggles with another, why offline apps can be just as practical as cloud ones, and why vocabulary and decoding matter as much as the neural network itself.
