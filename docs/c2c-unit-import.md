# Plan: import C2C units, gate them to the tech horizon, realize them through the caravan system

**Status:** PLAN — design only, no code yet. **Date:** 2026-07-18. Decisions locked with owner
(five Q&A rounds). Direct sibling of [`docs/c2c-building-import.md`](c2c-building-import.md) — this
is the **units** half of the same import pattern (`Info`-XML → gated `generated/*.json` → button-art
bake → tech-unlock overlay → tech-tree row). The **runtime home is the caravan system**
([`docs/caravan.md`](caravan.md), [`docs/explorer-caravan.md`](explorer-caravan.md)): a mustered
band *embodies* a concrete `UNIT_*`, drawing its **name, move-points, combat, and sprite** from the
imported catalog. Companion to the tech-tree docs ([`docs/tech-tree.md`](tech-tree.md)); the bake
mirrors `web/build-buildings.mjs` / `web/build-techs.mjs`.

**Goal.** Import the Caveman2Cosmos (C2C) **land buildable** unit set as **engine content**, **gated
to the eos tech horizon**, so that: (1) each kept tech's unlock overlay names the units it unlocks
and researching it grants the `UNIT_*` token; (2) a caravan band picks the **best researched,
non-obsolete unit of its role** and takes on that unit's identity/stats/art; and (3) the web **tech
tree shows each tech's unlocked units as a row of real C2C button icons** under its node, beside the
building grid. This is the **catalog + caravan-identity** pass — no buildable-unit build queue and no
combat model (both explicitly future; cf. explorer-doc decision 16).

## Decisions (locked with owner, 2026-07-18)

| # | Question | Decision |
| --- | --- | --- |
| 1 | **Import scope** | **Land buildable only.** `DOMAIN_LAND`, non-animal, from the three core files `U_Land`, `U_Workers`, `CIV4UnitInfos`. **Exclude** the animal/subdued sets, all sea + air, **U_Neanderthals** (race-specific — belongs with the per-race overlay machinery, cf. `RaceNameGenerator`) and **U_LandFly** (helicopters/gunships — the Renaissance cap gates nearly all out anyway). |
| 2 | **Gate** | **Identical to buildings.** A unit is in-scope iff it has a `<PrereqTech>` **and its whole tech-prereq expression resolves within the kept techs** — the primary `<PrereqTech>` **and** every `<TechTypes>` AND-entry must name a tech surviving into `techs.json` (the Prehistoric→Renaissance horizon capped at `TECH_INDUSTRIAL_LIFESTYLE`). Reuse the exact kept-tech set (read from the baked `techs.json`) and the `CAP_TECH` handling from `BuildingInfoExporter`. |
| 3 | **No-`PrereqTech` units** | **REVISED (owner, Phase 1): kept, not dropped — linked to `TECH_SEDENTARY_LIFESTYLE`.** The original decision (drop them, mirroring buildings) was reversed during Phase 1: a land unit with no `<PrereqTech>` is now **linked to the early `TECH_SEDENTARY_LIFESTYLE`** so the ancient starters (`UNIT_BAND`/`UNIT_TRIBE`) and the **capture/immigration units** (`UNIT_CAPTIVE_MILITARY`/`_CIVILIAN`/`_IMMIGRANT`, `UNIT_FREED_SLAVE`) enter the catalog with an early unlock rather than being excluded. A unit with a *real* prereq beyond the horizon is still gated out. The capture/immigration units are additionally tagged `"special"` (`CAPTIVE`/`FREED_SLAVE`) for the future capture/slavery/property mechanics (the SUBTERFUGE seam). 75 units were linked this way in the Phase-1 bake. |
| 4 | **Stat depth** | **Caravan-relevant core** per row: `{ id, name, pedia, prereqTech, andTechs, combatClass, defaultUnitAI, caravanRole, domain, iMoves, iCombat, iCost, obsoleteTech, artDefineTag, button }` **plus the whitelisted `<SubCombatTypes>` tags** (decision 12): `bandSizeClass` (`GROUP_*`), `era` (`ERA_*`), `species` (`SPECIES_*`), `quality` (`QUALITY_*`). Enough to drive the march (`iMoves`), identity (`name`/`pedia`), art (`button`), the role fold (`combatClass`), obsolescence, band size, era ordering, and the tech gate. No weapon/armor/attack-form tags, bonuses-vs-class, free-promotions, formation (dormant reference with no consumer). |
| 5 | **Role model** | **Extend `CaravanRole` with 5 new roles** (below). The `CaravanRole` fold keys off the unit's **`<Combat>` (UnitCombat class) primary, `<DefaultUnitAI>` fallback** (decision 11). Store **both raw** (`combatClass` + `defaultUnitAI`) on every row, so a finer split later never needs a re-bake. |
| 6 | **New roles** | 4 existing (`SETTLER`←`UNITAI_SETTLE`, `WORKER`←`UNITAI_WORKER`+`WORKER_SEA`, `EXPLORER`←`UNITAI_EXPLORE`, `MILITARY`←all combat + property-control AIs) **+ 5 new**: **`TRADE`**←`MERCHANT`; **`MISSIONARY`**←`MISSIONARY`,`PROPHET`; **`HUNTER`**←`HUNTER`,`GREAT_HUNTER`; **`HEALER`**←`HEALER`; **`COVERT`**←`SPY`,`INFILTRATOR`. Great people (`GENERAL`/`SCIENTIST`/`ARTIST`/`ENGINEER`) and stray sea AIs **fold to nearest** (no `GREAT_PERSON` role — no subsystem justifies one yet). **9 roles total.** |
| 7 | **Unit id** | **C2C `UNIT_*` verbatim** — the `Unlock` target and the caravan's `unitId`. No mapping table. |
| 8 | **Unlock model** | **A merged `unit-unlocks.json` overlay** (per tech, `Unlock(UNIT_*)`), merged by `TechTree` exactly like `building-unlocks.json`; `ResearchState.complete()` grants the `UNIT_*` token into `Settlement.getGrantedTechTokens()`. The selection rule reads the colony's granted tokens. |
| 9 | **Which unit a band embodies** | **Producer-determined** (owner): a band carries **the unit type the settlement built to produce it** — not an auto "best-researched" scan. The real driver is production (the future build queue); **interim**, with no queue, the settlement produces the **best-available, non-obsolete unit of the role** it can currently build (`<ObsoleteTech>` honored — a unit leaves the pool once obsoleted). So the `unitId` is *chosen at production*, and the "best available" logic is just the interim stand-in for a player/AI build choice. |
| 9a | **Band combat strength** | **Unit stats + the people in it** (owner): a caravan is a band of **people who can fight**, so its effective strength is the embodied unit's stats **plus its members' `WARFARE` (and role signature) skill** — *not* the unit's static `<iCombat>` alone. This is why the civilian-role ranking gap is moot: `iCombat` never has to rank workers/settlers/traders — the people carry the combat, the unit carries the identity/art/`iMoves`. |
| 9b | **iCost** | **Captured, unused.** `iCost` (build hammers) rides the catalog row but the muster stays free (driven by the existing food/draft provisioning); the cost-to-build a unit is deferred with the build queue / production model. |
| 10 | **Web surfacing** | **A per-node unit row in the tech tree**, mirroring the building grid: a parallel **`UnitBundle` → `/api/units`** (the `TechBundle`/`BuildingBundle` pattern) + a `unit-icons.webp` bake. Units and buildings both show what a tech unlocks. |
| 11 | **UnitCombat reference table** | **Import the ~50 functional `UnitCombat` classes** as their own `generated/unit-combats.json` — each with `{ id, name, categoryButton, iEarlyWithdrawChange/iDodge/iDamage… (combat modifiers), bForMilitary }`. Serves three ends: the **source of the `<Combat>`→`CaravanRole`→skill fold** (decision 5 + the skill section), a **per-class category icon set** (`categories/*.dds` — cheaper than per-unit art for grouping the tech-tree unit row), and **reference data for a future combat model**. The functional set = the distinct `<Combat>` values used by in-scope units (~50 of the 724; the other ~670 are weapon/armor/animal/era/religion tag taxonomy). |
| 12 | **SubCombatTypes whitelist** | Capture only the **meaningful `<SubCombatTypes>` families** per unit (decision 4): `GROUP_*`→`bandSizeClass`, `ERA_*`→`era`, `SPECIES_*`→`species`, `QUALITY_*`→`quality`. `era` doubles as a clean **"most advanced" ordering key** for the interim build selection (decision 9). Skip the weapon/armor/attack-form/motility noise. |

## Why this is bounded & safe

The gate ties the unit set to the **same horizon as the shipped tech tree** and the shipped building
import, so every imported unit attaches to an existing node. Ids are verbatim (no mapping layer to
drift). Import is **pure data** — deterministic, **no economic RNG** (a new salted stream only if the
selection rule ever needs randomness, which "best researched" does not). The catalog is **mostly
dormant**: until the selection rule + march-stat wiring land (later phases), it is unlock data +
tech-tree art, changing no run. The pre-gate land-buildable pool is ~**433** units with a
`<PrereqTech>` across the three core files (U_Land 395 + U_Workers 21 + CIV4UnitInfos 17); the
Renaissance cap will cut the modern-era units (guns, robots, hi-tech, tanks) heavily, so the final
in-scope count is **measured at Phase 1** (estimate ~150–250, exactly as the building count was
corrected from estimate to the real 1,270).

---

## Architecture

### 1. Engine: the unit catalog + the unlock wiring
- **New `Unit(id)` record** (`settlement/Unit.java` or `agent/Unit.java`) keyed by the verbatim
  `UNIT_*` id — the `Unlock` target and the caravan's `unitId`. Mirrors `Building(id)`.
- **New exporter `UnitInfoExporter`** (sibling of `BuildingInfoExporter`, `settlement/export/`),
  reusing the `public` `geo.export.Civ4Xml` DOM helpers and `BuildingInfoExporter`'s kept-tech
  computation verbatim. Reads the three core unit files via `com.civstudio.data.Civ4Files`, keeps
  units whose whole prereq expression ∈ the kept techs, and emits **`generated/units.json`**: one row
  per decision 4's field set. `name`/`pedia` resolve the `TXT_KEY_UNIT_*` strings from unit GameText
  (§2). `caravanRole` is the `defaultUnitAI` folded onto the shared `CaravanRole` taxonomy (§1a);
  `button` resolves through the unit's art tag (§3). The exporter validates every prereq resolves to
  a kept tech (fail-fast) and drops the unit otherwise (the gate) — identical to
  `BuildingInfoExporter`.
- **`CaravanRole` extended** with `TRADE`, `MISSIONARY`, `HUNTER`, `HEALER`, `COVERT` (decision 6).
  The **`<Combat>` (UnitCombat class) → `CaravanRole` → signature `Skill` fold** is a **committed
  static table** (the analogue of buildings' `Advisor.fromKey` reuse) — authored once from the
  grounded mapping in the skill section, shared by the exporter (to stamp `caravanRole`) and any
  engine consumer. Keys off `<Combat>` primary, `<DefaultUnitAI>` fallback (decision 5); **both raw**
  ride each row so the fold can be refined without a re-bake.
- **`UnitCombatExporter` → `generated/unit-combats.json`** (decision 11) — a sibling pass (or a mode
  of `UnitInfoExporter`) reading `CIV4UnitCombatInfos.xml`, keeping the **functional** classes (those
  that appear as a `<Combat>` of an in-scope unit — ~50 of 724, the rest being tag taxonomy) and
  emitting `{ id, name, categoryButton, <combat modifiers>, bForMilitary }`. `name` resolves the
  `TXT_KEY_UNITCOMBAT_*` string; `categoryButton` is the class's `<Button>` (`categories/*.dds`). This
  is the fold's data source, the tech-tree grouping icon set, and future combat-model reference.
- **Tech → Unlock effects.** `UnitInfoExporter` also authors **`generated/unit-unlocks.json`** — per
  primary `prereqTech`, an `Unlock(UNIT_*)` effect — a **separate generated overlay** that `TechTree`
  **merges** at load (`mergeEffects`, the building-unlocks path), so it never clobbers the
  hand-authored `tech-effects.json` and doesn't collide on the classpath. `ResearchState.complete()`
  grants the `UNIT_*` token into `Settlement.getGrantedTechTokens()`, exactly as building tokens are
  granted. CAP-gated units are excluded from the overlay (engine drops the tech), same as buildings.
- **Which unit a band embodies — producer-determined (decisions 9/9a/9b).** The `unitId` is set at
  **production**: the settlement builds a unit of a role and *that* is what the caravan carries (the
  future build queue is the real driver). **Interim** (no queue), `pickBuildableUnit(role, colony)`
  filters `units.json` to units of `role` whose token is granted (unlocked) and whose `obsoleteTech`
  is **not** yet researched, and returns the best-available (ordered by the `ERA_*` subcombat tag —
  decision 12 — then tech depth) as the stand-in for a build choice; a per-role **default**
  (earliest-unlocked) is the fallback and the plumbing target. Crucially the
  choice is **not** ranked by `iCombat`: a band's fighting strength comes from **its people's skills**
  (`WARFARE` + the role signature), so the unit only needs to supply **identity, art, and `iMoves`**,
  and civilian units (0 `iCombat`) rank fine. `iCost` is stored but unused (no muster cost yet).

### 1a. Caravan realization — what "embodies a `UNIT_*`" means
A `MarchingCaravan` gains a `unitId` (the embodied `UNIT_*`) set at muster via the selection rule.
From it the band draws:
- **identity** — the unit's `name`/`pedia` become the band's display name and hover (today a band is
  a bare "Explorer"/"Settler" label);
- **march stats** — the unit's `iMoves` feeds the daylight-scaled move-point budget
  (`MarchConfig.baseMovePoints` is currently a flat 3.0; a band's base derives from its unit's
  `iMoves`, scaled — the seam is the explorer doc's Phase-3 move-point march);
- **art** — the unit's `button` icon on the map/inspector now; the in-world `.nif`/`.kfm` sprite
  (`EarlyArtDefineTag`) is a later nifbake refinement (the stat-depth decision deferred it), the same
  seam the route-nif art awaits.

`CaravanRole` already discriminates the concrete band classes (`SettlerCaravan`/`WorkerCaravan`/
`ExplorerCaravan`/`MilitaryCaravan`); the 5 new roles get concrete classes **only as their missions
are designed** (`TradeCaravan` is already forecast in `docs/caravan-trade.md`). Until then a new-role
unit still imports and shows in the tech tree — it just has no band class to muster yet (dormant,
exactly like a building with no economic effect).

### 2. Fetch: add the unit C2C sources
Add to `Civ4Files.FILE_MAP` (Java) and `web/civ4.mjs` (the button `.dds` are resolved from the path
already in `units.json`, so `civ4.mjs` needs only art, as with buildings):
- **`Assets/XML/Units/U_Land_CIV4UnitInfos.xml`**, **`.../U_Workers_CIV4UnitInfos.xml`**,
  **`.../CIV4UnitInfos.xml`** — the three core unit files.
- **`Assets/XML/Units/CIV4UnitCombatInfos.xml`** — the ~50 functional UnitCombat classes (decision
  11): the `<Combat>`→role/skill fold source, the category-icon `<Button>`s, the combat modifiers.
- **`Assets/XML/Art/CIV4ArtDefines_Unit.xml`** — the `artDefineTag → <Button>` map.
- **Unit GameText** — `Assets/XML/GameText/Units_CIV4GameText.xml` + `UnitHelp_CIV4GameText.xml`
  (`TXT_KEY_UNIT_*` name/pedia) **and the `TXT_KEY_UNITCOMBAT_*` strings** (unit-combat class names —
  same or a sibling GameText file). Same load pattern as the building GameText; de-`TXT_KEY_`-fallback
  on a miss (count `unresolvedName`).

All cached under `.civ4-cache/<ref>/` like everything else.

### 3. Unit → button art (verified)
A `UnitInfo` carries no direct button; art resolves through its **`<UnitMeshGroups>`**. The exporter
takes the unit's **first `<EarlyArtDefineTag>`** (e.g. `ART_DEF_UNIT_WORKER`; multi-mesh units like a
band list male/female/child — the first is representative), looks it up by `<Type>` in
`CIV4ArtDefines_Unit.xml`, and reads `<Button>` (`ART_DEF_UNIT_WORKER → Art/Interface/Buttons/Units/
sparth/worker.dds`). `artDefineTag` (the tag) and `button` (the path) both land on the row.

### 4. Web bake: `unit-icons.webp` (mirror `build-buildings.mjs`)
New **`web/build-units.mjs`**, structurally the building-icon baker (reuse `web/icon-bake.mjs`'s
`iconPath`/`iconCell`/`packSheet`): resolve each unit's `button` via `civ4.mjs resolveArt`, pack into
**`web/assets/units/unit-icons.webp`** (64² cells, 50 cols), colour-chip fallback on a missing/404
button (log the count). Emit **`unit-icons` meta** (per-unit icon rect keyed by id) into a
`units-meta.json` the server merges — following the **tech/building routing**, *not* the world-map
`WorldBundle` (the Phase-2 building gotcha: icons ride the tech infra, not the map bundle).

### 5. Server + web: the `/api/units` bundle and the tech-tree unit row
- **`UnitBundle` → `/api/units`** (parallel to `BuildingBundle`/`TechBundle`): merges `units.json`
  (engine jar) + `units-meta.json` (icon rects). Bump the reactor patch version (a new endpoint).
- **Tech-tree unit row** (`web/js/techtree.mjs`): the tree data joins tech → unlocked units via
  `units.json` + `prereqTech` (or the reverse index the exporter can stamp onto `techs.json`). Under
  each node, beside the building grid, draw a **row of unit icons** from `unit-icons.webp`, grouped by
  `caravanRole`, uniform frame/backing, the same continuous-LOD footer-bar → focused-grid treatment
  buildings use. Click a unit → the **rail inspector** (name, pedia, `caravanRole`, `iMoves`/`iCombat`
  /`iCost`, `obsoleteTech`, "unlocked by" the primary + AND techs, larger art). Any unit inspectable,
  locked or not. The unified top-bar search corpus gains **units** (a third kind chip beside tech +
  building); picking a unit jumps to its `prereqTech` node and inspects it. **Session-aware**: dim
  units the spectated colony (`ColonyView.knownTechs`) hasn't unlocked, same as buildings.

---

## Skill–role realignment (folded 2026-07-18, decisions locked with owner)

The 12 skills (`com.civstudio.skill.Skill`) are today **RimWorld's verbatim** — `CONSTRUCTION,
PLANTS, INTELLECTUAL, MINING, SHOOTING, MELEE, SOCIAL, ANIMALS, COOKING, MEDICINE, ARTISTIC,
CRAFTING`. They are **re-done to align with the 9 `CaravanRole`s**: each role gains one **signature
skill** that governs a band's effectiveness in that role (and that acting in the role trains — the
on-the-job-training seam). Two skills are **kept** because they carry live logic — `INTELLECTUAL`
(tech science, 2 refs) and `SOCIAL` (ennoblement / marriage / leader picks, 10 refs) — and `CRAFTING`
is **renamed `PRODUCTION`** (a general making skill, not tied to a role). The other nine RimWorld
labels are dormant (0–1 logic refs each), so the re-do is mostly a rename + repurpose.

**The new 12 (thematic names; clean re-index — decision below):**

| Index | Skill | Role it powers | Was |
| --- | --- | --- | --- |
| — | `STEWARDSHIP` | `SETTLER` (found & govern) | — (new) |
| — | `CONSTRUCTION` | `WORKER` (build routes/improvements) | `CONSTRUCTION` (name kept) |
| — | `SURVIVAL` | `EXPLORER` (scout, forage, subsist) | — (new) |
| — | `WARFARE` | `MILITARY` | `MELEE`/`SHOOTING` |
| — | `COMMERCE` | `TRADE` | — (new) |
| — | `FAITH` | `MISSIONARY` | — (new) |
| — | `HUNTING` | `HUNTER` | `ANIMALS` |
| — | `MEDICINE` | `HEALER` | `MEDICINE` (name kept) |
| — | `SUBTERFUGE` | `COVERT` | — (new) |
| — | `INTELLECTUAL` | *(non-role — science)* | `INTELLECTUAL` (**kept, load-bearing**) |
| — | `SOCIAL` | *(non-role — leadership/marriage)* | `SOCIAL` (**kept, load-bearing**) |
| — | `PRODUCTION` | *(general production)* | `CRAFTING` (**renamed**) |

Indices are left blank because the re-index is **clean** (below) — the enum's declared order fixes
them; the point is the *set* and the role mapping, not a slot-preserving rename.

**Decisions (locked with owner):**

| # | Question | Decision |
| --- | --- | --- |
| S1 | **Set & count** | Stay at **12** = 9 role signature skills + `INTELLECTUAL` + `SOCIAL` + `PRODUCTION`. Count unchanged, so the columnar store width and `Skill.ALL[12]` arrays are **untouched** (no structural resize). |
| S2 | **Naming** | **Thematic** (`WARFARE`, `COMMERCE`, `FAITH`, `SURVIVAL`, `STEWARDSHIP`, `SUBTERFUGE`, `HUNTING`…), not the raw role names. |
| S3 | **Re-index** | **Clean re-index** — assign fresh indices to the new declared order; accept that seed-reproducible **skill draws re-baseline** and old saves/replays don't carry over (byte-identical was already abandoned — [[project-direction]]). |
| S4 | **Firm → skill remap** | The two firms whose skill vanishes: **`NFirm`** (food/necessity, was `PLANTS`) → **`SURVIVAL`**; **`EFirm`** (enjoyment, was `ARTISTIC`+`CRAFTING`+`SOCIAL`) → **`PRODUCTION`** (+ `SOCIAL` retained). Unchanged: `BuilderFirm`→`CONSTRUCTION`, `CFirm`→`PRODUCTION` (was `CRAFTING`), `ScienceFirm`→`INTELLECTUAL`, `StrategicFirm`→`SOCIAL`. |
| S5 | **Advisor seats** | **Religion seat now matches `FAITH`** (was `null`/overall-ability); `Technology`→`INTELLECTUAL` and `Foreign`→`SOCIAL` unchanged; `Globe` stays skill-less. Update `AdvisorRole.matchSkill` + the frontend `ROLE_SKILL` map (add `religion: "faith"`). **No new seats** this pass. |
| S6 | **Character sheet** | **Text names, auto-update.** `advisor-detail.mjs` already renders skill names generically (`cap(s.skill)`) with level bars + passion flames; the new names flow through with **zero frontend code change** (beyond the `ROLE_SKILL` religion entry). No per-skill icons, no role-grouping this pass. |
| S7 | **Band ↔ skill link** | The caravan band inspector (§5) **shows the embodied unit's governing (signature) skill and the band leader's level in it** — e.g. "WARFARE (leader 14)" — tying the unit catalog, the caravan, and the skill system on the map. |

**The authoritative fold — `UnitCombat` class → signature `Skill`** (grounded in the real `<Combat>`
values the in-scope land units carry; decision 11 + the fold key of decision 5). This is what the
exporter stamps; `CaravanRole` derives alongside:

| Signature skill | `UNITCOMBAT_*` classes it absorbs | Role |
| --- | --- | --- |
| `WARFARE` | `MELEE`, `MOUNTED`, `SIEGE`, `GUN`, `ARCHER`, `THROWING`, `HERO`, **`CAPTAIN`** (military leader) + gated-out modern combat (`ROBOT`/`HITECH`/`DOOM`/`ASSAULT_MECH`/`DREADNOUGHT`/`MISSILE`/`ROCKET_LAUNCHER`/`STRIKE_TEAM`/`TRACKED`/`WHEELED`/`HELICOPTER`…) | `MILITARY` |
| `CONSTRUCTION` | `WORKER` | `WORKER` |
| `SURVIVAL` | `RECON` | `EXPLORER` |
| `HUNTING` | `HUNTER`, `ANIMAL` (subdued/tamed handling) | `HUNTER` |
| `MEDICINE` | `HEALTH_CARE` | `HEALER` |
| `FAITH` | `MISSIONARY` | `MISSIONARY` |
| `COMMERCE` | `TRADE` | `TRADE` |
| `PRODUCTION` | **`EXECUTIVE`** (C2C corporation execs — owner ruling) + the firm making-skill (CFirm/EFirm) | *(non-role)* |
| `SUBTERFUGE` | `SPY`, `CRIMINAL`, `RUFFIAN`, **`LAW_ENFORCEMENT`** (the whole crime↔order axis — owner ruling) | `COVERT` |
| `STEWARDSHIP` | `SETTLER`, **`ADMINISTRATOR`** (governance) | `SETTLER` |
| `SOCIAL` | `ENTERTAINER` | *(non-role — leadership/marriage)* |
| `INTELLECTUAL` | `PRODIGY` (genius/scholar) | *(non-role — science)* |

Owner rulings on the three judgment-call folds (2026-07-18): **`EXECUTIVE`→`PRODUCTION`** (business as
industry, not trade); **the crime↔order axis all→`SUBTERFUGE`** (a guard and a thief share the
streetcraft); **`CAPTAIN`→`WARFARE`, `ADMINISTRATOR`→`STEWARDSHIP`** (leadership split by domain).
The fold falls back to `<DefaultUnitAI>` only when `<Combat>` is absent/ambiguous.

**Runtime meaning.** A `CaravanRole` → signature-`Skill` table (the inverse of the mapping above)
lives beside the `UnitAI` → `CaravanRole` fold. When the caravan realization (Phase 5) picks the unit
a band embodies, the band's role effectiveness (forage yield, trade profit, combat, build speed…)
scales with its leader/members' level in that role's signature skill, and acting in the role trains
it (the existing on-the-job-training path, re-pointed at the signature skill).

**Migration scope (what the re-do actually touches).** `Skill.java` (the enum — rename + reorder);
the **firm skill sets** (`NFirm`, `EFirm`, `CFirm` — S4); **`AdvisorRole.matchSkill`** + the frontend
`ROLE_SKILL` map (S5); the `SOCIAL`/`INTELLECTUAL` call sites keep working unchanged (names kept);
the server person projections (`PersonDetail`/`PersonProjections`) and MCP tools surface skills **by
name generically**, so they auto-update (`PersonProjections.skills()` iterates `Skill.all()` and
emits `s.name().toLowerCase()` — the only bare skill strings anywhere are the frontend `ROLE_SKILL`
map, an unavoidable JS↔Java boundary that can't import the enum); `advisor-detail.mjs` auto-updates
(S6). **No** array-width change (count stays 12). **Re-baseline expected** on the skill RNG stream
(S3), so land it as its own commit with the smoke tests re-blessed.

**Tests the re-do touches (Phase 0, shipped 2026-07-18).** Two kinds. (1) **Compile-breaking** —
three test files name now-deleted skill constants and must be repointed to survivors:
`SkillSystemTest` (`PLANTS`/`MINING`/`COOKING`/`CRAFTING` → `SURVIVAL`/`HUNTING`/`MEDICINE`/
`PRODUCTION`, plus the tie-break index comments 2/11 → 9/11), `LaborMarketTest` (`PLANTS` →
`SURVIVAL`; the `ARTISTIC`+`CRAFTING`+`SOCIAL` three-skill-mean case → `COMMERCE`+`PRODUCTION`+
`SOCIAL`), `LaborTrainsSkillsTest` (`PLANTS` → `SURVIVAL`). (2) **Re-blessed for the RNG re-baseline**
— the clean re-index *permutes each person's per-skill birth draws* (the enum-order loop in
`Demography.newSkillRecords` assigns the same draw sequence to different skills), which flipped two
**seed-fragile statistical integration tests** that were riding a thin margin: `LaborTrainsSkillsTest`
(necessity-trained skill vs an untrained one — the population mean was always confounded by per-skill
birth draws and diluted by unemployed/churned laborers; re-blessed to an **all-necessity, small
fully-employed labor force on a wide reserve** so the training signal dominates) and
`BirthsTest.marriedHouseholdsBearChildren` (the old tight config collapsed to lone unmarried heads
under the re-baselined economy — same stabilizing recipe applied to `runFertilePoolColony`). Neither
mechanism changed; only the seed exposure did. **No test became obsolete** — nothing was removed.

---

## Phasing (import + tech-view land behavior-neutral; caravan wiring last)

**Sequencing (owner, 2026-07-18):** land **Phase 0 (skills) now** — it's self-contained and unblocks
the role work; the **unit import (Phases 1–5) comes later**, after the near-term roadmap (taxation →
caravan trade, [[project-direction]]). Phases 1–4 are pure data/UI (dormant); Phase 5 (the
producer-determined caravan realization) is the behaviour-changing capstone and couples to whatever
production/build-queue model the roadmap grows.

- **Phase 0 — skill–role realignment (independent; land first, own commit). SHIPPED 2026-07-18.**
  Re-did `Skill.java` (the new 12, clean re-index), remapped the firm skill sets (S4), pointed the
  Religion advisor seat at `FAITH` (S5) + the frontend `ROLE_SKILL` religion entry, and repointed /
  re-blessed the touched tests (see **Tests the re-do touches** above). Full stack green (engine 378 +
  server 109). Behaviour changes only in the seed of skill draws + the two firms' scaling skill; the
  character sheet + person feed auto-update. Independent of the unit import — a prerequisite only for
  the Phase-5 band↔skill link (S7).

Each phase is independently compilable/testable; the catalog is inert until the selection rule +
march wiring fire (Phase 5), exactly as the building import landed dormant through Phase 4.

- **Phase 1 — `UnitInfoExporter` → `units.json` + `unit-unlocks.json` + `unit-combats.json`. SHIPPED
  2026-07-18.** The gated import + the `<Combat>`→role/skill fold + the unlock overlay + the functional
  UnitCombat reference table (decision 11). **Real counts:** **273 units** in scope (264 `U_Land` + 9
  `U_Workers` + 0 `CIV4UnitInfos`; 24 non-land + 166 gated-out; 75 no-prereq linked to
  `TECH_SEDENTARY_LIFESTYLE`), 273 UNLOCK effects over 99 techs, **28 functional UnitCombat classes**.
  By role: MILITARY 129, MISSIONARY 55, COVERT 24, HUNTER 19, WORKER 11, HEALER/TRADE 10, SETTLER 8,
  EXPLORER 7. Worker `<Builds>` **captured now** (9 units carry a repertoire — the
  `docs/c2c-build-import.md` seam, folded forward). `TechTree` merges `unit-unlocks.json` on the same
  footing as building unlocks; `TechResearchTest` now asserts the research→`UNIT_*` token seam end to
  end (the Phase-4 verification, folded in). The `<Combat>`→`CaravanRole`→signature-`Skill` fold lives
  on `CaravanRole` (`fromUnit`/`signatureSkillOf`); non-role combat classes (EXECUTIVE/PRODIGY/
  ENTERTAINER) fold via `<DefaultUnitAI>` rather than a MILITARY default. **No behaviour change** (the
  catalog is dormant; unit tokens grant but nothing reads them). Add the unit files +
  `CIV4UnitCombatInfos.xml` + `CIV4ArtDefines_Unit.xml` + unit GameText to `Civ4Files.FILE_MAP`. Report the real in-scope count /
  per-role + per-combat-class histogram / unresolved-name + missing-button counts. **No behaviour
  change** (nothing loads the JSON yet; the unlock overlay grants tokens but nothing reads unit tokens).
- **Phase 2 — button-art bake. SHIPPED 2026-07-18.** `web/build-units.mjs` (the `build-buildings.mjs`
  sibling, reusing `web/icon-bake.mjs`) bakes **two** 64² sheets: `web/assets/units/unit-icons.webp`
  (**256/273** units, 17 atlas-only base-game buttons → colour-chip fallback, same as buildings) keyed
  into `units-meta.json`, **plus** `web/assets/units/unit-combat-icons.webp` (**28/28** UnitCombat
  `categories/*.dds` grouping icons, 0 missing) keyed into `unit-combats-meta.json`. Each meta is a flat
  `{id: {icon:[x,y,64,64]}}` the server (Phase 3 `UnitBundle`) merges onto its engine JSON —
  `units-meta.json`→`units.json`, `unit-combats-meta.json`→`unit-combats.json`. Dormant until the
  Phase-3 tech-tree row consumes them.
- **Phase 3 — `/api/units` + tech-tree unit row. SHIPPED 2026-07-18.** `UnitBundle` merges
  `units.json`+`units-meta.json` and `unit-combats.json`+`unit-combats-meta.json` into one pack
  (`{units, combats}`), served gzipped at `GET /api/units` (`AssetController`, `ResourceManifest`
  entry). In `techtree.mjs`: a second **role-spectrum bar** per node (`.tech-uspec`, above the
  building bar), a per-node **unit grid** in the rail grouped by `caravanRole` (reusing the building
  cell/group classes with a role colour), a **unit inspector** (`showUnitRail`: large art, role +
  `special`, move/combat/hammers/obsolete stats, the `<Combat>` class with its category icon +
  folded signature skill, pedia, unlocked-by), the unified search gaining a **"Unit" kind chip**
  (`panel.mjs` guards widened), and session-aware dimming via the shared `unitUnlocked` predicate.
  Reactor patch bumped **0.9.45→0.9.46**; trivia line added; verified headless
  (`tools/webverify/unit-verify.mjs` — 273 units + 28 combats load, grid/inspector/search all render,
  zero console errors). Server 109 green.
- **Phase 4 — engine unlock model. DONE (folded into Phases 1+3).** `TechTree.mergeEffects` already
  merges `unit-unlocks.json` on the default + race paths (Phase 1); `ResearchState.complete()` grants
  `UNIT_*` tokens with no engine change (dispatches on effect type); `TechResearchTest` asserts the
  research→`UNIT_*` token seam end-to-end. No separate `UnitResearchTest` needed. Runs stay clean.
- **Phase 5a — caravan realization, IDENTITY only (behaviour-neutral). SHIPPED 2026-07-18, Explorer
  only.** New engine `UnitCatalog` loads `/units.json` (the `TechEffects`/`TechTree` resource-load
  pattern, lenient) and `pickBest(role, grantedTokens, knownTechs)` picks the colony's best-available
  **non-obsolete unlocked** unit of a role (interim ordering by `iCost` as the era proxy, id tie-break;
  no RNG). `ExplorerCaravan` embodies its pick at muster; `MarchingCaravan` gains `embodiedUnit` +
  `getUnitId`/`getUnitName`/`signatureSkill`/`leaderSkillLevel`. The render snapshot (`CaravanView` +
  `Snapshots`) carries `unitId`/`unitName`/`unitIcon` (rect via `UnitBundle.iconRect`)/`signatureSkill`
  /`leaderSkill`; the live map (`live.mjs`) draws the embodied unit's **button icon as the band marker
  + a name and "Skill · level" readout** at Overland zoom (`BAND.PROVINCE`+), a role dot otherwise.
  **Behaviour-neutral** — deterministic, no RNG, no move-point/effectiveness change — so all smoke
  tests pass unchanged (engine 385, server 111). Covered by `UnitCatalogTest` (5), `ExplorerEmbodimentTest`
  (2), `UnitBundleTest` (2). *Verification note:* the Java pipeline (selection → embodiment → snapshot
  → icon lookup) is fully unit-tested; the on-screen embodied band was **not** driven headlessly — it
  needs a research-progressed colony mustering a levy that unlocks a non-obsolete explorer unit (a
  Classical-complete demo colony already obsoletes the earliest explorers, which `pickBest` correctly
  skips), which the caravan demo doesn't produce.
- **Phase 5b — the behaviour-changing part (flag-gated; NOT yet built).** `iMoves` → the move-point
  budget (owner: **anchor to the median unit** so typical bands keep today's reach, minimising the
  collapse-timing re-baseline) + role effectiveness scaling with the signature skill; the other four
  band classes (Settler/Worker/Military). Gate behind a default-off flag and re-bless the smoke tests.
  Buildable build-queue + combat stay **out of scope** (future).

Each phase: build → (web) `node web/build*.mjs` + refresh engine jar + `spring-boot:run` + webverify;
(engine) `mvn -pl civstudio-engine test`. Commit per phase (fold docs into the code commit).

## Design direction (forward-looking — the payoff the import enables)

Design suggestions captured with the owner (2026-07-18); not all are in-scope for Phases 0–5, but the
import is shaped so they're reachable without a re-bake.

- **The embodied unit is a *loadout*, not the band.** A caravan is **N `Member`s (each with the 12
  skills) + an embodied `UNIT_*`** that supplies identity, art, `iMoves`, and combat class. The people
  are the substance (they carry the fighting via `WARFARE` — decision 9a); the unit is equipment.
  **Upgrading a band = re-equipping** to a newer unit of the same role when tech unlocks it — no
  disband/re-muster. Scouts become rangers become explorers emergently.
- **Emergent colonial specialization — the thesis.** Acting in a role trains its signature skill, and
  the explorer **renewal loop** (returnees found households, become banked — `docs/explorer-caravan.md`)
  seeds households carrying that skill. So a colony that runs winter **hunting** levies drifts toward
  `HUNTING`; a trade-heavy one toward `COMMERCE`. The colony grows a *character* from what it does —
  this is the point of aligning skills to roles, beyond a data import.
- **`GROUP_*` → the canonical band-size ladder.** `UNITCOMBAT_GROUP_SOLO→PARTY→SQUAD→COMPANY→
  BATTALION→…→HORDE` is a size taxonomy; the march already taxes column length by size. Wire
  `bandSizeClass` (decision 12) into the move-overhead term so the imported tag drives live mechanics
  and labels the map ("a *company* of spearmen").
- **`ERA_*` as a spine.** Cross-check each unit's `ERA_*` tag against the eos tech-tree era of its
  `prereqTech` (a mismatch is a gate smell), and **group the tech-tree unit row by era** (more legible
  than by role alone).
- **Worker units are the colony's *beasts of burden*, not just improvement-builders.** The six
  animal-drawn workers (`WORK{ANIMAL,BUFFALO,MULE,ELEPHANT,LLAMA,CAMEL}`, all TECH_PLOUGH) pair with
  the `UNITCOMBAT_ASSISTED_*` / `MOUNT_*` subcombat tags — natural **pack/draft animals for caravans**:
  a band equipped with `WORKMULE` carries more `Cargo` / moves faster (the RimWorld load→speed factor,
  `docs/caravan-march.md`; the caravan-trade cargo). Workers aren't only builders — they're haulage.
- **The `WorkerCaravan` realizes the trail→road pioneering mechanic.** The Explorer lays `ROUTE_TRAIL`;
  the **Worker upgrades trails→roads and builds improvements** ([[route-trail-pioneering]] says this is
  "next"). The imported worker ladder gives it identity + a per-era build-rate progression
  (`GATHERER→WORKER→WORKANIMAL→…`). Its mission data is **`CIV4BuildInfos.xml`** — the verb
  layer, **drafted in [`docs/c2c-build-import.md`](c2c-build-import.md)** (wires worker `<Builds>` →
  already-baked routes/improvements), needed before `WorkerCaravan.arrive()` stops being a no-op.
- **`GATHERER` blends worker↔forage.** The earliest "worker" is a prehistoric forager (TECH_GATHERING),
  overlapping the `EXPLORER`/`HUNTER` forage loop — a colony's first `WorkerCaravan` is really a
  foraging band. A hint that the `WORKER`/`SURVIVAL`/`HUNTING` skills sit on a continuum at the
  prehistoric end.
- **UnitCombat `categories/*.dds` = ready-made skill icons.** Since skills map ~1:1 to functional
  combat classes, each signature skill has a thematically-matched icon via its representative class
  (`recons.dds`→`SURVIVAL`…) — a one-line reuse that closes the deferred skill-icon gap (S6) later.
- **`SUBTERFUGE` is a seam, not an orphan.** The crime↔order axis (`LAW_ENFORCEMENT`/`CRIMINAL`) are
  C2C **property-control** units (`PropertyManipulators` in the unit schema) — the hook for a future
  crime/order **property system**. Latent-but-intentional, like the effect-less imported buildings.
- **Classless roles are catalog-only.** 4 of the 5 new `CaravanRole`s have no band class yet (only
  `TRADE` is forecast). Mark them **catalog-only** so nobody assumes a `MissionaryCaravan` exists; a
  muster against them is a no-op until a mission is designed.

## Open questions / risks

- **New-role bands have no class yet.** `TRADE`/`MISSIONARY`/`HUNTER`/`HEALER`/`COVERT` units import
  and show in the tree, but only `TradeCaravan` is forecast (`docs/caravan-trade.md`); the rest await
  a mission design. Fine — dormant like an effect-less building.
- **`iMoves` → march calibration.** Wiring a unit's `iMoves` into `MarchConfig.baseMovePoints` (flat
  3.0 today, itself owner-tuned) will shift band reach; measure before turning Phase 5 on
  (`ExpeditionsPrinter` / the food-balance canaries).
- **Missing button art.** Some `<Button>` paths may 404 at the pinned ref → colour-chip fallback; log
  the count (as `build-techs.mjs` does).
- **Race units deferred.** U_Neanderthals (and, later, Anbennar race-specific units) want the per-race
  overlay seam, not this universal import — a future cut.
- **Great people & sea-AI fold.** Folding `GENERAL`/specialists and stray sea AIs to nearest loses
  their semantics; the raw `defaultUnitAI` on each row lets a later pass reclassify without re-baking.

## Key files

- New: `docs/c2c-unit-import.md` (this); `UnitInfoExporter` (+ `UnitCombatExporter` mode) + `Unit`
  (engine); `generated/units.json` + `generated/unit-unlocks.json` + `generated/unit-combats.json`;
  `web/build-units.mjs`; `web/assets/units/unit-icons.webp` + the UnitCombat `categories` icon set;
  `units-meta.json`; `UnitBundle` (`/api/units`); `UnitResearchTest`.
- Changed: `com.civstudio.agent.CaravanRole` (+5 roles + the `<Combat>`→`CaravanRole`→signature-`Skill`
  fold table); `com.civstudio.data.Civ4Files` + `web/civ4.mjs` (unit files
  + `CIV4ArtDefines_Unit.xml` + unit GameText); `TechTree` (merge `unit-unlocks.json`); `ResearchState`
  (grant `UNIT_*` tokens); `web/js/techtree.mjs` (per-node unit row + inspector + search);
  `MarchingCaravan` (Phase 5 `unitId` + identity/stat draw + band↔skill link).
- Changed (Phase 0 — skill realignment): `com.civstudio.skill.Skill` (the new 12, clean re-index);
  `NFirm`/`EFirm`/`CFirm` (firm skill remap, S4) + `Firm`/`LaborMarket` skill-doc comments;
  `AdvisorRole.matchSkill` (Religion→`FAITH`, S5); `web/js/advisor-detail.mjs` `ROLE_SKILL` map (add
  `religion: "faith"`; names auto-update). Tests: `SkillSystemTest` / `LaborMarketTest` /
  `LaborTrainsSkillsTest` repointed off deleted skill constants; `LaborTrainsSkillsTest` +
  `BirthsTest` re-blessed (stable-colony configs) for the RNG re-baseline.
- Reuse: `Civ4Files`/`civ4.mjs` fetch+cache, `BuildingInfoExporter` kept-tech computation + gate +
  unlock-overlay pattern, `Civ4Xml` DOM helpers, `web/icon-bake.mjs`, the `TechBundle`/`BuildingBundle`
  server pattern, `TechEffect.Unlock` / `mergeEffects`, `CaravanRole` / `MarchingCaravan`.

*Planned 2026-07-18, decisions locked with owner (five rounds). Sibling to `c2c-building-import.md`;
when Phase 1 lands, add a one-line pointer in `CLAUDE.md` (units → caravan) and cross-link here.*
