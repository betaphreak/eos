# Design note: SocialClass (standing & standard of living)

**Status:** proposed (design only — not yet implemented)
**Date:** 2026-06-18
**Depends on:** the household stack (`eos.agent.Household`, `AbstractHousehold` and
its three implementors `Laborer`, `Noble`, `Ruler`), the peasant pool
(`eos.agent.Retinue` of `Member`s — see `docs/peasant-pool.md`), the ration tiers
(`eos.good.RationSize`), the currency tiers (`eos.bank.CurrencyType` and the
copper/silver/gold banks `SimulationHarness` provides), the wedding market's
priority order (`Household.weddingPriority()`, `market/WeddingMarket.java`), and —
as its deliberately-separate sibling axis — the rank ladder (`eos.agent.Rank`,
`docs/rank-ladder.md`).
**Related:** the tech tree (`docs/tech-tree.md`) gates a future `BURGHER` tier behind
a researched tech (its `SOCIAL_GATE` effect), so the two notes meet at the
wealthy-commoner rung.

## Motivation

The colony already has a clean four-tier social gradient, but it is encoded
**implicitly across the type hierarchy** rather than as a first-class concept. Each
tier is a distinct Java type (or none, for the pool), and the attributes that make
it "a noble" vs. "a commoner" are scattered as magic values inside each class:

| Tier | Type | Ration | Bank tier | Wedding prio | `Rank` | Income mode |
| --- | --- | --- | --- | --- | --- | --- |
| Peasant | `Member` in `Retinue` (not a household) | `SIMPLE` 0.25 | copper (pool acct) | n/a | unranked | relief (ruler-fed) |
| Laborer | `Laborer` | `FINE` 0.5 (`config.eatAmt()`) | copper | 0 | `HOUSEHOLD` | wage |
| Noble | `Noble` | `LAVISH` 1.0 | silver | 1 | `HOLDING` | dividends |
| Ruler | `Ruler` | `GOURMET` 2.0 | gold | 2 | `VILLAGE` | treasury + tax |

Concretely, the same "what station is this?" decision is re-made in four places:

- the **ration** is hardcoded per type — `RationSize.LAVISH` in `Noble.act()`,
  `RationSize.GOURMET` in `Ruler.act()`, `config.eatAmt()` (= `FINE`) in
  `Laborer.act()`, `RELIEF_RATION = SIMPLE` in `Retinue`;
- the **currency tier** is assigned implicitly by which bank `SimulationHarness`
  hands each agent (`getCopperBank`/`getSilverBank`/`getGoldBank`);
- the **wedding priority** is a per-type `weddingPriority()` override (0 / 1 / 2);
- the **role label** is a per-type `role()` override.

There is no single place that names what it *means* to belong to a tier. This note
proposes `SocialClass` as that place.

## The core distinction: SocialClass vs Rank

This model already has `Rank`, and `docs/rank-ladder.md` is explicit that **`Rank`
means "what an entity commands"** — its political/territorial scope, from
`HOUSEHOLD` (a family) up through `HOLDING`, `VILLAGE`, … `HEGEMONY`.
`SocialClass` is the **other** axis: **"your standing and standard of living"** — the
bundle of how richly you eat, which currency you bank in, how you rank in the
marriage market, and (descriptively) how you earn.

The two are **collinear today** — every noble both commands a `HOLDING` *and* lives
as gentry — which is exactly why it is tempting to treat class as redundant with
rank-plus-type. But they are **conceptually orthogonal**, and the model already
wants to pull them apart:

- A **wealthy commoner** who owns a firm is `HOUSEHOLD` rank, yet might bank and eat
  above a bare laborer.
- A **ruined / landless noble** keeps gentry standing through the insolvency grace
  period before demotion fires — class lagging rank (see `docs/rank-ladder.md`,
  phase 4).
- A **pooled peasant** has a class (`SIMPLE` relief) but **no rank at all** — it
  commands nothing — so class extends *below* the rank ladder's bottom rung.

So the decided framing (chosen explicitly during design):

> **`Rank` = command scope (political).  `SocialClass` = standing / consumption
> (economic-social).** They correlate now but are independent dials.

`SocialClass` is **not** a re-labelling of `Rank`, and `Rank` stays purely
political. Where they overlap today is incidental, not structural.

## The model (proposed behaviour)

- **`SocialClass` is an ordered enum carrying the standing bundle.** Each member
  owns the values currently scattered across the four classes: its daily
  `RationSize`, its banking `CurrencyType`, and its wedding priority. The enum order
  is the social gradient (peasant lowest), so "above"/"below" comparisons and the
  wedding sort fall out of `ordinal()`.

  ```java
  public enum SocialClass {
      PEASANT (RationSize.SIMPLE,  CurrencyType.COPPER, 0),  // pool relief
      COMMONER(RationSize.FINE,    CurrencyType.COPPER, 0),  // laborer
      GENTRY  (RationSize.LAVISH,  CurrencyType.SILVER, 1),  // noble
      ROYALTY (RationSize.GOURMET, CurrencyType.GOLD,   2);  // ruler

      private final RationSize ration;
      private final CurrencyType currency;
      private final int weddingPriority;
      // ration(), currency(), weddingPriority()
  }
  ```

  (Names are placeholders — `COMMONER`/`GENTRY`/`ROYALTY` read clearly against the
  15th-century setting. A future wealthy-commoner tier — `BURGHER`, between
  `COMMONER` and `GENTRY` — would bank in **silver**, not copper: see *The
  class→currency map* below.)

- **`Household.socialClass()` exposes it**, exactly mirroring how `rank()` was
  added: a default method on the interface, overridden per type.
  - `Laborer` → `COMMONER`
  - `Noble` → `GENTRY`
  - `Ruler` → `ROYALTY`

  The **pool** is the `PEASANT` class but is not a `Household`; `SocialClass.PEASANT`
  is still the right home for the `SIMPLE` relief ration, read by `Retinue`
  directly (the pool is "pre-household" raw population — the same way the rank ladder
  treats it as unranked).

- **The scattered constants are refactored to read from the class**, leaving the
  numeric values byte-identical:
  - the four ration sites read `socialClass().ration().perDay()` (the pool reads
    `SocialClass.PEASANT.ration()`);
  - `Household.weddingPriority()` becomes a default of
    `socialClass().weddingPriority()` (the per-type overrides drop out);
  - `SocialClass.currency()` **documents** the existing copper/silver/gold
    assignment — see the caveat below.

## Architecture mapping

### `SocialClass` — the enum

Lives in `eos.agent` alongside `Rank` (both are household-standing concepts). Pure
data + accessors, no dependencies beyond `RationSize` and `CurrencyType`. Additive
and behaviour-neutral on its own — nothing reads it until the refactor wires the
call sites, so it can land independently and keep runs byte-identical.

### `Household.socialClass()` — the accessor

```java
/** This household's social class — its standing and standard of living. */
default SocialClass socialClass() { return SocialClass.COMMONER; }
```

Overridden by `Noble` (`GENTRY`) and `Ruler` (`ROYALTY`); `Laborer` inherits the
`COMMONER` default. Defaulting to `COMMONER` (not `PEASANT`) keeps a new household
type a commoner unless it says otherwise, matching how `rank()` defaults to
`HOUSEHOLD`.

### Refactoring the call sites (behaviour-preserving)

| Site today | Becomes |
| --- | --- |
| `RationSize.LAVISH` in `Noble.act()` | `socialClass().ration()` |
| `RationSize.GOURMET` in `Ruler.act()` | `socialClass().ration()` |
| `config.eatAmt()` in `Laborer` (= `FINE`) | `socialClass().ration().perDay()` *(see note)* |
| `RELIEF_RATION = SIMPLE` in `Retinue` | `SocialClass.PEASANT.ration()` |
| `weddingPriority()` overrides (0/1/2) | `socialClass().weddingPriority()` default |

Note on `LaborerConfig.eatAmt()`: it is currently a tunable config knob that
happens to equal `FINE`. The cleanest move is to keep `eatAmt()` as the source of
truth for the laborer (a per-run tunable) and assert/seed it from
`SocialClass.COMMONER.ration()`, rather than silently replacing a config knob with
an enum constant. Decide during implementation; the design only requires that the
**default** value match the class.

### The currency caveat (load-bearing)

The ration and wedding priority are safe to centralize — they are pure consumption
/ ordering values, so reading them from the enum changes nothing. **The currency
tier is different.** Which bank an agent holds (copper / silver / gold) determines
whether the FX exchange fee fires and at what tier, so it is part of the calibrated
**dynamics**, not cosmetic. Therefore:

- `SocialClass.currency()` is **documentation** of the existing assignment, not a
  switch that re-banks an agent.
- `SimulationHarness` stays the thing that actually wires each agent's bank.
- A future class *change* that crosses a currency boundary must go through the
  money-conserving re-bank the rank ladder already does (`Estate` carried across,
  old account closed into equity, new account opened at the new tier — see
  `docs/rank-ladder.md`), **not** a field flip.

This keeps Phase 1 strictly behaviour-neutral.

### The class→currency map (and why higher classes bank in higher denominations)

The map is **class → currency**, and it is **not injective**: four classes, three
currencies, with `PEASANT` and `COMMONER` both on copper (in fact the *same*
zero-profit copper bank; silver and gold are the money-changers that skim the 2% FX
fee). `SocialClass.currency()` is well-defined regardless — each class returns
exactly one currency — but you **cannot** invert it to recover an agent's class
from its bank, which is the second reason currency stays *documentation*, not a
driver: it is neither cosmetic (it moves the FX dynamics) nor identifying (two
classes collapse onto copper).

A class banks in a higher denomination as its wealth outgrows the lower coin. This
settles the one place the map needs a real decision rather than inheriting today's
assignment — **a future `BURGHER` (wealthy-commoner) tier banks in silver, not
copper**: there is not enough copper in supply to denominate that much commoner
wealth, so the burgher trades up to silver exactly as the gentry do. So the
boundary between copper and silver is not "commoner vs. noble" (a rank/political
line) but "how much wealth must this denomination carry" (a class/economic line) —
which is precisely the orthogonality this note is built on. The resulting map:

| Class | Currency | Rationale |
| --- | --- | --- |
| `PEASANT` | copper | relief-level holdings |
| `COMMONER` | copper | a laborer's modest balances |
| `BURGHER` *(future)* | **silver** | too much wealth for the copper supply to carry |
| `GENTRY` | silver | rentier dividends |
| `ROYALTY` | gold | the treasury |

## Relationship to the rank ladder

The two axes share a shape (an ordered set of tiers a household sits on) but answer
different questions, and the existing transitions touch both:

| Transition | `Rank` move | `SocialClass` move |
| --- | --- | --- |
| Pool → laborer (recruitment) | — (raw pop → `HOUSEHOLD`) | `PEASANT` → `COMMONER` |
| Ennoblement | `HOUSEHOLD` → `HOLDING` | `COMMONER` → `GENTRY` |
| Demotion (ruined noble) | `HOLDING` → `HOUSEHOLD` | `GENTRY` → `COMMONER` |
| Succession | same rank | same class |

Because `socialClass()` is derived from the household *type* in this first cut, each
existing transition (which already swaps the type via the rank ladder's factories)
moves the class **for free** — there is nothing extra to wire. The independence of
the axes only becomes load-bearing when a transition moves one without the other
(the wealthy-commoner / lagging-noble cases above), which is explicitly **out of
scope here** (see limitations).

## Accepted limitations (out of scope for this cut)

1. **Class is derived from type, not stored.** In Phase 1 `socialClass()` is a
   per-type override, so it cannot diverge from the type — no wealthy commoner, no
   noble whose standing lags its (lost) rank. Storing class as independent mutable
   state is the natural Phase 2, but it is a real refactor (and the currency strand
   is load-bearing), so it is deferred.
2. **The currency tier is documented, not driven.** Per the caveat above, the enum
   records the assignment; `SimulationHarness` still wires the bank, and a
   class-driven re-bank is future work piggybacking on the rank ladder's
   money-conserving reform.
3. **No new tiers realized.** A `BURGHER`/wealthy-commoner tier (the obvious place
   the class axis diverges from rank) is named as a possibility but not added — its
   currency is **decided** (silver, per *The class→currency map*), but the tier
   itself, and the rule that promotes a rich commoner into it, are Phase 3 — and
   gated behind a researched tech (`docs/tech-tree.md`'s `SOCIAL_GATE`).
4. **The pool is not a household.** `SocialClass.PEASANT` is the relief class, read
   by `Retinue` directly; the pool stays raw population (no `Household`,
   consistent with how the rank ladder treats it as unranked).

## Phased implementation plan

- **Phase 1 — the enum + `Household.socialClass()`, refactor the ration & wedding
  sites.** Add `eos.agent.SocialClass` (the bundle), the `socialClass()` default +
  two overrides, and refactor the four ration sites and `weddingPriority()` to read
  from it; document `currency()` without changing any bank wiring. Byte-identical
  (values unchanged), covered by the existing smoke suite plus a small
  `SocialClassTest` (the gradient order, the per-type mapping, that
  `weddingPriority()` still returns 0/1/2). This is the scope of this note.
- **Phase 2 — class as stored, mutable state (future, separate note).** Decouple
  class from type so it can diverge (wealthy commoner, lagging noble); route any
  currency-crossing class change through the rank ladder's `Estate` re-bank so money
  stays conserved.
- **Phase 3 — class-driven behaviour & new tiers (future).** A `BURGHER` tier;
  class as an input to consumption/savings behaviour beyond the ration; whatever the
  "playable game" direction needs from a household's station.

## Open questions deferred to later

- Whether `LaborerConfig.eatAmt()` should remain a tunable knob seeded from the
  class, or be replaced by the class constant outright (Phase 1 keeps the knob).
- Whether `SocialClass` should also carry the **role label** (`role()`), unifying
  the last per-type string, or whether role stays a finer-grained per-type concern
  ("Notable laborer" is narrower than "commoner").
- Whether a future stored class belongs on `AbstractHousehold` or on the head
  `Member` (a person's station vs. a household's), once households grow past one
  member and a spouse could in principle out-rank the head.
