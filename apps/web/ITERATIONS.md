# Landing page iterations — design notes

Five complete landing-page concepts for Veena, served at `/1` … `/5`.
A floating dock (bottom-centre) switches between them; ← / → arrow keys also work.
Each page is a self-contained `.astro` file with its own fonts and scripts,
`noindex`, and a shared `src/components/iterations/Switcher.astro` dock.

All copy is grounded in `src/config/site.mjs` and `src/config/languages.mjs`
(model sizes, store links, the 22 scheduled languages with native names,
scripts, census speaker estimates). Keep those as the source of truth when
editing copy.

## The five concepts

| # | Name | Idea | Type |
|---|------|------|------|
| 1 | Stage | Concert hall at night; a live waveform-string "sings" a word in a different script each pass. Hold the brass key. | Fraunces (serif, opsz axis) |
| 2 | Specimen | Type-foundry specimen sheet: all 22 native scripts are the artwork — a wall of greetings set in themselves. | Bricolage Grotesque + Noto script families |
| 3 | Register | Government census register / case file: the Eighth Schedule entered row by row, vermillion stamps, clerk's pencil notes. | Spectral + Caveat |
| 4 | Schematic | Engineering cutaway of the on-device pipeline; the network path is drawn dashed and crossed out — the diagram is the privacy argument. | IBM Plex Sans/Mono |
| 5 | Poster | Indian matchbox-label energy: stacked colour bands, hard offset shadows, hold-the-mic moment, greetings marquee. | Baloo 2 + Anek Latin (Ek Type, Indian foundry) |

## Native-script font stack (reusable)

```css
font-family: "Noto Sans Bengali", "Noto Sans Devanagari", "Noto Sans Tamil",
  "Noto Sans Telugu", "Noto Sans Kannada", "Noto Sans Malayalam",
  "Noto Sans Gurmukhi", "Noto Sans Gujarati", "Noto Sans Oriya",
  "Noto Sans Arabic", "Noto Sans Meetei Mayek", "Noto Sans Ol Chiki", sans-serif;
```

One stack covers every supported script — the browser picks per glyph.
Urdu/Sindhi/Kashmiri tiles need `dir="rtl"`.

## Gotchas learned the hard way

- `display: grid/flex` on an element with the `hidden` attribute overrides the
  UA's `display: none` — always add a `.x[hidden] { display: none }` rule.
- Native strings must be copied verbatim from `languages.mjs` (Santali's Ol
  Chiki word differs by one glyph from what looks right).
- Astro scoped styles keep class names, so `document.querySelector(".dock-badge")`
  works from component scripts.

## Motion budget (one moment per page)

1. canvas wave + word cycling (auto-demo once, then hold-to-speak)
2. cycling hero word (2.6 s interval)
3. none — the register is deliberately still; hover only
4. SMIL pulses along the pipeline (removed entirely under reduced motion)
5. greetings marquee + hold-to-speak ring fill and speech-bubble pop

All pages: `prefers-reduced-motion` honoured, visible focus rings, `noindex`.
