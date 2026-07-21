# `generated/` — computed resources (do not hand-edit; **no longer committed**)

Everything under this directory is **produced by an exporter/baker**, not authored. Edit the
*source* (the upstream C2C/Anbennar data or the exporter code) and regenerate — never edit these
JSON files by hand.

**These files are not in git, and nothing downstream reads them anymore** — they are pure,
ephemeral **exporter build-scratch**, produced only during a local content regen. The single
committed content artifact every consumer reads is the **world-bundle snapshot** at
`civstudio-engine/src/test/resources/world-bundle.json.gz`:

- **tests / offline:** installed suite-wide by `FixtureWorldSourceInstaller`.
- **prod / server:** `StrapiWorldSource` fetches studio's `GET /api/world-bundle` — and studio's
  Postgres is seeded **from the committed bundle** (`studio/scripts/seed.js`, bundle mode).
- **web bakes:** `web/build-{techs,buildings,units}.mjs` read the committed bundle via
  `web/content-source.mjs`.

So this directory is written only by the exporters (and read only *by each other* — the
cross-exporter reads, e.g. `BuildingInfoExporter` reading the fresh `techs.json`) during a regen.
The regen flow is: run the exporters → they write this scratch tree → seed a **local** studio from
it (`node studio/scripts/seed.js --from-generated`) → snapshot the bundle
(`tools/make-world-bundle.mjs`) → commit the refreshed `world-bundle.json.gz`, which is what
everything above then reads. `.gitignore` keeps the whole tree out of git except two committed
exceptions: `geonames/subset.json.gz` (the machine-independent place-name subset) and this README.
See `docs/studio-datamodel-rebuild-plan.md`.

**Classpath note (still true when the files are present locally):** at package time Maven
**flattens this directory onto the classpath root** (see `civstudio-engine/pom.xml`
`<build><resources>`), so `generated/terrains.json` would load as `/terrains.json` and
`generated/map/provinces.json` as `/map/provinces.json`. Post-cutover, `mode=classpath` only works
in a working tree where the exporters have been re-run — a built jar no longer carries these.

## What produces what

| file(s) | exporter / baker | upstream source |
| --- | --- | --- |
| `terrains` `features` `improvements` `bonuses` `housing` `manufactured-bonuses` `recipes` `tier1-providers`.json, `map/route-models` `map/terrain-art`.json | `com.civstudio.geo.export.*Exporter` | C2C (fetched by `Civ4Files`) |
| `techs.json` | `com.civstudio.tech.export.TechInfoExporter` | C2C |
| `map/{provinces,edges,areas,regions,superregions,countries,cultures,religions,adjacencies,portals}.json` | `com.civstudio.geo.export.*Exporter` | Anbennar EU4 (fetched by `AnbennarFiles`) |

**Not here — web-only serving artifacts live in `civstudio-server`.** The files that only the
server serves to the browser (never read by the engine sim) are committed under
`civstudio-server/src/main/resources/` instead, and their exporters/bakers write straight there:
`map/borders.json` + `map/tierborders.json` (`ProvinceBorderExporter` / `TierBorderExporter`),
`map/web-asset-manifest.json` (`web/build.mjs`), and `techs-meta.json` (`web/build-techs.mjs`).
They still load at the same `/map/…` (and `/techs-meta.json`) classpath paths — the server's
runtime classpath merges both jars — so no loader changed.

**Excluded from the jar.** `map/terrain-art.json` (read by `web/build.mjs` from the source tree to
bake the terrain tiles) and `map/route-models.json` (no active consumer) are exporter outputs that
the engine sim never loads, so `pom.xml` keeps them out of the packaged jar. They stay here as
source-tree exporter outputs; regenerate them with their exporters as usual.

Each exporter is a `static main` run from the repo root, e.g.
`mvn -pl civstudio-engine compile exec:exec -Dsim.main=com.civstudio.geo.export.TerrainExporter`;
it writes back into this directory. See `docs/civ4-files.md` and `docs/architecture.md`.
