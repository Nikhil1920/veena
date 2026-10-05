import { isolationHeaders, proxyModel } from "./model-proxy.mjs";

export default {
	async fetch(request, env) {
		const url = new URL(request.url);
		try {
			if (url.pathname.startsWith("/models/")) return await proxyModel(request);
			const response = await env.ASSETS.fetch(request);
			return isolationHeaders(response);
		} catch (error) {
			console.error("Model delivery failed:", error);
			return new Response(
				"The download service is temporarily unavailable. Please retry.",
				{ status: 502 }
			);
		}
	},
};
