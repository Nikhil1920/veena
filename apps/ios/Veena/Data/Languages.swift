import Foundation

/// The 22 scheduled languages of India supported by Veena.
/// Ported from veena-web/src/config/languages.mjs (the canonical catalog).
struct Language: Identifiable, Hashable {
    let code: String
    let name: String
    let nativeName: String

    var id: String { code }
}

let languages: [Language] = [
    Language(code: "as", name: "Assamese", nativeName: "অসমীয়া"),
    Language(code: "bn", name: "Bengali", nativeName: "বাংলা"),
    Language(code: "brx", name: "Bodo", nativeName: "बर' / बड़ो"),
    Language(code: "doi", name: "Dogri", nativeName: "डोगरी"),
    Language(code: "gu", name: "Gujarati", nativeName: "ગુજરાતી"),
    Language(code: "hi", name: "Hindi", nativeName: "हिन्दी"),
    Language(code: "kn", name: "Kannada", nativeName: "ಕನ್ನಡ"),
    Language(code: "ks", name: "Kashmiri", nativeName: "کٲشُر / कॉशुर"),
    Language(code: "kok", name: "Konkani", nativeName: "कोंकणी"),
    Language(code: "mai", name: "Maithili", nativeName: "मैथिली"),
    Language(code: "ml", name: "Malayalam", nativeName: "മലയാളം"),
    Language(code: "mni", name: "Manipuri (Meitei)", nativeName: "ꯃꯤꯇꯩꯂꯣꯟ / মৈতৈলোন্"),
    Language(code: "mr", name: "Marathi", nativeName: "मराठी"),
    Language(code: "ne", name: "Nepali", nativeName: "नेपाली"),
    Language(code: "or", name: "Odia", nativeName: "ଓଡ଼ିଆ"),
    Language(code: "pa", name: "Punjabi", nativeName: "ਪੰਜਾਬੀ"),
    Language(code: "sa", name: "Sanskrit", nativeName: "संस्कृतम्"),
    Language(code: "sat", name: "Santali", nativeName: "ᱥᱟᱱᱛᱟᱲᱤ"),
    Language(code: "sd", name: "Sindhi", nativeName: "سنڌي / सिन्धी"),
    Language(code: "ta", name: "Tamil", nativeName: "தமிழ்"),
    Language(code: "te", name: "Telugu", nativeName: "తెలుగు"),
    Language(code: "ur", name: "Urdu", nativeName: "اردو"),
]

func language(byCode code: String) -> Language? {
    languages.first { $0.code == code }
}
