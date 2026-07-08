# Feature foliage art (real Civ4 trees)

The plot-zoom layer stamps **real Civ4 foliage cutouts** on vegetated plots instead of the old
procedural blobs. This replaces the `featureSprite` circles/lines for the features the Java generator
actually places.

## Which features are rendered

The Java terrain generator (`FeatureGenerator`, `ProvincePlotField`, `MapTerrainCodec`) places all of the
set below — including `CACTUS`, `BAMBOO` and `VERY_TALL_GRASS`, which the C2C `addFeatures` port now
generates (see `c2c-generator-port.md`). Every one either has a real Civ4 sprite atlas or is left as bare
terrain; there are **no procedural stand-ins** — a feature with no atlas simply draws no foliage:

| Feature | Sprite group | Source atlas |
|---|---|---|
| `FEATURE_FOREST` / `FEATURE_FOREST_ANCIENT` | `leafy` | `treeleafy/trees_1024.dds` |
| `FEATURE_JUNGLE` | `leafy` (denser) | `treeleafy/trees_1024.dds` (no dedicated jungle sheet) |
| `FEATURE_SAVANNA` | `palm` | `savanna/palms_1024.dds` |
| `FEATURE_OASIS` | `palm` | `savanna/palms_1024.dds` |
| `FEATURE_SWAMP` | `swamp` | `swamp/trees1.dds` |
| `FEATURE_BAMBOO` | `bamboo` | `bamboo/bambooattachments.dds` (leaf-cluster atlas) |
| `FEATURE_CACTUS` | `cactus` | **`kaktus/kaktus2.nif` rendered** (`tools/nifbake`) — no billboard atlas exists |
| `FEATURE_VERY_TALL_GRASS` | `grass` | **`sword_grass/wheat.nif` rendered** (`tools/nifbake`) |
| `FEATURE_FLOOD_PLAINS` | — | a ground quality, not foliage |
| `FEATURE_ICE` | (its own path) | `features/icepack` — see `coastlines.md` |

## Rendering 3D-model-only features — `tools/nifbake`

Cactus and very-tall-grass have no `*_1024.dds` billboard imposter in the Civ4 art — they exist only as 3D
`.nif` models. `tools/nifbake` bakes them into sprite sheets at build time: `nif.mjs` is a focused
Gamebryo **20.0.0.4** reader (it parses the scene graph + `NiTriShape`/`NiTriStrips` geometry exactly and
skips every other block type by a resync that lands on the next must-parse block), and `render.mjs`
software-rasterizes the textured triangles in an orthographic front view (Z up), drops near-horizontal
ground planes, and extracts each plant as a sprite via the same connected-component packing the `*_1024`
atlases use. `web/build.mjs` calls `bakeNifGroup` for the cactus/grass groups.

The atlases were moved out of the LFS `UnpackedArt/` tree into non-LFS `data/civ4/assets/terrain/features/`
so the build needs no `git lfs pull` (same as the icepack/wave-crest assets).

## Bake — `bakeFeatureSprites` (`web/build.mjs`)

The Civ4 `trees_*.dds` files are **irregular billboard sheets**: individual tree cutouts on a transparent
background, UV-mapped by their `.nif` (no clean grid, so an even slice won't work). Rather than parse the
`.nif`, the bake extracts cutouts by **connected-component labelling of the alpha**:

1. Decode the `.dds` (`dds.mjs`, DXT1/3/5 with alpha).
2. Flood-fill every opaque island (alpha ≥ 48); record each component's bbox, fill fraction, mean colour.
3. Keep the **tree-like** ones: moderate size, foliage fill fraction (0.1–0.85, so solid bark/ground blocks
   and sparse noise are dropped), not a wide/tall strip, and **green-dominant** (skips the snowy/autumn/bark
   variants baked into the same sheet). Relaxes the colour test if a sheet isn't green-dominant.
4. Pack the ~10 largest survivors into one horizontal RGBA strip PNG (`web/assets/trees-<group>-<seed>.png`),
   returning `{src, w, h, sprites:[[x,y,w,h]…]}`. Bundled as `BUNDLE.trees.{leafy,palm,swamp}`.

## Render — `featureSprite` (`web/js/plots.mjs`)

Each vegetated plot stamps **N deterministic sprites** (count/scale per `treeGroupFor` — jungle densest,
savanna sparsest), positions jittered by the plot's hash RNG, drawn back-to-front for natural overlap,
sized to the plot. A feature whose atlas is absent or hasn't loaded yet draws **no foliage** (the terrain
shows through) — the old procedural blobs were removed once every generated feature had real art. A
late-loading atlas invalidates the cached province texture canvases (the sprites are baked into them). The
atlas images load like the other baked assets (`treeImg`/`treeReady`).
