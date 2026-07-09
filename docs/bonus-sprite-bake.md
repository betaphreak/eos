# Bonus resource icons — real Civ4 art on the map

**Status:** **shipped (2026-07) via GameFont slicing.** The web map draws a **true per-resource Civ4
icon** on each resourced plot, replacing the procedural category glyphs (`docs/coastlines.md` Phase F).
The chosen source is **`GameFont.tga`** (sliced in the web build, no Blender). The `.nif`→render route
(§"Alternative" below) was fully built and works, but the models are shared across resource families
(58 distinct for 98 bonuses) and carry ground clutter — so GameFont's 106 unique, clean symbols won.

## As-built — GameFont slicing (pure Node, no Blender)
- **`data/civ4/res/Fonts/GameFont.tga`** (528×640, RLE 32-bit BGRA, bottom-up) — the master symbol
  sheet; its resource block is a **fixed 25-column grid of 21px cells starting at (0, 429)**
  (calibrated against known icons — banana/corn/fish/pig/wheat/gold all land correctly).
- **`web/tga.mjs`** — a minimal dependency-free TGA decoder (RLE + raw, 24/32bpp, origin-flip).
- **`build.mjs` `bakeBonusIcons()`** — regex-reads `BONUS_* → ArtDefineTag → FontButtonIndex` from the
  two committed Civ4 XMLs, slices each bonus's 21px cell out of GameFont, packs them into one atlas
  `assets/bonus-icons-<seed>.png` + a `{bonusType: cellIndex}` manifest in `data.js`. All 106 bonuses
  resolve to a unique cell (0 negative indices). Gitignored, regenerable.
- **`plots.mjs` `drawBonuses`** — blits the atlas cell (scaled ~0.72·tile) on each resourced plot at
  texture zoom, for land and the coastal shelf alike; the procedural category glyph is the fallback
  when a bonus has no icon or the atlas isn't loaded.

The Civ4 XML default namespace lives only on the root, so the child tags (`<Type>`, `<FontButtonIndex>`)
read literally — regex parsing works; ElementTree's `iter("BonusInfo")` would (silently) find 0.

## Alternative (explored, not shipped) — offline `.nif` → Blender render

## Why
The web plot layer renders a **bonus** per resourced plot. Until now that was a procedural
colour+shape glyph (no sprite art survived the LFS cleanup). Civ4 ships a 3D `.nif` model per
resource; Blender can render those to flat transparent PNGs offline, giving real per-resource art.

## Sources (committed **non-LFS** under `data/civ4/`)
- `data/civ4/CIV4BonusInfos.xml` — `BONUS_* → ArtDefineTag` (and the economic data → `bonuses.json`).
- `data/civ4/CIV4ArtDefines_Bonus.xml` — `ArtDefineTag → NIF path` **and** `FontButtonIndex`
  (the resource's cell in GameFont). 109 `BonusArtInfo` entries.

> **As-built:** the shipped bonus icons are baked from **GameFont** (`assets/bonus-icons.webp`,
> the `FontButtonIndex` cells), not from 3D `.nif` resource models. The old `.nif`-based bake below
> is historical: the `UnpackedArt/art/terrain/resources/**` `.nif` models it used lived in the
> Git-LFS `UnpackedArt/` tree, which was **removed** (2026-07, along with LFS). Reviving a
> `.nif`-render path would mean re-fetching those models from upstream C2C into `data/civ4/assets/`.
- `data/civ4/res/Fonts/GameFont.tga` (528×640) — the master symbol sheet; its lower block is the
  per-bonus icons, addressed by `FontButtonIndex`. The **fallback** source for bonuses with no `.nif`.

> Both XMLs carry a **default namespace** (`{x-schema:…}`), so ElementTree must match by *local
> name* (`tag.split('}')[-1]`), not `iter("BonusInfo")` — the classic gotcha that silently finds 0.

## Toolchain
- **Blender 3.6.23 LTS** (portable, `C:\Users\Eu\tools\blender-3.6.23-windows-x64\`) — chosen over
  5.x because the NIF addon targets 3.x. Not in the repo.
- **`io_scene_niftools` v0.1.1** (Blender NIF addon) — installed into Blender's user addons dir.
  Imports these Gamebryo 20.0.0.5 NIFs with geometry **and** textures. Offline only.
- **`tools/nif-bake/bake_bonus_sprites.py`** — the batch baker. Run:
  ```
  blender --background --python tools/nif-bake/bake_bonus_sprites.py -- <repoRoot> <outDir>
  ```

## Bake steps (per bonus)
1. Resolve `BONUS_* → tag → NIF → file` (with `_fx` fallback); no file → **skip** (GameFont fallback).
2. Import the `.nif`; **curate** the mesh (see below); frame an **orthographic 3/4-top** camera to the
   kept bounding box; a sun light; **Eevee, transparent film, 256², RGBA PNG**.
3. Write `manifest.json` = `{baked:[...], missing:[...]}`.

### Curation (pass 2)
Civ4 resource models bundle a **ground/mound base**, sometimes **wild + improved variants**, and
helper nodes (bounding spheres, water planes, billboards). To get a clean icon:
- Drop helper meshes by name (`wave/sphere/shadow/sound/billboard/collision/envlight`).
- If a `wild` node exists, drop the `cultivated/improved/worked/farm/mine/plantation/quarry` variant.
- Drop **ground bases**: meshes named `base/ground/plot/dirt/patch/terrain`, or a **wide+flat** mesh
  (`z-extent ≤ 0.18·max(x,y)`) sitting at the model's floor, or a `≤2`-poly quad.
- **Never prune to empty** — if every mesh got dropped, keep the single largest (the resource itself).
- **Skip untextured models** (no image loaded → a missing-texture placeholder); they take the fallback.

## Coverage (2026-07, first full run)
- **96 / 106** bonuses baked from real `.nif`.
- **8 art-less** (INCENSE, BEAVERS, CRAB, WHALE, COAL, GOLD_ORE, STONE, ANCIENT_RELICS) — no `.nif`
  in this snapshot → **GameFont** fallback by `FontButtonIndex` (all 8 have one). **Not dropped** —
  there are gameplay dependencies on them.
- **LLAMA** — the NIF addon hits an armature-chain bug on that mesh (there is a `llama_gamefont.tga`
  modular tile to fall back to). **PRICKLY_PEAR** — was over-curated (fixed by the never-empty rule).

## Key finding — the `.nif` models are shared (2026-07)
The 98 resolvable bonuses map to only **58 distinct NIFs** — 23 models are **shared across a
resource family**: `wheat.nif` ← BARLEY/MELONS/POTATOES/PUMPKIN/WHEAT/FLAX/OPIUM; `gems.nif` ←
AMBER/DIAMOND/RUBIES/SAPPHIRES/JADE/OBSIDIAN; `marble.nif` ← FINE_CLAY/MARBLE/NATRON/SALT/TURQUOISE
(the repeated "yellow mound"); etc. So the `.nif` renders **cannot tell those resources apart** —
Civ4 distinguishes them purely via the **unique `FontButtonIndex` symbol in GameFont**.

**Consequence:** for *distinct, per-resource* map icons, **GameFont is the better primary source** —
106 unique symbols, clean (no baked ground base), complete (covers the art-less 8), no Blender at
render time. The `.nif` renders give 3D richness but only ~58 distinct looks and carry base clutter
that resists generic curation (the base is often baked into the resource mesh). Options: GameFont for
all; or `.nif` only where a bonus owns a *unique* model, GameFont for the shared/art-less remainder.

## Remaining
- **GameFont slicer** — decode `GameFont.tga`, reverse-engineer the cell grid, cut the fallback icons.
- **Atlas + web** — `build.mjs` packs the icon PNGs → `assets/bonus-icons-<seed>.png` + a
  `{bonusType:[col,row]}` manifest in `data.js`; `plots.mjs drawBonuses` blits the sprite, keeping the
  procedural glyph as the last-resort fallback.
- The rendered PNGs (`tools/nif-bake/out/`) are regenerable and **gitignored**; the baker script +
  the source XML/TGA are committed.
