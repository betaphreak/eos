# Civ6 district generator (the "LSystem") — reference & CivStudio port plan

**Status:** reference / design note (nothing implemented). Reverse-engineered 2026-07-13 from the
Civ VI SDK depot (`.civ6-cache`, see [`civ6-assets.md`](civ6-assets.md) for the depot layout). This
is the durable record of **how Civ6 assembles a district hex** and **what CivStudio would need — in
the engine/server, not just the web bake — to drive a faithful version**. Companion to
[`civ6-art-replacement.md`](civ6-art-replacement.md) §H (the flat-tile interim) and
[`urban-plots.md`](urban-plots.md) (the current urban-core substrate + the interim pip that stands
in today).

**One-line takeaway:** the flat `Hex_District*` chip (§H) is a *label*; a real district *view* is a
**procedural building assembly** whose inputs — district type, population, era, culture, per-hex
placement — **do not exist in the engine today**. A district generator is therefore a
**server-support feature first, a render feature second.**

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

So Layer 3 is **Civ6 taxonomy/slotting logic + C2C-baked building sprites**. Map each Civ6
`BUILDING_*` (or, better, each CivStudio building/firm type) to a C2C building `.nif`; nifbake →
`web/assets` sprite; the generator stamps it at the block slot.

**Composite:** `District (identity + SV chip + landmark)` → `Generator (spine/blocks/filler, scaled
by population, palette by era × culture)` → `Buildings (C2C-baked function-building sprites in
slots)`. The Civ6 **generator logic is the portable part**; the Civ6 building/backdrop *geometry* is
not (no `.fgx` renderer), and is replaced — buildings by **C2C nifbake sprites**, the generic
residential backdrop by our own 2D block fill (or likewise C2C-baked filler houses).

---

## 2. Why this needs server (engine) support

CivStudio today models a city as **`TERRAIN_URBAN` plots + a scalar `dev`** (`urban-plots.md`). None
of the four inputs the generator consumes exist as engine state:

| Generator input | Civ6 source | CivStudio today | Needed |
| --- | --- | --- | --- |
| **District type** | `DISTRICT_*` per hex | — (no districts at all) | an engine district concept, per urban plot |
| **Population / growth stage** | city population → `GrowthStage` | colony aggregate counts only; not per-plot | a per-district population/size the render can key growth off |
| **Era** | game era → `EraDistribution` | tech tree exists, but no "art era" projection | map tech/date → an art-era enum |
| **Culture / region** | civ culture → building set | `Province.culture` **exists** (Anbennar) | culture → art-style-set mapping (the one input we mostly have) |
| **Per-hex placement** | district plots in the city | urban core plots exist; no district identity/adjacency | assign district types to urban plots (+ optional adjacency) |

So the render layer **cannot invent districts** — the authoritative assignment (which plot is which
district, at what population/era) must come from the **engine and flow through the server feed**
(the plot stream `/api/plots/<id>` and/or the bundle). This is the "server support" gate: a district
generator is blocked on an engine district model, not on art or canvas code.

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
constructed — this is genuine sim state (firms/tech/wealth), authoritative only in the engine. The
natural source is the settlement's existing firm/building set, mapped to the Civ6 `BUILDING_*`
taxonomy and thence to a C2C `.nif`. `pop`/`era`/`style` can degrade to colony-level defaults before
per-plot data exists; `buildings` degrades to empty (backdrop only). The seam is the same optional
plot-record field the improvement layer already reserves, allow-listed through `WorldBundle`/the plot
serializer.

Where district identity comes from in the engine is an open design question — candidates: (a) derive
purely in the web layer from `dev` + `Province.culture` (no engine change, but not authoritative /
not sim-driven); (b) a real engine **District** placed by the settlement as it grows (authoritative,
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
