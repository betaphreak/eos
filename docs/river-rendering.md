# River rendering on the WorldMap

**Status:** planned. Today rivers draw as a **flat translucent blue fill** of each
river plot (`web/js/plots.mjs:212`) — the crudest stand-in on the map. This doc is
the plan to render them as real, flowing rivers, and — the part that makes it worth
doing — to stop discarding the flow information we already have.

Cross-refs: the terrain-art pipeline this rides on is `docs/ported-terrain-art-system.md`
(§11 As-built status is the current state; rivers are 2D DDS, not `.nif`, so they need
**no** offline mesh→sprite baker — decodable today via `web/dds.mjs`).

---

## 1. The data reality — and what we throw away

- **Per plot we store one boolean, `river`** — "whether a river pixel fell on this
  plot" (`ProvincePlotField.java` `ProvincePlot`; persisted by `ProvincePlotStore`).
  No flow direction, no width, no edge geometry.
- **But two upstream signals exist, and both are currently collapsed to that boolean:**

  1. **Authored — `data/anbennar/rivers.bmp`.** The EU4/Anbennar river map is an
     indexed bitmap whose non-white pixels encode, by colour: **river width levels**
     (a blue-shade ramp, narrow→wide) plus **special node markers** (river *source*,
     *tributary flow-in / confluence*, and *split*). Direction is implied — sources are
     upstream; width grows downstream toward the mouth. **We reduce all of it to 1/0 at
     `ProvinceRaster.java:120`:** `riverFlag = (river[i] & 0xFFFFFF) != RIVER_NONE ? 1 : 0`
     (`RIVER_NONE = 0xFFFFFF`, pure white). Everything the mod authors encoded about the
     river network is lost right there.

  2. **Topographic — `data/anbennar/heightmap.bmp`.** We already sample it into the
     per-plot `elevation` (0–255), used today for hillshade and the movement-cost
     overlay. Water flows downhill, so a D8 steepest-descent over `elevation` yields a
     drainage direction per cell.

**Decision: the authored palette is the primary flow signal; elevation is the
tie-breaker/fallback.** Rivers sit in flat valley floors, which is exactly where the
sampled 0–255 heightmap goes flat/noisy between adjacent river cells — so *pure* D8
is ambiguous precisely where we need it and would need pit-filling + flat-resolution
(Planchon–Darboux) to behave. The mod's authored width+source markers are cleaner and
"correct" for the game world; use elevation only to orient segments the palette leaves
ambiguous.

**Pinned palette (histogrammed from `data/anbennar/rivers.bmp`, an 8-bit indexed
5632×2048 BMP — 14 distinct colours in use):**

| Colour | RGB | Meaning |
|--------|-----|---------|
| white `#ffffff` | 255,255,255 | land — no river |
| grey `#797b78` | 121,123,120 | sea (never on a land plot) |
| blue ramp | `#00dffb` `#29c9fd` `#138afa` `#2f6afd` `#2220f0` `#3b00f5` `#2b139c` `#461f80` | **width**, cyan→deep-blue = narrow→wide |
| green | `#1cf118` / `#33830d` | **source** node |
| red | `#e51b02` | **flow-in / confluence** node |
| yellow | `#e2e311` | **split** node |

Because it is an indexed BMP the entries are exact (no anti-aliasing), so the
**dominant channel** classifies unambiguously: greyscale (`max−min < 40`) → none;
blue max → width (bucketed by the green channel, cyan has the most green); else
green/red/yellow → source/flow-in/split. See `ProvinceRaster.classifyRiver`.

---

## 2. Phase 1 — authored-width rivers

The first shippable river: a water-textured ribbon that follows the network and
**tapers by the river's authored width** (thin at sources → wide at mouths). Width
comes straight from `rivers.bmp`, so this already reads as flowing water with **no
directed graph** — explicit flow *direction* (a data product, no animation) is Phase 2.
Split into a Java data half
(1A) and a web render half (1B); they can land in either order, since 1B degrades to a
fixed-width ribbon until 1A ships.

### Phase 1A — Java: export the river network (stop flattening) — **DONE (2026-07)**

The old `ProvinceRaster` collapsed every non-white `rivers.bmp` pixel to `1/0`. Now
`ProvinceRaster.classifyRiver(rgb)` classifies each pixel from the §1 palette and the
result is carried, unflattened, all the way to the persisted grid.

- **One packed int, not separate fields.** All fields fit one int (compact record +
  JSON): `0` = none; the **low digit** is the width level `1..4` (narrow→wide); the **tens
  digit** is the downstream flow direction `1..8` (Phase 2 — `0` in 1A, filled later); the
  **hundreds digit** is the node marker (`1` source, `2` confluence, `3` split); the
  **thousands digit** is a 4-bit river-adjacency mask (`1`=E, `2`=W, `4`=S, `8`=N — NB4 order)
  naming which orthogonal neighbours are also river cells (§1B seam fix). e.g. `3` = a plain
  width-3 river, `101` = a source, `53` = a width-3 river flowing W, `5141` = a source flowing
  W with river neighbours E+S. Nodes carry nominal width 1. `classifyRiver` returns the static
  part (width + node); `RiverFlow` folds in the flow digit (§3); `ProvinceRaster.riverAdjMask`
  folds in the adjacency digit. **Kept decimal-digit, not bit-packed:** the code is stored as
  gzipped-JSON *text* (`ProvincePlotStore`), so digits stay human-readable and gzip erases any
  size gap — byte-alignment would only pay off under a future fixed-width binary pack.
- **Why the mask (not the render-only fallback):** rivers are drawn per-province into
  separate offscreen canvases, so `drawRiver`'s neighbour lookup over a province's own grid
  stops at the bbox edge — a river **interrupts at every province seam**. The mask is computed
  globally (like the coast sea-mask and `RiverFlow`, where a neighbour in the next province
  *is* visible), so a border cell links across the seam and both sides meet at the shared edge.
  `drawRiver` still falls back to the in-province grid when the mask is `0` (older packs).
- **The field kept the name `river`, widened `boolean → int`.** Threaded through
  `ProvinceMask` (`int[] river`, new `riverCode(lx,ly)`) → `ProvincePlotField.ProvincePlot`
  (`int riverCode`) → `settlement.Plot` (`int river`) → `ProvincePlotStore.StoredPlot`
  (`int river`, same JSON key). `river()` **stays boolean** everywhere it is consumed
  (caravan routing, `featureFor` flood-plains, tests) as `code != 0`; a new `riverCode()`
  exposes the int for the web/Phase 2. Keeping the JSON key `river` means the **live site
  is not broken** by 1A landing before 1B — `if (q.river)` is still truthy-correct (int
  `0` is falsy).
- **No flow graph yet** — direction derivation is Phase 2. 1A only stops discarding.

Touch-points (all done): `ProvinceRaster` (`classifyRiver`/`widthLevel`), `ProvinceMask`,
`ProvincePlotField`, `settlement/Plot`, `settlement/ProvincePlotStore`, and the three
`new Plot(…, pp.riverCode(), …)` call sites (`WorldPlotGenerator`, `ProvincePlotPool`×2).
Grids regenerated (`mvn -o exec:exec -Dsim.main=…geo.export.WorldPlotGenerator`, 4710
provinces / 35 s); verified varied codes (widths 1–4 + node markers) across 2694 river
provinces; full `mvn test` green. `packPlots` picks the grids up on the next web build.

### Phase 1B — Web: bake the tile + draw the ribbon — **DONE (2026-07)**

Built as sketched below: `bakeRiverTile()` (`web/build.mjs`) bakes
`allriverssmall.dds` into `web/assets/river-<seed>.png` and ships it as `BUNDLE.river`;
`core.mjs` exports it as `RIVER`; `drawRiver()` (`web/js/plots.mjs`) replaces the flat
cell fill with the tapered connectivity ribbon, reading width from `q.river % 10`.
Verified headless (`tools/webverify/verify-pack.mjs`) on a river-rich province — the
ribbon follows the network with the water texture, zero console errors; graceful
fallback to the flat blue when the tile is absent (LFS/`file://`) is preserved.

**Refinement — real water strands (option A).** `bakeRiverTile()` originally recoloured
the Civ4 river texture to a flat *mean* (via `detailTile`), which discarded the water
pattern — because the wavy ripple **strands live in the DXT5 *alpha* channel** (mean ≈49),
not the near-flat RGB. It now bakes from that alpha: `k = 0.6 + 1.5·strand` modulates the
river-blue, so ripples read as bright dashes over darker water and the ribbon looks like
flowing water. This was chosen over Phase 3's Civ4 edge tiles (§4) — the faithful use of
the real Civ4 river art that fits our center-line pixel rivers.

**Build (`web/build.mjs`)** — bake one water tile, mirroring `bakeTerrainTiles`/`detailTile`
(which already decode a `.dds`, downsample, and recolour mean→target):

```js
const RIVER_RGB = [74, 124, 170];   // == today's flat-fill hue, keeps the palette cohesive
function bakeRiverTile() {
  const T = 64;
  const tile = detailTile('Art/Terrain/Routes/Rivers/allriverssmall.dds', RIVER_RGB, T);
  if (!tile) return null;            // graceful degradation, like bakeTerrainTiles (LFS/file://)
  fs.mkdirSync(path.join(WEB, 'assets'), { recursive: true });
  const file = `river-${SEED}.png`;
  fs.writeFileSync(path.join(WEB, 'assets', file), encodePng(T, T, tile));
  return { src: `assets/${file}`, tile: T };
}
```

Add `river: bakeRiverTile()` to the `BUNDLE` object written into `data.js` (beside
`terrainTiles`), and export it from `core.mjs` as `RIVER` (mirroring the `TT` export).

**Runtime (`web/js/plots.mjs`)** — load the tile like `ttImg`/`ttReady`, then **replace
line 212** inside `buildPlotTexCanvas` (where `grid` already maps coords→plot). Straight
segments / bends / T-junctions fall out of 4-neighbour connectivity for free (the same
`NB4` trick the terrain edge-blend uses). `q.river` is the packed int, so the **width is
`q.river % 10`** (the flow direction is `Math.floor(q.river / 10) % 10`, the node marker
`Math.floor(q.river / 100)` — neither used by the ribbon):

```js
let rvImg = null, rvReady = false;
if (RIVER) { rvImg = new Image(); rvImg.onload = () => { rvReady = true; draw(); }; rvImg.src = RIVER.src; }

// in buildPlotTexCanvas, once: const riverPat = rvReady ? o.createPattern(rvImg, "repeat") : null;
// replacing `if (q.river) { o.fillStyle = "rgba(74,124,170,.55)"; o.fillRect(...) }`:
if (q.river) drawRiver(o, cx, cy, tpp, q, grid, riverPat);

// A river plot's segment: a water-textured ribbon from cell centre to each 4-neighbour
// that is also river; a source blob when isolated. Width tapers by the packed river code's
// width digit (q.river % 10). Falls back to the flat fill colour when the tile is absent.
function drawRiver(o, cx, cy, s, q, grid, pat) {
  const isR = d => { const n = grid.get((q.x + d[0]) * 1e5 + (q.y + d[1])); return n && n.river; };
  const links = NB4.filter(isR);
  const lvl = Math.min(4, (q.river % 10) || 1);           // width digit 1..4; guard 0
  const mx = cx + s / 2, my = cy + s / 2, w = s * (0.16 + 0.06 * lvl);
  o.save();
  o.strokeStyle = pat || "rgba(74,124,170,.85)"; o.fillStyle = pat || "rgba(74,124,170,.85)";
  o.lineWidth = w; o.lineCap = "round"; o.lineJoin = "round";
  o.globalAlpha = pat ? 0.9 : 0.55;
  if (!links.length) { o.beginPath(); o.arc(mx, my, w * 0.6, 0, 7); o.fill(); }
  else for (const d of links) { o.beginPath(); o.moveTo(mx, my); o.lineTo(mx + d[0] * s / 2, my + d[1] * s / 2); o.stroke(); }
  o.restore();
}
```

The cheap 1px `buildPlotCanvas` (zoom 5–16) keeps its river *tint* (`plots.mjs:55`) —
a ribbon is meaningless at 1px/plot.

---

## 3. Phase 2 — flow direction (data product, no animation) — **DONE (2026-07)**

**The map is unchanged — flow direction is invisible data.** Phase 1 already conveys flow
*implicitly* through the authored width taper; Phase 2 derives the flow *direction* per
river cell as a **data product** — the seam for caravan river-navigation and downstream/
upstream gameplay, and the basis for Phase 3's directional edge tiles.

`RiverFlow.direction()` (`geo/RiverFlow.java`, pure + unit-tested) computes, for every river
cell, the downstream 8-neighbour direction `1..8` (`0` = a sink/mouth):

- **Width leads, elevation only breaks ties.** A cell flows to its *widest* river neighbour
  (a river grows toward its mouth); equal-width neighbours are settled by **lower
  elevation** (downhill), and a deterministic **cell-index tie-break** settles the rest —
  which also makes the directed graph **acyclic** (every edge points to a strictly greater
  cell in the `(score, index)` order → a flow forest rooted at the width maxima). Because
  width, not elevation, leads, the noisy near-flat valley-floor heightmap only *nudges* the
  result — **no pit-filling / flat-resolution needed**, sidestepping the classic D8 problem.
- **Global, not per-province.** Run once over the whole river raster in `ProvinceRaster`
  (a cell's true downstream neighbour can sit in the next province), then folded into each
  plot's code as the **tens digit** (§1A encoding). `river % 10` (width) is untouched, so
  the Phase 1B ribbon renders identically.
- **Decode:** `Plot.flowDir()` / `riverWidth()` / `riverNode()`; web `Math.floor(q.river/10)%10`.

Verified: `RiverFlowTest` (width-leads, elevation tie-break, width-beats-uphill, diagonal,
acyclicity); grids regenerated (4710 provinces); flow distributed across all 8 directions
with 12.3% sinks; width/node digit counts identical to pre-Phase-2; full `mvn test` green;
headless render unchanged.

**Dropped: drainage-accumulation width.** It was the doc's optional "one visible change,"
but this map's authored width is coarse (≈80% width-1) *and* a computed accumulation would
lean on the flat-valley heightmap — likely adding noise rather than a cleaner taper. The
authored width (Phase 1B) is the better taper signal, so accumulation is not pursued. Any
future flow *cue* would be a **static** glyph (a chevron), never motion.

---

## 4. Phase 3 — faithful Civ4 edge tiles — **investigated, not pursued (2026-07)**

**Outcome: the Civ4 river edge tiles are the wrong art for our data.** After rendering the
masks to look at them, the mismatch is fundamental, so Phase 3 was dropped in favour of the
option-A water-strand ribbon (§2) — and the *edge-tile* idea is redirected to **coastlines**
(`docs/coastlines.md`), where it actually fits.

What the art is (all DXT5, decodable via `dds.mjs`): `allrivers*.dds` = the river **water
fill**; `border00a` · `border01[a-h]` · `border02[a-h]` · `border03..05[a-d]` = **edge/
corner alpha masks** (6 configs × 4–8 orientations = **29**, a river band along one edge
with corner variants, rotated by the `a–h` suffix); `noriverborder`/`norivercurvedborder` =
dry edges; `riverwisps0N` = foam. There is **no Civ4 XML** for any of it — no `data/civ4/`
file references them and there is no `ROUTE_RIVER`; the engine picks tiles by convention.

Why it does not fit: these are **edge** decals for Civ4's **edge-based** rivers (a river
runs along the boundary *between* two plots). Our rivers come from **EU4 `rivers.bmp`, which
is pixel-based** — a river is a chain of *cells* whose line runs **through cell centres**
(Phase 1B renders exactly that). An edge decal can draw "river along my north edge" but not
"river passing through my centre N→S", so using the border tiles would require converting
our centre-line rivers to an edge model — a lossy, half-cell-shifted reinterpretation that
would look *worse* than the ribbon. So there is no `RiverArtExporter` and no
`bakeRiverTiles`; the ribbon is the faithful-art answer for pixel rivers.

> **Where the edge machinery does belong — coastlines.** The land/sea boundary genuinely
> *is* a plot edge, so the Civ4 coast art (`heightmap/coastblendmasks/coastscalemask00–15`,
> `textures/water/*`) and a per-plot **sea-edge mask** (global, computed in `ProvinceRaster`
> like the flow direction) fit cleanly. That is the next feature — see `docs/coastlines.md`.

> **Not to be confused with routes.** `data/civ4/CIV4RouteModelInfos.xml` (roads / rails /
> paths / tunnels — no rivers) *is* XML-bound and drives the separate **routes** feature
> via a `RouteModelExporter` → `map/route-models.json` (`ported-terrain-art-system.md §6`).

---

## 5. Verification

- `node web/build.mjs <seed>` → `web/assets/river-<seed>.png` written; **absent-tolerant**
  (the river texture resolves from `data/civ4/assets`; if absent, the page flat-fills the ribbon).
- Serve over **HTTP** (terrain zoom needs HTTP, not `file://`); eyeball a river province
  past 16× with `tools/webverify`.
- Phase 2/3: assert the river network is connected and (for directed edges) acyclic per
  drainage basin; spot-check a known river runs source→mouth the right way.

---

## 6. Reuse

The Phase 1B connectivity ribbon and the Phase 2 directed-flow model generalise to the
other 2D-decodable route art — `routes/Roads`, rails, bridges, docks — which is the natural
next feature after rivers. Bonuses/features remain gated on the offline `.nif`→sprite
baker (`docs/ported-terrain-art-system.md` §11), a separate track.
