# Veena — App Store Connect listing, iOS

Reference for App Store Connect. Copy paste into the fields. All facts match the shipped app. Nothing here needs the network after the first model download.

Source of truth for claims: `veena-ios/Veena/Features/Dictate/DictateView.swift`, `rust_backend/src/lib.rs`, `veena-web/src/config/site.mjs`, `veena-web/src/pages/ios.astro`, `veena-web/src/pages/privacy.astro`.

---

## App information

* App name (30 char limit): `Veena: Offline Speech to Text`
  * Count is 28 characters. This is the primary ASO field, so it carries the generic term you need to rank for.
  * Alternative if you want stronger Telugu pull on day one: `Veena - Telugu Speech to Text` (27 chars). Use the primary for launch, test the alternative after 4 weeks if Telugu search drives most installs.

* Bundle ID: `com.byanr.veena`
* SKU: `veena-ios-001`
* Primary language: English (U.S.)
* Primary category: Productivity
* Secondary category: Utilities
* Price: Free
* Availability: Worldwide, but prioritize India storefront in Search Ads

## Subtitle (30 char limit)

`Offline dictation, 22 languages`

* Count is 28 characters.
* Covers two high intent phrases that are not in the app name: dictation and languages. Apple indexes subtitle heavily, so do not repeat speech or text here.

If you use the Telugu app name variant, switch subtitle to `Voice to text, works offline` (27 chars) to keep coverage wide.

## Keywords (100 char limit, comma separated, no spaces needed)

`telugu,tamil,hindi,urdu,bengali,marathi,gujarati,kannada,malayalam,punjabi,voice typing,transcribe,audio`

* Count is 97 characters. Paste exactly as written.
* Why these: The keywords field should not repeat words already in the name or subtitle. Apple ignores repeats. So speech, text, offline, dictation, languages, and veena are omitted here on purpose.
* Telugu, Tamil, and Hindi go first because they have the highest search volume among Indian language dictation queries, and a new app can actually rank for them. The tail terms voice typing, transcribe, and audio catch file import intent.
* Do not add 22 or numbers, Apple strips them. Do not add free, best, or competitor names, they are rejected or wasted.

## Promotional text (170 char limit, editable without a new build)

`Turn speech into text in 22 Indian languages, fully offline. No account, no uploads. Tap or hold the mic, edit, copy, and import audio from Files.`

* Count is 141 characters. Update this monthly. Use it to mention festivals, new languages, or press without resubmitting the binary.

## Description (4000 char limit, keep to about 1600 for read time)

Use this exactly. Paragraphs are short for the above the fold preview. The first three lines decide if someone taps more.

```
Speak in your language. Get text on your iPhone, even in airplane mode.

Veena turns speech into editable text in all 22 scheduled languages of India. It runs entirely on your phone, so you can dictate with no signal, on a flight, or anywhere the network is patchy. Your audio never leaves the device.

22 LANGUAGES, ONE APP
Assamese, Bengali, Bodo, Dogri, Gujarati, Hindi, Kannada, Kashmiri, Konkani, Maithili, Malayalam, Manipuri, Marathi, Nepali, Odia, Punjabi, Sanskrit, Santali, Sindhi, Tamil, Telugu, Urdu. Pick your language once, switch anytime in Settings. Telugu uses a dedicated model, the other 21 share a single multilingual model with per language decoding, so size stays sane.

WORKS OFFLINE, PRIVATE BY DESIGN
After a one time model download the app needs no internet at all. Telugu is about 125 MB, the full multilingual set is about 480 MB, downloaded once over wifi. No account, no cloud, no analytics, no ads. Recordings and transcripts are stored locally on your iPhone. Delete an entry and it is gone.

DICTATE OR IMPORT
On the Dictate screen hold the mic to record and release to transcribe, or set it to tap to start and tap to stop. The status pill shows recording, transcribing, or ready, with an elapsed timer. Text lands in an editable box you can fix before you copy. Use Copy or Clear in one tap, play back the last recording to check it, or import an audio file from Files and Veena will transcribe it on device.

HISTORY THAT STAYS YOURS
Every mic dictation and every imported file is saved with its language, date, duration, and transcript. Open any row to read the full text, copy it, play the audio, or delete it. Nothing syncs anywhere.

BUILT FOR THE IPHONE
Native SwiftUI app, light and dark mode, works with the iPhone keyboard and pasteboard. Microphone permission is used only to record when you press the mic. Background audio is off. The engine is the same Veena core that runs on Android and Windows: IndicConformer exported to ONNX and run with ONNX Runtime through Rust, quantized to INT8 so it fits on device and runs in under a second for short utterances.

WHO IT IS FOR
Students who take notes in Malayalam, shop owners who message suppliers in Gujarati, reporters who transcribe interviews in Hindi, anyone who would rather speak in their own script than type it.

Free. No subscription. Your voice stays on your phone.

Support: anr.appcontact@gmail.com
Privacy: https://veena.byanr.com/privacy/
Website: https://veena.byanr.com
```

Notes on what was not claimed: no real time streaming, no auto punctuation promises, no translation, no speaker labels, no cloud backup. Those would get you bad reviews or a rejection. The engine does CTC greedy decoding, which is fast and offline, but it will not punctuate like a server model. Do not promise that in screenshots or in reply to reviews.

## What's new (for v0.0.2, 4000 char limit, keep short)

```
First version of Veena for iPhone.

- Speech to text in 22 Indian languages, offline after a one time download
- Hold to record or tap to record, your choice in Settings
- Editable transcript with copy, clear, and play last recording
- Import audio from Files and transcribe on device
- Local history with language, date, and duration, copy or delete anytime
- No account, no uploads, audio stays on your phone
```

When you ship the next build, replace this with one or two real changes. Apple deprioritizes generic whats new text.

## Category rationale and where to rank as a new app

You will not outrank Apple Dictation, Otter, or Notta for speech to text on day one. Those terms have huge search volume and entrenched leaders with thousands of ratings.

You can rank for long tail Indian language terms where volume is solid and competition is thin: speech to text telugu, telugu voice typing, tamil speech to text, hindi voice to text, voice typing in marathi, transcribe audio offline, offline dictation gujarati, and similar combos.

That is why the name owns the generic and the keywords own the languages. Together they give you a chance in both generic browse and language specific search. In the description the full list of 22 languages is written out, that text is indexed for search, and it helps Siri suggestions too.

After launch, watch Search Console and App Analytics for impressions on those language phrases. If one language takes off, localize that storefront next. The site already has language pages at /speech-to-text/<slug>/, link them as your marketing URL per locale.

Do not chase ratings with prompts in the first session. Ask for a rating after a user has completed three successful transcriptions, that is when sentiment is highest.

## Screenshots and app preview

You need 6.7 inch and 6.5 inch sets at minimum, 5.5 inch if you still support it. Export from the simulator, do not add device frames that Apple already adds.

Order them to match the copy above:

1. Hero. Dictate screen with Telugu text in the box, language chip visible showing Telugu, status pill on Ready. Caption: Speak in Telugu, get text instantly, offline.
2. Languages. Language picker list showing native names. Caption: 22 Indian languages. Switch anytime.
3. Mic modes. Settings screen showing Hold to Record and Tap to Record cards. Caption: Hold or tap, your choice.
4. Import. Files picker open next to a transcript from an imported audio note. Caption: Import audio from Files. Transcribe on device.
5. History. History list with two or three rows, language tag, duration, date. Caption: History stays on your phone.
6. Privacy. Empty state detail plus the copy line No account. No uploads. Caption: Your audio never leaves your iPhone.

Captions should be sentence case, under 30 characters per line, no exclamation marks. Use the system font, white text on the dark blue from the splash screen #208AEF for contrast.

For the preview video, 20 seconds max, screen record on device, no hands, no music that needs a license. Show hold to dictate, release, text appears, copy.

## App privacy, age rating, and review notes

* App Privacy in App Store Connect:
  * Data collection: Data Not Collected. This matches the privacy page and the code. There is no analytics SDK, no crash reporter that sends user content, no server upload of audio. The only network request is the one time model download, which sends no user data.
  * If you add crash reporting later, update this before that build goes live.

* Age Rating: 4+
* Permissions:
  * Microphone usage string: `Veena needs access to your microphone to transcribe speech to text.` This is already set in app.json. Keep the wording identical in App Store Connect privacy prompts.
  * No background modes. Do not enable background audio.

* Review notes to paste:
```
Veena is an offline speech to text app. After install, the app downloads a speech model once over wifi, then works with no network. All transcription is local.

To test:
1. Allow microphone.
2. Pick Telugu or Hindi on first launch. The model downloads, then shows Ready.
3. Hold the mic, speak for 2 to 3 seconds, release. Text appears in the editable box.
4. Try Files import using any short m4a or wav in Files.
5. History tab shows saved entries, can be copied or deleted.

No account, no login, no payment. Bundle ID com.byanr.veena, version 0.0.2.
Contact anr.appcontact@gmail.com if you need a test audio file.
```

* Support URL: https://veena.byanr.com
* Privacy Policy URL: https://veena.byanr.com/privacy/
* Marketing URL per locale: https://veena.byanr.com/ios/

## Search ads starter set

Do not bid on speech to text as an exact match yet. You will pay too much.

Start with discovery broad match on low cost language phrases, capped at 30 to 50 INR per tap, India only:

- voice typing telugu, telugu speech to text, tamil voice typing, hindi speech to text, marathi voice to text, gujarati dictation, transcribe audio offline, offline voice to text

Add negative keywords: english, spanish, free online, pc, windows, mac, so you do not pay for desktop intent.

After 200 taps, move the winners to exact match ad groups, raise bids on the top three, pause the rest.

## Localization to do next

Ship English first. Then add two storefronts where demand is clear:

* Hindi (hi) and Telugu (te). Translate name, subtitle, keywords, and the first three description paragraphs only. Keep screenshots in English for now, but swap the transcript example to Hindi or Telugu in one shot.

Keywords for Hindi storefront, 100 chars:
`हिंदी,वॉइस टाइपिंग,बोलकर लिखें,ट्रांसक्राइब,ऑफलाइन,डिक्टेशन,मराठी,गुजराती,तमिल,तेलुगु`

Keywords for Telugu storefront, 100 chars:
`తెలుగు,వాయిస్ టైపింగ్,మాట్లాడి రాయండి,ట్రాన్స్క్రైబ్,ఆఫ్లైన్,డిక్టేషన్,హిందీ,తమిళం,కన్నడ,మలయాళం`

Have a native speaker check these, App Store search is sensitive to spelling and script mixing.

## Checklist before submit

* App icon has no alpha, 1024 by 1024.
* Screenshots show real transcripts, not lorem ipsum, and scripts render correctly, check Bodo, Santali Ol Chiki, and Urdu Nastaliq if you include them.
* Info.plist microphone string matches the listing.
* Test on a clean device with no model cached, verify the download size warning shows before the 480 MB fetch, and that airplane mode after download still transcribes.
* Ratings prompt not shown on first launch.

---

Last checked against code on 2026-08-27. If the model sizes or language count change, update the description line that says 22 languages and the about 125 MB or about 480 MB line before the next submission. Those two facts are the ones reviewers spot check.
