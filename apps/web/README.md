# veena-web

Marketing website and desktop-browser transcription workspace for **Veena**, the offline speech-to-text app for all 22 scheduled languages of India. Open `/transcribe/` for browser transcription. See [browser implementation and validation](BROWSER_TRANSCRIPTION.md).

Built with Astro 7, React 19, Tailwind CSS v4, and MDX-ready content collections.
Static pages with a model-delivery Worker, sitemap, robots.txt, RSS, Open Graph/Twitter meta, and
JSON-LD structured data (WebSite, Organization via the layout; Article,
BlogPosting, SoftwareApplication, MobileApplication, FAQPage, BreadcrumbList
per page).

## Content model

Two content collections (defined in `src/content.config.ts`):

| Collection  | Location                          | Rendered at               |
| ----------- | --------------------------------- | ------------------------- |
| `languages` | `src/content/languages/<slug>.md` | `/speech-to-text/<slug>/` |
| `blog`      | `src/content/blog/<slug>.md`      | `/blog/<slug>/`           |

Canonical language facts (name, ISO code, native name, script, speaker
count, regions) live in `src/config/languages.mjs` — the source of truth for
the language hub page and the fact panel on every language page. The markdown
entries add the page title, meta description, keywords, FAQ, and body prose.

Frontmatter fields:

- **languages**: `title`, `description`, `keywords[]`, optional `faq[]`
  (`q`, `a`)
- **blog**: `title`, `description`, `pubDate` (YYYY-MM-DD), optional
  `updatedDate`, `tags[]`, optional `faq[]`, `draft` (default false)

FAQ items are rendered both as a visible section and as FAQPage JSON-LD —
do not repeat FAQ questions in the markdown body.

## Site config

`src/config/site.mjs` holds name, title, description, canonical URL
(`https://veena.byanr.com`), author, contact email, and `DOWNLOADS` — the
store links (Google Play, App Store) and the Windows installer details. Every
download button on the site comes from there. macOS and Linux desktop builds
are not published yet, so no installers are linked for them.

Store badges live in `src/assets/` and are rendered by
`src/components/StoreButtons.astro` (`variant="badges" | "links"`,
`tone="dark" | "light"`, `windows={false}` to hide the Windows button).

## Pages

- `/` home: hero with store buttons, how-it-works strip, 22-language grid,
  features, CTA. SoftwareApplication JSON-LD.
- `/transcribe/`: local browser recording, file import, transcription, history and text export.
- `/download/`: transactional hub for all three platforms + setup steps + FAQ.
- `/android/`, `/ios/`: platform pages targeting mobile app queries;
  MobileApplication JSON-LD.
- `/windows/`: desktop page with Windows-specific FAQ; links the installer.
- `/speech-to-text/`: hub for the 22 language pages (the SEO core).
- `/features/`: feature cards + cloud-vs-Veena comparison table.
- `/how-it-works/`: technical explainer (IndicConformer, masked decoding,
  quantization). Differentiated from the blog's how-to guides.
- `/privacy/`: plain-language privacy statement (matches the store data
  safety declarations).
- `/blog/`: guides; `/rss.xml` feed for it.
- `/about/`, `/404`.

## Facts to keep true

Copy is grounded in the actual apps. When the apps change, check these claims:

- Models download during first-run setup (not bundled): Telugu ~125 MB,
  multilingual (21 other languages) ~480 MB.
- Telugu is the only language with a dedicated model; the rest share one
  multilingual IndicConformer model, each decoding through its own vocabulary.
- After the model download, transcription needs no internet at all.
- Mobile: hold/tap dictation, audio file import, local history with replay,
  copy/share. Desktop (Windows): global shortcut, paste into any app,
  custom-word correction, local history with retention settings.
- No account, no telemetry, no audio uploads.

## Development

```bash
pnpm install
pnpm dev        # http://localhost:4321
pnpm build      # pages, local runtime, offline cache and delivery Worker in dist/
pnpm preview    # http://127.0.0.1:4321/transcribe/
pnpm test:transcription # real fixture preprocessing and decoder regressions
pnpm check      # type-check + lint + format check
```

## Adding a language page

1. Add a `<slug>.md` entry under `src/content/languages/`.
2. Facts come automatically from `src/config/languages.mjs` — add the slug
   there first if the language is new.
3. The page, its canonical URL, breadcrumbs, Article + FAQPage JSON-LD,
   download CTA, and sibling-language links are all generated from the entry.

## SEO notes

- The sitemap integration emits `sitemap-index.xml`; `robots.txt` points to it.
- Every page renders canonical URL (trailing slash), meta description, OG and
  Twitter tags, and site-wide WebSite JSON-LD.
- Language pages emit `Article` + `FAQPage` + `BreadcrumbList` JSON-LD;
  blog posts emit `BlogPosting` + optional `FAQPage` + `BreadcrumbList`.
- Internal linking is deliberate: the homepage and language hub link to all
  22 language pages, each language page links to sibling languages plus a
  store-button CTA, and header/footer carry Download on every page.

## Production deployment

The website is deployed at https://veena.byanr.com through the Cloudflare Pages project `veena`, configured in `wrangler.jsonc`. Use `pnpm deploy` to build and update the Pages deployment. The ChatGPT Sites deployment is retired.

Speech models stay on dl.byanr.com, using the exact native-app archive URLs. Only the website, ONNX Runtime execution engine, and sample audio are deployed as web assets. See BROWSER_TRANSCRIPTION.md for the streaming model-delivery route and download verification.
