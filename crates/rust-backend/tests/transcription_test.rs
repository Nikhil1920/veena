use std::path::PathBuf;

fn project_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .parent()
        .unwrap()
        .join("tools")
        .join("voice-models")
}

const EXPECTED_TELUGU: &str = "తెలుగు స్పీచ్ టు టెక్స్ట్ టెస్ట్ ఆడియో వన్ మా దుకాణానికి ఒక వ్యక్తి వచ్చి పది బస్తాలు యూరియా తీసుకున్నారు రెండు ప్యాకెట్ల నూనె తీసుకున్నారు మరియు నాలుగు ప్యాకెట్ల విత్తనాలు సొరకాయ ఇత్తనాలు తీసుకువెళ్లారు";

fn single_model_paths() -> (PathBuf, PathBuf, PathBuf) {
    let root = project_root();
    (
        root.join("models")
            .join("indicconformer_stt_te_ctc_static_qdq_int8.onnx"),
        root.join("models").join("decoder_config.json"),
        root.join("models").join("tokenizer_te.vocab"),
    )
}

fn multi_model_paths() -> Option<(PathBuf, PathBuf, PathBuf, PathBuf)> {
    let dir = project_root().join("models").join("indicconformer-multi");
    let model = dir.join("indicconformer_multi_ctc_int8.onnx");
    let config = dir.join("decoder_config.json");
    let vocab = dir.join("vocab.json");
    let masks = dir.join("language_masks.json");
    if model.exists() && config.exists() && vocab.exists() && masks.exists() {
        Some((model, config, vocab, masks))
    } else {
        None
    }
}

#[test]
fn test_transcription_matches_expected() {
    let (model_path, config_path, vocab_path) = single_model_paths();
    let audio_path = project_root().join("telugu_test_audio.wav");

    let transcriber = rust_backend::Transcriber::new(
        model_path.to_str().unwrap(),
        config_path.to_str().unwrap(),
        vocab_path.to_str().unwrap(),
    )
    .expect("Failed to create transcriber");

    let result = transcriber
        .transcribe_file(audio_path.to_str().unwrap(), None)
        .expect("Transcription failed");

    assert_eq!(result, EXPECTED_TELUGU);
}

#[test]
fn test_transcribe_file_basic() {
    let (model_path, config_path, vocab_path) = single_model_paths();
    let audio_path = project_root().join("telugu_test_audio.wav");

    let transcriber = rust_backend::Transcriber::new(
        model_path.to_str().unwrap(),
        config_path.to_str().unwrap(),
        vocab_path.to_str().unwrap(),
    )
    .expect("Failed to create transcriber");

    let result = transcriber
        .transcribe_file(audio_path.to_str().unwrap(), None)
        .expect("Transcription failed");

    println!("Transcription result: {}", result);
    assert!(!result.is_empty(), "Transcription should not be empty");
}

#[test]
fn test_transcribe_file_basic_2() {
    let (model_path, config_path, vocab_path) = single_model_paths();
    let audio_path = project_root().join("record_out.wav");

    let transcriber = rust_backend::Transcriber::new(
        model_path.to_str().unwrap(),
        config_path.to_str().unwrap(),
        vocab_path.to_str().unwrap(),
    )
    .expect("Failed to create transcriber");

    let result = transcriber
        .transcribe_file(audio_path.to_str().unwrap(), None)
        .expect("Transcription failed");

    println!("Transcription result: {}", result);
    assert!(!result.is_empty(), "Transcription should not be empty");
}

#[test]
fn test_multilingual_telugu_matches_single_model() {
    let Some((model_path, config_path, vocab_path, masks_path)) = multi_model_paths() else {
        // The 456 MB multilingual model is optional on this machine; skip
        // when it has not been downloaded into voice-models/models/.
        println!("Skipping: multilingual model artifacts not present");
        return;
    };
    let audio_path = project_root().join("telugu_test_audio.wav");

    let transcriber = rust_backend::Transcriber::new_multilingual(
        model_path.to_str().unwrap(),
        config_path.to_str().unwrap(),
        vocab_path.to_str().unwrap(),
        masks_path.to_str().unwrap(),
    )
    .expect("Failed to create multilingual transcriber");

    let result = transcriber
        .transcribe_file(audio_path.to_str().unwrap(), Some("te"))
        .expect("Multilingual transcription failed");

    println!("Multilingual [te] result: {}", result);
    // Matches the dedicated Telugu model except the final word, where the
    // int8 multilingual export settles on ళ్ళ instead of ళ్ల.
    let expected = "తెలుగు స్పీచ్ టు టెక్స్ట్ టెస్ట్ ఆడియో వన్ మా దుకాణానికి ఒక వ్యక్తి వచ్చి పది బస్తాలు యూరియా తీసుకున్నారు రెండు ప్యాకెట్ల నూనె తీసుకున్నారు మరియు నాలుగు ప్యాకెట్ల విత్తనాలు సొరకాయ ఇత్తనాలు తీసుకువెళ్ళారు";
    assert_eq!(result, expected);
}

#[test]
fn test_multilingual_rejects_unknown_language() {
    let Some((model_path, config_path, vocab_path, masks_path)) = multi_model_paths() else {
        println!("Skipping: multilingual model artifacts not present");
        return;
    };

    let transcriber = rust_backend::Transcriber::new_multilingual(
        model_path.to_str().unwrap(),
        config_path.to_str().unwrap(),
        vocab_path.to_str().unwrap(),
        masks_path.to_str().unwrap(),
    )
    .expect("Failed to create multilingual transcriber");

    let audio = vec![0.0f32; 1600];
    let err = transcriber
        .transcribe(&audio, 16000, Some("xx"))
        .expect_err("unknown language should fail");
    assert!(err.to_string().contains("unsupported language"));
}
