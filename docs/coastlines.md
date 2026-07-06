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

## Phase B — web: draw the shore — (next cut)

Read `q.coast` per plot; on each water edge draw a **shore band** into
`buildPlotTexCanvas` (under the terrain, over the sea): either the faithful 16-way
`coastscalemask` blend (shallow→shore→land, baked to a web atlas by a `bakeCoastTiles`, like
`bakeTerrainTiles`) or, as a first cut, a lighter procedural surf/shallow-water gradient
keyed by the edge mask. Absent-tolerant like the river tile (flat sea if the art/field is
missing).
