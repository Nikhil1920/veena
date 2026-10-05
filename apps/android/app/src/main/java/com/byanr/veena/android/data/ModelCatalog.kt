package com.byanr.veena.android.data

/**
 * Downloadable voice models, mirroring the desktop catalog
 * (apps/desktop/src-tauri/src/managers/model.rs).
 */
data class ModelDef(
    val id: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String?,
    /** True when the model decodes for any of the 22 languages. */
    val multilingual: Boolean,
)

object ModelCatalog {
    const val BASE_URL = "https://dl.byanr.com/veena-byanr"

    val TELUGU = ModelDef(
        id = "indicconformer-te",
        url = "$BASE_URL/indicconformer_stt_te_ctc_static_qdq_int8.tar.gz",
        sizeBytes = 124_831_706L,
        sha256 = null,
        multilingual = false,
    )

    val MULTILINGUAL = ModelDef(
        id = "indicconformer-multi",
        url = "$BASE_URL/indicconformer_multi_ctc_int8.tar.gz",
        sizeBytes = 478_579_709L,
        sha256 = "0c885b33ff0df71fa4a91171a7c46486d337c4503302076a1ada8ca730c42f94",
        multilingual = true,
    )

    /** Telugu ships a dedicated model; every other language uses the multilingual model. */
    fun modelForLanguage(code: String): ModelDef =
        if (code == "te") TELUGU else MULTILINGUAL

    val ALL: List<ModelDef> = listOf(TELUGU, MULTILINGUAL)
}
