---
title: "India's 22 Scheduled Languages and the Case for Native Speech Recognition"
description: "India's Eighth Schedule lists 22 scheduled languages, yet most speech technology serves a few. Why local, on-device recognition matters for digital inclusion."
pubDate: 2026-08-01
tags: ["indian languages", "digital access"]
draft: false
faq:
  - q: "What are the 22 scheduled languages of India?"
    a: "They are the languages listed in the Eighth Schedule of the Constitution: Assamese, Bengali, Bodo, Dogri, Gujarati, Hindi, Kannada, Kashmiri, Konkani, Maithili, Malayalam, Manipuri, Marathi, Nepali, Odia, Punjabi, Sanskrit, Santali, Sindhi, Tamil, Telugu, and Urdu."
  - q: "Why do low-resource languages need local speech recognition?"
    a: "Cloud services concentrate on the largest languages, so speakers of smaller scheduled languages are left with poor or no support. On-device engines with dedicated vocabularies can serve every scheduled language, offline and without uploading audio."
  - q: "Does Veena support all 22 scheduled languages?"
    a: "Yes. Veena's multilingual engine covers all 22 scheduled languages of India, with a dedicated vocabulary and decoding tuned for each language, running entirely on your device."
---

The Constitution of India recognizes 22 scheduled languages, a list that spans hundreds of millions of speakers at one end and a language kept alive by a devoted few at the other. Walk into a government office, a bank, or a hospital in most of the country, and the digital systems on the desk will be running in English or Hindi, regardless of the languages spoken through the window.

The gap between the languages the Constitution recognizes and the languages technology serves is one of the quietest digital divides in the world. Speech recognition, done right, is one of the few technologies that can actually close it.

## What the Eighth Schedule is

The Eighth Schedule of the Constitution of India is the list of languages the state recognizes for official purposes. It began with 14 languages in 1950 and grew through constitutional amendments to 22 today: Assamese, Bengali, Bodo, Dogri, Gujarati, Hindi, Kannada, Kashmiri, Konkani, Maithili, Malayalam, Manipuri (Meitei), Marathi, Nepali, Odia, Punjabi, Sanskrit, Santali, Sindhi, Tamil, Telugu, and Urdu. For the full reference list with scripts and speaker counts, see our [page on the 22 scheduled languages of India](/scheduled-languages-of-india/).

Recognition matters. It entitles a language to official status, instruction, and cultural support from the state. It signals that a language is not a dialect or a local quirk but part of the country's linguistic fabric.

## The languages in numbers

The schedule spans radically different scales, and every tier of it deserves a working speech interface:

- The largest: Hindi, spoken by more than 500 million people, and Bengali, with about 100 million speakers in India.
- The major regional languages: Telugu (about 90 million), Marathi (about 85 million), Tamil (about 75 million), Gujarati (about 60 million), Urdu (about 50 million), Kannada (about 45 million), Odia (about 40 million), Malayalam (about 38 million), Maithili (about 35 million), and Punjabi (about 35 million in India).
- The smaller but still substantial: Assamese (about 25 million), Nepali (about 16 million in India), Santali (about 8 million), and Kashmiri (about 7 million).
- The least served: Sindhi (about 3 million in India), Dogri (about 2.5 million), Konkani (about 2.5 million), Manipuri (about 2 million), and Bodo (about 1.5 million).
- The exceptional: Sanskrit, spoken by about 25,000 people, with a revival underway.

Every one of these communities has the same need: to produce text in their own language. Very few of them are served by mainstream speech technology.

## Why speech is the right interface

Text input is the bottleneck of digital access in India. Indic scripts are beautiful and their keyboards are painful. Typing Bengali, Kannada, or Santali on a phone involves transliteration guesswork, layout switching, and slow correction. For a speaker who is not a trained typist, producing a paragraph in their own script can take many minutes.

Speech removes the bottleneck. Speaking is the one input method every adult has practiced for a lifetime. A speech recognizer that works in your language turns a fifteen-minute typing session into a thirty-second dictation. That is the difference between digital services being theoretically available and actually usable.

## Why low-resource languages need local recognition

Most speech recognition services, cloud-based or otherwise, follow the market: they ship the biggest languages first and treat the long tail as an afterthought. A speaker of Bodo or Santali is not just underserved; they are usually not served at all. The result is a feedback loop in which the languages with the fewest resources get the least technology.

On-device speech recognition breaks the loop. Because the recognition engine runs locally, there is no per-minute cost, no data center to justify, and no upload to worry about. An engine built on a multilingual model, with a dedicated vocabulary per language, can cover all 22 scheduled languages at once. Veena is built exactly this way: its engine is based on IndicConformer, a multilingual model from AI4Bharat, with each language getting its own vocabulary and decoding. One bundle, every scheduled language.

Local recognition also solves the practical problems of the cloud approach: patchy connectivity in rural areas, and the privacy cost of uploading audio that contains names, family details, and personal information. For a scheduled language with few speakers, the data collected by a cloud service is not an asset; it is a privacy risk with no compensating benefit.

## Digital inclusion in practice

Speech recognition in every scheduled language is a bridge between citizens and services:

- Government services: forms, entitlements, and applications in the language of the applicant.
- Banking: account queries and instructions in the customer's language.
- Education: notes, homework, and study material in the mother tongue.
- Healthcare: dictation in the language of the consultation, as covered in our [article on medical dictation](/blog/speech-to-text-for-doctors/).

In each case the pattern is the same: the citizen speaks, the device writes. No keyboard training, no transliteration, no dependence on English as the price of access.

## The case for native recognition

A language is not supported by technology just because it exists on a list. Support means a recognizer that handles its sounds, its script, and its vocabulary with the care a native speaker would want. That is what a dedicated vocabulary buys: the model knows the words of the language rather than guessing from a shared pool.

For the 22 scheduled languages of India, the technology to do this exists today, on-device, free, and offline. The question is whether speakers of every scheduled language get to use it. On [Veena's speech to text hub](/speech-to-text/) you can see the case for each language: from [Bengali speech to text](/speech-to-text/bengali/) and [Tamil speech to text](/speech-to-text/tamil/) at the scale of hundreds of millions, to [Santali speech to text](/speech-to-text/santali/) and [Bodo speech to text](/speech-to-text/bodo/) for communities the mainstream has ignored.

When every scheduled language has a working recognizer, the Eighth Schedule stops being a document about the past and becomes a commitment technology actually honors.
