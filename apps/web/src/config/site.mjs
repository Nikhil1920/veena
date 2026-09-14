export const SITE = {
	name: "Veena",
	// Short name used in <title> suffixes and og:site_name.
	shortName: "Veena",
	title: "Veena — Offline Speech to Text for All 22 Indian Languages",
	description:
		"Veena turns speech into text in all 22 scheduled languages of India — Telugu, Hindi, Tamil, Bengali and more — entirely on your device. Free, no account, works offline.",
	defaultLanguage: "en",
	url: "https://veena.byanr.com",
	author: "Nikhil Reddy Avuthu",
	ogImage: "/og-image.png",
	ogImageAlt:
		"Veena — offline speech to text for all 22 scheduled languages of India",
	email: "anr.appcontact@gmail.com",
};

/**
 * Where Veena ships today. Every download link on the site comes from here
 * so the store listings and artifacts stay consistent.
 * macOS and Linux desktop builds are in development — do not link installers
 * for them until they are published.
 */
export const DOWNLOADS = {
	playStore: "https://play.google.com/store/apps/details?id=com.byanr.veena",
	appStore: "https://apps.apple.com/us/app/id6769981675",
	windows: {
		url: "https://dl.byanr.com/veena-byanr/desktop/Veena_0.0.2_x64-setup.exe",
		version: "0.0.2",
		requirement: "Windows 10 or 11, 64-bit",
		size: "about 18 MB",
	},
	android: {
		requirement: "Android 8.0 or later",
		/** Dedicated Telugu model; every other language uses the multilingual one. */
		modelDownload: "about 125 MB (Telugu) or about 480 MB (all 22 languages)",
	},
	ios: {
		// Deliberately no pinned iOS version here: the App Store listing is
		// authoritative for device requirements and changes per release.
		requirement: "iPhone",
	},
};
