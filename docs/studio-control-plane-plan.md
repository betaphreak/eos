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

### A1 — Flatten the `Era.MEDIEVAL.economy()` delegation

`SimulationConfig.DEFAULT` must be expressible as pure data. Either inline the 14 delegated values or
make `Era.Economy` a serialisable component of the profile. **Decide before A2** — this is the one place
where "just serialise the record" does not hold.

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

### D1 — Admin → map (cheapest; no CSP change, no embedding)

`injectComponent('editView', 'right-links', …)` on the province edit view: an "Open in world map" link to
`https://anbennar.civstudio.com/?realm=<realm>&p=<provinceId>`. Reads `provinceId` + `realm` off the
form. Deep-linking already works — `readDeepLink` handles `?p=&z=` and cross-realm navigation.

### D2 — Map → admin (needs the key lookup)

Add an "Edit in Studio" link to the province rail (`web/js/rail.mjs:93`, `provinceRail(p)` — it already
renders `province ${p.id}`).

The `provinceId → documentId` problem. **Recommended: runtime lookup** —
`GET /api/provinces?filters[provinceId][$eq]=<id>&fields[0]=documentId&locale=en`, then build the
content-manager URL. One extra request, only on click, and it keeps the bundle faithful. The
alternative — emitting `documentId` into the bundle — is a faithfulness change that
`studio/scripts/verify-bundle.js` diffs per-record, and it couples a rendering artefact to a CMS
implementation detail. Prefer the lookup.

### D3 — Close the URL round-trip

`selectProvince` must `history.replaceState` the `?p=` param, so "what I'm looking at" is always
linkable. Small change in `web/js/rail.mjs:87`; keep it `replaceState` (not `push`) so province
selection doesn't spam the back button.

### D4 — Embedded map page (optional, later)

Only if D1–D3 prove insufficient. Requires: `'frame-src': ["'self'", 'https://anbennar.civstudio.com']`
in `studio/config/middlewares.ts`, an explicit `frame-ancestors https://civstudio.com` on the SWA side
(`web/staticwebapp.config.json` — currently unset, i.e. accidentally permissive rather than deliberately
so), and a `postMessage` seam for cross-frame selection. `connect-src` already allows `https:`, so the
bundle fetch needs no change.

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

1. **A1** — flatten `Era.MEDIEVAL.economy()` into `SimulationConfig.DEFAULT`, or make `Era.Economy` a
   serialisable profile component? Affects whether `Era` stays the home of era-scaled economics.
2. **`contentVersion` on `SessionRecord`** — confirm this lands *before* A4, and whether old records
   without one are treated as "unknown" or refused.
3. **B2 behaviour change** — what should `SessionHost.build` do with an unrecognised scenario string?
   404, or fall back to `"standard"` with a loud warning? (Existing sessions in the registry carry
   free-form strings, so a hard 404 could strand them.)
4. **D2** — runtime `documentId` lookup (recommended) vs emitting it into the bundle.
5. **C1 authz** — is command history owner-or-admin, or admin-only?
