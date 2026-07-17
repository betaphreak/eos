# Country flags

The **Nations** political overlay flies real Anbennar heraldry: every country's flag, baked from the
mod's own art into one WebP atlas, is stamped over its lands as an on-map **flag + name label** and
shown as a chip in the Nations legend. Reference art only — nothing in the sim consumes it.

Sibling of [`trade-goods.md`](trade-goods.md) (a baked Anbennar-art layer) and
[`political-map.md`](political-map.md) (the country/culture/faith overlay this rides on).

## Source → atlas → overlay

1. **Source.** Anbennar ships one flag per country tag at `gfx/flags/<TAG>.tga` (1527 files,
   128×128, 24-bit, uncompressed or RLE) — fetched on demand from the GitLab source like every other
   mod file (`web/anbennar.mjs`, pinned by `anbennar-source.lock`), never vendored. sharp/libvips
   can't decode TGA, so the repo's own `web/tga.mjs` decoder feeds it raw RGBA.

2. **Bake — `web/build-flags.mjs`** (`node web/build-flags.mjs`, the sibling of `build-buildings.mjs`).
   Lists `gfx/flags/` (`anbennar.mjs` `list()` — offline via the `.anbennar-cache` junction, else the
   GitLab tree API), decodes each TGA, box-downsamples it to a **48×48** cell, and packs all of them
   into one sheet encoded to **`web/assets/flags/flag-atlas.webp`** (~950 KB, 1526 flags — one odd TGA
   skips). All tags are baked, whether or not they own a shipped province, so any owner resolves.
   Emits **`web/flags.js`**:

   ```js
   window.FLAGS = { src: "assets/flags/flag-atlas.webp", cell: 48, cols: 40, count, index: { TAG: cellIndex } };
   ```

   The per-tag `cellIndex` gives the atlas cell — `x = (i % cols) * cell`, `y = ⌊i / cols⌋ * cell` —
   the `window.TRADEGOODS.icons` shape, so the overlay reuses the same blit math. `flags.js` loads
   eagerly (`<script defer>` in `index.html`, optional — a 404 just turns flags off); the atlas image
   itself loads lazily on the first political draw.

3. **Names.** The label text is the country's **localised English name** (`Lorent`, `Galéinn`,
   `Rüng Igedch`), imported by `CountryExporter` from `localisation/*countries*l_english.yml`
   (`A01:0 "Lorent"`, read as UTF-8) into `countries.json`, falling back to the definition-file stem
   for a tag with no localised entry (38 of 1454). This flows into `window.POLITICAL.countries`
   (`web/build.mjs`) the overlay already reads.

4. **Overlay — `web/js/overlays/political.mjs`.** On the Nations overlay:
   - **Legend chips** — each row's flag as a CSS background-sprite of its atlas cell (`flagChipHtml`).
   - **On-map labels** (`drawCountryLabels`) — a flag + name at each country's **bbox-area-weighted
     centroid** of owned provinces (`countryAnchors`, cached per plane). Shown only in the overview
     (below `K_PLOT`, fading out toward plot detail) and **greedily de-collided** — biggest nations
     claim the space first, capped at `LBL_MAX` — so the map never drowns in labels.

## Rebaking

`node web/build-flags.mjs` after a country/flag change; re-run `CountryExporter` + `web/build.mjs`
if names changed. All outputs are committed static assets — `web/` auto-deploys on push, no server
redeploy (the flag layer never touches the engine or the bundle).
