//! Live transcription: text for a recording while it is still being made.
//!
//! The models are full-context, not streaming, so the audio so far is decoded
//! again as it grows; an encoder pass costs a few percent of the audio's
//! duration. Two things make those previews usable:
//!
//! - An utterance that stops mid-word derails the whole decode, not just its
//!   last word. A preview therefore fades its cut end into 300 ms of faint
//!   noise, so the model hears an utterance that ended.
//! - A preview decodes only the last few seconds afresh: older audio is
//!   decoded once, in sections cut at pauses, and its text kept.
//!
//! The final text does not come from previews. Windows the recording has
//! outgrown are transcribed exactly as [`Transcriber::transcribe`] cuts them,
//! so [`LiveSession::finish`] returns what transcribing the whole recording
//! would, with all but the last window already done.

use std::error::Error;

use crate::chunk::{self, OVERLAP_SAMPLES, SAMPLE_RATE, WINDOW_SECONDS};
use crate::{preprocess, Decoder, Transcriber};

/// Audio a preview decodes afresh before the older part of it is set aside.
const PREVIEW_SECONDS: usize = 8;
/// Less new audio than this decodes to noise more often than to a word.
const MIN_PREVIEW_SAMPLES: usize = SAMPLE_RATE;
/// A pause between words; the gap inside a stop consonant is shorter.
const PAUSE_SAMPLES: usize = 3 * SAMPLE_RATE / 10;
/// Pauses are looked for every 50 ms.
const PAUSE_STEP: usize = SAMPLE_RATE / 20;
/// A preview's cut end fades out over 20 ms...
const FADE_SAMPLES: usize = SAMPLE_RATE / 50;
/// ...into 300 ms of noise at about -50 dBFS, a quiet room.
const PAD_SAMPLES: usize = 3 * SAMPLE_RATE / 10;
const PAD_LEVEL: f32 = 3e-3;

type Transcribe<'a> = dyn FnMut(&[f32]) -> Result<String, Box<dyn Error>> + 'a;

/// The windowing behind [`LiveSession`], apart from the model.
#[derive(Default)]
struct LiveWindows {
    /// 16 kHz audio from the start of the window still open.
    tail: Vec<f32>,
    /// Transcript of the windows the recording has outgrown.
    settled: String,
    /// Samples at the front of `tail` that the last settled window also heard.
    carried: usize,
    /// Preview text of `tail[..previewed]`, decoded once.
    preview: String,
    previewed: usize,
}

impl LiveWindows {
    /// Transcribes and drops every window the audio has outgrown.
    fn settle(&mut self, transcribe: &mut Transcribe) -> Result<(), Box<dyn Error>> {
        while let Some(end) = chunk::full_window(&self.tail, WINDOW_SECONDS * SAMPLE_RATE) {
            let window = &self.tail[..end];
            if !chunk::is_silent(window) {
                self.settled = chunk::join_transcripts(&self.settled, &transcribe(window)?);
            }
            self.tail.drain(..end - OVERLAP_SAMPLES);
            self.carried = OVERLAP_SAMPLES;
            self.preview.clear();
            self.previewed = 0;
        }
        Ok(())
    }

    fn preview(&mut self, transcribe: &mut Transcribe) -> Result<String, Box<dyn Error>> {
        self.settle(transcribe)?;
        while self.tail.len() - self.previewed > PREVIEW_SECONDS * SAMPLE_RATE {
            let rest = &self.tail[self.previewed..];
            let section = &rest[..quietest_pause(rest)];
            self.preview = append(&self.preview, &preview_text(section, transcribe)?);
            self.previewed += section.len();
        }

        let fresh = &self.tail[self.previewed..];
        // Audio the last window already transcribed is not new.
        let heard = if self.previewed == 0 { self.carried } else { 0 };
        let mut text = self.preview.clone();
        if fresh.len() >= heard + MIN_PREVIEW_SAMPLES {
            text = append(&text, &preview_text(fresh, transcribe)?);
        }
        Ok(chunk::join_transcripts(&self.settled, &text))
    }

    fn finish(&mut self, transcribe: &mut Transcribe) -> Result<String, Box<dyn Error>> {
        self.settle(transcribe)?;
        if self.tail.is_empty() || chunk::is_silent(&self.tail) {
            return Ok(self.settled.clone());
        }
        Ok(chunk::join_transcripts(
            &self.settled,
            &transcribe(&self.tail)?,
        ))
    }
}

fn preview_text(audio: &[f32], transcribe: &mut Transcribe) -> Result<String, Box<dyn Error>> {
    if chunk::is_silent(audio) {
        return Ok(String::new());
    }
    transcribe(&ended(audio))
}

/// Where a preview sets older audio aside: the middle of the quietest 300 ms
/// of `audio`, leaving enough on either side to decode. `audio` must be
/// longer than [`PREVIEW_SECONDS`].
fn quietest_pause(audio: &[f32]) -> usize {
    let energy = |from: usize| -> f32 {
        audio[from..from + PAUSE_SAMPLES]
            .iter()
            .map(|s| s * s)
            .sum()
    };
    let last = audio.len() - MIN_PREVIEW_SAMPLES - PAUSE_SAMPLES;
    let mut quietest = MIN_PREVIEW_SAMPLES;
    let mut lowest = f32::INFINITY;
    for from in (MIN_PREVIEW_SAMPLES..=last).step_by(PAUSE_STEP) {
        let energy = energy(from);
        if energy < lowest {
            lowest = energy;
            quietest = from;
        }
    }
    quietest + PAUSE_SAMPLES / 2
}

/// Preview sections share no audio, so nothing is de-duplicated between them.
fn append(text: &str, more: &str) -> String {
    if text.is_empty() || more.is_empty() {
        format!("{text}{more}")
    } else {
        format!("{text} {more}")
    }
}

/// `audio` with its cut end faded into faint noise.
fn ended(audio: &[f32]) -> Vec<f32> {
    let mut out = Vec::with_capacity(audio.len() + PAD_SAMPLES);
    out.extend_from_slice(audio);
    let fade = FADE_SAMPLES.min(out.len());
    let fade_start = out.len() - fade;
    for (i, sample) in out[fade_start..].iter_mut().enumerate() {
        *sample *= 1.0 - (i + 1) as f32 / fade as f32;
    }
    // A fixed seed: the same audio always previews the same way.
    let mut seed = 12345u32;
    out.extend((0..PAD_SAMPLES).map(|_| {
        seed = seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
        ((seed >> 8) as f32 / (1u32 << 23) as f32 - 1.0) * PAD_LEVEL
    }));
    out
}

/// Live transcription of one recording: [`push`](Self::push) audio as it
/// arrives, [`preview`](Self::preview) for text to show meanwhile, and
/// [`finish`](Self::finish) for the transcript once the recording stops.
pub struct LiveSession<'a> {
    transcriber: &'a Transcriber,
    decode: Box<Decoder<'a>>,
    windows: LiveWindows,
}

impl Transcriber {
    /// Starts live transcription of a recording in `language`. Fails for a
    /// language the model does not support.
    pub fn live(&self, language: Option<&str>) -> Result<LiveSession<'_>, Box<dyn Error>> {
        Ok(LiveSession {
            transcriber: self,
            decode: self.decoder(language)?,
            windows: LiveWindows::default(),
        })
    }
}

impl LiveSession<'_> {
    /// Adds 16 kHz mono audio to the end of the recording.
    pub fn push(&mut self, audio: &[f32]) {
        self.windows.tail.extend_from_slice(audio);
    }

    /// The recording so far as text. Its last words are provisional: they
    /// can change as more audio arrives, and again in [`Self::finish`].
    pub fn preview(&mut self) -> Result<String, Box<dyn Error>> {
        let (transcriber, decode) = (self.transcriber, &self.decode);
        self.windows
            .preview(&mut |audio| decode(&transcriber.infer_logits(audio, preprocess::TARGET_SR)?))
    }

    /// The transcript of everything pushed: the text
    /// [`Transcriber::transcribe`] returns for the same audio. More audio
    /// can still be pushed afterwards.
    pub fn finish(&mut self) -> Result<String, Box<dyn Error>> {
        let (transcriber, decode) = (self.transcriber, &self.decode);
        self.windows
            .finish(&mut |audio| decode(&transcriber.infer_logits(audio, preprocess::TARGET_SR)?))
    }
}

fn into_c_string(result: Result<String, Box<dyn Error>>, call: &str) -> *mut libc::c_char {
    match result {
        Ok(text) => std::ffi::CString::new(text).unwrap().into_raw(),
        Err(e) => {
            eprintln!("{call} failed: {e}");
            std::ptr::null_mut()
        }
    }
}

/// Starts a live session on `handle`, which must outlive it. Returns NULL
/// for a language the model does not support.
#[no_mangle]
pub extern "C" fn live_session_create(
    handle: *mut Transcriber,
    language: *const libc::c_char,
) -> *mut LiveSession<'static> {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let transcriber: &'static Transcriber = unsafe { &*handle };
    match transcriber.live(unsafe { crate::optional_str(language) }) {
        Ok(session) => Box::into_raw(Box::new(session)),
        Err(e) => {
            eprintln!("live_session_create failed: {e}");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn live_session_push(
    session: *mut LiveSession<'static>,
    audio: *const f32,
    len: usize,
) {
    if session.is_null() || audio.is_null() {
        return;
    }
    unsafe { &mut *session }.push(unsafe { std::slice::from_raw_parts(audio, len) });
}

#[no_mangle]
pub extern "C" fn live_session_preview(session: *mut LiveSession<'static>) -> *mut libc::c_char {
    if session.is_null() {
        return std::ptr::null_mut();
    }
    into_c_string(unsafe { &mut *session }.preview(), "live_session_preview")
}

#[no_mangle]
pub extern "C" fn live_session_finish(session: *mut LiveSession<'static>) -> *mut libc::c_char {
    if session.is_null() {
        return std::ptr::null_mut();
    }
    into_c_string(unsafe { &mut *session }.finish(), "live_session_finish")
}

/// Frees `session`. No call may still be running on it.
#[no_mangle]
pub extern "C" fn live_session_destroy(session: *mut LiveSession<'static>) {
    if !session.is_null() {
        drop(unsafe { Box::from_raw(session) });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Speech-like test audio: noise bursts with a 200 ms gap every 2.3 s.
    fn recording(seconds: usize) -> Vec<f32> {
        let mut seed = 7u32;
        (0..seconds * SAMPLE_RATE)
            .map(|i| {
                seed = seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
                let noise = (seed >> 8) as f32 / (1u32 << 23) as f32 - 1.0;
                let in_gap = i % (23 * SAMPLE_RATE / 10) < SAMPLE_RATE / 5;
                noise * if in_gap { 0.001 } else { 0.3 }
            })
            .collect()
    }

    /// Stands in for the model: one word naming the audio it was given.
    fn name(audio: &[f32]) -> Result<String, Box<dyn Error>> {
        let sum: f32 = audio.iter().step_by(97).sum();
        Ok(format!("{}:{}", audio.len(), sum.to_bits()))
    }

    fn batch(audio: &[f32]) -> String {
        chunk::split_audio(audio, WINDOW_SECONDS)
            .into_iter()
            .fold(String::new(), |text, window| {
                chunk::join_transcripts(&text, &name(window).unwrap())
            })
    }

    #[test]
    fn finish_matches_batch_however_the_audio_arrives() {
        let audio = recording(70);
        for chunk_len in [audio.len(), 1_000, 16_000 * 9 + 1] {
            let mut windows = LiveWindows::default();
            for (index, part) in audio.chunks(chunk_len).enumerate() {
                windows.tail.extend_from_slice(part);
                if index % 3 == 0 {
                    windows.preview(&mut name).unwrap();
                }
            }
            assert_eq!(windows.finish(&mut name).unwrap(), batch(&audio));
        }
    }

    #[test]
    fn finish_can_follow_more_audio() {
        let audio = recording(40);
        let mut windows = LiveWindows::default();
        windows.tail.extend_from_slice(&audio[..30 * SAMPLE_RATE]);
        assert_eq!(
            windows.finish(&mut name).unwrap(),
            batch(&audio[..30 * SAMPLE_RATE])
        );
        windows.tail.extend_from_slice(&audio[30 * SAMPLE_RATE..]);
        assert_eq!(windows.finish(&mut name).unwrap(), batch(&audio));
    }

    #[test]
    fn previews_pad_their_end_and_the_final_text_does_not() {
        let audio = recording(3);
        let mut heard = Vec::new();
        let mut record = |audio: &[f32]| {
            heard.push(audio.len());
            name(audio)
        };
        let mut windows = LiveWindows::default();
        windows.tail.extend_from_slice(&audio);
        windows.preview(&mut record).unwrap();
        windows.finish(&mut record).unwrap();
        assert_eq!(heard, vec![audio.len() + PAD_SAMPLES, audio.len()]);
    }

    #[test]
    fn a_preview_decodes_older_audio_only_once() {
        let audio = recording(24);
        let mut heard = Vec::new();
        let mut record = |audio: &[f32]| {
            heard.push(audio.len());
            name(audio)
        };
        let mut windows = LiveWindows::default();
        for end in (SAMPLE_RATE..=audio.len()).step_by(SAMPLE_RATE / 4) {
            let known = windows.tail.len();
            windows.tail.extend_from_slice(&audio[known..end]);
            windows.preview(&mut record).unwrap();
        }
        // Each decode covers the audio since a pause, never the whole recording.
        let longest = PREVIEW_SECONDS * SAMPLE_RATE + SAMPLE_RATE / 4 + PAD_SAMPLES;
        assert!(heard.iter().all(|len| *len <= longest), "{heard:?}");
        assert!(windows.previewed > 12 * SAMPLE_RATE);

        // What was set aside keeps its text.
        let sections = windows.preview.clone();
        assert!(!sections.is_empty());
        let preview = windows.preview(&mut name).unwrap();
        assert!(preview.starts_with(&sections));
    }

    #[test]
    fn older_audio_is_set_aside_at_a_pause() {
        let mut audio = vec![0.5f32; 9 * SAMPLE_RATE];
        // A 400 ms pause at 3.0 s, and a 100 ms gap at 6.0 s that is not one.
        audio[3 * SAMPLE_RATE..3 * SAMPLE_RATE + 4 * SAMPLE_RATE / 10].fill(0.0);
        audio[6 * SAMPLE_RATE..6 * SAMPLE_RATE + SAMPLE_RATE / 10].fill(0.0);
        let cut = quietest_pause(&audio);
        assert!(
            cut > 3 * SAMPLE_RATE && cut < 3 * SAMPLE_RATE + 4 * SAMPLE_RATE / 10,
            "cut at {cut}"
        );

        // Without a pause, the cut still leaves both sides enough to decode.
        let steady = vec![0.5f32; 9 * SAMPLE_RATE];
        let cut = quietest_pause(&steady);
        assert!(cut >= MIN_PREVIEW_SAMPLES && steady.len() - cut >= MIN_PREVIEW_SAMPLES);
    }

    #[test]
    fn previews_wait_for_enough_new_audio() {
        let mut fail = |_: &[f32]| -> Result<String, Box<dyn Error>> { panic!("decoded") };
        let mut windows = LiveWindows::default();
        assert_eq!(windows.preview(&mut fail).unwrap(), "");
        assert_eq!(windows.finish(&mut fail).unwrap(), "");

        windows
            .tail
            .extend_from_slice(&recording(1)[..MIN_PREVIEW_SAMPLES - 1]);
        assert_eq!(windows.preview(&mut fail).unwrap(), "");

        // Past a settled window, the audio it shares with the next is not new.
        let mut windows = LiveWindows::default();
        windows.tail.extend_from_slice(&recording(26));
        windows.settle(&mut name).unwrap();
        let settled = windows.settled.clone();
        assert!(!settled.is_empty());
        windows
            .tail
            .truncate(OVERLAP_SAMPLES + MIN_PREVIEW_SAMPLES - 1);
        assert_eq!(windows.preview(&mut fail).unwrap(), settled);
    }

    #[test]
    fn silence_is_not_decoded() {
        let mut windows = LiveWindows::default();
        windows.tail.resize(2 * SAMPLE_RATE, 0.0);
        let mut fail = |_: &[f32]| -> Result<String, Box<dyn Error>> { panic!("decoded silence") };
        assert_eq!(windows.preview(&mut fail).unwrap(), "");
        assert_eq!(windows.finish(&mut fail).unwrap(), "");
    }

    #[test]
    fn a_failed_window_is_retried() {
        let audio = recording(30);
        let mut windows = LiveWindows::default();
        windows.tail.extend_from_slice(&audio);
        let mut fail = |_: &[f32]| -> Result<String, Box<dyn Error>> { Err("no".into()) };
        assert!(windows.preview(&mut fail).is_err());
        assert_eq!(windows.finish(&mut name).unwrap(), batch(&audio));
    }

    #[test]
    fn ended_audio_fades_into_faint_noise() {
        let out = ended(&[1.0; SAMPLE_RATE]);
        assert_eq!(out.len(), SAMPLE_RATE + PAD_SAMPLES);
        assert_eq!(out[SAMPLE_RATE - FADE_SAMPLES - 1], 1.0);
        assert_eq!(out[SAMPLE_RATE - 1], 0.0);
        let pad = &out[SAMPLE_RATE..];
        assert!(pad.iter().all(|s| s.abs() <= PAD_LEVEL));
        assert!(!chunk::is_silent(pad));
        // Shorter than the fade: still well-formed.
        assert_eq!(ended(&[1.0; 3]).len(), 3 + PAD_SAMPLES);
        assert_eq!(ended(&[]).len(), PAD_SAMPLES);
    }
}
