# Design note: births and children

**Status:** Phases 1, 2 and 4 implemented (births on by default + parentage + the
ChildrenFirm). **Phase 3 (calibration → stable colony) attempted and found BLOCKED:**
births cannot prevent the colony's structural ~6–10-year food-balance collapse, which
precedes the 15-year child-maturation window, so the collapse tests are **not** flipped
— see *Phased implementation plan → Phase 3*. **Phase 5 (abandonment / age-gating the
pool) pending.**
**Date:** 2026-06-28
**Depends on:** the `Person`/`Member` split (`com.civstudio.agent.Member`), the
per-member `AbstractHousehold.checkOldAgeDeath()` loop (which already promotes the
next surviving member to head), the marriage feature (`WeddingMarket`, which seats a
spouse as a second member), and the per-member life-table mortality
(`Member.rollOldAgeDeath`).

## Motivation

The colony's labour force is a **replacement-only ratchet**: a dead laborer is
replaced one-for-one by promotion from the `Retinue`, and the pool only ever
*drains* (founding consumes most of it; nothing refills the standing reserve). Pool
immigration, lenient mortality and a deep larder push collapse out to ~15–30 years,
but a `CalibrationSweep` confirms **no parameter combination yields a stable ruler
colony** — the workforce cannot renew itself, so every ruler-bearing colony
eventually collapses (this is accepted in `CLAUDE.md` as the status quo).

This note adds the durable fix the rest of the model has been deferring to:
**households bear children, and those children grow into workers**. A married
household produces offspring who eat, age on the life table, and — at working age —
join the labour market and can in time **inherit the headship**. That makes a
household **self-perpetuating from its own line** rather than dependent on pool
promotion, breaking the ratchet at its root.

The key structural lever already exists: `AbstractHousehold.checkOldAgeDeath()`
**promotes the next surviving member to head** when the head dies. With children
present, a grown child becomes the new head — so the renewal mechanism is mostly a
consequence of seating children as members and letting the existing machinery run.

## The model (decided behaviour)

- **Children are born into the household as members.** A birth appends a newborn
  `Member` (age 0) to the household; it is **not** a new household and founds no
  dynasty of its own. Households therefore grow past today's size-2 (head + spouse)
  cap.
- **Children eat `RationSize.SNACK`** (0.1 necessity/day) and **do not work or earn**
  while they are children. They improve their skills only by attending the
  **`ChildrenFirm`** — a capacity-limited civic school (see *The ChildrenFirm
  (schooling)* below); a child with no place trains nothing.
- **Coming of age is the race working-age floor** (`Race.minInitAgeYears()`, ~15 for
  humans — the same floor the model already uses when drawing founding/promoted
  heads, so there is one definition of "working age"). At that age a member becomes
  an **adult**: it switches to the full `FINE` worker ration, joins the labour
  market with its own skills, and trains them through work like any worker.
- **Fertility is stochastic, gated on food.** A married household with a **female
  member of childbearing age** rolls a per-day birth probability each step; a birth
  occurs only if the household's necessity stock clears a **food-buffer gate**
  (enough stock on hand to feed its current members *plus* the newborn with a
  cushion). So births throttle themselves in scarcity — the binding constraint that
  drives collapse — and a prosperous, well-fed household breeds.
- **Births are on by default, everywhere.** Fertility is the model's normal state,
  not an opt-in: `FertilityConfig.DEFAULT` carries a calibrated non-zero
  `dailyBirthProb`, so **every** colony breeds (any colony with married households —
  i.e. every ruler-bearing colony, which runs a wedding market). A run can still tune
  or disable it per colony, but the default is on. (The Phase-1 landing shipped the
  default at `0` so the mechanism could go in byte-identical; flipping the default to
  the calibrated value is Phase 2/3 — see *Phased implementation plan*.)
- **Children are subject to mortality** on the full life table. `Member` already
  rolls `rollOldAgeDeath` against its age and race each step; the life table's young
  ages carry historically high infant/child mortality, which acts as a natural
  demographic brake (no separate child-death mechanism is needed).
- **No hard household-size cap.** Size is bounded organically by the food gate (no
  births when food is short), child mortality, and the existing
  starve-off-the-youngest rule (below) — not by an arbitrary maximum.
- **Scope: laborers only.** Only `Laborer` households bear children for now. Nobles
  and the Ruler keep their current fresh-drawn same-dynasty heir (the colony's
  built-in `Household.successor` policy); extending births to them — so a noble/ruler
  child becomes the real hereditary heir — is deferred (see *Open questions*).
- **Everyone tracks their parents.** Every individual carries a reference to its
  **mother** and **father**. A newborn's are set to the breeding couple that bore it
  (the household's adult female and adult male). **Every initially-generated
  individual starts with both parents `null`** — founding heads, seeded/promoted
  peasants, wed-in spouses, and immigrants all arrive with no known parentage (their
  forebears are outside the colony's knowledge). So parentage is populated only by
  in-colony births, growing a real family tree generation by generation. It is a
  forward-looking hook (lineage display, incest avoidance in the wedding market,
  parent→child inheritance) and purely additive — `null` for everyone until births
  fill it in.

### Eating, with mixed rations

Today a laborer eats `eatAmt × getMemberCount()` and a uniform
`fed = floor(ate / ration)` decides how many members are fed. With children that
becomes a **ration-weighted, ordered feed**:

- The household's daily need is **Σ ration(member)**, where an adult eats `FINE`
  (`config.eatAmt()`) and a child eats `SNACK`.
- Members are fed **in priority order: head → other adults → children**. Because
  members are appended head → spouse → children and `removeNonHeadMember()` drops the
  **last** member, the **youngest children starve off first** — the demographically
  sensible order, and the one the life-table choice implies. (This is already the
  incidental order; the change makes it intentional.)
- If even the head cannot be fed, the household **dissolves** as it does today (its
  estate settles, a successor inherits or the dynasty goes extinct).

The food-buffer figures the laborer already keeps — the `targetNStock` scaling on
`nConsumption` and the two-days-of-food `minN` floor — switch from raw
`getMemberCount()` to **ration-weighted mouths**, so a child counts as 0.1 of an
adult's food demand rather than a full unit.

### Renewal (why this fixes collapse)

When a head dies with surviving members, `checkOldAgeDeath()` promotes the next
member to head and the household **lives on**. With children present this means a
grown child inherits the headship from its parent — the household renews **from its
own line**, with no pool promotion and no fresh capital from the Ruler. Pool
promotion still fires, but only as the fallback when a household dies out entirely
(every member dead). A colony of breeding households is therefore **self-renewing**:
the workforce is no longer a strict replacement ratchet, which is the whole point.

## Architecture mapping

### `Member` — a child/adult distinction (`agent/Member.java`)

Add `boolean isAdult(LocalDate today)` → `getAgeYears(today) >= race().minInitAgeYears()`.
This is the single definition of coming-of-age that the eating, labour-market and
fertility code all read. `Member` already carries the birth date, age and per-member
mortality, so no other state is needed.

### `Member` — parentage (planned)

`Member` gains two nullable references, **`mother`** and **`father`** (other
`Member`s). They are set **only at birth**, in `newChild` below, to the bearing
couple; **every other `Member` constructor leaves them `null`** — the drawing
constructor (founding heads), the adopt-a-peasant constructor (promotion, the pool
seed, immigrants), and the wedding spouse are all initially-parentless. The fields
are read-only after construction (a person's parents never change). Because a parent
`Member` object persists after death (it is kept as the household's sole member for
the death log), a child's references to dead parents stay valid for lineage queries.
Purely additive: nothing reads the fields yet — they are the seam for a family tree,
wedding-market incest avoidance, and parent→child inheritance.

### `Laborer.act()` — bearing and eating (`agent/laborer/Laborer.java`)

1. **Eat** with the ration-weighted ordered feed above (head first, children starve
   off last), replacing the uniform-ration block. Head unfed ⇒ dissolve (unchanged).
2. **Bear a child** (new, after survival): if the household has a **fertile female
   adult** (one of head/spouse is female and of childbearing age) **and** its
   necessity stock clears the food-buffer gate, roll the per-day birth probability;
   on success, append the newborn `Member`.
3. The **food-buffer target / `minN`** computations switch to ration-weighted mouths.

`seekSpouseIfSingle()` is unchanged: it posts only a household of size 1, so a married
household (size ≥ 2) never re-seeks, and a household with children is never single.

### `LaborMarket.addEmployee(Laborer)` (`market/LaborMarket.java`)

Loop only **adult** members (`member.isAdult(colony.getDate())`). Children deliver no
labour and earn no wage — a one-line filter on the existing per-member loop. Their
skills neither contribute productivity nor gain experience until they come of age.

### `Demography` — the newborn draw (`mortality/Demography.java`)

A new `Member newChild(String surname, Race race, Settlement colony, Member mother,
Member father)` (or similar):
- `birthDate = colony.getDate()` (age 0);
- **`mother` / `father`** set to the bearing couple (the only place parentage is
  populated — see *`Member` — parentage*);
- gender 50/50 (`sampleGender`);
- skills around the gender-specific colony mean (`newSkillTracker(colony.getMeanSkill(gender))`),
  drawn fresh — **skill is not inherited**, consistent with every other head/member draw;
- a given name from the matching gender/race table at the newborn's skill rarity,
  carrying the **household surname** (a child is not a new dynasty);
- the child's **race** is the head's race (irrelevant for today's mono-race colonies).

Plus a fertility helper for the per-day birth roll. **Stream choice:** the birth roll
and the newborn's gender/skill/name draws run on the **demographic skill / naming
RNGs** (alongside `sampleGender`/`newSkillTracker`), never the mortality or economic
streams — the project's reproducibility rule. Like the pool's market activity, births
**change the economic dynamics** (more mouths, more necessity demand, and eventually
more workers), so runs are **not** byte-identical to today — expected for a
behavioural feature.

### `FertilityConfig` — the parameters (new record)

Following the `*Config` + `DEFAULT` convention (`WeddingConfig`, `RetinueConfig`, …),
an immutable record holding:

- `dailyBirthProb` — per-day probability a fertile, well-fed household bears a child
  (calibrated to a target completed fertility);
- `childbearingMinAge` / `childbearingMaxAge` — the fertile age window (e.g. 15 / 45);
- `foodBufferDays` — the gate: necessity stock must cover this many days of the
  household's (member+1) ration before a birth fires;
- `childRation` — `RationSize.SNACK`.

It is a **colony-wide demographic property** (uniform across the colony's households,
fixed for its life — like `targetNStock` and the gendered mean skills), set on the
colony via `SimulationHarness` and read in `Laborer.act()`, and surfaced through
`SimulationConfig` so a run can tune it from `main`. Its `DEFAULT` carries the
**calibrated non-zero `dailyBirthProb` — births are on by default for every colony**
(see *The model*); a run lowers it (or sets `0`) only to deliberately suppress
births. (The Phase-1 cut shipped `DEFAULT` at `0` so the mechanism could land
byte-identical; Phase 2/3 sets the live default.)

## The ChildrenFirm (schooling)

A **`ChildrenFirm`** gives the colony's children somewhere to grow their skills
before working age, so a child **comes of age already capable**. That turns the
renewal mechanism from "replace a dead worker with an unskilled grown-up newborn"
into "replace it with a *trained* one", and gives the food gate on births a payoff:
a prosperous colony breeds *and* schools, raising the productivity of the next
generation. It is modelled on the crown-service institutions (`BuilderFirm` /
`StrategicFirm` / the research `ScienceFirm`), but unlike any production firm it
**produces no good, earns no revenue, and pays no wages** — it is purely a training
institution.

### Decided behaviour

- **A pure training institution.** No good, no market, no money moves through it; its
  only effect is advancing the enrolled children's skills.
- **An automatic civic institution.** One per colony, created at founding alongside
  the other crown services (the harness installs it — ruler-associated, no noble
  owner, no dividend). It is **not** chartered or dissolved by the dynamic firm
  provisioning and **does not occupy a build slot**: it is simply present wherever
  there are children.
- **Capacity-limited, oldest-first.** It has a fixed number of places
  (`capacity`). Each step it enrolls up to that many children, **the oldest first**
  (those closest to working age, so their freshly-grown skills land just before they
  enter the workforce); the younger overflow waits and is enrolled in later years as
  it ages up. A child with no place gains nothing that tick.
- **One random skill per tick, passion-scaled.** Each enrolled child gains
  `xpPerTick` experience in **one randomly chosen skill** (of the 12), applied
  through the existing `SkillRecord.learn` curve so the gain is passion-scaled (a
  child advances its passionate skills faster). Over many ticks a child develops an
  uneven, organic skill profile rather than a flat one. (A skill trained above the
  decay floor of level 10 then decays daily like anyone's, but an enrolled child
  keeps gaining — the usual train-vs-decay equilibrium.) The random-skill draw runs
  on the **demographic skill RNG**, never the economic stream, so schooling does not
  perturb the economy's random draws.

### Architecture mapping

- **`ChildrenFirm`** (new, `agent/firm/` beside `BuilderFirm`): an `Agent` — a
  "firm" by name and civic role, not a producer — registered with the colony via
  `colony.addAgent(...)` and acting in the agent phase. Each `act()` it gathers the
  colony's children (the sub-working-age members across the laborer households,
  scanning `colony.getAgents()` for `Laborer` members where
  `!member.isAdult(today)`), sorts them oldest-first, takes the first `capacity`, and
  trains each. **No labour market is involved** — a wage-driven `LaborMarket`
  allocates places by wage budget, so a no-wage firm would hire no one; the
  institution **pulls** its pupils directly.
- **`Demography`** gains a small helper to pick a random `Skill` on the skill RNG
  (e.g. `randomSkill()` / `trainRandomSkill(SkillTracker, xp)`), keeping the draw off
  the economic and mortality streams, consistent with `newChild` / `bearsChild`.
- **`ChildrenFirmConfig`** (new `*Config` record with `DEFAULT`): `capacity` and
  `xpPerTick` — a colony-wide schooling parameter, held by the firm and set via the
  harness. `capacity` is a fixed knob for now; tying it to colony size or a dedicated
  school building is a later refinement.
- **Harness wiring:** `SimulationHarness` creates the `ChildrenFirm` as part of the
  default civic setup (like `createDefaultStrategicSector` and the default builder),
  so every ruler-bearing colony has a school. The bare pool-less sims that skip the
  other crown services skip it too.

### Reporting — `Children.csv`

The child population gets its **own** time-series — a `ChildrenPrinter` writing
`Children.csv` — mirroring how `LaborersPrinter` / `RetinuePrinter` aggregate the
living laborer population and the pool. It is the natural home for both the child
**demographics** and the **school** state (the school produces no good, so it stays
out of `Firms.csv`, and its child-centric stats read better here than in the
crown-services `Services.csv`). One row per cycle on the standard monthly cadence
(`Printer.shouldPrint`), aggregating over the **living** children read from
`colony.getAgents()` each step (the sub-working-age members across the laborer
households), with columns:

- `Date`
- `Count` — living children
- `AvgAge` — mean child age in years
- `AvgSkill` — mean child `overallLevel`
- `Enrolled` — children attending the `ChildrenFirm` this cycle
- `Capacity` — the school's `capacity` (so `Enrolled` reads against its ceiling)

Registered for ruler-bearing colonies alongside the school (the bare pool-less sims,
which bear no children, register neither). `Enrolled` / `Capacity` are `0` for a
colony that somehow has children but no school.

## Reporting (the rest)

- The existing `LaborersPrinter` aggregates over the **living** laborer *households*;
  with children it can additionally expose the household-level detail — total
  **member** count vs. **household** count and average household size — while the
  per-child demographics live in `Children.csv` (above).
- `SimLog`: births are high-frequency churn, so **no per-birth line** at the default
  floor — the annual digest's population figures already summarise growth. A notable
  newborn (peak skill above `Household.NOTABLE_SKILL`) can be logged by name at
  `fine`, mirroring the notable-arrival log, and registered as a person of interest.

## Abandonment (the caravan hinge)

When a ruler-bearing colony's workforce falls below `DISSOLUTION_WORKFORCE_FLOOR` it
dissolves into a wandering `MigrantCaravan` (`docs/caravan.md`): every household
member except the ruler-leader is absorbed into the band's `Retinue`
(`MigrantCaravan.dissolve` → `following.absorb`). **Children are absorbed too** — the
colony's next generation travels with the band and can re-found a new colony. Three
decisions make a pooled child behave sensibly (the `Retinue` is otherwise age-blind):

- **Children are age-gated in the pool.** A pooled child (a `Member` with
  `!isAdult(today)`) stays a non-working child: it eats the **child ration**
  (`SNACK`), not the adult relief/wandering ration, and is **ineligible to be
  promoted into a household head or wed as a spouse**. It ages on the life table while
  pooled, so when it **reaches working age it becomes promotable/marriageable
  automatically** (the age check is read live). This stops the age-blind pool from
  making a child a household head or a spouse. (A pooled child does not train — a
  wandering band has no school.)
- **Parentage survives absorption.** `Retinue.adopt` carries the absorbed member's
  `mother`/`father` references into the pooled `Member` (rather than rebuilding a bare
  one), so the family link survives the colony's fall even though the household is
  broken up. Seeded/immigrant peasants stay parentless, as before.

**Implementation surface** (all in `agent/Retinue.java`, using `getColony().getDate()`
for the live age check):
- `feed()` — ration per peasant by adulthood (child ration vs. the mode ration), and
  starve accordingly (a ration-weighted feed, as `Laborer` now does);
- `promoteHighestSkilled()` / `promoteHighestSkilled(int)` — consider only adults;
- `bestSpouseCandidate(Gender)` — consider only adults;
- `adopt(Member)` — pass `m.getMother()` / `m.getFather()` into the new `Member`.

**Accepted edge:** if a band's following is *all* children (no adult to promote),
re-founding promotes no labour force until they mature — the band keeps wandering (its
larder draining) and may not survive to raise them. A future refinement could let a
band wait out the children's coming-of-age; for now it is an accepted failure mode.

## The collapse tests (the planned flip did NOT happen — see Phase 3)

The original plan was to **flip the collapse-asserting tests to assert survival**
once births made colonies self-renewing (`ClosedColonySmokeTest` /
`TwinSettlementEconomyTest` assert a colony departs as a Caravan; the caravan-machinery
tests *require* dissolution). The Phase-3 calibration sweep found **births do not, and
cannot, prevent the collapse** (the ~6–10-year food-balance shock precedes the 15-year
maturation window — see Phase 3). **So the tests are NOT flipped:** colonies still
collapse, the assertions remain correct, and the caravan/dissolution suite still has
its trigger. The flip is deferred until baseline survival is extended past the
maturation window by a separate food-economy/survival fix.

## Phased implementation plan

- **Phase 1 — the mechanism, with focused coverage. (Implemented.)** `Member.isAdult`,
  the ration-weighted ordered feed, the birth roll + `Demography.newChild`, the
  adults-only labour filter, and `FertilityConfig` (shipped at `dailyBirthProb 0`, so
  the landing was byte-identical). Covered by `agent/BirthsTest`: the coming-of-age
  boundary, and a short pool-colony run where married households bear children and the
  population exceeds the household count.
- **Phase 2 — wire the config + default on + parentage. (Implemented.)**
  `FertilityConfig` is threaded through `SimulationConfig.fertility()` and applied to
  the colony in the `SimulationHarness` constructor; `FertilityConfig.DEFAULT` is
  flipped **on** (placeholder `dailyBirthProb 0.002`, `foodBufferDays 14`), so births
  are on by default for every colony. `Member` gained the `mother`/`father` parentage
  fields, set at birth in `newChild` and `null` for every other individual
  (`BirthsTest` asserts every colony-born child records both parents).
  - **Finding:** at the placeholder rate the standard colonies **still collapse**
    within the 25-year horizon (the whole suite, incl. `ClosedColonySmokeTest`'s
    `assertCollapsed`, stays green) — births are on but too weak to outrun the ~15-year
    maturation delay and the children's consumer drag. Pushing the rate up, lengthening
    horizons, and flipping the collapse tests is the Phase-3 work.
- **Phase 3 — calibration attempted; BLOCKED (negative result).** A wide sweep
  (`dailyBirthProb` 0–0.05, `foodBufferDays` 5–14, pool 40–900, food firms 1–40,
  founder age 18–35, external inflow 0–20 000, capped and uncapped) found **no
  configuration that stabilizes a colony.** Every colony collapses at **~6–10 years**;
  higher birth rates collapse *sooner* (more consuming mouths), and at collapse the
  colony holds 30–170 children who never reached working age. The cause is a
  **timescale mismatch**: the colony dies of its structural ~6–10-year food/larder
  shock (the replacement-ratchet collapse the `CalibrationSweep` already proved has no
  parameter fix) **5–9 years before** a home-grown child matures at **15**. Crucially
  the collapse is **not demographic** — colonies founded with 18-year-olds (who cannot
  die of old age for decades) collapse on the same ~9-year schedule — so it is a
  **food-balance** instability that fertility cannot touch and births only worsen.
  - **Consequence:** the collapse-asserting tests **stay as-is** (colonies genuinely
    still collapse / depart as caravans — the assertions remain correct); they are
    *not* flipped. `CLAUDE.md`'s collapse narrative stands.
  - **Real prerequisite (out of births' scope):** a colony must first be made to
    **survive ≥ ~18 years** before births can renew it. That is a **food-economy /
    survival calibration** (necessity TFP, consumption, the larder ramp, the
    replacement ratchet) — the long-standing accepted-collapse problem — *not* a
    fertility knob. Births are a correct, necessary piece of the eventual fix, but
    they are **insufficient alone**: they can only sustain a colony that already lives
    long enough to raise a generation. Re-attempt Phase 3 *after* baseline survival
    reaches the maturation window.
- **Phase 4 — the ChildrenFirm (schooling). (Implemented.)** Added the `ChildrenFirm`,
  its `ChildrenFirmConfig`, the `Demography.trainRandomSkill` helper, the harness wiring
  (`createDefaultChildrenFirm`, in the default founding), and the `ChildrenPrinter` /
  `Children.csv`. Covered by `agent/firm/ChildrenFirmTest`: enrolled children's skills
  rise far above the newborn baseline, and enrollment is capped at capacity (oldest-first)
  when oversubscribed. Its payoff — children entering the workforce already skilled —
  feeds the Phase-3 calibration.
- **Phase 5 — abandonment / age-gating the pool.** When a colony dissolves into a
  caravan its children are absorbed into the band (already the case); age-gate them in
  the `Retinue` so a pooled child eats a child ration and cannot be promoted to head
  or wed until it comes of age, and carry parentage through `Retinue.adopt` (see
  *Abandonment (the caravan hinge)*). Covered by a test: dissolve a colony holding
  children and assert they enter the band's pool, are not promoted/wed while underage,
  and keep their parent references.

## Open questions deferred to later

- **Births for nobles and the Ruler** — so a noble/ruler child becomes the real
  hereditary heir, replacing the fresh-drawn successor. Natural next scope once the
  laborer path is proven.
- **Adult children splitting off** into their own new households (e.g. on marriage),
  rather than only accumulating as members of the parent household. Today an adult
  child stays a working member and only ever becomes head by inheritance; a
  marriage-driven household-fission would be a richer model.
- **Multiple fertile couples per household** — today only the head + spouse breed;
  an adult child does not take its own spouse while in the parent household.
- **Inherited aptitude** — skill is currently drawn fresh for every newborn; a model
  where a child's skills/passions correlate with its parents' is a future refinement.
- **Calibration interactions** — more mouths raise necessity demand, which the
  ruler's dynamic firm provisioning answers by chartering more food firms; the
  fertility rate, the food-buffer gate, and the provisioning thresholds will need to
  be tuned together (the model is calibration-sensitive).
