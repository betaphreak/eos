# Plan: districts + buildings buildout (Phase 5 auto-build → the district view)

**Status:** execution plan (nothing here implemented yet). Written 2026-07-14. This is the
sequenced, decisions-locked plan that carries the **remaining** work across two existing docs to
completion:

- [`c2c-building-import.md`](c2c-building-import.md) — Phases 1–4 shipped (the import, button-art
  bake, tech-tree view, and engine unlock model). Its **Phase 5 (auto-build onto district plots)** is
  the one behavior-changing step left, and it lives here.
- [`district-generator.md`](district-generator.md) — the reverse-engineered Civ6 LSystem reference +
  CivStudio port design. Nothing built. Its **§2 engine support**, **§2a district object**, and
  **§3 view paths** are realized here.

Those two docs stay the *reference* (why, and the C2C/Civ6 depot detail); **this** doc is the *how and
in what order*. When a phase ships, update the matching section in its home doc and flip its checkbox
here.

## Decisions (locked with owner, 2026-07-14)

| Question | Decision |
| --- | --- |
| **Scope** | The **full arc** A–D: engine district contract, Phase 5 auto-build, server feed, and the web sprite bake + district generator view. One plan, phased. |
| **District type / pop / era / style authority** | **Engine-authoritative** (`district-generator.md` §2a option **b**): a real engine district assignment placed by the settlement as it grows, sim-driven, serving down to the web. The web layer never invents it. The throwaway web-derived prototype (option a) is **not** taken — we commit engine state from the start. |
| **Phase 5 auto-build behavior** | **Gated off, behavior-neutral by default.** Wire the trigger and place onto district plots behind a config flag that defaults **off**, so the collapse smoke tests stay green; flip on only once placement is designed and the tests are deliberately re-baselined. |
| **District art (the composite)** | **Civ6 flat 2D district-hex art** (`Hex_District*` / `Districts_*_Visible`, bakeable today) is the district **tile/ground**; **C2C nifbake building sprites** stamp on top. Civ6 3D `DIS_*` meshes are **not** used — no headless `.fgx` renderer (see fact 6). No `DIS_` albedo filler, no CivNexus6 conversion. |
| **District type source** | **Derived from the buildings on the plot** (their `category`) — buildings drive districts — *not* the occupant firm sector. Plot 0 (village center) seeded as `CITY_CENTER`. Minimal `DistrictType` set first, grown later. |
| **Starting district count** | The city starts with **`province.development()`** districts (EU4 1444 **ADM + DIP + MIL**, already on `Province.development()`), **capped at `province.plots()`** (the total plots in the province — the ceiling). Firms with no constructed buildings sit in the **city center**, so this count is render/placement metadata — **no economic effect**. `Settlement.getStartingDistrictCount()`. |
| **Where type/era/style are computed** | The **engine owns the raw state** (the district count, and the placed building ids on `Plot.buildings()`) + the domain enums (`DistrictType`, `ArtEra`). The **per-plot type / era / style projection** is computed **server-side** in D3 (where the building categories already live, per "bare ids, client joins `/api/buildings`") — a pure, deterministic function of engine state, so still authoritative without pulling building-category data into the engine. |
| **Building sprite scope** | Bake **all 1,270** imported buildings' C2C sprites (not just the ~82 function taxonomy) — full coverage up front. |
| **Building metadata to client** | Snapshot sends **bare ids**; the client joins category/art against the `/api/buildings` bundle it already loads. |
| **Layout fidelity** | **Port the Civ6 LSystem** — spine (`HEX_SPINED`) / fill (`HEX_ONLY`), block-shape footprints, population growth stages, era × culture palette — to arrange the C2C sprites within the Civ6 hex. |
| **Doc** | This new file, cross-linked from both siblings. |

## Key architectural facts (some correct the sibling docs)

1. **The district object already exists** — `Settlement.getDistrictPlots()` (delegating to
   `PlotField`), each `Plot` a district slot already carrying a `PlotOccupant` (`getOccupant()`) and a
   `List<Building> buildings()` with `addBuilding`/`hasBuilding`. We **enrich**, not invent.
2. **`Plot` is `public final`** (`settlement/Plot.java:40`) — no `District extends Plot`. Per
   `district-generator.md` §2a we **enrich `Plot` with an optional `districtType`** (+ render inputs)
   rather than un-finalize or subclass it.
3. **`Building` is a bare `record Building(String id)`** (`settlement/Building.java:24`) — id only, no
   category/art. The rich building metadata (name/category/`button`/`artDefineTag`) lives in
   `generated/buildings.json`, already loaded server-side by the Phase-3 `BuildingBundle`
   (`/api/buildings`). Keep `Building` an id and **join to `buildings.json`** for art/category; do not
   duplicate metadata onto the engine record.
4. **The tech→building unlock seam is live.** `ResearchState.complete()` already grants the
   `BUILDING_*` tokens into `Settlement.getGrantedTechTokens()` (building-import Phase 4). Phase 5's
   job is only to turn a *granted token* into a *placed `Building` on a plot* — the grant already
   happens.
5. **Terrain plots are canonical; districts are session state — different feeds.** The per-province
   plot grid the web reads is served by `PlotService` as a **seed-independent, session-independent**
   cached gzip-JSON blob (`web/PlotService.java` — "a property of the map, not of a run"). District
   type, population, and *which buildings a colony has built* are **per-session sim state** and
   **cannot** ride that canonical blob. So — correcting `district-generator.md` §2's "add to
   `/api/plots/<id>`" phrasing — the district record is served through the **session render snapshot**
   (the `render/` package, the same seam building-import Phase 3c used for `ColonyView.knownTechs`),
   overlaid on the canonical terrain grid in the browser.
6. **Civ6 3D district art can't render headlessly; its 2D art can.** Civ6's `DIS_*` district/building
   pieces are 2,708 `.geo`/`.fgx` meshes with **no open-source renderer** (`nifbake` reads Civ4 `.nif`
   only; the sole `.fgx` path is CivNexus6, a closed Windows GUI) — `civ6-art-replacement.md` §H. What
   **does** bake today is Civ6's **flat 2D strategic-view art** (`Hex_District*`, `Districts_*_Visible`
   `.dds`, decoded by `web/dds.mjs`, resolved by `civ6.mjs`) and per-piece albedo `.dds`. This is
   exactly why the composite is **Civ6 flat district-hex ground + C2C `.nif` building sprites**: both
   halves are headlessly bakeable, the 3D Civ6 geometry is not. This is the crux the whole D4/D5 design
   rests on.

---

## The phases

Five phases. **D1–D3 are engine/server and land behavior-neutral** (gated off); **D4–D5 are the web
view.** The vertical slice that first shows *something real* is D1→D3→D5-prototype; D2 (auto-build)
can land in parallel behind its flag.

### Phase D1 — Engine: the district contract (behavior-neutral) ✅ DONE (2026-07-14)

**Shipped** as additive, byte-neutral engine domain: the two enums + the starting-district count. The
per-plot *projection* (type/era/style) is deferred to D3 server-side (see the decisions table) — the
engine owns the counts + enums + raw building ids, not the category fold. All 277 engine tests pass
(272 prior + 5 new in `DistrictTypeTest`); no economic assertion moved.

- **`DistrictType` enum** (`settlement/DistrictType.java`) — `CITY_CENTER` + one district per `Advisor`
  branch (`CAMPUS`/`HOLY_SITE`/`ENCAMPMENT`/`COMMERCIAL_HUB`/`THEATER`/`NEIGHBORHOOD`), with
  `fromCategory(Advisor)` folding the building taxonomy onto the district axis. Minimal starter set.
- **`ArtEra` enum** (`settlement/ArtEra.java`) — `ANCIENT`/`CLASSICAL`/`MEDIEVAL`/`RENAISSANCE` (the
  tree's Prehistoric→Renaissance horizon), with `fromProgress(researched, total)` bucketing research
  completion evenly across the eras.
- **`Settlement.getStartingDistrictCount()`** — `min(province.development(), getMaxPlots())`; `0` for a
  province-less colony. `getMaxPlots()` already exposes the province plot cap.

The remaining D1 idea from the original plan below (enriching `Plot` with a per-plot `districtType`
field / render inputs) is **not needed** as engine state: the type is a pure projection of the placed
buildings, computed server-side in D3. Kept below for the record.

<details><summary>Original plan (superseded by the split above)</summary>

Enrich the plot model with the render inputs `district-generator.md` §2 tabulates, assigned
authoritatively by the settlement.

- **`DistrictType` enum** (`settlement/`) — the CivStudio district identities, seeded from the Civ6
  `DISTRICT_*` set (`district-generator.md` §1 Layer 1): `CITY_CENTER`, `CAMPUS`, `HOLY_SITE`,
  `ENCAMPMENT`, `COMMERCIAL_HUB`, `INDUSTRIAL_ZONE`, `HARBOR`, … Start minimal (CITY_CENTER + the few
  we can actually assign) and grow it as placement rules mature.
- **`ArtEra` enum + projection** — map the colony's tech/date to an art era (`ANCIENT` / `CLASSICAL` /
  … / the Renaissance cap), the analogue of Civ6 `EraDistribution`. One projection function
  (tech-count or date → era); no new RNG.
- **Enrich `Plot`** with an optional `districtType` field (nullable; `null` = untyped backdrop), plus
  the per-plot render inputs (`pop`, `era`, `style`) *or* derive them at serialization time from the
  colony + `Province.culture` (§D3). Prefer a single `districtType` field on `Plot`; keep pop/era/style
  **derived** unless a per-plot override is genuinely needed, to keep `Plot` lean.
- **`style` from `Province.culture`** — a committed `culture → C2C-art-style` table
  (`district-generator.md` §1 Layer 2's ~20 `DIS_CTY_<style>` sets). Reference data only in D1; the
  web reads it in D5.
- **District type derives from the buildings on the plot (owner) — buildings drive districts.** The
  plot's `districtType` is a **function of the `category` of the buildings standing on it**
  (`Plot.buildings()` → each building's `category` from `buildings.json` — the `Advisor` axis / shared
  taxonomy): a plot accumulating campus-category buildings *becomes* a `CAMPUS`, commercial ones a
  `COMMERCIAL_HUB`, etc. This is **not** driven by the occupant firm's sector. Consequences:
  - The founding plot (plot 0, the village center per `Building.java`) is **seeded** `CITY_CENTER` and,
    since D2 places everything at plot 0 first, stays the mixed center until per-district spreading
    lands — so in the first cut there is effectively one typed district (the center) plus untyped
    plots, which is fine.
  - Because the type is *derived*, `districtType` can be a **computed accessor** over `buildings()`
    (with the plot-0 `CITY_CENTER` seed) rather than a separately-maintained field — one source of
    truth, no drift between "what's built" and "what district this is". A cached field is an
    optimization only.
- **No economic effect** — this is render metadata derived from already-placed buildings; assertions in
  the collapse tests must not move (and with D2 gated off, no buildings are placed at all, so the
  derived type is just the plot-0 seed).

**Verify:** `mvn -pl civstudio-engine test` (all green, unchanged economics); a focused
`DistrictTypeTest` — plot 0 derives `CITY_CENTER`; a plot with N campus-category buildings derives
`CAMPUS`; the mapping is deterministic; and the aggregate economy is byte-identical to pre-change (no
RNG draw added).

</details>

### Phase D2 — Engine: Phase 5 auto-build, gated off (the one behavior change) ✅ DONE (2026-07-14)

Turn a granted `BUILDING_*` token into a placed `Building` on a district plot. This is
`c2c-building-import.md` §5. **✅ DONE (2026-07-14).**

**Shipped:**
- **Flag, default off** — a per-colony `Settlement.setAutoBuildDistricts(boolean)` (default **off**;
  `isAutoBuildDistricts()`). Chosen over a `*Config` record field to avoid threading run config into
  every `Settlement` constructor; the effect is the same — existing runs are byte-identical.
- **Trigger** — in `Settlement.applyTechEffect(...)` (called from `ResearchState.complete()` where the
  token is granted): a `TechEffect.Unlock` whose target starts with `BUILDING_` places the building via
  `getDistrictPlots().get(0).addBuilding(...)` (the village center, plot 0). Idempotent (`hasBuilding`
  guard); a no-op before any plot is laid or for a non-building token.
- **First-cut simplification (deviates from the plan):** placement fires on the **primary-tech
  Unlock grant**, *not* the full `prereqTech`-AND-`andTechs` expression — the `andTechs` list lives in
  `buildings.json`, which the engine deliberately doesn't load (D1 split). Since buildings carry **no
  yield** (`Plot.java`), an occasionally-early sprite is render-only and harmless; the full `andTechs`
  gate is a documented refinement (needs the building catalog in the engine, or the check moved
  server-side where categories already live).
- **No `Building` record change** — placement needs only the id (the token itself). Category/art are
  joined server-side from `buildings.json` (§D3).

**Verified:** full engine suite **281/281 green** with the flag off (byte-identical — the 4 new
`AutoBuildTest` cases are the only additions). `AutoBuildTest` with the flag on: the unlocked building
lands at plot 0; idempotent on re-research; a non-`BUILDING_` token places nothing; and with the flag
off (default) research places nothing while the token is still granted. Collapse smoke tests stay on
the flag-off default and stay green.

### Phase D3 — Server: serve the district record on the session snapshot ✅ DONE (2026-07-14)

Expose the district state to the browser through the **session render snapshot**, not the canonical
`PlotService` blob (fact 5). **Shipped leaner than the original contract:** the server sends the
**authoritative raw state** (bare building ids per district plot + the starting district count + the
culture); the web derives type/era/style (D5) from data it already holds — matching the "bare ids,
client joins `/api/buildings`" decision and keeping the snapshot hot path free of building-category
coupling.

**Shipped** (`render/`):
- **`DistrictView(int index, List<String> buildings)`** — one per district plot carrying buildings
  (sparse: auto-build puts everything at the center in the first cut, so typically just index 0). Bare
  eos ids; the web joins `/api/buildings` for category/sprite and derives the `DistrictType` from those
  categories (index 0 = `CITY_CENTER`).
- **`ColonyView`** gains `startingDistricts` (`Settlement.getStartingDistrictCount()`), `culture` (the
  province culture → the web's art-style fold), and `districts` (`List<DistrictView>`). No allow-list
  gate needed — `ColonyView` is a record serialized wholesale (as `knownTechs` was in Phase 3c).
- **`Snapshots.districtViews(...)`** projects the non-empty district plots; degrades to an empty list
  before anything is built, and `culture` to `null` for a province-less colony.
- **era/style are client-derived** (not server fields): the web already has `knownTechs` + the total
  tech count (→ `ArtEra.fromProgress`) and `culture` (→ art-style table), so it computes them in D5.
  The Java `DistrictType`/`ArtEra` enums stay the authoritative spec, mirrored in JS.

**Verified:** server suite **33/33** + a new `DistrictSnapshotTest` (2 cases) driving the projection
through `Snapshots.of` — a province colony reports `startingDistricts > 0` and its culture with an empty
`districts` list; after auto-build an `Orchard` shows at district index 0. **No deploy yet** — the
fields are additive and ignored by the current client (no drift); version bump + `az` deploy batch with
D5 when there's something to render.

<details><summary>Original contract (superseded by the leaner shipped shape)</summary>

- **Render view.** Extend the colony render snapshot (`render/`, alongside `ColonyView`) with a
  per-district-plot record — the `district-generator.md` §2 contract:
  ```jsonc
  "district": {
    "type": "CITY_CENTER",        // Plot.districtType (nullable → omitted)
    "pop": 14,                    // growth-stage filler density (colony-derived first)
    "era": "CLASSICAL",           // ArtEra projection
    "style": "RMED",             // culture→art-style (Province.culture)
    "buildings": ["MARKET","BANK"] // Plot.buildings() ids → C2C sprites
  }
  ```
  `pop`/`era`/`style` degrade to colony-level defaults before per-plot data exists; `buildings`
  degrades to empty (backdrop only).
- **Join building metadata** from the existing `BuildingBundle` (`/api/buildings`) so the client gets
  category/art for each built id without a second lookup, or send bare ids and let the client join
  against the bundle it already has. Prefer the latter (client already loads `/api/buildings` for the
  tech tree).
- **Allow-list** the new field through whatever serializer gate the snapshot uses (the building-import
  Phase-3 gotcha: new keys must be explicitly allow-listed, cf. `WorldBundle`).

**Verify:** spectate the demo (`spring-boot:run`), hit the session snapshot endpoint, confirm the
`district` record is present for a colony's plots (Dhenijansar shows `CITY_CENTER` at plot 0 and its
built ids), and that a non-spectated / dataless plot degrades cleanly. Headless check via
`tools/webverify`.

</details>

### Phase D4 — Web: bake the district-hex ground (Civ6 2D) + the C2C building **sprite** set

Two bakes, matching the composite (facts 6 + decision table): the **district-hex ground** from Civ6
flat art, and the in-world **building sprites** from C2C `.nif` (`district-generator.md` §1 Layer 3).
The building sprite is distinct from the Phase-2 **button** bake — two bakes from the same building
(flat button, shipped; 3D-model sprite, here).

**D4a — Civ6 district-hex ground tiles. ✅ DONE (2026-07-14).** `civ6.mjs districtTile(type)` resolves
each of the 7 `DistrictType`s to its Civ6 `Hex_District*` chip (`CITY_CENTER→CityCenter`,
`HOLY_SITE→Faith`, `COMMERCIAL_HUB→Commercial`, …); `build.mjs bakeDistrictTiles()` decodes (via
`dds.mjs`), resamples to 256², keeps the hex-cutout alpha, and emits `web/assets/districts/dis-*.webp`
+ a `districtTiles` manifest key `{TYPE:{src,w,h}}` (allow-listed in `WorldBundle`, served via
`/api/bundle`). All 7 baked and verified (the CityCenter chip is the star hex). These are the
**ground/tile** the generator lays the hex on (and the far-zoom chip, LOD) — fully bakeable 2D `.dds`,
no 3D mesh path. **No deploy yet** (additive bundle key, ignored until D5).

**D4b — C2C building 3D sprites (nifbake). ⏸ DEFERRED (2026-07-14) — investigated, not viable enough.**
The nifbake path the `district-generator.md` doc assumed turned out far weaker than believed
(investigated with a probe, not just trusted):
- **Only 80 of the 1,270 gated buildings have a real 3D model** — the other **1,190 use
  `Art/Empty.nif`** (C2C is largely a 2D-interface mod; those are button-only, already baked in Phase 2).
- Of the 80, **only ~33 nifs even resolve** — the `CIV4ArtDefines_Building.xml` NIF paths (e.g.
  `Barracks/Barracks.nif`) don't match the `UnpackedArt` disk layout (variant subfolders, `Pedia_`
  prefixes); and **0 of the resolved ones have a co-located texture** (textures aren't next to the nifs —
  they'd need extracting from each nif's internal `NiSourceTexture` refs and re-resolving).
- `tools/nifbake renderNif` itself **works** (a manual Barracks render produced a recognizable sprite),
  but reliable coverage would be a real rabbit hole for maybe ~20–30 usable results.

**Decision (owner, 2026-07-14):** defer the 3D sprite bake; **D5 uses the flat button icon on a small
plinth** as each building's representation (already baked in Phase 2, full 1,270 coverage). 3D nifbake
sprites are a future enhancement (needs nif-path fixing + texture extraction from nif internals).

### Phase D5 — Web: the district generator view (`city.mjs`)

Port the Civ6 LSystem *logic* over the composite — Civ6 flat district-hex **ground** (D4a) with each
building drawn as its **flat button icon on a plinth** (D4b deferred the 3D sprites; button icons give
full 1,270 coverage) arranged on it — `district-generator.md` §3 path 2, the "removed `drawLots` done
right." Consumes the engine-authoritative district record from D3 (bare building ids → join
`/api/buildings` for category/art + the `icon` sprite rect).

- **Ground** — lay the Civ6 `districtTile(type)` hex art (D4a) as the tile the assembly sits on, keyed
  by `district.type`.
- **Generator logic (full LSystem port, owner)** in `city.mjs`: the two modes — `CityCenter` =
  `HEX_SPINED` (central avenue axis + blocks along it), `GenericDistrict` = `HEX_ONLY` (fill, no spine)
  — keyed by `district.type`. Population-scaled filler via `GrowthStage` keyed off `district.pop`;
  block-shape footprint vocabulary (`LG_SQ`/`SQ`/`REC`/`TR`/`WR`); era × culture palette from
  `district.era` × `district.style`. The numbers/observed stages in `district-generator.md` §1 are the
  tuning starting point.
- **Stamp sprites at block slots** — the constructed buildings (`district.buildings`) as C2C nifbake
  sprites (D4b) at the LSystem's block slots; the generic residential **backdrop** filler as our own 2D
  block fill (or C2C-baked filler houses) over the Civ6 ground.
- **LOD** — the flat Civ6 district chip (D4a / `civ6-art-replacement.md` §H / `district-generator.md`
  §3 path 1) stays the **far-zoom** representation; the full generated assembly fades in at deep zoom,
  matching the band-spine LOD (`docs/zoom-bands.md`).
- **Tune** growth/era/culture/slotting against the live engine data now that it's authoritative
  (option b) — no throwaway derived prototype to unwind.

**Verify:** `tools/webverify/shot.mjs` (deep-link a colony province at deep zoom), confirm the city
assembly renders with real building sprites and no console errors; visual spot-check across a couple of
eras/cultures. Add a `web/` node:test unit for the generator's deterministic layout (the user wants
web unit tests going forward; `web/` is dependency-free → built-in `node:test`).

---

## Sequencing & dependencies

```
D1 (Plot districtType derived from buildings + era/style, neutral)
 ├─► D2 (auto-build, gated off) ────────────┐
 └─► D3 (serve district record) ────────────┼─► D5 (LSystem generator view) ──► tune
     D4a (Civ6 hex tiles) ┐                 │
     D4b (C2C sprites)    ┴─────────────────┘
```

- **D1 first** — everything keys off `districtType` (derived from `buildings()`) + the render inputs.
- **D2 and D3 are independent** after D1 and can land in either order / in parallel. D3 is what makes
  anything visible; D2 is what makes `district.buildings` non-empty (so the derived type becomes more
  than the plot-0 `CITY_CENTER` seed). Ship D3 before D2 so the view works (ground + backdrop only)
  even before auto-build is flipped on.
- **D4 (both bakes) is independent** of D1–D3 and can be done any time before D5; D4a needs `.civ6-cache`,
  D4b needs `.civ4-cache`, both already wired.
- **D5 last** — it consumes D3 (data) + D4a (ground tiles) + D4b (sprites), and is the biggest single
  piece (full LSystem port).

Recommended first vertical slice to something on screen: **D1 → D3 → D4a → D5 laying just the Civ6
district-hex ground + `CITY_CENTER`**, then **D4b + D5 LSystem layout** for the filled city, then flip
**D2** on to populate real buildings.

## Risks & open questions

- **Placement richness (D1/D2).** The first cut centers everything at plot 0. Real per-district-type
  slotting (which building on which district) is deferred — the plan keeps it a later refinement so
  D1–D3 stay small. Flag it in `district-generator.md` §2a when it lands.
- **Flipping D2 on (later).** Turning auto-build on is a deliberate, separate decision that
  re-baselines the collapse smoke tests. Buildings still carry **no economic effect** at that point
  (fact 4 / `Plot.java`), so the flip is render-only; "buildings have effects" is a further phase
  entirely, not in this plan.
- **Sprite coverage & sheet size (D4b).** All 1,270 buildings are baked; not every one has clean `.nif`
  art, so the view must degrade to backdrop-only for missing sprites (log every drop — no silent
  truncation). 1,270 sprites also risks the WebP 16383-px cap — tile across multiple sheets like the
  button bake if needed.
- **LSystem port is the big lift (D5).** The full port (spine/fill modes, block-shape vocabulary,
  growth stages, era × culture palette) is substantial, and the block shapes + growth numbers +
  art-style codes in `district-generator.md` §1 are **inferred, not confirmed** — expect a tuning pass
  against the real look. The vertical slice (Civ6 ground + `CITY_CENTER`, no LSystem) de-risks it by
  landing something visible before the full layout logic.
- **Style table (D1).** The `culture → art-style` mapping is inferred (`district-generator.md` §1 Layer
  2 art-style codes are inferred, not confirmed) — author it as a best-effort committed table, easy to
  retune.

## When phases ship

- Bump the reactor patch version before deploying any server-visible change (D2 flag, D3 snapshot
  field): `mvn versions:set -DprocessAllModules` (shows in `/actuator/info`).
- Add a loading-screen trivia line per shipped feature (`web/assets/loading/trivia.json`).
- Redeploy the server on any change touching live-server data (engine resources / snapshot / bundle /
  server code) — static site + server drift silently breaks otherwise.
- Update the matching section in `c2c-building-import.md` (Phase 5) / `district-generator.md` (§2/§3)
  and add a one-line `CLAUDE.md` pointer when the subsystem is first live.

*Companion plan to [`c2c-building-import.md`](c2c-building-import.md) and
[`district-generator.md`](district-generator.md). Nothing here is implemented; checkboxes flip as
phases land.*
