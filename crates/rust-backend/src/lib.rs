mod decode;
pub mod preprocess;

use std::collections::HashMap;
use std::error::Error;
use std::fs::File;
use std::io::BufReader;
use std::sync::Mutex;

use ndarray::{Array2, Axis, Ix3};
use ort::session::Session;
use ort::value::TensorRef;
use serde::Deserialize;

/// Per-language logit masks ship either as boolean flags over the full class
/// dimension or as explicit class indices; both normalize to indices.
#[derive(Deserialize)]
#[serde(untagged)]
enum MaskEntry {
    Indices(Vec<i64>),
    Flags(Vec<bool>),
}

enum Backend {
    Single {
        blank_id: i64,
        label_map: Vec<String>,
    },
    Multilingual {
        blank_id: i64,
        vocab: HashMap<String, Vec<String>>,
        masks: HashMap<String, Vec<usize>>,
        default_language: String,
    },
}

pub struct Transcriber {
    session: Mutex<Session>,
    backend: Backend,
}

fn read_blank_id(decoder_config_path: &str) -> Result<i64, Box<dyn Error>> {
    let config_content = std::fs::read_to_string(decoder_config_path)?;
    let config: serde_json::Value = serde_json::from_str(&config_content)?;
    Ok(config["blank_idx"].as_i64().unwrap_or(5632))
}

impl Transcriber {
    /// Single-language IndicConformer CTC model (e.g. the Telugu export).
    pub fn new(
        model_path: &str,
        decoder_config_path: &str,
        vocab_path: &str,
    ) -> Result<Self, Box<dyn Error>> {
        let session = Session::builder()?.commit_from_file(model_path)?;
        let blank_id = read_blank_id(decoder_config_path)?;
        let label_map = decode::load_vocab(vocab_path)?;

        Ok(Transcriber {
            session: Mutex::new(session),
            backend: Backend::Single {
                blank_id,
                label_map,
            },
        })
    }

    /// Multilingual IndicConformer CTC model: one shared CTC head where each
    /// language owns a slice of the output classes, selected per utterance
    /// via `language_masks.json` + `vocab.json`.
    pub fn new_multilingual(
        model_path: &str,
        decoder_config_path: &str,
        vocab_path: &str,
        masks_path: &str,
    ) -> Result<Self, Box<dyn Error>> {
        let session = Session::builder()?.commit_from_file(model_path)?;
        let blank_id = read_blank_id(decoder_config_path)?;

        let vocab: HashMap<String, Vec<String>> =
            serde_json::from_reader(BufReader::new(File::open(vocab_path)?))?;
        let raw_masks: HashMap<String, MaskEntry> =
            serde_json::from_reader(BufReader::new(File::open(masks_path)?))?;

        let mut masks = HashMap::with_capacity(raw_masks.len());
        for (lang, entry) in raw_masks {
            let indices: Vec<usize> = match entry {
                MaskEntry::Indices(values) => {
                    values.into_iter().map(|i| i as usize).collect::<Vec<_>>()
                }
                MaskEntry::Flags(flags) => flags
                    .into_iter()
                    .enumerate()
                    .filter(|(_, flag)| *flag)
                    .map(|(i, _)| i)
                    .collect::<Vec<_>>(),
            };
            if indices.is_empty() {
                return Err(format!("language '{}' has an empty mask", lang).into());
            }
            masks.insert(lang, indices);
        }

        if vocab.is_empty() || masks.is_empty() {
            return Err("multilingual vocab/masks are empty".into());
        }

        let default_language = if vocab.contains_key("hi") {
            "hi".to_string()
        } else {
            vocab.keys().min().cloned().ok_or("no languages in vocab")?
        };

        Ok(Transcriber {
            session: Mutex::new(session),
            backend: Backend::Multilingual {
                blank_id,
                vocab,
                masks,
                default_language,
            },
        })
    }

    pub fn transcribe(
        &self,
        audio: &[f32],
        sample_rate: u32,
        language: Option<&str>,
    ) -> Result<String, Box<dyn Error>> {
        let logits = self.infer_logits(audio, sample_rate)?;

        match &self.backend {
            Backend::Single {
                blank_id,
                label_map,
            } => decode::ctc_greedy_decode(&logits, *blank_id, label_map),
            Backend::Multilingual {
                blank_id,
                vocab,
                masks,
                default_language,
            } => {
                let lang = language.unwrap_or(default_language.as_str());
                let mask = masks
                    .get(lang)
                    .ok_or_else(|| format!("unsupported language '{}'", lang))?;
                let tokens = vocab
                    .get(lang)
                    .ok_or_else(|| format!("no vocabulary for language '{}'", lang))?;
                decode::ctc_greedy_decode_masked(&logits, mask, *blank_id, tokens)
            }
        }
    }

    pub fn transcribe_file(
        &self,
        audio_path: &str,
        language: Option<&str>,
    ) -> Result<String, Box<dyn Error>> {
        let (audio, sample_rate) = preprocess::read_wav(audio_path)?;
        self.transcribe(&audio, sample_rate, language)
    }

    fn infer_logits(&self, audio: &[f32], sample_rate: u32) -> Result<Array2<f32>, Box<dyn Error>> {
        let (features, length) = preprocess::compute_features(audio, sample_rate)?;

        let features_3d =
            features
                .view()
                .into_shape_with_order((1, features.shape()[0], features.shape()[1]))?;
        let features_3d = features_3d.into_owned();

        let length_1d = length.clone();

        let mut session = self.session.lock().unwrap();

        let features_tensor = TensorRef::from_array_view(&features_3d)?;
        let length_tensor = TensorRef::from_array_view(&length_1d)?;

        let outputs = session.run(ort::inputs![
            "audio_signal" => features_tensor,
            "length" => length_tensor,
        ])?;

        let logits = outputs[0].try_extract_array::<f32>()?;
        let logits_3d = logits
            .into_dimensionality::<Ix3>()
            .map_err(|e| format!("Expected 3D logits: {}", e))?;
        Ok(logits_3d.index_axis(Axis(0), 0).to_owned())
    }
}

unsafe fn optional_str<'a>(ptr: *const libc::c_char) -> Option<&'a str> {
    if ptr.is_null() {
        None
    } else {
        std::ffi::CStr::from_ptr(ptr).to_str().ok()
    }
}

#[no_mangle]
pub extern "C" fn transcriber_create(
    model_path: *const libc::c_char,
    decoder_config_path: *const libc::c_char,
    vocab_path: *const libc::c_char,
) -> *mut Transcriber {
    let model_path = unsafe { std::ffi::CStr::from_ptr(model_path) }
        .to_str()
        .unwrap();
    let decoder_config_path = unsafe { std::ffi::CStr::from_ptr(decoder_config_path) }
        .to_str()
        .unwrap();
    let vocab_path = unsafe { std::ffi::CStr::from_ptr(vocab_path) }
        .to_str()
        .unwrap();

    match Transcriber::new(model_path, decoder_config_path, vocab_path) {
        Ok(t) => Box::into_raw(Box::new(t)),
        Err(e) => {
            eprintln!("transcriber_create failed: {}", e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn transcriber_create_multilingual(
    model_path: *const libc::c_char,
    decoder_config_path: *const libc::c_char,
    vocab_path: *const libc::c_char,
    masks_path: *const libc::c_char,
) -> *mut Transcriber {
    let model_path = unsafe { std::ffi::CStr::from_ptr(model_path) }
        .to_str()
        .unwrap();
    let decoder_config_path = unsafe { std::ffi::CStr::from_ptr(decoder_config_path) }
        .to_str()
        .unwrap();
    let vocab_path = unsafe { std::ffi::CStr::from_ptr(vocab_path) }
        .to_str()
        .unwrap();
    let masks_path = unsafe { std::ffi::CStr::from_ptr(masks_path) }
        .to_str()
        .unwrap();

    match Transcriber::new_multilingual(model_path, decoder_config_path, vocab_path, masks_path) {
        Ok(t) => Box::into_raw(Box::new(t)),
        Err(e) => {
            eprintln!("transcriber_create_multilingual failed: {}", e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn transcriber_transcribe_file(
    handle: *mut Transcriber,
    audio_path: *const libc::c_char,
) -> *mut libc::c_char {
    transcriber_transcribe_file_lang(handle, audio_path, std::ptr::null())
}

#[no_mangle]
pub extern "C" fn transcriber_transcribe_file_lang(
    handle: *mut Transcriber,
    audio_path: *const libc::c_char,
    language: *const libc::c_char,
) -> *mut libc::c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let transcriber = unsafe { &*handle };
    let audio_path = unsafe { std::ffi::CStr::from_ptr(audio_path) }
        .to_str()
        .unwrap();
    let language = unsafe { optional_str(language) };

    match transcriber.transcribe_file(audio_path, language) {
        Ok(text) => {
            let c_str = std::ffi::CString::new(text).unwrap();
            c_str.into_raw()
        }
        Err(e) => {
            eprintln!("transcriber_transcribe_file failed: {}", e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn transcriber_transcribe_audio(
    handle: *mut Transcriber,
    audio: *const f32,
    len: usize,
    sample_rate: u32,
) -> *mut libc::c_char {
    transcriber_transcribe_audio_lang(handle, audio, len, sample_rate, std::ptr::null())
}

#[no_mangle]
pub extern "C" fn transcriber_transcribe_audio_lang(
    handle: *mut Transcriber,
    audio: *const f32,
    len: usize,
    sample_rate: u32,
    language: *const libc::c_char,
) -> *mut libc::c_char {
    if handle.is_null() || audio.is_null() {
        return std::ptr::null_mut();
    }
    let transcriber = unsafe { &*handle };
    let audio_slice = unsafe { std::slice::from_raw_parts(audio, len) };
    let language = unsafe { optional_str(language) };

    match transcriber.transcribe(audio_slice, sample_rate, language) {
        Ok(text) => {
            let c_str = std::ffi::CString::new(text).unwrap();
            c_str.into_raw()
        }
        Err(e) => {
            eprintln!("transcriber_transcribe_audio failed: {}", e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn transcriber_destroy(handle: *mut Transcriber) {
    if !handle.is_null() {
        drop(unsafe { Box::from_raw(handle) });
    }
}

#[no_mangle]
pub extern "C" fn transcriber_free_string(s: *mut libc::c_char) {
    if !s.is_null() {
        drop(unsafe { std::ffi::CString::from_raw(s) });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_preprocess_features() {
        let sr = 16000;
        let duration = 0.5;
        let num_samples = (sr as f64 * duration) as usize;
        let audio: Vec<f32> = (0..num_samples)
            .map(|i| (2.0 * std::f32::consts::PI * 440.0 * i as f32 / sr as f32).sin())
            .collect();

        let (features, length) = preprocess::compute_features(&audio, sr).unwrap();

        assert_eq!(features.shape()[0], 80);
        assert_eq!(length[0] as usize, features.shape()[1]);
        assert!(features.shape()[1] > 0);

        // Check per-band normalization
        for band in 0..5 {
            let row = features.row(band);
            let mean = row.mean().unwrap();
            let variance =
                row.iter().map(|v| (v - mean).powi(2)).sum::<f32>() / (row.len() as f32 - 1.0);
            let std = variance.sqrt();
            assert!(
                mean.abs() < 0.01,
                "Band {} mean should be ~0, got {}",
                band,
                mean
            );
            assert!(
                (std - 1.0).abs() < 0.01,
                "Band {} std should be ~1, got {}",
                band,
                std
            );
        }
    }

    #[test]
    fn test_normalization_synthetic() {
        let mut data =
            Array2::<f32>::from_shape_vec((3, 100), (0..300).map(|v| v as f32).collect()).unwrap();
        crate::preprocess::normalize_per_feature(&mut data);
        for band in 0..3 {
            let row = data.row(band);
            let mean = row.mean().unwrap();
            let variance =
                row.iter().map(|v| (v - mean).powi(2)).sum::<f32>() / (row.len() as f32 - 1.0);
            let std = variance.sqrt();
            assert!(
                mean.abs() < 1e-5,
                "Band {} mean should be ~0, got {}",
                band,
                mean
            );
            assert!(
                (std - 1.0).abs() < 1e-5,
                "Band {} std should be ~1, got {}",
                band,
                std
            );
        }
    }
}
