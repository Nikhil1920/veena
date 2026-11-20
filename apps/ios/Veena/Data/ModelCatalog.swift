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
    /// Set when the model decodes this one language only.
    var languageCode: String?

    var displayName: String {
        if let languageCode {
            return "IndicConformer \(language(byCode: languageCode)?.name ?? languageCode) (keyboard)"
        }
        return multilingual ? "IndicConformer Multilingual (22 languages)" : "IndicConformer Telugu"
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

    // MARK: Language-specific models

    /// Download size and SHA-256 of each language's own model: the balanced
    /// INT8 builds from tools/voice-models (reports/mobile-quantization).
    /// Telugu has its dedicated model above; Manipuri's own model scored
    /// worse than the multilingual one, so it has none.
    private static let languageModelFiles: [String: (Int64, String)] = [
        "as": (130_293_218, "ff09c94f8adbc39b3a3d1336ef98f9298d325076927db1036ac503a4cfc4b1ec"),
        "bn": (130_307_973, "9e5c9645d83818efbeb68b4a1d80ad82ab3cfbd2cb07837968fee0d82292450c"),
        "brx": (130_299_785, "c815ccbd9dffccf1a5824bde8286a8d42f100031ed7e5dd14d8a87d0414580e6"),
        "doi": (130_298_800, "bcc4437fd7483d1000813cbdb4f593f8ccab3980fd03dfeb8862293792b21c83"),
        "gu": (130_297_955, "00d5b107de741383459167642b871d2031207e7333a8273efbb567b978732caa"),
        "hi": (130_367_015, "eec1bc8891dffd3671e58e4670933e64a06f15ad1ec0cc9882b440992e6dfe0b"),
        "kn": (130_300_898, "c411eb22d3877df1d4a445473774a5e6596a4c52254cfe5ff1337fd2e6e3b92d"),
        "kok": (130_290_534, "a52f2a99dc8945a226a97b9382eb5da4b78cc0b84a619445ee62daeef0af18b9"),
        "ks": (130_302_419, "414f8f125be998f202d119583a3831b07df530d3e8d2783b3a1853dedd4073ba"),
        "mai": (130_288_717, "545c005a56a439046d3ea565868474e52349205666d6782e1f4b77631adff728"),
        "ml": (130_293_625, "6203011a6ac074aa79ed827a42d3b91f654dafd7a7676cfb956662a81cb5283b"),
        "mr": (130_299_765, "79cc6134282aea0a999490d184e8afb8d15629a8dc2f86bc760c288687c8cf8d"),
        "ne": (130_309_068, "f5c28f6ed6ef19cb3a3ae7af675c43c9b905a749b96b95c6a0a5499175667263"),
        "or": (130_292_073, "fa957347862806c8fd63c99686a84c0169acb8dac1971f1e4cf0b55b8f295b38"),
        "pa": (130_293_374, "df9632e3115d5f4dae88d7fba92e7a71870088468668cd5c5f4d39d44d229fad"),
        "sa": (130_293_654, "7f27fef6dead5c3d1746eb261620ec64e6be49cf9601475445686f0cccf57544"),
        "sat": (130_306_646, "6a21d71239ff3f1107863c7a5f5fc20b4219270a2c2838bd094f927c73e30ed6"),
        "sd": (130_279_835, "0a06a56b35a828d35e02862a12955b9e9fa476b5426504fa2e4a179f97298eb0"),
        "ta": (130_359_382, "27a07689455a32facacc870c35dc8bf1d097df75f325d2a0f3f4cb6deb9c17bb"),
        "ur": (130_304_603, "f3aeecaa7f221e2285f79246abfe650d6b85eb14b02b7bf78377169cd760145a"),
    ]

    /// `code`'s own model: a quarter of the multilingual model's size, which
    /// is what lets the keyboard run it without the app. The app itself goes
    /// on using `model(forLanguage:)`.
    static func languageModel(forLanguage code: String) -> ModelDef? {
        guard let (size, sha256) = languageModelFiles[code] else { return nil }
        let id = "indicconformer-\(code)-balanced-int8-candidate-v1"
        return ModelDef(
            id: id,
            url: baseURL.appendingPathComponent("models/\(id).tar.gz"),
            sizeBytes: size,
            sha256: sha256,
            multilingual: false,
            languageCode: code
        )
    }

    static let languageModels: [ModelDef] = languages.compactMap { languageModel(forLanguage: $0.code) }

    /// The model the keyboard dictates with by itself in `code`, if there is one.
    static func keyboardModel(forLanguage code: String) -> ModelDef? {
        code == "te" ? telugu : languageModel(forLanguage: code)
    }
}
