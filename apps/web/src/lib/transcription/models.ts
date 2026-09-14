export type ModelId = "telugu" | "multilingual";

export const MODELS = {
	telugu: {
		id: "telugu",
		name: "Telugu",
		bytes: 124774521,
		sha256: "5ad62bee00fd2141fb1cb1232641ce6a03e4bca238d27e3ee59992db30c46e2e",
		archive: "indicconformer_stt_te_ctc_static_qdq_int8.tar.gz",
		files: [
			"indicconformer_stt_te_ctc_static_qdq_int8.onnx",
			"decoder_config.json",
			"tokenizer_te.vocab",
		],
	},
	multilingual: {
		id: "multilingual",
		name: "All 22 languages",
		bytes: 478579709,
		sha256: "0c885b33ff0df71fa4a91171a7c46486d337c4503302076a1ada8ca730c42f94",
		archive: "indicconformer_multi_ctc_int8.tar.gz",
		files: [
			"indicconformer_multi_ctc_int8.onnx",
			"decoder_config.json",
			"vocab.json",
			"language_masks.json",
		],
	},
} as const;

export type Progress = { phase: string; completed?: number; total?: number };

export function modelForLanguage(language: string): ModelId {
	return language === "te" ? "telugu" : "multilingual";
}
