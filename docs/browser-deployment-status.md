# Browser transcription deployment status

Updated 22 September 2026.

Veena is deployed at [veena.byanr.com](https://veena.byanr.com) through the Cloudflare Pages project `veena`. Its Pages hostname is [veena-1dl.pages.dev](https://veena-1dl.pages.dev).

The old ChatGPT Site was permanently deleted, and its local hosting manifest was removed. The prior Cloudflare Worker `restless-fire-cc09` has been detached from `veena.byanr.com`.

Both speech models use the exact dl.byanr.com archives used by the native applications. Pages streams these downloads through the same-origin `/models/` route because the CDN does not provide browser CORS headers. Model weights and archives are not included in web assets.

Static pages and runtime assets respond with the browser isolation headers needed for WASM workers. Both model archives support HEAD and resumable byte-range downloads. Future updates use `pnpm deploy` from `apps/web`.

See [implementation and validation notes](../apps/web/BROWSER_TRANSCRIPTION.md) for model details, sample results, and browser limits.
