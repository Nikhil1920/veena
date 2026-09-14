use log::info;
use rust_backend::Transcriber;
use std::path::Path;

const MULTILINGUAL_MODEL_FILENAME: &str = "indicconformer_multi_ctc_int8.onnx";
const MULTILINGUAL_VOCAB_FILENAME: &str = "vocab.json";
const MULTILINGUAL_MASKS_FILENAME: &str = "language_masks.json";

const SINGLE_MODEL_FILENAME: &str = "indicconformer_stt_te_ctc_static_qdq_int8.onnx";
const DECODER_CONFIG_FILENAME: &str = "decoder_config.json";
const SINGLE_VOCAB_FILENAME: &str = "tokenizer_te.vocab";

pub struct IndicConformerEngine {
    transcriber: Transcriber,
}

impl IndicConformerEngine {
    pub fn load(model_dir: &Path) -> Result<Self, Box<dyn std::error::Error>> {
        let multilingual_model_path = model_dir.join(MULTILINGUAL_MODEL_FILENAME);

        let transcriber = if multilingual_model_path.exists() {
            // Multilingual model: single fused ONNX with a shared CTC head
            // plus per-language vocabularies and logit masks.
            info!(
                "Loading multilingual IndicConformer model from {:?}",
                model_dir
            );
            Transcriber::new_multilingual(
                multilingual_model_path
                    .to_str()
                    .ok_or("Invalid model path")?,
                model_dir
                    .join(DECODER_CONFIG_FILENAME)
                    .to_str()
                    .ok_or("Invalid config path")?,
                model_dir
                    .join(MULTILINGUAL_VOCAB_FILENAME)
                    .to_str()
                    .ok_or("Invalid vocab path")?,
                model_dir
                    .join(MULTILINGUAL_MASKS_FILENAME)
                    .to_str()
                    .ok_or("Invalid masks path")?,
            )?
        } else {
            // Single-language model (e.g. Telugu).
            info!("Loading IndicConformer model from {:?}", model_dir);
            Transcriber::new(
                model_dir
                    .join(SINGLE_MODEL_FILENAME)
                    .to_str()
                    .ok_or("Invalid model path")?,
                model_dir
                    .join(DECODER_CONFIG_FILENAME)
                    .to_str()
                    .ok_or("Invalid config path")?,
                model_dir
                    .join(SINGLE_VOCAB_FILENAME)
                    .to_str()
                    .ok_or("Invalid vocab path")?,
            )?
        };

        Ok(Self { transcriber })
    }

    pub fn transcribe(
        &self,
        audio: &[f32],
        sample_rate: u32,
        language: Option<&str>,
    ) -> Result<String, Box<dyn std::error::Error>> {
        self.transcriber.transcribe(audio, sample_rate, language)
    }
}
