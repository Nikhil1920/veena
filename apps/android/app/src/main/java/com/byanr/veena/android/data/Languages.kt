package com.byanr.veena.android.data

/**
 * The 22 scheduled languages of India supported by Veena.
 * Ported from veena-web/src/config/languages.mjs (the canonical catalog);
 * codes match the IndicConformer model language codes.
 */
data class Language(
    val code: String,
    val name: String,
    val nativeName: String,
)

val LANGUAGES: List<Language> = listOf(
    Language("as", "Assamese", "অসমীয়া"),
    Language("bn", "Bengali", "বাংলা"),
    Language("brx", "Bodo", "बर' / बड़ो"),
    Language("doi", "Dogri", "डोगरी"),
    Language("gu", "Gujarati", "ગુજરાતી"),
    Language("hi", "Hindi", "हिन्दी"),
    Language("kn", "Kannada", "ಕನ್ನಡ"),
    Language("ks", "Kashmiri", "کٲشُر / कॉशुर"),
    Language("kok", "Konkani", "कोंकणी"),
    Language("mai", "Maithili", "मैथिली"),
    Language("ml", "Malayalam", "മലയാളം"),
    Language("mni", "Manipuri (Meitei)", "ꯃꯤꯇꯩꯂꯣꯟ / মৈতৈলোন্"),
    Language("mr", "Marathi", "मराठी"),
    Language("ne", "Nepali", "नेपाली"),
    Language("or", "Odia", "ଓଡ଼ିଆ"),
    Language("pa", "Punjabi", "ਪੰਜਾਬੀ"),
    Language("sa", "Sanskrit", "संस्कृतम्"),
    Language("sat", "Santali", "ᱥᱟᱱᱛᱟᱲᱤ"),
    Language("sd", "Sindhi", "سنڌي / सिन्धी"),
    Language("ta", "Tamil", "தமிழ்"),
    Language("te", "Telugu", "తెలుగు"),
    Language("ur", "Urdu", "اردو"),
)

fun languageByCode(code: String): Language? = LANGUAGES.firstOrNull { it.code == code }
