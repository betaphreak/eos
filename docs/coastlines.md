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

## Phase A — engine: a per-plot sea mask — **DONE (2026-07)**

Coast is an edge property computed **globally** (a land plot's water neighbour can be a sea
province the per-province mask can't see), so it belongs in `ProvinceRaster`, like the flow
direction:

- **Water classification, self-contained.** `ProvinceRaster` reads
  `data/anbennar/default.map` `sea_starts` + `lakes` id blocks (the raw source
  `ProvinceExporter` uses; strip `#comments`), maps the ids to pixel colours via its
  existing `idToColor`, and holds a `Set<Integer>` of **water colours** (`SEA`/`LAKE`;
  `IMPASSABLE` wasteland is **not** water → no shore). Deterministic, no `WorldMap`
  dependency, so `WorldPlotGenerator` and the live `loadOrGenerate` regen identically.
- **The mask (8-bit).** For each land pixel, which of its **8 neighbours** are water: low
  nibble = orthogonal **edges** (`1`=E, `2`=W, `4`=S, `8`=N — for the shoreline foam + coastal
  gameplay), high nibble = diagonal **corners** (`16`=NW, `32`=NE, `64`=SE, `128`=SW — the §B
  render fills these diagonal sea corners so outer corners wrap round). `0` = inland. Computed in
  `ProvinceRaster.seaMask()` (global pixel access, E-W cylinder wrapped), threaded through
  `ProvinceMask.coast()` → `ProvincePlot` → `Plot.coast()`/`isCoastal()` →
  `ProvincePlotStore.StoredPlot`, exactly like the river code.

Verified: grids regenerated; 34.4% of provinces have coastline (island 67%, largest
interior province 0%); `mvn test` green.

## Phase B — web: draw the shore — **DONE (2026-07)**

`drawCoast()` (`web/js/plots.mjs`) reads `q.coast` per plot in `buildPlotTexCanvas` (past
`K_TEX`) and, for each water **edge** (low nibble, `1`=E,`2`=W,`4`=S,`8`=N), draws a
**shallow-water band reaching OUTWARD from the shoreline into the adjacent sea** (strongest at
the shore, fading out), plus a thin **foam line** at the shoreline. The shallows ring the land
*in the water*, aligned to the coast. The adjacent sea is not a plot of this province, so
`buildPlotTexCanvas` pads its offscreen by one cell (`PAD`) to give room to bleed outward. The
diagonal **corner** bits (high nibble) additionally fill the diagonal sea with a radial fade,
so an outer corner (sea to the E *and* S → SE diagonal also sea) wraps round instead of leaving
a square notch between the two edge bands. Procedural (no baked art), absent-tolerant
(`q.coast` 0 → no draw). Only the textured layer; the flat-tile overview and background raster
are unchanged.

**Why not the Civ4 `coastscalemask` blend** (tried and reverted): those 16 `.tga` masks are
**diagonal-corner** ramps (decoded: bits TL=1,TR=2,BR=4,BL=8, dark=shallow) that draw the
shallow *inside* the land cell — a half-cell **inland** of the true coast — and don't tile on
our fine 1-pixel-per-plot staircase coastlines, so the shore didn't line up. They are built
for Civ4's coarser grid where the shore straddles plot edges; the outward procedural band is
the right fit for EU4-style pixel coastlines (cf. the river edge-tile mismatch,
`docs/river-rendering.md` §4). The `bakeCoastTiles`/`tga.mjs` path was removed.
