//! Exercise production model loading, preprocessing and decoding on native ORT.
//! Timings describe this host only; use an otherwise idle host for comparisons.
use std::{collections::HashMap, error::Error, ffi::CStr, fs, time::Instant};

use serde_json::{json, Value};

const USAGE: &str = "model_smoke --model MODEL --decoder-config CONFIG --vocab VOCAB --audio WAV [--language LANG] [--language-masks MASKS | --single-language] [--warmup 0] [--runs 1]";

fn parse_args(
    args: impl Iterator<Item = String>,
) -> Result<HashMap<String, String>, Box<dyn Error>> {
    let mut args = args.peekable();
    let mut values = HashMap::new();
    while let Some(key) = args.next() {
        if key == "--help" || key == "-h" {
            return Err(USAGE.into());
        }
        if key == "--single-language" {
            if values.insert(key, "true".into()).is_some() {
                return Err("duplicate argument".into());
            }
            continue;
        }
        if ![
            "--model",
            "--decoder-config",
            "--vocab",
            "--audio",
            "--language",
            "--language-masks",
            "--warmup",
            "--runs",
        ]
        .contains(&key.as_str())
        {
            return Err(format!("unknown argument {key}; {USAGE}").into());
        }
        let value = args
            .next()
            .filter(|value| !value.starts_with("--"))
            .ok_or_else(|| format!("missing value for {key}"))?;
        if values.insert(key, value).is_some() {
            return Err("duplicate argument".into());
        }
    }
    for key in ["--model", "--decoder-config", "--vocab", "--audio"] {
        if !values.contains_key(key) {
            return Err(format!("missing {key}; {USAGE}").into());
        }
    }
    if values.contains_key("--single-language") == values.contains_key("--language-masks") {
        return Err("choose --single-language or --language-masks".into());
    }
    Ok(values)
}

fn main() -> Result<(), Box<dyn Error>> {
    let args = parse_args(std::env::args().skip(1))?;
    let warmup: usize = args.get("--warmup").map_or("0", String::as_str).parse()?;
    let runs: usize = args.get("--runs").map_or("1", String::as_str).parse()?;
    if runs == 0 {
        return Err("--runs must be positive".into());
    }
    let model_path = &args["--model"];
    let config_path = &args["--decoder-config"];
    let vocab_path = &args["--vocab"];
    let language = args.get("--language").map(String::as_str);
    let single = args.contains_key("--single-language");
    // This reports the actual linked library, rather than the Cargo API version.
    let ort_version = unsafe {
        let base = ort::sys::OrtGetApiBase();
        if base.is_null() {
            return Err("OrtGetApiBase returned null".into());
        }
        CStr::from_ptr(((*base).GetVersionString)())
            .to_string_lossy()
            .into_owned()
    };
    let mut report = json!({
        "schema_version": 1,
        "status": "compatibility_not_run",
        "release_ready": false,
        "model": fs::canonicalize(model_path)?,
        "model_bytes": fs::metadata(model_path)?.len(),
        "audio": fs::canonicalize(&args["--audio"])?,
        "language": language,
        "host": {"os": std::env::consts::OS, "arch": std::env::consts::ARCH},
        "backend": if single {"rust_backend::Transcriber::new"} else {"rust_backend::Transcriber::new_multilingual"},
        "frontend": "rust_backend::preprocess::compute_features",
        "onnxruntime_version": ort_version,
        "onnxruntime_build": ort::info(),
        "ort_rust_api_version": ort::MINOR_VERSION,
        "execution_provider": "native default CPU provider",
        "session_threads": "production constructor defaults",
        "build_profile": if cfg!(debug_assertions) {"debug"} else {"release"},
        "warmup_runs": warmup,
        "measured_runs": runs,
        "timing_note": "Host native only; no physical phone or browser tested. A compatibility smoke under concurrent workloads is not a qualified latency comparison.",
        "decoder_config": serde_json::from_str::<Value>(&fs::read_to_string(config_path)?)?,
        "decoder_assets": {"config": fs::canonicalize(config_path)?, "vocab": fs::canonicalize(vocab_path)?},
    });
    if !single {
        report["decoder_assets"]["language_masks"] =
            json!(fs::canonicalize(&args["--language-masks"])?);
        let vocabulary: HashMap<String, Vec<String>> =
            serde_json::from_str(&fs::read_to_string(vocab_path)?)?;
        if let Some(language) = language {
            report["selected_language_tokens"] = json!(vocabulary.get(language).map(Vec::len));
        }
    }
    let result: Result<(), Box<dyn Error>> = (|| {
        let (audio, sample_rate) = rust_backend::preprocess::read_wav(&args["--audio"])?;
        if audio.is_empty() || sample_rate == 0 || audio.iter().any(|v| !v.is_finite()) {
            return Err("audio must be nonempty and finite".into());
        }
        report["audio_sample_rate"] = json!(sample_rate);
        report["audio_samples"] = json!(audio.len());
        report["audio_duration_seconds"] = json!(audio.len() as f64 / sample_rate as f64);
        let load_start = Instant::now();
        let transcriber = if single {
            rust_backend::Transcriber::new(model_path, config_path, vocab_path)?
        } else {
            rust_backend::Transcriber::new_multilingual(
                model_path,
                config_path,
                vocab_path,
                &args["--language-masks"],
            )?
        };
        report["model_load_ms"] = json!(load_start.elapsed().as_secs_f64() * 1000.0);
        report["model_loaded"] = json!(true);
        for _ in 0..warmup {
            transcriber.transcribe(&audio, sample_rate, language)?;
        }
        let mut results = Vec::with_capacity(runs);
        for iteration in 0..runs {
            let start = Instant::now();
            let transcript = transcriber.transcribe(&audio, sample_rate, language)?;
            results.push(json!({"iteration": iteration, "transcript": transcript, "preprocess_inference_decode_ms": start.elapsed().as_secs_f64() * 1000.0}));
        }
        report["transcript_stable"] = json!(results
            .iter()
            .all(|value| value["transcript"] == results[0]["transcript"]));
        report["runs"] = json!(results);
        report["status"] = json!("native_compatibility_passed");
        Ok(())
    })();
    if let Err(ref error) = result {
        report["status"] = json!("native_compatibility_failed");
        report["error"] = json!(error.to_string());
    }
    // stdout contains exactly one machine-readable report; ORT diagnostics use stderr.
    println!("{}", serde_json::to_string_pretty(&report)?);
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn modes_are_exclusive() {
        let args = "--model m --decoder-config c --vocab v --audio a --language-masks masks --single-language";
        assert!(parse_args(args.split_whitespace().map(str::to_string)).is_err());
    }

    #[test]
    fn parses_repetition_and_multilingual_arguments() {
        let args = "--model m --decoder-config c --vocab v --audio a --language-masks masks --language te --runs 3 --warmup 1";
        let parsed = parse_args(args.split_whitespace().map(str::to_string)).unwrap();
        assert_eq!(parsed["--runs"], "3");
        assert_eq!(parsed["--language"], "te");
    }
}
