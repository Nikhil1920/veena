import { createServer } from "node:http";
import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import { resolve, extname } from "node:path";
import { Readable } from "node:stream";
import { modelArchives, proxyModel } from "./model-proxy.mjs";

const root = resolve("dist");
const port = Number(process.env.PORT || 4321);
const mime = {
	".html": "text/html; charset=utf-8",
	".js": "text/javascript",
	".mjs": "text/javascript",
	".css": "text/css",
	".wasm": "application/wasm",
	".wav": "audio/wav",
	".svg": "image/svg+xml",
	".png": "image/png",
	".json": "application/json",
};

async function serveFile(filename, request, response, type) {
	const info = await stat(filename);
	let start = 0,
		end = info.size - 1;
	const range = request.headers.range?.match(/^bytes=(\d+)-(\d*)$/);
	if (range) {
		start = Number(range[1]);
		if (range[2]) end = Math.min(end, Number(range[2]));
		if (start > end || start >= info.size) {
			response.writeHead(416, { "Content-Range": `bytes */${info.size}` });
			response.end();
			return;
		}
		response.setHeader("Content-Range", `bytes ${start}-${end}/${info.size}`);
	}
	response.writeHead(range ? 206 : 200, {
		"Content-Type":
			type || mime[extname(filename)] || "application/octet-stream",
		"Content-Length": end - start + 1,
		"Accept-Ranges": "bytes",
	});
	if (request.method === "HEAD") {
		response.end();
		return;
	}
	const stream = createReadStream(filename, { start, end });
	response.on("close", () => stream.destroy());
	stream.pipe(response);
}

createServer(async (request, response) => {
	response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
	response.setHeader("Cross-Origin-Embedder-Policy", "require-corp");
	response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
	if (!["GET", "HEAD"].includes(request.method)) {
		response.writeHead(405);
		response.end();
		return;
	}
	const url = new URL(request.url, `http://localhost:${port}`);
	try {
		if (url.pathname.startsWith("/models/")) {
			const name = url.pathname.slice(8);
			if (!modelArchives.has(name)) {
				response.writeHead(404);
				response.end();
				return;
			}
			// Optional fixtures accelerate local validation; no model enters the Site bundle.
			const paths = [
				resolve("../../tools/voice-models/models", name),
				resolve("../../tools/voice-models/browser-assets/archives", name),
			];
			for (const path of paths) {
				if (await stat(path).catch(() => null)) {
					await serveFile(path, request, response, "application/gzip");
					return;
				}
			}
			const upstream = await proxyModel(
				new Request(url, { method: request.method, headers: request.headers })
			);
			response.writeHead(upstream.status, Object.fromEntries(upstream.headers));
			if (upstream.body) Readable.fromWeb(upstream.body).pipe(response);
			else response.end();
			return;
		}
		let filename = resolve(root, `.${decodeURIComponent(url.pathname)}`);
		if (!filename.startsWith(`${root}/`) && filename !== root) {
			response.writeHead(403);
			response.end();
			return;
		}
		if ((await stat(filename).catch(() => null))?.isDirectory())
			filename = resolve(filename, "index.html");
		await serveFile(filename, request, response);
	} catch (error) {
		if (!response.headersSent)
			response.writeHead(error.code === "ENOENT" ? 404 : 500);
		response.end("Not available");
	}
}).listen(port, "127.0.0.1", () =>
	console.log(`Local: http://127.0.0.1:${port}/transcribe/`)
);
