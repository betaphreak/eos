# Plan: rebuild the Studio (Strapi) data model as the engine's authoritative content store

> **Status:** Phases 1–2 **built** (2026-07-18); Phases 3–6 not started. Companion reference: the
> measured exporter inventory in [`studio-exporter-datasets.md`](studio-exporter-datasets.md). Studio
> lives at `studio/` (Strapi 5, Node/TS); the engine is `civstudio-engine` (Java 25). Big, multi-phase
> — read the Risks section before committing.
>
> **Built so far (Phase 1 + 2):** the 12 stale API types + their `config/sync` content-manager entries
> are deleted; the new model is authored as **29 collection types + 4 single types** and loads clean in
> Strapi (`strapi ts:generate-types` → 0 errors, all relations resolve). Authoring was hybrid:
> `province`/`tech`/`recipe` hand-written; the other 26 emitted by `studio/scripts/gen-schemas.mjs`
> (one declarative spec, all enum value-sets centralized as the single source, mirrored from the engine
> enums + measured datasets). `studio/scripts/validate-schemas.mjs` structurally lints every schema
> (JSON, UID resolution, inverse-pair consistency) with no DB. Single types this pass are the four
> **data-bearing** ones only — `era-modifiers`, `rank-ladder`, `region-earth-map` (the region→ISO
> place-name map), `map-version`; `simulation-config` / `balance-parameters` /
> `calendar-settings` are **deferred** (code-side tunables, not exporter data).
>
> **Phase 3 seeder BUILT (2026-07-18):** `studio/scripts/seed.js` — a standalone CommonJS ETL that
> boots Strapi programmatically and upserts the engine's committed exporter JSON via the Document
> Service. Two-phase, idempotent (natural-key upsert → then relation relink through a key→documentId
> map). Seeds the full **core model** — all 27 collections at exact reference-doc counts (province
> 5268, edges 5268, portals 6094, area 1573, building 1270, unit 273, bonus 432 [+manufactured], tech
> 339, …), the `province` hub + `neighbors` self-relation (28606 links), the tech self-graph, and the
> two data-bearing single types (`region-earth-map`, `map-version`=9). Four keyless derived tables
> (`adjacency`/`province-edge`/`province-portal`/`route-model`) gained a natural-key scalar so they
> upsert uniformly. ~4249 relation targets are unresolved-by-design (portals to filtered provinces;
> building/bonus refs to techs gated out of the kept horizon — the engine ignores these too).
> **DEFERRED still:** `place-name` (GeoNames), full all-race `name-pool` (seeds human+harimari only),
> `era-modifiers`/`rank-ladder` single types (no JSON source).
>
> **Phase 4 STARTED (2026-07-19) — the `/api/world-bundle` endpoint (studio side).** A custom
> collectionless route (`studio/src/api/world-bundle/`) serves one gzipped, version-stamped,
> **PATH-KEYED** bundle: `bundle.resources["/map/provinces.json"]` is byte-for-byte what the engine
> reads from that classpath resource today, so the engine's `WorldSource.open(path)` just serializes
> `resources[path]` and every Jackson parser is unchanged. The projection **reverses `seed.js`** (Strapi
> attrs → committed keys, relations → natural keys). This pass covers the **WorldMap subsystem** (the 11
> `map/*.json` + `terrains`/`unit-combats`), **verified faithful** against the committed JSON by
> `studio/scripts/verify-bundle.js` — all datasets `mismatch=0`, incl. the relational province hub (5268
> provinces, every relation reversed) and the geo hierarchy. Gated by a `WORLD_BUNDLE_TOKEN` shared
> secret (open when unset, for dev). **Finding:** committed `areas.json` references 1563 phantom province
> ids absent from `provinces.json` (export cruft); relations can't represent them, so the normalized
> store correctly drops them (the seeder did too) — the bundle is cleaner than the file, behavior-neutral
> pending the engine-side check. **Next (Phase 4 cont.):** project the remaining engine datasets (tech +
> overlays, TerrainRegistry detail features/bonuses/improvements/routes, units, buildings, feasts,
> region-earth-map, human-names); then the ENGINE `WorldSource` seam — an `InputStream open(String
> path)` interface threaded into the ~10 independent `getResourceAsStream + MAPPER.readValue` loaders
> (WorldMap, TerrainRegistry, TechTree, UnitCatalog, LiturgicalCalendar, …; there is NO shared resource
> abstraction today), with `ClasspathWorldSource` (behavior-neutral default) + `StrapiWorldSource` (JDK
> HttpClient) + `FixtureWorldSource` (snapshot, for `mvn test`). **Not yet done:** that engine seam;
> cutover/deleting `generated/` (Phase 5).
>
> **Naming pass (2026-07-18):** collection-type names follow **philosophy A** — mirror the
> source/engine vocabulary (`bonus`, `feature`, `improvement`, `area`, `adjacency` stay as the C2C/EU4
> and Java classes name them, for Studio↔engine parity) — with three clarity fixes: `tier1-provider`→
> `resource-source`, `unit-combat`→`combat-class`, and the `region-name` single type reverted to
> `region-earth-map`. The api folders now all match their content-type UIDs.
>
> **Session cont. (2026-07-18):** the new schema is now **live in the deployed Strapi admin**
> (`civstudio.com/admin`) — the 29 collection + 4 single content types shipped in the studio image
> rolled this session, **empty/unseeded** (the seeder is still Phase 3). Studio version is now
> **0.9.47** (kept in sync with the reactor). Related studio work also landed this session but
> **outside this data-model plan** (separate tracks, noted here only for orientation): the server admin
> console moved into **Strapi admin homepage widgets** ([`admin-console.md`](admin-console.md)); the
> container images moved **Azure ACR → public GitHub Container Registry** with a buildx layer-cached CI
> ([`client-server.md`](client-server.md) §Deployment); and the admin gained a **CivStudio brand theme**
> (gold/navy, ported from `web/`) with a web/-style login splash (`studio/src/admin/{theme.ts,app.tsx}`).

## Goals (from the directives that shaped this)

1. **Remove the 12 stale collection types** in `studio/src/api/` (country, culture, race, name-group,
   province, province-area, region, super-region, province-relation, rank, tech, era). Their schemas
   predate and diverge from what the engine now models.
2. **Add new content types that mirror what the exporters emit** — one modeled per dataset in the
   reference doc.
3. **Eliminate committed game data from `civstudio-engine/src/main/resources/`** — not just
   `generated/` but every data file (feasts, tech-effects, names, region-earth-map, the GeoNames
   subset). Strapi becomes the **authoritative** store; **the engine reads it live at boot** (decision
   locked). Only the non-content build pins (`*-source.lock`) stay in the repo.
4. **Global properties/parameters become Single Types** (Strapi's one-entry kind), not collections.

## Where we are today (established by investigation)

- Studio has 12 collection types + custom bulk endpoints for country/culture/province/
  province-relation only. **No seeding code exists in this repo** — the old "Java client" that POSTed
  to Strapi lived in the standalone backend repo and was never migrated.
- The engine is deliberately **DB-free**: `geo/export/*Exporter.java` parse the Anbennar EU4 + C2C
  sources and write **committed JSON** under `resources/generated/` (and `/map/`); `WorldMap` and the
  tech/building/unit/terrain loaders read those files at boot. The exporters' comments state the
  Strapi/Postgres path was removed on purpose.
- So this is not reconnecting a dormant wire — it is **re-introducing** Strapi as the source of truth
  and **reversing the data-flow direction** the engine was refactored away from.

## Target content model

### A. Collection Types (~27) — grouped by domain

Naming: kebab-singular (Strapi convention). Enum columns use Strapi `enumeration` attributes with the
value sets from the reference doc. Every `prereqTech`/`obsoleteTech`/`bonusType`/etc. string key
becomes a **relation**; every `[key]` array becomes a many-relation.

| Group | Collection types | From datasets |
|---|---|---|
| **Geography** | `province`, `country`, `culture`, `religion`, `trade-good`, `area`, `region`, `super-region`, `adjacency`, `province-edge`, `province-portal` | #1–11 |
| **Game definitions** | `tech`, `building`, `unit`, `combat-class`, `housing`, `recipe`, `resource-source` | #14, 18–20, 27–29 |
| **Terrain / plot** | `terrain`, `feature`, `bonus`, `improvement`, `route`, `route-model`, `terrain-art` | #21–26, 12–13 |
| **Naming / calendar** | `name-pool` (race+kind), `feast` (race field), `tech-effect` (race-keyed) | #30, 31, tech-effects |
| **Reference** | `place-name` (GeoNames subset) | generated/geonames |

Modeling rules baked in:
- **`bonus`** absorbs `manufactured-bonuses.json` (same schema, `bonusClass=MANUFACTURED`) — one
  collection, seeded from both files.
- **`building-unlocks.json` / `unit-unlocks.json` are NOT collections** — they become
  `tech ↔ building` and `tech ↔ unit` relations (the inverse of `prereqTech`), seeded from the
  overlay files.
- Geometry/art (**decision: full collection types**): `adjacency` (285), `province-edge` (5268,
  `province` + `km[]`), `province-portal` (6094, `province` + `portals[]` component), `route-model`
  (350), `terrain-art` (25) each get their own type. `province-edge`/`province-portal` are parallel
  to `province.neighbors`; keep the neighbor list on `province` and the km/pixel geometry on the
  sidecars so `province` stays lean.
- **`province` neighbors** stay a self many-to-many (seeded two-phase, as the old bulk-links did).

### B. Single Types (globals/parameters)

| Single type | Holds | Source |
|---|---|---|
| `simulation-config` | run-level tunables | `SimulationConfig.DEFAULT` |
| `balance-parameters` | the immutable `*Config` DEFAULTs (Firm/Bank/Noble/Retinue…) — one editable blob (or split per subsystem) | those records |
| `calendar-settings` | rest-day couplings + global calendar knobs | calendar package |
| `era-modifiers` | per-era percent modifiers (growth/train/construct/…) — a JSON map keyed by era **enum** | old `era` type's modifier fields |
| `rank-ladder` | the localized rank titles/casus-belli copy keyed by rank **enum** | old `rank` type |
| `region-earth-map` | region→ISO map for plot place-naming | `geo/region-earth-map.json` |
| `map-version` | the plot-cache generation version — today the compile-time-inlined `MAP_VERSION`/`GEN_VERSION` constant | `server`/engine constant |

> `map-version` as a Single Type retires the "inlined static-final-int" gotcha (bumping it no longer
> needs a `mvn clean compile` of the server) — it becomes data the engine/server/bake read at boot to
> key the `.map` plot cache. It should be **unified with (or feed) the `content-version` stamp** in the
> Architecture section: a data-model or map change bumps one authoritative version, invalidating caches
> and pinning reproducibility. Consumers must read it from Strapi rather than a recompiled constant.

> Rationale for `era-modifiers`/`rank-ladder` as Single Types: era & rank are now **enum attributes**
> (decision C), but they carried editable metadata that must land somewhere — a keyed JSON blob in a
> Single Type keeps it editable without re-introducing a collection.
> Note: `tech-effects` is **not** a Single Type — its `-harimari` race variant means N race-scoped
> entries, so it is a small race-keyed collection (§A, Naming group), not a singleton.

### Full `resources/` disposition (expanded scope — decisions locked)

| Path | What | Disposition |
|---|---|---|
| `generated/*.json`, `generated/map/*.json` | exporter datasets | → collection types (§A) |
| `generated/README.md` | doc | delete with `generated/` |
| `generated/geonames/` (~4.5 MB) | GeoNames subset (plot place-naming, bake-time) | → **`place-name` collection** (large; the plot bake now reads it from Strapi) |
| `generated/names/<race>/…`, `human-names/*.json` | name pools (human committed; other races generated on-demand) | → **`name-pool` collection** (`race` + `kind` fields); **pre-seed all races** via a build step running `RaceNameGenerator` |
| `feasts.json`, `feasts-harimari.json` | feast days (+ race variant) | → `feast` collection with a `race` field |
| `tech-effects.json`, `tech-effects-harimari.json` | eos tech-effect overlay (+ race variant) | → `tech-effect` race-keyed collection |
| `geo/region-earth-map.json` | region→ISO map | → `region-earth-map` Single Type |
| `anbennar-source.lock`, `civ4-source.lock`, `geonames-source.lock` | upstream source-ref pins | **KEEP in repo** — build config, upstream of seeding; cannot live in Strapi |

Cross-cutting from the expansion:
- **Race axis.** feasts, names, and tech-effects are race-scoped (`-harimari` today). Race is an enum
  attribute (decision C), so these types carry a `race` field. All races are **pre-seeded** (build
  step invokes `RaceNameGenerator`), so no lazy runtime name generation.
- **The plot bake reads Strapi.** Place-naming (`generated/geonames/`) becomes the `place-name`
  collection, so `WorldPlotGenerator`/`PlaceNamer` now source names from Strapi at bake time — a second
  Strapi coupling beyond the sim boot. Both should consume the same world bundle (see Architecture).

### C. Enums as attributes (no reference collections)

Per decision: `era`, `advisor`, `rank`, `race`, `skill` are plain enumeration strings on the types
that reference them (e.g. `tech.era`, `tech.advisor`, `combat-class.signatureSkill`), alongside the
descriptor enums (`province.type`/`realm`/`continent`/`climate`/`winter`/`monsoon`,
`trade-good.category`, `building.category`, `bonus.bonusClass`, `adjacency.type`, unit descriptors).
No `era`/`rank`/`race`/`advisor`/`skill` tables.

## Data-flow architecture — "live Strapi at boot" (decision locked)

Target flow (reverses today's):

```
Anbennar EU4 + C2C sources ──(exporters, dev-time)──▶ [seeder] ──▶ Strapi (authoritative)
                                                                        │  REST, at boot
                                                                        ▼
                                                                 civstudio-engine
```

- **Seeding (populate Strapi):** keep the exporters unchanged for now — they already parse the
  sources correctly and emit JSON. Add a **seeder in `studio/`** (Node script / Strapi bootstrap)
  that reads the exporters' JSON output and upserts via the **Document Service** (in-process, no HTTP).
  Two-phase for relations (create all rows, capture `key→documentId`, then relink), **batched** —
  5268 provinces + 6094 portals + 1573 areas through per-item `create()` is otherwise very slow. This
  is a transitional bridge; later the exporters can target Strapi directly and the committed JSON can
  be deleted.
- **Reading (engine consumes Strapi):** introduce a `WorldSource` seam in the engine with two impls:
  - `StrapiWorldSource` — at boot, calls **one custom consolidated endpoint** (`GET /api/world-bundle`,
    a custom controller in `studio/`) over the JDK `HttpClient` and gets the flat, gzipped,
    version-stamped payload. Default in prod/dev. (Not 29 paginated per-collection REST calls.)
    Authenticates with a **read-only Strapi API token** (env var, e.g. `STRAPI_TOKEN`) — the game
    model is not public. The token is a managed secret across dev/CI/prod.
  - `FixtureWorldSource` — loads a snapshot from disk. **Required for the test suite and offline dev**
    (see Risks). This is not a hedge against the decision; it is what keeps `mvn test` runnable.
  Rewire `WorldMap` and the tech/building/unit/terrain/route loaders to go through `WorldSource`
  instead of reading `resources/generated/*`.
- **Reproducibility:** a live store can drift under the sim. Stamp a **content version** (a
  `content-version` field on a Strapi Single Type, bumped on every reseed) and have the engine read +
  log it at boot, so a run records which content snapshot it used. Seed-reproducibility now means
  "same seed **and** same content version."

## Architecture recommendations

Seven load-bearing choices, ordered by how much risk they remove:

1. **Keep the sim core pure — a `WorldData` aggregate behind a `WorldSource` seam.** Introduce one
   immutable in-memory aggregate (`WorldData`: provinces, techs, buildings, …) that the sim depends
   on. `WorldSource` produces it; the sim core never imports an HTTP client or knows Strapi exists.
   The source is chosen at the composition root (server / `main` / test), not in the sim.

2. **Make the runtime contract a flat, denormalized bundle that mirrors today's JSON shape.** This is
   the single biggest de-risker. Strapi's normalized relational model is right for *editing*; the
   runtime wants the flat shape the engine already parses (`owner`=tag, `neighbors`=[id], not nested
   objects). If the boot bundle emits that same shape, the "engine rewrite" collapses from "rewrite
   every loader" to "change the byte source from file to HTTP" — the parsers survive. A **projection**
   layer denormalizes Strapi → bundle.

3. **One consolidated, version-stamped, gzipped world-bundle endpoint — not 29 paginated REST calls.**
   5268 provinces + 6094 portals + tens of thousands of place-names over per-collection pagination is
   thousands of round-trips per boot. Serve the whole projection as one payload
   (`GET /world-bundle?version=…`), gzipped, and have the engine fetch once and **cache by
   content-version** (refetch only when the version changes). Even under "live at boot," a
   version-gated cache buys fast boots and offline resilience.

4. **The engine talks to Strapi directly (decision locked); the projection lives in Strapi.** The
   `StrapiWorldSource` impl lives in `civstudio-engine` and calls Strapi over the JDK `HttpClient` (no
   new dependency — `java.net.http` is built in, and the engine already parses this JSON). The
   normalized→flat **projection is a custom Strapi endpoint** (`GET /api/world-bundle?version=…`, a
   custom controller in `studio/`) so the engine receives the flat shape its parsers already expect —
   the sim core still depends only on `WorldData`, and only the loader module knows Strapi. The Spring
   server, plot-bake, and web consume the engine's `WorldData`/`WorldSource` (or hit the same bundle
   endpoint); there is **no server mediation layer**.

5. **Content versioning is first-class.** Stamp every published seed with a `content-version`; the
   engine reads and records it at boot. Reproducibility becomes **seed + content-version** (not seed
   alone). Ranked/timeline runs (the shared-world royale) *must* pin a content-version, or a mid-season
   content edit silently forks the world — fold this into the savegame (`SessionSpec` + command log +
   content-version).

6. **Seeding is an idempotent ETL, not a one-shot script.** exporters (extract from Anbennar/C2C) →
   transform to Strapi shapes → **batched, natural-key upsert** via the Document Service, two-phase for
   relations. Re-runnable (a reseed converges, doesn't duplicate). Keep the exporter→JSON step as the
   intermediate artifact so the load stage is a pure JSON→Strapi transform you can test in isolation.

7. **`FixtureWorldSource` is the test/offline contract, not a hedge.** A `WorldSource` that loads a
   cached bundle snapshot is what keeps `mvn test` (every scenario, offline) and offline dev runnable
   without Strapi. This *is* the reconciliation of "kill committed `generated/`" with the existing test
   architecture: no committed source-of-truth JSON, but a gitignored/CI-produced fixture snapshot for
   tests.

8. **A scheduled content export → committed git snapshots is the backup/history layer.** A live DB
   loses git's free versioning, diffs, PR review, and backup — and hand-edits (the point of an
   authoritative CMS) aren't reconstructible from the exporters. So run a periodic job that exports
   Strapi content to committed JSON snapshots. Crucially this snapshot **triple-duties**: (a) backup +
   diffable history + rollback of hand-edits, (b) the `FixtureWorldSource` snapshot for `mvn test` /
   offline, (c) a re-seedable disaster-recovery image. It is *not* the source of truth (Strapi is) —
   it's the durable, reviewable mirror. Pair with routine Postgres backups.

Net shape:

```
Anbennar/C2C ─exporters→ JSON ─seeder(ETL)→ Strapi (normalized, editable, versioned)
                                              │  custom controller: project → flat, gzipped,
                                              │  version-stamped   GET /api/world-bundle
                    ┌─────────────────────────┼─────────────────────────┐
                 engine (sim)            plot-bake                 server / web
     StrapiWorldSource (JDK HttpClient) → WorldData         (consume engine WorldData
              cached by content-version                       or the bundle endpoint)
```

## Migration phases

**Phase 0 — Prep.** Back up `strapi-civbox`. Freeze the exporter dataset counts (the reference doc).
Lock the naming/relation conventions above. Decide `balance-parameters` grouping (one blob vs. per
subsystem).

**Phase 1 — Teardown.** Delete the 12 old `studio/src/api/*` content types + their bulk endpoints.
Purge the stale `config/sync/*` entries. Plan a clean DB rebuild (dev data is disposable — it was
seeded from the old model).

**Phase 2 — Author the new schema.** Create the 27 collection types + 7 single types from the
reference doc (attributes, enums, relations). Consider a small **codegen** that emits `schema.json`
from each dataset's field map to avoid ~29 hand-written files drifting from the data (and generates the
Strapi enum lists from the engine enums, so enum sets have one source). Enable `draftAndPublish: false`
(reference data). **Keep i18n on the display strings** (province/tech/building/`name`s, rank titles) —
localizable now, single-locale-seeded; numeric/geometry/key fields stay non-localized.

**Phase 3 — Seeder.** Build the Node seeder in `studio/`: load-order parents→children, two-phase
relation relink, batched inserts, idempotent upsert keyed on natural keys (`tag`/`key`/`id`/`Type`).
Wire the tech↔building / tech↔unit unlock relations from the overlay files. Verify counts match the
reference doc exactly.

**Phase 4 — Engine read path + Strapi bundle endpoint.** Build the custom `GET /api/world-bundle`
controller in `studio/` (denormalize collections → the flat shape the engine parses; gzip;
version-stamp with `map-version`/`content-version`). Add the `WorldSource` seam in the engine;
implement `StrapiWorldSource` (JDK `HttpClient` against that endpoint) and `FixtureWorldSource`
(snapshot). Rewire `WorldMap` + all `generated/`-reading loaders through `WorldSource`. Read the
version at boot and key the `.map` plot cache on it. Keep both sources behind config so the switch is
flippable.

**Phase 5 — Cutover.** Flip the default to `StrapiWorldSource`. **Delete `resources/generated/`** (and
`/map/`) from git; update `.gitignore` (a fixture snapshot lives in a gitignored cache, like `.map`).
Repoint/retire the exporters (their role becomes "seed Strapi", not "write committed JSON"). Regen
`config/sync`; set read permissions (public read or an API token). Realign the studio version with the
reactor. Update `studio/CLAUDE.md`, `docs/architecture.md`, and the exporter docs.

**Phase 6 — Verify.** Fresh-seed a DB; boot the engine against local Strapi; assert entity counts
(5268 provinces, 339 techs, 1270 buildings, 273 units, …) match; run a scenario smoke test through
`WorldSource`; confirm `web/` (which reads the Java server's `/api/bundle`, itself now Strapi-backed)
still renders.

## Risks & open tensions

- **⚠ Test suite becomes Strapi-dependent (biggest one).** `mvn test` today boots every scenario
  full-length, fully offline. "Live at boot" means the engine can't construct a world without Strapi.
  Mitigation: the `FixtureWorldSource` above — tests (and offline dev) load a committed/cached
  snapshot; only prod/integration uses `StrapiWorldSource`. This *does* keep a file snapshot around
  for tests, which is a soft partial-return of `generated/` in fixture form. Worth confirming you're
  OK with that seam; without it, CI needs a live seeded Strapi per run.
- **Runtime coupling.** The headless sim now needs the CMS reachable (local dev needs `studio` up —
  we've confirmed it runs; prod needs it deployed and seeded before the engine boots). Boot ordering
  and a health gate matter.
- **Boot performance.** Pulling 27 collections (incl. 5268+6094+1573 large ones) over REST on every
  boot is heavy. Options: a single consolidated snapshot endpoint, gzip, or a boot cache keyed on
  content-version (fetch only when the version changed).
- **Reproducibility.** Addressed by the content-version stamp, but it's a real change to the sim's
  guarantees — document it.
- **Scale of the engine rewrite.** Every `generated/`-reading loader changes. Sequence behind the
  `WorldSource` seam so it's incremental and revertible, not a big-bang.

## What is intentionally deferred
- Retargeting the exporters to write Strapi directly (Phase 3 keeps them writing JSON that the seeder
  ingests).
- The engine writing *back* to Strapi (this plan is seed + read; edits happen in the Strapi admin).
- Exposing the new model through the eventual unified admin dashboard (separate track).
