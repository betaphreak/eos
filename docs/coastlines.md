# Coastlines on the WorldMap

**Status:** in progress. Today the plot terrain-zoom layer renders only **land** plots
(each province's `rivers.bmp`/`terrain.bmp` pixels); the sea is just the flat background
raster tint, so the land/sea boundary is a **hard pixelated edge** with no shore. This doc
is the plan to draw a real coastline there.

This is the feature the Civ4 **edge-tile** machinery actually fits: unlike rivers (EU4
pixel/centre-line — see `docs/river-rendering.md` §4), the land/sea boundary genuinely *is*
a plot edge, so a per-plot **sea-edge mask** + the Civ4 coast art compose cleanly.

## The art (Civ4, `UnpackedArt/art/terrain`, DXT — decodable via `web/dds.mjs`)

- `heightmap/coastblendmasks/coastscalemask00–15.tga` — a **16-way blend mask set** (the
  same auto-tiling idea as the terrain blend), for feathering shore/shallow water into land.
- `textures/water/{shore,sea,seadeep,seatrop,seapol,trench,lake}.dds` + `coasttempblend` /
  `coasttropblend` / `coastpolarblend` — the shore/shallow textures per climate.

## Phase A — engine: a per-plot sea-edge mask — **(this cut)**

Coast is an edge property computed **globally** (a land plot's water neighbour can be a sea
province the per-province mask can't see), so it belongs in `ProvinceRaster`, like the flow
direction:

- **Water classification, self-contained.** `ProvinceRaster` reads
  `data/anbennar/default.map` `sea_starts` + `lakes` id blocks (the raw source
  `ProvinceExporter` uses; strip `#comments`), maps the ids to pixel colours via its
  existing `idToColor`, and holds a `Set<Integer>` of **water colours** (`SEA`/`LAKE`;
  `IMPASSABLE` wasteland is **not** water → no shore). Deterministic, no `WorldMap`
  dependency, so `WorldPlotGenerator` and the live `loadOrGenerate` regen identically.
- **The mask.** For each land pixel, a 4-bit **orthogonal edge** mask — which of E/W/S/N
  borders a water pixel (bit order = `NB4`: `1=E, 2=W, 4=S, 8=N`); `0` = inland. Computed
  in `ProvinceRaster.mask()` (global pixel access), threaded through `ProvinceMask.coast()`
  → `ProvincePlot` → `Plot.coast()` → `ProvincePlotStore.StoredPlot` (new `coast` field),
  exactly like the river code. Also useful later for gameplay (ports / sea trade).

Verify: regenerate grids; coastal provinces have nonzero masks, interior/land-locked ones
are all 0; `mvn test` green.

## Phase B — web: draw the shore — **procedural first cut DONE (2026-07)**

`drawCoast()` (`web/js/plots.mjs`) reads `q.coast` per plot in `buildPlotTexCanvas` (past
`K_TEX`) and, for each water edge (bit `1`=E,`2`=W,`4`=S,`8`=N), draws a **shallow-water
band** fading inward from the shoreline plus a thin **foam line** at the water's edge — so
the hard land/sea boundary reads as a coast. No baked art, no `q.coast` → no draw (absent-
tolerant). Verified headless on the Madala Islands (`verify-pack.mjs`, deep zoom): each
island gets a shallows + surf rim, zero console errors. Only the textured layer draws it;
the flat-tile overview (`K_PLOT`–`K_TEX`) and the background raster are unchanged.

**Optional fidelity swap (later):** replace the procedural band with the faithful 16-way
`coastscalemask` blend, baked to a web atlas by a `bakeCoastTiles` (like `bakeTerrainTiles`)
and keyed by the same edge mask — the river 1B → option-A pattern.
