# Design note: the rank ladder (promotion & demotion)

**Status:** implemented (phases 1–4); phase 5 (further triggers, further rungs) is future work
**Date:** 2026-06-17
**Depends on:** the `eos.agent.Rank` enum, the `eos.agent.Household` interface and
its three implementors (`Laborer`, `Noble`, `Ruler`), the adopt-style household
constructors (`Noble(Member head, …)`, the adopt-a-`Member` `Laborer`), and the
colony's deferred-mutation hooks (`Settlement.scheduleEndOfStepAction`,
`scheduleAddAgent`, `scheduleRemoveAgent`).
**Related:** `Rank` (command scope, this note) is the deliberately-separate sibling of
**`SocialClass`** (standing & standard of living, `docs/social-class.md`) — collinear
today but independent axes; and the **tech tree** (`docs/tech-tree.md`) gates the
`HOLDING → VILLAGE → CITY` ascent behind researched techs (its `SOCIAL_GATE` effect).

## Motivation

The colony already performs three social-mobility transitions, each hand-written
in its own place:

| Transition | Rank move | Code today | What changes |
| --- | --- | --- | --- |
| Promotion from the pool | (raw) → `HOUSEHOLD` | `SimulationHarness.promoteToLaborer` | `Member` → `Laborer`, fresh copper account, ruler-funded skill-sum endowment |
| Ennoblement | `HOUSEHOLD` → `HOLDING` | `SimulationHarness.ennobleBestLaborer` | `Laborer` → `Noble`, re-bank copper→silver, balances carried |
| Succession | same rank | `Household.successor(colony)` | same type, same dynasty, inherits estate |

They share one shape: **take a household's identity (its members) and money,
reform it into the type that realizes a different rank, and swap the agent in —
deferred to end of step, after the dying/moving agent's offers have cleared.**
That repeated shape is what this note abstracts, so a fourth transition (today
there is no **demotion** at all) costs a factory registration rather than a new
bespoke method.

The unifying lens is the `Rank` enum: each rung names **what an entity commands**,
not what it is — `HOUSEHOLD` (the bottom rung) commands a family, `CARAVAN` a
following, `HOLDING` firms/estates, `VILLAGE` a settlement, on up to `HEGEMONY`.
Promotion is a step up that ladder; demotion a step down; both are the same
machinery in opposite directions.

## The model (decided behaviour)

- **Every rung on the household ladder is a real `Agent`.** A household's rank is a
  property of the household (`Household.rank()`), and a transition turns one
  household agent into another. There is no rung occupied by a non-agent.
- **`CARAVAN` is a *command* rung, not a population.** A pooled peasant is **not**
  rank `CARAVAN`; it commands nothing, so it is unranked raw population (no rung at
  all — passive, fed by a patron, no agency). `CARAVAN` is the rung of
  *whoever owns a following*. The peasant pool is therefore an **asset** attached
  to a `CARAVAN`-or-higher household — modelled like a `Noble`'s `firms` list —
  not a parallel ranked population the transition engine must special-case.
- **"Promotion out of the pool" is recruitment, not a rank-step.** It is a
  `CARAVAN`+ entity founding a *new* `HOUSEHOLD` agent from its following (raw
  population → a new household). It is a creation event, exactly what
  `promoteToLaborer` already does; it is not a peasant climbing a rung, so it needs
  no `CARAVAN` source rank and stays outside the rank engine (it remains the
  pool/recruitment mechanism it is today).
- **The rank engine is uniformly `Household ↔ Household`.** `promote(h)` reforms a
  household into the next rank up; `demote(h)` into the next rank down. Money is
  conserved (the old account is closed into equity and the carried balances reopen
  the new one, possibly at a different bank tier), members carry across, and the
  agent swap is deferred to end of step.
- **`CARAVAN` and `CITY`→`HEGEMONY` are reserved rungs.** No concrete agent type
  realizes them yet (the ruler today jumps straight to commanding both a following
  *and* a settlement, i.e. `VILLAGE`). The ladder registers **no factory** for an
  unrealized rung, so `promote()`/`demote()` skip past it cleanly. When a future
  entity fills `CARAVAN` (a warband leader who has gathered followers but holds no
  land), it slots in with the pool as its backing asset — no engine change.
- **Scope of the first cut.** Only the three realized rungs and the transitions
  between them: `HOUSEHOLD` (`Laborer`), `HOLDING` (`Noble`), `VILLAGE` (`Ruler`).
  The existing `ennobleBestLaborer` becomes `promote` over a selected laborer; the
  reverse `demote` (`HOLDING` → `HOUSEHOLD`, a ruined noble re-banking silver→copper)
  is the new capability the abstraction unlocks for free.

## Architecture mapping

### `Rank` learns its neighbours

The enum already carries a stable `level()` (its position in the hierarchy). Add the
two adjacency helpers — pure, no dependencies:

```java
private static final Rank[] BY_LEVEL = values(); // level == ordinal today

/** The next rank up, or empty at {@link #HEGEMONY}. */
public Optional<Rank> promoted() {
    return level + 1 < BY_LEVEL.length ? Optional.of(BY_LEVEL[level + 1]) : Optional.empty();
}

/** The next rank down, or empty at {@link #HOUSEHOLD}. */
public Optional<Rank> demoted() {
    return level > 0 ? Optional.of(BY_LEVEL[level - 1]) : Optional.empty();
}
```

(If `level` ever diverges from declaration order, replace the index arithmetic with a
lookup over a `level`-sorted array; the signatures stay the same.)

### `Household` exposes its rank

One default method on the `eos.agent.Household` interface, overridden per type:

```java
/** This household's rank — the scope of what it commands. */
default Rank rank() { return Rank.HOUSEHOLD; }
```

- `Laborer` — inherits the default `HOUSEHOLD`.
- `Noble` — overrides to `HOLDING`.
- `Ruler` — overrides to `VILLAGE`.

This is purely additive and behaviour-neutral on its own (nothing reads `rank()`
until the engine does), so it can land independently and keep runs byte-identical.

### `Estate` — what survives a rank change

A small immutable carrier of the transferable identity + money, all in copper (the
base unit), independent of the concrete household type:

```java
/** What survives a rank change: who, and how much money (in copper). */
public record Estate(List<Member> members, double checking, double savings) {}
```

The head is `members.get(0)`; any spouse rides along in the list. Balances are read
from the old bank (in copper) before its account is closed.

### `RankFactory` — building the type that realizes a rank

```java
/** Builds the household type that realizes one rank, adopting an existing estate. */
@FunctionalInterface
public interface RankFactory {
    Household reform(Estate estate, Settlement colony);
}
```

Each factory owns its rank's quirks — its bank tier and any funding rule — so the
engine never branches on rank:

- `HOUSEHOLD` factory → `new Laborer(head, …)` adopting the estate at the **copper**
  bank. (This is the *re-formation* of an existing household at the `HOUSEHOLD`
  rung — e.g. a demoted noble — **not** pool recruitment, which stays separate.)
- `HOLDING` factory → `new Noble(head, checking, savings, nobleConfig, silverBank,
  colony)` adopting the estate at the **silver** bank, carrying the remaining
  members across. This is exactly today's `ennobleBestLaborer` body.

No factory is registered for `CARAVAN`, `CITY`…`HEGEMONY`, or for `VILLAGE` in the
first cut (the ruler is created at founding, not promoted into).

### `RankLadder` — the registry + the engine

A small object the harness installs on the colony (mirroring `setFirmFactory` /
`addReplacementPolicy`). It holds the per-rank factories and runs the transition:

```java
public Optional<Household> promote(Household h) { return reform(h, h.rank().promoted()); }
public Optional<Household> demote(Household h)  { return reform(h, h.rank().demoted()); }

private Optional<Household> reform(Household h, Optional<Rank> target) {
    if (target.isEmpty() || !factories.containsKey(target.get()))
        return Optional.empty();                 // off the end, or a reserved rung
    RankFactory factory = factories.get(target.get());
    colony.scheduleEndOfStepAction(() -> {        // after this step's offers clear
        Estate estate = snapshotAndClose(h);      // read balances, close old account → equity
        Household reformed = factory.reform(estate, colony);
        colony.scheduleRemoveAgent((Agent) h);
        colony.scheduleAddAgent((Agent) reformed);
    });
    return ...;                                   // a handle, or empty-but-scheduled
}
```

`snapshotAndClose(h)` reuses the existing account-move accounting (read checking +
savings from `h.getBank()`, close the account folding it into equity — the same
sequence `ennobleBestLaborer` does inline today), so **money is conserved** across
the swap and the re-bank.

### Collapsing the existing transitions onto it

- `ennobleBestLaborer` → `selectBestLaborer(...).ifPresent(ladder::promote)`. The
  selection logic (highest head `SOCIAL`/`INTELLECTUAL`, youngest on a tie) stays;
  the *transformation* moves into the `HOLDING` factory.
- `topUpAristocracy` and the no-owner ennoblement fallback call `ladder.promote(...)`
  instead of `ennobleBestLaborer()` — same deferral semantics they already use.
- `promoteToLaborer` / the pool is **untouched** — it is recruitment, not a rank
  step, and stays the pool's own concern.

### Why `CARAVAN`-as-asset removes the only asymmetry

In the first sketch of this design `CARAVAN` was "the pooled peasant's rank," which
forced an asymmetric branch: the bottom transition crossed the `Member`-in-a-pool /
`Agent` boundary, so the engine needed a special pool hook. Re-reading the ladder as
*what an entity commands* relocates the pool to an **asset** of a `CARAVAN`+
household (like `Noble.firms`). The result: every `Estate` is the estate of a
genuine agent, every swap is `scheduleRemoveAgent`/`scheduleAddAgent`, and the
engine has **no** special case. The pool is drawn from for recruitment (creating
`HOUSEHOLD` agents) rather than promoted *from*.

## Accepted limitations (explicitly out of scope for this cut)

1. **Most rungs are unrealized.** Only `HOUSEHOLD`/`HOLDING`/`VILLAGE` have agent
   types. `CARAVAN` and `CITY`→`HEGEMONY` are reserved: `promote()`/`demote()`
   return empty there. This is intentional — the ladder is the seam those entities
   will plug into, not a promise they exist now.
2. **`VILLAGE` is not yet a promotion target.** A ruler is created at founding, and
   no factory reforms a noble into a ruler. Promoting `HOLDING` → `VILLAGE` (a noble
   founding/seizing a settlement) is left for when multi-settlement politics arrives;
   the rung is realized, the *transition into it* is not.
3. **Demotion has one trigger (insolvency); others are future.** Phase 4 wired the
   first: a noble insolvent (a net debtor) past a one-year grace period is "ruined"
   and demoted (see the phased plan). Other policies that might fire `demote()` —
   attainder, losing one's last holding, conquest — are not modelled. A
   ruined-noble re-ennoblement oscillation is possible in principle (demote drops the
   noble count below `targetNobles`, the weekly top-up re-ennobles the ablest
   laborer), but the one-year grace on each side damps it and standard nobles stay
   solvent (export wage), so it is not observed in practice.
4. **Money-supply drift on demotion mirrors promotion's.** Re-banking conserves the
   carried balances, but if a demotion (like ennoblement) is funded fresh rather
   than purely carrying balances, the same equity-strand caveat as the pool note
   applies. The first cut carries balances 1:1 (no fresh funding), so it is neutral.

## Phased implementation plan

- **Phase 1 — `Rank` adjacency + `Household.rank()`. (Implemented.)** The
  `Rank.promoted()`/`demoted()` helpers (single-step walk over a `level`-indexed
  array, empty at the ends) and the per-type `rank()` overrides (`Laborer` inherits
  the default `HOUSEHOLD`, `Noble` → `HOLDING`, `Ruler` → `VILLAGE`). Pure, additive,
  byte-identical. Unit-tested by `eos.agent.RankTest` (the ladder walk, the empty
  ends, and that promote/demote are exact inverses in the interior).
- **Phase 2 — `Estate` + `RankFactory` + `RankLadder`, refactor ennoblement.
  (Implemented.)** The engine lives in `eos.agent` (`Estate`, `RankFactory`,
  `RankLadder`); `SimulationHarness.ennobleBestLaborer` keeps its selection loop but
  delegates the transformation to `rankLadder().promote(best)`, with the former
  inline body now the `HOLDING` factory. `topUpAristocracy` and the no-owner charter
  fallback are unchanged (they still call `ennobleBestLaborer`). Behaviour-preserving
  — the full suite stays green, including the ennoblement/Hanseatic/meritocratic
  smoke runs. **Realized subtlety:** a `Laborer` is `HOUSEHOLD` and ennoblement
  targets `HOLDING`, so `promote` had to *skip* the unrealized `CARAVAN` rung; the
  ladder walks to the nearest rank with a registered factory rather than the
  immediate neighbour.
- **Phase 3 — demotion capability. (Implemented.)** The `HOUSEHOLD` factory (noble →
  copper-banking laborer, built like a pool-promoted laborer but adopting the carried
  balances) makes `demote()` exercisable, exposed as the public
  `SimulationHarness.demote(Household)`. Money is conserved symmetrically because the
  ladder transcribes the copper balance directly (`openAcct` + plain `closeAcct`,
  neither touching equity), changing only the bank tier and type. Covered by
  `eos.simulation.NobleDemotionTest` (a raised noble demoted re-banks in copper, head
  carried across, balances unchanged). **No automatic trigger yet** — nothing in a
  live run calls `demote`, so registering the `HOUSEHOLD` factory leaves every run
  byte-identical.
- **Phase 4 — the insolvency demotion trigger. (Implemented.)** A `Noble` tracks its
  `consecutiveInsolventDays` (a net-debtor step advances it, any solvent step resets
  it — no RNG or money, so byte-identical to the economic stream). A
  `demoteRuinedNobles` step action, registered for every ruler-bearing colony by
  `createDefaultRuler`, demotes (end of step, like ennoblement) any noble insolvent
  for ≥ `NOBLE_INSOLVENCY_GRACE_DAYS` (365, a placeholder matching
  `MIN_FIRM_LIFETIME_DAYS`). Before demoting, the noble's holdings are reassigned to
  the least-loaded other noble (`Noble.transferPropertyTo`) so its firms/banks are
  not orphaned; if it is the colony's only noble they go unowned until the next
  charter's no-owner fallback re-ennobles an owner. Standard nobles work the export
  firm and stay solvent, so the trigger is a no-op there and the full suite stays
  green; covered by `eos.simulation.RuinedNobleDemotionTest` (a noble crushed under
  an unpayable debt is demoted to a copper-banking laborer past the grace window).
- **Phase 5 — future (separate notes).** Further demotion triggers (attainder, loss
  of last holding); realizing `CARAVAN` as an entity with the pool as its asset; the
  `HOLDING` → `VILLAGE` promotion once settlements can be founded/seized.

## Decided since (see `docs/village-founding.md`)

- **No multi-rung `promote`/`demote`.** Founding (`CARAVAN → HOLDING → VILLAGE`) is
  modelled as two ordinary single-rung reforms by giving the middle rung real content
  (a holder of a village hall + banks), so the ladder never needs to move more than
  one rung. The helpers stay single-step.
- **The `RankLadder` is global, and lives on `GameSession`** — *not* on `Settlement`.
  Rank spans the colony-less (a wandering `CARAVAN`) and the supra-settlement
  (`CITY`…`HEGEMONY`) states, so it cannot belong to one colony. The current
  implementation is colony-bound (it works because every existing reform is
  within-colony); the move to `GameSession` happens as part of founding, after
  banks-as-holdings (so factories resolve their banks from the passed scope rather
  than capturing per-colony harness state).
- **Holdings are a first-class concept, distinct from `Estate`.** `Estate` is the
  household's *liquid* identity carried across a reform (members + balances);
  *holdings* (firms, banks, the hall — unified behind a `Property` interface) are the
  *productive assets* owned, transferred separately (`transferPropertyTo`). A reform
  carries the `Estate`; the holdings move on their own.

## Open questions deferred to later

- Whether selection (who gets promoted/demoted) belongs in the ladder or stays with
  the caller (the harness/ruler), as the ennoblement selection does today.
- How a multi-member household's **spouse** is handled when only the head's rank
  changes — carried across in the `Estate` (current assumption) vs. split off.
