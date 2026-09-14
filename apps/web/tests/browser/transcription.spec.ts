import { test, expect } from "@playwright/test";
import { readFile, mkdtemp, rm } from "node:fs/promises";
import { resolve, join } from "node:path";
import { tmpdir } from "node:os";
import { LANGUAGES } from "../../src/config/languages.mjs";
import { SITE } from "../../src/config/site.mjs";
import { readPcmWav } from "../../src/lib/transcription/audio";

// A normal profile provides disk-backed OPFS for the larger model. Chromium's
// incognito file system can exhaust its separate memory limit during extraction.
const persistentTest = test.extend({
	context: async ({ playwright, viewport }, runWithContext) => {
		const profile = await mkdtemp(join(tmpdir(), "veena-browser-test-"));
		const context = await playwright.chromium.launchPersistentContext(profile, {
			headless: true,
			viewport,
		});
		try {
			await runWithContext(context);
		} finally {
			await context.close();
			await rm(profile, { recursive: true, force: true });
		}
	},
});

const fixture = resolve("public/audio/telugu_test_audio_16k.wav");
const modelRequest = (url: string) =>
	new URL(url).pathname.startsWith("/models/");
const downloadRequest = (url: string) =>
	/^\/(models|runtime)\//.test(new URL(url).pathname);

// Give Chromium's temporary test profile a stable quota for the production archives.
// This changes real browser storage capacity; downloads and inference stay real.
test.beforeEach(async ({ page, context }) => {
	const cdp = await context.newCDPSession(page);
	await cdp.send("Storage.overrideQuotaForOrigin", {
		origin: "http://127.0.0.1:4321",
		quotaSize: 4 * 1024 ** 3,
	});
});

for (const language of [
	{ slug: "telugu", name: "Telugu", code: "te", size: "125 MB", stale: "hi" },
	{ slug: "hindi", name: "Hindi", code: "hi", size: "479 MB", stale: "te" },
]) {
	test(`${language.name} waits for consent and uses the page language over saved settings`, async ({
		page,
		context,
	}) => {
		const downloads: string[] = [];
		context.on("request", (request) => {
			if (downloadRequest(request.url())) downloads.push(request.url());
		});
		await context.addInitScript((staleLanguage) => {
			localStorage.setItem(
				"veena-browser-settings",
				JSON.stringify({
					language: staleLanguage,
					retention: 50,
					append: false,
				})
			);
		}, language.stale);
		await page.goto(`/speech-to-text/${language.slug}/`);
		await expect(
			page.getByRole("heading", {
				level: 1,
				name: `${language.name} speech to text`,
				exact: true,
			})
		).toBeVisible();
		const setup = page.getByRole("region", {
			name: "Download model",
			exact: true,
		});
		await expect(setup).toBeVisible();
		await expect(setup).toContainText(language.size);
		await expect(setup).toContainText(/only.*once/i);
		await expect(
			setup.getByRole("button", { name: "Download model", exact: true })
		).toBeEnabled();
		await expect(
			setup.getByRole("link", { name: "Or use the app", exact: true })
		).toHaveAttribute("href", /\/download\/?$/);
		await expect(
			page.getByLabel("Speech language", { exact: true })
		).toHaveValue(language.code);
		await expect(page.getByLabel("Voice model", { exact: true })).toHaveCount(
			0
		);
		await expect(page.getByRole("progressbar")).toHaveCount(0);
		await page.waitForLoadState("networkidle");
		expect(downloads).toEqual([]);
		expect(page.workers()).toHaveLength(0);
		if (language.code !== "te") {
			await expect(
				page.getByRole("button", { name: /Telugu sample/ })
			).toHaveCount(0);
		}
	});
}

test("changing speech language opens its language page without downloading a model", async ({
	page,
	context,
}) => {
	const downloads: string[] = [];
	context.on("request", (request) => {
		if (downloadRequest(request.url())) downloads.push(request.url());
	});
	await page.goto("/speech-to-text/telugu/");
	await expect(
		page.getByRole("button", { name: "Download model", exact: true })
	).toBeEnabled();
	await page.getByLabel("Speech language", { exact: true }).selectOption("hi");
	await expect(page).toHaveURL(/\/speech-to-text\/hindi\/$/);
	await expect(page.getByLabel("Speech language", { exact: true })).toHaveValue(
		"hi"
	);
	await expect(
		page.getByRole("region", { name: "Download model", exact: true })
	).toContainText("479 MB");
	await expect(page.getByRole("button", { name: /Telugu sample/ })).toHaveCount(
		0
	);
	await page.getByLabel("Speech language", { exact: true }).selectOption("bn");
	await expect(page).toHaveURL(/\/speech-to-text\/bengali\/$/);
	await expect(page.getByLabel("Speech language", { exact: true })).toHaveValue(
		"bn"
	);
	await expect(
		page.getByRole("button", { name: "Download model", exact: true })
	).toBeEnabled();
	expect(downloads).toEqual([]);
});

test.describe("download feedback", () => {
	// Only network failures are mocked; no model, readiness, or transcript is fabricated.
	test.use({ serviceWorkers: "block" });

	test("consent shows progress, locks language, and offers retry after a failed download", async ({
		page,
		context,
	}) => {
		let releaseRequest!: (route: import("@playwright/test").Route) => void;
		const pendingRequest = new Promise<import("@playwright/test").Route>(
			(resolve) => {
				releaseRequest = resolve;
			}
		);
		await context.route("**/models/**", (route) => releaseRequest(route));
		await page.goto("/speech-to-text/hindi/");
		await page
			.getByRole("button", { name: "Download model", exact: true })
			.click();
		const route = await pendingRequest;
		expect(route.request().url()).toContain(
			"indicconformer_multi_ctc_int8.tar.gz"
		);
		await expect(
			page.getByRole("progressbar", { name: "Model download progress" })
		).toBeVisible();
		await expect(
			page.getByLabel("Speech language", { exact: true })
		).toBeDisabled();
		await expect(
			page.getByRole("button", { name: "Cancel download", exact: true })
		).toBeVisible();
		await route.fulfill({ status: 503, body: "Unavailable" });
		await expect(page.getByRole("alert")).toContainText(
			/download failed.*503/i
		);
		await expect(
			page.getByRole("button", { name: "Download model", exact: true })
		).toBeEnabled();
		await expect(
			page.getByLabel("Speech language", { exact: true })
		).toBeEnabled();
		await expect(page.getByRole("progressbar")).toHaveCount(0);
	});

	test("a cancelled download returns to setup without loading a model", async ({
		page,
		context,
	}) => {
		let releaseRequest!: (route: import("@playwright/test").Route) => void;
		const pendingRequest = new Promise<import("@playwright/test").Route>(
			(resolve) => {
				releaseRequest = resolve;
			}
		);
		await context.route("**/models/**", (route) => releaseRequest(route));
		await page.goto("/speech-to-text/telugu/");
		await page
			.getByRole("button", { name: "Download model", exact: true })
			.click();
		const route = await pendingRequest;
		await expect(
			page.getByRole("progressbar", { name: "Model download progress" })
		).toBeVisible();
		await page
			.getByRole("button", { name: "Cancel download", exact: true })
			.click();
		await route.abort().catch(() => {});
		await expect(
			page.getByRole("button", { name: "Download model", exact: true })
		).toBeEnabled();
		await expect(page.getByRole("progressbar")).toHaveCount(0);
		await expect(
			page.getByLabel("Speech language", { exact: true })
		).toBeEnabled();
	});
});

test("all language pages have server-rendered headings and indexable canonical URLs", async ({
	request,
}) => {
	for (const language of LANGUAGES) {
		const response = await request.get(`/speech-to-text/${language.slug}/`);
		expect(response.ok(), language.slug).toBe(true);
		const html = await response.text();
		const headings = [...html.matchAll(/<h1\b[^>]*>([\s\S]*?)<\/h1>/g)].map(
			(match) =>
				match[1]
					.replace(/<[^>]+>/g, "")
					.replace(/\s+/g, " ")
					.trim()
		);
		expect(headings, language.slug).toEqual([
			`${language.name} speech to text`,
		]);
		expect(
			html.match(/<link\b[^>]*rel="canonical"[^>]*>/)?.[0],
			language.slug
		).toContain(`href="${SITE.url}/speech-to-text/${language.slug}/"`);
		expect(
			html.match(/<meta\b[^>]*name="robots"[^>]*>/)?.[0],
			language.slug
		).toContain('content="index, follow"');
		expect(
			html.match(/<meta\b[^>]*name="description"[^>]*>/)?.[0],
			language.slug
		).toMatch(/content="[^"]{40,}"/);
	}
});

test.describe("mobile app handoff", () => {
	test.use({
		viewport: { width: 390, height: 844 },
		userAgent:
			"Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 Version/26.0 Mobile/15E148 Safari/604.1",
	});

	test("mobile visitors get app links and readable language content before any download", async ({
		page,
		context,
	}) => {
		const downloads: string[] = [];
		context.on("request", (request) => {
			if (downloadRequest(request.url())) downloads.push(request.url());
		});
		await page.goto("/speech-to-text/hindi/");
		await expect(
			page.getByRole("heading", { level: 1, name: "Hindi speech to text" })
		).toBeVisible();
		await expect(
			page.getByRole("heading", { name: "Take Veena with you." })
		).toBeVisible();
		await expect(
			page.getByRole("link", { name: "Get iPhone app", exact: true })
		).toHaveAttribute("href", /https:\/\/apps\.apple\.com\//);
		await expect(
			page.getByRole("button", { name: "Download model", exact: true })
		).toHaveCount(0);
		await page.waitForLoadState("networkidle");
		expect(downloads).toEqual([]);
		expect(
			await page.evaluate(
				() => document.documentElement.scrollWidth <= window.innerWidth
			)
		).toBe(true);
	});
});

// These tests download the production archives and run real ONNX Runtime Web inference.
test("production Telugu transcription survives automatic offline reload and requires consent after cache deletion", async ({
	page,
	context,
}) => {
	test.setTimeout(300_000);
	const expected = JSON.parse(
		await readFile("tests/fixtures/expected.json", "utf8")
	);
	const models: string[] = [];
	const writes: string[] = [];
	context.on("request", (request) => {
		if (modelRequest(request.url())) models.push(request.url());
		if (!["GET", "HEAD"].includes(request.method()))
			writes.push(`${request.method()} ${request.url()}`);
	});
	await page.goto("/speech-to-text/telugu/");
	await page
		.getByRole("button", { name: "Download model", exact: true })
		.click();
	await expect(
		page.getByLabel("Choose audio file", { exact: true })
	).toBeEnabled({ timeout: 120_000 });
	await page
		.getByLabel("Choose audio file", { exact: true })
		.setInputFiles(fixture);
	await expect(
		page.getByLabel("Speech language", { exact: true })
	).toBeDisabled();
	await expect(page.getByLabel("Transcript", { exact: true })).toHaveValue(
		expected.telugu,
		{
			timeout: 90_000,
		}
	);
	await expect(page.getByTestId("transcription-timing")).toContainText(
		"1 part"
	);
	await page.evaluate(async () => {
		await navigator.serviceWorker.ready;
	});
	expect(models).toHaveLength(1);
	await context.setOffline(true);
	await page.reload();
	await expect(
		page.getByLabel("Choose audio file", { exact: true })
	).toBeEnabled({ timeout: 60_000 });
	await expect(page.getByLabel("Speech language", { exact: true })).toHaveValue(
		"te"
	);
	await page
		.getByRole("button", { name: "Try the 23-second Telugu sample" })
		.click();
	await expect(page.getByLabel("Transcript", { exact: true })).toHaveValue(
		expected.telugu,
		{
			timeout: 90_000,
		}
	);
	expect(models).toHaveLength(1);
	await context.setOffline(false);
	await page.evaluate(async () => {
		const root = await navigator.storage.getDirectory();
		await root.removeEntry("veena-models", { recursive: true });
	});
	await page.reload();
	await expect(
		page.getByRole("button", { name: "Download model", exact: true })
	).toBeEnabled();
	await page.waitForLoadState("networkidle");
	expect(models).toHaveLength(1);
	expect(page.workers()).toHaveLength(0);
	expect(writes).toEqual([]);
});

persistentTest(
	"production multilingual worker matches the fixture and shares its saved download across language pages",
	async ({ page, context }) => {
		test.setTimeout(300_000);
		const expected = JSON.parse(
			await readFile("tests/fixtures/expected.json", "utf8")
		);
		const wav = await readFile(fixture);
		const pcm = readPcmWav(
			wav.buffer.slice(wav.byteOffset, wav.byteOffset + wav.byteLength)
		);
		expect(pcm).toBeDefined();
		const models: string[] = [];
		context.on("request", (request) => {
			if (modelRequest(request.url())) models.push(request.url());
		});
		await page.goto("/speech-to-text/hindi/");
		const workerStarted = page.waitForEvent("worker");
		await page
			.getByRole("button", { name: "Download model", exact: true })
			.click();
		const worker = await workerStarted;
		await expect(
			page.getByLabel("Choose audio file", { exact: true })
		).toBeEnabled({ timeout: 150_000 });
		await expect(
			page.getByLabel("Speech language", { exact: true })
		).toHaveValue("hi");
		// The supplied golden fixture is Telugu. Exercise that decoder directly in the
		// real loaded worker; the Hindi UI stays Hindi and no transcript is mocked.
		const result = await worker.evaluate(async (audio) => {
			type Reply = {
				id: number;
				type: string;
				text?: string;
				model?: string;
				error?: string;
			};
			const runtime = globalThis as unknown as {
				postMessage: (message: Reply) => void;
				onmessage: (event: MessageEvent) => Promise<void>;
			};
			const requestId = 987654;
			const original = runtime.postMessage;
			let answer: Reply | undefined;
			runtime.postMessage = (message) => {
				if (
					message.id === requestId &&
					["result", "error"].includes(message.type)
				)
					answer = message;
				original.call(globalThis, message);
			};
			try {
				await runtime.onmessage(
					new MessageEvent("message", {
						data: {
							id: requestId,
							type: "transcribe",
							language: "te",
							audio: Float32Array.from(audio).buffer,
						},
					})
				);
			} finally {
				runtime.postMessage = original;
			}
			if (!answer || answer.type === "error")
				throw new Error(answer?.error || "No inference result");
			return answer;
		}, Array.from(pcm!));
		expect(result.model).toBe("multilingual");
		expect(result.text).toBe(expected.multilingual);
		expect(models).toHaveLength(1);
		await page
			.getByLabel("Speech language", { exact: true })
			.selectOption("bn");
		await expect(page).toHaveURL(/\/speech-to-text\/bengali\/$/);
		await expect(
			page.getByLabel("Speech language", { exact: true })
		).toHaveValue("bn");
		await expect(
			page.getByLabel("Choose audio file", { exact: true })
		).toBeEnabled({ timeout: 90_000 });
		await expect(
			page.getByRole("button", { name: /Telugu sample/ })
		).toHaveCount(0);
		expect(models).toHaveLength(1);
	}
);
