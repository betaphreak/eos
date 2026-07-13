# Civ6 district generator (the "LSystem") — reference & CivStudio port plan

**Status:** reference / design note (nothing implemented). Reverse-engineered 2026-07-13 from the
Civ VI SDK depot (`.civ6-cache`, see [`civ6-assets.md`](civ6-assets.md) for the depot layout). This
is the durable record of **how Civ6 assembles a district hex** and **what CivStudio would need — in
the engine/server, not just the web bake — to drive a faithful version**. Companion to
[`civ6-art-replacement.md`](civ6-art-replacement.md) §H (the flat-tile interim) and
[`urban-plots.md`](urban-plots.md) (the current urban-core substrate + the interim pip that stands
in today).

**One-line takeaway:** the flat `Hex_District*` chip (§H) is a *label*; a real district *view* is a
**procedural building assembly**. Its engine object **already exists** — the 1D, time-ordered plot
map (`Settlement.getDistrictPlots()`), each plot a district slot carrying an occupant + a
`Building` list (§2). So this is a **server-support feature first** (enrich those plots with a
district *type* + pop/era/style, serve them, and bake the buildings) **and a render feature second**
(port the Civ6 generator *logic* over C2C-baked building sprites). Buildings come from **Civ4 C2C**,
not Civ6 (no `.fgx` renderer).

---

## 1. How Civ6 builds a district — three layers

A rendered district hex is the composition of three artdef layers (all XML,
`.civ6-cache/Civ6/pantry/ArtDefs/`):

### Layer 1 — `Districts.artdef` (135 KB): the district's identity
One element per district type — verified set: `DISTRICT_CITY_CENTER`, `DISTRICT_CAMPUS`,
`DISTRICT_HOLY_SITE`, `DISTRICT_ENCAMPMENT`, `DISTRICT_COMMERCIAL_HUB`,
`DISTRICT_ENTERTAINMENT_COMPLEX`, `DISTRICT_THEATER`, `DISTRICT_HARBOR`,
`DISTRICT_INDUSTRIAL_ZONE`, `DISTRICT_AQUEDUCT`, … Each carries:
- a **`Landmark`** (the signature building/anchor for that district),
- a **`StrategicView`** reference → the flat `Hex_District*` chip / `Districts_*_Visible` badge we
  already bake-scoped in §H (the 2D map representation),
- **`Audio`** ambience (`PLAY/STOP_AMBIENCE_DISTRICT_*`) — irrelevant to us.

### Layer 2 — `CityGenerators.artdef` (1.3 MB): the procedural residential backdrop
The "LSystem" proper — it **fills the hex with generic city fabric** (streets + blocks + buildings)
around the specialty buildings. Key structure:

- **Only two generators**, chosen by district:
  - **`CityCenter`** → `Var_Mode = HEX_SPINED`: lays a central **spine** (avenue axis) across the
    hex, then blocks along it. Params: `Var_EnableSpines=true`, `Var_SpineWidthRange`=0.2–0.5,
    `Var_SpineLengthRange`=1.1–1.6.
  - **`GenericDistrict`** → `Var_Mode = HEX_ONLY`: fills the hex with no spine. **Every non-city-
    center district reuses this one generator** for its backdrop — the visual difference between a
    Campus and a Commercial Hub comes from Layer 3 + culture, *not* a per-district generator.
- **Growth by population** — each generator has **`GrowthStage`** children keyed by
  `Var_Population` (observed City Center stages at pop **1 / 14 / 22**). A higher stage raises the
  building count / density. Per-stage knobs: `Var_FillerOccupancy`, `Var_FillerRatio` (~0.75),
  `Var_CityArea` (~12), `Var_ScatterGrouping` (~0.031). This is the direct analogue of Civ4's
  population-driven City LSystem growth.
- **Block-shape footprint templates** — buildings are placed inside block outlines whose shapes are
  a small vocabulary, repeated per culture set: **`LG_SQ`** (large square), **`SQ`** (square),
  **`REC`** (rectangle), **`TR`** (triangle), **`WR`** (wedge/irregular — inferred). ~15 block
  variants per set.
- **~20 culture/region building sets** — the `DIS_CTY_<style>_*` families the block lists draw
  from: `AB AE AW MG MGG IU INDCL INDRH RBAL RBRZ RC RE RIND RJ RMED RMUG RNA RSA RSCT RSS` (art-
  style codes; each ~10–27 `_Bld_NN` pieces + its block templates + foundation/paver plates like
  `DIS_CTY_Med_Foundation`, `DIS_CTY_Med_Pavers`). The generator picks the set matching the city's
  **culture/region**.
- **Era swap** — **`EraDistribution`** keyed by `ARTERA_ANCIENT` / `ARTERA_CLASSICAL` /
  `ARTERA_INDUSTRIAL`: the building palette changes with the city's era, so a city visually ages.
  **`Distribution`** collections are the weighted random-pick tables within a set.
- **Placement grammar** — `Assets/Placement_Valid{Edge,Fill,Solid}_Hex.ast` +
  `Placement_Purchase_Hex.ast`: the hex sub-regions a piece may occupy (edge ring / interior fill /
  solid core). The spatial rules the generator obeys.

### Layer 3 — the specialty (function) buildings — **sourced from Civ4 C2C, not Civ6**
Civ6's `Buildings.artdef` (441 KB, **82 named `BUILDING_*`** — Library, University, Market, Bank,
Stock Exchange, Cathedral, Mosque, Factory, Power Plant, Barracks, Armory, Lighthouse, Seaport,
Amphitheater, Arena, Broadcast Center, Palace, Monument, Granary…) is the **taxonomy reference**:
it tells us *which* function buildings a district shows and at which slots. But the **pixels come
from Civ4 Caveman2Cosmos** (owner decision, 2026-07-13), for a decisive reason:

> **C2C buildings render headlessly; Civ6 buildings do not.** Civ6 `BUILDING_*` geometry is
> `.fgx`/`.geo` with **no OSS renderer** (§H). C2C building art is Civ4 **`.nif`**, which
> `tools/nifbake` already renders to sprite sheets in-process (the same pipeline behind the old city
> sprite, cactus and tall-grass foliage), fetched on demand via `com.civstudio.data.Civ4Files` /
> `web/civ4.mjs` (`docs/civ4-files.md`, `docs/features-art.md`). C2C also has a **huge** building
> inventory spanning eras, so coverage of the 82-function taxonomy (and beyond) is ample.

So Layer 3 is **Civ6 taxonomy/slotting logic + C2C-baked building sprites**. The source of truth is
the eos-native building id — `Plot.buildings()`' `Building.id()` / the CivStudio firm type (§2), not
the Civ6 enum. **C2C building data is already wired**: `com.civstudio.data.Civ4Files` maps
`Assets/XML/Buildings/{Regular,SpecialBuildings,zProviders}_CIV4BuildingInfos.xml` (fetched to
`.civ4-cache/<ref>/`; ~**2,900 buildings** — 2403 regular + 453 special + 48 provider — **each with an
`<ArtDefineTag>`** → `CIV4ArtDefines_Building.xml` → a `.nif` + button). Pipeline: eos building id →
C2C `BUILDING_*` → ArtDefineTag → `.nif` → **`tools/nifbake`** sprite → `web/assets` → the generator
stamps it at the block slot. Coverage is ample (2.9k C2C buildings ≫ any function taxonomy we need).

**Composite:** `District (identity + SV chip + landmark)` → `Generator (spine/blocks/filler, scaled
by population, palette by era × culture)` → `Buildings (C2C-baked function-building sprites in
slots)`. The Civ6 **generator logic is the portable part**; the Civ6 building/backdrop *geometry* is
not (no `.fgx` renderer), and is replaced — buildings by **C2C nifbake sprites**, the generic
residential backdrop by our own 2D block fill (or likewise C2C-baked filler houses).

---

## 2. Server (engine) support — the district object already exists

**The district object is already in the engine** (owner, 2026-07-13): it is the **1D, time-ordered
plot map** — `Settlement.getDistrictPlots()` (renamed 2026-07-13 from `getPlots()`; delegates to
`PlotField`), the colony's build plots in **claim order**. Each `Plot` is one **district slot**, and
already carries what a district needs:
- a **`PlotOccupant`** (`Plot.getOccupant()`) — the firm working the plot;
- a **`List<Building> buildings()`** with `addBuilding`/`hasBuilding`, where **`Building` is a record
  keyed by an eos-native id** (e.g. `FIRM_BANKING_HOUSE`) that **already matches the tech tree's
  `TechEffect.Unlock` target** — the exact seam for "research unlocks a building that then appears".

So, correcting the first draft: districts are **not** missing (they are the district-plot map) and
buildings are **not** missing (they are `Plot.buildings()`). What's missing is (a) a **district
*type*** per plot, (b) the **pop / era / style** render inputs, (c) **serving** them on the plot
feed, and (d) the **C2C building-art bake**. Still engine-first — but *enrichment*, not invention.

| Generator input | Civ6 source | CivStudio engine today | Gap to close |
| --- | --- | --- | --- |
| **District identity** | `DISTRICT_*` per hex | the district-plot map (`getDistrictPlots()`), each plot a slot | add a **district type** to `Plot` (or a `District`; see §2a) |
| **Buildings in it** | placed `BUILDING_*` | `Plot.buildings()` — `Building` id ↔ tech unlock | wire the tech-gated auto-build (a documented later phase) + map ids → C2C art |
| **Population / growth** | city pop → `GrowthStage` | colony aggregate counts; not per-plot | a per-plot size/pop the render keys filler off |
| **Era** | game era → `EraDistribution` | tech tree / date exist; no "art era" projection | map tech/date → an art-era enum |
| **Culture / style** | civ culture → building set | `Province.culture` exists (Anbennar) | culture → C2C-art-style table (**for planning**, owner) |

The authoritative assignment still flows **engine → server feed** (`/api/plots/<id>` and/or the
bundle) → render; the render layer never invents it.

### 2a. Could `District extends Plot`? — checked: not as-is
`Plot` is declared **`public final class Plot`**, so **`District extends Plot` will not compile**
without dropping `final`. Options, with a recommendation:
- **Un-finalize + subclass** (`class District extends Plot`): possible, but `Plot` is a value-like
  class — three public constructors + a private chain, mutable fields, no identity semantics — so
  subclassing it is fragile; and *every* build plot is already a district slot, so a subtype implies
  some plots aren't, contradicting the "plot map = districts" framing.
- **Enrich `Plot` (recommended):** add an optional **`districtType`** (and per-plot pop/era if
  wanted) field on `Plot` itself — the district-plot map already *is* the district list, and
  `buildings`/`occupant` are already there. No subclass, no un-finalizing.
- **Compose:** a lightweight `District` **record wrapping a `Plot` + type**, if district behaviour
  ever outgrows a field.
- **Verdict (for planning):** prefer **enrichment or composition over inheritance**; do **not**
  un-finalize `Plot` merely to subclass it.

### Minimum engine/server contract (proposed)
A per-urban-plot record the plot feed would carry (extends the `ProvincePlot`
`(geo,terrain,plotType,feature,bonus)` shape — cf. the deferred `improvement` field in
`urban-plots.md`/§F):

```jsonc
"district": {
  "type": "CITY_CENTER" | "CAMPUS" | "COMMERCIAL_HUB" | ... ,
  "pop": 14,                    // drives the growth-stage filler density
  "era": "CLASSICAL",           // drives the era palette (filler style)
  "style": "RMED",              // culture/region backdrop set (derived from Province.culture)
  "buildings": ["MARKET","BANK"] // the FUNCTION buildings actually built → C2C sprites in slots
}
```

`buildings` is the important addition over the terrain/improvement contract: because the specialty
buildings are **real C2C-baked sprites** (Layer 3), the view must know *which* the city has
constructed — this is genuine sim state (firms/tech/wealth), authoritative only in the engine. It is
**already in the model**: `Plot.buildings()` — the plot's `Building` ids (which equal the tech
tree's `Unlock` targets). The mapping is **eos building id → C2C `BUILDING_*` → `.nif`** (§1 Layer 3),
no Civ6 enum in the loop. `pop`/`era`/`style` can degrade to colony-level defaults before per-plot
data exists; `buildings` degrades to empty (backdrop only). The seam is the same optional plot-record
field the improvement layer already reserves, allow-listed through `WorldBundle`/the plot serializer.

Where the district **type** comes from is the open design question (identity/pop already exist as the
plot + its occupancy) — candidates: (a) derive purely in the web layer from `dev` + `Province.culture`
(no engine change, but not authoritative / not sim-driven); (b) a real engine **District** placed by
the settlement as it grows (authoritative,
sim-driven, the proper version, and the one that unlocks the [`city-and-league.md`](city-and-league.md)
CITY rung's "denser urban content"); (c) hybrid — engine assigns a City Center on the founding plot,
web derives the rest. **Recommend (b) long-term, (a) as a throwaway prototype** to tune the 2D
generator before committing engine state.

---

## 3. Implementation paths for the *view* (once the data exists)

1. **Flat `Hex_District*` chip (the §H interim).** No generator — one symbolic hex per district plot.
   Cheap, ships now, already scoped. The *label*, not the city. Keep as the far-zoom representation.
2. **Civ6 generator logic + C2C building sprites (the target real view).** Port the *layout* — spine
   (HEX_SPINED) / fill (HEX_ONLY), block-shape footprints, population-scaled filler, era/culture
   palette — into `city.mjs`, and **stamp C2C-baked building sprites** (nifbake, §1 Layer 3) at the
   block slots: the constructed **function buildings** from the `buildings` contract field, with the
   generic residential **backdrop** either our own 2D block fill or C2C-baked filler houses. This is
   the removed `drawLots` done right — spine + blocks + growth stages + real building art — and it is
   headless-bakeable end to end (C2C `.nif` renders; Civ6 `.fgx` does not). The block-shape
   vocabulary + growth-stage numbers in §1 are the tuning starting point.
3. **Faithful Civ6 3D bake.** Blocked — no headless `.fgx` renderer (§1 / §H). Not pursued; C2C
   sprites (path 2) are the stand-in for Civ6's own building geometry.

**Sequencing:** (server) land the district data contract incl. `buildings` → (web) bake the C2C
building sprite set + prototype path 2 against derived data → (engine) make districts + built-
buildings authoritative → (web) tune growth/era/culture/slotting. Path 1 remains the far-zoom chip
throughout.

---

## 4. Key depot paths (for whoever builds this)

- `ArtDefs/Districts.artdef` — district identities, SV chips, landmarks.
- `ArtDefs/CityGenerators.artdef` — the generator (spine/blocks/growth/era/culture). **The LSystem.**
- `ArtDefs/Buildings.artdef` — 82 specialty `BUILDING_*`.
- `ArtDefs/Cities.artdef`, `ArtDefs/Farms.artdef`, `ArtDefs/Walls.artdef` — related city fabric.
- `Assets/Placement_Valid{Edge,Fill,Solid}_Hex.ast` — hex placement zones.
- `Textures/DIS_CTY_<style>_*` (297 files) / `DIS_CMP_/ENT_/PRD_/HBR_/REL_…` — the building albedos.
- `Textures/Hex_District*.dds` — the 2D SV chips (§H).

*Written 2026-07-13. Verified by parsing the artdef XML in the depot; art-style-code meanings and the
`WR` block shape are inferred, not confirmed. When a district generator is built, cross-link it from
`civ6-art-replacement.md` §H and add a one-line pointer in `CLAUDE.md`.*
