use std::error::Error;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::PathBuf;
use std::time::Instant;

fn main() -> Result<(), Box<dyn Error>> {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .join("voice-models");
    let output = root.join("browser-assets/reference");
    std::fs::create_dir_all(&output)?;
    let audio_path = root.join("telugu_test_audio_16k.wav");
    let (audio, sample_rate) = rust_backend::preprocess::read_wav(audio_path.to_str().unwrap())?;

    let feature_start = Instant::now();
    let (features, length) = rust_backend::preprocess::compute_features(&audio, sample_rate)?;
    let feature_ms = feature_start.elapsed().as_secs_f64() * 1000.0;
    let feature_path = output.join("telugu-16k-features.f32le");
    let mut file = BufWriter::new(File::create(&feature_path)?);
    for value in features.iter() {
        file.write_all(&value.to_le_bytes())?;
    }
    file.flush()?;
    std::fs::copy(&audio_path, output.join("telugu_test_audio_16k.wav"))?;
    let mut results = Vec::new();
    for (id, model_file) in [
        (
            "indicconformer-te",
            "indicconformer_stt_te_ctc_static_qdq_int8.onnx",
        ),
        ("indicconformer-multi", "indicconformer_multi_ctc_int8.onnx"),
    ] {
        let model_dir = root.join("browser-assets").join(id);
        let model_path = model_dir.join(model_file);
        if !model_path.exists() {
            continue;
        }
        let config = model_dir.join("decoder_config.json");
        let load_start = Instant::now();
        let model = if id == "indicconformer-te" {
            rust_backend::Transcriber::new(
                model_path.to_str().unwrap(),
                config.to_str().unwrap(),
                model_dir.join("tokenizer_te.vocab").to_str().unwrap(),
            )?
        } else {
            rust_backend::Transcriber::new_multilingual(
                model_path.to_str().unwrap(),
                config.to_str().unwrap(),
                model_dir.join("vocab.json").to_str().unwrap(),
                model_dir.join("language_masks.json").to_str().unwrap(),
            )?
        };
        let load_ms = load_start.elapsed().as_secs_f64() * 1000.0;
        let mut runs = Vec::new();
        for iteration in 0..2 {
            let start = Instant::now();
            let text = model.transcribe(&audio, sample_rate, Some("te"))?;
            let ms = start.elapsed().as_secs_f64() * 1000.0;
            runs.push(serde_json::json!({"iteration": iteration, "transcript": text, "preprocessInferenceDecodeMs": ms}));
        }
        results.push(serde_json::json!({"id": id, "modelLoadMs": load_ms, "runs": runs}));
    }
    let metadata = serde_json::json!({
        "source": "Production rust_backend crate, native ORT default provider",
        "build": if cfg!(debug_assertions) { "debug" } else { "release" },
        "sampleRate": sample_rate,
        "audioSamples": audio.len(),
        "audioDurationSeconds": audio.len() as f64 / sample_rate as f64,
        "featuresFile": feature_path,
        "featuresFormat": "IEEE 754 float32 little endian, C row-major [1,80,time]",
        "featuresShape": [1, features.shape()[0], features.shape()[1]],
        "length": length[0],
        "preprocessingMs": feature_ms,
        "models": results,
    });
    let report = serde_json::to_string_pretty(&metadata)?;
    std::fs::write(output.join("native-reference.json"), &report)?;
    println!("{report}");
    Ok(())
}
