# Design note: the tech tree (research, effects, social gating)

**Status:** phases 1–3 implemented (the `eos.tech` graph + queries; the `TechEffect`
schema + per-sector multiplier hook; and live research — a dedicated ruler-funded
`ScienceFirm` produces the research points, monthly ruler selection with RP buffering,
completion applying effects, always on for export colonies, founding in the
`GameSession`'s `Era` (Medieval by default — knowing up to Classical and warm-started
90% through the Medieval entry tech), per-era research cost via `EraModifiers.researchPercent`,
and the whole tree persisting across a band's abandonment/re-founding). The era ladder
is now a **single `era.com.civstudio.Era`** (the former `eos.tech.Era` was merged in). Because the
shipped effect overlay is still **empty**,
completed techs carry no effects yet, so a standard run advances research but its only
economic footprint is the science firm (slot + ruler-funded scholar wages) until the
overlay is populated (a coverage pass) — that authoring, plus wiring
`UNLOCK`/`SOCIAL_GATE` to real consumers, is phase 4.
**Date:** 2026-06-18
**Depends on:** the imported `src/main/resources/techs.json` (a Caveman2Cosmos /
Civ4 tech graph), the firm production functions (`ConsumerGoodFirm`, `CFirm`,
`StrategicFirm` — all `A·L^β·K^(1-β)`), the existing static per-sector tech
coefficient (`SimulationHarness.NECESSITY_TECH_FACTOR`, which this generalizes),
the strategic export sector and its INTELLECTUAL-driven labor (`docs/` notes +
`LaborMarket`), the ruler's monthly review loop (`Ruler.reviewSectors`), the shared
session services pattern (`GameSession` owns `NameRegistry` / `Demography` /
`LiturgicalCalendar` / `SlotTable`, all Jackson-loaded), `SimulationConfig`, and —
as downstream consumers of the "unlock / gate" effects — the rank ladder
(`docs/rank-ladder.md`) and `SocialClass` (`docs/social-class.md`).

## The imported data

`techs.json` is a Civ4-mod tech graph: **366 techs** across 6 eras (Prehistoric 99,
Ancient 94, Classical 59, Medieval 54, Renaissance 59, Industrial 1 — the lone
Industrial node is a truncation boundary; the tree effectively ends at Renaissance).
Each tech carries:

- `Type` (id, e.g. `TECH_MERCANTILISM`), `Era`, `Advisor` (one of 6 categories:
  Military, Economy, Growth, Culture, Religion, Science), `iCost` (research cost,
  1 in Prehistoric rising to ~4000 in late Renaissance);
- `OrPreReqs` (need **any one** of the listed `PrereqTech`) and often `AndPreReqs`
  (need **all** of them) — standard Civ4 prerequisite semantics;
- `iGridX`/`iGridY` (tree-layout coordinates, display only) and `Flavors` (AI
  weighting);
- a long tail of **Civ4-specific effect flags** (`iWorkerSpeedModifier`,
  `bIrrigation`, `bBridgeBuilding`, `FirstFreeUnit`, combat `DomainType`,
  `bEmbassyTrading`, …) and localization/asset keys (`Description`, `Quote`,
  `Sound`, `Button`) — **none of which map to anything in eos** (no units, terrain
  improvements, or combat). These are ignored.

So we reuse the **graph** (nodes, eras, costs, prereqs, categories) and **discard the
effects**, supplying eos-native effects through a separate overlay (below). The
imported file stays pristine — it is read-only reference data.

## Decided behaviour (from the design questions)

1. **Tech does three things:** (a) **boosts sector productivity** (raises a firm
   sector's Cobb-Douglas `A`), (b) **unlocks content** (new goods / firm types /
   buildings), (c) **gates social/political progression** (rank rungs, social
   classes, features). The effect schema must express all three.
2. **Research is fueled by INTELLECTUAL labor** — supplied by the ruler and nobles
   (the scholarly aristocracy), via a dedicated science firm (see below). It is
   **always on** for an export colony (no enable flag).
3. **The founding era lives on the `GameSession`** (`era.com.civstudio.Era`, default `MEDIEVAL`
   — so all simulations start Medieval). A colony founding in era E knows every tech up
   to `E.below()` for free and researches E's frontier, founding 90% through E's entry
   tech (`TECH_E_LIFESTYLE`). At the `MEDIEVAL` default that's Prehistoric→Classical
   pre-known, warm-started 90% into `TECH_MEDIEVAL_LIFESTYLE`. The tech tree's modeled
   ceiling is the Renaissance, so the lone Industrial node (and later eras) are dropped
   at load.
4. **One research focus at a time, ruler-chosen.** A single colony research pool
   accumulates toward the current focus's `iCost`; on completion the ruler picks the
   next researchable tech at its monthly review. Research points are **never wasted** —
   any produced while there is no focus (or beyond a focus's cost) buffer and carry to
   the next focus.
4b. **The tech tree persists across abandonment.** When a colony is abandoned its
   research (known set, researched techs, focus, buffered progress) is carried out by
   the wandering band and restored onto the colony it re-founds (see
   {@code docs/caravan.md}).
5. **Advisor → firm sector** mapping drives the default productivity effect:

   | Advisor | eos sector |
   | --- | --- |
   | Growth | Necessity |
   | Economy | Capital |
   | Science | Export (strategic) |
   | Culture | Enjoyment |
   | Religion | Enjoyment |
   | Military | *deferred — no sector yet (inert until warfare exists)* |

6. **Effect schema is designed first** (this note); how many techs get hand-authored
   effects vs. a formulaic default is a *later coverage decision* the schema must
   leave open.

## The effect schema (the centerpiece)

A tech's eos meaning is a list of `TechEffect`s, authored in a **separate overlay
file** keyed by tech `Type` — `tech-effects.json` — so `techs.json` stays an
untouched import and only techs that *have* an eos effect need an entry. Three effect
kinds cover the three decided roles:

### 1. `SECTOR_PRODUCTIVITY` — the bread-and-butter effect

Multiplies a firm sector's total-factor productivity `A`. This is the runtime
generalization of today's static `NECESSITY_TECH_FACTOR` (a fixed 2.0 baked into the
necessity firms' `A` at founding): instead of one constant set once, the colony keeps
a **live per-sector multiplier** that completed techs raise.

```jsonc
{ "kind": "SECTOR_PRODUCTIVITY", "sector": "CAPITAL", "factor": 1.05 }  // +5% to capital A
```

- `sector` ∈ { `NECESSITY`, `ENJOYMENT`, `CAPITAL`, `EXPORT` } (the advisor map
  above gives each tech a *default* sector; an authored effect may override it).
- `factor` is multiplicative and **cumulative** — the colony tracks
  `techMultiplier[sector]` (all starting at 1.0), and each completed
  `SECTOR_PRODUCTIVITY` effect multiplies it in.
- **Hook:** firms multiply it into `A` at the point of production —
  `config.A() * colony.techMultiplier(sector)` in `ConsumerGoodFirm.getOutput` /
  `CFirm` / `StrategicFirm`. Default 1.0 ⇒ **byte-identical until a tech completes**.
  (Today's `NECESSITY_TECH_FACTOR` either folds into the necessity sector's starting
  multiplier or stays the founding `A` baseline — decided at implementation.)

### 2. `UNLOCK` — enable new content

```jsonc
{ "kind": "UNLOCK", "target": "GOOD_PAPER" }
{ "kind": "UNLOCK", "target": "FIRM_BANKING_HOUSE" }
```

Enables a content element that does not exist (or is disabled) before the tech: a new
good, a new firm/sector type, a building on a special site, a new market. Targets are
**eos-native ids**, not Civ4's. Most unlock targets are **forward-looking** — the
first cut wires few or none (eos has a fixed good set today), but the schema reserves
the kind so a content tech (e.g. `TECH_CORPORATION` → a joint-stock firm,
`TECH_STOCK_BROKERING` → a second bank tier) slots in without a schema change.

### 3. `SOCIAL_GATE` — enable a social/political capability

```jsonc
{ "kind": "SOCIAL_GATE", "capability": "CLASS_BURGHER" }
{ "kind": "SOCIAL_GATE", "capability": "RANK_CITY" }
{ "kind": "SOCIAL_GATE", "capability": "FEATURE_CARAVAN_TRADE" }
```

Sets a colony capability flag that the rank ladder / `SocialClass` / feature code
reads as a precondition. This is the seam tying tech to the other two design notes:
e.g. a colony cannot raise a **`BURGHER`** class (`docs/social-class.md`) or promote
to **`CITY`** rank (`docs/rank-ladder.md`, `docs/city-and-league.md`) until the
gating tech is researched. Like `UNLOCK`, mostly forward-looking; the schema reserves
it so those systems gain a tech precondition without rework.

A tech may carry **several** effects (e.g. `TECH_MERCANTILISM` → +Export productivity
**and** unlock a trade building). The default, for any Renaissance tech with **no**
overlay entry, is a single small `SECTOR_PRODUCTIVITY` on its advisor-mapped sector
(Military techs map to nothing, so they default to **inert**) — which is what makes a
purely-formulaic coverage strategy possible later without authoring 59 entries by
hand.

## Architecture mapping

### `TechTree` — the static graph (shared, on `GameSession`)

The parsed `techs.json` plus the `tech-effects.json` overlay, loaded once via Jackson
(already the project's one runtime dep, used by `NameRegistry`). Immutable; holds the
nodes, prereq edges, costs, eras, advisor→sector map, and each tech's resolved
`List<TechEffect>`. Lives on `GameSession` beside the other shared services, since the
graph is identical across colonies in a session. Eras are the single `era.com.civstudio.Era`
ladder (the former `eos.tech.Era` merged in): `TechTree` maps each row via
`Era.fromTechKey` and drops anything past its `MAX_TECH_ERA = Era.RENAISSANCE` ceiling
(the lone Industrial node and later eras), and exposes the era partition so "pre-known
up to the start era" is a simple `isAtOrBefore` predicate. The `GameSession` also owns
the founding `Era` itself (default `MEDIEVAL`), from which `ResearchState`'s baseline
and warm-start derive.

### `ResearchState` — per-colony progress

Each colony keeps its own research progress (colonies research independently, like
they bank and age independently):

- the **known set** — seeded at founding with every Prehistoric→Classical tech;
- the **completed set** — the techs it researched itself (a subset of known, beyond
  the pre-known baseline), so a re-founded colony can re-apply their effects;
- the **current focus** (a Medieval-then-Renaissance tech, warm-started at the Medieval
  entry) and **buffered research points** toward its scaled cost;
- the per-sector **`techMultiplier`** and the set of granted capability/unlock tokens
  live on the `Settlement` (raised via `applyTechEffect`).

A tech is **researchable** when it is unknown and its prereqs are satisfied (all
`AndPreReqs` known **and** at least one `OrPreReqs` known). Because all
pre-Renaissance techs are pre-known, Renaissance nodes whose prereqs lie in earlier
eras are immediately available; intra-Renaissance prereqs order the rest.

### Research production — the science firm

Research points (RP) are produced by a dedicated **`ScienceFirm`** — a labor-only firm
modeled on `BuilderFirm`/`StrategicFirm`. It hires on its own **`ScholarLabor`**
market and converts the scholarly labor it gets into RP by `A · L^β` (a
`ScienceConfig`), delivering them to the colony's `ResearchState` each step (no money
moves for the RP itself). The firm sells nothing, so research is a **crown-funded
public good**: its wage budget is funded each step out of the **ruler's treasury**,
and those wages flow to the scholars when the market clears — a near zero-profit
conduit (ruler → firm → scholars), exactly as the builder is for its peasants. A
colony with no ruler funds no scholars, so research never advances.

- **Who staffs it (the scholars).** The same **aristocracy**: nobles post their
  INTELLECTUAL labor to `ScholarLabor` (and the ruler does too during the early
  ennoblement ramp, so research is staffed before the nobles are raised — mirroring how
  the ruler works the export firm meanwhile). Because `ScholarLabor` is a *separate*
  market from the export `NobleLabor`, a noble supplies full labor to **both** — a
  scholar-merchant doing double duty — so adding research leaves export output
  unchanged rather than competing with it.
- **Relation to the export sector.** Research no longer rides on the strategic firm at
  all (an earlier cut accrued RP from export labor; that was removed). The strategic
  firm is pure export again; the `ScienceFirm` is the single research source.
- **Pacing.** RP yield is the science firm's `A · L^β`; with ~5 nobles + the ruler at
  INTELLECTUAL≈5–15 a default colony completes its first Renaissance tech (cost ~2,200)
  well before collapse. The `ScienceConfig` curve and `SimulationConfig.researchCostScale`
  (a cost multiplier) tune the pace.

### Research selection — the ruler picks

Folds into the ruler's existing monthly review (`Ruler.reviewSectors` already
charters/dissolves firms; research selection is the same kind of sovereign decision).
Default heuristic: **lowest `iCost` among researchable** (deterministic frontier
advance). A natural richer heuristic reuses the demand-pressure signal the dynamic
provisioning already reads (`ConsumerGoodMarket.getUnmetPressure`) — pursue the tech
whose sector is most demand-pressured — and the `Flavors` weights are available as a
third option. Default cheapest; the heuristic is a knob.

### Integration points (minimal, additive)

| Concern | Hook | Neutral until… |
| --- | --- | --- |
| Productivity | `config.A() * colony.techMultiplier(sector)` at each produce site | a `SECTOR_PRODUCTIVITY` tech completes (mult = 1.0 before) |
| Research yield | a dedicated `ScienceFirm` produces RP from scholar labor, funded by the ruler | adds the firm's slot + ruler-funded wages (no RP-side money move) |
| Selection | a branch in the ruler's monthly review | no focus ⇒ no-op |
| Unlock / gate | colony capability flags read by content / ladder / class code | no gating tech researched ⇒ flags unset, today's behaviour |

A run with research **disabled** (`researchEnabled = false`, or any colony without a
strategic sector) is byte-identical. With research **enabled** the productivity /
unlock / gate hooks stay neutral until a completed tech carries an effect (the overlay
is empty today), but the `ScienceFirm` itself is a real agent — it occupies a slot and
draws ruler-funded scholar wages — so an enabled run is *not* byte-identical, only
economically near-neutral (the existing smoke suite stays green).

## Accepted limitations (out of scope for this cut)

1. **Effects are mostly productivity.** `UNLOCK` and `SOCIAL_GATE` are schema-complete
   but wire to little real content yet (eos has a fixed good set; `BURGHER`/`CITY` are
   themselves proposed-only). They are the seam, not a promise the content exists.
2. **Military techs are inert.** With no warfare/units in eos, Military-advisor techs
   default to no effect. They remain in the graph (prereqs may route through them) but
   do nothing until a military model exists.
3. **One global research focus per colony.** No parallel research, no per-sector
   research budgets; matches the "one focus, ruler-picks" decision.
4. **Pacing is uncalibrated.** Per the caveat, the RP→iCost relationship needs a
   sweep; the first cut ships a knob and a placeholder rate, not a tuned economy.
5. **Pre-Renaissance tree is collapsed to "known."** The ~307 earlier techs are a flat
   pre-known set, not an in-run ladder — their individual effects are folded into the
   colony's founding baseline (today's `NECESSITY_TECH_FACTOR` etc.), not replayed.

## Phased implementation plan

- **Phase 1 — load the graph + research nothing. (Implemented.)** The `eos.tech`
  package: `Era` (the five in-scope eras, chronologically ordered) and `Advisor`
  enums, the `Tech` record (type / era / advisor / cost / or-prereqs / and-prereqs —
  all Civ4 effect and asset fields discarded), and `TechTree.load()` (parses
  `/techs.json`, drops the out-of-scope Industrial node by era, and fail-fast
  validates that every prerequisite resolves). `GameSession.getTechTree()` exposes it,
  loaded **lazily** so tech-less runs and tests pay no parse cost — nothing reads it,
  so the full suite stays green and behaviour is unchanged. The graph queries
  (`preKnownThrough(era)`, `prereqsSatisfied(tech, known)`, `researchableFrontier(known)`)
  land here as pure functions of a known set. `tech.com.civstudio.TechTreeTest` covers graph
  integrity: 365 kept techs, the era partition counts (99/94/59/54/59), every prereq
  resolves, and a Medieval-complete start's frontier is exactly the single Renaissance
  entry tech (`TECH_RENAISSANCE_LIFESTYLE`).
- **Phase 2 — the effect schema + overlay. (Implemented.)** `TechEffect` is a
  sealed interface with three records — `SectorProductivity(sector, factor)`,
  `Unlock(target)`, `SocialGate(capability)` — Jackson-polymorphic on a `"kind"`
  discriminator; `Sector` is `{NECESSITY, ENJOYMENT, CAPITAL, EXPORT}` (the builder
  has none). `TechEffects` loads the overlay (`/tech-effects.json`, **shipped empty**
  — schema and plumbing without coverage) keyed by tech id; `TechTree` merges it and
  validates every key resolves, exposing `effectsOf(type)`. The colony holds a
  per-`Sector` `techMultiplier` (default 1.0) with `getTechMultiplier` /
  `applyTechEffect` (a `SectorProductivity` multiplies its sector cumulatively;
  `Unlock`/`SocialGate` record a granted token, read by nothing yet). Each production
  firm declares `sector()`; `ConsumerGoodFirm` / `CFirm` / `StrategicFirm` read an
  **effective A** = `config.A() · colony.getTechMultiplier(sector())` at every
  A-use site (output **and** marginal cost). `NECESSITY_TECH_FACTOR` is left as the
  founding A baseline with the multiplier riding on top — so at the default 1.0
  multiplier production is byte-identical and the full smoke suite stays green.
  Covered by `tech.com.civstudio.TechEffectTest` (schema + overlay parsing) and
  `simulation.com.civstudio.TechProductivityTest` (a directly-applied effect scales exactly its
  sector's firm output, cumulatively, leaving other sectors untouched).
- **Phase 3 — research production + completion + ruler selection. (Implemented.)**
  A per-colony `tech.com.civstudio.ResearchState` (on `Settlement`, via `getResearch()`/
  `setResearch`). The baseline and warm-start derive from the `GameSession`'s `Era`
  (default `MEDIEVAL`): pre-known = `preKnownThrough(era.below())` (Classical), warm-start
  = `seedInitialFocus("TECH_" + era + "_LIFESTYLE", 0.9)` (90% through the Medieval entry
  tech). A focus's cost is scaled by its era's `EraModifiers.researchPercent` (Medieval
  250%, Renaissance 300% — research grows costlier as eras advance) times
  `researchCostScale`. A dedicated **`ScienceFirm`** (labor-only, on
  its own **`ScholarLabor`** market, staffed by the nobles + ruler, funded each step
  from the ruler's treasury) produces RP by `A · L^β` and calls `research.accrue(rp)`
  each step. RP **buffer** in `progress` (even with no focus, and carrying the overflow
  past a completion, so none is wasted) and advance a single focus the ruler picks
  **monthly** (cheapest researchable, in `Ruler.act`'s first-of-month block); on
  reaching the focus's `cost · researchCostScale` the tech **completes**,
  `colony.applyTechEffect` runs each of its overlay effects, the tech is added to a
  `completed` set, and the colony goes focus-less until the next monthly pick. Research
  is created unconditionally in `createDefaultStrategicSector` (no enable flag); a
  `ResearchPrinter` (`Research.csv`: focus, progress, cost, RP/step, completed/known
  counts, per-sector multipliers) charts it. The RP yield is set by `ScienceConfig`
  (`A·L^β`); `researchCostScale` (default 1.0) tunes pace. **Persistence:**
  `ResearchState.snapshot()`/`restore()` carry the whole tree (known, completed, focus,
  buffered progress) onto a wandering `Caravan` on abandonment and back onto the
  re-founded colony (`reFoundStandardColony`), re-applying the researched techs' effects
  so productivity is recovered. Covered by `tech.com.civstudio.ResearchStateTest` (pick / buffer
  / warm-start / complete-with-overflow / snapshot-restore, deterministic via the
  sample overlay) and `simulation.com.civstudio.TechResearchTest` (a standard run completes ≥1
  tech before collapse). **Note:** with the overlay empty those completions apply no
  effects, so a standard run *advances* research (counters, `Research.csv`) but its only
  economic footprint is the science firm (slot + ruler-funded scholar wages); the
  productivity divergence proper arrives with overlay coverage (phase 4).
- **Phase 4 — wire `UNLOCK` / `SOCIAL_GATE` to real consumers (future, separate
  notes).** As `BURGHER`, `CITY`, caravan trade, and any new goods/firms land, give
  them tech preconditions through the gate flags. No schema change expected.

## Open questions deferred to later

- The **RP formula** — linear in INTELLECTUAL-scaled export labor, or also scaled by
  colony size / a Science-sector research multiplier (the "Science → research rate"
  option that was *not* chosen for the sector map could re-enter here as a
  research-on-research compounding effect).
- Whether the **pre-known baseline** should literally fold the earlier eras' effects
  into starting multipliers, or just start every multiplier at 1.0 and treat history
  as flavor (the first cut leans to the latter for simplicity).
- Whether research selection stays with the **ruler** or becomes a reserved
  **player** decision once the playable seat exists (mirrors the same open question in
  `docs/rank-ladder.md`).
- Whether **`SECTOR_PRODUCTIVITY` factors compound multiplicatively without bound**
  or need a per-sector cap / diminishing returns to stay calibration-safe.
