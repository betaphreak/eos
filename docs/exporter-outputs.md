# Exporter build-scratch — `civstudio-engine/target/generated/`

The content exporters/bakers write their computed JSON into **`civstudio-engine/target/generated/`**
(under Maven `target/`, so `mvn clean` wipes it). It is **gitignored, ephemeral build-scratch** — do
not hand-edit; edit the *source* (the upstream C2C/Anbennar data or the exporter code) and regenerate.

**Nothing downstream reads this tree.** The single committed content artifact every consumer reads is
the **world-bundle snapshot** at `civstudio-engine/src/test/resources/world-bundle.json.gz`:

- **tests / offline:** installed suite-wide by `FixtureWorldSourceInstaller`.
- **prod / server:** `StrapiWorldSource` fetches studio's `GET /api/world-bundle` — and studio's
  Postgres is seeded **from the committed bundle** (`studio/scripts/seed.js`, bundle mode).
- **web bakes:** `web/build-{techs,buildings,units}.mjs` read the committed bundle via
  `web/content-source.mjs`.

So `target/generated/` is written only by the exporters, and read only *by each other* — the
cross-exporter reads, e.g. `BuildingInfoExporter` reading the fresh `techs.json` — during a regen. The
regen flow is: run the exporters → they write this scratch tree → seed a **local** studio from it
(`node studio/scripts/seed.js --from-generated`) → snapshot the bundle (`tools/make-world-bundle.mjs`)
→ commit the refreshed `world-bundle.json.gz`, which is what everything above then reads. See
`docs/studio-datamodel-rebuild-plan.md`.

**The one committed exporter output** is the GeoNames place-name subset — it ships in the jar so any
machine can bake plot names. It is a **normal resource** at
`civstudio-engine/src/main/resources/geonames/subset.json.gz` (classpath `/geonames/subset.json.gz`),
*not* in the scratch tree; `GeoNamesSubsetExporter` writes it there directly. `GeoNamesSubset` loads it
from the classpath (via the `WorldSource` seam, which falls back to the classpath for geonames — it is
not in the world bundle).

## What produces what (all under `target/generated/` unless noted)

| file(s) | exporter / baker | upstream source |
| --- | --- | --- |
| `terrains` `features` `improvements` `bonuses` `housing` `manufactured-bonuses` `recipes` `tier1-providers`.json, `map/route-models` `map/terrain-art`.json | `com.civstudio.geo.export.*Exporter` | C2C (fetched by `Civ4Files`) |
| `techs.json`, `buildings.json`, `units.json` (+ `*-unlocks`, `unit-combats`) | `com.civstudio.{tech,settlement}.export.*Exporter` | C2C |
| `map/{provinces,edges,areas,regions,superregions,countries,cultures,religions,adjacencies,portals}.json` | `com.civstudio.geo.export.*Exporter` | Anbennar EU4 (fetched by `AnbennarFiles`) |
| `src/main/resources/geonames/subset.json.gz` **(committed)** | `com.civstudio.geo.names.GeoNamesSubsetExporter` | GeoNames dump |

**Web-only serving artifacts live in `civstudio-server`.** The files that only the server serves to the
browser (never read by the engine sim) are committed under `civstudio-server/src/main/resources/` and
their exporters/bakers write straight there: `map/borders.json` + `map/tierborders.json`
(`ProvinceBorderExporter` / `TierBorderExporter`), `map/web-asset-manifest.json` (`web/build.mjs`), and
the icon metas `techs-meta.json` / `buildings-meta.json` / `units-meta.json` / `unit-combats-meta.json`
(`web/build-{techs,buildings,units}.mjs`).

Each exporter is a `static main` run from the repo root, e.g.
`mvn -pl civstudio-engine compile exec:exec -Dsim.main=com.civstudio.geo.export.TerrainExporter`; it
writes into `target/generated/`. See `docs/civ4-files.md` and `docs/architecture.md`.
