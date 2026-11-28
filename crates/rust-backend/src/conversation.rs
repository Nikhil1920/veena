//! Conversation captions: audio arrives for as long as someone keeps the
//! screen open, and comes back as one line of text per utterance.
//!
//! An utterance runs from the start of speech to the next pause of
//! [`END_PAUSE_FRAMES`]. One that goes on without such a pause is cut once it
//! passes [`MAX_UTTERANCE_SECONDS`], at its quietest moment near the end, and
//! the rest carries on as the next utterance. While an utterance is spoken it
//! previews like a live session (see `live.rs`); once it ends it is
//! transcribed whole and its audio dropped, so memory stays bounded however
//! long the conversation runs. Nothing is decoded while nobody speaks.
//!
//! Speech is told from background noise by energy against a noise floor
//! that follows the room: the quietest 20 ms of the last few seconds. The
//! model copes with noise itself; this only decides where lines break and
//! when to stay idle.

use std::collections::VecDeque;
use std::error::Error;

use serde::Serialize;

use crate::chunk::{self, SAMPLE_RATE, WINDOW_SECONDS};
use crate::live::{LiveWindows, Transcribe};
use crate::{preprocess, Decoder, Transcriber};

/// Speech is detected 20 ms at a time.
const FRAME_SAMPLES: usize = SAMPLE_RATE / 50;
/// Speech starts after 60 ms of it in a row...
const ONSET_FRAMES: usize = 3;
/// ...and the utterance keeps the 300 ms before that, so its first sound is whole.
const PREROLL_SAMPLES: usize = 3 * SAMPLE_RATE / 10;
/// A pause this long (700 ms) ends the utterance...
const END_PAUSE_FRAMES: usize = 35;
/// ...which keeps the first 300 ms of it: the model expects an utterance to end.
const KEPT_PAUSE_SAMPLES: usize = 3 * SAMPLE_RATE / 10;
/// An utterance with less speech than 200 ms is a knock or a click, not a word.
const MIN_SPEECH_FRAMES: usize = 10;
/// Longer utterances are cut at their quietest moment in the last 3 s.
const MAX_UTTERANCE_SECONDS: usize = 15;
/// Another preview is worth running for 200 ms of new speech.
const PREVIEW_STEP_SAMPLES: usize = SAMPLE_RATE / 5;

/// The noise floor is the quietest frame of the last 3 s, kept as the
/// quietest frame of each half second.
const FLOOR_BLOCK_FRAMES: usize = 25;
const FLOOR_BLOCKS: usize = 6;
/// Speech is at least 10 dB over the noise floor...
const SPEECH_OVER_FLOOR: f32 = 3.16;
/// ...and over -54 dBFS, so a silent room's faint hiss never counts.
const MIN_SPEECH_RMS: f32 = 0.002;

const _: () = assert!(
    MAX_UTTERANCE_SECONDS < WINDOW_SECONDS,
    "an utterance is transcribed in one window"
);

/// What changed since the last update.
#[derive(Debug, Default, PartialEq, Serialize)]
pub struct CaptionUpdate {
    /// Utterances that ended, oldest first: their final text. Utterances that
    /// decoded to nothing are left out.
    pub finished: Vec<String>,
    /// The utterance still being spoken, as text whose words can still
    /// change; empty when there is none or it has no words yet.
    pub partial: String,
    /// Whether someone is speaking: an utterance is in progress.
    pub speaking: bool,
}

/// Tells speech from the room's background noise.
#[derive(Default)]
struct Detector {
    /// The quietest frame of each finished half second, newest last.
    block_floors: VecDeque<f32>,
    /// The quietest frame of the half second in progress.
    block_floor: f32,
    block_frames: usize,
}

impl Detector {
    fn is_speech(&mut self, frame: &[f32]) -> bool {
        let rms = (frame.iter().map(|s| s * s).sum::<f32>() / frame.len() as f32).sqrt();
        if self.block_frames == 0 || rms < self.block_floor {
            self.block_floor = rms;
        }
        self.block_frames += 1;
        let floor = self
            .block_floors
            .iter()
            .copied()
            .fold(self.block_floor, f32::min);
        if self.block_frames == FLOOR_BLOCK_FRAMES {
            if self.block_floors.len() == FLOOR_BLOCKS {
                self.block_floors.pop_front();
            }
            self.block_floors.push_back(self.block_floor);
            self.block_frames = 0;
        }
        rms > (floor * SPEECH_OVER_FLOOR).max(MIN_SPEECH_RMS)
    }
}

struct Utterance {
    /// Its audio so far, previewed as a live session.
    windows: LiveWindows,
    speech_frames: usize,
    /// Frames since the last speech.
    pause_frames: usize,
    /// Length of the audio at the last preview.
    previewed: usize,
}

/// The segmentation behind [`Conversation`], apart from the model.
#[derive(Default)]
struct Captions {
    detector: Detector,
    /// Audio short of a whole frame.
    frame: Vec<f32>,
    /// The latest audio while nobody speaks, kept for an utterance to start with.
    preroll: VecDeque<f32>,
    /// Speech frames in a row while nobody was speaking yet.
    onset: usize,
    current: Option<Utterance>,
    /// Utterances that ended and still need their final text.
    ended: VecDeque<LiveWindows>,
    /// Final text not yet handed out.
    ready: Vec<String>,
    partial: String,
}

impl Captions {
    fn push(&mut self, audio: &[f32]) {
        for &sample in audio {
            self.frame.push(sample);
            if self.frame.len() == FRAME_SAMPLES {
                let frame = std::mem::replace(&mut self.frame, Vec::with_capacity(FRAME_SAMPLES));
                self.add_frame(&frame);
            }
        }
    }

    fn add_frame(&mut self, frame: &[f32]) {
        let speech = self.detector.is_speech(frame);
        let Some(utterance) = &mut self.current else {
            self.keep_preroll(frame);
            self.onset = if speech { self.onset + 1 } else { 0 };
            if self.onset == ONSET_FRAMES {
                self.current = Some(Utterance {
                    windows: LiveWindows::starting_with(self.preroll.drain(..).collect()),
                    speech_frames: self.onset,
                    pause_frames: 0,
                    previewed: 0,
                });
                self.onset = 0;
                self.partial.clear();
            }
            return;
        };

        utterance.windows.tail.extend_from_slice(frame);
        if speech {
            utterance.speech_frames += 1;
            utterance.pause_frames = 0;
        } else {
            utterance.pause_frames += 1;
        }

        let len = utterance.windows.tail.len();
        if utterance.pause_frames == END_PAUSE_FRAMES {
            let pause = END_PAUSE_FRAMES * FRAME_SAMPLES;
            let rest = self.end_utterance(len - pause + KEPT_PAUSE_SAMPLES);
            self.keep_preroll(&rest);
        } else if let Some(cut) =
            chunk::full_window(&utterance.windows.tail, MAX_UTTERANCE_SECONDS * SAMPLE_RATE)
        {
            let pause_frames = utterance.pause_frames;
            let rest = self.end_utterance(cut);
            // Mid-speech: the rest is taken to be speech too.
            self.current = Some(Utterance {
                pause_frames: pause_frames.min(rest.len() / FRAME_SAMPLES),
                windows: LiveWindows::starting_with(rest),
                speech_frames: MIN_SPEECH_FRAMES,
                previewed: 0,
            });
        }
    }

    fn keep_preroll(&mut self, audio: &[f32]) {
        self.preroll.extend(audio);
        let excess = self.preroll.len().saturating_sub(PREROLL_SAMPLES + ONSET_FRAMES * FRAME_SAMPLES);
        self.preroll.drain(..excess);
    }

    /// Ends the current utterance at `cut` and returns its audio after that.
    fn end_utterance(&mut self, cut: usize) -> Vec<f32> {
        let mut utterance = self.current.take().expect("an utterance in progress");
        let rest = utterance.windows.tail.split_off(cut);
        if utterance.speech_frames >= MIN_SPEECH_FRAMES {
            self.ended.push_back(utterance.windows);
        }
        self.partial.clear();
        rest
    }

    fn step(&mut self, transcribe: &mut Transcribe) -> Result<CaptionUpdate, Box<dyn Error>> {
        while let Some(windows) = self.ended.front_mut() {
            let text = windows.finish(transcribe);
            // A failed utterance is dropped rather than failing every step after it.
            self.ended.pop_front();
            let text = text?;
            if !text.trim().is_empty() {
                self.ready.push(text.trim().to_string());
            }
        }
        if let Some(utterance) = &mut self.current {
            let len = utterance.windows.tail.len();
            if len >= utterance.previewed + PREVIEW_STEP_SAMPLES {
                utterance.previewed = len;
                self.partial = utterance.windows.preview(transcribe)?.trim().to_string();
            }
        }
        Ok(CaptionUpdate {
            finished: std::mem::take(&mut self.ready),
            partial: self.partial.clone(),
            speaking: self.current.is_some(),
        })
    }

    /// Ends the utterance in progress, as if a pause had followed it, and
    /// forgets the audio waiting for one to start.
    fn flush(&mut self, transcribe: &mut Transcribe) -> Result<CaptionUpdate, Box<dyn Error>> {
        if let Some(utterance) = &self.current {
            let len = utterance.windows.tail.len();
            self.end_utterance(len);
        }
        self.frame.clear();
        self.preroll.clear();
        self.onset = 0;
        self.step(transcribe)
    }
}

/// Captions for a conversation in one language: [`push`](Self::push) audio
/// as it arrives and call [`step`](Self::step) for what changed. A step
/// decodes only what is due: the final text of utterances that ended, and a
/// preview of the one in progress once it has grown by 200 ms.
pub struct Conversation<'a> {
    transcriber: &'a Transcriber,
    decode: Box<Decoder<'a>>,
    captions: Captions,
}

impl Transcriber {
    /// Starts captioning a conversation in `language`. Fails for a language
    /// the model does not support.
    pub fn conversation(&self, language: Option<&str>) -> Result<Conversation<'_>, Box<dyn Error>> {
        Ok(Conversation {
            transcriber: self,
            decode: self.decoder(language)?,
            captions: Captions::default(),
        })
    }
}

impl Conversation<'_> {
    /// Adds 16 kHz mono audio. Cheap: decoding waits for [`Self::step`].
    pub fn push(&mut self, audio: &[f32]) {
        self.captions.push(audio);
    }

    /// Decodes what is due and returns what changed. An utterance whose
    /// final text fails to decode is dropped, and the error returned.
    pub fn step(&mut self) -> Result<CaptionUpdate, Box<dyn Error>> {
        let (transcriber, decode) = (self.transcriber, &self.decode);
        self.captions
            .step(&mut |audio| decode(&transcriber.infer_logits(audio, preprocess::TARGET_SR)?))
    }

    /// Ends the utterance in progress, for when listening pauses or stops:
    /// its final text comes back in this update.
    pub fn flush(&mut self) -> Result<CaptionUpdate, Box<dyn Error>> {
        let (transcriber, decode) = (self.transcriber, &self.decode);
        self.captions
            .flush(&mut |audio| decode(&transcriber.infer_logits(audio, preprocess::TARGET_SR)?))
    }
}

fn update_json(result: Result<CaptionUpdate, Box<dyn Error>>) -> Result<String, Box<dyn Error>> {
    Ok(serde_json::to_string(&result?)?)
}

/// Starts captioning on `handle`, which must outlive the conversation.
/// Returns NULL for a language the model does not support.
#[no_mangle]
pub extern "C" fn conversation_create(
    handle: *mut Transcriber,
    language: *const libc::c_char,
) -> *mut Conversation<'static> {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let transcriber: &'static Transcriber = unsafe { &*handle };
    match transcriber.conversation(unsafe { crate::optional_str(language) }) {
        Ok(conversation) => Box::into_raw(Box::new(conversation)),
        Err(e) => {
            eprintln!("conversation_create failed: {e}");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn conversation_push(
    conversation: *mut Conversation<'static>,
    audio: *const f32,
    len: usize,
) {
    if conversation.is_null() || audio.is_null() {
        return;
    }
    unsafe { &mut *conversation }.push(unsafe { std::slice::from_raw_parts(audio, len) });
}

#[no_mangle]
pub extern "C" fn conversation_step(conversation: *mut Conversation<'static>) -> *mut libc::c_char {
    if conversation.is_null() {
        return std::ptr::null_mut();
    }
    crate::live::into_c_string(
        update_json(unsafe { &mut *conversation }.step()),
        "conversation_step",
    )
}

#[no_mangle]
pub extern "C" fn conversation_flush(conversation: *mut Conversation<'static>) -> *mut libc::c_char {
    if conversation.is_null() {
        return std::ptr::null_mut();
    }
    crate::live::into_c_string(
        update_json(unsafe { &mut *conversation }.flush()),
        "conversation_flush",
    )
}

/// Frees `conversation`. No call may still be running on it.
#[no_mangle]
pub extern "C" fn conversation_destroy(conversation: *mut Conversation<'static>) {
    if !conversation.is_null() {
        drop(unsafe { Box::from_raw(conversation) });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Noise at `level`, from a fixed seed.
    fn noise(seconds: f32, level: f32, seed: u32) -> Vec<f32> {
        let mut seed = seed;
        (0..(seconds * SAMPLE_RATE as f32) as usize)
            .map(|_| {
                seed = seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
                ((seed >> 8) as f32 / (1u32 << 23) as f32 - 1.0) * level
            })
            .collect()
    }

    /// Speech-like audio: loud, with 100 ms dips between words.
    fn speech(seconds: f32) -> Vec<f32> {
        let mut audio = noise(seconds, 0.3, 11);
        for (i, sample) in audio.iter_mut().enumerate() {
            if i % (SAMPLE_RATE * 6 / 10) < SAMPLE_RATE / 10 {
                *sample *= 0.01;
            }
        }
        audio
    }

    fn quiet(seconds: f32) -> Vec<f32> {
        noise(seconds, 0.001, 5)
    }

    /// Stands in for the model: names how long the audio was, in 10 ms.
    fn length(audio: &[f32]) -> Result<String, Box<dyn Error>> {
        Ok(format!("{}", audio.len() / (SAMPLE_RATE / 100)))
    }

    /// Feeds `audio` in 100 ms pieces, stepping after each, and returns
    /// every finished line.
    fn caption(captions: &mut Captions, audio: &[f32], heard: &mut Vec<usize>) -> Vec<String> {
        let mut lines = Vec::new();
        for part in audio.chunks(SAMPLE_RATE / 10) {
            captions.push(part);
            let update = captions
                .step(&mut |audio| {
                    heard.push(audio.len());
                    length(audio)
                })
                .unwrap();
            lines.extend(update.finished);
        }
        lines
    }

    #[test]
    fn silence_decodes_nothing() {
        let mut captions = Captions::default();
        let mut fail = |_: &[f32]| -> Result<String, Box<dyn Error>> { panic!("decoded") };
        for part in quiet(30.0).chunks(1_600) {
            captions.push(part);
            assert_eq!(captions.step(&mut fail).unwrap(), CaptionUpdate::default());
        }
        // A silent room is not speech, however long it lasts.
        let mut captions = Captions::default();
        captions.push(&vec![0.0; 10 * SAMPLE_RATE]);
        assert_eq!(captions.step(&mut fail).unwrap(), CaptionUpdate::default());
    }

    #[test]
    fn each_utterance_becomes_a_line() {
        let audio = [quiet(2.0), speech(2.0), quiet(1.5), speech(3.0), quiet(1.5)].concat();
        let mut captions = Captions::default();
        let mut heard = Vec::new();
        let lines = caption(&mut captions, &audio, &mut heard);
        assert_eq!(lines.len(), 2, "{lines:?}");

        // Each line heard its speech, a little before it and a little of the pause.
        let first: usize = lines[0].parse().unwrap();
        let second: usize = lines[1].parse().unwrap();
        assert!((220..=270).contains(&first), "{lines:?}");
        assert!((320..=370).contains(&second), "{lines:?}");
    }

    #[test]
    fn previews_follow_the_speech_and_clear_when_it_ends() {
        let audio = [quiet(1.0), speech(4.0)].concat();
        let mut captions = Captions::default();
        let mut partials = Vec::new();
        for part in audio.chunks(SAMPLE_RATE / 10) {
            captions.push(part);
            let update = captions.step(&mut length).unwrap();
            partials.push(update.partial.clone());
            assert!(update.speaking || update.partial.is_empty());
        }
        // Text shows within 1.5 s of speech starting, and grows with it.
        assert!(!partials[24].is_empty(), "{partials:?}");
        let last: usize = partials.last().unwrap().parse().unwrap();
        assert!(last > partials[24].parse().unwrap());

        captions.push(&quiet(1.0));
        let update = captions.step(&mut length).unwrap();
        assert_eq!(update.finished.len(), 1);
        assert_eq!(update.partial, "");
        assert!(!update.speaking);
    }

    #[test]
    fn previews_wait_for_new_speech() {
        let mut captions = Captions::default();
        captions.push(&[quiet(1.0), speech(2.0)].concat());
        let mut decodes = 0;
        let mut count = |audio: &[f32]| {
            decodes += 1;
            length(audio)
        };
        let first = captions.step(&mut count).unwrap();
        assert!(!first.partial.is_empty());
        // Too little new audio for another decode: the last text stands.
        captions.push(&speech(0.1));
        assert_eq!(captions.step(&mut count).unwrap(), first);
        assert_eq!(decodes, 1);
    }

    #[test]
    fn a_click_is_not_an_utterance() {
        let audio = [quiet(2.0), speech(0.1), quiet(2.0)].concat();
        let mut captions = Captions::default();
        let mut heard = Vec::new();
        assert!(caption(&mut captions, &audio, &mut heard).is_empty());
        assert!(heard.is_empty(), "{heard:?}");
    }

    #[test]
    fn long_speech_is_cut_into_bounded_lines_without_losing_audio() {
        let audio = [quiet(1.0), speech(50.0), quiet(1.5)].concat();
        let mut captions = Captions::default();
        let mut heard = Vec::new();
        let lines = caption(&mut captions, &audio, &mut heard);
        assert!(lines.len() >= 4, "{lines:?}");
        let lengths: Vec<usize> = lines.iter().map(|l| l.parse().unwrap()).collect();
        assert!(lengths.iter().all(|&l| l <= MAX_UTTERANCE_SECONDS * 100), "{lengths:?}");
        // Every bit of speech is in exactly one line: no gaps, no overlap.
        let total: usize = lengths.iter().sum();
        assert!((5_030..=5_070).contains(&total), "{lengths:?}");
        // Decodes never cover more than one utterance.
        assert!(heard.iter().all(|&len| len <= (MAX_UTTERANCE_SECONDS + 1) * SAMPLE_RATE));
    }

    #[test]
    fn a_steady_noisy_room_still_has_pauses() {
        // A fan at -34 dBFS under speech at -10 dBFS.
        let fan = |seconds| noise(seconds, 0.035, 3);
        let mix = |a: Vec<f32>, b: Vec<f32>| -> Vec<f32> { a.iter().zip(&b).map(|(x, y)| x + y).collect() };
        let audio = [
            fan(4.0),
            mix(speech(2.0), fan(2.0)),
            fan(1.5),
            mix(speech(2.0), fan(2.0)),
            fan(1.5),
        ]
        .concat();
        let mut captions = Captions::default();
        let mut heard = Vec::new();
        let lines = caption(&mut captions, &audio, &mut heard);
        assert_eq!(lines.len(), 2, "{lines:?}");
    }

    #[test]
    fn flush_ends_the_utterance_in_progress() {
        let mut captions = Captions::default();
        captions.push(&[quiet(1.0), speech(2.0)].concat());
        let update = captions.flush(&mut length).unwrap();
        assert_eq!(update.finished.len(), 1);
        assert_eq!(update.partial, "");
        assert!(!update.speaking);
        assert_eq!(captions.flush(&mut length).unwrap(), CaptionUpdate::default());
    }

    #[test]
    fn empty_text_is_not_a_line() {
        let mut captions = Captions::default();
        captions.push(&[quiet(1.0), speech(2.0), quiet(1.0)].concat());
        let mut nothing = |_: &[f32]| -> Result<String, Box<dyn Error>> { Ok(" ".into()) };
        assert_eq!(captions.step(&mut nothing).unwrap(), CaptionUpdate::default());
    }

    #[test]
    fn a_failed_utterance_is_dropped_and_the_next_still_captioned() {
        let mut captions = Captions::default();
        captions.push(&[quiet(1.0), speech(2.0), quiet(1.0)].concat());
        let mut fail = |_: &[f32]| -> Result<String, Box<dyn Error>> { Err("no".into()) };
        assert!(captions.step(&mut fail).is_err());
        assert_eq!(captions.step(&mut length).unwrap(), CaptionUpdate::default());

        captions.push(&[speech(2.0), quiet(1.0)].concat());
        assert_eq!(captions.step(&mut length).unwrap().finished.len(), 1);
    }

    #[test]
    fn updates_serialize_for_the_apps() {
        let update = CaptionUpdate {
            finished: vec!["నమస్తే".into()],
            partial: "a \"b\"".into(),
            speaking: true,
        };
        assert_eq!(
            update_json(Ok(update)).unwrap(),
            r#"{"finished":["నమస్తే"],"partial":"a \"b\"","speaking":true}"#
        );
    }
}
