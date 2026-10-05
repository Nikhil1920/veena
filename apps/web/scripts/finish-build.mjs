import { readFile, writeFile, readdir } from "node:fs/promises";
import { createHash } from "node:crypto";

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
// Cached worker responses must receive updated isolation headers after a deploy.
hash.update(await readFile("dist/_headers"));
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

console.log(`Prepared offline app cache ${version}.`);

// Pages advanced mode serves the site and streams the existing native CDN archives.
const proxy = await readFile("scripts/model-proxy.mjs", "utf8");
const worker = (
	await readFile("scripts/cloudflare-worker.mjs", "utf8")
).replace(/^import .*;\n/, "");
await writeFile("dist/_worker.js", `${proxy}\n${worker}`);
await writeFile(
	"dist/_routes.json",
	JSON.stringify({ version: 1, include: ["/models/*"], exclude: [] })
);
