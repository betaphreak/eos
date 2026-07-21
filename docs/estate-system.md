# Design note: the estate system (internal politics, government, coups)

**Status:** Design (not built) — a north-star model, split out of
[`docs/country-rank-import.md`](country-rank-import.md) as it grew into its own subsystem. This is
the **spine**: the machinery every register shares. Each register's own mechanics live in its spoke —
[`estate-nobles.md`](estate-nobles.md) · [`estate-burghers.md`](estate-burghers.md) ·
[`estate-church.md`](estate-church.md) (also the faith layer) · [`estate-tribes.md`](estate-tribes.md).
**Date:** 2026-07-20 · **Updated:** 2026-07-21 (design-dialogue pass: republic rail inverted,
person-bound legitimacy, mission-chain disasters, register-typed privileges & estate armies,
despot-exit ladder & runtime religions, divergent research poles; then split into spine + four
register spokes)
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
| theocracy | **Religious** / **Mages** / **Artificers** — by the magic↔science scale (`estate-church.md`) | theocratic |
| tribal / native | clan & kin structures | tribal |

So a country's register is not a fixed stamp — it is *whoever currently holds power*, and a coup (§5)
changes it. Register is **dynamic**, the mirror of dynamic rank (rank doc §5): rank changes by
centralize/fragment, register by an estate winning the influence race.

## 3. Estates vs factions, concretely

The mechanical version of the rank doc's "member vs faction":

- **Greater nobles** → the **Aristocratic estate**. **Lesser nobles** → **factions** (L−2 loose blocs).
- The **state religion's church is an estate by default**; other faiths are **factions**, promoted
  to a **Religious estate** at **>1 province** — and a state religion holding *no* province yields no
  church estate at all (the heterodox rule). The full religion rules, and the whole **faith layer**,
  live in [`estate-church.md`](estate-church.md).

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

**Theocracies carry extra administrative/commercial estates** — a **bureaucratic** and/or
**patrician** estate beyond the ruling church, each with its own influence/loyalty; their detail (and
their generative origin from a dead god-emperor's machine) is in
[`estate-church.md`](estate-church.md) §1.

**Estates and factions are two *different* systems** that share the ladder's positions — different
*mechanics* (an estate runs influence/loyalty/coups; a faction runs an unrest gauge), not one object at
two sizes. But they are **convertible phases of the same bloc**: an estate **fractures into factions** at
a leader's death, and factions **coalesce back into an estate** when a majority share a goal — the same
nobles can be one estate today and a scatter of factions after a bad succession (the reversible transition
is detailed in §5).

**Estates (L−1)** carry the full political machinery (influence + loyalty, privileges, agendas, coups,
§5) and their agenda is to **rule**:
- **Aligned** — back a government type and, given influence, **install it** (§5): the Crown → monarchy,
  the Burghers → republic, the Religious estate → theocracy.
- **Reactionary** — seek no *register* of their own (they install no government type), but they are **not**
  a symmetric "oppose all change" bloc: each is a specific long-lived faction that **promoted to an
  estate** and drives **centralization** (§4) toward its own pole. The **Undead** (liches — the
  **Vampires** estate, e.g. in a lich-ruled realm) push toward **`+100` centralization** (absolutism, the
  crown crushing the estates); the **Immortals** (long-lived lineages — the **Khets** estate, in
  Kheterata) push toward **`−100`** (a decentralized federation). So the two reactionary poles are
  *opposite*, not aligned. What they share is durability — eternal enough to have outlived every reform,
  they entrench their preferred structure and resist being moved off it, making a nation with a strong
  reactionary estate hard to shift *away from that estate's pole*.

**Every estate is era-bound.** Each estate has a **tech that unlocks it** (its birth) and a **tech
from which it starts its opposition** (its horizon — from there it resists further advancement). The
**patricians** are a *Classical* estate: they oppose anything past **Meritocracy**; the **tribes**
oppose the Medieval package, and their full arc — opposition, last stand, the Feudalism flip — is the
first fully-specified instance ([`estate-tribes.md`](estate-tribes.md) §7). The **reactionaries**
(below) fit the frame as the degenerate case: ancient estates whose opposition never ends. So a
nation's **estate roster is tech-derived** — which estates exist follows from which unlock techs its
society has passed — and the import catalog's content record gains two tech references per estate.

**And the bound binds the ruler: no nation advances past its ruling estate's opposition horizon** —
so **every era transition requires a power transition**. To pass Meritocracy you must first break the
patricians; to enter the Medieval era, the tribes; to industrialize, one day, the nobles. The tech
tree is a **political gauntlet** — history alternates technical and social revolutions — and the coup
machinery (§5) is the research mechanic's other half. (The magocracy's frozen trunk,
`estate-church.md` §2, is this law applied to a ruling Mages estate.)

**Factions (L−2)** are a **distinct, simpler mechanic** — not influence/loyalty/coups but an
**unrest-pressure gauge** that, when it boils over, erupts as a **revolt** toward the faction's aim: a
**peasant faction** revolts toward the `−100` mob pole / communism (§7); an **anarchist faction** revolts
to **decentralize**. So estates seek to *rule* (from above); factions are the pressure that builds
*beneath* them and occasionally erupts. The gauge fills **emergently** from the nation's state — not
an authored trigger list — and what a revolt does on eruption is likewise emergent from conditions.

**Estate depth scales with rank.** The full system is not equally present at every scale: an adventurer
company (`CARAVAN`/`CITY`, pre-estate — its politics are internal party-factions) has almost none; a
`COUNTY` has the three major estates but simple politics; only a large, centralized nation
(`KINGDOM`/`EMPIRE`) runs the whole estate/centralization/coup struggle. For the player (rank-windowed
SP), the estate layer **turns on** as they climb into a real nation.

**The player can also inhabit the system *from below*, not only as the sovereign.** Besides the obvious
seat — playing an **independent nation** (you *are* the Crown, managing sub-scales and buying loyalty) —
a player can play as a **faction leader (L−2)** or an **estate leader (L−1) inside someone else's
nation**, working the machinery from within: an estate leader building influence toward a **coup** (§5),
a faction leader building unrest toward a **revolt** (§3), or — atop the religious recursion — the
**head of a faith** (§4): a pontiff running a transnational theocracy against its own heresies. This fits the rank window (a rank-1/rank-2 body
sitting inside a higher-rank polity) and makes the estate layer a *playable interior*, not just an
AI-run backdrop to the sovereign seat. The window also bounds *how* the interior is played: a
low-rank player interacts with the nation only **through its local organs** at the window's altitude —
the count's court and the province's estates, never the distant king directly — and the game
experience changes as rank climbs.

**The first instance of this playable interior is Adventurer faction/estate gameplay.** The SP adventurer
company (the lowest-rank start, §"estate depth" above) is played as a **faction/estate operating within
or against a nation** before it ever becomes a sovereign — the earliest, smallest form of the inside-job
loop, and the on-ramp by which a player first meets the estate machinery from below.

### The canonical Anbennar estates (import catalog)

The **31 distinct estates** authored in Anbennar (`common/estates/*.txt`; ~10 vanilla EU4 estates
Anbennar disabled — Jains, Janissaries, Rajput… — are omitted), mapped onto this model. This is the
import catalog: a nation's `set_estate_privilege = estate_<name>_…` lines name which it holds.

**Register estates — who can rule** (an aligned estate's coup installs its register, §5):

| estate | register / pole |
|---|---|
| `estate_nobles` | aristocratic (greater nobles; the Crown's estate) |
| `estate_burghers` | plutocratic (Burghers) |
| `estate_castonath_patricians` | plutocratic (a merchant-patrician city elite; the **patrician estate**, incl. within theocracies, §3) |
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
| `estate_vampires` | **reactionary — Undead** (a lich/undead faction promoted to an estate; drives centralization → **`+100`**, §3) |
| `estate_adventurers` | the adventurer-company estate (SP register / `adventurer_reform`) |
| `estate_planetouched` | racial (magic-touched beings) |
| `estate_eunuchs_anb` | court / administrative — the **bureaucratic estate** (esp. in theocracies, §3) |

**Culture-specific** (Haless / Sarhal / Aelantir regional estates; need loc to classify):
`estate_adeen` · `ahati` · `ajgriijarul` · `chumijemoya` · `gerunanin` · `kabiurgarko` · `khelorvalshi`
· `shinukhorchi`.

**What the catalog validates:** `estate_mages` (`−100`) and `estate_artificers` (`+100`) *are* the
theocracy magic↔science poles, so that sub-scale is fully **data-derivable, not curated**;
`estate_vampires` is the reactionary Undead estate (centralization `+100` pole); `estate_adventurers`
backs the adventurer register; and each ruling register has its estate (`nobles` / `burghers` / `church`
/ `nomadic_tribes`).

**Reactionaries are specific promoted factions, not a generic tag** — the Undead as `estate_vampires`
(imported from Anbennar), and the **Immortals** as the **Khets** (in **Kheterata**). Note the asymmetry:
**the Khets are *not* an Anbennar estate** — there is no import slug for them, so the Immortal reactionary
would be **authored fresh in CivStudio** as the counterpart to the imported Undead one. The two drive
centralization to *opposite* poles (Undead → `+100`, Immortals → `−100`, §3), so they are not one
interchangeable "reactionary" slot.

## 4. The two-level model: register × sub-scale

Every register carries a **−100 … 0 … +100 sub-scale** that sets the *form* its rule takes — a second
axis, orthogonal to the register itself.

Each register's sub-scale — its bands, couplings and flavor — lives in its **spoke doc**; the
comparative summary:

| register | sub-scale | **−100** | **0** | **+100** | spoke |
|---|---|---|---|---|---|
| aristocratic | `legitimacy` *(the reigning person's stat)* | **civil war** — fragments into fiefdoms | fresh/contested reign — where successions land | **absolutism** — territory cap ↑ | [`estate-nobles.md`](estate-nobles.md) |
| plutocratic | `republican tradition` | **mob rule** (`−50…−100`) | **despotism** band (`0…−50`) — the despot & his exit ladder | **elections / parliament** *(in 1.0)* | [`estate-burghers.md`](estate-burghers.md) |
| theocratic | `magic↔science` | **mageocracy** — common trunk frozen, magic advances | regular theocracy | **technocracy** — the Clockpunk Era | [`estate-church.md`](estate-church.md) |
| tribal | `militarism` | **militant pacifism** | no national army | **war-horde** — the army rules, sub-tribes assimilated | [`estate-tribes.md`](estate-tribes.md) |

Three couplings stay visible at spine level: the republic's tradition is **protected by
centralization** (above `0` it cannot go negative; the three bands and the `>25 / >50 / >75`
despot-exit ladder are in the burghers spoke); the aristocratic `−100` civil war **is** dynamic
rank's **−1 fragment**; and the theocratic scale is a **research-direction dial** (divergent
specializations off the common tech trunk, no regression — the church spoke).

Both ends of a sub-scale are extreme; the healthy/balanced state sits at a pole (elections, absolutism,
militarization) or the centre (regular theocracy, no-army), by axis. So a nation's political identity is **register × sub-scale
value** — *plutocratic @ −80* is a republic collapsing into mob rule; *aristocratic @ −100* is a
kingdom in civil war fragmenting into fiefs. And the **−100 pole is a distinct collapse per register**:
republic → estates *destroyed* by the mob; theocracy → the common trunk *frozen* by mageocratic
zealots; monarchy → the realm *fragmented* by civil war; tribal → the host *disarmed* into pacifism.

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

*(The **reactionary** estates are the exception, §3: the Undead/Vampires side *with* the crown toward
`+100`, the Immortals/Khets *with* fragmentation toward `−100` — they seek no register and so don't need
the functioning middle, entrenching a pole instead. "All estates pull to `0`" holds for the aligned
register estates, whose survival depends on the middle.)*

**The struggle is fought over crown control — centralization *is* the control ledger** (§5). The
crown centralizes by **revoking privileges and seizing what they granted** (↑ centralization, ↓ that
estate's influence); the estates resist by **holding their privileges**, keeping centralization near
`0`. So **centralization, crown control, and estate influence are one ledger** — with each register
accruing on **its own base** (the Nobles' land, the Burghers' capital, the Church's adherents and
orders, the sub-tribes' levies; the register-typed table, §5). Land is the aristocratic case and the
historical anchor, but the extremes generalize: at `+100` the crown holds every lever → estates
dispossessed and gone; at `−100` control is dispersed to autonomous members → no central estates.
There is no separate "centralization stat" to nudge — you move it only by **moving privileges and the
assets behind them**, which is why it is contested and not a free lever.

- **Centralize / fragment** (rank doc §5): as centralization rises past a threshold, a **plural**
  collective (a Federation) *consolidates* into a **singular** entity (an Empire) — the +1 rung; falling
  fragments it back. So the ladder's singular/plural **parity is the discretization of this continuous
  axis** — the Lake Federation (−100) is a plural `FEDERATION`; centralizing it to Kalsyto crosses into
  a singular `EMPIRE`.

It is "important for the empires" because that is where it spreads: a one-province county is inherently
centralized, but a great empire can be unitary (Yezel Mora, +100), a federated HRE (Empire of Anbennar,
0), or so loose it registers as a plural `FEDERATION` rather than a singular `EMPIRE` (the Lake
Federation, −100).

**The healthy centralization is rank-scaled — there is no universal "good" value.** The same `0` that is
a *functioning* middle for a duchy is *ambient internal war* for an empire: a larger polity needs **more**
centralization just to hold together. So what matters is not the absolute value but the **centralization
deficit** = `needed(rank) − actual` — a realm slides into low-grade internal warfare once its deficit
exceeds a margin. The Empire of Anbennar sits in perpetual low-grade conflict at `0` because at *empire*
rank `0` falls well short of what it needs (§5); a county at `0` is fine. A consequence: **climbing a
rank raises the bar** — conquer your way up to `EMPIRE` and you drop into ambient war unless you had
already centralized ahead of the promotion, so **expansion destabilizes until you re-centralize**. (The
named nations pinned to −100/0/+100 above are illustrative points, not fixed anchors — the model is the
deficit, not the absolute number.)

**Legitimacy vs centralization — distinct axes, but centralization has stability rails.** The monarchy's
`legitimacy` is *dynastic* (a rightful, secure crown); `centralization` is *administrative* (unitary vs
federated). They are **distinct dimensions** — you can vary one while holding the other, so a legitimate
crown can rule a somewhat federated realm and a usurper a unitary one; legitimacy raises the territory
*cap* (how much a secure crown can hold), centralization sets the *structure* of what is held. But
"distinct" does **not** mean uncoupled from *stability*: each register has a **centralization comfort
side**, and crossing the **`−25` rail** the wrong way destabilizes it into its collapse mode, **opposite
in sign per register**:

- a **monarchy** pushed **under `−25`** (too federated) becomes **disbandable**: dissolved into its
  fiefs, the fragment / civil-war direction (§8.1);
- a **theocracy** pushed **under `−25`** can be **subverted by its own internal estates**: the
  **mages** capturing the magic↔science sub-scale toward `−100` (mageocracy) or the **artificers**
  toward `+100` (technocracy). The weak centre can no longer hold the *regular* theocracy at `0`, so a
  pole faction seizes the scale (`estate-church.md`; the §5 two-step path);
- a **tribal** register's **estates *are* sub-tribes** (formal L−1 blocs, not mere kin-factions), so
  pushed **under `−25`** those constituent **tribes break free** (secede into independent tribes — the
  tribal analog of a monarchy disbanding into fiefs);
- a **republic** pushed **under `−25`** sees the **despotism mechanic fire** — a strongman seizes the
  weak centre (the three tradition bands, `estate-burghers.md` §2).

So the axes are independent as *coordinates*, but **all four registers share the same hard stability
rail: centralization `≥ −25`** — each simply fails in its own register-flavored way below it (disband /
subvert / break-free / despotize). Centralization *protects* every register, the republic included.

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

**Secede-or-usurp is a universal law of the model**: every subordinate bloc has the same two exits —
a vassal secedes or usurps its overlord, factions coalesce to capture (or abandon) their estate
(§3/§5), a **heresy** captures its faith's head (*Religions are runtime objects*, below) — so any new
bloc type arrives with its exit mechanics pre-decided.

**Import:** the subject graph (`vassal`/`march`/`dependency`, rank doc §3.4) places subjects on the
negative half (start value a heuristic from the relationship's terms/age); `historical_rival` counts
imply an independent's PP tier (3 rivals → PP ≥ 75). Both importable.

### Religions are runtime objects — see `estate-church.md`

The religion catalog is **not static** — god-emperor **cults** and heresy **schisms** create
religions at runtime, and a faith spanning nations is itself a **polity on the recursion**: an L+1
transnational theocracy, centralization **capped at `−75`**, whose permanent `needed(rank)` deficit
**breeds its heresies** (the Regent Court at ~`−90` → Corinite). It has a holy-site capital, a real
ruler as its head (a **playable seat** — the pontiff), and wealth-fed **decadence**. The full faith
layer lives in [`estate-church.md`](estate-church.md) §5; the **dynamic religion catalog** it
requires is tracked in §6.

## 5. Influence, loyalty & coups — how power changes hands

**Every estate and faction has a leader who is a real `Person`** in the simulation — with mortality,
dynasty, skills and passions (the `name`/`skill`/`mortality` layer, §6). The leader is the coup's or
revolt's *protagonist*: an ambitious high-MIL noble is who *fires* the aristocratic disaster, the
over-re-elected president is literally the man who becomes the despot (`estate-burghers.md`). So the tracks below attach to
a **named character**, not an abstract bloc — the estate layer rides the people the sim already models.

**Leaders die — and an estate can die with them.** Mortality is always on, so an estate's leader
eventually dies. The default is **succession**: a new leader steps up and **inherits the estate's
influence and loyalty tracks**, the bloc continuing under a new face. But a leader's death is also a
**fracture point** — an estate can **break apart and split into factions** (its L−1 coherence lost,
devolving into the looser L−2 bodies of §3). So a strong leader is part of what *holds an estate
together*: lose him at the wrong moment and the aristocratic estate can shatter into rival noble factions
rather than pass cleanly to an heir. (This is the estate-level echo of the despot problem — a personalist
regime is only as durable as the person.)

**And factions can re-coalesce into an estate.** The fracture is not permanent. The shattered bloc
becomes several factions, each carrying a goal, and if a **majority of those factions share the same
goal** they **merge into a new estate** — with **loyalty and influence reset to baseline**. So a noble
estate that breaks into a dozen squabbling noble factions **re-forms as a fresh aristocratic estate** the
moment a majority of them align on a common aim. Estate ⇄ faction is therefore a **reversible phase
transition**: **fracture** (estate → factions) at a leader's death, **coalescence** (factions → estate)
on majority goal-consensus — a second promotion path alongside the territorial one (a religion crossing
`>1` province, §3).

Each estate carries **two variables**: **influence** and **loyalty**, each an **independent 0–100%+
track** (not a shared pool). The coup trigger is **two-variable, EU4-style**: a **non-ruling** aligned
estate stages a **coup** when its **influence is high _and_ its loyalty is low** — a strong _and_
disloyal estate, not merely a dominant one (a high-influence but *loyal* estate backs the current order
rather than seizing it). Because the tracks are independent per estate, a nation can face **several
competing coup threats at once**.

- **Influence** is the estate's *strength* — the coup's fuel, not its trigger alone: a coup needs high
  influence **paired with low loyalty** (above). When the coup fires, it installs the estate's register.
- **Loyalty** is an **estate's** loyalty **to its overlord** — the polity it belongs to. Low loyalty
  risks revolt or secession, and with high influence a **coup**; high loyalty backs the current order.
  For a **cultural estate** — a vassal (§4, *Power projection & vassalage*) — loyalty to the overlord is
  *exactly* the vassal's independence desire: loyal when freshly subjugated, zero when it breaks free.
  (Factions carry no loyalty track; they run on a simpler **unrest-pressure** mechanic, §3.)

**Influence and loyalty are outputs of privileges — and privileges are register-typed.** The nation
holds **crownland** (more broadly, *crown control*); granting an estate a **privilege** cedes control
to it, **raising its influence and loyalty** while **lowering centralization** (§4 — the control
ledger *is* centralization). But each register accrues on **its own power base**, and is granted (and
fought) in **its own currency**:

| estate | influence base | privilege currency | the crown's counter |
|---|---|---|---|
| Nobles | **land share** (plots/holdings) | land grants | confiscation |
| Burghers | **capital share** — firm equity + bank deposits (quantities the engine already computes) | charters, monopolies | revoke charters, debase, tax |
| Church | **adherent/province share + holy-order strength** | tithes, temple rights, order charters | seize temples, dissolve orders |
| tribal sub-tribes | **levy / manpower** (paramilitary hosts) | host & levy rights | disband hosts |

The import's `set_estate_privilege` / `change_estate_land_share` lines seed the starting grants
(importable). The ruler's dilemma is the EU4 one: privileges buy an estate's loyalty (against a coup)
but raise its influence (toward one), and cost crown control either way. **Loyalty seeks an
equilibrium set by the standing privileges** — it drifts toward that level and moves off it through
overlord interactions (grants, seizures, agenda outcomes), so total neglect is never a stable
strategy — while **influence is a stock** on the register's base. The crown claws control back the
same ways it ceded it: **confiscation** (revoking privileges / seizing the assets behind them),
**diplomacy**, **mission rewards**, or **military force**.

**The right to bear an army is itself a privilege — and estate armies fight the internal wars.** A
noble **levy right**, a sub-tribe's **host**, a holy order's **charter**, a battlemage order — each is
a register-typed privilege: granting it buys loyalty and raises influence (the military base above)
while draining centralization; **revoking it is the disarmament crisis** (dissolving the Templars,
disbanding the samurai). Below `0` centralization the sub-tribes hold armies *emergently* — the crown
is simply too weak to revoke. These estate forces exist **in parallel to the national army**, and they
are the **combatants of internal warfare**: a coup resolves in real force ratios (estate hosts, led by
the leader's MIL, against the crown's army) — so an estate arming itself is *visible* before it
strikes.

**Loyalty also moves via agendas — and agendas share one task mechanic with disasters.** Each estate
periodically demands a **task** — conquer a province, convert a region, pass a reform; **completing it
raises that estate's loyalty**, **ignoring it lowers** it. So loyalty accrues two ways (privileges +
agenda completion) while influence tracks the register's base. Agendas are the steady drip of estate
interaction between the big privilege decisions — the main way loyalty moves turn to turn. Agendas and
**disaster mission chains** (below) are **one task mechanic with two issuers** — an estate issuing
small optional tasks, a crisis issuing a forced chain — one subsystem to build and one content type to
author.

**Council seats are a loyalty lever too.** The ruler's council (the shipped Privy-Council/advisor
rail) is filled by **appointment**: seating an estate's leader courts that estate; passing them over
is a snub that costs its loyalty. **Each seat is fed by the estate whose influence base matches its
domain** (the six `tech.Advisor` branches):

| seat | natural estate |
|---|---|
| Military | the **largest private-army holder** — the Nobles' marshal, a Command clan, a holy order's grandmaster, a battlemage order |
| Economy | the **Burghers**; the patrician estate in theocracies |
| Religion | the **state church**; in a heterodox nation (§3) the strongest religious *faction* — the seat that picks the next state religion |
| Science | the **Mages / Artificers** — the seat that steers the national research focus (§4) |
| Culture | the **racial/cultural estates** (planetouched, castes, a vassal's representative — an integration gesture) |
| Growth | the **Nobles' steward** or the **bureaucratic estate** (a temple-state's eunuch administration) |

*(Of the shipped four seats: Foreign stays skill-based or takes a cultural-estate rep; Globe belongs
to `estate_adventurers` — the explorers' chair.)*

**Seats fill in a cascade: estates → factions → the ruler's captains.** If no estate supplies a
domain's candidate, the seat draws from the **faction** leaders; if no faction either, from the
**ruler's own captains** — his household officers, today's skill-based roster. Political weight falls
with each tier: an estate leader's seat is a full loyalty lever, a faction leader's a courtship of the
unrest gauge, a captain politically neutral (loyal by construction, moving nothing). So the council
works at every rank depth — a small colony's court is all captains (exactly the shipped behavior), and
the estate layer takes the chairs over as the nation grows into them. With more blocs than chairs, the
council's composition is a **standing public statement of which estates are in favor**.

The exception is an **underage or missing ruler** — in a regency the estate leaders **seat
themselves** (the strongest bloc of each domain) and **extract, CK-style**: influence gains,
self-granted privileges, leaking crownland. A minority succession is a genuine danger window, and a
come-of-age ruler's first work is clawing back what the council ate.

**Transitions follow a constrained graph, not any-to-any.** A coup changes the *register*; the register's
*sub-scale poles* are reached only by **drift within** it, never a direct coup:

- The **Religious** estate coups a nation into a **theocracy**; the **Burghers** into a **republic**; the
  **Crown** back into a **monarchy**. In a monarchy, if the Crown is *not* in control, it pushes to coup
  back into power.
- **Mages** and **Artificers** are **theocracy-internal** — you *cannot* coup a monarchy straight into a
  mageocracy. The Religious estate must first make it a theocracy; *then* the magic↔science scale slides
  toward the Mages' (`−100`) or Artificers' (`+100`) pole. So monarchy → mageocracy is a **two-step
  path** (coup to theocracy, then drift the scale), not a jump.

**A coup resets *internal* loyalties — and is an *opening* for everyone else.** When the ruling estate
changes, the nation's **internal estates** reset their loyalty to a baseline as the landscape reforms
around the new regime. But the reset is **not universal**:

- **Cultural estates (vassals, §4) do not reset.** A coup is a moment of overlord weakness they
  *exploit*: gaining an independence **CB**, or breaking free outright. Their accumulated liberty is
  preserved across the regime change, not clawed back.
- **Factions reset *by kind*.** An **adventurer**-type faction genuinely resets; a **peasant** (or other
  insurgent) faction does *not* — like a vassal, it treats the coup as an opening and its unrest spikes.

So a coup is not just a register flip; it is a reshuffle of who backs whom **at the centre**, while the
periphery (disloyal vassals, restless peasants) reads it as the moment to strike.

**Sub-scales are ruler-managed levers.** A sub-scale value (§4) is not emergent from the influence race —
it is **steered by the ruler**, spending a **political/mana currency** (a per-category point pool, the
EU4 monarch-power shape). Managing it is the texture of playing each government: a republic *manages its
tradition* (toward elections, away from the mob), a theocracy *balances magic vs science*, a monarchy
*shores up legitimacy* — each a points sink. So the two dynamics are distinct — an
**estate coup** changes *which register* you are (discrete, from the influence race), while a **policy
lever** slides *where on its sub-scale* you sit (continuous, ruler-driven). Reactionary estates (§3)
fight *both*: they resist the coup and revert the drift.

**A coup plays out like an EU4 *disaster*, not an instant flip.** Conditions build (an estate's
influence climbing past thresholds), the disaster **fires**, and it **resolves through its mission
chain** — a forced sequence of tasks (the same task mechanic as agendas, above) the protagonists work
through — often via **internal warfare**: armed struggle among the nation's estates, factions, and
members, fought by the **estate armies** (above) against the crown's. The aristocratic `−100`
civil-war pole is the archetype.

**A fired disaster cannot sit forever — a second coup escalates it.** If a **second coup fires from a
different cause** while one is in progress, the crisis breaks the nation's frame: with centralization
**non-negative** it becomes a full **civil war** (fought out); with centralization **negative** the
nation **implodes** — every estate becomes an independent nation, the fragment edge fired for all of
them at once.

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
| an estate/faction **leader** | a real `Person` (the `name`/`skill`/`mortality` layer) — the estate's protagonist, §5 |
| a **Religious estate** | a per-religion bloc (importable from province `religion`) |
| the **−100 rabble** pole | the **peasant pool** / `Laborer`s in revolt against the estates (`Retinue`) |
| **crownland vs privileges** | the ruler-vs-`Noble` split of the colony's **plots / holdings** — **every plot's owner belongs to an estate or faction**, so the land ledger *is* which bloc each plot's owner sits in. **Ennoblement already moves land**: promoting a laborer shifts that person's plots from the commoner pool into the aristocratic estate, so `SocialMobility` is *already* a centralization lever (each promotion/demotion nudges the land share, §4) |
| the **political/mana currency** (sub-scale levers) | a new per-category point pool — sibling of the beaker/research-point types the tech system plans |
| the **Burghers' influence base** (capital share, §5) | **already computed** — firm equity + bank deposits (the banking layer's equity seam) |
| **council seats** (appointment, §5) | the shipped **Privy Council / advisor rail** — seats become estate leaders, loyalty/influence on the character sheet |

So building it is extending `SocialMobility` (which already promotes/demotes across ranks) with the
per-estate **influence** and **loyalty** tracks, the crownland↔privilege land lever, and the coup
transition — rather than a new subsystem from zero. The two genuinely new resources are the crownland
budget and the political-currency pool.

**Two-speed simulation.** Nations run on a **coarse monthly tick** — influence/loyalty drift, agenda
rolls, disaster checks, centralization moves — while embedded settlements keep the **daily** step. The
estate layer is a monthly overlay on the daily economy, so 1454 nations stay cheap beside the per-day
colonies.

**Build order — what's buildable now vs. what it waits on.** The layers do *not* all land at once:

- **Buildable today (no new resources):** the **religion-derived layer** — every religion present in a
  nation is a **faction**, and each religion holding **>1 province** promotes to **one Religious estate**.
  This reads straight off `provinces.json` `religion` (§3, "the one part importable today") and needs
  none of the machinery below. It is the natural first slice — reference structure, like the economy /
  scenario / trade-good catalogs. The **faith entities** (`estate-church.md`) are computable the same way:
  provinces-per-religion + border adjacency yield each transnational faith-polity, its rank, and its
  heresy-pressure (deficit) map — the whole religious geography of 1444, before any runtime exists.
- **Not yet built — hard prerequisites for the coup loop:** the **`Era`** dimension (gates communism, §7),
  the **political/mana currency** pool (the sub-scale levers, §5), the **crownland budget** (the
  influence/loyalty ledger, §4/§5), the **task/mission subsystem** (agendas + disaster chains, one
  mechanic, §5), and the **dynamic religion catalog** (runtime cults & heresies, §4). The full
  influence → loyalty → coup machine can't run until these exist, so they sequence *before* it — the
  religion-derived layer is shippable independently of them.

## 7. Scope — post-estate modes (communism, anarchism)

The four registers are a **class-society** model: each names a *ruling estate*. Some governments abolish
the estate system itself, and those sit **outside** the register axis rather than on it — the model's
scope ends where the estates do.

> **1.0 scope note.** **Communism** is an **industrial-era** ideology (era-gated below), **out of scope
> for 1.0**. **Parliaments are *in* 1.0** — medieval parliaments are real (England's from 1265, the
> Cortes, the Estates-General, the Althing), so the republic's electoral/`+100` pole ships. Only the
> *post-estate* industrial modes (communism / anarchism) are deferred — the rest of this section
> documents the shape of that deferred exit.

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
| aristocratic | **Civil War** ⚠ *(fragmenting)* | **Fresh/contested reign** ⟳ *(where successions land)* | **Absolute Monarchy** ✓ |
| plutocratic | **Mob Rule** ⚠ *(`−50…−100` → communism ⌁)* | **Despotism** ✓ *(strongman; band `0…−50`)* | **Electoral Republic** ✓ *(parliament; in 1.0)* |
| theocratic | **Mageocracy** ✓ | **Theocracy** ✓ | **Technocracy** ✓ |
| tribal | **Pacifist tribe** ✓ | **Tribe** ✓ *(no army)* | **War-Horde** ✓ *(army rules)* |

✓ stable · ⟳ transitional (resolves up or down) · ⚠ collapse (transitions out) · ⌁ industrial-era, **post-1.0** (§7): communism / anarchism only (the electoral/parliament pole **ships in 1.0**)

**Only two poles are true collapses** — aristocratic `−100` (civil war → the realm *fragments*) and
plutocratic `−100` (mob rule → *exits* to communism). The theocratic and tribal `−100` poles are
extreme but *functioning* governments, not breakdowns. The aristocratic `0` reign-state is the one
transitional (where successions land; an interregnum reads as `0`, §4). The other nine are stable. So the register with the *fragile* extreme is the
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

Five kinds of edge move a nation through the space:

1. **Register coup** *(a disaster — the influence race, §5)*: a **strong _and_ disloyal** estate (high
   influence, low loyalty) fires a coup that plays out like an EU4 disaster, often resolving through
   **internal warfare** (fast if centralized, ambient if not).
   - `tribal → monarchy | republic | theocracy` — a settling tribe as the Crown / Burghers / Religious
     estate consolidates.
   - `monarchy ↔ republic ↔ theocracy` — mutual, via the corresponding estate's coup.
   - `any → tribal` — collapse/fragmentation into clans (the reversion).
2. **Sub-scale drift** *(continuous — ruler policy, §5)*: slide within the current register toward its
   poles; no register change. This is the *only* way to reach mageocracy/technocracy (in theocracy),
   elections/mob (in republic), absolutism/civil-war (in monarchy).
3. **Centralize / fragment** *(rank parity, §4/§5)*: centralization crossing a threshold flips plural
   `FEDERATION` ↔ singular `EMPIRE`. Aristocratic `−100` (civil war) **is** a fragment event, and a
   **monarchy under `−25`** centralization becomes **disbandable** (the register `−25` rail, §4 —
   shared by all four registers, each failing its own way below it).
4. **Post-estate exit** *(§7)*: from the `−100` collapse poles — plutocratic `−100` (mob) → **communism**
   (stabilized) or **anarchism** (if decentralized). A class society *leaves* the register axis.
5. **Despot conversion** *(ruler-initiated — §4)*: a **despot** (a republic's over-re-elected president,
   now the plutocratic estate's personalist ruler) **disbands his own estate and sides with another**,
   flipping the register **from the top** on a **ladder keyed to centralization** — above `25`,
   `republic → monarchy` (crowns himself king, siding with the aristocratic estate); above `50`, an
   undead/immortal despot proclaims a `theocracy`; above `75`, founds a **god-emperor cult** as a new
   religion (a mortal founder's cult survives him — his old estate institutionalizes as the
   theocracy's bureaucrats/patricians; `estate-burghers.md` §3, `estate-church.md` §1). Same edge as
   a register coup but driven by the *ruler*, not an influence-race disaster.
6. **Tech flip** *(era-driven — `estate-tribes.md` §7)*: research changes the political structure.
   Completing **Feudalism** flips a nation's **tribes estate** into fealty-pledged **noble
   factions**, and flips a **tribal-register nation into a despotism** — the khan-turned-strongman
   landing in the plutocratic despotism band, from which edge 5's ladder carries it onward (so
   `tribal → monarchy` is two steps through the despot). Entering the Medieval era
   (`TECH_MEDIEVAL_LIFESTYLE`) first fires the tribes' **regressive last stand** — undo the Medieval
   techs, raze the Vassalage/Feudalism-line buildings.

**The `−25` centralization rail governs all four registers the same way** (§4): a **monarchy under
`−25`** is disbandable into fiefs (edge 3), a **tribal register under `−25`** sees its sub-tribe
estates **break free** (edge 3, secession), a **theocracy under `−25`** is subvertible by its
mages/artificers toward a magic↔science pole (edge 2's drift, forced by weak centre), and a **republic
under `−25`** sees the despotism mechanic fire (edge 2 → then possibly edge 5). All four destabilize
when *too federated*; centralization above the rail — and for a republic, above `0` — is protective.

**Resolution edges of the unstable states:**
- Fresh/contested reign (`0`) → a consolidated monarchy (legitimacy built) · **or** → Civil War (a
  disputed succession).
- Civil War → fragments (rank −1) · **or** → a new dynasty restores the monarchy.
- Mob Rule → Communism (the revolt holds) · **or** → Despotism (a strongman restores order).

Reactionary estates (§3) resist being moved **off their own pole** — the Undead/Vampires entrench
`+100` centralization, the Immortals/Khets `−100` — resisting the coups, drift and fragment/exit that
would shift the nation away from where they hold it, which is what makes a reactionary-heavy nation feel
frozen *at that pole*.

## 9. Open questions

*Resolved recent passes:* "unaligned"→**reactionary** (§3); legitimacy vs centralization **distinct**
(§4); coup topology a **constrained graph** (§5); sub-scale movement **ruler policy** in a **political
currency** (§5); the **PP/vassalage** axis, vassals as **cultural estates** with **secede-or-usurp**
exits (§4); **influence independent per estate**, and **centralization = crownland = the land ledger**
(the tug-of-war is fought by moving land; an estate's influence is *driven by* its land share, §4/§5);
**loyalty moves via privileges + agendas** (§5); **factions are a distinct unrest-pressure mechanic**,
not mini-estates (§3); **estate depth scales with rank** (§3); the **coup trigger is two-variable** —
high influence *and* low loyalty, not influence alone (§5); the **coup reset is internal-estates-only** —
vassals exploit the opening, factions reset by kind (adventurer yes, peasant no) (§5); **centralization
is rank-scaled** — a `needed(rank)` deficit, not an absolute good value, so expansion destabilizes until
re-centralized (§4); **reactionaries are opposite-pole promoted factions** — Undead/Vampires → `+100`,
Immortals/Khets → `−100` centralization, not a symmetric "block all change" bloc (§3); **build order** —
the religion-derived faction/estate layer is **buildable now** off `provinces.json`, while the coup loop
waits on unbuilt `Era` + political-currency + crownland resources (§6); the **player can inhabit the
system from below** — as a faction (L−2) or estate (L−1) leader inside another nation, not only as the
sovereign (§3); **1.0 scope** — **communism** is industrial-era, **out for 1.0**; **parliaments stay** (medieval
parliaments are real, so the republic's electoral `+100` pole ships) (§7); the **`−25`
centralization rail** couples register to centralization — despotism is a band `0…−50` and a republic
**over `−25`** despotizes (a president re-elected too often becomes the **despot**, ruler of the
plutocratic estate, who can **disband his estate and side with another to change register from the top**,
e.g. crown himself king); a **monarchy under `−25`** is **disbandable**, a **tribal register under `−25`**
sees its **sub-tribe estates break free**, and a **theocracy under `−25`** is **subverted by its
mages/artificers** toward a magic↔science pole — monarchy, theocracy & tribal want `≥ −25`, the republic
alone `≤ −25` (§4); **tribal estates *are* sub-tribes** — formal L−1 blocs, not mere kin-factions (§3/§4);
theocracies may also carry a **bureaucratic** (`estate_eunuchs_anb`) and/or **patrician**
(`estate_castonath_patricians`) estate (§3); **every estate/faction has a real-`Person` leader** — the
coup/revolt's protagonist, riding the sim's people layer (§5/§6); **leaders die** — tracks **pass to a
successor**, but an estate can also **break at leader death and split into factions**, and factions
**re-coalesce into a new estate** (tracks reset) when a majority share a goal — estate ⇄ faction is a
**reversible phase transition** (§3/§5); **every plot's
owner belongs to an estate/faction** and **ennoblement already moves land** between blocs, so
`SocialMobility` is already a centralization lever (§6); the **first playable interior is Adventurer
faction/estate gameplay** (§3).

*Resolved in the **2026-07-21 dialogue pass** (all folded into the body):* the **republic's rail
inverted** — all four registers share centralization `≥ −25`, and a republic runs three tradition
bands (above `0` centralization tradition floored at `0`; `0…−25` erodible; under `−25` the despotism
mechanic fires), so centralization *protects* a republic and despotization is a weak-centre failure,
not a size destiny (§4); **legitimacy is the reigning person's stat** — succession resets it,
interregnum is an event-state, not a scale point (§4); the **despot-exit ladder** — monarchy /
theocracy (undead/immortal, `>50`) / **god-emperor cult** as a new religion (`>75`), a mortal
founder's cult surviving him by institutionalizing his estate into the theocracy's
bureaucrats/patricians (§4/§3); **disasters resolve through mission chains** and **agendas + disaster
missions are one task mechanic** (§5); a **second concurrent coup escalates** — civil war at
non-negative centralization, **national implosion** (every estate independent) at negative (§5);
**influence bases and privileges are register-typed** — land / capital / adherents+orders / levies,
each estate fought in its own currency (§5); **army-rights are privileges** and **estate armies are
the internal wars' combatants** (§5); **loyalty seeks a privilege-set equilibrium** (§5); **council
seats are appointments** (snubs cost loyalty) with CK-style **regency extraction** (§5); **religions
are runtime objects** — cults, heresies that *capture the faith's head*, reform desire + decadence
(§4); **secede-or-usurp is a universal law** of subordinate blocs (§4); the research poles are
**divergent specializations with no regression** — a magocracy freezes the common trunk while magic
advances, a technocracy diverts into the **Clockpunk Era**, and buildings/units riding the common
tree make the divergence visible (§4); the **curated empire list goes to studio** and **Purpose stays
out of the title table** (rank doc §10); **nations tick monthly** while embedded settlements stay
daily (§6); the despot ladder is **`>25 / >50 / >75`** (§4); and the **heterodox rule** — no province
of the state faith → no church estate, only religious factions, one of which eventually founds a new
church and **converts the nation** (§3); the **council seat ↔ estate mapping confirmed** —
domain-matched seats filling in a cascade **estates → factions → the ruler's captains** (§5); and the
**faith is a polity** — an L+1 transnational theocracy over bordering co-religionist nations,
centralization **capped at `−75`**, whose permanent `needed(rank)` deficit **breeds its heresies**
(Regent Court ~`−90` → Corinite), with a **holy-site capital**, a real **ruler as head of the faith**,
and **wealth feeding decadence** (weakens estates, costs conversions, gates the faith-reform reset)
(§4); a **failed capture schisms** into a new religion; the **head of the faith is a playable
seat** — the fourth playable interior (§4/§3); and the **tribes are era-bound** — entering the
Medieval era fires their regressive last stand (undo the Medieval techs, raze the
Vassalage/Feudalism-line buildings), completing **Feudalism** flips them into fealty-pledged noble
factions, a **tribal-register nation flips into a despotism** (the tech flip, §8.4 edge 6), and
**only the tribes block buildings** on their holdings (per-holding, the science line)
(`estate-tribes.md` §7); **every estate is era-bound** — an unlock tech and an opposition tech per
estate (the patricians: Classical, opposing past **Meritocracy**), the tribes' arc the first full
instance, the reactionaries the never-ending case (§3); **every era transition requires a power
transition** — no nation advances past its ruling estate's opposition horizon (the tech tree as a
political gauntlet; the magocracy freeze is this law applied to ruling Mages) (§3); and a mutiny
victory is a **hard reset by peace deal** — the Medieval techs gone, the buildings razed
(`estate-tribes.md` §7).

Still open — **the consolidated list** (2026-07-21):

**Political core**

- **The `needed(rank)` centralization curve** — the actual function per rank rung, and the deficit
  margin that tips a realm into ambient internal war (a county needs ~`0`, an empire meaningfully
  positive).
- **`−25` rail vs. the `needed(rank)` deficit** — two *different* centralization thresholds: is the
  register rail genuinely fixed, or should it scale with rank too? Presented as independent for now.
- **Disaster/mission content authoring** — proposal on the table (unconfirmed): author **4 register
  archetype disasters + per-estate overrides** (vampires, the Commands, castes, patricians) instead of
  31 bespoke definitions. Also: chain length (missions per disaster), and which estates get bespoke
  1.0 content.
- **Start-state axis values** — the starting *sub-scale value*, *centralization*, and *PP/vassalage*
  still need sources; reforms + the diplomacy/rival graphs cover much, the fine values are heuristic.
- **Reactionary/insurgent bloc presence** — which nations get an Undead/Immortal reactionary estate or
  a peasant/anarchist faction, from what import signal. The Undead anchor is **`estate_vampires`**
  (imported); the Immortal **Khets** are **not an Anbennar estate** and need a CivStudio rule (§3).
- **Purpose vs estate agenda** — the rank doc's Purpose axis and an estate's agenda (now part of the
  task mechanic) may be the same lens at different scales; reconcile when both are built.

**Religion layer** *(`estate-church.md`)*

- **Faith-entity formation across water** — the transnational faith forms over co-religionist nations
  sharing a **land border**; the non-contiguous case (an overseas co-religionist) is **untested** —
  outside the entity, a second entity of the same religion, or membership regardless? A prototype
  question, not a design one.

**The era-bound estate lifecycle** *(spine §3; `estate-tribes.md` §7)*

- **What survives the flip** — do the chiefs keep their **land** (tribal holdings → noble fiefs, the
  new factions born landed) and their **hosts** (war-bands → noble levies)? Does "pledged fealty"
  mean high starting loyalty, or the coalescence baseline?
- **Piecewise erosion** — research is per-settlement, so does the national tribes estate erode
  **holding by holding** as each settlement feudalizes (a feudalization frontier), requiring the
  influence base to decompose per settlement?
- **"Scientific buildings"** — defined by the existing import (`ADVISOR_SCIENCE`-branch buildings in
  the C2C catalog), or a bespoke list?
- **The generic opposition toolkit** *(to be designed)* — what opposition *does* for estates other
  than the tribes (whose building-block is unique): loyalty falling per opposed tech researched?
  their disaster arming with regressive aims? Is the mages' beaker redirect *their* opposition form?
- **Per-estate lifecycle content** — the 31-row catalog now needs `unlock tech` + `opposition tech`
  (+ flip target and flip tech) per estate: the shape of the studio content schema.
- **Flip targets per estate** — in principle **every estate flips** at its horizon's end (tribes →
  nobles is the template), but the targets are unauthored (patricians → Burghers?), and testing full
  lifecycles needs the **tech import extended past the kept horizon** — up to
  `TECH_INFORMATION_LIFESTYLE`.

**Research poles** *(`estate-church.md` §2)*

- **Freeze semantics** — is the common trunk frozen at the level held when the pole took over, or
  clamped to a named era; and is it *resumable* when the scale drifts back toward `0` (a century
  behind the neighbors)?
- **The beaker-split driver** — at intermediate scale values, who sets the split: the *scale position*
  (a policy dial), the mage estate's *influence* (the scale as emergent readout), or the *ruler's
  focus* with estates stealing proportional to influence? And does the redirect operate **outside**
  theocracies — a monarchy's Mages estate siphoning beakers?
- **Clockpunk vs. the gnomish special era** — one content with two doors (race door + government
  door), or two distinct trees? Does the dwarven special era connect to any pole?
- **Magic-tree payload** — what magic techs unlock: battlemage host strength (arming the estate's
  army, §5), spells as economy/caravan modifiers, mage buildings — given buildings/units ride the
  common tree.
