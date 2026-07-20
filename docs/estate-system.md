# Design note: the estate system (internal politics, government, coups)

**Status:** Design (not built) — a north-star model, split out of
[`docs/country-rank-import.md`](country-rank-import.md) as it grew into its own subsystem.
**Date:** 2026-07-20
**Depends on:** `agent/Rank.java` (the estate/faction *nesting by rank level* lives in the rank doc);
the engine's ruler/noble/commoner layer — `agent/ruler/Ruler`, `agent/noble/Noble`,
`simulation/SocialMobility` (ennoblement/demotion), `agent/Retinue` (the peasant pool); the imported
`government`/`religion`/reform data (rank doc §8).
**Related:** [`docs/country-rank-import.md`](country-rank-import.md) — §4 the estate/faction rank
*recursion*, §3.2 the *register*, §5 dynamic rank; `docs/race.md`; the research/beaker-type plan (the
theocracy scale is a research lever).

---

## 1. What this is

A nation's **internal politics**: the estates and factions inside it, which one *rules* (= the
government type), and how power changes hands (influence → coups → government transitions). This is the
**runtime** layer beneath the rank model's estate/faction *structure*. The rank doc (§4) defines the
shapes — an **estate** is a rank rung one below the polity (**L−1**, a plural body), a **faction** is
two below (**L−2**, an unaligned member); this doc says what those blocs *do*.

## 2. The ruling estate IS the government

The government type is defined by **which estate rules**, and the rank-model **register** (the title
flavor, rank doc §3.2) is exactly that ruling estate:

| government | ruling estate | register |
|---|---|---|
| monarchy | the **Crown** | aristocratic |
| republic | the **Plutocratic** estate (Burghers) | plutocratic |
| theocracy | **Religious** / **Mages** / **Scientists** — by the magic↔science scale (§4) | theocratic |
| tribal / native | clan & kin structures | tribal |

So a country's register is not a fixed stamp — it is *whoever currently holds power*, and a coup (§5)
changes it. Register is **dynamic**, the mirror of dynamic rank (rank doc §5): rank changes by
centralize/fragment, register by an estate winning the influence race.

## 3. Estates vs factions, concretely

The mechanical version of the rank doc's "member vs faction":

- **Greater nobles** → the **Aristocratic estate**. **Lesser nobles** → **factions** (L−2 loose blocs).
- **Every religion present** in the nation is a **faction**; a religion holding **>1 province** is
  promoted to a **Religious estate**. So the **faction→estate threshold is territorial** — a faction
  becomes an estate once it holds more than one province.

The religion rule is the one part **importable today**: `provinces.json` carries `religion` per
province, so counting a nation's provinces per religion yields its Religious estates vs mere faiths.

**Default composition — the three major estates.** A base nation (a `COUNTY`, one province) starts with
the **three major estates**: **Nobles** (aristocratic), **Burghers** (plutocratic), and the **Church** of
that province's religion (theocratic) — the three "civilized" registers, each a latent ruling candidate.
Its **`government`** at start says which of the three currently rules (§2). Beyond them a nation has
**zero or more factions**, and — as it grows, or by its culture/reforms — the further estates of the
catalog below (a `>1`-province religion → a second Religious estate, `estate_mages` via a magocracy
reform, caste or Command estates, a reactionary `estate_vampires`, …). A **tribal** society is the
exception: it replaces the three civilized estates with `estate_nomadic_tribes` / `estate_monstrous_tribes`
/ clan estates.

**Estates and factions are two *different* systems** that share the ladder's positions — not one object
at two sizes.

**Estates (L−1)** carry the full political machinery (influence + loyalty, privileges, agendas, coups,
§5) and their agenda is to **rule**:
- **Aligned** — back a government type and, given influence, **install it** (§5): the Crown → monarchy,
  the Burghers → republic, the Religious estate → theocracy.
- **Reactionary** — seek no government of their own and instead **oppose all change**. The **Immortals**
  (long-lived elven lineages) and the **Undead** (liches) — eternal enough to have outlived every
  reform — spend influence *against* change: blocking reforms, reverting sub-scale drift, resisting
  every coup. A nation with a strong reactionary estate is hard to reform in *any* direction.

**Factions (L−2)** are a **distinct, simpler mechanic** — not influence/loyalty/coups but an
**unrest-pressure gauge** that, when it boils over, erupts as a **revolt** toward the faction's aim: a
**peasant faction** revolts toward the `−100` mob pole / communism (§7); an **anarchist faction** revolts
to **decentralize**. So estates seek to *rule* (from above); factions are the pressure that builds
*beneath* them and occasionally erupts.

**Estate depth scales with rank.** The full system is not equally present at every scale: an adventurer
company (`CARAVAN`/`CITY`, pre-estate — its politics are internal party-factions) has almost none; a
`COUNTY` has the three major estates but simple politics; only a large, centralized nation
(`KINGDOM`/`EMPIRE`) runs the whole estate/centralization/coup struggle. For the player (rank-windowed
SP), the estate layer **turns on** as they climb into a real nation.

### The canonical Anbennar estates (import catalog)

The **31 distinct estates** authored in Anbennar (`common/estates/*.txt`; ~10 vanilla EU4 estates
Anbennar disabled — Jains, Janissaries, Rajput… — are omitted), mapped onto this model. This is the
import catalog: a nation's `set_estate_privilege = estate_<name>_…` lines name which it holds.

**Register estates — who can rule** (an aligned estate's coup installs its register, §5):

| estate | register / pole |
|---|---|
| `estate_nobles` | aristocratic (greater nobles; the Crown's estate) |
| `estate_burghers` | plutocratic (Burghers) |
| `estate_castonath_patricians` | plutocratic (a merchant-patrician city elite) |
| `estate_church` | theocratic — **regular** (magic↔science `0`) |
| `estate_mages` | theocratic — **mageocracy** pole (magic↔science `−100`) |
| `estate_artificers` | theocratic — **technocracy** pole (magic↔science `+100`) |
| `estate_nomadic_tribes` | tribal (horde / nomad) |
| `estate_monstrous_tribes` | tribal (monster races) |
| `estate_cossacks` | tribal / frontier-military |

**The Command's clans** — a militarized society's internal estates (its army castes):
`estate_wolf_command` · `boar_command` · `lion_command` · `dragon_command` · `elephant_command` ·
`tiger_command`.

**Caste system (Rahen):** `estate_uppercastes` · `middlecastes` · `lowercastes` · `raj_ministries`.

**Special / reactionary / racial:**

| estate | role |
|---|---|
| `estate_vampires` | **reactionary** — the Undead (undying, oppose change, §3) |
| `estate_adventurers` | the adventurer-company estate (SP register / `adventurer_reform`) |
| `estate_planetouched` | racial (magic-touched beings) |
| `estate_eunuchs_anb` | court / administrative |

**Culture-specific** (Haless / Sarhal / Aelantir regional estates; need loc to classify):
`estate_adeen` · `ahati` · `ajgriijarul` · `chumijemoya` · `gerunanin` · `kabiurgarko` · `khelorvalshi`
· `shinukhorchi`.

**What the catalog validates:** `estate_mages` (`−100`) and `estate_artificers` (`+100`) *are* the
theocracy magic↔science poles, so that sub-scale is fully **data-derivable, not curated**;
`estate_vampires` is the reactionary Undead estate; `estate_adventurers` backs the adventurer register;
and each ruling register has its estate (`nobles` / `burghers` / `church` / `nomadic_tribes`).

**Open:** the reactionary **Immortals** (§3) has no obvious single estate here (candidate:
`planetouched` or an elf-specific one); the eight culture-specific estates need the loc files.

## 4. The two-level model: register × sub-scale

Every register carries a **−100 … 0 … +100 sub-scale** that sets the *form* its rule takes — a second
axis, orthogonal to the register itself.

**Republic — `republican tradition`:**

| scale | form |
|---|---|
| **+100** | **elections** — the estates run a proper republic |
| **0** | **despotism** — a strongman has seized it |
| **−100** | **peasant rabble** in control, **burning all estates** — mob rule, the *destruction of the estate system itself* (the commoners turning on the estates) |

**Theocracy — `magic↔science`:**

| scale | ruling estate | form |
|---|---|---|
| **−100** | the **Mages** | **mageocracy** — mages oppose all technology and *undo* research |
| **0** | the **Religious** faction | a **regular theocracy** — mages *and* scientists coexist |
| **+100** | the **Scientists** | **technocracy** — scientists oppose all magic |

The theocracy scale doubles as a **research lever**: it gates arcane (magic) vs empirical (science)
advancement, plugging into the beaker/research-point types the tech system plans.

**Monarchy — `legitimacy`:** the **most stable and easiest to manage** register (the baseline —
republics must manage tradition, theocracies balance magic/science).

| scale | form |
|---|---|
| **+100** | max **absolutism** — a fully centralized crown; raises the **territory cap** the nation can hold |
| **0** | **interregnum** — a leaderless gap between reigns |
| **−100** | **civil war** — the nation **breaks into fiefdoms** (fragmentation: the estates/fiefs secede) |

The monarchy scale wires straight into **rank**: `+100` absolutism lifts the territory cap (room to
grow up the ladder), and `−100` civil war *is* dynamic rank's **−1 fragment** (rank doc §5) — a
kingdom shattering into its component counties/baronies.

**Tribal — `militarism`:** the war-band/horde register's axis is how the army relates to the state.

| scale | form |
|---|---|
| **+100** | fully **militarized** — the state is **run by the army** (a war-horde / stratocracy); the nominal ruler is a powerless figurehead |
| **0** | **no national army** — the tribe fields no standing host |
| **−100** | **militant pacifism** — nobody is allowed to fight |

So a horde at `+100` is the classic army-run war-machine (the Great Khan's host); at `−100` a pacifist
people. This fits the TRIBAL register's flavor (hordes, warbands, packs) — its sub-scale is martial,
where the monarchy's is dynastic and the republic's constitutional.

Both ends of a sub-scale are extreme; the healthy/balanced state sits at a pole (elections, absolutism,
militarization) or the centre (regular theocracy, no-army), by axis. So a nation's political identity is **register × sub-scale
value** — *plutocratic @ −80* is a republic collapsing into mob rule; *aristocratic @ −100* is a
kingdom in civil war fragmenting into fiefs. And the **−100 pole is a distinct collapse per register**:
republic → estates *destroyed* by the mob; theocracy → progress *undone* by mageocratic zealots;
monarchy → the realm *fragmented* by civil war; tribal → the host *disarmed* into pacifism.

So all **four registers now carry a complete sub-scale** — legitimacy (aristocratic), republican
tradition (plutocratic), magic↔science (theocratic), militarism (tribal) — each a −100…0…+100 axis,
each with its own flavor of extreme.

### Centralization — a *universal* axis (not a register sub-scale)

Distinct from the four register sub-scales (each tied to one register), **centralization** applies to
*every* polity: how **unified vs federated** it is, −100 to +100. Most salient for **empires**, where
it varies:

| scale | form | estate layer | at start |
|---|---|---|---|
| **−100** | fully **decentralized** — a loose federation of autonomous members | **none** — members are autonomous factions | the **Lake Federation** |
| **0** | **mixed** — a central authority over estate power blocs | **the estates exist** (nobles/burghers/church) | the **Empire of Anbennar** |
| **+100** | fully **centralized/absolutist** — the crown has **eliminated the estates** | **none** — estates crushed; only (powerless) factions | **Yezel Mora** |

**Estates are a *middle-band* phenomenon — both extremes dissolve them, for opposite reasons.** This is
the sharpest thing about the axis:

- At **−100** the realm is too decentralized to *have* a central estate structure — a loose federation
  whose autonomous members are its factions (rank doc §4).
- At **+100** the crown has **eliminated the estates** — absolute central control, the intermediary blocs
  crushed, only powerless factions left.
- Only in the **middle** does the estate system function (nobles/burghers/church as real power blocs).
  Estates need a *balance*: enough central authority to organize them, not so much it destroys them.

The instability differs at each end, which drives the rest of the model:

- **−100** → ambient **internal warfare** (autonomous members war among themselves — the Empire of
  Anbennar's default, §5).
- **+100** → no estate coups (no estates), but the crown is exposed to **faction revolt** — with the
  estates gone, the peasant/mob factions are the only remaining threat, the door to the `−100` mob
  pole / communism (§7). Absolutism is coup-proof but brittle to the streets.
- **middle** → the functioning estate system, coups possible but manageable.

**So all estates pull centralization toward `0`.** The middle is where the estate system is strongest and
a coup is easiest, so *every* estate — whatever its register — works to hold centralization at `0`,
resisting both a crown centralizing toward absolutism (`+100`, which would **eliminate** them) and
fragmentation toward federation (`−100`, which dissolves them into autonomous members). This makes
centralization a **contested tug-of-war**, unlike the ruler-managed sub-scales (§5): the **crown** pushes
toward `+100` (absolute power, estates crushed), the **estates** pull back toward `0` (where they can
take over). The ruler's drive to centralize *is* the drive to be rid of the estates; the estates'
resistance *is* their survival — the central political struggle of any nation.

**The struggle is fought over crownland — centralization *is* the land ledger** (§5). The crown
centralizes by **seizing land and revoking privileges** (↑ centralization, ↓ that estate's influence);
the estates resist by **holding their privileges** — their land — keeping centralization near `0`. So
**centralization, crownland, and estate influence are one ledger**: an estate's influence *is* its land
share, and both extremes are land monopolies (`+100` the crown holds everything → estates landless and
gone; `−100` the land is dispersed to autonomous members → no central estates). There is no separate
"centralization stat" to nudge — you move it only by **moving land**, which is why it is contested and
not a free lever.

- **Centralize / fragment** (rank doc §5): as centralization rises past a threshold, a **plural**
  collective (a Federation) *consolidates* into a **singular** entity (an Empire) — the +1 rung; falling
  fragments it back. So the ladder's singular/plural **parity is the discretization of this continuous
  axis** — the Lake Federation (−100) is a plural `FEDERATION`; centralizing it to Kalsyto crosses into
  a singular `EMPIRE`.

It is "important for the empires" because that is where it spreads: a one-province county is inherently
centralized, but a great empire can be unitary (Yezel Mora, +100), a federated HRE (Empire of Anbennar,
0), or so loose it registers as a plural `FEDERATION` rather than a singular `EMPIRE` (the Lake
Federation, −100).

**Legitimacy vs centralization — distinct axes.** The monarchy's `legitimacy` is *dynastic* (a rightful,
secure crown); `centralization` is *administrative* (unitary vs federated). They are **independent**: an
absolutist emperor can hold a vast *federated* realm (high legitimacy, low centralization), and a
usurper can rule a unitary one. Legitimacy raises the territory *cap* (how much a secure crown can
hold); centralization decides the *structure* of what is held. Same for the others — a republic's
tradition and its centralization move independently.

### Power projection & vassalage — a second universal axis

Like centralization, this applies to **every** nation regardless of register — the numeric form of
**sovereignty** (rank doc §3.4), one −100…+100 axis pivoting at independence (`0`):

```
−100 ──── −50 ──── −25 ──── 0 ──── +25 ──── +50 ──── +75 ──── +100
 max      may hold  indep.  INDEP.  1 rival  2 rivals  3 rivals (max)
subjug.   vassals    CB     (auto)  ◄──────── power projection ────────►
◄──────────── vassalage (grow toward freedom) ──────────►
```

**Positive half — Power Projection (PP)** — an independent nation's reach:
- Rivals unlock at `+25` / `+50` / `+75` (the EU4 cap of three).
- **PP < 25 is the "too weak to resist" band**: an independent with no rival that **shares a border** can
  be **vassalized without war**. At PP ≥ 25 (a rival backs it) it must be conquered. So PP is a
  **deterrence stat** — pushing past 25 buys you out of peaceful subjugation.
- PP grows **emergently** (EU4-style): aggressive expansion into rivals, trade dominance, subjects — not
  a set knob.

**Negative half — vassalage** — a subject's autonomy:
- On being subjugated a nation does **not** snap to −100; its start value is **emergent from the status
  quo and the peace terms** — a brutal conquest lands near −100, a negotiated submission much higher.
  `−100` is only the floor (total subjugation).
- **Above −50** a vassal may hold **its own vassals** (subinfeudation).
- **−25** unlocks an **independence CB** (win freedom by war); **0** triggers independence
  **automatically**.
- Liberty grows **emergently** from the overlord's weakness, relative size, and distance.

**A vassal is a "cultural estate" of its overlord.** Capped at the overlord's **Rank−1**, it sits as an
external **L−1 estate** (§4 recursion) *inside* the overlord, and its **loyalty** (§5) *is* its position
on this axis — loyal near `−100`, zero at `0` (it secedes). So the **`history/diplomacy` subject graph
and the estate/faction recursion are one structure**: overlord → vassal (L−1 cultural estate) →
sub-vassal (L−2), chaining down — and a vassal must be **above −50** to have a sub-vassal at all.

**A cultural estate has two exits — secede *or* usurp.** Beyond seceding (reaching `0`), a powerful,
disloyal vassal can **coup its overlord** — like any high-influence estate (§5), it seizes the parent
nation *from within*. Its aim then is not merely the throne but to **remake the nation for its whole
culture or race**: the subject's kind rising to rule, converting the realm into a nation *of* and *for*
them. So a dwarven vassal that usurps a human empire turns it into a dwarven one — a cultural/racial
takeover, the internal counterpart to secession.

**Import:** the subject graph (`vassal`/`march`/`dependency`, rank doc §3.4) places subjects on the
negative half (start value a heuristic from the relationship's terms/age); `historical_rival` counts
imply an independent's PP tier (3 rivals → PP ≥ 75). Both importable.

## 5. Influence, loyalty & coups — how power changes hands

Each estate carries **two variables**: **influence** and **loyalty**, each an **independent 0–100%+
track** (not a shared pool). Any **non-ruling** aligned estate whose **influence passes 100%** stages a
**coup**; because the tracks are independent, a nation can face **several competing coup threats at
once**.

- **Influence** is the drive to rule — the coup engine. Passing 100% installs the estate's register.
- **Loyalty** is an **estate's** loyalty **to its overlord** — the polity it belongs to. Low loyalty
  risks revolt or secession, and with high influence a **coup**; high loyalty backs the current order.
  For a **cultural estate** — a vassal (§4, *Power projection & vassalage*) — loyalty to the overlord is
  *exactly* the vassal's independence desire: loyal when freshly subjugated, zero when it breaks free.
  (Factions carry no loyalty track; they run on a simpler **unrest-pressure** mechanic, §3.)

**Influence and loyalty are outputs of crownland & privileges** — the accrual mechanic (the EU4 estate
lever). The nation holds **crownland**; granting an estate a **privilege** cedes crownland to it,
**raising its influence and loyalty** while **lowering the crown's control** (= lowering centralization,
§4 — the land ledger *is* centralization). So influence is not free-floating — it is bought with land and
rights, and the import's `set_estate_privilege` / `change_estate_land_share` lines seed the starting
grants (importable). The ruler's dilemma is the EU4 one: privileges buy an estate's loyalty (against a
coup) but raise its influence (toward one), and cost crownland either way.

**Loyalty also moves via agendas.** Each estate periodically demands a **task** — conquer a province,
convert a region, pass a reform; **completing it raises that estate's loyalty**, **ignoring it lowers**
it. So loyalty accrues two ways (privileges + agenda completion) while influence tracks land share.
Agendas are the steady drip of estate interaction between the big privilege decisions — the main way
loyalty moves turn to turn.

**Transitions follow a constrained graph, not any-to-any.** A coup changes the *register*; the register's
*sub-scale poles* are reached only by **drift within** it, never a direct coup:

- The **Religious** estate coups a nation into a **theocracy**; the **Burghers** into a **republic**; the
  **Crown** back into a **monarchy**. In a monarchy, if the Crown is *not* in control, it pushes to coup
  back into power.
- **Mages** and **Scientists** are **theocracy-internal** — you *cannot* coup a monarchy straight into a
  mageocracy. The Religious estate must first make it a theocracy; *then* the magic↔science scale slides
  toward the Mages' (`−100`) or Scientists' (`+100`) pole. So monarchy → mageocracy is a **two-step
  path** (coup to theocracy, then drift the scale), not a jump.

**A coup resets all loyalties.** When the ruling estate changes, every estate's and faction's loyalty
(including every vassal's, §4) resets to a baseline — the landscape reforms around the new regime. So a
coup is not just a register flip; it is a reshuffle of who backs whom, and a moment when a formerly-loyal
subject or estate may suddenly find itself disloyal to the new order.

**Sub-scales are ruler-managed levers.** A sub-scale value (§4) is not emergent from the influence race —
it is **steered by the ruler**, spending a **political/mana currency** (a per-category point pool, the
EU4 monarch-power shape). Managing it is the texture of playing each government: a republic *manages its
tradition* (toward elections, away from the mob), a theocracy *balances magic vs science*, a monarchy
*shores up legitimacy* — each a points sink. So the two dynamics are distinct — an
**estate coup** changes *which register* you are (discrete, from the influence race), while a **policy
lever** slides *where on its sub-scale* you sit (continuous, ruler-driven). Reactionary estates (§3)
fight *both*: they resist the coup and revert the drift.

**A coup plays out like an EU4 *disaster*, not an instant flip.** Conditions build (an estate's
influence climbing past thresholds), the disaster **fires**, and it **resolves over time** — often
through **internal warfare**: armed struggle among the nation's estates, factions, and members for
control. The aristocratic `−100` civil-war pole is the archetype.

**Centralization gates the resolution.** A **centralized** nation (§4) suppresses the disaster
quickly — the centre wins and the coup resolves clean. A **decentralized** one cannot, so a coup
**devolves into internal warfare** among its members. At the extreme this is a nation's *default state*:
the **Empire of Anbennar** (centralization `0`) is perpetually in low-grade internal conflict precisely
because it is **not centralized enough to act as one nation** — its electors and kingdoms warring among
themselves is the *baseline*, not a one-off crisis. So low centralization doesn't just weaken a realm;
it makes the coup/disaster machinery its ambient condition.

**Each estate carries its own disaster(s) and a path to rulership.** The coup is *per-estate*, not one
generic event: every estate has **one or more disasters** — its specific crisis — and a **route by which
winning it makes the estate the ruler** (installing its register, §2):

| estate | disaster | becomes ruler as |
|---|---|---|
| Nobles | feudal / succession **civil war** | aristocratic (monarchy) |
| Burghers | mercantile **revolution** | plutocratic (republic) |
| Religious / Mages / Scientists | **theocratic uprising** | theocratic (+ its magic↔science pole) |
| clan / Command / nomad estates | **war-band mutiny** | tribal (war-horde) |
| peasant **faction** | **peasant revolt** | the `−100` mob pole / communism (§7) |
| cultural estate (vassal) | **independence war** *or* **usurpation** (§4) | secede, or take the realm over for its culture/race |
| reactionary (Immortals / Undead) | **stasis lockdown** — entrench the status quo | *(rarely rules; an undead/immortal state that then opposes all change)* |

So an estate is fully specified by **(influence, loyalty, its disaster(s), its rulership path)** — and
the 31-estate catalog (§3) is, in effect, **31 disaster/rulership definitions** to author.

## 6. Engine mapping — it grafts onto existing machinery

The estate system is not built from scratch; it sits on the sim's existing class/mobility layer:

| estate concept | engine today |
|---|---|
| the **Crown** (ruling monarchy) | the colony's `Ruler` |
| the **Aristocratic estate** | the `Noble`s raised by ennoblement (`SocialMobility`) |
| greater vs lesser nobles | the estate/faction split on the noble tier |
| a **Religious estate** | a per-religion bloc (importable from province `religion`) |
| the **−100 rabble** pole | the **peasant pool** / `Laborer`s in revolt against the estates (`Retinue`) |
| **crownland vs privileges** | the ruler-vs-`Noble` split of the colony's **plots / holdings** (land already changes hands via ennoblement) |
| the **political/mana currency** (sub-scale levers) | a new per-category point pool — sibling of the beaker/research-point types the tech system plans |

So building it is extending `SocialMobility` (which already promotes/demotes across ranks) with the
per-estate **influence** and **loyalty** tracks, the crownland↔privilege land lever, and the coup
transition — rather than a new subsystem from zero. The two genuinely new resources are the crownland
budget and the political-currency pool.

## 7. Scope — post-estate modes (communism, anarchism)

The four registers are a **class-society** model: each names a *ruling estate*. Some governments abolish
the estate system itself, and those sit **outside** the register axis rather than on it — the model's
scope ends where the estates do.

**Communism** is the clearest case. It is the **−100 rabble pole stabilized**: the republic's `−100` is
already "the peasant rabble burning all estates," today a *transient* collapse. Communism is that made
**permanent and systematized** — the commoners (the peasant pool / `Laborer`s, §6) governing
collectively after abolishing the Crown and Nobles: a **classless / post-estate** order with no ruling
estate to name.

- **It keeps the other axes.** Centralization still separates the variants: Marxist-Leninist communism
  is centralized (`+100`, command economy); **anarcho-communism / anarchism** is decentralized (`−100`,
  no central authority *and* no estates). Post-estate modes span centralization like empires do.
- **It is era-gated.** The estate model is medieval/early-modern; communism is an *industrial-era*
  ideology. The `Era` dimension gates it — no communists at a 1444 start, by construction; reachable
  only after a nation advances past the estate-society eras.
- **The exit is the `−100` pole.** A register does not *become* communist; its estate system is
  *overthrown* from the `−100` pole and replaced by a classless mode. The model has a clean **door out**
  of class government rather than a forced estate slot.

The boundary is the point: the register/estate machinery specifically describes **class societies**, and
the `−100` poles are the doors out of them.

## 8. The combination matrix & transition graph

Enumerating the axes shows which combinations are real governments, which are transient, and how they
connect.

### 8.1 Government states — register × sub-scale pole

Each register uses **its own** sub-scale, so a nation sits on exactly one; the 4 × 3 poles give twelve
named states:

| register | −100 | 0 | +100 |
|---|---|---|---|
| aristocratic | **Civil War** ⚠ *(fragmenting)* | **Interregnum** ⟳ *(leaderless gap)* | **Absolute Monarchy** ✓ |
| plutocratic | **Mob Rule** ⚠ *(→ communism)* | **Despotism** ✓ *(strongman)* | **Electoral Republic** ✓ |
| theocratic | **Mageocracy** ✓ | **Theocracy** ✓ | **Technocracy** ✓ |
| tribal | **Pacifist tribe** ✓ | **Tribe** ✓ *(no army)* | **War-Horde** ✓ *(army rules)* |

✓ stable · ⟳ transitional (resolves up or down) · ⚠ collapse (transitions out)

**Only two poles are true collapses** — aristocratic `−100` (civil war → the realm *fragments*) and
plutocratic `−100` (mob rule → *exits* to communism). The theocratic and tribal `−100` poles are
extreme but *functioning* governments, not breakdowns. Interregnum is the one transitional state (a
succession gap). The other nine are stable. So the register with the *fragile* extreme is the
aristocratic/plutocratic pair (class-tension collapses); theocracy and tribal degrade gracefully.

### 8.2 Centralization — an orthogonal overlay, not new government kinds

Every state above *also* has a **centralization** value (§4), independent of register/sub-scale. It adds
no new *kind* of government — it sets whether the realm is **federated** (`−100`, a plural `FEDERATION`
by rank) or **unitary** (`+100`). So an Absolute Monarchy can be a unitary kingdom (`+100`) or a
federated empire like the HRE (`0`). Centralization multiplies each state by its *structure* without
adding unrealistic combinations; it maps to the rank ladder's parity.

### 8.3 Unrealistic / degenerate combinations

- **Collapse poles are not destinations** — civil war and mob rule are *transitions*, not places a
  nation rests; they resolve within a few steps (fragment / exit / restore).
- **No cross-register sub-scale** — a monarchy has *legitimacy*, not *magic↔science*; "absolutist
  mageocracy" is meaningless. Reaching a mageocracy from a monarchy is the two-step path (§5).
- **Fully-centralized tribal** (`+100` centralization) borders on monarchy — a unified war-horde under
  one khan is a king in all but name, and tends to reform aristocratic.

### 8.4 The transition graph

Four kinds of edge move a nation through the space:

1. **Register coup** *(a disaster — the influence race, §5)*: an estate past 100% fires a coup that
   plays out like an EU4 disaster, often resolving through **internal warfare** (fast if centralized,
   ambient if not).
   - `tribal → monarchy | republic | theocracy` — a settling tribe as the Crown / Burghers / Religious
     estate consolidates.
   - `monarchy ↔ republic ↔ theocracy` — mutual, via the corresponding estate's coup.
   - `any → tribal` — collapse/fragmentation into clans (the reversion).
2. **Sub-scale drift** *(continuous — ruler policy, §5)*: slide within the current register toward its
   poles; no register change. This is the *only* way to reach mageocracy/technocracy (in theocracy),
   elections/mob (in republic), absolutism/civil-war (in monarchy).
3. **Centralize / fragment** *(rank parity, §4/§5)*: centralization crossing a threshold flips plural
   `FEDERATION` ↔ singular `EMPIRE`. Aristocratic `−100` (civil war) **is** a fragment event.
4. **Post-estate exit** *(§7)*: from the `−100` collapse poles — plutocratic `−100` (mob) → **communism**
   (stabilized) or **anarchism** (if decentralized). A class society *leaves* the register axis.

**Resolution edges of the unstable states:**
- Interregnum → Absolute Monarchy (a new reign) · **or** → Civil War (a disputed succession).
- Civil War → fragments (rank −1) · **or** → a new dynasty restores the monarchy.
- Mob Rule → Communism (the revolt holds) · **or** → Despotism (a strongman restores order).

Reactionary estates (§3) act on **every** edge — they resist the coup, revert the drift, and block the
fragment/exit — which is what makes a reactionary-heavy nation feel frozen.

## 9. Open questions

*Resolved recent passes:* "unaligned"→**reactionary** (§3); legitimacy vs centralization **distinct**
(§4); coup topology a **constrained graph** (§5); sub-scale movement **ruler policy** in a **political
currency** (§5); **loyalty to overlord** + **coup reset** (§5); the **PP/vassalage** axis, vassals as
**cultural estates** with **secede-or-usurp** exits (§4); **influence independent per estate**, and
**centralization = crownland = the land ledger** (the tug-of-war is fought by moving land; an estate's
influence *is* its land share, §4/§5); **loyalty moves via privileges + agendas** (§5); **factions are a
distinct unrest-pressure mechanic**, not mini-estates (§3); **estate depth scales with rank** (§3). Still
open:

- **Per-estate disaster/rulership content** — the coup is per-estate, each with its disaster(s) and
  rulership path (§5); the **31-estate catalog (§3) must be authored as 31 disaster/rulership
  definitions** (trigger conditions, the internal-war resolution, the government it installs). A large
  content pass, and the natural first place `estate-system` becomes data (like the economy/scenario
  catalogs).
- **Crownland regrowth** — the land ledger *is* centralization (§4), and granting privileges cedes land;
  what lets the crown **claw land back** (reforms, conquest, time, a strong ruler) to re-centralize?
- **Faction pressure drivers** — what fills a faction's unrest gauge (over-centralization, low sub-scale,
  war exhaustion, culture/religion mismatch) and what the revolt does on eruption.
- **Tribal estates** — sub-scale settled (militarism); still open is whether tribal has formal *estates*
  (clans as L−1 blocs) or only kin-factions, and whether the ruling tribal estate at `+100` militarism
  is a distinct **Military** estate (the army having couped) that other registers can also hold.
- **Purpose vs estate** — the rank doc's Purpose axis (§3.3, a collective's why) and an estate's agenda
  may be the same lens at different scales; reconcile when both are built.
- **Start-state axis values** — which estate rules at start is `government` (importable); the starting
  *sub-scale value* (republican tradition, magic↔science, legitimacy, militarism), *centralization*, and
  *PP/vassalage* still need sources — reforms + the diplomacy/rival graphs cover much (see the mapping
  discussion), but the fine values are heuristic.
- **Reactionary/insurgent bloc presence** — which nations have an Immortal/Undead reactionary estate or
  a peasant/anarchist insurgent faction, and from what import signal (race? religion? a reform?).
