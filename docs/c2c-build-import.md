# Plan: import C2C builds (the worker verb layer) — wiring workers → routes/improvements/features

**Status:** PLAN — design only, no code yet. **Date:** 2026-07-18. **Coupled child of**
[`docs/c2c-unit-import.md`](c2c-unit-import.md) (the units these builds belong to) — that doc's Design
direction flags this as "the mission data the `WorkerCaravan` needs before `arrive()` stops being a
no-op." Serves the **trail→road pioneering** mechanic ([[route-trail-pioneering]],
[`docs/caravan.md`](caravan.md) `WorkerCaravan`) and the plot improvement/feature systems
([`docs/plots.md`](plots.md), [`docs/province-plots.md`](province-plots.md)). Mirrors the building /
unit import pattern (`Info`-XML → tech-gated `generated/*.json` → button-art bake → tech-tree row).

**Goal.** Import the Caveman2Cosmos **`CIV4BuildInfos.xml`** (151 builds) as **engine content, gated
to the eos tech horizon** — the *verb* catalog that connects a **worker unit** (via its `<Builds>`
list) and a **tech** to a **plot mutation**: lay a route, build an improvement, or clear/terraform a
feature. This is what turns `WorkerCaravan.arrive()` into a real action, and gives the Explorer's
`ROUTE_TRAIL` a Worker successor that upgrades it to roads.

## Why this is a thin coupling, not a big import

A `BuildInfo` produces exactly one of three outcomes, and **both structured targets already exist as
imported catalogs** — so this import mostly *wires*, it doesn't introduce new target data:

| Build outcome | Count | Target — already imported | The tie |
| --- | --- | --- | --- |
| **Route** (`<RouteType>`) | 13 | **`geo.RouteType`** / `routes.json` (`RouteExporter`) — `TRAIL`→`PATH`→`ROAD`→`PAVED_ROAD`→`RAILROAD`→… | `BUILD_TRAIL`→`ROUTE_TRAIL`, etc. The pioneering ladder — `Plot.layRoute` already exists ([[route-trail-pioneering]]). |
| **Improvement** (`<ImprovementType>`) | 129 | **`geo.Improvement`** / `improvements.json` (`ImprovementExporter`) — farms, mines, camps, pastures, orchards… | `BUILD_FARM`→`IMPROVEMENT_FARM`. `Plot` already carries a Civ4 improvement field ([`docs/plots.md`](plots.md)). |
| **Feature** (`<FeatureStructs>`/`<FeatureType>`) | ~1155 sub-entries | `Plot` feature (trees/jungle/marsh/smog…) from `trees.bmp` etc. (`MapTerrainCodec`) | chop-forest / clear-smog: each build lists which features it can remove + the tech/time to do so. |

So `builds.json` is a **verb catalog** (`BUILD_* → {routeType | improvementType | removes-features}` +
tech/time/cost/art), joined to the **unit** side (`UnitInfo.<Builds>`) and executed against the
**plot**. No new target catalog — routes and improvements are already baked.

## The seam — unit `<Builds>` → `BUILD_*` → plot

Confirmed in the C2C data:
- **`UnitInfo` carries `<Builds><BuildType>BUILD_*</BuildType>…`** (e.g. `UNIT_WORKER` lists
  `BUILD_TRAIL`, `BUILD_FORTIFIED_CAVE`, … with era-obsolescence comments). So the **worker unit
  rows** in `units.json` (`docs/c2c-unit-import.md`) gain a **`builds: [BUILD_*]`** field — the unit's
  build repertoire. (Only worker-family units carry a meaningful `<Builds>`; others are empty.)
- A `BuildInfo` gates on a **direct-child `<PrereqTech>`** (the 1,290 total `<PrereqTech>` include one
  per `<FeatureStruct>` sub-entry — a per-feature tech/time to remove that feature; the build's own
  prereq is the direct child).
- `<iTime>` is **work-turns** (the effort), `<iCost>` the rush cost, `<bKill>` whether performing it
  **consumes the unit** (e.g. a workboat builds a fishing improvement and is spent — land workers are
  not killed), `<PrereqBonus>` a required resource (4 builds), `<TerrainChange>`/`<FeatureChange>`
  terraforming (11/10). `<Button>` is `Art/Interface/Buttons/Builds/*.dds`.

## Architecture

### 1. Engine — `BuildInfoExporter` → `generated/builds.json`
Sibling of `UnitInfoExporter` / `BuildingInfoExporter`, reusing the kept-tech computation + `Civ4Xml`
DOM helpers verbatim. Reads `CIV4BuildInfos.xml`, keeps builds whose **direct `<PrereqTech>` ∈ the
kept techs** (the standard gate; a build needing a later-era tech is unreachable), and emits one row:
```
{ id, name, prereqTech, iTime, iCost, bKill,
  routeType?, improvementType?,
  removesFeatures?: [ { featureType, prereqTech, iTime } ],
  terrainChange?, featureChange?, prereqBonus?, button }
```
Validate each `routeType`/`improvementType` resolves to an **already-baked** `routes.json` /
`improvements.json` id (fail-fast, the building-import validation pattern) — this is where the wiring
is proven at bake time. `name` resolves `TXT_KEY_BUILD_*` from Builds GameText.

### 2. Engine — the unit side (couples to `docs/c2c-unit-import.md`)
`UnitInfoExporter` additionally captures each unit's **`<Builds>` list** onto its `units.json` row
(`builds: [BUILD_*]`), filtered to builds that survive the gate. This is the worker's repertoire — the
`WorkerCaravan` reads it to know what it can do. (Land-only scope already excludes the sea-worker
builds' `WORKER_SEA` units.)

### 3. Fetch
Add to `Civ4Files.FILE_MAP` (+ `web/civ4.mjs` needs only the art, as `builds.json` carries the resolved
`button` path): **`Assets/XML/Units/CIV4BuildInfos.xml`** + the **Builds GameText**
(`TXT_KEY_BUILD_*`) + the build button art (`Buttons/Builds/*.dds`). Cached under `.civ4-cache/<ref>/`.

### 4. Runtime — `WorkerCaravan.arrive()` executes a build (the behavior; gated off until sequenced)
The `WorkerCaravan` scaffold's no-op `arrive()` becomes: pick a `BUILD_*` from the embodied worker
unit's `builds` list that (a) the home colony has the **`prereqTech`** for, (b) is **valid on the
target plot** (terrain/feature/bonus prereqs), and (c) advances the colony's goal (upgrade the trail it
came in on, improve a resource plot). Executing it:
- **route** → `Plot.layRoute(routeType)` (exists; never downgrades — the trail→road upgrade the
  Explorer's `ROUTE_TRAIL` was waiting for);
- **improvement** → set the plot's improvement field to `improvementType`;
- **feature** → clear/terraform the plot feature.
`<iTime>` accumulates as **work over days** (the daylight-scaled effort seam, like forage), scaled by
the worker's `CONSTRUCTION` signature skill (the band↔skill link, `docs/c2c-unit-import.md` §1a) and
its era (a `WORKANIMAL` builds faster than a bare `GATHERER`). All of it is **per-session mutable plot
state** (like `Plot.routeType`/districts — [[route-trail-pioneering]]), excluded from the canonical
`.map`; it rides the session render snapshot.

### 5. Web — build buttons in the worker inspector
The worker unit's inspector (the unit-import tech-tree rail, §5 there) lists its `builds` as the C2C
build button icons (`Buttons/Builds/*.dds`, a small bake beside `unit-icons.webp`), each showing what
it makes (route/improvement art it already has) + its `prereqTech`. A later cut draws "buildable here"
affordances on a selected plot.

## Phasing (mirrors the unit import; all dormant until §4)

- **Phase B1 — `BuildInfoExporter` → `builds.json` + unit `builds` field.** The gated import + the
  route/improvement cross-validation + the `<Builds>` capture on worker rows + the fetch entries.
  Report the in-scope count / how many resolve to a route vs improvement vs feature. **No behaviour
  change** (nothing reads `builds.json`).
- **Phase B2 — build button art + web.** Bake the build buttons; show a worker's repertoire in its
  inspector.
- **Phase B3 — `WorkerCaravan.arrive()` execution (behavior-changing; gate off).** The plot-mutation
  execution + `iTime` work accumulation + the `CONSTRUCTION`-skill/era scaling. Turns on the
  trail→road pioneering end-to-end (Explorer trails → Worker roads). Couples to the per-session plot
  overlay + the trail-gated routing sequencing ([[route-trail-pioneering]]).

## Open decisions (to lock with owner)

- **Tech-gating model:** gate builds **at execution** (a worker checks `prereqTech ∈ knownTechs`) —
  lighter, no token plumbing — **vs** a `build-unlocks.json` overlay granting `BUILD_*` tokens like
  units/buildings (consistent, lets the tech tree show "this tech unlocks BUILD_ROAD" via the join).
  *Recommend: gate-at-execution for the engine; the tech tree can still show builds via the
  `prereqTech` join without an overlay.*
- **Feature-removal scope:** the 129 improvements + 13 routes are the clear wins; the ~1,155
  feature-interaction sub-entries (chop/clear/terraform) are bulkier and mostly modern (smog, plagued
  terrain). *Recommend: import the `removesFeatures` list for completeness but only wire the
  **prehistoric-relevant** clears (forest/jungle) in §4 first.*
- **What a worker chooses to build:** the `arrive()` goal policy (upgrade-my-trail vs improve-a-
  resource vs terraform) — a heuristic to design when B3 is sequenced.
- **Sequencing:** after the unit import (itself after taxation → caravan-trade, `docs/c2c-unit-import.md`),
  since it reads `units.json`'s `builds` field and realizes the `WorkerCaravan`.

## Key files

- New: `docs/c2c-build-import.md` (this); `BuildInfoExporter` (engine); `generated/builds.json`;
  build-button bake; worker `builds` field on `units.json`.
- Changed: `com.civstudio.data.Civ4Files` + `web/civ4.mjs` (`CIV4BuildInfos.xml` + Builds GameText +
  build art); `UnitInfoExporter` (capture `<Builds>`); `agent.WorkerCaravan` (B3 `arrive()`
  execution); the unit-import tech-tree rail (worker repertoire).
- Reuse: the building/unit import gate + `Civ4Xml` + kept-tech computation; **`routes.json`
  (`RouteExporter`) + `improvements.json` (`ImprovementExporter`) as the already-baked targets**;
  `Plot.layRoute` + the plot improvement/feature fields; the per-session plot-overlay snapshot seam.

*Planned 2026-07-18. Coupled child of `c2c-unit-import.md`; when Phase B1 lands, add a one-line
pointer in `CLAUDE.md` (workers → builds) and cross-link both docs.*
