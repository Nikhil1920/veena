# Veena visual identity

Veena turns speech in the 22 scheduled languages of India into private, editable text. The design puts the choice of language first, with native scripts as useful visual content. The memorable element is generous, rounded typography paired with the instrument mark. Supporting pages stay quiet and readable.

Palette: paper white #FFFFFF, jade #164C40, ink #213630, sage #60746B, pale green #F2F7F4, brass #DEA947. Bricolage Grotesque carries English display headings; DM Sans handles interface and reading text. Fonts are self-hosted, with system fallbacks for Indian scripts.

Layout plan (reviewed before implementation):

```
[veena mark + wordmark]              [Languages  Why Veena  Guides] [Get the app]

[Your voice.       ]                [Choose your language                   ]
[Your language.   ]                [తెలుగు Telugu    हिन्दी Hindi          ]
[Your words.      ]                [தமிழ் Tamil      বাংলা Bengali         ]
[plain explanation]                [ಕನ್ನಡ Kannada    मराठी Marathi        ]
[app action       ]                [All 22 languages                       ]

[From a thought to a paragraph.]    [1 Download] [2 Speak] [3 Keep]
[Your words stay with you.         privacy statement + practical link     ]
[compact footer                                                         ]
```

All content is left aligned. Language links open real tools. A sequence is numbered only when it describes actual setup steps. Avoid decorative waveforms, invented transcript mockups, repeated card grids, automatic motion, and large closing sales blocks. The original proposal's generic hero/mockup was replaced by a working language directory because language choice is Veena's defining task. The transcription workspace uses one clear consent panel followed by a jade recording rail and white editor.

## Logo

Generated using built-in image_gen in generate mode, then revised in edit mode. Original asset: `public/brand/veena-mark.png`, 1254 × 1254, transparent PNG. A diagonal Saraswati veena remains the primary silhouette, with a secondary gourd, curved headstock, and a prominent six-slot vintage microphone capsule and curved cradle integrated into its resonator. There is no separate upright microphone.

Exact final prompt:

Use case: logo-brand
Asset type: one standalone square logo mark for Veena, a privacy-first speech-to-text website supporting all 22 Indian scheduled languages.
Primary request: Create exactly one polished, original emblem fusing a Saraswati veena and a microphone into a single coherent symbol. The distinctive veena silhouette is primary: one large rounded pear-shaped resonator at lower-left, a long narrow fretted neck rising diagonally toward upper-right, a characteristic smaller secondary gourd beside the upper neck, and a graceful curved headstock. Integrate just three bold capsule-shaped microphone grille slots within the large resonator; the veena strings visually connect that resonator to the neck. The microphone is a subtle acoustic detail in the instrument, never a separate icon.
Scene/backdrop: genuinely transparent background with actual alpha transparency, no visible background, no checkerboard.
Style/medium: premium flat vector-like brand symbol; confident bold silhouette, precise smooth contours, minimal details that remain readable at 40 pixels.
Composition/framing: square canvas, single centered emblem filling approximately 80% of the canvas, generous clean negative space, strong optical balance.
Color palette: deep jade #164C40 as the main solid fill, restrained brass-gold #DEA947 accents on a few strings or frets.
Text: none. No wordmark or letters.
Constraints: preserve recognizable Saraswati veena proportions and its two resonators. Keep microphone grille slots large and simple. Clean crisp edges and solid flat colors. One mark only.
Avoid: generic upright microphone icon, mic-on-stand outline, headphones, guitar shape, disconnected collage, decorative border, frame, gradients, shadows, textures, 3D, perspective, mockup scene, text, watermark.

### Microphone clarification

The first mark's three gold bars read as instrument details. At the user's request, the lower resonator was revised into an immediately recognizable microphone while preserving the veena silhouette.

Exact revision prompt:

Use case: precise-object-edit
Asset type: revised Veena standalone logo mark on a genuinely transparent square canvas.
Input image: edit target. Keep the recognizable diagonal Saraswati veena concept and jade/brass palette, but fix the microphone: the current three gold bars do not read as a mic.
Primary edit: fuse an immediately recognizable vintage microphone capsule into the large lower-left resonator. Make this microphone head approximately 40% of the complete mark: a broad rounded brass-gold capsule grille with six thick dark-jade horizontal ventilation slots and a few small side perforations. Wrap a clean bold curved U-shaped jade microphone cradle around the lower half of the capsule, with clear space between cradle and capsule and small visible side pivots. The cradle and capsule are integral to the rounded veena resonator; this is one coherent veena-and-microphone emblem, with the microphone plainly visible at a glance.
Keep unchanged in concept: the long narrow fretted veena neck rises diagonally toward upper-right, the smaller secondary gourd sits beside the upper neck, and the headstock has a graceful curve. Retain recognizable veena proportions. Connect the neck into the upper-right edge of the microphone resonator. Stop the strings before the microphone grille so they do not obscure its slots. Remove the old three gold pickup-like bars completely.
Style: premium minimal flat vector-like logo, simple bold shapes readable at 40 pixels, precise smooth contours, solid deep jade #164C40 and brass-gold #DEA947; no gradients or texture.
Composition: exactly one centered fused mark filling about 80% of the square canvas, optical balance and clear negative space. Actual alpha transparency, no background or checkerboard.
Constraints: no text, no wordmark, no border, no extra separate microphone, no mic stand, no headphones, no guitar shape, no collage, no shadows, no 3D, no mockup scene. Make BOTH veena and microphone immediately recognizable.

Exact final background correction prompt:

Use case: background-extraction
Edit the supplied Veena logo PNG. Remove the ENTIRE gray-and-white checkerboard pattern behind the jade-and-brass logo and replace it with ACTUAL TRANSPARENT PIXELS (PNG alpha channel = 0). The checkerboard in the input is an unwanted opaque background, not part of the logo.
Preserve the logo itself exactly: diagonal veena neck and curved headstock, gold strings and frets, the small upper gourd, the large lower-left jade resonator containing the gold vintage microphone capsule, six jade grille slots, side perforations, and the microphone U-shaped cradle. Keep all logo shapes, colors, proportions, layout and canvas size. Do not redesign, add, remove, or alter any part of the instrument or microphone.
Output exactly one transparent PNG logo cutout, with genuine alpha transparency throughout all background areas. No checkerboard, no colored backdrop, no white background, no black background, no text, no mockup. Change only the background into actual transparency.

## Validation

Checked the main pages at 390px and 1440px: no horizontal overflow across 12 page routes. Reviewed rendered home, model setup, and download screens at both sizes. Nine browser regressions pass, including real Telugu and multilingual inference, explicit download consent, cancellation/retry, language navigation, and automatic cached offline reload. Five audio pipeline checks pass. Astro reports zero errors; edited sources pass lint and formatting.
