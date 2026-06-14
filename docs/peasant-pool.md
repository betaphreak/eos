# Design note: the peasant pool

**Status:** proposed (design only — not yet implemented)
**Date:** 2026-06-14
**Depends on:** the `Person`/`Member` split (`eos.agent.Member`) and the per-member
`AbstractHousehold.checkOldAgeDeath()` loop.

## Motivation

Today a dead laborer household is replaced by a **same-dynasty heir** that inherits
its estate (registered as a replacement policy in
`SimulationHarness.createLaborers`), so the labor force and money supply both stay
constant and dynasties are immortal. This note replaces that dynastic succession,
*for laborers only*, with **state-managed social mobility**: a reserve **pool of
peasants** from which the **Ruler** elevates the most skilled to fill the gap a dead
household leaves. It gives the Ruler a real fiscal obligation (feeding the poor)
beyond its luxury habit, makes labor replacement meritocratic rather than
hereditary, and funds that obligation by **taxing the colony's accumulated wealth**
— bank profits and noble income — the first slice of the planned taxation feature.

## The model (decided behaviour)

- There is a **pool of peasants** — people with skills and an age but **no
  household**, no wage, and no account of their own.
- Peasants **eat one necessity per day** each and **never consume enjoyment**.
- The **Ruler feeds the pool**: their necessity is bought on the necessity market
  and **billed to the Ruler at cost** each step. The Ruler **may borrow** (overdraw
  into a loan, as build sponsors already do) to cover the bill, so peasants do
  **not** starve from Ruler insolvency — they starve only when the necessity
  **market itself** cannot supply them. Starvation is thus a *supply-side* valve;
  the Ruler's failure mode is unbounded debt, bounded only by tax calibration.
- The **Ruler funds itself by taxation**, not just its opening treasury: each step
  it skims a fraction of every **bank's distributable profit** and a fraction of
  every **noble's income** into its treasury. This is the colony's redistributive
  loop — the wealth held by the banking sector and the aristocracy taxed to feed
  the poor — and the first slice of the planned taxation feature. Whether the take
  covers relief plus luxury is now a calibration question, not a structural spiral
  (see *Accepted limitations*).
- Peasants live a **full lifecycle** while pooled: they **age and can die of old
  age** (as well as starvation), and their **skills decay daily** like everyone
  else's.
- When a **laborer** household dissolves (its head dies with no surviving member),
  the Ruler **promotes the highest-overall-skill peasant** out of the pool and
  founds a **new laborer household** for it, **endowed fresh from the Ruler's
  treasury**. This is the replacement — there is no same-dynasty heir.
- **Scope: laborers only.** Nobles and the Ruler keep their existing same-dynasty
  succession (the colony's built-in `Household.successor` policy), so the Ruler
  persists to run the pool and the export sector keeps its nobles.
- **The initial labor force is born from the pool.** At founding the pool is seeded
  with the whole starting population (`numLaborers` to be employed **plus** the
  standing reserve), and the Ruler creates the initial laborer households by the
  **same promotion logic** used for replacements — one code path for making a
  laborer household, at founding and ever after, retiring the harness's bespoke
  `createLaborers` construction. The unpromoted remainder is the reserve, which only
  drains thereafter (see *Accepted limitations*).

## Architecture mapping

### `PeasantPool` — a new `Agent`

A new `eos.agent.PeasantPool extends Agent`, registered with the colony via
`colony.addAgent(...)` like any other agent, holding a `List<Member>`. The
`Member` type from the household split is an exact fit: a peasant is precisely a
`Person` (name + skills) with its own birth date, age and old-age mortality, minus
a household. The pool owns a `Necessity` good (a small stock it eats from, like a
`Laborer` holds `necessity`), and a reference to the colony's `Ruler` (its
sponsor) and the necessity `ConsumerGoodMarket`.

### Step-loop integration

The pool acts in the normal agent phase of `Settlement.newDay()` (phase 1,
`agent.act()`), and its necessity purchases settle in phase 4 (`market.clear()`),
exactly like a laborer's. `PeasantPool.act()` each step:

1. **Age + die:** for each peasant, `member.rollOldAgeDeath(demography, today)`;
   remove the dead. (Reuses the per-member mortality added for households.)
2. **Decay skills:** `member.skills().tick()` for each survivor. (Household members
   are ticked in `Settlement.newDay`; pool members are ticked here instead, since
   they are not in a household.)
3. **Eat:** consume one necessity per peasant from the pool's stock; any peasant
   that cannot be fed **starves** and is removed.
4. **Restock, billed to the Ruler:** post a buy offer to the necessity market for
   the pool's shortfall, funded by debiting the **Ruler's account** at cost (the
   `BuilderFirm`-bills-its-sponsor pattern — `Bank.withdraw(rulerID, cost)`). The
   Ruler **overdraws into a loan** if its treasury is short, so the bill is always
   met from the money side; peasants starve (step 3) only when the **market** can't
   supply the necessity. No enjoyment is ever bought.

All peasant **draws** (skills via `Demography.newSkillTracker`, ages via
`Demography.sampleInitialAgeDays`, old-age rolls, names) run on the **demographic /
naming RNGs**, never the economic stream — the project's reproducibility rule. The
pool's **market purchases**, however, are genuine economic activity (they add
necessity demand), so this feature **changes the economic dynamics**: runs are
**not** byte-identical to today (unlike the `Member` refactor, which was). That is
expected for a behavioural feature.

### Seeding and founding

`SimulationHarness` creates the `PeasantPool` and seeds it with
`numLaborers + peasantReserveSize` (reserve default **10**) members at founding,
each drawn like a household head (skill on the skill RNG around
`colony.getMeanSkill()`, age on the mortality RNG). Peasants are **not** given a
dynasty surname while pooled — surnames are drawn without replacement and reserved
for living *households*. A peasant carries a given name (and skills) only; its
unique dynasty surname is drawn at **promotion**, when it founds a household.

The **initial labor force is then created through the same promotion path** (see
below): the Ruler promotes the top `numLaborers` peasants into laborer households,
leaving the reserve pooled. This retires the bespoke household construction in
`createLaborers`. Two ordering consequences:

- The **Ruler must be created before** the founding promotions (today it is created
  last so its demographic draws don't perturb the commoners'; that ordering rule
  changes — acceptable, since the feature is behavioural and not byte-identical
  anyway).
- Founding order becomes: markets/banks/firms → seed pool → Ruler promotes
  `numLaborers` households → register the (now promotion-based) replacement policy →
  the one pre-run labor-market clear → `run()`.

Because promotion endows fresh from the Ruler, **the Ruler capitalizes the whole
initial labor force on day 0** — a large opening loan. This is not new money versus
today (laborers already open with conjured savings); it gives that money a
counterparty (Ruler debt) repaid over time by taxation. The promoted household's
opening balances and necessity stock come from the **existing laborer config**
(`LaborerInit` — the same template `createLaborers` used), merely *funded* by the
Ruler rather than appearing directly in the account.

### Promotion = the single laborer-household factory

There is one `Ruler.promoteFromPool()` operation, used in **two** places: in bulk at
founding (to create the initial `numLaborers` households) and one at a time as the
replacement policy. It **promotes the highest-overall-skill peasant**: remove it
from the pool and construct a **new `Laborer` household** for it, with the config
opening balances/necessity **funded from the Ruler's treasury** (borrowed if
needed; not the dead estate), drawing a **fresh dynasty surname**.

As the replacement policy (registered in the harness in place of the old
same-dynasty rule):

- If the dead agent is a `Laborer`, the Ruler promotes one peasant as above.
- If the **pool is empty**, return `null` — no replacement is produced, and the
  labor force shrinks by one (the accepted depletion).

Consequences that fall out of existing machinery:

- The dead laborer gets **no successor**, so its dynasty is extinct and its
  surname is **recycled** automatically (`Settlement` already calls
  `names.releaseDynastyName(...)` for a dead household with no successor).
- The dead laborer's **estate folds into bank equity** as it does today (because
  the replacement is funded fresh, not by inheritance).
- The dead laborer's **surviving members** (none at today's size-1 households)
  would join the pool — the seam for the *dependents* refill (see *Future work*).

Nobles and the Ruler are untouched: their same-dynasty heirs still come from the
colony's built-in `Household.successor` policy.

### Ruler taxation (funding the relief)

The Ruler is no longer a pure drain on a fixed treasury — it levies taxes **each
step**, crediting its (gold) treasury, which then funds peasant relief and luxury
from one pot:

- **Bank profits:** a fraction (`bankProfitTaxRate`) of each bank's
  `getDistributableProfit()` — the profit slice (FX fees, spread, strategic export
  earnings) that the noble dividend channel already exposes — collected through the
  same `payDividend(...)` accounting, with the **Ruler** as recipient.
  Estates-in-transit and injected open-colony funds are left alone (they are not
  distributable profit), so inheritance and the immigration money buffer are
  undisturbed.
- **Noble income:** a fraction (`nobleIncomeTaxRate`) of each noble's income for the
  step (wage + dividends + interest), withdrawn from the noble's account.

Revenue lands in the Ruler's gold account, so copper-quoted taxes convert
copper→gold and fire the gold bank's FX fee, exactly as the Ruler's spending already
does (and silver-banked nobles pay an FX fee on the withdrawal too — the friction is
intended).

**Step-loop timing.** Noble income must be taxed **before** the noble zeroes its
per-step income accumulators at the end of its own `act()` (`Account.priIC /
secIC / interest`), so the levy runs within or just after each noble's act, not as a
late step-action. Exact placement — a tax pass in `Ruler.act()` reading
`colony.getBanks()` and the noble agents, vs. a dedicated collection hook — is an
implementation detail for the slice. Either way the levies move money only between
existing accounts (no new RNG draws), so taxation is deterministic and, unlike the
pool's market buying, does not itself perturb the economic random stream.

### Configuration

`SimulationConfig` gains `peasantReserveSize` (default **10**) — the standing
reserve held *beyond* the employed labor force; the pool is seeded with
`numLaborers + peasantReserveSize` at founding. It also gains the per-step tax rates
`bankProfitTaxRate` and `nobleIncomeTaxRate`. **Calibrated and enabled**: defaults
0.05 and 0.02, validated stable across a full Strategic-style run and the whole
test suite (the only behavioural change was the now-accumulating Ruler). The Ruler's existing `DEFAULT_RULER_GOLD` / `consumptionRate` are
unchanged for now (its day-0 capitalization of the labor force is covered by
borrowing).

### Reporting

- A `PeasantPrinter` writing `Peasants.csv`: count, average overall skill, average
  age, necessity consumed, starvation deaths, total billed to the Ruler.
- `SimLog` events: a **promotion** (the elevated peasant logged by name + skill,
  mirroring the notable-arrival log) and, in aggregate, **starvation** in the pool
  (a per-step count rather than one line per peasant, to avoid log spam).

## Accepted limitations (explicitly out of scope for this cut)

1. **The pool only drains.** With size-1 households a dead head leaves no members,
   so nothing refills the pool: after founding consumes most of it, only the reserve
   remains, draining via promotions and old-age/starvation deaths. It trends to
   empty, after which laborer deaths go unreplaced and the labor force declines.
   Routing founding through the pool does **not** fix this — the standing reserve is
   still small (10). **Accepted** for now — the real refill is the *dependents* path
   (multi-member households), deferred. Treat this as a transitional sandbox; the
   reserve size is a starting point, expected to change.
2. **Ruler solvency is a calibration problem, not a structural one.** The Ruler has
   recurring tax income (bank-profit + noble-income levies) and **borrows** for any
   shortfall, so it always meets its bills; it never forces peasant starvation. The
   open question is whether the take services the Ruler's debt — covering daily
   peasant food **plus** the day-0 capitalization of the labor force **plus** each
   promotion endowment **plus** its luxury habit — or whether that debt grows without
   bound. That is tax-rate tuning, not a structural doom. The rates
   (`bankProfitTaxRate`, `nobleIncomeTaxRate`) are placeholders; over-taxing the
   nobles/banks has knock-on effects (it skims the equity the export sector and
   money-changers accumulate), so this needs validating against the test invariants
   like any other calibrated coupling.
3. **Money-supply leak.** Because promotion is funded fresh (not by inheritance),
   each dead laborer's estate strands in bank equity (out of circulation) while the
   Ruler injects money — a monetary drift the old inherit-the-estate path avoided.
   Accepted as a consequence of the "fresh from the Ruler" choice.

## Phased implementation plan

- **Phase 1 — Ruler taxation.** The per-step bank-profit and noble-income levies
  into the Ruler's treasury. Independent of the peasants and observable on its own
  (the Ruler's gold balance rises; the `Bank` and persons-of-interest printers show
  the take), so it can land and be calibrated first. This is the first slice of the
  broader taxation feature.
- **Phase 2 — the pool exists and is fed.** `PeasantPool` agent + `Member` reuse,
  seeding the **reserve** (size 10), aging/old-age death, skill decay, eating, and
  Ruler-billed restocking with the Ruler borrowing to cover it. Add the
  `PeasantPrinter`. No promotion yet, so founding still uses the current
  `createLaborers` and the reserve simply drains by death. Self-contained; does not
  touch the replacement policy.
- **Phase 3 — promotion, and founding through the pool.** Add `Ruler.promoteFromPool()`
  and use it in both places: swap the laborer replacement policy to promote the
  highest-skill peasant (fresh endowment, fresh surname; `null` on an empty pool),
  **and** retire `createLaborers`' construction by seeding the pool with
  `numLaborers + reserve` and having the Ruler create the initial labor force the
  same way. Log promotions. This is the step that changes colony dynamics and unifies
  the founding/replacement code path.
- **Phase 4 — future (separate notes).** The *dependents* refill (a dead head's
  household members fall into the pool — requires multi-member households) and
  possibly peasant reproduction, to make the pool self-sustaining.

## Open questions deferred to later

- Refill mechanism once we want a sustainable pool (dependents vs. reproduction).
- Whether promotion should weight a firm-relevant skill rather than the overall
  level (a peasant's value to a *necessity* firm is its `PLANTS` skill, etc.).
- Tax calibration: rates that fund relief without crushing the equity the export
  sector and money-changers accumulate; flat vs. progressive; whether to extend the
  base to firm profits later.
