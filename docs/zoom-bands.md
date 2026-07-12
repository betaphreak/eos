# Zoom bands — the continuous-zoom spine

> **Status: plan / not yet built.** This is the design spec for refactoring the web
> frontend around one continuous zoom axis divided into nine logical *bands*. It
> supersedes the scattered `cam.k` thresholds documented (as-built) in
> `web/README.md` and the per-layer fade code. Band **names and regime boundaries
> are provisional** — they are game-design calls the owner finalizes.

## The idea in one paragraph

The WorldMap already runs on a single continuous magnification scalar, `cam.k ∈
[1, 256]` (`core.mjs`). That one number is the spine that blends three UX regimes:
**EU4-style strategy at the macro end (1×), caravan/overland play in the middle,
and city-builder micro at the deep end (256×)**. Today "which regime am I in" is
re-derived ad-hoc in ~20 places as `(cam.k - X)/Y` ramps and `cam.k < Z` cutoffs
across seven files, with two independent fade mechanisms. This refactor makes the
regime structure *explicit and central*: everything that draws or accepts input
declares which **band(s)** it lives in, and a single helper cross-fades it as the
continuous zoom slides through. The continuous zoom masks the logical banding — you
never see a "level" snap; layers dissolve into one another.

## The band coordinate

```
band b = log2(cam.k)          // cam.k 1→256  ⇒  b 0→8
```

Nine bands land on the powers of two:

| b | cam.k | | b | cam.k | | b | cam.k |
|---|-------|-|---|-------|-|---|-------|
| 0 | 1× | | 3 | 8× | | 6 | 64× |
| 1 | 2× | | 4 | 16× | | 7 | 128× |
| 2 | 4× | | 5 | 32× | | 8 | 256× |

A drawable does **not** read `cam.k` or `b` directly. It declares a **band
envelope** — a trapezoid in band units `[in0, in1, out0, out1]` — and reads its
alpha from one shared helper. This is a generalization of the `tierAlpha()`
trapezoid already used (and duplicated) by `labels.mjs` and `tiers.mjs`.

### `js/bands.mjs` (new — the registry + math)

```js
// canonical continuous band position; everything reads this, never cam.k
export const band = () => Math.log2(cam.k);          // 0 … 8

// trapezoid: 0 outside [in0,out1]; ramp in [in0,in1]; hold 1 to out0; ramp out to out1.
// Use Infinity for out0/out1 to "fade in and stay" (e.g. terrain textures).
export function bandAlpha([in0, in1, out0 = Infinity, out1 = Infinity]) {
  const b = band();
  if (b <= in0 || b >= out1) return 0;
  if (b < in1)  return (b - in0) / (in1 - in0);
  if (b <= out0) return 1;
  return (out1 - b) / (out1 - out0);
}

// named band constants (see table) + regime helpers for the INPUT spine
export const BAND = { WORLD:0, REALM:1, REGION:2, PROVINCE:3, TERRAIN:4,
                      LOCALE:5, PLOT:6, PARCEL:7, STRUCTURE:8 };
export const REGIME = { ATLAS:"atlas", OVERLAND:"overland", GROUND:"ground" };
export function regime() {                             // current interaction regime
  const b = band();
  return b < 3 ? REGIME.ATLAS : b < 6 ? REGIME.OVERLAND : REGIME.GROUND;
}
export const atLeast = n => band() >= n;               // hard gate when a fade is wrong
```

Every existing `Math.max(0, Math.min(1, (cam.k - A)/(B - A)))` and every
`cam.k < Z` becomes a `bandAlpha([...])` or `atLeast(BAND.X)` call. The two fade
mechanisms collapse to one.

## Layer registry — draw order + band mapping in one editable place

**Which layer sits in which band, and the order layers paint in, are the two most
frequently-tuned knobs — so they must be data in one file, never control flow spread
through `renderScene`.** *(Shipped — Phase 3.)* The imperative draw sequence that lived in
`main.renderScene()` is now the ordered registry `js/layers.mjs`; array order **is** the
back-to-front paint order, so reordering a layer is moving its line:

```js
// js/layers.mjs — the single source of truth for draw ORDER (array order) + GATING + band note.
export const LAYERS = [
  { id:"raster",       band:"all",                     draw: drawRaster },
  { id:"lakes",        band:"all",                     draw: drawLakes },
  { id:"seaCells",     band:"all",  gate:notPolitical, draw: drawSeaCells },
  { id:"gapHatch",     band:"≥PLOT (64×)", gate:()=>atLeast(BAND.PLOT)&&notPolitical(), draw: drawGapHatch },
  { id:"plots",        band:"≥REGION→, self-fade", gate:notPolitical, draw: drawSurfacePlots },
  { id:"cost",         band:"≥REGION→, toggle",        draw: drawCostOverlay },
  { id:"impassable",   band:"all",  gate:notPolitical, draw: drawImpassable },
  { id:"political",    band:"self-fade", gate:isPolitical, draw: drawPolitical },
  { id:"tiers",        band:"WORLD–PROVINCE, self-fade", draw: drawTiers },
  { id:"provBorders",  band:"PROVINCE (7.5→10×)",      draw: drawProvinceBorders },
  { id:"underworld",   band:"all",  gate:()=>S.plane==="underworld", draw: drawUnderworld },
  { id:"caveEntrances",band:"all",  gate:overworld,    draw: drawCaveEntrances },
  { id:"adjacencies",  band:"≥3.3 (10×)",              draw: drawAdjacencies },
  { id:"hover",        band:"all",                     draw: drawHoverHighlight },
  { id:"selected",     band:"all",                     draw: drawSelectedHighlight },
  { id:"live",         band:"all",  gate:()=>S.overlay==="live", draw: drawLive },
  { id:"tradeGoods",   band:"TERRAIN→PLOT, self-fade", gate:()=>overworld()&&notPolitical(), draw: drawTradeGoodIcons },
  { id:"labels",       band:"≥PROVINCE, self-fade",    draw: drawLabels },
  // { id:"city",      band:"GROUND", gate:()=>regime()===REGIME.GROUND, draw: drawCity },  // Phase 5
];
```

A layer descriptor is `{ id, band, gate?, draw }`:

- **array position** — the z-index; the registry paints top-to-bottom. Reordering = moving a line.
- **`band`** — a human annotation of where the layer lives on the spine. The actual fade is
  single-sourced *inside* each `draw` via `bandAlpha` (Phase 2); most layers self-fade, so the
  runner stays a pure gate+draw. (A follow-up could lift the envelope into the table and have the
  runner pass `bandAlpha(env)` into `draw(alpha)` for the flat-alpha layers — deferred, not needed.)
- **`gate`** — a cheap predicate (`notPolitical`, `isPolitical`, `overworld`, an overlay/plane/
  regime check) that skips the layer entirely.

The **draw fns live in the modules that own their state** — `main.mjs` keeps the ones that close
over the raster/camera and the province-polygon/`Pby`/hatch helpers (and exports them); the
overlays own theirs. `layers.mjs` only imports and orders them. `renderScene()` is now:

```js
function renderScene() {
  if (cam.k < 10) ensureTiers(draw);   // tier geometry lazy-load (data, not a draw layer)
  renderLayers();                      // paint LAYERS in order for this world copy
}
```

The per-world-copy wrap and scene clip stay in `paint()` around this; only the layer *sequence*
moved into data. `main` ↔ `layers` is a deliberate import cycle — safe because every draw is a
hoisted function declaration, initialised before the `LAYERS` array is built.

## The nine bands

Names are **function-first** and provisional. The three regimes group the bands
into the three UX feels; interaction mode switches at regime boundaries (the input
spine — see below).

| Band | Zoom | Name | Regime | **Draws here today** | **Target / additions** |
|---|---|---|---|---|---|
| **0** | 1× | **World** | 🌍 Atlas | Ocean climate gradient + ripple (full), continent labels & tier borders, political fills full-opacity | — (macro is the mature end) |
| **1** | 2× | **Realm** | 🌍 Atlas | Super-region labels & tier borders | — |
| **2** | 4× | **Region** | 🌍 Atlas | Region labels & tier borders; plots + cost begin fade-in (k5≈b2.3); political fill starts tapering | — |
| **3** | 8× | **Province** | 🐫 Overland | Province names, province borders, sea/lake names, straits/canals/tunnels appear (k≥10≈b3.3), tier lazy-load stops | Colony/settlement markers become first-class; caravan routes read as lines |
| **4** | 16× | **Terrain** | 🐫 Overland | **K_TEX**: real Civ4 textures, trade-good icons appear, plot hover on, sea ripple gone, political→borders-only, live colony marker→city-sprite | Caravans get band-scaled presence; overland is the caravan "home" band |
| **5** | 32× | **Locale** | 🐫 Overland | Deep terrain; trade-good icons hold, begin fade at k48 | Named locales / points of interest; hand-off to Ground |
| **6** | 64× | **Plot** | 🏘️ Ground | Gap-grid hatch (k>64), trade-goods gone, per-plot resource/bonus icons prominent | **City-micro skeleton begins**: building footprints per developed plot |
| **7** | 128× | **Parcel** | 🏘️ Ground | Bonus icons scale with `cam.k/K_MAX`; nothing else band-specific | Agent/household dots from the live feed; street/road hints |
| **8** | 256× | **Structure** | 🏘️ Ground | **K_MAX**: deepest plot magnification | Individual buildings + agents (laborer/noble/ruler/firm) legible |

Zoom-independent chrome (sea-cell wash, impassable hatch, hover/selection
highlights, minimap, HUD/sidebar/timeline, underworld plane veil) draws across all
bands today. Under the refactor each gains an explicit envelope where it helps
(e.g. minimap should *hide* in Ground; highlight stroke should thin with depth).

## The three regimes — the input spine

Per the "visual **and** input spine" decision, the band is the game's core
mode-switch, not just an LOD ladder. A click/hover/drag means different things per
regime:

| Regime | Bands | Feel | Primary object a click targets | Overlays that make sense |
|---|---|---|---|---|
| 🌍 **Atlas** | 0–2 (1–4×) | EU4 grand strategy | Nation / culture / faith / province | Political (nation/culture/faith), geography tiers |
| 🐫 **Overland** | 3–5 (8–32×) | Caravan / operational | Caravan, trade route, colony/settlement, province terrain | Live (Spectate), trade goods, physical terrain |
| 🏘️ **Ground** | 6–8 (64–256×) | City builder / tactical | Plot, building, agent/household | Physical terrain, per-plot resources, city detail |

Regime boundaries are `band() < 3` / `< 6`. `panel.mjs`'s hit-testing (`provinceAt`,
`plotAt`) becomes regime-dispatched: Atlas → province/polity pick; Overland → caravan
/ colony / province pick; Ground → plot / building / agent pick. This is the largest
*behavioral* change and is why the input spine was called out as a design decision.

### Crossing a boundary gives a UI signal

Because the click-target changes at a boundary, the crossing is signalled — three
coordinated pieces:

1. **Mode chip** — the top-left readout *is* the current **band name**, iconed + tinted by the
   regime (e.g. `🐫 Terrain`), with the raw `×` zoom in its tooltip. The band name answers "where am
   I on the spine"; the icon/accent answer "what regime am I in". Crossing a boundary swaps both.
2. **Regime cursor** — the canvas cursor swaps per regime (Atlas grab/arrow, Overland a
   route reticle, Ground a plot crosshair): the felt, wordless signal that
   click-semantics changed.
3. **Transition pulse** — on an actual crossing (not every frame) the chip animates its
   icon/label swap and a subtle accent vignette flashes at the viewport edge (~400ms).
   Fired once per crossing by a `lastRegime` latch in the paint loop.

**Hysteresis is mandatory** — zoom is continuous, so a scroll-tick can land on a seam.
Each boundary gets a deadband: enter Overland at `b ≥ 3.0`, fall back to Atlas only at
`b < 2.85` (same ±0.15 around `b = 6`), else the chip/cursor/vignette strobe. `regime()`
therefore has a stateful variant that reads the previous regime to apply the deadband:

```js
let _regime = REGIME.ATLAS;                    // latched, hysteretic
export function regime() {
  const b = band(), lo = 3, hi = 6, d = 0.15;  // deadband ±0.15 band around each seam
  const up = _regime === REGIME.ATLAS ? lo : lo - d,      // asymmetric thresholds
        dn = _regime === REGIME.GROUND ? hi : hi + d;
  _regime = b < up ? REGIME.ATLAS : b < dn ? REGIME.OVERLAND : REGIME.GROUND;
  return _regime;
}
```

## Z-levels — the vertical axis, orthogonal to bands

The band spine answers *"how deep am I looking?"* (`cam.k` 1→256, the zoom). A separate axis answers
*"which vertical level am I looking at?"* Today that axis is the binary `S.plane ∈ {overworld,
underworld}` toggle (`docs/underworld.md`), but it is really the first instance of an integer
**z-level**:

- **z = 0** — the surface (and surface holds, and the impassable mountains that sit *on top of*
  underground provinces).
- **z = −1** — the Underworld (the Serpentspine).
- future: **+1** and deeper **−2…** as provinces gain z-levels.

So the render has three orthogonal axes: **band** (zoom depth, 0–8), **z-level** (vertical stacking,
integer), **overlay** (None / Political / Live). They compose independently — each z-level has its
**own full 0→8 band progression** (the Serpentspine has its Atlas-macro cave network and its
Ground-micro dwarven hold, exactly as the surface does).

**Z-level is per-province, and columns stack.** A single map column can carry provinces at several
levels at once — e.g. a z=0 surface hold or impassable mountain directly *above* a z=−1 cavern. So
membership is a province field (`p.z`), not a whole-map mode: `isUnderground(p)` (today keyed on
`p.type`) becomes `p.z === −1`, and the surface set is `p.z === 0`.

**Committed architecture: a per-z-level layer set.** The registry becomes keyed by z-level — one
ordered `LAYERS` list per level — and the z-level selector (today's Overworld/Underworld toggle,
tomorrow a −1 / 0 / +1 control) picks which level's list `renderLayers()` walks. `drawUnderworld`'s
hand-rolled internal stack (veil the level above → cave floors → per-plot terrain at the plot band →
amber rims) folds into the **z=−1 list as first-class registry entries**, ending the current
asymmetry where the whole underground is one opaque layer while the surface's equivalents are
individual entries. Gating by `isUnderground`/`isSurface` becomes gating by z-level — a province is
drawn on each level it occupies.

**Plots are per (province, z-level).** A province that spans z-levels owns **one plot grid per level**
it occupies — a dwarven-hold column carries a surface (`z=0`) grid *and* one for each underground
level; an ordinary province carries a single grid. So the frontend's `province._plots` becomes
`_plotsByZ[z]`, the plot cache / `plotIndex` key and the `/api/plots/{id}` route gain a z
(`/api/plots/{id}/{z}`), the generation-versioned cache keys on z too, and the plots layer for level
*z* draws each present province's `_plotsByZ[z]`. Engine-side, `WorldPlotGenerator` emits a grid per
occupied (province, z).

**Every underworld province gets a generated `z=0` cap.** The overworld directly above a Serpentspine
province is generated as **impassable mountain terrain** (it is mostly mountains anyway), so columns
are complete — a z=−1 cavern always has a z=0 impassable-mountain surface over it, with surface hold
provinces the passable exceptions. This fills the surface map above the underground instead of leaving
a hole, and is the concrete source of the "impassable mountains on top of underground provinces sit at
z=0" rule.

Rendering neighbours: viewing z=−1, the level above recedes to a ghost (as the veil does today);
shafts/cave-entrances mark where columns connect **across** z-levels (a vertical adjacency), the
same role straits/tunnels play *within* a level.

**In the current (pre-z-level) registry the plane is a `gate`**, beside `isPolitical`:

```js
{ id:"underworld",    gate:()=>S.plane==="underworld", draw: drawUnderworld },
{ id:"caveEntrances", gate:overworld,                  draw: drawCaveEntrances },
{ id:"tradeGoods",    gate:()=>overworld()&&notPolitical(), ... },   // off underground
```

That's the seam the per-z-level layer set replaces.

### Switching z-levels — the Google-indoor floor picker

Switching levels follows the **Google Maps indoor** pattern: a **context-sensitive vertical z-stack**
on the map's right edge, top = highest, the active level lit in the regime accent:

```
  +1   Towers / holds above
▶  0   Surface
 −1   Serpentspine
 −2   Deep roads
```

- **Context-sensitive** — the picker appears only when the current view (or the selected column)
  spans more than one z-level; the surface-only majority of the map shows no picker. Its entries are
  the z-levels present under the viewport/column (columns stack, §above). This is today's binary
  Overworld/Underworld toggle generalised to N levels.
- **Scope follows the band** (decided): at **Atlas/Overland** (macro/mid) the picker switches the
  **global** active level — pan the whole continent-sized Serpentspine at `z=−1`; at **Ground**
  (city-micro, inside a hold) it becomes a **local floor-stack** for the focused hold, the
  surrounding map staying surface. Reuses the regime spine — global plane where the underground is a
  realm, Google-local floors where it's a building.
- **Switching = the per-z-level layer set**: it sets `activeZ`, `renderLayers()` walks that level's
  `LAYERS`, and the levels above recede to a ghost veil — the chosen level lit *in place* under a
  dimmed ghost, exactly a floor swap.
- **Spatial descent** (beyond the picker): clicking a **cave-entrance / shaft** glyph *enters* the
  connected level, centred on the cavern (the vertical adjacency across z-levels) — Google's "tap the
  building to go inside." Plus keyboard **PgUp/PgDn** (or `[`/`]`) to step levels.

Interaction: the z-level is orthogonal to the regime, so the mode chip shows both (e.g.
`🐫 Terrain · z−1 Serpentspine`); hit-testing picks provinces at the active z-level.

### Introducing z-levels — data-model & migration plan

The frontend registry is z-ready (Phase 5); making z *real* is an engine/data change, kept purely
additive — **province ids never change**.

**`province.z` — native level by `ProvinceType`:**

| ProvinceType | z | note |
|---|---|---|
| (any surface) | `0` | default |
| `DWARVEN_HOLD_SURFACE` | `0` | surface hold access (passable) |
| `CAVERN`, `DWARVEN_HOLD` | `−1` | the Serpentspine / default holds |
| `DWARVEN_ROAD` (Dwarovrod) | `−2` | the deep roads |

**Column completion — generated z=0 caps.** Every underground province (`z<0`) *also* occupies `z=0`
as a generated **impassable-mountain** grid (it's mostly mountains anyway), so there are no holes above
the underground; real surface holds are the passable exceptions. A province therefore has a *set* of
occupied levels and one plot grid per level (`_plotsByZ`).

**Touch-points (all additive):**
- `Province` model: a native `z` + occupied-levels set; the cavern/hold exporters (`CavernExporter`, …)
  stamp `z` from type, and the current `isUnderground(type)` test seeds it.
- `WorldPlotGenerator`: emit a grid per occupied `(province, z)`; the `z=0` cap is a cheap procedural
  impassable-mountain fill.
- Plot pipeline: `plotIndex`, the generation-versioned plot cache and `/api/plots/{id}` all gain a `z`
  (`/api/plots/{id}/{z}`); `WorldBundle` ships each province's occupied levels.
- Frontend: `province._plots` → `_plotsByZ[z]`; the plots/cavern layers draw `_plotsByZ[activeZ]`;
  `activeZ()` reads a real active level once the z-selector lands (today it maps the plane toggle).

**Migration is a no-op for existing data:** today's single plane *is* `z=0`, and the binary Underworld
view already renders as the `z=−1` layer set (Phase 5). Introducing real z only *adds* levels — nothing
at `z=0` moves.

## The right-side panel — a regime-scoped inspector that drills

The panel follows the input spine: because a click targets a different object per
regime, the inspector that shows it must too. It is **one inspector on a drill-path
breadcrumb** (`Nation ▸ Province ▸ Plot ▸ Household`), not three panels that
hard-swap — zooming *deepens* the selection and zooming out *re-broadens* it, and
ambient sections cross-fade with the band exactly like map layers.

| Regime | Panel identity | Selected-entity card | Ambient / no-selection content |
|---|---|---|---|
| 🌍 **Atlas** (0–2) | **Almanac** (strategic) | Nation / culture / faith / region: holdings, demographics, political facts, geo crumbs | Political-overlay legend + coverage counts; polity/region search |
| 🐫 **Overland** (3–5) | **Dispatch** (operational) | Caravan cargo/journey/ETA, colony population/treasury/markets, province terrain, route endpoints | Live session HUD (clock/speed/tax) + event log — the Spectate chrome *is* this regime's ambient panel |
| 🏘️ **Ground** (6–8) | **Registry** (micro) | Plot terrain/yield/improvement, building type/`dev`, household/agent name/skills/wealth/family | The hovered settlement's roster; plot-grid key |

Three rules keep it coherent with the continuous zoom:

1. **Drill-path persistence.** The selected entity stays selected across regimes and
   *accretes* detail (province in Atlas → its colony/markets in Overland → its plots
   in Ground); ancestors remain as collapsible breadcrumb context, so the panel never
   blanks. Zooming out pops the path.
2. **Ambient sections are enveloped** with the same `bandAlpha` — the political legend
   fades out leaving Atlas, the live HUD fades in across Overland, the roster fades in
   in Ground. The **selected-entity card stays pinned** through the transition; only
   the ambient sections swap.
3. **No selection → regime overview**, so the panel is always useful: Atlas a
   world/nation summary, Overland the active colony list, Ground the hovered settlement.

This re-homes what's scattered today: the Spectate HUD (`live.mjs`) + event log
(`livelog.mjs`) become Overland's ambient panel; the province-detail sidebar
(`panel.mjs`) becomes the drill-path card; the political legend becomes Atlas's
ambient section. `panel.mjs` gains a `regime()`-keyed section registry that mirrors
the map's band registry — panel and canvas run off the *same* spine.

## Migration map — every scattered threshold → a declared band

The table below is the complete consolidation target. Left: today's inline code.
Right: the band envelope it becomes. (Envelopes are expressed in band units; values
are the `cam.k` thresholds converted via `log2`, then snapped toward band centers
where it reads cleanly. Exact snapping is a tuning pass, not a code-shape question.)

| Feature | Today (`cam.k`) | Module | → Envelope (band units) |
|---|---|---|---|
| Continent labels + tier borders | `[0.9,1,1.5,2.3]` | labels/tiers | `[-,0, 0.6,1.2]` (World) |
| Super-region labels + borders | `[1.7,2.2,3.4,4.7]` | labels/tiers | `[0.6,1, 1.8,2.2]` (Realm) |
| Region labels + borders | `[3.6,4.7,7,9.5]` | labels/tiers | `[1.8,2.2, 2.8,3.3]` (Region) |
| Ocean ripple | fade `5→16` (out) | main | out `[…, 2.3,4]` |
| Political fill taper | `0.58` <5, lerp `5→16` | political | fill alpha keyed on `band()` 2.3→4 |
| Plots fade-in | `(k-5)/1.5` | plots | in `[2.3,2.6]` (fade in, stay) |
| Cost overlay | `≥5`, `(k-5)/1.5` | plots | in `[2.3,2.6]` |
| Province names | fade `6.5→8.5` | labels | in `[2.7,3.1]` (Province) |
| Province borders | fade `7.5→10` | main | in `[2.9,3.3]` |
| Sea/lake names | fade `8.5→10.5` | labels | in `[3.1,3.4]` |
| Adjacency lines / teleporters | `≥10` (hard) | main | `atLeast(3.3)` |
| Tier lazy-load trigger | `<10` | main/tiers | `band() < 3.3` |
| Real terrain textures | `≥16` (hard) | plots | `atLeast(BAND.TERRAIN)` |
| Trade-good icons | `≥16`, fade `48→64` | plots | `[4,4, 5.6,6]` (Terrain→Plot) |
| Plot hover hit-test | `≥16` | panel | `atLeast(BAND.TERRAIN)` |
| Live colony overview→sprite | `<16` marker | live | flip at `BAND.TERRAIN` |
| Bonus/resource icons | `>16`, size `k/256` | plots | in `[4,4.3]`; size keyed on `band()` |
| Gap-grid hatch | `>64` (hard) | main | `atLeast(BAND.PLOT)` |
| Minimap visible | `fw/fh<0.985` | minimap | keep, + hide in Ground (`band()<6`) |

## City-micro skeleton (bands 6–8) — the new render surface

The chosen scope is **framework + a city-micro skeleton**: stand up a new
`js/city.mjs` that renders *something new* in the Ground regime end-to-end, proving
the deepest band, without building the full city sim view.

Minimum skeleton (all enveloped, all fed by data that already exists):
- **Band 6 (Plot):** building *footprints* on developed plots — a rectangle sized by
  the plot's `dev` value (the same field the existing `TERRAIN_URBAN` city sprite
  already reads in `plots.mjs`), so the urban core resolves into discrete lots.
- **Band 7 (Parcel):** **agent/household dots** from the live SSE feed (the colony's
  population is already streamed for the Spectate overlay) — laborers/nobles/ruler as
  positioned marks, fading in over `[6.6,7]`.
- **Band 8 (Structure):** labels/affordances on the dots (hover a household → who
  lives there), and the input-spine hit-test that selects a building/agent.

Everything past the skeleton (real building art, streets, firm interiors, animated
agents) is explicitly deferred; the skeleton exists to lock the band framework and
the Ground input mode against real data.

## Module plan (per-layer + central registry)

Keep today's clean per-layer split; add the registry and one new layer. No wholesale
reorg.

- **New `js/bands.mjs`** — `band()`, `bandAlpha()`, `BAND`/`REGIME` constants,
  `regime()`, `atLeast()`. Imported by every draw/input module. Absorbs and deletes
  the duplicated `tierAlpha()` in `labels.mjs` and `tiers.mjs`.
- **New `js/layers.mjs`** — the ordered layer registry (draw order + band mapping in one
  place); `renderScene()` becomes a loop over it. See *Layer registry* above.
- **New `js/city.mjs`** — the Ground-regime micro layer (skeleton above). Registered in
  `layers.mjs` with `regime:REGIME.GROUND`.
- **Split `js/plots.mjs`** (767 lines, four concerns) → keep terrain in `plots.mjs`;
  move `drawBonusOverlay` + `drawTradeGoodIcons` into `js/overlays/resources.mjs`;
  `drawCostOverlay` into `js/overlays/cost.mjs`. Each declares its own envelope.
- **Migrate in place** — `main.mjs`, `labels.mjs`, `tiers.mjs`, `political.mjs`,
  `live.mjs`, `minimap.mjs`, `panel.mjs` swap inline ramps/cutoffs for `bandAlpha`/
  `atLeast`. `panel.mjs` hit-tests become `regime()`-dispatched (the input spine).

## Phased implementation

1. **Registry.** Add `bands.mjs`; port `tierAlpha`→`bandAlpha`; wire `labels.mjs` +
   `tiers.mjs` to it (behavior-identical — the safest first migration). Verify the
   map looks unchanged.
2. **Migrate remaining fades.** Convert every threshold in the migration-map table to
   an envelope. Still behavior-preserving; now single-sourced. Verify per band.
3. **Layer registry.** ✅ *Done.* Added `layers.mjs`; moved `renderScene`'s 18-call imperative
   sequence into the ordered `LAYERS` table (draw order + gating now editable in one place),
   extracting the inline blocks (raster/lakes/borders/hover/selected) into named layer fns.
   Behavior-identical (verified headless at z=2 and z=24). The optional `resources.mjs`/`cost.mjs`
   split out of `plots.mjs` was **deferred** — those draws stay in `plots.mjs`, registered from there.
4. **Input spine + regime signal.** ✅ *Signal done.* The top-bar readout became the band-name mode
   chip (regime-tinted, `×` in tooltip); the regime cursor is stamped on `#stage`; a hysteretic
   accent pulse (`#regimePulse`) flashes on each crossing. The regime is now wired into the input
   layer (stage `data-regime`). Per-regime *target* dispatch (Ground selecting buildings/agents) is
   **deferred to Phase 6** — today Atlas/Overland/Ground all pick provinces (+plots past band 4), so
   there is no Ground-specific target until the city micro exists.
5. **Per-z-level layer set.** ✅ *Frontend done.* Added `activeZ()` (from the plane toggle) + a per-layer
   `z` set; `renderLayers()` skips layers off the active level. `drawUnderworld` folded into four
   first-class z:[−1] entries (veil / cavern floors / cavern plots / rims), ending the opaque-underground
   asymmetry. Verified: overworld unchanged, underworld renders through the registry. The real
   per-province `z` data model + z=0 caps + `_plotsByZ` is the engine plan in §Introducing z-levels.
6. **City skeleton.** Add `city.mjs` as a `regime:GROUND` layer: footprints (b6) → agent dots
   (b7) → labels/pick (b8), fed by the live feed. The first genuinely new pixels.
7. **Chrome + panel.** Envelope the always-on chrome (minimap hide in Ground, highlight stroke
   thinning, adjacency fade vs hard cutoff) and make the right panel a regime-scoped drill-path
   inspector (Almanac / Dispatch / Registry).

Each phase is independently shippable and verifiable by scrubbing the zoom across all
nine bands (the existing `tools/webverify` `?p=&z=` deep link drives an exact band).

## Open questions (deferred to tuning / later design)

- **Exact envelope snapping** — how tightly each layer hugs its band center vs.
  overlapping neighbors (a feel pass, done live, not a code-shape decision).
- **Ground data feed** — the skeleton reuses the Spectate SSE population; a dedicated
  per-building/agent snapshot channel is a server-side follow-up if the micro view
  grows past the skeleton.

**Settled:**

- **Regime transitions are signalled** (mode chip + regime cursor + transition pulse,
  hysteretic) — see *Crossing a boundary gives a UI signal*.
- **The tech tree is out of scope** — it keeps its own separate zoom space
  (`techtree.mjs`, `KMAX=1.8`) and is not a map band or regime.
