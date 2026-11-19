//! Long-audio windowing, ported from the web worker
//! (apps/web/src/lib/transcription/features.ts) so every app splits and
//! stitches identically.
//!
//! The encoder attends over the whole input, so memory grows with the square
//! of its length (a 4-minute clip peaks near 3.7 GB with the multilingual
//! model), and the exported relative positional encoding stops at 5000
//! encoder frames (~6 min 40 s). Bounded windows keep both in check.

pub(crate) const SAMPLE_RATE: usize = 16_000;

/// Longest window passed to the encoder in one run.
pub(crate) const WINDOW_SECONDS: usize = 25;

/// How far back from a window's end to look for a quiet cut point.
const SEARCH_SAMPLES: usize = 3 * SAMPLE_RATE;
/// Energy is compared over 100 ms blocks.
const BLOCK_SAMPLES: usize = SAMPLE_RATE / 10;
/// Neighbouring windows share 500 ms so a word cut at the boundary is heard whole.
pub(crate) const OVERLAP_SAMPLES: usize = SAMPLE_RATE / 2;
/// Fast speech stays under 6 words a second.
const MAX_WORDS_PER_SECOND: usize = 6;
/// Words compared when de-duplicating text the overlap produced twice: no more
/// than the overlap can hold, so a phrase the speaker really repeated just
/// after the overlap survives.
const MAX_JOIN_WORDS: usize = OVERLAP_SAMPLES * MAX_WORDS_PER_SECOND / SAMPLE_RATE;

/// Splits 16 kHz audio into windows of at most `seconds`, cutting at the
/// quietest 100 ms block of each window's last 3 s. Short clips stay whole.
pub(crate) fn split_audio(audio: &[f32], seconds: usize) -> Vec<&[f32]> {
    let max = seconds * SAMPLE_RATE;
    let mut parts = Vec::new();
    let mut start = 0;
    while let Some(end) = full_window(&audio[start..], max) {
        parts.push(&audio[start..start + end]);
        start += end - OVERLAP_SAMPLES;
    }
    parts.push(&audio[start..]);
    parts
}

/// Length of the window at the front of `audio` once the audio runs past
/// `max` samples: `max`, pulled back to the quietest 100 ms block of the
/// window's last 3 s. `None` while everything still fits in one window. Only
/// the first `max` samples are read, so audio that is still growing gets the
/// same cut it would get once complete.
pub(crate) fn full_window(audio: &[f32], max: usize) -> Option<usize> {
    if audio.len() <= max {
        return None;
    }
    let mut end = max;
    let mut lowest = f32::INFINITY;
    for candidate in (max - SEARCH_SAMPLES..max).step_by(BLOCK_SAMPLES) {
        let energy: f32 = audio[candidate..candidate + BLOCK_SAMPLES]
            .iter()
            .map(|s| s * s)
            .sum();
        if energy < lowest {
            lowest = energy;
            end = candidate + BLOCK_SAMPLES / 2;
        }
    }
    Some(end)
}

/// Digital silence: normalizing its features would only amplify noise.
pub(crate) fn is_silent(window: &[f32]) -> bool {
    let energy: f32 = window.iter().map(|s| s * s).sum();
    energy / (window.len() as f32) < 1e-9
}

/// Appends `next` to `previous`, dropping the words the shared overlap
/// produced at the end of one window and the start of the next.
pub(crate) fn join_transcripts(previous: &str, next: &str) -> String {
    if previous.is_empty() {
        return next.to_string();
    }
    if next.is_empty() {
        return previous.to_string();
    }
    let left: Vec<&str> = previous.split_whitespace().collect();
    let right: Vec<&str> = next.split_whitespace().collect();
    let longest = MAX_JOIN_WORDS.min(left.len()).min(right.len());
    for overlap in (1..=longest).rev() {
        if left[left.len() - overlap..] == right[..overlap] {
            return left
                .iter()
                .chain(&right[overlap..])
                .copied()
                .collect::<Vec<_>>()
                .join(" ");
        }
    }
    format!("{previous} {next}")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn offset(audio: &[f32], part: &[f32]) -> usize {
        (part.as_ptr() as usize - audio.as_ptr() as usize) / std::mem::size_of::<f32>()
    }

    #[test]
    fn short_audio_is_one_window() {
        let audio = vec![0.1; WINDOW_SECONDS * SAMPLE_RATE];
        let parts = split_audio(&audio, WINDOW_SECONDS);
        assert_eq!(parts.len(), 1);
        assert_eq!(parts[0].len(), audio.len());
    }

    #[test]
    fn long_audio_windows_are_bounded_overlapping_and_complete() {
        // 8 minutes of a constant tone: no quiet blocks to prefer.
        let audio: Vec<f32> = (0..8 * 60 * SAMPLE_RATE)
            .map(|i| (i as f32 * 0.05).sin())
            .collect();
        let parts = split_audio(&audio, WINDOW_SECONDS);
        assert!(parts.len() > 1);

        let mut expected_start = 0;
        for part in &parts {
            assert!(part.len() <= WINDOW_SECONDS * SAMPLE_RATE);
            assert_eq!(offset(&audio, part), expected_start);
            expected_start += part.len() - OVERLAP_SAMPLES;
        }
        let last = parts.last().unwrap();
        assert_eq!(offset(&audio, last) + last.len(), audio.len());
    }

    #[test]
    fn cut_lands_in_the_quiet_gap() {
        let rate = SAMPLE_RATE;
        let mut audio = vec![0.5f32; 40 * rate];
        // A 300 ms pause at 23.0 s, inside the last 3 s of the first window.
        audio[23 * rate..23 * rate + 3 * rate / 10].fill(0.0);
        let parts = split_audio(&audio, WINDOW_SECONDS);
        let end = parts[0].len();
        assert!(
            end > 23 * rate && end < 23 * rate + 3 * rate / 10,
            "cut at {end}"
        );
    }

    #[test]
    fn silence_detection() {
        assert!(is_silent(&[0.0; 1600]));
        assert!(!is_silent(&[0.01; 1600]));
    }

    #[test]
    fn joins_drop_overlapping_words() {
        assert_eq!(join_transcripts("", "a b"), "a b");
        assert_eq!(join_transcripts("a b", ""), "a b");
        assert_eq!(join_transcripts("a b c", "b c d"), "a b c d");
        assert_eq!(join_transcripts("a b c", "c d"), "a b c d");
        assert_eq!(join_transcripts("a b", "c d"), "a b c d");
    }

    #[test]
    fn joins_keep_phrases_repeated_past_the_overlap() {
        assert_eq!(MAX_JOIN_WORDS, 3);
        assert_eq!(
            join_transcripts("so thank you very much", "thank you very much again"),
            "so thank you very much thank you very much again"
        );
    }
}
