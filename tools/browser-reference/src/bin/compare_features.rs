use ndarray::Array3;
use ort::{session::Session, value::TensorRef};
use std::{error::Error, io::Write, path::PathBuf, time::Instant};

fn main() -> Result<(), Box<dyn Error>> {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .join("voice-models")
        .join("browser-assets");
    let reference = root.join("reference");
    let mut reports = Vec::new();
    for (id, filename) in [
        (
            "indicconformer-te",
            "indicconformer_stt_te_ctc_static_qdq_int8.onnx",
        ),
        ("indicconformer-multi", "indicconformer_multi_ctc_int8.onnx"),
    ] {
        let mut session = Session::builder()?.commit_from_file(root.join(id).join(filename))?;
        for (source, feature_file) in [
            ("rust", "telugu-16k-features.f32le"),
            ("browser", "browser-features.f32le"),
        ] {
            let path = reference.join(feature_file);
            if !path.exists() {
                continue;
            }
            let bytes = std::fs::read(path)?;
            let values: Vec<f32> = bytes
                .chunks_exact(4)
                .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
                .collect();
            let frames = values.len() / 80;
            let features = Array3::from_shape_vec((1, 80, frames), values)?;
            let length = ndarray::Array1::from_vec(vec![frames as i64]);
            let start = Instant::now();
            let outputs = session.run(ort::inputs![
                "audio_signal" => TensorRef::from_array_view(&features)?,
                "length" => TensorRef::from_array_view(&length)?,
            ])?;
            let inference_ms = start.elapsed().as_secs_f64() * 1000.0;
            let logits = outputs[0].try_extract_array::<f32>()?;
            let outfile = reference.join(format!("native-logits-{id}-{source}.f32le"));
            let mut writer = std::io::BufWriter::new(std::fs::File::create(&outfile)?);
            for value in logits.iter() {
                writer.write_all(&value.to_le_bytes())?;
            }
            writer.flush()?;
            reports.push(serde_json::json!({
                "id": id, "featureSource": source, "shape": logits.shape(),
                "logitsFile": outfile, "inferenceMs": inference_ms,
            }));
        }
    }
    let json = serde_json::to_string_pretty(&reports)?;
    std::fs::write(reference.join("native-logits-report.json"), &json)?;
    println!("{json}");
    Ok(())
}
