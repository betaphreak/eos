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

## Phase C — web: open water (real sea texture) — **DONE (2026-07)**

Phases A/B textured the *shore*; the open sea was still the flat crop tint (`SEA=[18,31,51]`)
baked into the terrain raster — a dead navy void behind the continents. Phase C fills it with
the real Civ4 sea art.

- **A greyscale ripple tile.** `bakeSeaTile()` (`web/build.mjs`) reads the wave luminance of
  `textures/water/seadetail.dds` and bakes a **128px neutral-mean (128) greyscale** tile — the
  wave pattern only, no colour, soft contrast so the repeat is subtle. Baked to
  `web/assets/sea-<seed>.png`, shipped as `BUNDLE.sea` / `core.SEA`. (The ocean's colour comes
  from the climate gradient below; the tile only ripples it via `soft-light`, so grey=128 is a
  no-op and darker/lighter texels deepen/brighten the water.)
- **Transparent sea in the raster.** `bakeTerrain` now bakes ocean/inland_ocean pixels (indices
  15/17) **transparent** — colour averages land sub-pixels only, alpha = the land fraction, so a
  downsampled coast pixel is a soft partly-transparent land edge over the water rather than a
  hard line. Needed `encodePng` to grow an optional alpha channel (colour type 6 / RGBA). Coast
  (35) stays opaque so its shore tint survives at world zoom.
- **The water layer.** `main.mjs`'s `drawSeaBase` paints the ocean behind everything (replacing
  the void fill); the transparent-sea raster then composites over it, so land is opaque terrain
  and every non-land pixel shows sea. **Colour is climate-banded** (below); **ripples** come from
  the `SEA` tile drawn with `soft-light`. The Phase B shallows layer over it near shores.
  Absent-tolerant: no bands → flat sea fill; no ripple tile → gradient only; neither → `#070a10`.
- **The ripple is map-anchored, not a screen grid.** A fixed screen-space tile read as an ugly
  static grid unrelated to the map. Instead the pattern is `setTransform`ed to **map space** — it
  pans and scales with the world (`s = cam.k · VIEW.dw/MAP.dw · SEA_WAVE`, origin at the map
  raster's on-screen corner) — and its opacity **fades out between `K_PLOT` and `K_TEX`**, so the
  upscaled tile never blurs at deep zoom (where open water is calm and land is the subject anyway).
- **Climate bands.** The sea colour is a **vertical latitude gradient** — tropical (≤23°) →
  temperate (~40°) → polar (≥60°), symmetric about the equator, sampled down the viewport via the
  `latAtScreenY` inverse Mercator. The three band colours (`bakeSeaBands`, `BUNDLE.seaBands`) take
  the authentic HUE of the Civ4 `seatrop`/`sea`/`seapol` blend textures at a hand-tuned dark
  LUMINANCE (tropical brightest/tealest, polar dimmest/greyest), mirroring the land recolour.
  Past **72°** the colour fades to deep-ocean dark, so the empty polar seas beyond the mapped
  land read as deep water instead of a flat grey expanse showing the ripple's tiling.
- **±89° clip.** `draw()` clips the whole scene to `|lat| ≤ 89°` (the Mercator projection
  diverges toward the poles and the source map has no data there), filling void beyond.

Verified headless (`tools/webverify`) at world, coastal-deep and northern zoom — ocean reads as
real Civ4 water with warm tropics fading to grey-blue poles, shore shallows frame the land over
it, rivers unbroken, nothing drawn past 89°, zero console errors.

- **Depth banding.** Open ocean darkens with depth, using **distance from land** as the
  bathymetry proxy (the heightmap has no sea-level datum here). `bakeTerrain` marks pure-ocean
  crop pixels, runs a two-pass chamfer `distanceToLand` transform, and paints a dark `seaDeepColor`
  (the `seadeepblend` hue at a very dark luminance) into those pixels at an alpha ramped by a
  smoothstep over the distance (0 at the coast → peak in the deep). Since the sea is otherwise
  transparent, the climate gradient shows on the shelf and open water reads dark — no render
  change, it rides the same transparent-sea composite.

**Lakes** ride the same path: EU4 paints them with the ocean indices (`15/17`) on `terrain.bmp`,
so lake pixels are transparent and show the climate gradient + ripple like the sea. Being
enclosed, their distance-to-land is small, so they stay shelf-coloured (not deep-dark) — right
for shallow inland water. They are **not** tinted as distinct freshwater yet.

## Phase D — web: real shore texture on the shallows — **DONE (2026-07)**

Phase B drew the shallow bands as a flat procedural teal; Phase D gives them the real Civ4
**shore wave texture**, the same soft-light treatment the open sea got in Phase C.

- **A greyscale shore-wave tile.** `bakeShoreTile()` (`web/build.mjs`) bakes a **128px neutral-mean
  greyscale** ripple from `textures/water/shoredetail.dds` (shared with the sea tile via
  `bakeRippleTile`, at a touch more contrast — 1.3 vs 1.1 — so the near-shore chop reads). Shipped
  as `BUNDLE.shore` / `core.SHORE`; null when the art is absent → the shallows stay flat-tinted.
- **The shallows tint** is now the authentic **tropical-sea HUE at a bright coastal-teal luminance**
  (`bakeSeaBands().shore`, `SEA_BANDS.shore` → `[88,193,190]`) — the shore reads as a brighter,
  lighter version of the open water. (`shoreblend.dds` itself is a neutral sandy blend with no
  usable water hue, like the land blends, so it is **not** used; only `shoredetail.dds` was moved
  out of LFS into `data/civ4/assets`.)
- **One province-level pass.** The per-plot `drawCoast` became `paintCoast` (`web/js/plots.mjs`),
  run once per province after the hillshade pass (and before features/rivers, so a river mouth sits
  over the shallows). It paints the shore-hue bands + corners on a scratch layer, clips the shore
  ripple to that layer's alpha (`destination-in`), then composites colour + ripple (`soft-light`,
  α 0.9) + crisp foam onto the offscreen. So the wave rides only the shore water and fades out
  exactly as the band does. Degrades to flat bands when the shore tile isn't loaded.

Verified headless (`tools/webverify`) at world, coastal and deep island zoom — the coast reads as a
bright textured teal shallows ring fading into deep water, foam at the shoreline, zero console errors.

## Phase E — engine: coastal water plots (features & bonuses) — **DONE (2026-07)**

Civ4 places bonuses (fish, crab, clam, whale, pearls) and features (ice) on water tiles. The
engine used to generate **land plots only**, so the shore had none. Phase E generates a
**coastal-shelf water field** for every sea/lake province, owned by that province.

- **Shelf water terrains in the registry.** `TerrainExporter` now keeps the shelf water terrains
  (`TERRAIN_COAST`/`TERRAIN_SEA` + polar/tropical, `TERRAIN_LAKE_SHORE`/`TERRAIN_LAKE`) alongside
  the 16 land terrains (deep sea/ocean/trench stay out). This wakes the sea bonuses in
  `bonuses.json`, whose `validTerrains` reference them.
- **A global distance-to-land field.** `ProvinceRaster` computes, once, the Chebyshev distance
  from every pixel to the nearest dry land (two chamfer passes), exposed per cell on
  `ProvinceMask.landDist`. It grades a sea province's own water cells into the shelf: `1` = COAST,
  `2..SHELF_MAX(3)` = SEA; cells further out are deep water and get no plot.
- **Water plot generation.** `ProvincePlotField.generate` branches on province type: SEA/LAKE grow
  the shelf field (each shelf cell a `FLAT` water plot, its terrain from `MapTerrainCodec.water`
  by depth + climate-by-latitude), and the **existing** `BonusGenerator.pick` places the sea
  resource — fish/crab in temperate/tropical coast, crab/whale into polar, by the terrain's climate
  variant. LAND and IMPASSABLE wasteland keep the land path. Deterministic off the same canonical
  per-province terrain stream; land fields are untouched. Ice (a water feature) is deferred.
- **All provinces generated.** `WorldPlotGenerator` now iterates every non-RNW province (RNW/Unused
  already dropped upstream), writing land fields for LAND/IMPASSABLE and shelf fields for SEA/LAKE;
  a deep-ocean province with no shelf writes nothing.

One correctness fix landed here: Civ4's fishing bonuses (fish/crab/clam/whale/pearls/shrimp/lobster)
declare **no relief flags** — water is gated by terrain, not flatlands/hills/peaks — so the land
relief gate left only `OIL`/`NATURAL_GAS` (which are flat). `BonusGenerator.pick` gained a scoped
`water` flag that skips the relief test on water plots (land placement byte-identical); sea provinces
now carry the full mix (fish/crab/lobster/shrimp/pearls + offshore oil/gas + C2C deep-sea vents).
Verified by `ProvincePlotFieldTest.seaProvinceGrowsAnEligibleCoastalShelf` (a coastal sea province
grows a bounded shelf ring of flat water plots, COAST cells touch the shore, every placed resource is
an eligible sea bonus, and a classic fishing bonus actually appears across a seed sweep).

## Phase F — web: render coastal resources — **DONE (2026-07)**

Phase E generated the data; Phase F makes it visible. The web used to ship **LAND provinces only**
and draw **no resource icons at all**. The plot render loop (`drawPlots`) already tolerated ring-less
provinces (it culls by bbox / centroid), so sea provinces joined without any ocean polygons.

- **F-a — shelf plots into the bundle** (`build.mjs`). The packed set grew beyond LAND to the SEA/LAKE
  provinces that have grids (309 sea + 69 lake): their plots pack into `plots.pack` + `plotIndex` like
  land, and each gets a minimal province record (`id, type, lat, lon`, `hasPlots`, `rings:null`) plus a
  **plot-extent bbox** (`packPlots` parses the grid once) that `provSrcBox` uses for viewport culling
  in place of a polygon. The border exporter is unchanged (no giant ocean outlines).
- **F-b — render water plots** (`plots.mjs`). `buildPlotTexCanvas` and the flat `buildPlotCanvas` skip
  all the land ground stages for a sea/lake province (`p.type` SEA/LAKE), leaving its cells transparent
  so the base sea gradient + ripple and the Phase-B/D shore shallows (drawn from the land side) show
  through — only the resource glyphs are painted. Bounded: sea provinces hold only their thin shelf ring.
- **F-c — a procedural resource-icon layer** (`drawBonuses`/`bonusGlyph`, new). No sprite art survives
  the LFS cleanup, so each plot with a bonus gets a small procedural **category glyph** — colour + shape
  keyed by category (sea food = teal circle, gems/luxury = magenta diamond, energy = amber triangle,
  metal/stone = steel square, farm/trade crop = green circle, livestock/game = tan circle, else a pale
  dot). Drawn into the per-province texture canvas, so it only appears past `K_TEX`. Shared by land and
  sea, so **land** bonuses (wheat, iron, gold…) are now visible for the first time too.
- **F-d — polish.** Glyphs are texture-zoom only; the flat overview shows clean water; sea/lake stay
  **non-interactive** (labels already skip non-LAND; the click/centroid fallback skips ring-less
  provinces). Verified headless at world / mid / texture zoom — coasts dotted with teal fishing markers
  and offshore energy/pearl glyphs, land carries its resources, clean water at mid-zoom, zero console
  errors.

**Ice & tooltip (2026-07):** both landed. `FEATURE_ICE` joined the feature registry (`FeatureExporter`;
its `validTerrains` are the polar sea/coast terrains) and `ProvincePlotField.generateWater` places it
on polar shelf water with a coverage that ramps by latitude (~15% at 66° → 90% by ~82°), one climate
band per province so the draw stays deterministic; `plots.mjs` `drawSeaIce` renders it as pale pack ice
(open shelf shows a teal rim where coverage is partial). A hover **tooltip** (`plotAt` in `panel.mjs`,
using each province's stored plot grid) names the resource under the cursor — `◆ Fish`, `◆ Ice`, `◆ Iron
Ore` — for land and the ring-less sea shelf alike, at texture zoom.

**Interactive sea provinces (2026-07):** `ProvinceBorderExporter` now also outlines the **coastal**
sea/lake provinces (those that grew a shelf grid — deep ocean stays skipped), tracing + Douglas–Peucker
simplifying them like land (+~100 KB in `borders.json`, avg ~27 pts/ring). With rings in the bundle they
hover, highlight, click-select and populate the detail rail exactly like land — terrain mix (Sea/Coast
Polar), the ice feature count, and the sea-resource breakdown — and faint sea-zone outlines divide the
ocean. `WorldPlotGenerator` must run before the border exporter so the shelf grids exist.

**Map polish (2026-07):** three finishing touches once sea/lake provinces ship with outlines.
- **Freshwater lake tint.** `main.mjs` `renderScene` fills each `LAKE` province's polygon a distinct
  green-teal over the (blue) sea base, so lakes read as fresh water rather than ocean — no per-pixel
  raster mask needed; it rides the lake outlines now in the bundle.
- **Sea/lake labels.** `labels.mjs` names water provinces in a cool italic, in a secondary pass drawn
  after (and lower priority than) land names, fading in deeper (`cam.k` 8.5→10.5) so they don't clutter
  the world view.
- **Rail stat.** a selected sea/lake province shows *Water area* + *Shelf plots* instead of the
  land-only *Land/Water plots*.

**Real resource icons (2026-07):** the procedural category glyphs are now the **fallback** — resourced
plots draw the true Civ4 resource symbol, sliced from `GameFont.tga` by `FontButtonIndex` and atlased in
the web build (no Blender). See `docs/bonus-sprite-bake.md`. The ripple pattern is still zoom-invariant;
a geo-scaled variant is a possible refinement.

## Phase G — real coast art, natural pack ice, no hillshade — **DONE (2026-07)**

The shore had real ripple textures but the shoreline itself was a flat procedural white line, the coastal
land ended in a hard square staircase, and — the biggest offender in the far north — polar shelf ice was
drawn as **opaque pale squares** (`FEATURE_ICE` covers 70–90 % of a >66° sea's shelf), which read as a
blocky white grid ringing every arctic coast. Three changes, all riding the existing per-province offscreen:

- **Real shoreline foam.** `bakeFoamTile()` (`build.mjs`) keeps `waves/wave_crest.dds` as an RGBA strip
  (white crest → clear); `drawFoamCrest` (`plots.mjs`) lays a per-plot slice of it along each water edge
  (foam at the shore, fading seaward — one `setTransform`/`FOAM_XF` orients the strip for E/W/S/N). Its
  naturally ragged crest breaks up the shoreline. Kept short/low-alpha so narrow sea channels stay open;
  falls back to the old thin foam line when the art is absent.
- **Beach apron.** `drawBeach` feathers each coastal land plot's own terrain colour (mildly darkened — a
  wet shore reads darker) a jittered distance into the water, so the land dissolves into the shallows
  instead of ending in a hard square. Shared `outwardBands` helper with the shallows.
- **Pack-ice floes, real texture.** `bakeIceTile()` bakes the clean upper region of
  `features/icepack/icepack_1024.dds` into a colour tile; `drawSeaIce` now draws each ice plot as a
  **translucent floe** textured with it, inset per edge — touching neighbouring ice (a thin crack) but
  pulling back from open water (a lead of dark shelf shows), corners jittered — so the shelf reads as
  broken pack ice, not a white grid. Flat pale floe when the tile is absent.
- **Hillshade removed.** The elevation-normal hillshade (`EXAG=4`) amplified the gentle continental
  heightmap into a strong per-plot bright/dark **checker** on near-flat provinces (most of the map), which
  itself read as square tiles. It is gone; the ground is now the flat Civ4 terrain texture and relief reads
  from the terrain/feature mix. The high-ground snow cap (`elevation ≥ 165`) stays.

New committed art (non-LFS, `data/civ4/assets/…`): `terrain/waves/wave_crest.dds`,
`terrain/features/icepack/icepack_1024.dds`. Baked tiles (`foam-*.png`, `ice-*.png`) are gitignored/regen.
Verified headless at arctic deep/moderate and temperate deep — clean terrain, floe-textured ice with open
leads, foam + beach at the shore, zero console errors.

## Phase H — wider shallows + a ragged (non-square) land edge — **DONE (2026-07)**

Phase G softened only the *water* side of the shore (beach apron feathering outward, foam at the
shoreline), so a straight coast still read as a column of square land tiles with a thin fringe — the
land/water boundary was always an unbroken grid line. Two changes in `paintCoast` (`plots.mjs`) break
that up, both riding the existing per-province offscreen:

- **Lever A — wider transition.** `PAD` grew `1 → 2` so the shore can bleed >1 cell into the sea; the
  shallows band reach went `s·0.9 → s·1.25` and the beach apron `s·0.32–0.74 → s·0.45–1.0`, giving a
  broader shore→deep gradient instead of a one-cell sliver.
- **Lever B1 — carve the land edge.** Before the shore bands draw, `erodeCoast` bites a ragged,
  per-plot-hash-jittered notch out of each coastal cell's water-facing edge (`coastBites` → K=4 spans,
  depth 0..0.30·s, some spans left full so the coast mixes eroded and intact tiles → `clearRect`). The
  shallows pass (`drawCoastBands`) then refills those exact bite rects with the shore hue, so the carved
  land reads as wet shore — no transparent deep-sea gaps. The coastline meanders ±~0.3 cell off the grid,
  so the land stops reading as squares. Same deterministic-hash idiom as `drawBeach`/`drawSeaIce`; gated on
  `ramp>0` (well-resolved provinces only). Verified headless (before/after at temperate-coast plot zoom):
  the hard square staircase becomes a wide teal shallows with an irregular shoreline, zero console errors.

**Ocean & icon render polish (2026-07).** Two map-rendering adjustments alongside the chrome rework:
- **No polar ripple tiling.** The ocean ripple (`drawSeaBase`, `main.mjs`) is now confined to the map
  raster's on-screen Y extent — beyond it (the empty polar seas between the map edge and the ±89° clip)
  the tile was repeating as a visible static grid; those bands now stay flat gradient.
- **Resource icons → zoom-gated screen overlay.** `drawBonuses` (baked into the province texture) became
  `drawBonusOverlay` (`plots.mjs`), a screen-space pass, each icon anchored in its plot's **bottom-left**
  corner. Land + shelf water alike; cheap (few provinces in view that deep). Sizing/gate updated below.

## Phase I — merged ice sheet, land-into-water coast, icon sizing — **DONE (2026-07)**

Phase G/H's per-plot floes and land-erosion still read as squares/artefacts. The fixes reframe both around
the right mental model: **the coast is a water tile**, and **ice is a field, not tiles**.

- **Ice → one merged sheet** (`drawSeaIce`). The 70–90 %-coverage shelf is now filled as a **single Path2D**
  (all ice cells), so interior seams vanish — no checkerboard. A cell's edge is inset (jittered) **only where
  it faces open water**; ice-ice edges stay flush. A cool rim is stroked along the **outer boundary only**
  (loose segments), never the interior. Dark leads show where the shelf isn't ice. One texture fill + a faint
  sun sheen; flat pale sheet when the tile is absent.
- **Coast → land extension, not land erosion** (`paintCoast`/`coastExtendPolys`/`extendCoast`). The coast is
  the *water* shelf, so the shore is made organic by the coastal **LAND** cells **protruding into the coast
  water**, never by eating land (the old `erodeCoast` filled carved land with teal → blue blotches on the
  ground). Per water edge, a quad juts outward by a **corner-continuous** jittered depth (`coastDepth` keyed
  on shared global corners → a wavy line across cells, not per-cell rectangles), filled with the plot's real
  terrain pattern. Order: shallows (`drawCoastBands`, outward only) + ripple first, **then** the land bumps on
  top — so the shore hue stays in the water and the boundary is a wavy land-into-shallows line. The wave-crest
  foam and darkened beach apron (which lapped onto land) were dropped.
- **Resource icon sizing.** `drawBonusOverlay` is hidden at `cam.k ≤ 16`; icon size is **relative to the
  on-screen plot size** (`(pxr(1)−pxr(0)) × 1.32` ≈ 21 px at 64× on desktop) rather than absolute px, so it
  scales with the terrain on any viewport — a fixed-px icon covered too many plots on a narrow mobile screen.
- **Terrain blend.** The 16-way edge blend now also feathers **equal-LayerOrder** neighbours (mutually, at
  half strength) — previously only a strictly-higher neighbour bled, so same-layer boundaries (grass/plains/
  tundra) met at a hard seam; feather widened `0.5→0.62` cell.
- **Coast reach.** Land extension deepened (`coastDepth` `0.05–0.42 → 0.18–0.63` cell) and shallows reach
  `1.1→1.35` cell, so the shore pushes further into the sea with a healthy shallows ring beyond the bumps.

Verified headless via `?p=&z=` deep links (`tools/webverify/shot.mjs`) at a polar shelf (seamless ice, ragged
edge, open leads) and a temperate coast (wavy land bumps into teal shallows, no blue on land), zero console errors.

> Navigation, camera framing, and the web UI chrome (deep links, focus/pan, the collapsible sidebar,
> floating search, status line, dark default, brand wordmark) are documented in **[`docs/ux.md`](ux.md)**, not here.
