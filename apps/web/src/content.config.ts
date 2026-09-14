import { defineCollection, z } from "astro:content";
import { glob } from "astro/loaders";

const faqSchema = z.array(
	z.object({
		q: z.string(),
		a: z.string(),
	})
);

/**
 * Blog articles — long-form SEO content about speech to text, voice typing,
 * Indian languages, and how Veena works. Rendered at /blog/<slug>.
 */
const blog = defineCollection({
	loader: glob({ pattern: "**/*.md", base: "./src/content/blog" }),
	schema: z.object({
		title: z.string(),
		description: z.string(),
		pubDate: z.coerce.date(),
		updatedDate: z.coerce.date().optional(),
		tags: z.array(z.string()).default([]),
		draft: z.boolean().default(false),
		faq: faqSchema.optional(),
	}),
});

/**
 * Per-language speech to text pages — one entry per scheduled language,
 * rendered at /speech-to-text/<slug>. Canonical facts (speakers, script,
 * regions, ISO code) live in src/config/languages.mjs; each entry here
 * supplies the page-specific title, meta description, FAQ, and the body.
 */
const languages = defineCollection({
	loader: glob({ pattern: "**/*.md", base: "./src/content/languages" }),
	schema: z.object({
		title: z.string(),
		description: z.string(),
		keywords: z.array(z.string()).default([]),
		faq: faqSchema.optional(),
	}),
});

export const collections = { blog, languages };
