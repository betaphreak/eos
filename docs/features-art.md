# Feature foliage art (real Civ4 trees)

The plot-zoom layer stamps **real Civ4 foliage cutouts** on vegetated plots instead of the old
procedural blobs. This replaces the `featureSprite` circles/lines for the features the Java generator
actually places.

## Which features are rendered

The Java terrain generator (`FeatureGenerator`, `ProvincePlotField`, `MapTerrainCodec.treeFeatureKey`/
`terrainFeatureKey`) only ever places this set — everything else in the `FeatureExporter` registry
(`FOREST_ANCIENT`, `BAMBOO`, `VERY_TALL_GRASS`) is **defined but never generated**, so it isn't worth art:

| Feature | Sprite group | Source atlas |
|---|---|---|
| `FEATURE_FOREST` | `leafy` | `treeleafy/trees_1024.dds` |
| `FEATURE_JUNGLE` | `leafy` (denser) | `treeleafy/trees_1024.dds` (no dedicated jungle sheet) |
| `FEATURE_SAVANNA` | `palm` | `savanna/palms_1024.dds` |
| `FEATURE_OASIS` | `palm` + water pool | `savanna/palms_1024.dds` |
| `FEATURE_SWAMP` | `swamp` | `swamp/trees1.dds` |
| `FEATURE_CACTUS` | — (procedural) | no cactus art exists in the tree set |
| `FEATURE_FLOOD_PLAINS` | — | a ground quality, not foliage |
| `FEATURE_ICE` | (its own path) | `features/icepack` — see `coastlines.md` |

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
sized to the plot. Falls back to the procedural blobs when an atlas is absent or hasn't loaded yet — and a
late-loading atlas invalidates the cached province texture canvases (the sprites are baked into them). The
atlas images load like the other baked assets (`treeImg`/`treeReady`).
