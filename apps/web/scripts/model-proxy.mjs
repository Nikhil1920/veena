// Delivery only: this endpoint never accepts audio or runs transcription.
export const modelArchives = new Set([
	"indicconformer_stt_te_ctc_static_qdq_int8.tar.gz",
	"indicconformer_multi_ctc_int8.tar.gz",
]);

export async function proxyModel(request) {
	const name = new URL(request.url).pathname.slice("/models/".length);
	if (!modelArchives.has(name))
		return new Response("Model not found", { status: 404 });
	if (!["GET", "HEAD"].includes(request.method))
		return new Response("Method not allowed", {
			status: 405,
			headers: { Allow: "GET, HEAD" },
		});
	const headers = new Headers();
	const range = request.headers.get("Range");
	if (range) {
		if (!/^bytes=\d+-\d*$/.test(range))
			return new Response("Invalid range", { status: 416 });
		headers.set("Range", range);
	}
	const response = await fetch(`https://dl.byanr.com/veena-byanr/${name}`, {
		method: request.method,
		headers,
		signal: request.signal,
		redirect: "error",
	});
	const outgoing = new Headers();
	for (const key of [
		"Content-Length",
		"Content-Range",
		"ETag",
		"Last-Modified",
		"Accept-Ranges",
	]) {
		if (response.headers.has(key)) outgoing.set(key, response.headers.get(key));
	}
	outgoing.set("Content-Type", "application/gzip");
	outgoing.set("Cross-Origin-Resource-Policy", "same-origin");
	outgoing.set("Cache-Control", "public, max-age=86400");
	return new Response(response.body, {
		status: response.status,
		headers: outgoing,
	});
}

export function isolationHeaders(response) {
	const result = new Response(response.body, response);
	result.headers.set("Cross-Origin-Opener-Policy", "same-origin");
	result.headers.set("Cross-Origin-Embedder-Policy", "require-corp");
	result.headers.set("Cross-Origin-Resource-Policy", "same-origin");
	return result;
}
