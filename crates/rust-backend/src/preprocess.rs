use std::error::Error;

use ndarray::{Array1, Array2};
use rubato::{
    Resampler, SincFixedIn, SincInterpolationParameters, SincInterpolationType, WindowFunction,
};
use rustfft::{num_complex::Complex, FftPlanner};

const TARGET_SR: u32 = 16000;
const NUM_MEL_BINS: usize = 80;
const WINDOW_LENGTH: usize = 320;
const HOP_LENGTH: usize = 160;
const FFT_LENGTH: usize = 512;
const PREEMPHASIS: f32 = 0.97;
const LOG_GUARD: f32 = 1e-9;

pub fn read_wav(path: &str) -> Result<(Vec<f32>, u32), Box<dyn Error>> {
    let mut reader = hound::WavReader::open(path)?;
    let spec = reader.spec();
    let sample_rate = spec.sample_rate;
    let channels = spec.channels as usize;

    let samples: Vec<f32> = match spec.sample_format {
        hound::SampleFormat::Float => reader.samples::<f32>().map(|s| s.unwrap_or(0.0)).collect(),
        hound::SampleFormat::Int => {
            let bits = spec.bits_per_sample;
            let max_val = (1u64 << (bits - 1)) as f64;
            reader
                .samples::<i32>()
                .map(|s| (s.unwrap_or(0) as f64 / max_val) as f32)
                .collect()
        }
    };

    let audio = if channels > 1 {
        samples
            .chunks(channels)
            .map(|ch| ch.iter().sum::<f32>() / channels as f32)
            .collect()
    } else {
        samples
    };

    Ok((audio, sample_rate))
}

fn resample(input: &[f32], orig_sr: u32, target_sr: u32) -> Vec<f32> {
    if orig_sr == target_sr {
        return input.to_vec();
    }
    let params = SincInterpolationParameters {
        sinc_len: 256,
        f_cutoff: 0.95,
        interpolation: SincInterpolationType::Linear,
        oversampling_factor: 256,
        window: WindowFunction::BlackmanHarris2,
    };
    let mut resampler = SincFixedIn::<f32>::new(
        target_sr as f64 / orig_sr as f64,
        1.0,
        params,
        input.len(),
        1,
    )
    .expect("Failed to create resampler");
    let waves_in = vec![input.to_vec()];
    let waves_out = resampler
        .process(&waves_in, None)
        .expect("Resampling failed");
    waves_out.into_iter().next().unwrap_or_default()
}

fn apply_preemphasis(audio: &[f32], coeff: f32) -> Vec<f32> {
    let mut out = Vec::with_capacity(audio.len());
    if audio.is_empty() {
        return out;
    }
    out.push(audio[0]);
    for i in 1..audio.len() {
        out.push(audio[i] - coeff * audio[i - 1]);
    }
    out
}

fn hann_window(len: usize) -> Vec<f32> {
    (0..len)
        .map(|n| 0.5 * (1.0 - (2.0 * std::f32::consts::PI * n as f32 / (len - 1) as f32).cos()))
        .collect()
}

fn hz_to_mel_slaney(hz: f32) -> f32 {
    if hz < 1000.0 {
        3.0 * hz / 200.0
    } else {
        15.0 + 27.0 * (hz / 1000.0).ln() / 6.4f32.ln()
    }
}

fn mel_to_hz_slaney(mel: f32) -> f32 {
    if mel < 15.0 {
        200.0 * mel / 3.0
    } else {
        1000.0 * ((mel - 15.0) * 6.4f32.ln() / 27.0).exp()
    }
}

fn create_mel_filterbank(
    sr: u32,
    n_fft: usize,
    n_mels: usize,
    f_min: f32,
    f_max: f32,
) -> Array2<f32> {
    let n_freqs = n_fft / 2 + 1;
    let mel_min = hz_to_mel_slaney(f_min);
    let mel_max = hz_to_mel_slaney(f_max);

    let mel_points: Vec<f32> = (0..n_mels + 2)
        .map(|i| mel_min + (mel_max - mel_min) * i as f32 / (n_mels + 1) as f32)
        .collect();

    let hz_points: Vec<f32> = mel_points.into_iter().map(mel_to_hz_slaney).collect();

    let fft_freqs: Vec<f32> = (0..n_freqs)
        .map(|i| i as f32 * sr as f32 / n_fft as f32)
        .collect();

    let mut filterbank = Array2::<f32>::zeros((n_mels, n_freqs));

    for i in 0..n_mels {
        let left = hz_points[i];
        let center = hz_points[i + 1];
        let right = hz_points[i + 2];

        for (j, &freq) in fft_freqs.iter().enumerate() {
            if freq >= left && freq <= center {
                if (center - left).abs() > f32::EPSILON {
                    filterbank[[i, j]] = (freq - left) / (center - left);
                }
            } else if freq > center && freq <= right {
                if (right - center).abs() > f32::EPSILON {
                    filterbank[[i, j]] = (right - freq) / (right - center);
                }
            }
        }
    }

    for i in 0..n_mels {
        let width = hz_points[i + 2] - hz_points[i];
        if width > 0.0 {
            let enorm = 2.0 / width;
            filterbank.row_mut(i).map_inplace(|v| *v *= enorm);
        }
    }

    filterbank
}

fn compute_stft_power(
    audio: &[f32],
    n_fft: usize,
    hop_length: usize,
    window: &[f32],
) -> Array2<f32> {
    let win_length = window.len();
    let pad = win_length / 2;
    let padded: Vec<f32> = std::iter::repeat(0.0)
        .take(pad)
        .chain(audio.iter().copied())
        .chain(std::iter::repeat(0.0).take(pad))
        .collect();

    let n_frames = if padded.len() < win_length {
        0
    } else {
        (padded.len() - win_length) / hop_length + 1
    };

    let n_freqs = n_fft / 2 + 1;
    let mut power = Array2::<f32>::zeros((n_freqs, n_frames));

    let mut planner = FftPlanner::new();
    let fft = planner.plan_fft_forward(n_fft);

    for frame_idx in 0..n_frames {
        let start = frame_idx * hop_length;
        let mut buffer: Vec<Complex<f32>> = (0..n_fft)
            .map(|i| {
                if i < win_length {
                    Complex::new(padded[start + i] * window[i], 0.0)
                } else {
                    Complex::new(0.0, 0.0)
                }
            })
            .collect();

        fft.process(&mut buffer);

        for k in 0..n_freqs {
            power[[k, frame_idx]] = buffer[k].norm_sqr();
        }
    }

    power
}

pub(crate) fn normalize_per_feature(features: &mut Array2<f32>) {
    for mut row in features.rows_mut() {
        let mean = row.mean().unwrap_or(0.0);
        let variance =
            row.iter().map(|v| (v - mean).powi(2)).sum::<f32>() / (row.len() as f32 - 1.0);
        let std = variance.sqrt().max(f32::EPSILON);
        row.map_inplace(|v| *v = (*v - mean) / std);
    }
}

pub fn compute_features(
    audio: &[f32],
    sample_rate: u32,
) -> Result<(Array2<f32>, Array1<i64>), Box<dyn Error>> {
    let audio_resampled = resample(audio, sample_rate, TARGET_SR);

    let audio_preemphasized = apply_preemphasis(&audio_resampled, PREEMPHASIS);

    let window = hann_window(WINDOW_LENGTH);

    let power_spec = compute_stft_power(&audio_preemphasized, FFT_LENGTH, HOP_LENGTH, &window);

    let filterbank = create_mel_filterbank(TARGET_SR, FFT_LENGTH, NUM_MEL_BINS, 0.0, 8000.0);

    let mut mel_spec = filterbank.dot(&power_spec);

    mel_spec.mapv_inplace(|v| (v + LOG_GUARD).ln());

    let n_frames = mel_spec.len() / NUM_MEL_BINS;
    let length = Array1::from_vec(vec![n_frames as i64]);

    normalize_per_feature(&mut mel_spec);

    Ok((mel_spec, length))
}
