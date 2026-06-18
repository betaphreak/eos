# Design note: the tech tree (research, effects, social gating)

**Status:** phase 1 implemented (the `eos.tech` graph + queries, lazily on
`GameSession`, read by nothing yet); phases 2–4 are future work
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
2. **Research is fueled by INTELLECTUAL / export output** — the intellectual labor
   the ruler and nobles already supply to the strategic export sector.
3. **Scope is up to Renaissance.** The colony **begins knowing every
   Prehistoric→Medieval tech** (free historical baseline, fitting the 1444 start)
   and researches only the **~59 Renaissance** nodes live. The lone Industrial node
   is dropped.
4. **One research focus at a time, ruler-chosen.** A single colony research pool
   accumulates toward the current focus's `iCost`; on completion the ruler auto-picks
   the next researchable tech.
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
graph is identical across colonies in a session. Filters out the Industrial node and
exposes the era partition so "pre-known up to Medieval" is a simple era predicate.

### `ResearchState` — per-colony progress

Each colony keeps its own research progress (colonies research independently, like
they bank and age independently):

- the **known set** — seeded at founding with every Prehistoric→Medieval tech;
- the **current focus** (a single Renaissance tech) and **accumulated research
  points** toward its `iCost`;
- the per-sector **`techMultiplier`** and the set of granted **capabilities/unlocks**.

A tech is **researchable** when it is unknown and its prereqs are satisfied (all
`AndPreReqs` known **and** at least one `OrPreReqs` known). Because all
pre-Renaissance techs are pre-known, Renaissance nodes whose prereqs lie in earlier
eras are immediately available; intra-Renaissance prereqs order the rest.

### Research production — where the points come from

Research points (RP) are a **side yield of intellectual labor**, computed from the
same INTELLECTUAL-scaled output the strategic sector's workers (ruler + nobles)
already deliver to the noble-only labor market — *without moving any money*. So the
export-earnings flow is untouched and the economic random stream is unperturbed; RP
is a new, purely-additive quantity. Each step the colony's RP accrue to its current
focus; when `accumulated ≥ focus.iCost`, the tech completes, its effects apply, and
the ruler picks the next focus.

- **Alternative considered:** splitting the strategic firm's output *between* export
  earnings and RP (research competes with trade income). Rejected for the first cut —
  it perturbs the calibrated export/treasury flow; the additive byproduct is cleaner.
- **Pacing caveat (calibration).** Renaissance `iCost`s run to the thousands while a
  handful of INTELLECTUAL≈5–15 workers deliver only a few productivity-units/step, and
  a colony lives ~5–7 years (~2000 steps) before the pool drains. So a colony will
  realistically complete only **a few** techs in its life — plausibly fine (tech is a
  slow background force), but a `researchCostScale` knob on `SimulationConfig` should
  let runs tune the pace. Flag, don't pre-calibrate.

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
| Research yield | new RP accrual when the noble-labor market clears | always additive (no money moved) |
| Selection | a branch in the ruler's monthly review | no focus ⇒ no-op |
| Unlock / gate | colony capability flags read by content / ladder / class code | no gating tech researched ⇒ flags unset, today's behaviour |

Every hook defaults to the current behaviour, so a run with research disabled (or
before the first completion) stays **byte-identical**.

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
  land here as pure functions of a known set. `eos.tech.TechTreeTest` covers graph
  integrity: 365 kept techs, the era partition counts (99/94/59/54/59), every prereq
  resolves, and a Medieval-complete start's frontier is exactly the single Renaissance
  entry tech (`TECH_RENAISSANCE_LIFESTYLE`).
- **Phase 2 — the effect schema + overlay.** `TechEffect` (the three kinds) and the
  `tech-effects.json` loader; the per-colony `techMultiplier` and the
  `config.A() * multiplier` production hook (with `NECESSITY_TECH_FACTOR` reconciled).
  Still inert at runtime (no research accrues), so byte-identical; tested by applying
  an effect directly and asserting a sector's output scales.
- **Phase 3 — research production + completion + ruler selection.** RP accrual from
  intellectual labor, focus accumulation/completion applying effects, ruler's
  cheapest-researchable pick in the monthly review, a `researchCostScale` knob, and a
  `ResearchPrinter` (`Research.csv`: focus, progress, completed count, per-sector
  multipliers). This is where a standard run first diverges — covered by a smoke test
  that a default colony completes ≥1 tech before collapse and that the affected
  sector's productivity rises.
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
