const APP_CACHE = "veena-app-__VERSION__";
const PRECACHE = __PRECACHE__;

self.addEventListener("install", (event) => {
	event.waitUntil(
		(async () => {
			const cache = await caches.open(APP_CACHE);
			// Sequential requests avoid holding multiple large WASM responses in memory.
			for (const url of PRECACHE) await cache.add(url);
			// Wait for existing tabs to close before replacing their runtime assets.
		})()
	);
});

self.addEventListener("activate", (event) => {
	event.waitUntil(
		(async () => {
			for (const key of await caches.keys())
				if (key.startsWith("veena-app-") && key !== APP_CACHE)
					await caches.delete(key);
			await self.clients.claim();
		})()
	);
});

self.addEventListener("fetch", (event) => {
	const request = event.request;
	const url = new URL(request.url);
	if (
		request.method !== "GET" ||
		url.origin !== self.location.origin ||
		url.pathname.startsWith("/models/")
	)
		return;
	const pagePath = `${url.pathname.replace(/\/$/, "")}/`;
	if (request.mode === "navigate" && PRECACHE.includes(pagePath)) {
		event.respondWith(
			(async () => {
				const response = await (await caches.open(APP_CACHE)).match(pagePath);
				return response || fetch(request);
			})()
		);
		return;
	}
	if (PRECACHE.includes(url.pathname))
		event.respondWith(
			caches
				.open(APP_CACHE)
				.then(async (cache) => (await cache.match(request)) || fetch(request))
		);
});
