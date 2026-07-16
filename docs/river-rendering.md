# River rendering on the WorldMap

**Status:** shipped (2026-07). Rivers draw as a **water-textured centre-line ribbon** that tapers
from a headwater thread to a trunk "highway of water", with terrain visible on both banks. The
width is real: it comes from each cell's **drainage catchment**, derived from a flow network that
is **rooted at the sea**.

Cross-refs: the terrain-art pipeline this rides on is `docs/ported-terrain-art-system.md`. The
auto-tiled route renderer (`docs/route-rendering.md`) is the sibling feature — same centre-line
plot model, and the reason `river-geom.mjs` is split from the draw code the way `route-tiling.mjs`
is.

---

## 1. The data

**Source: `rivers.bmp`** (Anbennar/EU4) — an 8-bit indexed 5632×2048 BMP whose non-white pixels
encode, by colour, **river width levels** (a blue ramp, narrow→wide) plus **node markers**. Because
it is indexed the entries are exact (no anti-aliasing), so the **dominant channel** classifies
unambiguously — see `ProvinceRaster.classifyRiver`.

| Colour | RGB | Meaning |
|--------|-----|---------|
| white `#ffffff` | 255,255,255 | land — no river |
| grey `#797b78` | 121,123,120 | sea |
| blue ramp | `#00dffb` … `#461f80` | **authored width**, cyan→deep-blue = narrow→wide |
| green | `#1cf118` / `#33830d` | **source** node |
| red | `#e51b02` | **flow-in / confluence** node |
| yellow | `#e2e311` | **split** node |

**Our rivers are PIXEL-based, not edge-based**: a river is a chain of *cells* whose line runs
**through cell centres**. This one fact drives every render decision below — and it is what killed
the Civ4 river art (§5).

### The packed code

Everything about a river cell rides in one int on the plot, as decimal digits. (Kept decimal, not
bit-packed: the grid is stored as gzipped-JSON *text*, so digits stay readable and gzip erases any
size gap.)

| Digit | Field | Range | Decoder |
|-------|-------|-------|---------|
| `1` | authored width | 1..4 | `Plot.riverWidth()` |
| `10` | downstream flow direction | 1..8 (0 = mouth) | `Plot.flowDir()` |
| `100` | node marker | 1 source, 2 confluence, 3 split | `Plot.riverNode()` |
| `1000` | river-adjacency mask (**two digits**) | 0..15 (1=E, 2=W, 4=S, 8=N) | `Plot.riverAdj()` |
| `100000` | **render width class** | 1..9 | `Plot.riverClass()` |

e.g. `915384` = class 9, adjacency 15, a split, flowing SE, authored width 4.

> **The adjacency field spans TWO digits** (it reaches 15). That is why the class sits at `100000`,
> and why every adjacency demask is **`% 100`, not `% 16`** — a `% 16` folds the class digit back in
> as garbage (`915384 / 1000 = 915`; `915 % 16 = 3`, not 15). Adding the class bit three call sites
> this way (`Plot.riverAdj`, `FeatureGenerator`, and `ProvincePlotFieldTest` itself);
> `ProvincePlotFieldTest.riverCodeFieldsDoNotCollide` now guards the packing on real map data.

**Why the adjacency mask exists:** rivers are drawn per-province into separate offscreen canvases,
so a neighbour lookup over a province's *own* grid stops at its bbox edge — a river would
**interrupt at every province seam**. The mask is computed globally
(`ProvinceRaster.riverAdjMask`, like the coast sea-mask and the flow network), so a border cell
links across the seam and both sides meet at the shared edge. `riverLinks` still falls back to the
in-province grid when the mask is `0` (older packs).

The field is named `river` and is an `int`. `river()` **stays boolean** everywhere it is consumed
(caravan routing, `featureFor` flood-plains, tests) as `code != 0`, so the JSON key never changed
and any pack decodes truthily.

---

## 2. The ribbon — `river-geom.mjs` + `plots.mjs drawRivers`

Pure geometry lives in `web/js/river-geom.mjs` — **zero imports**, so it unit-tests under
`node --test` without the browser globals `core.mjs` pulls in (`river-geom.test.mjs`; the same
split as `route-tiling.mjs` / `routes.mjs`). Painting is `drawRivers` in `plots.mjs`, baked once
into the cached province canvas, so it costs nothing per frame.

- **A cell's ribbon runs from its centre out to the shared edge with each linked neighbour.** Two
  properties fall out for free:
  - adjacent cells meet **exactly** at the shared-edge midpoint — including **across a province
    seam**, where each province's canvas draws its own half and the round cap completes the join;
  - a bend is a **quadratic through the cell centre**, so a turning river reads as a river rather
    than a staircase. Junctions (tee/cross) stay unsmoothed spokes — a junction is genuinely a
    corner, and curving one branch into another would imply a flow that isn't there.
- **Width comes from the class**, and cells are **bucketed by class into one `Path2D` each**. Width
  is constant *within* a cell and only steps *between* cells, so a plain `stroke()` suffices — no
  variable-width polygon offsetting — and the round cap hides the step. Width changes slowly (one
  class per doubling of catchment), so steps are invisible except at confluences, which is exactly
  where a real river visibly widens.
- **Three passes**, every class's bank before any water, so a tributary's bank can never cut a dark
  line across the trunk it joins: **bank** (dark, `w + 2·bank`) → **shallows** (light rim,
  `w + bank`, mirroring the sea coast) → **water** (the texture, `w`). The banks follow the
  *water*, not the plot square.
- **`CLASS_WIDTH` lives in the web, not the engine.** The class is the *data*; the class→pixels
  curve is the *look*, so it retunes with no map rebake.
- Absent the baked tile (LFS not pulled / `file://`), the water pass falls back to flat blue.
- The cheap 1px `buildPlotCanvas` (zoom 5–16) keeps its river **tint** — a ribbon is meaningless at
  1px/plot.

**The water tile** (`bakeRiverTile`, `build.mjs` → `BUNDLE.river` → `RIVER`): Civ6's
`SV_TerrainHexCoast` recoloured river-blue, or C2C's `allriverssmall.dds` as fallback — whose
ripple strands live in the DXT5 **alpha**, so the bake modulates the blue by `k = 0.6 + 1.5·strand`
and the ripples read as bright dashes over darker water. Civ6's own `TER_River_Water` was rejected:
it has **baked-in flow arrows**.

> **What this replaced.** The previous `drawRiver` flooded each river plot's **entire square** with
> the water texture (`fillRect(cx, cy, s, s)`) and outlined the **cell edges** — blocky, opaque over
> the terrain, and blind to width (it never read the width digit its own comment claimed it did). It
> had been changed from a stroke to a full tile because a thin stroke "only showed a sliver" of
> texture. The real answer was a *wider, tapered* stroke, not a square.

---

## 3. Flow direction — rooted at the sea

`RiverFlow.derive()` (`geo/RiverFlow.java`, pure + unit-tested) returns a `Network(dir, acc)` for
the whole river raster at once. **Every river cell touching open water is a mouth; flow is the
shortest path through the network back to one**, found by a multi-source BFS out from all mouths at
once. Because a river network is essentially a tree, that BFS path is the **only** path — so
nothing about the terrain can corrupt it.

- **Global, not per-province** — a cell's true downstream neighbour can sit in the next province.
- **Endorheic systems** (no sea contact) root at the cell likeliest to be the terminus: widest,
  then lowest, then lowest index for determinism.
- **Elevation and authored width are tie-breaks for that fallback root only** — never flow signals.
- Decode: `Plot.flowDir()`; web `Math.floor(q.river/10)%10`.

### Post-mortem: the two derivations that failed

Both were measured on the real map, which has **465 connected river systems**:

| derivation | roots | maxAcc | verdict |
|---|---|---|---|
| width-following + elevation tie-break | 13,630 | 196 | shattered |
| width-following, no elevation | 7,294 | 419 | shattered |
| **sea-rooted BFS** | **5,233** | **5,012** | shipped |

1. **Steepest-descent (D8) on the heightmap** — never shipped, and rightly: the heightmap is
   near-flat and noisy exactly on the valley floors where rivers live, so it drowns in pits.
2. **Follow the authored width downhill** (the original Phase 2) — assumed width grows
   monotonically toward the mouth. It does not: the authored width plateaus and wobbles, so **every
   local width maximum became a sink**; and the elevation tie-break inside uniform-width stretches
   (71% of cells are width-1) re-imported the very D8 pit problem it was designed to dodge. The
   result was a flow "forest" of ~9-cell fragments, and an accumulation over it was meaningless.

The 5,233 sea-rooted mouths still exceed 465 because a system meeting the coast at several points
legitimately has several mouths (a delta).

---

## 4. Width class — the drainage catchment

`Network.acc` is each cell's **drainage accumulation**: how many river cells drain through it,
counting itself — 1 at a headwater, growing seaward, peaking at a mouth. It is one sweep back along
the BFS order (a parent always precedes its children, so a single pass totals every subtree),
O(cells).

`ProvinceRaster.widthClass(acc, authoredWidth)` maps it to the render class **1..9, one class per
octave** — a river must **double its catchment to widen a step**, which is what turns a raw
1..5012 range into a taper the eye reads as growth. It is **floored by the authored width**, so a
channel the mod drew wide never renders as a trickle where our catchment disagrees with the authors.

Measured over the whole map (116,550 river cells), classes 1..9 =
`[22535, 11478, 5051, 11508, 11165, 14464, 14159, 10868, 15322]` — ≈19% thread, ≈13% trunk.

**Why not just use the authored width?** It is coarse and bimodal — 70.8% width-1, 15.7% width-2,
1.9% width-3, 11.6% width-4 — so it separates "big river" from "small river" but cannot taper one
river *along its length*, which is the thing that reads as flow. It is kept in the code (and
`Plot.riverWidth()`) for future gameplay use, and as the class's floor.

**The invariant:** every cell drains to exactly one mouth, so the mouths' accumulations **sum to
the river-cell count** — asserted in `RiverFlowTest` and confirmed on the real map (116,550 =
116,550). It is also the acyclicity guard: a cycle would strand cells outside the sweep and break
the sum.

---

## 5. Civ4 river edge tiles — investigated, not pursued

**The Civ4 river art is the wrong art for our data.** `allrivers*.dds` = the water fill;
`border00a` · `border01[a-h]` · … = **edge/corner alpha masks** (29 of them); `riverwisps0N` = foam.
There is **no Civ4 XML** for any of it and no `ROUTE_RIVER` — the engine picks tiles by convention.

Why it does not fit: these are **edge** decals for Civ4's **edge-based** rivers (a river runs along
the boundary *between* two plots). Ours are pixel-based, with the line through cell centres. An edge
decal can draw "river along my north edge" but not "river passing through my centre N→S", so
adopting them would mean a lossy, half-cell-shifted reinterpretation that looks *worse* than the
ribbon.

> **Where the edge machinery does belong — coastlines.** The land/sea boundary genuinely *is* a plot
> edge, so the Civ4 coast art + a per-plot sea-edge mask fit cleanly there (`docs/coastlines.md`).

> **Not to be confused with routes.** `CIV4RouteModelInfos.xml` (roads/rails/paths — no rivers) *is*
> XML-bound and drives the separate **routes** feature (`docs/route-rendering.md`). Roads are
> centre-line and connectivity-tiled — the same model as our rivers, which is why that renderer, not
> the river art, was the right thing to learn from.

---

## 6. Verification

- `mvn -pl civstudio-engine test` — `RiverFlowTest` (mouth-rooting beats a width maximum, confluence
  sums, the sum-to-cell-count invariant, monotonic-downstream, endorheic fallback, acyclicity);
  `ProvincePlotFieldTest.riverCodeFieldsDoNotCollide` + `riverAdjacencyMaskMatchesRiverNeighbours`
  (the packing, on real data).
- `node --test web/js/river-geom.test.mjs` — decode (incl. the `%100` regression and the
  pre-class-pack fallback), seam meeting, bends, junctions, width monotonicity.
- Serve over **HTTP** (terrain zoom needs HTTP, not `file://`); eyeball a river province past 16×
  with `tools/webverify/shot.mjs` (which needs `?live=<server>` to skip the server picker — it
  passes one by default).
- A class change is a **generation change**: bump `MAP_VERSION` → **CI rebake**
  (`regenerate-map.yml`) → **then** roll the server, in that order (`docs/client-server.md`
  §Deployment). Do **not** delete the prod cache: the bump already invalidates it (the cache is
  `<share>/map/v<MAP_VERSION>`), and `map/v<N>` holds GeoNames names production cannot regenerate.
  And `MAP_VERSION` is a static-final int that **inlines** into the server classes, so a bump needs
  `mvn -pl civstudio-server clean compile`.
