# Settlement size and build slots

Current-state architecture reference (extracted from `CLAUDE.md`). The spatial
capacity model — how a settlement's size maps to build slots, how it grows, and the
out-of-band special sites. The runtime-founding design that builds on this lives in
[`village-founding.md`](village-founding.md).

A `Settlement` is a **disc of radius `size`** with a fixed number of **build slots**
— the spatial capacity that holds firms (and, later, housing and other buildings).
The geometry is a precalculated lookup, `SlotTable` (`settlement/SlotTable.java`),
loaded once from `/slots.json` (the exported design spreadsheet, sizes 0–95) and
held on the `GameSession`: like `NameRegistry`/`Demography` it is **shared** by every
colony in the session — it is pure geometry, independent of seed and location — and
threaded into the `Settlement` constructor alongside them (the sim never passes it;
`GameSession.newSettlement` supplies it). Each row is a `SlotInfo` record
(`settlement/SlotInfo.java`): `total` build slots = `floor(π·size²)` (size 0
hand-set to 1, the lone slot the export firm takes); of those `road =
floor((size/100)·total)` are consumed by roads — a **linearly-growing congestion
share** — and `wall` (≈ circumference; the exact column is transcribed from the
sheet, the one column no clean formula reproduces) by walls; what's left is
`effective = total − road − wall`, the usable capacity. `maxSpecialSites` (1→6,
unlocked at sizes `{0,4,10,19,31,57}`) counts **special sites** — out-of-band slots
for enormous buildings/projects *not* subject to the normal limit. `SlotInfo.wallBuildTimePercent()`
(= `Wall%²`) is a wall build-speed multiplier the `BuilderFirm` uses (inverted to a
work multiplier) when costing a ring's wall work — walls go up fast while the colony
is small, slower as its circumference grows. Because roads eat an ever-larger share,
**effective capacity peaks at size 66 (4244 slots) and then declines**; the table
caps at 95 (a colony would take far more than a normal run to approach it).

Each `Settlement` carries a **mutable** `size` and a `List<Slot>` — one `Slot`
(`settlement/Slot.java`: a nullable `SlotOccupant` occupant with
`occupy`/`vacate`/`isVacant`) per effective slot. `SlotOccupant`
(`settlement/SlotOccupant.java`) is the occupant interface; `Agent` implements it, so
firms (the only occupants today) qualify, and it is the seam for future non-firm
occupants (housing, a village hall). A colony is **founded at `SlotTable.MIN_SIZE`
(3 → 15 effective slots)** and **grows to fit its occupants**, but *how* it grows
depends on its lifecycle. `claimSlot(occupant)` seats the occupant on the first
vacant slot; when none is free it diverges: **at founding** (before `start()`) it
lays out the initial footprint by calling `setSize(size+1)` (which extends the slot
list — growth-only; shrinking below the occupied count throws) one step at a time
until the occupant fits — a one-time genesis sizing — whereas **once the colony is
live** (after `start()`) it does *not* grow itself: the only way a running colony
gets bigger is through its `BuilderFirm` (see *Agents → Builder* in `CLAUDE.md`).
`SimulationHarness` claims a slot for **every firm** — capital, enjoyment, necessity
and the `StrategicFirm`. Now that the standard colonies found with only **1E + 1N +
1C + 1 strategic + 1 builder = 5 firms**, they fit inside the floor (size 3 → 15
slots) at founding and **grow during the run** as the ruler's dynamic provisioning
charters more (the builder seats them); `SmallOpenEconomy`'s bare `2E + 2N + 1C`
firms still size their footprint at founding. This realizes "smallest size that fits
the firms, floored at 3" with no per-sim configuration: `size` is **not** a
`SimulationConfig` field — it is always founded at 3 and emerges from what's placed.
Slot placement moves no money and consumes no randomness, so it does not perturb the
economic stream.

A `Settlement` grows during the run only if it has a **builder**, a `BuilderFirm`
registered via `setBuilder` (a colony without one simply never grows once live —
`claimSlot` throws if a live builderless colony runs out of room; this is the case
for the bare `SmallOpenEconomy`, which has no pool and so no builder). Every
**pool-bearing** colony now gets a default builder (created in
`createDefaultRetinue`), so the standard sims all carry one — and it is now
**active in practice**: a colony founded with just 1E + 1N grows as the ruler's
dynamic provisioning charters firms, so the builder raises ring after ring to seat
them (the previously-dormant growth path is now the common case). When a live
colony's `claimSlot` finds no free slot, it **queues the next ring** for the builder
(`requestGrowth`) and holds the occupant **pending** (returning `null` — slots are
pure bookkeeping, so the firm is economically active immediately regardless), seating
it once the ring is built. A ring (size `n → n+1`) splits into `BuildProject`s
(`settlement/BuildProject.java`) by funder: a `LAND` task per pending occupant (the
slot it will stand on, funded by **that firm**) plus the ring's `ROAD` and `WALL`
public works (funded by the **ruler**). The builder bills each sponsor at cost as it
works; `completeFinishedRings()` calls `setSize` and seats the pending firms once a
ring's land, road and wall tasks are all done. The **scaffold cap**
(`BuilderConfig.scaffoldCap`, the limit on build-units per step) and the wall
build-speed factor bound how fast this goes.

## Special sites

Special sites are **occupiable** but separate from the effective-slot machinery:
`Settlement` holds a parallel `specialSites` list grown by `setSize` as larger sizes
unlock more (`SlotInfo.maxSpecialSites`, 1 at the founding floor rising to 6).
`claimSpecialSite(occupant)` / `getSpecialSites()` place on / view them; unlike
`claimSlot`, special sites are **not** raised by the builder and **not** grown on
demand — their count is fixed by the current size — so `claimSpecialSite` throws when
every unlocked site is taken (the colony must grow to unlock another). Nothing
occupies them in the standard runs yet; the planned first occupant is the **village
hall** (a civic seat) — see [`village-founding.md`](village-founding.md).
