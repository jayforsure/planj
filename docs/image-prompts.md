# Image prompts for planj

Use with any image model (Midjourney, DALL·E, Imagen, Flux). Paste the **base style** first,
then the **subject** for the slot. Export as PNG or WebP at the sizes below and drop the file
into `android/app/src/main/res/drawable/` with the slot's filename; the app shows it with no
code change.

## Base style (prefix every prompt with this)

> Minimal abstract 3D still life for a premium personal-analytics app. Matte, softly lit
> objects on a near-black background (#0E1113) with a subtle dark-teal vignette. One accent
> colour only: mint-teal (#2EC4B6), used on a single object or edge; everything else in
> charcoal, graphite and off-white (#EDEFF2). Soft studio lighting from the upper left, gentle
> ambient occlusion, faint film grain. Calm, quiet, honest mood. No people, no text, no logos,
> no UI, no glow effects, no neon, no purple gradients, no lens flare, no clutter. Generous
> empty space around the subject. Photographic realism with a slightly sculptural, paper-craft
> feel.

Negative prompt (where supported):
`text, letters, watermark, logo, people, faces, hands, neon, purple, glow, bokeh lights,
busy background, gradients rainbow, cartoon, low quality`

## Slots

### `img_welcome` — 3:2 (e.g. 1800×1200) — welcome page and signed-out Account tab
Subject: three matte spheres rising in a gentle diagonal from lower left to upper right,
graphite, graphite, then a single mint-teal sphere at the top, connected by a thin off-white
line — an abstract forecast trending upward (this mirrors the app icon). Spheres sit on a
dark ground plane with soft contact shadows. Composition weighted to the right third,
leaving the left side quiet.

### `img_profile` — 5:2 (e.g. 2000×800) — profile banner
Subject: a wide, low landscape of dark matte dunes or folded paper hills under a thin
horizon line, with one small teal sphere resting in a hollow at the right. Very calm, wide
negative space. Should read as "your own quiet place".

### `img_signin` — 5:2 (e.g. 2000×800)
Subject: an open matte-black door frame seen straight on, slightly ajar, a soft off-white
light spilling from the gap, one mint-teal key resting on the floor in front. Symmetrical,
centered, minimal.

### `img_create` — 5:2 (e.g. 2000×800)
Subject: a single smooth graphite seed or pebble on a dark ground, with a tiny mint-teal
sprout just emerging from its top. Centered, macro depth of field, plenty of space.

### `img_verify` — 5:2 (e.g. 2000×800)
Subject: a matte off-white envelope, slightly open, lying on a dark surface with a small
mint-teal circular wax seal. Shot from a three-quarter angle, soft shadow.

### `img_recovery` — 5:2 (e.g. 2000×800)
Subject: a small matte-black safe-deposit box, closed, with a single mint-teal key lying
beside it on a dark surface. Suggests "keep this somewhere safe". Centered, restrained.

### `img_forgot` — 5:2 (e.g. 2000×800)
Subject: a matte off-white padlock, unlocked, with its shackle open, on a dark surface; a
faint mint-teal thread runs from the lock toward the right edge. Calm, not alarming.

## Export checklist
- PNG or WebP, sRGB, no transparency needed (background should match #0E1113 at the edges).
- Keep the subject inside the central 80% — the card crops edges on narrow phones.
- Filenames exactly as above, lowercase, no spaces: `img_welcome.png`, `img_profile.png`,
  `img_signin.png`, `img_create.png`, `img_verify.png`, `img_recovery.png`, `img_forgot.png`.
- Target file size under 400 KB each (WebP at quality 85 is ideal).
