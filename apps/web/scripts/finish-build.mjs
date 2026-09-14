import { readFile, writeFile, mkdir, cp, readdir } from "node:fs/promises";
import { createHash } from "node:crypto";
import { resolve } from "node:path";

async function files(dir) {
	const entries = await readdir(dir, { withFileTypes: true });
	return (
		await Promise.all(
			entries.map(async (entry) =>
				entry.isDirectory()
					? files(`${dir}/${entry.name}`)
					: [`${dir}/${entry.name}`]
			)
		)
	).flat();
}

const paths = (await files("dist")).filter(
	(path) =>
		path.startsWith("dist/_assets/") ||
		path.startsWith("dist/runtime/") ||
		path.startsWith("dist/audio/") ||
		path.startsWith("dist/fonts/") ||
		path.startsWith("dist/brand/") ||
		path === "dist/transcribe/index.html" ||
		(path.startsWith("dist/speech-to-text/") && path.endsWith("/index.html")) ||
		path === "dist/favicon.svg"
);
const hash = createHash("sha256");
for (const path of paths.sort()) {
	hash.update(path);
	hash.update(await readFile(path));
}
const version = hash.digest("hex").slice(0, 16);
const precache = paths.map((path) => path.slice(4).replace(/index\.html$/, ""));
const template = await readFile("scripts/transcription-sw.template.js", "utf8");
await writeFile(
	"dist/transcription-sw.js",
	template
		.replace("__VERSION__", version)
		.replace("__PRECACHE__", JSON.stringify(precache))
);

// The normal Astro static output remains directly deployable. Sites additionally
// uses this small Worker for same-origin streaming of the existing model CDN.
await mkdir("dist/server", { recursive: true });
const proxy = await readFile("scripts/model-proxy.mjs", "utf8");
const worker = (await readFile("scripts/site-worker.mjs", "utf8")).replace(
	/^import .*;\n/,
	""
);
await writeFile("dist/server/index.js", `${proxy}\n${worker}`);
await mkdir("dist/.openai", { recursive: true });
await cp(".openai/hosting.json", "dist/.openai/hosting.json");
await mkdir("dist/client", { recursive: true });
for (const entry of await readdir("dist", { withFileTypes: true })) {
	if (["client", "server", ".openai"].includes(entry.name)) continue;
	await cp(resolve("dist", entry.name), resolve("dist/client", entry.name), {
		recursive: true,
	});
}
console.log(`Prepared offline app cache ${version} and model-delivery Worker.`);
