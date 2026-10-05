//! Export the production Rust frontend's features as a NumPy v1 float32 array.
use std::{
    error::Error,
    fs::OpenOptions,
    io::{BufWriter, Write},
    path::PathBuf,
};

fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 3 {
        return Err("usage: export_features input.wav output.npy".into());
    }
    let output = PathBuf::from(&args[2]);
    let (audio, rate) = rust_backend::preprocess::read_wav(&args[1])?;
    if audio.is_empty() || rate == 0 || audio.iter().any(|value| !value.is_finite()) {
        return Err("audio must be nonempty and finite with a positive sample rate".into());
    }
    let (features, lengths) = rust_backend::preprocess::compute_features(&audio, rate)?;
    if features.iter().any(|value| !value.is_finite()) {
        return Err("audio produced nonfinite features".into());
    }
    let frames = lengths[0];
    let mut header =
        format!("{{'descr': '<f4', 'fortran_order': False, 'shape': (1, 80, {frames}), }}");
    // NumPy v1: six-byte magic, two version bytes, two header-length bytes.
    let padding = (64 - ((10 + header.len() + 1) % 64)) % 64;
    header.push_str(&" ".repeat(padding));
    header.push('\n');
    let header_len = u16::try_from(header.len())?;
    let mut writer = BufWriter::new(
        OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&output)?,
    );
    writer.write_all(b"\x93NUMPY\x01\x00")?;
    writer.write_all(&header_len.to_le_bytes())?;
    writer.write_all(header.as_bytes())?;
    for value in &features {
        writer.write_all(&value.to_le_bytes())?;
    }
    writer.flush()?;
    println!(
        "{}",
        serde_json::json!({
            "features": output, "shape": [1, 80, frames],
            "duration_seconds": audio.len() as f64 / rate as f64,
            "frontend": "rust_backend::preprocess::compute_features",
        })
    );
    Ok(())
}
