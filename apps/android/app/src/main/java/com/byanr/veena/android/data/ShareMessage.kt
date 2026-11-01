package com.byanr.veena.android.data

/**
 * One line per language saying what Veena does: "Speak in <language> and
 * Veena types it out at once, with no internet, right on your phone."
 */
private val PITCHES: Map<String, String> = mapOf(
    "as" to "অসমীয়াত কথা কওক, বীণাই তৎক্ষণাত টাইপ কৰি দিব — ইণ্টাৰনেট নোহোৱাকৈ, আপোনাৰ ফোনতে।",
    "bn" to "বাংলায় কথা বলুন, বীণা সঙ্গে সঙ্গে টাইপ করে দেবে — ইন্টারনেট ছাড়াই, আপনার ফোনেই।",
    "brx" to "बर' रावआव बुं, बीणाया गोख्रैयै टाइप खालामनानै होगोन — इन्टारनेट गैयाबालानो, नोंथांनि फोनआवनो।",
    "doi" to "डोगरी च बोलो, वीणा झट्ट टाइप करी देग — बिना इंटरनेट दे, तुंदे फोन उप्पर गै।",
    "gu" to "ગુજરાતીમાં બોલો, વીણા તરત જ ટાઇપ કરી આપશે — ઇન્ટરનેટ વગર, તમારા ફોનમાં જ.",
    "hi" to "हिन्दी में बोलिए, वीणा तुरंत टाइप कर देगी — बिना इंटरनेट, आपके फ़ोन पर ही।",
    "kn" to "ಕನ್ನಡದಲ್ಲಿ ಮಾತನಾಡಿ, ವೀಣಾ ತಕ್ಷಣ ಟೈಪ್ ಮಾಡುತ್ತದೆ — ಇಂಟರ್ನೆಟ್ ಇಲ್ಲದೆ, ನಿಮ್ಮ ಫೋನ್‌ನಲ್ಲೇ.",
    "ks" to "کٲشرِ پٲٹھۍ کٔرِو کَتھ، وینا کَرِ فوراً ٹایپ — انٹرنیٹ وَرٲے، تُہٕنٛدِس فونس پؠٹھ۔",
    "kok" to "कोंकणींत उलयात, वीणा रोखडेंच टायप करून दितली — इंटरनेटा बगर, तुमच्या फोनाचेरूच.",
    "mai" to "मैथिलीमे बाजू, वीणा तुरंत टाइप क' देत — बिना इंटरनेटक, अहाँक फोनेपर।",
    "ml" to "മലയാളത്തിൽ സംസാരിക്കൂ, വീണ ഉടൻ ടൈപ്പ് ചെയ്തുതരും — ഇന്റർനെറ്റ് ഇല്ലാതെ, നിങ്ങളുടെ ഫോണിൽ തന്നെ.",
    "mni" to "ꯃꯤꯇꯩꯂꯣꯟꯗꯥ ꯋꯥ ꯉꯥꯡꯕꯤꯌꯨ, ꯚꯤꯅꯥꯅꯥ ꯈꯨꯗꯛꯇꯥ ꯇꯥꯏꯞ ꯇꯧꯕꯤꯒꯅꯤ — ꯏꯟꯇꯔꯅꯦꯠ ꯌꯥꯎꯗꯅꯥ, ꯅꯍꯥꯛꯀꯤ ꯐꯣꯟꯗꯥ꯫",
    "mr" to "मराठीत बोला, वीणा लगेच टाइप करून देईल — इंटरनेटशिवाय, तुमच्या फोनवरच.",
    "ne" to "नेपालीमा बोल्नुहोस्, वीणाले तुरुन्तै टाइप गरिदिन्छ — इन्टरनेटबिना, तपाईंकै फोनमा।",
    "or" to "ଓଡ଼ିଆରେ କୁହନ୍ତୁ, ବୀଣା ତୁରନ୍ତ ଟାଇପ୍ କରିଦେବ — ଇଣ୍ଟରନେଟ୍ ବିନା, ଆପଣଙ୍କ ଫୋନରେ ହିଁ।",
    "pa" to "ਪੰਜਾਬੀ ਵਿੱਚ ਬੋਲੋ, ਵੀਣਾ ਝੱਟ ਟਾਈਪ ਕਰ ਦੇਵੇਗੀ — ਇੰਟਰਨੈੱਟ ਤੋਂ ਬਿਨਾਂ, ਤੁਹਾਡੇ ਫ਼ੋਨ 'ਤੇ ਹੀ।",
    "sa" to "संस्कृतेन वदतु, वीणा सद्यः टङ्कयति — अन्तर्जालं विना, भवतः दूरवाण्याम् एव।",
    "sat" to "ᱥᱟᱱᱛᱟᱲᱤ ᱛᱮ ᱨᱚᱲ ᱢᱮ, ᱣᱤᱱᱟ ᱫᱚ ᱛᱟᱨᱟᱛᱮ ᱴᱟᱭᱤᱯ ᱠᱟᱛᱮ ᱮᱢᱟᱭ — ᱤᱱᱴᱟᱨᱱᱮᱴ ᱵᱮᱜᱚᱨ, ᱟᱢᱟᱜ ᱯᱷᱚᱱ ᱨᱮ ᱜᱮ᱾",
    "sd" to "سنڌي ۾ ڳالهايو، وينا فوراً ٽائيپ ڪري ڏيندي — انٽرنيٽ کان سواءِ، توهان جي فون تي ئي.",
    "ta" to "தமிழில் பேசுங்கள், வீணா உடனே டைப் செய்து தரும் — இணையம் இல்லாமல், உங்கள் போனிலேயே.",
    "te" to "తెలుగులో మాట్లాడండి, వీణ వెంటనే టైప్ చేస్తుంది — ఇంటర్నెట్ లేకుండా, మీ ఫోన్‌లోనే.",
    "ur" to "اردو میں بولیے، وینا فوراً ٹائپ کر دے گی — بغیر انٹرنیٹ کے، آپ کے فون پر ہی۔",
)

fun sharePitch(code: String): String? = PITCHES[code]

/**
 * What "Share Veena" sends: a line in the sharer's dictation language, then
 * the same idea in English, then the link.
 */
fun shareMessage(language: Language?, url: String): String {
    val english = if (language != null) {
        "I use Veena to turn my ${language.name} speech into text, offline on my phone. " +
            "It works in 22 Indian languages and it's open source."
    } else {
        "I use Veena to turn speech into text, offline on my phone. " +
            "It works in 22 Indian languages and it's open source."
    }
    val pitch = language?.let { sharePitch(it.code) }
    return listOfNotNull(pitch, english, url).joinToString("\n\n")
}
