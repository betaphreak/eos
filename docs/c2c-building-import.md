# Plan: import C2C buildings, gate them to the tech horizon, wire them into the tech tree

**Status:** **Phases 1–4 shipped** (2026-07-13/14): the import (`BuildingInfoExporter` →
`generated/buildings.json`, **1,270** gated buildings), the button-art bake (`building-icons.webp`),
the full-canvas tech-tree view (grid + rail inspector + unified search + researched-state), and the
engine unlock model (`building-unlocks.json`, tokens granted on research). **Only Phase 5 (auto-build
onto district plots) remains** — the one behavior-changing step, deferred with `district-generator.md`.
Written 2026-07-13; decisions locked with owner.
Companion to [`district-generator.md`](district-generator.md) (the districts these buildings populate)
and the tech-tree docs ([`tech-tree.md`](tech-tree.md)); the button-art bake mirrors the existing
tech-button bake (`web/build-techs.mjs`).

**As-built deltas from the plan (Phase 1).** Three assumptions in the plan below were corrected against
the real C2C data at the pinned ref:
- **Count is 1,270, not ~1,449** (1,090 Regular + 180 Special + 0 zProviders). The plan's estimate was
  high; with the locked gate the *full-expression* gate and a *primary-`PrereqTech`-only* gate give the
  **same** set — every building with an in-scope primary also has in-scope `<TechTypes>` — so the AND-list
  never tightens the count, it only validates. (2,896 `<BuildingInfo>` scanned: 319 have no `<PrereqTech>`,
  1,307 gated out by tech scope. 8 commented-out records the DOM parser correctly ignores.)
- **`category` derives from `<Advisor>`, not `<BuildingClassType>`** — that field **does not exist** in
  C2C building XML. `<Advisor>` (6 canonical values) is reused via the existing `com.civstudio.tech.Advisor`
  enum, so the building taxonomy *is* the tech tree's advisor axis (maximally "shared beyond the tech tree").
  **145 buildings carry no `<Advisor>`** (belief/housing/safety module buildings — they also carry no
  `<Flavors>`, so there's no signal to derive one); their `category` is **omitted** and the web groups them
  in an "Other" bucket.
- **Building GameText spans six files** (`Buildings`, `Buildings_Animals`, `Slavery`, `Traditions`,
  `Human_Sacrifice`, `Cannibalism` `_CIV4GameText.xml`) — every gated building's name resolves from one of
  them; no de-`TXT_KEY_` fallback was needed in practice.
- **Wonders are out of scope** (owner) — Great/Group/National Wonders (and the `zAnimals`/`zCultures`/
  `zEarthBuildings`/`zFolklore` sets) are **not** imported; CivStudio will use **Anbennar monuments** instead.
- Exporter lives at **`com.civstudio.settlement.export.BuildingInfoExporter`**, reusing the (now `public`)
  `geo.export.Civ4Xml` DOM helpers. The kept-tech set is read straight from the baked `techs.json` (339
  ids), so it can't drift from the shipped tree.

**Goal.** Import the Caveman2Cosmos (C2C) building set as **engine content**, **gated to the eos
tech horizon**, so that: (1) each kept tech's `TechEffect.Unlock` names the buildings it unlocks;
(2) researching a tech auto-builds those buildings onto the colony's **district plots**
(`Settlement.getDistrictPlots()`); and (3) the web **tech tree shows each tech's buildings as a row
of real C2C button icons** under its node. Buildings render (in the district view) from C2C art via
`nifbake` — see `district-generator.md` §1 Layer 3; **this** doc is the *import + tech* half.

## Decisions (locked with owner, 2026-07-13)

| Question | Decision |
| --- | --- |
| **Import scope** | **All** buildings gated by a **kept tech** — no curation. ~**1,449** (1,245 Regular + 204 Special; zProviders carry no `PrereqTech`). |
| **Gate** | A building is in-scope iff **its whole tech prereq expression resolves within the 502 kept techs** — its primary `<PrereqTech>` **and every `<TechTypes>` entry** (see *Prereq handling*). Kept set = the Prehistoric→Atomic horizon capped at `TECH_INFORMATION_LIFESTYLE` (`TechInfoExporter.IN_SCOPE`/`CAP`/`DROP`). A building needing any later-era / dropped-religion tech is excluded (unreachable — no node satisfies it). |
| **Prereq handling** | Mirror the tech tree (owner). Buildings carry a primary `<PrereqTech>` **plus `<TechTypes>`** — a **list of additional AND-required techs** (93 Regular / 9 Special). *(These are the building field names; the tech `<PrereqAndTechs>`/`<PrereqOrTechs>` don't appear in the building data — `TechTypes` is the AND form. No OR form is present, but the code path mirrors `TechTree`'s and/or so an OR would be handled if it appears.)* Model prereqs, validate them against the kept set, and satisfy them, the same way `TechTree` does (`andPrereqs`/`orPrereqs`, `prereqsSatisfied`). |
| **Researched state** | The tech tree shows **Dhenijansar's research level in the live demo** (owner): techs Dhenijansar has researched read as *researched*, and their buildings light; the rest are dimmed. Makes the view **session-aware**, not a static reference. |
| **Building id** | **C2C `BUILDING_*` verbatim.** `Building.id` = the C2C `<Type>` string = the `TechEffect.Unlock` target. No mapping table. |
| **Plan depth** | **Includes the engine unlock model** — populate `TechEffect.Unlock` in `tech-effects.json` *and* wire the tech-gated auto-build onto district plots (couples to `district-generator.md` placement). |
| **Tech-tree UI** | A **grid of building-button icons under each node**: **24 wide × up to 3 rows** (72 max ≥ the worst case, `TECH_ORCHARDS` 55). **Card height is grow-to-fit** (1–3 rows per its count; ragged heights accepted). The grid **fades in when zoomed in** (illegible when zoomed out, so it appears past a zoom threshold). Each icon sits in a **uniform frame/backing** so the varied C2C art reads as one set. **Clicking a building → an inspect panel** (name, C2C help/pedia text, larger art). |
| **Icon sheet layout** | Bake the building-icon WebP at **~50 columns** (storage layout only — unrelated to the 24-wide *display* grid); missing-art → colour-chip fallback. |

## Why this is bounded & safe

The gate ties the building set to the **same horizon as the shipped tech tree**, so every imported
building attaches to an existing node, and the count is bounded (~1,449, not the full ~2,900). Ids
are verbatim, so there is **no mapping layer** to drift. Import is pure data (deterministic; **no
economic RNG** — a new salted stream only if placement ever needs randomness).

---

## Architecture

### 1. Engine: the building data + the unlock wiring
- **`Building(id)` already exists** (`settlement/Building.java`) — a record keyed by an eos-native id
  that equals the tech `Unlock` target. With verbatim ids, `id == "BUILDING_ORCHARD"` etc. `Plot`
  already tracks `buildings()` / `addBuilding` / `hasBuilding` (`district-generator.md` §2). **No new
  types needed.**
- **New exporter `BuildingInfoExporter`** (sibling of `TechInfoExporter`, `tech/export/` or a new
  `settlement/export/`): reads the three `Assets/XML/Buildings/{Regular,SpecialBuildings,zProviders}_
  CIV4BuildingInfos.xml` via `com.civstudio.data.Civ4Files`, keeps those whose `<PrereqTech>` ∈ the
  kept-tech set (reuse `TechInfoExporter`'s `IN_SCOPE`/`CAP`/`DROP` to compute the kept set), and
  emits **`generated/buildings.json`**: per building `{ id, name, help, pedia, prereqTech, andTechs,
  category, artDefineTag, button }` (+ optionally `cost`). **`category`** is a first-class field (the
  grid groups by it, §4) and a **shared taxonomy reused beyond the tech tree** (owner) — derive it from
  C2C's `<BuildingClassType>` / Civ4 `Flavors`, folded into a small eos category set (economy /
  military / culture / faith / infrastructure / …). Author the fold once as a committed table so every
  consumer shares it. **`prereqTech`** is the
  primary; **`andTechs`** is the flattened `<TechTypes>` list (the AND-required techs) — the building's
  analogue of a tech's `andPrereqs`, so a building's full requirement is *`prereqTech` AND all
  `andTechs`*. The exporter validates every one resolves to a **kept** tech (fail-fast, exactly like
  `TechTree.validatePrereqs`) and **drops the building** if any doesn't (the gate). `name`/`help`/`pedia`
  resolve the `TXT_KEY_BUILDING_*` strings via C2C GameText (§2). `button` is the `<Button>` path from
  `CIV4ArtDefines_Building.xml` by `artDefineTag` (§3).
- **Tech → Unlock effects.** Populate **`tech-effects.json`** (today `{}`) with, per kept tech, an
  `Unlock` effect per gated building: `{ "type":"UNLOCK", "target":"BUILDING_ORCHARD" }`. Authored by
  the exporter (not by hand). Hang the `Unlock` off the building's **primary `prereqTech`**; `TechTree`
  grants the building token on research (the existing `TechEffect.Unlock` path — "granted tokens on
  the colony").
- **Respect the full AND expression.** A building isn't actually *available* until its `prereqTech`
  **and every `andTechs`** are researched — the single-tech grant isn't enough. So the availability
  check reuses `TechTree`'s **`prereqsSatisfied` (AND-all / OR-any)** over the building's prereqs,
  exactly as a tech's own prereqs are checked; the `Unlock` token marks the primary, the AND-list gates
  when the building becomes buildable.
- **Auto-build onto district plots.** Wire the **deferred tech-gated auto-build trigger** named in
  `Plot`/`Building`: once a building's full prereq expression is satisfied, the settlement calls
  `addBuilding` on a district plot from `getDistrictPlots()` (center buildings at plot 0 — the village
  center — per `Building.java`; on-plot/functional buildings later). Placement rules and the
  district-type seam are `district-generator.md` §2/§2a. **This is the one behavior-changing phase** —
  stage it last and keep it off until placement is designed, so the import + tech-view land
  byte-neutral first.

### 2. Fetch: add the two missing C2C sources
`Civ4Files` (Java) and `web/civ4.mjs` (Node) already fetch the `CIV4BuildingInfos.xml`. **Add**:
- **`Assets/XML/Art/CIV4ArtDefines_Building.xml`** (1.46 MB) — the `artDefineTag → <Button>` map
  (verified: `ART_DEF_BUILDING_ACCOUNTING_FIRM → art/Craft/Art/accountingfirm.dds`).
- **Building GameText** (`Assets/XML/GameText/*Buildings*_CIV4GameText.xml`) — the `TXT_KEY_BUILDING_*`
  **name / help / pedia** strings, for `buildings.json` (names + the click-to-inspect panel, §4), same
  pattern as `TechInfoExporter`'s English-string load.

Both go in the `Civ4Files.PATHS` map (committed-relative key → C2C path) and the `civ4.mjs`
equivalent, cached under `.civ4-cache/<ref>/` like everything else.

### 3. Web bake: `building-icons.webp` (mirror `build-techs.mjs`)
New **`web/build-buildings.mjs`**, structurally the tech-button baker:
- Input: `buildings.json` (each carries its `button` .dds path; resolve via `civ4.mjs resolveArt`,
  warmed by `prefetch`).
- Pack every building button into **`web/assets/buildings/building-icons.webp`** — **64×64 cells, 50
  columns** (owner: rows of 50), ~1,449 cells → ~30 rows (≈3200×1920, within WebP's 16383 cap).
  Missing-art buildings get the colour-chip fallback (as `build-techs.mjs` does).
- Emit a manifest entry **`buildingIcons`**: `{ src, cell:64, cols:50, index:{ "BUILDING_*": n } }` in
  `web-asset-manifest.json`, assembled into the bundle by `WorldBundle` — **remember to add the new
  key to `WorldBundle`'s manifest→bundle allow-list** (the Phase-3 gotcha from
  `civ6-art-replacement.md`).
- **This is icon/button art only** — distinct from the in-world building *sprites* (`nifbake`, the
  `.nif` albedo) the district view stamps (`district-generator.md` §1 Layer 3). Two different bakes
  from the same C2C building: the flat **button** here, the 3D-model **sprite** there.

### 3b. Presentation — depends on the **Technology Advisor** map-mode ([`privy-council.md`](privy-council.md))
This building work assumes the tech tree is no longer a modal but the **Technology Advisor** map-mode:
it renders over `#stage` (top bar + right rail stay), which frees the right rail to host the building
**inspector** (§4) — impossible while the modal covers the rail. That refactor — the advisor-mode
framework, the modal→map-mode conversion, and the researched-state colony binding — is designed in
[`privy-council.md`](privy-council.md), which **precedes this plan**. Everything below (the building
grid, inspector, search) plugs into that Technology Advisor + rail.

### 4. Web tech tree: the per-node building grid + inspector
The tech-tree data must carry, per tech, its unlocked building ids. Source: `buildings.json` +
`prereqTech` (the web joins tech→buildings), or the exporter emits the reverse index onto each tech
in `techs.json`. Then `web/js/techtree.mjs`:
- Under each node card (today: title + `t.icon` cell from `tech-icons.webp` + cost), draw a **grid of
  building buttons** — each building's cell from `building-icons.webp` via `buildingIcons.index`.
  Resize the tech card (`CARD_W`/`CARD_H` in `techtree.mjs`) so the grid is **24 icons wide**; icon
  size follows from `CARD_W / 24` (small, ~a dozen px).
- **Grow-to-fit height (owner):** each card is exactly as tall as its building count needs — **1, 2 or
  3 rows** (`ceil(count / 24)`, capped at 3; 72 slots ≥ the worst case of 55, so **no scroll/overflow
  affordance**). Heights are ragged; since layout is per-column with `ROW_H`, either give each grid
  **row** its own vertical slot or measure tallest-per-row so ragged cards don't overlap. Retune
  `COL_W`/`ROW_H` for the wider/taller cards.
- **Fade in when zoomed in (owner):** the building grid is illegible at 24-wide when the whole tree is
  in view, so draw it only past a zoom threshold — a `bandAlpha`-style cross-fade on the tree's own
  zoom (`KMAX`/`KSTEP`/`minZoom()`), the tech title+icon+cost staying visible at all zooms. When faded
  out, the card can also shrink back to its title-only height (so the tree is compact when overviewed).
- **Uniform frame/backing (owner):** the C2C buttons span many art styles/eras, so frame each cell in
  a consistent backing (a subtle rounded panel + hairline, rendered at draw time — keep the baked sheet
  raw art, cf. the resource-icon class backings which *are* baked; here a single render-time frame is
  cheaper and uniform). This makes the grid read as one set.
- **Order by category (owner):** within a node's grid, group buildings by **`category`** (the shared
  taxonomy, §1) so similar buildings sit together — more scannable than alphabetical/import order, and
  the same categories are reused elsewhere later.
- **Clickable → inspect, in the right-side rail (owner):** clicking a building icon opens its details
  in the **existing right-side rail** (the one the province/city panels use) — name, C2C **help/pedia**
  text, its **prereqs** (`prereqTech` + `andTechs`), `category`, and a larger view of the art (the
  button icon, or later the nifbake sprite). Needs the `TXT_KEY_BUILDING_*` **help/pedia** strings, so
  `buildings.json` carries `help`/`pedia` (§2). **Any building is inspectable — even dimmed/un-unlocked
  ones (owner)** — so the tree doubles as a browsable reference of everything ahead.
- **Researched state = Dhenijansar in the demo, two states (owner):** the view is **session-aware**.
  Read Dhenijansar's research from the live demo session (its `ResearchState` / researched-tech set),
  and show **two states only — locked vs unlocked**: a building lights when its **whole** prereq
  expression is researched (§1), otherwise it's dimmed. **No built-vs-unlocked distinction** — so the
  feed only needs the colony's **researched techs** (a small addition to the render snapshot / bundle),
  *not* per-plot `Plot.buildings()` built-state. The tree reflects what Dhenijansar has unlocked, not a
  static reference.

### 4a. Search bar — **one unified top-bar box, mode-scoped** (owner)
The map-mode refactor (§3b) means the **top bar persists across modes**, so the search **unifies into
the single top-bar box** — the tech modal's separate header search goes away with the modal (owner).
One widget (`searchbox.mjs`), no duplicate; its **corpus follows the active mode** (below).
- **Mode-scoped corpus (owner):** the box searches **only the active mode's content** — provinces in
  map-based advisors (as today), **techs + buildings** in the Technology Advisor. Same box, same
  `searchbox.mjs` behaviour; the caller swaps the `search`/`renderRow`/`onPick` per mode. No
  always-global corpus, no cross-mode switch-on-pick (you switch to the Technology Advisor first).
- **Per-mode placeholder (owner):** the placeholder tracks the mode — "Find a province…" (map) /
  "Find a tech or building…" (Technology Advisor).
- **Techs+buildings results:** **one interleaved ranked list** with a per-row **kind chip** (tech vs
  building) — no sections, no filter tabs (owner). **Match name + id**, case-insensitive
  (de-`TXT_KEY_`/`TECH_`/`BUILDING_`-prefixed for display).
- **Quick-open hotkey (owner):** a key (e.g. `/` or Ctrl-K) focuses the search from any mode — wire it
  through `shortcuts.mjs`, checking it doesn't clash with existing bindings.
- **On pick:** a **tech** → the existing centre-node + flash (`centerNode`/`nodeEl`); a **building** →
  pan to its **primary `prereqTech`** node, force the zoom past the grid fade-in (§4), highlight the
  cell, and open its **rail** inspector (§4). A building lives under one node, so the jump is
  unambiguous.

---

## Phasing (import + tech-view land behavior-neutral; auto-build last)

- **Phase 1 — `BuildingInfoExporter` → `buildings.json`. ✅ DONE (2026-07-13).** 1,270 kept-tech-gated
  buildings, verbatim ids, `{id,name,help,pedia,category,prereqTech,andTechs,artDefineTag,button,cost}`.
  Added `CIV4ArtDefines_Building.xml` + the six building GameText files to `Civ4Files.FILE_MAP` (the web
  `civ4.mjs` needs no XML entries — buildings.json already carries the resolved `button` .dds path, so the
  Phase-2 bake only fetches art). Promoted `Civ4Xml` helpers to `public`. No behavior change (pure data;
  nothing loads `buildings.json` yet).
- **Phase 2 — button-art bake. ✅ DONE (2026-07-13).** `web/build-buildings.mjs` →
  `web/assets/buildings/building-icons.webp` (64², 50 cols; 3200×1344, **1,046/1,270** icons, 224
  colour-chip fallbacks) + `civstudio-server/src/main/resources/buildings-meta.json` (per-building
  `icon` rect, keyed by id). **Routing deviates from the plan:** building icons do **not** go through
  `web-asset-manifest.json` / `WorldBundle` (that's the world-*map* bundle). The building grid lives in
  the **tech-tree view**, which is served by `TechBundle` `/api/techs`; so buildings follow the *tech*
  infra — `buildings.json` (engine jar, `/buildings.json`) + `buildings-meta.json` (icon rects) merged by
  a parallel `BuildingBundle` at **`/api/buildings`** (Phase 3), exactly the `TechBundle`/`techs-meta`
  pattern. Shared bake helpers (`iconPath`/`iconCell`/`packSheet`) factored into `web/icon-bake.mjs` and
  reused by `build-techs.mjs` (byte-identical regen verified).
- **Phase 3 — tech-tree view. ✅ DONE (2026-07-14).** Delivered in three parts:
  - **3a — the enabling refactor.** The tech tree became the Technology advisor's **full-canvas
    map-mode** (covers the stage region, top bar + advisor sub-bar + right rail stay live), the era
    tabs moved into the top-bar sub-strip, and node detail moved into the **shared right rail** — so
    the rail is free for the building inspector (the §3b dependency, realized here rather than assumed
    from privy-council). Server side: a parallel **`BuildingBundle` → `/api/buildings`** (the
    `TechBundle` pattern).
  - **3b — the grid, as a continuous LOD (owner's pick, deviates from the plan's always-24-wide fade).**
    Every node with buildings shows a thin **category-spectrum footer bar** (segment colour = category,
    width ∝ count); **focusing** a node expands its full building grid **in the rail**, grouped by
    category over a category-tinted backing with uniform frames. Click a building → its **rail
    inspector** (art, category, pedia, "unlocked by" the primary + AND techs). Any building is
    inspectable, locked or not.
  - **3c — search + researched-state.** The unified top-bar search interleaves **techs + buildings**
    with a kind chip; picking a building jumps to its tech and inspects it. The tree is **session-aware**:
    `ColonyView.knownTechs` (the spectated colony's known set — the demo's **Dhenijansar**, 229 techs)
    **dims** the techs/buildings it hasn't unlocked (110 of 339), desaturated, never hidden.
  - Verified headless throughout (`tools/webverify/tech-verify.mjs`, incl. `--spectate`); zero console
    errors. The one deferred owner idea (advisor-noble portrait in the building inspector) is future
    work — the seat already shows as the court chip in the sub-bar.
- **Phase 4 — engine unlock model. ✅ DONE (2026-07-14).** `BuildingInfoExporter` also authors a
  **generated `generated/building-unlocks.json`** overlay — **1,266 `Unlock(BUILDING_*)` effects over 285
  techs** (keyed by each building's primary `prereqTech`; the 4 CAP-gated buildings are excluded, since
  the engine drops that tech). **Routing deviates from the plan:** rather than populate the hand-authored
  `tech-effects.json` (which would risk clobbering hand-authored effects on regeneration, and collides on
  the `/tech-effects.json` classpath path), the building unlocks are a **separate generated overlay** that
  `TechTree` **merges** with the hand-authored one at load (`mergeEffects`). Building unlocks are
  **universal** (race-independent), so both the default `TechTree.load()` and the race path
  (`TechTree.loadWithRaceOverlay`, used by `GameSession.getTechTree(Race)`) merge them; the test-only
  `load(String)` stays single-overlay for isolated assertions. `ResearchState.complete()` grants the
  `BUILDING_*` tokens into `Settlement.getGrantedTechTokens()` on research (asserted end-to-end in
  `TechResearchTest`). No placement (Phase 5), so runs stay clean; all 272 engine tests pass.
- **Phase 5 — auto-build onto district plots.** The tech-gated `addBuilding` trigger + placement onto
  `getDistrictPlots()`. Couples to `district-generator.md` placement; **the behavior-changing step** —
  gate it off by default until district placement is designed. **Sequenced in
  [`district-buildout.md`](district-buildout.md) Phase D2** (the execution plan that carries Phase 5 +
  the district generator to completion).

Each phase: build → (web) `node web/build*.mjs` + refresh engine jar + `spring-boot:run` + webverify;
(engine) `mvn -pl civstudio-engine test`. Commit per phase.

## Open questions / risks

- **Special vs. Regular buildings.** Both imported (1,245 + 204). Confirm zProviders (no `PrereqTech`,
  0 gated) are correctly excluded, or need a different gate.
- **Multi-prereq buildings — handled (owner).** Buildings carry a primary `<PrereqTech>` + a
  `<TechTypes>` AND-list (`andTechs`), modelled/validated/satisfied like a tech's own and/or prereqs
  (§1, *Prereq handling*). The building **displays** under its primary `prereqTech` node, but only
  becomes **buildable/lit** when the full `prereqTech` + `andTechs` expression is satisfied. The tech
  `<PrereqAndTechs>`/`<PrereqOrTechs>` field names don't occur in the building data (no OR observed),
  but reusing `TechTree`'s and/or path means an OR would be handled if a mod adds one.
- **Building GameText spread.** C2C building names live across several GameText files; the exporter
  must load all matching `*Buildings*_CIV4GameText.xml`, and fall back to a de-`TXT_KEY_`-ed id when a
  name is missing (as the tech exporter counts `unresolvedName`).
- **1,449 is data, mostly dormant.** Most imported buildings have no CivStudio economic meaning yet —
  they exist as unlock data + tech-tree art until district placement + a building economic effect land
  (both deferred). No silent economic change.
- **Icon coverage.** Some `<Button>` paths may 404 at the pinned C2C ref → colour-chip fallback; log
  the count (like `build-techs.mjs`'s "N without art").

## Key files

- New: `docs/c2c-building-import.md` (this); `BuildingInfoExporter` (engine); `generated/buildings.json`;
  `web/build-buildings.mjs`; `web/assets/buildings/building-icons.webp`.
- Changed: `com.civstudio.data.Civ4Files` + `web/civ4.mjs` (add `CIV4ArtDefines_Building.xml` +
  building GameText); `tech-effects.json` (populate `Unlock`s); `web-asset-manifest.json` +
  `server.web.WorldBundle` (`buildingIcons` key); `web/js/techtree.mjs` (per-node building row);
  `generated/techs.json` *iff* the tech→buildings index is emitted onto techs.
- Reuse: `Civ4Files`/`civ4.mjs` fetch+cache, `build-techs.mjs` bake pattern, `TechInfoExporter`'s
  kept-tech computation, `TechEffect.Unlock`, `Plot.addBuilding`.

*Planned 2026-07-13, decisions locked with owner. Sibling to `district-generator.md`; when Phase 1
lands, add a one-line pointer in `CLAUDE.md` (tech-tree/buildings) and cross-link here.*
