import Foundation

/// Downloadable voice models, mirroring the desktop catalog
/// (apps/desktop/src-tauri/src/managers/model.rs).
struct ModelDef: Identifiable, Hashable {
    let id: String
    let url: URL
    let sizeBytes: Int64
    let sha256: String?
    /// True when the model decodes for any of the 22 languages.
    let multilingual: Bool

    var displayName: String {
        multilingual ? "IndicConformer Multilingual (22 languages)" : "IndicConformer Telugu"
    }
}

enum ModelCatalog {
    static let baseURL = URL(string: "https://dl.byanr.com/veena-byanr")!

    static let telugu = ModelDef(
        id: "indicconformer-te",
        url: baseURL.appendingPathComponent("indicconformer_stt_te_ctc_static_qdq_int8.tar.gz"),
        sizeBytes: 124_831_706,
        sha256: nil,
        multilingual: false
    )

    static let multilingual = ModelDef(
        id: "indicconformer-multi",
        url: baseURL.appendingPathComponent("indicconformer_multi_ctc_int8.tar.gz"),
        sizeBytes: 478_579_709,
        sha256: "0c885b33ff0df71fa4a91171a7c46486d337c4503302076a1ada8ca730c42f94",
        multilingual: true
    )

    /// Telugu ships a dedicated model; every other language uses the multilingual model.
    static func model(forLanguage code: String) -> ModelDef {
        code == "te" ? telugu : multilingual
    }

    static let all: [ModelDef] = [telugu, multilingual]
}
