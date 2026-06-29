# Proposal: the food-economy / population-renewal redesign

**Status:** design proposal (2026-06-29). No code changed by this doc. It synthesizes
the `docs/food-balance.md` investigation (which proved, across eight measured levers,
that the colony collapse is a *coupled* production↔renewal trap, not a tuning gap) and
proposes a concrete redesign that assembles the pieces already built into one
self-sustaining loop. Companion to `docs/food-balance.md` (the diagnosis),
`docs/births.md` (the births mechanism), and `docs/peasant-pool.md` (the labor model).

## 1. The problem, in one paragraph

A ruler colony collapses at ~10–12 years. Two compounding modes (`docs/food-balance.md`):
**(A)** a founding starvation crunch (largely fixed — the necessity sector is now sized
to the labor force), and **(B)** a residual decline in which the workforce only ever
*replaces* deaths from a finite peasant pool, the pool drains, food output (which tracks
the workforce) falls below survival demand, the necessity price spirals, and the colony
spirals to collapse. The deep fact is that **the population cannot renew itself**: new
*households* (the count the survival floor measures) are created only by promotion from
the finite pool; in-colony births grow household *size*, never *count*.

## 2. Why eight incremental levers all failed

Every single-knob lever was measured and is neutral-to-negative — they keep *confirming*
the trap rather than breaking it:

| Lever | Result | Why it failed |
|---|---|---|
| Founding-age pool children | **~9→11.5 y (shipped)** | staggers the cohort — the one win, but only delays |
| Softer mortality (0.5→0.15) | flat (~+1 y, noisy) | decisive deaths are starvation, not old age |
| Demand-driven promotion | **worse** (~−1 y) | extra workers are net food *consumers* in the crunch |
| Higher mean skill (7→8) | worse (~−1.4 y) | ration-capped demand absorbs the output; bigger endowment drains the ruler |
| Household fission (built) | **dormant** (never fires) | no child survives to maturity in a living parent household |
| Child ration / child protection | no help | children mature too late vs. parent lifespan |
| minN price-cap | flat | the spike is **budget-driven**, not floor-driven; food deficit is real |
| Asymmetric price band | **chaotic / worse** | distorting price discovery destabilizes the coupled loop |

The throughline: **output capacity, workforce headcount, mortality, and price mechanics
are all symptoms.** The binding constraints are (i) food output that tracks a declining
workforce and (ii) a renewal valve that is structurally closed.

## 3. The core insight: a coupled production↔renewal trap

A colony is stable **iff both** hold at the operating population:

1. **Production balance:** food output ≥ consumption (no chronic deficit), *and* the
   surplus does not crash the price/wage economy.
2. **Renewal balance:** new households form at ≥ the death rate.

These are coupled, which is why no single knob works:

- Output needs workers; workers need food; food needs output → **a production loop**.
- Renewal needs births; births need a food buffer; children need food to survive to
  maturity; maturity must feed new household formation → **a renewal loop**.
- The two loops share the *same scarce food*. Adding people (promotion, births) without
  first creating a food surplus just accelerates the deficit (measured: demand-driven
  promotion, higher skill, fission all backfire or stall).

So the fix cannot be a knob on either loop in isolation. It must establish a **food
surplus that is stored rather than dumped** (so output can exceed consumption without
deflating wages), and then **spend that surplus on renewal** (feeding children to
maturity and opening the births buffer) — closing both loops together.

## 4. The redesign: five components, one loop

The keystone is new; the rest are calibration of mechanisms that already exist or are
already built (fission, glut-close, founding children, the child ration).

### 4.1 Ever-normal granary — the keystone (new)

A **ruler-run food buffer** that stabilizes the necessity price by trading against the
market within a band around the reference price (`ConsumerGoodMarket.getInitialPrice()`):

- when supply would push the price **below** a floor (a glut), the granary **buys** the
  surplus and stocks it — the price cannot crash, so firm revenue and the labor-share
  wage budget do not collapse (this is what makes a food *surplus* survivable, which raw
  skill/TFP and the asymmetric band both failed to achieve);
- when scarcity would push the price **above** a ceiling, the granary **sells** from
  stock — capping the death-throes spike at its source (the elastic budget demand that
  the minN price-cap could not touch), and actually putting *more food on the market*
  when it is most needed.

This is the classic ever-normal granary, and it is the *single* mechanism that fixes
**both** price regimes the investigation showed a symmetric band cannot serve — because
it acts on **quantity** (real stock in and out), not on the price-discovery band. It
extends what the `Ruler` already does (keeps a necessity larder, buys necessity, funds
pool relief). New parameters (a `GranaryConfig`): floor/ceiling price factors, target
stock (days of colony consumption), per-step trade cap. Funded from the ruler treasury;
the granary's *stock* is the colony's strategic food reserve.

### 4.2 Net-food-positive workers (calibration, now safe)

For the granary to fill, a necessity worker must **over-produce** — its marginal food
output must exceed its household's consumption at the operating point. Today it sits at
"bare parity" (`docs/food-balance.md` mode B). Raising necessity TFP (`NFirm.effectiveA`
/ `NECESSITY_TECH_FACTOR`, or the tech tree's per-sector multiplier) was previously
self-defeating because surplus deflated the price. **Behind the granary (4.1) and the
glut-aware close rule (already done, `Ruler.reviewSector`), surplus is now absorbed at
the floor instead of crashing the price**, so TFP becomes a usable lever for the first
time. Target: a stable workforce produces a comfortable surplus (e.g. output ≈ 1.3–1.5×
consumption) that the granary banks.

### 4.3 Child survival — child relief (new, small)

Children are the first to starve (`Laborer` feeds head → adults → children, youngest
off first), so in any lean spell the next generation is culled before it matures — the
measured reason fission never fires. Add **child relief**: when a household cannot feed a
child member, the child's ration is drawn from the **granary** (4.1) rather than the
child starving — the food analogue of the pool's poor-relief, billed to the ruler. The
granary's accumulated surplus (4.2) is exactly the reserve that pays for this. Children
now survive lean spells to reach working age.

### 4.4 Home-grown renewal — fission + marriage (built + calibration)

- **Fission is already built** (`AbstractHousehold.releaseGrownChild` →
  `Laborer.emancipateChild` → `SimulationHarness.formNewHouseholds`, dormant): a
  colony-born child that reaches working age leaves to found its **own** household,
  turning births into household *count* at zero net resource cost. Once 4.1–4.3 let
  children survive to maturity, this is the valve that finally fires. Make the dowry
  **ruler/granary-funded** (the parent's larder is depleted exactly when its child
  matures — the measured second gate), drawing the food dowry from the granary.
- **Marriage throughput** gates births (a birth needs a married couple). Raise
  `WeddingConfig.capacity` and/or seed pool-promoted laborers as couples, so enough
  households pair to sustain a birth rate. With the granary smoothing food, more
  households also clear the `FertilityConfig.foodBufferDays` gate, so births rise.

### 4.5 How the loop closes

Workers over-produce (4.2) → the granary fills and holds the price stable (4.1) → stable
prices and wages let households prosper, marry (4.4) and clear the births food-buffer →
children are born and **survive lean spells on granary relief** (4.3) → they mature and
**fission into new households** (4.4) → the workforce is renewed from within, independent
of the finite pool → output is sustained. The peasant pool reverts to its proper role:
**founding seed + immigration buffer**, not the sole renewal source.

## 5. Implementation plan (phased, each independently verifiable)

Build and validate one component at a time against the existing default colony, so a
regression is caught before the next layer is added.

1. **Granary (4.1).** Build `Granary` + `GranaryConfig`, wired to the `Ruler` /
   `Settlement`, trading on the necessity market each clear. **Verify:** on the *current*
   colony the necessity price stays in-band (no crash to 0.09, no spike to 40+), with no
   change to the collapse horizon yet (it only stabilizes price, not yet output/renewal).
2. **TFP up (4.2).** Raise necessity TFP behind the granary. **Verify:** the granary
   stock now *accumulates* (workers are net-positive) and the price stays in-band (no
   deflation). Horizon should improve as the founding crunch and mode-B deficit ease.
3. **Child relief (4.3).** Feed unfed children from the granary. **Verify:** children
   now reach working age — household `children` count carries cohorts through to maturity
   instead of dropping to deaths; fission begins to fire.
4. **Fission funding + marriage (4.4).** Granary-funded dowry; raise wedding capacity.
   **Verify:** fission fires steadily; new household formation ≥ death rate.
5. **Steady state.** **Verify:** the colony reaches a stable or growing population over a
   long horizon (see §6) rather than collapsing.

Each phase is a small, reversible change with a clear pass/fail, in contrast to the
eight blind single-knob sweeps that preceded this.

## 6. Success criteria

The redesign works iff a default colony (seed 7654321, Dhenijansar) **does not collapse**
over a long horizon (e.g. 40+ in-game years), specifically:

- **household count** stable or growing (the current monotonic decline is gone);
- **new households/yr** (fission + promotion) ≥ **deaths/yr**, with fission the dominant
  term once the first home-grown generation matures (~year 15);
- **granary stock** oscillates around a positive target (never chronically empty);
- **necessity price** stays within the granary band (no crash, no spiral);
- **laborer wealth** stays positive on average (no deflationary debt spiral).

A `CalibrationSweep`-style harness over the granary/TFP/relief/marriage parameters then
finds the stable region (the earlier sweep proved *none* exists under the replacement-only
model — the point is that this redesign should make one exist).

## 7. Risks and open questions

- **Ruler treasury drain.** The granary, child relief, and fission dowries all spend the
  ruler's fixed founding fortune. If they outrun tax revenue the ruler goes insolvent.
  Mitigation: the granary *buys low / sells high*, so it should be roughly self-funding
  over a cycle; child relief and dowries are paid from granary *stock* (food it already
  bought cheap), not fresh treasury. Needs measurement.
- **Granary instability.** A badly-tuned band could oscillate (buy and sell each step) or
  corner the market. Mitigation: per-step trade caps, a dead-band around the reference,
  hysteresis (reuse the `SectorMemory` pattern from firm provisioning).
- **TFP deflation if the granary saturates.** If the granary hits its target stock and
  stops buying, surplus again hits the market and deflates. Mitigation: let the target
  scale with population, and let surplus past target spill to exports (the strategic
  sector) or spoil (a storage-loss term) rather than crash the price.
- **Over-renewal.** If fission + births overshoot, population could outgrow food and
  re-enter scarcity. This is the *healthy* problem (the granary + glut-close + firm
  provisioning are exactly the negative feedback for it) — but worth watching.
- **Marriage realism.** Raising wedding capacity is a blunt fix; a better model is
  marriage as a normal life event for adult households, not a capacity-rationed market.

## 8. What already exists vs. what is new

**Already built (assembled, not re-created):**
- Household **fission** (dormant, `SimulationHarness.formNewHouseholds`).
- **Glut-aware firm close** rule (`Ruler.reviewSector`, the price-crash signal).
- **Founding-pool children** + the **child ration** (`SNACK`) in `Laborer`/`Retinue`.
- **Births** (`Laborer.act` / `FertilityConfig`) and the **wedding market**.
- The ruler's **necessity larder** and **pool relief** (the seam the granary extends).

**New work:**
- `Granary` + `GranaryConfig` (the keystone, §4.1) — the largest piece.
- **Child relief** from the granary (§4.3) — small, in `Laborer` feeding + ruler.
- **Granary-funded fission dowry** (§4.4) — small change to `formNewHouseholds`.
- **Necessity TFP** calibration (§4.2) and **wedding capacity** (§4.4) — parameter work.

The net of the investigation: stop tuning the symptoms, build the granary that lets a
food surplus exist, and spend that surplus on keeping the next generation alive long
enough to renew the colony from within.
