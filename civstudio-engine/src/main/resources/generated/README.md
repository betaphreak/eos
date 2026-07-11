# `generated/` — computed resources (do not hand-edit)

Everything under this directory is **produced by an exporter/baker**, not authored. Edit the
*source* (the upstream C2C/Anbennar data or the exporter code) and regenerate — never edit these
JSON files by hand.

**Classpath note:** at package time Maven **flattens this directory onto the classpath root** (see
`civstudio-engine/pom.xml` `<build><resources>`), so `generated/terrains.json` loads at runtime as
`/terrains.json` and `generated/map/provinces.json` as `/map/provinces.json`. The `generated/`
prefix exists only in the source tree, to keep computed files apart from the hand-authored ones
(`../feasts*.json`, `../tech-effects*.json`, `../names/`, `../map/*.lock`).

## What produces what

| file(s) | exporter / baker | upstream source |
| --- | --- | --- |
| `terrains` `features` `improvements` `bonuses` `housing` `manufactured-bonuses` `recipes` `tier1-providers`.json, `map/route-models` `map/terrain-art`.json | `com.civstudio.geo.export.*Exporter` | C2C (fetched by `Civ4Files`) |
| `techs.json` | `com.civstudio.tech.export.TechInfoExporter` | C2C |
| `map/{provinces,borders,edges,tierborders,areas,regions,superregions,countries,cultures,religions,adjacencies,portals}.json` | `com.civstudio.geo.export.*Exporter` | Anbennar EU4 (fetched by `AnbennarFiles`) |
| `techs-meta.json`, `map/web-asset-manifest.json` | `web/build-techs.mjs`, `web/build.mjs` | C2C art + baked web assets |

Each exporter is a `static main` run from the repo root, e.g.
`mvn -pl civstudio-engine compile exec:exec -Dsim.main=com.civstudio.geo.export.TerrainExporter`;
it writes back into this directory. See `docs/civ4-files.md` and `docs/architecture.md`.
