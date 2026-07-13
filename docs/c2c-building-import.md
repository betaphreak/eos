# Plan: import C2C buildings, gate them to the tech horizon, wire them into the tech tree

**Status:** plan (design only — nothing implemented). Written 2026-07-13; decisions locked with owner.
Companion to [`district-generator.md`](district-generator.md) (the districts these buildings populate)
and the tech-tree docs ([`tech-tree.md`](tech-tree.md)); the button-art bake mirrors the existing
tech-button bake (`web/build-techs.mjs`).

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
| **Gate** | A building is in-scope iff its `<PrereqTech>` is one of the **339 kept techs** (the Prehistoric→Renaissance horizon, capped at `TECH_INDUSTRIAL_LIFESTYLE` — `TechInfoExporter.IN_SCOPE`/`CAP`). Later-era / dropped-religion-tech buildings are excluded (no tech node to hang them on). |
| **Building id** | **C2C `BUILDING_*` verbatim.** `Building.id` = the C2C `<Type>` string = the `TechEffect.Unlock` target. No mapping table. |
| **Plan depth** | **Includes the engine unlock model** — populate `TechEffect.Unlock` in `tech-effects.json` *and* wire the tech-gated auto-build onto district plots (couples to `district-generator.md` placement). |
| **Tech-tree UI** | A **grid of building-button icons under each node**, always visible; **resize the tech card** to fit **24 icons wide × up to 3 rows** (72 max — covers the worst case, `TECH_ORCHARDS` at 55). |
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
  emits **`generated/buildings.json`**: per building `{ id, name, prereqTech, artDefineTag, button }`
  (+ optionally `cost`, `category` from the XML). `name` resolves `TXT_KEY_BUILDING_*` via C2C
  GameText (see §4). `button` is the `<Button>` path pulled from `CIV4ArtDefines_Building.xml` by
  `artDefineTag` (see §3).
- **Tech → Unlock effects.** Populate **`tech-effects.json`** (today `{}`) with, per kept tech, an
  `Unlock` effect per gated building: `{ "type":"UNLOCK", "target":"BUILDING_ORCHARD" }`. Authored by
  the exporter (join buildings→prereqTech), not by hand. `TechTree` then grants the building token on
  research (the existing `Unlock` path — `TechEffect.Unlock`, "granted tokens on the colony").
- **Auto-build onto district plots.** Wire the **deferred tech-gated auto-build trigger** named in
  `Plot`/`Building`: when a tech unlock grants `BUILDING_*`, the settlement calls `addBuilding` on a
  district plot from `getDistrictPlots()` (center buildings at plot 0 — the village center — per
  `Building.java`; on-plot/functional buildings later). Placement rules and the district-type seam
  are `district-generator.md` §2/§2a. **This is the one behavior-changing phase** — stage it last and
  keep it off until placement is designed, so the import + tech-view land byte-neutral first.

### 2. Fetch: add the two missing C2C sources
`Civ4Files` (Java) and `web/civ4.mjs` (Node) already fetch the `CIV4BuildingInfos.xml`. **Add**:
- **`Assets/XML/Art/CIV4ArtDefines_Building.xml`** (1.46 MB) — the `artDefineTag → <Button>` map
  (verified: `ART_DEF_BUILDING_ACCOUNTING_FIRM → art/Craft/Art/accountingfirm.dds`).
- **Building GameText** (`Assets/XML/GameText/*Buildings*_CIV4GameText.xml`) — `TXT_KEY_BUILDING_* →`
  English name, for `buildings.json` names + tech-tree tooltips (same pattern as `TechInfoExporter`'s
  English-string load).

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

### 4. Web tech tree: a building-button row per node
The tech-tree data must carry, per tech, its unlocked building ids. Source: `buildings.json` +
`prereqTech` (the web joins tech→buildings), or the exporter emits the reverse index onto each tech
in `techs.json`. Then `web/js/techtree.mjs`:
- Under each node card (today: title + `t.icon` cell from `tech-icons.webp` + cost), draw a **grid of
  building buttons** — each building's cell from `building-icons.webp` via `buildingIcons.index`.
  **Resize the tech card** (`CARD_W`/`CARD_H` in `techtree.mjs`) so the grid is **24 icons wide, up to
  3 rows** (owner) — 72 slots, ≥ the worst case (`TECH_ORCHARDS`, 55). Icon size follows from
  `CARD_W / 24` (small, ~a dozen px); the card grows taller by the number of rows a node needs (1–3).
- Hover/click a building icon → tooltip with its `name` (from the GameText join). Optional: dim
  buildings whose tech isn't yet researched, matching the tree's `lit`/`sel` state.
- Layout note: 24×3 = 72 covers every kept tech (max 55), so **no scroll/overflow affordance is
  needed**; a node uses only as many rows as it has buildings. The wider card also reflows edges/
  columns — retune `COL_W`/`ROW_H` so resized cards don't overlap.

---

## Phasing (import + tech-view land behavior-neutral; auto-build last)

- **Phase 1 — `BuildingInfoExporter` → `buildings.json`.** Filtered to kept-tech-gated, verbatim ids,
  `{id,name,prereqTech,artDefineTag,button}`. Add `CIV4ArtDefines_Building.xml` + building GameText to
  `Civ4Files`/`civ4.mjs`. No behavior change (pure data).
- **Phase 2 — button-art bake.** `web/build-buildings.mjs` → `building-icons.webp` (64², 50 cols) +
  `buildingIcons` manifest key + `WorldBundle` allow-list.
- **Phase 3 — tech-tree view.** `techtree.mjs` renders the per-node building-button row + tooltips.
  Web-only; verify with `tools/webverify`.
- **Phase 4 — engine unlock model.** Populate `tech-effects.json` with `Unlock(BUILDING_*)` per tech;
  `TechTree` grants tokens on research. Assertable in tests (research tech → token present); no
  placement yet, so runs stay clean.
- **Phase 5 — auto-build onto district plots.** The tech-gated `addBuilding` trigger + placement onto
  `getDistrictPlots()`. Couples to `district-generator.md` placement; **the behavior-changing step** —
  gate it off by default until district placement is designed.

Each phase: build → (web) `node web/build*.mjs` + refresh engine jar + `spring-boot:run` + webverify;
(engine) `mvn -pl civstudio-engine test`. Commit per phase.

## Open questions / risks

- **Special vs. Regular buildings.** Both imported (1,245 + 204). Confirm zProviders (no `PrereqTech`,
  0 gated) are correctly excluded, or need a different gate.
- **Multi-prereq buildings.** C2C also has `PrereqAndTechs`/`PrereqOrTechs`. This plan keys a building
  to its single `<PrereqTech>` (the primary). If a building's *real* unlock needs several techs, it
  will show under only the primary node — acceptable for a first cut; note it.
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
