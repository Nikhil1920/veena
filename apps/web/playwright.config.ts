import { defineConfig } from "@playwright/test";

export default defineConfig({
	testDir: "./tests/browser",
	outputDir: "./tests/browser/results",
	workers: 1,
	use: {
		baseURL: "http://127.0.0.1:4321",
		viewport: { width: 1440, height: 1000 },
		screenshot: "only-on-failure",
		trace: "retain-on-failure",
	},
	webServer: {
		command: "pnpm run preview:transcription",
		url: "http://127.0.0.1:4321/transcribe/",
		reuseExistingServer: true,
	},
});
