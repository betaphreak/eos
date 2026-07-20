# Plan: studio as the control plane — balance configs, scenario definitions, session detail, map↔province

**Status:** **PLAN** (2026-07-20). Four workstreams that together turn `studio/` from a content store into
the game's control plane. **A** and **B** are coupled (a scenario names a balance profile); **C** and **D**
are independent admin surfaces and can ship in any order. Companion to
[`docs/studio-datamodel-rebuild-plan.md`](studio-datamodel-rebuild-plan.md) (the `WorldSource` seam this
builds on), [`docs/client-server.md`](client-server.md), [`docs/mcp-server.md`](mcp-server.md) and
[`docs/admin-console.md`](admin-console.md).

## The organising idea

studio plays **two roles with opposite requirements**, and the value of this plan depends on not
conflating them:

| | Content authority | Ops console |
|---|---|---|
| What | authored, version-stamped, seeds the engine | live, high-churn, read-mostly |
| Where | Strapi **content types** → `/api/world-bundle` → `WorldSource` | Strapi **admin pages/widgets** → the Java server's HTTP API |
| Rule | reproducibility is `seed + content-version`; must never vary per-run | never a content type — it is runtime, not content |
| This plan | **A** (balance configs), **B** (scenario definitions) | **C** (session detail), **D** (map↔province) |

**A/B move authored data across the existing `WorldSource` seam. C/D add admin UI over endpoints that
mostly already exist.** Nothing here puts live session state into Postgres as content.

---

## Current state (verified 2026-07-20)

### The `WorldSource` seam — ready to carry more

`com.civstudio.data.WorldSource` (`civstudio-engine/.../data/WorldSource.java:21`) is two methods:

```java
InputStream open(String path);        // null when absent — load-bearing, callers rely on it
default boolean exists(String path);
```

`BundleWorldSource` (`.../data/BundleWorldSource.java:42`) maps a path to bytes by re-serialising the
JSON subtree at `resources[path]`, falling back to `ClasspathWorldSource` when the key is missing — so
**adding a new resource path costs nothing downstream**: every existing Jackson parser is untouched.
`StrapiWorldSource` fetches the whole bundle in its constructor. Installation is
`WorldSourceInitializer` (`civstudio-server/.../data/WorldSourceInitializer.java:41`), an
`ApplicationEnvironmentPreparedEvent` listener keyed on `civstudio.world-source.mode`
(`classpath`/`strapi`/`fixture`) — *not* a Spring profile, and deliberately not a `@Component` because
`UnitCatalog` captures the source at class-load. The studio side authors **32 path keys** in
`studio/src/api/world-bundle/services/world-bundle.ts:97`, cached keyed on `contentVersion`.

**`contentVersion` is today only a traceability stamp** — its single Java consumer is the stderr boot
line at `WorldSourceInitializer:76`. Workstream A makes it load-bearing. That is the main risk in this
plan and is called out again below.

### The balance configs — 13 records + `SimulationConfig`

All immutable records with `@Builder(toBuilder = true)` and a hand-written `DEFAULT`:
`FirmConfig`, `BankConfig`, `NobleConfig`, `RetinueConfig`, `LaborerConfig`, `FertilityConfig`,
`GranaryConfig`, `BuilderConfig`, `ScienceConfig`, `StrategicFirmConfig`, `ChildrenFirmConfig`,
`WeddingConfig`, `MarchConfig` (21 fields). Plus `SimulationConfig` (`.../simulation/SimulationConfig.java:126`),
32 components with nested `PriceRange`/`FirmInit`/`CFirmInit`/`LaborerInit`.

Three facts that shape the design:

1. **Jackson 3 (`tools.jackson`) is already on the classpath and deserialises records with no annotations.**
2. **`SimulationConfig.DEFAULT` is not all literals** — 14 of 32 values delegate to
   `Era.MEDIEVAL.economy()` (`.../era/Era.java:41`). An external loader must flatten or override that too.
3. **There is no single injection seam.** The "singleton per colony" configs arrive as Lombok `@Setter`
   fields on `SimulationHarness` (`nobleConfig:172`, `retinueConfig:178`, `childrenFirmConfig:185`,
   `granaryConfig:191`); the rest are factory arguments. A `BalanceProfile` aggregate is needed.

The model to copy already exists: `CalibrationRun.run(cfg, seed, provinceId, retinue, sink, steps)`
(`.../simulation/CalibrationRun.java:77`) is fully parameterised with no constants.

### Scenarios — three disjoint notions, no registry

Confirmed: **there is no scenario registry anywhere.**

1. **Engine scenario classes** — convention only: a `public static SimulationHarness run()` + `main()` in
   `com.civstudio.simulation`. No interface, no annotation, not enumerable at runtime. Each is
   `SimulationConfig.DEFAULT` (or a small `toBuilder()` diff) → `SimulationHarness.create(cfg, seed, provinceId)`
   → `foundStandardColony(eSavings, nSavings, nStock)` → printers → `run()`.
2. **`SessionSpec`** — `record SessionSpec(long seed, String scenario, int provinceId)`. The `scenario`
   string is **never resolved to anything**: `SessionHost.build` (`civstudio-server/.../SessionHost.java:356`)
   branches only on `isTimeline()`, and every other value funnels into one hardcoded `foundStandardColony`.
   The comment at `:354` says so outright.
3. **`ScenarioMcpTools.listScenarios()`** (`.../mcp/ScenarioMcpTools.java:49`) — a hardcoded one-element
   list (`"standard"`) guarded by a `"standard".equals(scenario)` throw.

So a registry has exactly **three call sites** to satisfy. `ScenarioMcpTools.applyConfig:132` is a
hand-written 11-key whitelist that generic binding would supersede.

### Session read surface — richer than expected, three gaps

Endpoints (all under `com.civstudio.server.web`): `GET /api/sessions/{id}/snapshot` (`SessionSnapshot`,
**204** before the first frame), `/events?level&from&to&grep&limit` (`List<LogLine>`, limit clamped to
512), `/stream` (SSE), `/colony` (`ColonyDetail`), `/caravan/{id}` (`CaravanDetail`), `/person/{id}`
(`PersonDetail`), `/routes/{pid}` (`ProvinceRoutes`).

Records available to render: `ColonyView` (**28 fields** — population, children, nobles, firms, poolSize,
cpi, prices, plots, taxes, advisors, knownTechs, districts, researchingTech/progress, tier, provinceId,
centre), `CaravanView` (role, unit, larder, hoard, signature skill), `ColonyDetail` (skills + resident
roster), `CaravanDetail` (crew sorted by survival), `PersonDetail` (skills with passion + household),
`AdvisorView`, `DistrictView`, `LogLine`.

**Gaps:**
- **No HTTP route for the command log** — `CommandLog` is reachable only via MCP `get_command_log`
  (`SessionMcpTools.java:97`). `pendingCount()` is exposed nowhere.
- **`/colony` and `/person` are hard-wired to `colonies.get(0)`** (`ColonyController.java:44`), so a
  multi-colony Timeline is not fully inspectable over HTTP.
- **`snapshot.log` is a delta.** `SessionLogBuffer` is drain-once (CAP 512) and its drain becomes
  `SessionSnapshot.log`; `SessionEventLog` is the retained ring (CAP 4096, default 200) behind `/events`.
  The server still hands every new subscriber the cached `lastSnapshot` (`HostedSession.java:334`) —
  the delta-replay is fixed **client-side only**, by the tick gate in `web/js/snapshot-dedupe.mjs`.

**Authorization:** `SecurityConfig` is `anyRequest().permitAll()` with all gating in-controller. Every
session *read* above is public; `GET /api/sessions` is ungated but filtered by `SessionAuthz.canSee`.
Detail endpoints use `host.get(sid)` (404 for an unloaded run) whereas `/snapshot` uses `getOrRestore`.

### Map ↔ province — the key mismatch

- Strapi `province` (`studio/src/api/province/content-types/province/schema.json`): **`provinceId`
  (integer, required, unique) is the natural key**; `name` is i18n-localized; relations to
  country/culture/religion/trade-good/area/region plus a `neighbors` self-M2M.
- The admin deep link needs **`documentId`** — an opaque 24-char string that is **never exported in the
  bundle** and has no committed `provinceId → documentId` map. URL shape:
  `/admin/content-manager/collection-types/api::province.province/<documentId>?plugins[i18n][locale]=en`.
- The viewer's `p.id` **is** `provinceId` (`web/js/core.mjs`), and `?p=<id>&z=<zoom>` already deep-links
  (`readDeepLink`, `web/js/main.mjs:555`). **But `selectProvince` (`web/js/rail.mjs:87`) never writes
  `?p=` back to the URL** — only `switchRealm` and the lobby mutate the query string.
- `window.BUNDLE` comes from the **Java server** `/api/bundle`, not Strapi's `/api/world-bundle`.
- `web/` is a separate origin (Azure SWA, `anbennar.civstudio.com`) with **no** `frame-ancestors` set.
  Strapi's CSP (`studio/config/middlewares.ts`) sets no `frame-src`, and with helmet's `useDefaults` that
  falls back to `default-src 'self'` — **an iframe is blocked today**.
- **No custom admin *page* exists** in `studio/src/admin` — only the two homepage widgets. The primitives
  are `app.addMenuLink({to, icon, intlLabel, permissions, Component})` for a page and
  `app.getPlugin('content-manager').injectComponent('editView', 'right-links', …)` for a per-entry button.

---

## A — Balance configs as content

**Goal:** tuning becomes a content edit + a re-seed, not a recompile. Unlocks the loop
*author profile → `run_scenario`/`sweep` → `compare_runs`*.

### A0 — `BalanceProfile` aggregate (engine, no Strapi yet)

Introduce `com.civstudio.balance.BalanceProfile`: a record holding `SimulationConfig` plus the 13
per-owner configs, with a `DEFAULT` composed from the existing `DEFAULT`s (so it is behaviour-neutral by
construction). Give `SimulationHarness` a single `setBalanceProfile(BalanceProfile)` that fans out to the
existing `@Setter`s and factory arguments — the missing injection seam. Scenarios keep compiling
unchanged.

*Ship criterion:* full suite green with `BalanceProfile.DEFAULT` wired in; byte-identical run output for
a fixed seed.

### A1 — Economy is an era × race matrix, NOT part of the profile — **DECIDED 2026-07-20 (owner)**

The open question was whether to flatten `Era.MEDIEVAL.economy()` into `SimulationConfig.DEFAULT` or
fold it into the balance profile. **Neither.** Economy stays **authored on two axes**: an era sets the
technological/commercial epoch (one `Economy` per era), and a **race** sets who is living through it.
Today's constants are the *human* column — authored before race was a lever, and read as universal
when they are not: a race maturing at 9, or living for centuries, does not plausibly share humanity's
pool size, promotion ratio or savings behaviour.

That makes race the same shape it already is everywhere else in the engine — its own name tables and
life table where they exist, the human calendar and tech overlay where they do not (`docs/race.md`) —
and the same shape as the per-race tech trees, where the shipped `techs.json` is the human tree.

**Consequence for `BalanceProfile`: it must NOT hold economies.** The 15 `Era.Economy` fields are
authored content on their own axes; the profile covers the *other* tunables (the 13 per-owner agent
configs, and the structural/run-level `SimulationConfig` fields — `numEFirms`/`numNFirms`,
`foundingLaborersPerNFirm`, `durationYears`, the `foundAtCamp`/`homePlots` flags, …). That is a
cleaner split than the original plan: two authored matrices, one tuning profile.

**Shipped so far:** `Era.economy(Race)` names the axis, with every race falling back to the human
column until its own is authored, and `SimulationConfig` now says `Era.MEDIEVAL.economy(Race.HUMAN)`
so the human assumption is explicit rather than implicit. Behaviour-neutral (`EraEconomyTest`).

**Also shipped:** `SimulationConfig.defaultFor(Era, Race)` — the base a scenario builds on, with
`DEFAULT` now defined as `defaultFor(MEDIEVAL, HUMAN)`. `ElvenEconomy`/`HarimariEconomy` start from
their own race's cell, so an authored column will reach them untouched.

A **factory**, deliberately, not resolution applied inside `SimulationHarness.create`: the economy has
to be the *floor* that a scenario's `toBuilder()` tweaks sit on top of. Resolving the race economy
after the caller had already customised would silently overwrite deliberate overrides — and several
scenarios do override economy-derived fields (`ElvenEconomy` sets `retinueSize`/`promotionRatio`,
`SmallOpenEconomy` sets `externalInflowPerStep`/`immigrationThreshold`). A record cannot distinguish
"explicitly set" from "inherited", so the only honest fix is to let the caller pick its base first.
An uncalibrated era is refused outright rather than founding a colony on null tuning.

**Also shipped — the world already knows who lives where.** A settlement does not need to be told its
race: `Race.ofCultureGroup(String)` + `WorldMap.raceOf(Province)` read it off the map. A province has a
culture, a culture has a group, and the group key *is* a race id — both were imported from
`anb_cultures.txt`. Measured on the shipped world: **57 of 70** culture groups name a race exactly;
the rest fall back to `HUMAN`, and `HUMAN` is the one race with no group (it is the engine's default,
not an Anbennar people). Rubyhold is dwarven, Dancers Retreat elven, and Dhenijansar — group
`south_raheni` — reads human. Pinned against the real map, including a guard against the failure mode
that would look like working code: if the group keys ever drift from the race ids, every province
silently reads `HUMAN` and the world loses its peoples.

**Founding by province race — SHIPPED.** A colony now takes its race from the province it stands in.
This was blocked on **name pools**, not on anything structural: only `HUMAN` has hand-authored surname
tables (151k names across 822 tiers), while every other race is imported from Anbennar's
`anb_cultures.txt` — a couple of hundred — against a standard colony of ~405 households. Founding
anywhere non-human died mid-founding on `dynasty master pool exhausted`.

The fix was to stop treating surname uniqueness as absolute. `DynastyPool` now **wraps** once its list
is spent instead of refusing, and `NameRegistry`'s in-use set became a **counted multiset** so one
surname may be held by several households (a plain set would have collapsed the duplicates and let one
release free a name two households were still using). Slices stay disjoint for the first full pass, and
never repeat a name *within* a slice.

Global uniqueness was the unrealistic constraint, not the shortage of names: four hundred medieval
households emphatically do not hold four hundred distinct surnames. Human runs are untouched — 151k
surnames outlast anything this engine founds, so the wrap never fires there.

Rubyhold now founds a full-size **dwarven** colony and Eargate an **Anbennarian** one, both with
repeating surnames; Dhenijansar still founds human, which is why every pre-existing scenario is
unaffected.

**Per-colony economy — phases 1 & 2 SHIPPED.** `Settlement` carries the `(era, race)` cell for whoever
founded it, and the founding path reads *that* rather than the run config: 48 reads moved from
`cfg.X()` to `colony.getEconomy().X()`. `foundStandardColony()` now defaults its firm-savings and
necessity-stock lambdas from the colony's own numbers — 43 call sites lost the boilerplate
`i -> cfg.eFirm().savings()`, which only ever handed the harness back a value it already had, and
which could not express a run seating several races because each lambda closes over one config.

`Settlement#setEconomy` / `SimulationHarness#tuneEconomy` are the **option (a)** override seam: a
scenario wanting numbers other than its race's says so out loud.

> **Phase 2 carries a bridge that phase 3 removes.** `SimulationConfig` still holds the 15 economy
> fields and ~20 call sites still override them there, so `SimulationHarness`'s constructor seeds the
> colony's economy *from the config*. That keeps moving the reads behaviour-preserving — `econ()` is
> exactly `cfg` — but it means **a multi-race session still shares one economy**. Deleting the fields
> and converting the override sites to `tuneEconomy` is what actually fixes that, and it is phase 3.
> The bridge is one line in the constructor, marked, and is the last thing making the run config win.

**Still to do:** phase 3 — see the worklist below.

#### Phase 3 worklist (attempted 2026-07-20, reverted — not committed)

Phase 3 was attempted end-to-end and backed out at the last step: the engine and scenarios converted
cleanly, but ~23 test files need per-file surgery on multi-line builder chains, which is not a safe
blind regex. Nothing is half-applied — the tree is at phase 2 and green. The recipe below is what was
actually done before the revert, so it should replay quickly.

**1. `SimulationConfig` — remove 14 of the 15 economy fields** (record components *and* the matching
`econ.X()` arguments in `defaultFor`'s constructor call): `ePrice`, `nPrice`, `eFirm`, `nFirm`,
`cFirm`, `laborer`, `externalInflowPerStep`, `immigrationThreshold`, `laborShare`,
`bankProfitTaxRate`, `nobleIncomeTaxRate`, `retinueSize`, `promotionRatio`, `targetNobles`.

**Keep `targetNStock`.** It is consumed at *settlement construction* — `newSettlement(..., targetNStock,
...)` — before any harness exists, and is read back by `Laborer` through `Settlement#getTargetNStock()`.
Moving it means dropping the constructor parameter across every `newSettlement` overload and their
callers, which is its own change. Removing the other 14 without it is coherent; doing both at once is
not.

**2. `SimulationHarness` — delete the phase-2 bridge**: the marked `colony.setEconomy(economyOf(cfg))`
line in the constructor and the `economyOf` helper. That line is the only thing still letting the run
config win.

**3. Engine consumers** — `DynamicFirmProvisioner` (14 reads) and `SocialMobility` (7 reads): rewrite
`cfg.X()` to `colony.getEconomy().X()`. Both already hold a `colony` field, so it is a pure rename.

**4. Six scenarios** — `ElvenEconomy`, `HarimariEconomy`, `OpenColonyEconomy`, `SurvivalExperiment`,
`CampFoundingEconomy`, `SmallOpenEconomy`: lift the economy setters out of the `SimulationConfig`
builder into `h.tuneEconomy(e -> e.toBuilder()....build())`, placed after the harness is created and
**before founding**.

**5. ~23 test files**, two regular patterns:
- `cfg.<economyField>()` → `h.getColony().getEconomy().<field>()` (the harness local is `h` almost
  everywhere) — safe regex.
- economy setters inside a `SimulationConfig...toBuilder()` chain → moved into a `h.tuneEconomy(...)`
  call after the harness line. This is the fiddly half: the chains span lines and mix economy with
  non-economy setters (`settlementName`, `durationYears`, `foundAtCamp`, `homePlots`, `numEFirms`),
  which must stay on the config.

  By error count: `WeddingMarketTest` 12, `SimulationConfigDefaultForTest` 10, `LaborerEnnoblementTest` 8,
  `SettlementCampFoundingTest`/`LaborTrainsSkillsTest`/`HouseholdDissolutionTest`/`ChildrenFirmTest`/
  `BirthsTest` 6 each, `TechResearchTest`/`TechProductivityTest`/`RuinedNobleDemotionTest`/
  `NobleOwnedBankDividendTest`/`NobleDemotionTest`/`HomePlotEconomyTest`/`CaravanRefoundTest` 4 each,
  then `RulerTaxationTest`, `MixedRaceColonyTest`, `ExplorerRenewalTest`, `CommercialFarmTest`,
  `CampBootViabilityTest` 2 each. `SimulationConfigDefaultForTest` needs rewriting rather than
  converting — it asserts on economy fields that will no longer be on the config.

> **Gotcha that cost real time:** Maven does **not** recompile the test sources after the record
> changes shape. `mvn -o -pl civstudio-engine test` then runs *stale* test classes and reports
> `NoSuchMethodError` at runtime instead of compile errors, which reads like a behaviour bug and is
> not. Use `mvn -o -pl civstudio-engine clean test-compile` to get the real worklist.

**Verification:** the suite is the safety net — these tests assert on colony sizes, tax collected and
population, so a mis-converted override fails loudly rather than silently testing something else. The
end state is that a dwarven seat and a human seat in one Timeline finally run different economics,
which is the whole point of the phase. `SessionHost` founds every colony from
`DEFAULT`, and a Timeline could seat players of different races, so per-*colony* economy resolution
remains open — `SimulationConfig` is per-run, and that is the next structural question, not something
`defaultFor` answers.

### A2 — Serialise/deserialise round-trip

Jackson 3 handles records with no annotations. Add `BalanceProfileCodec` + a round-trip test asserting
`read(write(DEFAULT)).equals(DEFAULT)`. Emit the canonical JSON to a new resource path
**`/balance/profiles.json`** (a map of `profileKey → BalanceProfile`, with `"default"` present).

### A3 — Load through `WorldSource`

`BalanceProfiles.current()` reads `/balance/profiles.json` via `WorldSources.current().open(...)`,
falling back to `BalanceProfile.DEFAULT` when absent (the `null`-return contract). Follow the
`UnitCatalog.load()` shape exactly (`.../agent/UnitCatalog.java:59`).

### A4 — Strapi content type + bundle key

New collection type `balance-profile` (key, label, and the config groups as components or JSON fields;
**not** i18n — these are numbers). Extend `world-bundle.ts` to emit `resources['/balance/profiles.json']`,
and `seed.js` to ingest it. Regenerate the test fixture with `tools/make-world-bundle.mjs`.

### A5 — Retire the MCP whitelist

Replace `ScenarioMcpTools.applyConfig`'s 11-key switch (`:132`) with generic binding over the profile,
and let `run_scenario`/`sweep` take a `profileKey` in addition to ad-hoc overrides.

> **Trap — `contentVersion` becomes load-bearing.** Today it is a stderr stamp. Once balance rides the
> bundle, *a content edit changes simulation behaviour*, so a run is only reproducible as
> `seed + contentVersion + command log`. Before A4 lands, `contentVersion` must be **persisted on
> `SessionRecord`** and surfaced in the lobby row / run store — otherwise old runs silently become
> irreproducible with no way to detect it. Treat this as a hard prerequisite, not a follow-up.

---

## B — Scenario definitions (depends on A0–A3)

**Goal:** a scenario becomes data — *seed + province + balance profile + founding shape + flags* — so it
can be authored, enumerated, and launched without Java.

### B1 — `ScenarioRegistry` (engine)

```java
public record ScenarioDef(String key, String label, String blurb,
        long seed, int provinceId, String balanceProfile,
        FoundingShape shape, Map<String,Object> flags) { }
```

`FoundingShape` is an enum covering what actually exists today: `STANDARD_COLONY` (the
`foundStandardColony` path), `CAMP` (`foundAtCamp`), `GRANULAR` (the `SmallOpenEconomy` hand-wiring),
`TWIN` (`TwinSettlementEconomy`'s own `GameSession` + `SessionRunner.runConcurrently`), `TIMELINE`.

**Escape hatch, deliberately:** the registry maps `key → ScenarioDef` *or* `key → code factory`. Not
every scenario is expressible as data — `TwinSettlementEconomy` builds its own `GameSession` and
`SmallOpenEconomy` skips `foundStandardColony` entirely. Forcing those into data would be the failure
mode of this workstream. Data-define the standard/camp ones; leave the odd ones as registered code.

### B2 — Resolve the three call sites

- `SessionHost.build:356` — resolve `spec.scenario()` through the registry instead of ignoring it.
  **This is a behaviour change**: strings that silently produced a standard colony would now 404 or
  resolve differently. Keep an explicit `"standard"` alias and log unknown keys loudly.
- `ScenarioMcpTools.listScenarios:49` — return the registry instead of the hardcoded singleton.
- Each `XxxEconomy.run()` — becomes a thin `ScenarioRegistry.get("homogeneous").launch()`.

### B3 — Strapi content type + bundle key

`scenario` collection type (key, label, blurb, seed, provinceId, `balanceProfile` relation, shape enum,
flags). Emit `resources['/scenarios.json']`. `label`/`blurb` **are** i18n-localized (player-facing);
numbers are not.

### B4 — Surface it

Scenario picker in the lobby / create-session flow, fed by the registry rather than a hardcoded string.

---

## C — Session detail admin page

**Goal:** click a row in the Live sessions widget → a full page. Mostly assembly; three server gaps to
close first.

### C1 — Close the server gaps — **SHIPPED 2026-07-20**

- **`GET /api/sessions/{id}/commands`** → `CommandLogView(history, pending)`. New `CommandController`.
  The projection moved to `render/CommandProjections` + `render/CommandView`, and MCP's
  `get_command_log` now delegates to it — the duplicate `SessionMcpTools.CommandInfo`/`project` are
  gone, so the two views **cannot** drift. The wire shape is unchanged (identical field names/types).
- **Gated** — `SessionAuthz.denyCommandLog`: 401 anonymous, owner-or-admin past that, mirroring who may
  *write* a command with no colony named. The one gated session read; every other stays public.
- **`?colony=` on `/colony` and `/person`** — resolution lifted into `web/Colonies.resolve(hs, name)`:
  absent/blank ⇒ the POV colony (so existing web clients are unchanged), a name ⇒ that colony, an
  unknown name ⇒ **404, never a silent fallback**. A Timeline's non-first seats are now reachable, and
  a colliding agent id can no longer resolve to the wrong person.
- **`getOrRestore`** on the colony/person/caravan detail reads, so a recorded-but-unloaded run answers
  instead of 404-ing half a page. `RouteController` deliberately keeps `get` — it is polled per viewport
  province, where paying a restore would be wrong.
- Covered by `SessionDetailApiTest` (4 tests: the gate, the unowned case, submit→step→applied history,
  the `?colony=` resolution incl. the 404). Full server suite green (123).

**Deferred from C1:** no endpoint yet exposes seats/owners (`SeatRecord`, `ownerOf`), tick rate, or
spectator identities — the lobby row still only says `mine` plus `seats`/`standing` counts. Add when a
panel actually needs them.

### C2 — The page shell — **SHIPPED 2026-07-20**

First custom admin **page** in this repo (everything before it was a homepage widget or a
content-manager view). `app.addMenuLink` in `studio/src/admin/app.tsx` → `pages/Sessions.tsx`
(router) → `SessionListPage` / `SessionDetailPage`. Overview panel only; the rest is §C3.

Two Strapi facts worth recording, both verified against `@strapi/admin` 5.42 rather than assumed:

- **`to` must be RELATIVE.** `Router#addMenuLink` strips a leading slash and logs a warning, so it is
  `to: 'civstudio-sessions'` (URL `/admin/civstudio-sessions`), not `/plugins/…`.
- **The route is registered as `` `${to}/*` ``**, so the page owns its own nested routes — hence the
  internal `<Routes>` with `index` and `:id`. No extra registration needed for the detail view.
- Like `widgets`, `addMenuLink` must be called in **`register`**, not `bootstrap` (whose argument is a
  restricted `Pick` without it).

The widget and the page share `lib/sessions.ts` (state colours, `controlsFor`, `controlSession`,
`sessionPath`) and `components/sessionBits.tsx` (`Pill`, `Detail`, `StatePair`, `SessionFigures`), so
the two surfaces cannot disagree about what a state means or which controls the server accepts.

The detail page finds its run by filtering `GET /api/sessions` rather than via a per-session route:
that is the only endpoint returning the lobby row shape, and it already applies the caller's
visibility rules — so a run the operator may not see simply is not there, and the page says "no
session visible to you" without disclosing which of the two it is.

Verified locally by `tools/webverify/sessions-page-verify.mjs` (menu link present, list renders live
rows, clicking one routes to `/admin/civstudio-sessions/<id>`, no page errors).

### C3 — Panels — **SHIPPED 2026-07-20**

Five tabs under the Overview card, all on `serverApi.ts` + `useServerPoll`: **Colony** (`/colony` —
vitals, colony-average skill profile, household roster), **Court** (`AdvisorView` from the snapshot →
`/person/{id}` character sheet with passions + household), **Bands** (`CaravanView` from the snapshot
→ `/caravan/{id}` crew in survival/succession order), **Events** (`/events` with the server's own
`level`/`grep` filters), **Commands** (the §C1 route).

The **colony dropdown** appears only when a run has more than one colony — a single-colony run gets
no dead control. It drives both colony-scoped panels, and `?colony=` is sent only when the choice is
*not* the run's first colony, so the single-colony case stays on the server's own default resolution
(which also works before the first snapshot frame arrives, when no colony name is known yet).

Caravans and advisors are read from the **snapshot**, because that is the only place either is
enumerated — there is no `/caravans` route, and advisors live on `ColonyView`.

**Testing** is documented in [`tools/webverify/README.md`](../tools/webverify/README.md) §Studio admin
checks: `sessions-page-verify.mjs`, `sessions-panels-verify.mjs`, and `sessions-commands-verify.mjs`
(which needs a local server, since the command log is gated *and* newer than the deployed build; the
README carries the exact `spring-boot:run` invocation, including the **fixture** world source that
`generated/`'s removal made mandatory).

> **Trap — never render history from `snapshot.log`.** It is the drain-once delta; a STOPPED session's
> cached frame will hand you the same one or two lines on every poll, forever. Use `/events` for
> history. If you also render the live stream, replicate the monotonic-tick gate from
> `web/js/snapshot-dedupe.mjs` — the server-side replay was never fixed.

> **Trap — a failed read must never render as an empty state.** `useServerPoll` exposes `error`, and
> the first cut of these panels ignored it: against a server predating the `/commands` route the
> Commands panel confidently reported *"Applied 0 · No commands have been applied to this run"* for a
> request that had **405**'d. "There is nothing here" is a claim about the run; never make it on a read
> that did not happen. Every panel now returns `<LoadError>` when `error && !data`.

---

## D — Map ↔ province two-way link

**Goal:** the province content entry and the rendered province reach each other. Bidirectionality is
what makes studio a control plane rather than two tools in one browser.

### D1 — Admin → map — **SHIPPED 2026-07-20**

`OpenInWorldMap` injected into `editView` / `right-links`. That zone is shared by **every** content
type, so the component self-filters to `api::province.province` and renders nothing elsewhere. It
reads the live **form values**, not the saved document, so an unsaved realm change links where the
editor is looking rather than where the row last was; a province with no id yet (a new entry) gets no
link rather than a link to nowhere.

Registered in **`bootstrap`**, not `register`: the zone belongs to the content-manager *plugin*,
which is loaded by then — and `getPlugin` is one of the few things bootstrap's restricted `Pick`
does give us.

### D2 — Map → admin (needs the key lookup)

Add an "Edit in Studio" link to the province rail (`web/js/rail.mjs:93`, `provinceRail(p)` — it already
renders `province ${p.id}`).

The `provinceId → documentId` problem. **Recommended: runtime lookup** —
`GET /api/provinces?filters[provinceId][$eq]=<id>&fields[0]=documentId&locale=en`, then build the
content-manager URL. One extra request, only on click, and it keeps the bundle faithful. The
alternative — emitting `documentId` into the bundle — is a faithfulness change that
`studio/scripts/verify-bundle.js` diffs per-record, and it couples a rendering artefact to a CMS
implementation detail. Prefer the lookup.

### D3 — Close the URL round-trip — **SHIPPED 2026-07-20**

`selectProvince` now mirrors the selection into `?p=` via `history.replaceState` — browsing, not
navigation, so Back still leaves the map instead of walking every glance. `z` is deliberately not
written: zoom changes continuously, so capturing whatever it happened to be at click time would be
arbitrary, and without it a reload frames the whole province (`focusProvinceFit`), which is the
useful landing.

The query-string arithmetic lives in a new dependency-free `web/js/deeplink.mjs` (`selectionUrl`)
because importing `rail.mjs` in node fails — it pulls in modules that read `window.BUNDLE` at module
scope. Unit-tested in `web/js/deeplink.test.mjs` (6 cases, incl. that `realm`/`session`/`live` and
the mode hash all survive).

### D4 — Embedded map — **SHIPPED 2026-07-20**

Two surfaces, one `WorldMapFrame`: a homepage **widget** (pinned full-width) and a **World map** page
on the left nav (`/admin/civstudio-map`), which forwards `?p=`/`?realm=` into the frame — so
`/admin/civstudio-map?p=4411` is a shareable admin link to a province. No `postMessage` bridge was
needed: changing the iframe `src` re-navigates the viewer, and the viewer's existing deep-link
contract does the rest.

`frame-src` **and** `child-src` were added to `studio/config/middlewares.ts`. Helmet's `useDefaults`
leaves `frame-src` falling back to `default-src 'self'`, which blocks the iframe outright — and the
element renders either way, so the only symptom is an empty panel plus a console refusal. The verify
script asserts on the refusal, not the element.

Two things the embed needs that a cold visit does not, both URL params:
- **`?live=<serverBase>`** — without it the viewer opens its "Choose a server" splash and waits. In an
  admin panel the answer is never in doubt: it is the server the ops widgets already talk to.
- **`?lobby=0`** — a new opt-out in `web/index.html`'s `openLobbyDuringLoad`. Embedded, the Spectator
  Lobby is not a choosing but a modal in someone else's panel, over the map you asked for. A URL flag
  rather than the existing `sessionStorage` route, because the embedder is cross-origin and cannot
  reach into the viewer's storage. Links that open in their **own tab** deliberately keep the lobby —
  there it *is* the front door. Verified both ways by `tools/webverify/lobby-optout-verify.mjs`.

**Not done: `frame-ancestors` on the SWA side.** `web/staticwebapp.config.json` still sets no
`X-Frame-Options`/`frame-ancestors`, so the viewer is embeddable by anyone — permissive by accident
rather than by decision. Tightening it to `'self' https://civstudio.com` is worth doing, but
deliberately *not* in the same change that starts depending on framing: `web/` auto-deploys on push,
so a wrong origin list would break the embed the moment it shipped, with no staging step in between.

---

## Sequencing

| Order | Why |
|---|---|
| **C** first | Independent, all-assembly, visible win. Establishes the first custom admin page, which D and later work reuse. |
| **D1+D3** next | Hours, not days. D1 needs no CSP change; D3 is a one-liner that makes the viewer linkable. |
| **A** then | The compounding one, but gated on the `contentVersion` prerequisite. Do A0–A2 (pure engine, behaviour-neutral) before committing to A4. |
| **B** last | Only meaningful once A gives it a profile to name. |
| **D2** any time | Independent of the rest; needs the lookup decision. |
| **D4** only if needed | Most cost, least marginal value over D1. |

## Explicitly out of scope

Live session state as content types (runtime, not content — it would poison content-versioning); the
plot cache (a ~24-min baked artefact keyed by `MAP_VERSION`, belongs in blob storage); anything on the
per-tick hot path.

## Open decisions

1. ~~**A1** — flatten `Era.MEDIEVAL.economy()`, or make it a profile component?~~ **Decided
   2026-07-20:** neither — economy is an **era × race** authored matrix and stays out of the profile;
   today's values are the human column. See §A1.
2. **`contentVersion` on `SessionRecord`** — confirm this lands *before* A4, and whether old records
   without one are treated as "unknown" or refused.
3. **B2 behaviour change** — what should `SessionHost.build` do with an unrecognised scenario string?
   404, or fall back to `"standard"` with a loud warning? (Existing sessions in the registry carry
   free-form strings, so a hard 404 could strand them.)
4. **D2** — runtime `documentId` lookup (recommended) vs emitting it into the bundle.
5. **C1 authz** — is command history owner-or-admin, or admin-only?
