import rss from "@astrojs/rss";
import { getCollection } from "astro:content";
import type { APIContext } from "astro";
import { SITE } from "../config/site.mjs";

export async function GET(context: APIContext) {
	const posts = (await getCollection("blog"))
		.filter((post) => !post.data.draft)
		.sort((a, b) => b.data.pubDate.valueOf() - a.data.pubDate.valueOf());

	return rss({
		title: `Blog & Guides | ${SITE.title}`,
		description:
			"Guides and articles about speech to text for Indian languages — voice typing, offline transcription, privacy, and how Veena works.",
		site: context.site ?? SITE.url,
		items: posts.map((post) => ({
			title: post.data.title,
			description: post.data.description,
			pubDate: post.data.pubDate,
			link: `/blog/${post.id}/`,
		})),
		customData: "<language>en-in</language>",
	});
}
