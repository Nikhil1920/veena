// @ts-check
import { defineConfig } from "astro/config";
import react from "@astrojs/react";
import icon from "astro-icon";
import mdx from "@astrojs/mdx";
import sitemap from "@astrojs/sitemap";
import tailwindcss from "@tailwindcss/vite";
import { SITE } from "./src/config/site.mjs";

// https://astro.build/config
export default defineConfig({
	site: SITE.url,
	integrations: [
		react(),
		icon(),
		mdx(),
		sitemap({
			// /transcribe is an app shell; /1/ to /5/ are noindexed design iterations.
			filter: (page) => {
				const path = new URL(page).pathname;
				return !path.startsWith("/transcribe") && !/^\/\d+\/$/.test(path);
			},
			changefreq: "weekly",
			priority: 0.7,
		}),
	],
	vite: {
		plugins: [tailwindcss()],
		server: {
			headers: {
				"Cross-Origin-Opener-Policy": "same-origin",
				"Cross-Origin-Embedder-Policy": "require-corp",
			},
			proxy: {
				"^/models/(indicconformer_stt_te_ctc_static_qdq_int8|indicconformer_multi_ctc_int8)\\.tar\\.gz$":
					{
						target: "https://dl.byanr.com",
						changeOrigin: true,
						rewrite: (path) => path.replace("/models/", "/veena-byanr/"),
					},
			},
		},
		build: {
			cssMinify: "lightningcss",
			minify: "terser",
			terserOptions: {
				compress: {
					drop_console: true,
					drop_debugger: true,
				},
			},
		},
	},
	build: {
		inlineStylesheets: "auto",
		assets: "_assets",
	},
	compressHTML: true,
	image: {
		domains: [],
		remotePatterns: [],
	},
});
