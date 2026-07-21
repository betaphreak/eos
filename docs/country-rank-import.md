# Design note: placing Anbennar countries on the rank ladder

**Status:** Design (not built) — a north-star model. The `Rank` ladder and its titles/casus-belli
exist (`agent/Rank.java`) as a single **aristocratic** register; nothing yet reads a country onto them.
This note specifies the model (four axes + a recursion), how a country's placement is computed from
imported data, and the full field inventory of the Anbennar country scripts.
**Date:** 2026-07-20 · **Updated:** 2026-07-21 (§10: two questions resolved in the estate-system
dialogue pass)
**Depends on:** `agent/Rank.java` (`level`/`isPlural`, `TitleMode`, `CasusBelli`, `Relation`); the
imported political layer (`geo/Country`, `WorldMap.provincesByOwner`, per-province
`baseTax`/`baseProduction`/`baseManpower`); `settlement/SettlementTier` (the growth ladder rank reuses);
the Anbennar EU4 source (`.anbennar-cache/<ref>/history/countries/*.txt`, `.../common/countries/*.txt`,
`.../history/diplomacy/*.txt`, via `data.AnbennarFiles`).
**Related:** [`docs/estate-system.md`](estate-system.md) (the runtime estate system this doc's §4
structure feeds — the spine; its four register spokes are `estate-nobles/burghers/church/tribes.md`), `docs/political-map.md` (province ownership + `Country`), `docs/race.md` (the "authored per
axis, neutral fallback" shape reused here), `docs/settlement-tier-ladder-plan.md` (tier↔rank),
`docs/rank-ladder-improvements.md`, and the memory notes on single-player as an adventurer company.

---

## 1. Motivation

`Rank.java` is a 16-rung ladder (`HOUSEHOLD` 0 … `HEGEMONY` 15) that **alternates** singular consolidated
entities (even levels: `HOLDING`, `CITY`, `DUCHY`…) with plural collectives (odd levels: `CARAVAN`,
`VILLAGE`, `LEAGUE`…). Its titles are **aristocratic** — Baron, Count, Duke, King, Emperor. But
Anbennar's 1454 countries are not all feudal aristocracies; by `government`: 563 monarchy, 341 republic,
335 tribal, 164 theocracy, 39 native. Founding a merchant republic or a centaur horde with a *Duke* is
wrong. The ladder needs flavor registers, and a country needs to be *placed* on it from its data.

## 2. The model at a glance

Every political entity is a point in **four axes**, with a **recursion** for internal politics.

| Axis | Values | From |
|---|---|---|
| **Level** | HOUSEHOLD (0) … HEGEMONY (15); even = singular, odd = plural collective | owned-province size |
| **Register** | ARISTOCRATIC / PLUTOCRATIC / THEOCRATIC / TRIBAL — *the ruling estate* (`estate-system.md`; each carries a −100…0…+100 sub-scale) | `government` |
| **Purpose** *(collectives only)* | governance → Diet · defense → March · trade → Hanse | reforms, idea groups, rival-pressure |
| **Sovereignty** | independent ↔ subordinate | `history/diplomacy` subject graph |

An entity's title also carries the existing **`TitleMode`** — a *legitimacy* context (legitimate /
rebel / envoy), orthogonal to the four axes and unchanged.

**The recursion (internal politics).** A polity with a **middle** level of centralization has **estates**
at L−1 (plural bodies) and **factions** at L−2 (its unaligned members). Both *extremes* dissolve the
estate layer, leaving only factions — a `−100` federation is too loose to have central estates, and a
`+100` absolutist crown *eliminates* them. So estates are a **middle-band** phenomenon; see
[`estate-system.md`](estate-system.md) §4 for the non-monotonic detail.

**The through-line — little new machinery.** Level, the L−1/L−2 nesting, the overthrow casus-belli, and
centralize/fragment (= the `SettlementTier` growth ladder) are **already** in `Rank.java` +
`SettlementTier`. The only genuinely new pieces are the **Register** axis (a `Governance` enum), the
**Purpose** axis, and the **sovereignty graph** from the diplomacy import.

## 3. The axes

### 3.1 Level — the ladder, at Anbennar scale

**One Anbennar province = a `COUNTY` (8), run by a Count.** Provinces are large — a whole county's
worth of land. **All start countries are singular** (even rungs) — the plural rungs (`LEAGUE`, `MARCH`,
`FEDERATION`, `HEGEMONY`…) are *structural roles* (estates, subject-relations, formables), never a
thing a start country *is* (§4, §5). The calibrated bands (validated against all 794 territory-holders,
2026-07-20):

| owned provinces | rung | note |
|---|---|---|
| landless `adventurer_reform` | **CARAVAN (1)** | a wandering band = the engine's `Caravan`; the SP start |
| landed `adventurer_reform` | **CITY (4)** | a company with a base — internal estates (chapters) & factions (rival captains) |
| 1, republic/theocracy | **BARONY (6)** | a city-state / temple |
| 1, monarchy/tribal | **COUNTY (8)** | one province = one county |
| 2–9 | **DUCHY (10)** | the dominant middle tier (518 of 794) |
| 10+ | **KINGDOM (12)** | the great powers (~55) |
| `government_rank = 3` **and** ≥10, **or** a curated lore-empire | **EMPIRE (14)** | 5 — see §7 |

**No `HEGEMONY` at game start** (there can be only one, and none exists at 1444). `BARONY` sits
*below* `COUNTY` here as an independent city-state/temple, and *also* appears inside a county as a
noble's fief (§4) — sovereignty (§3.4), not level, tells them apart. A *landed* adventurer company has
settled — but a company with a base is a complex organization with its own estates and factions, so it
is a `CITY` (4), not a rural Barony. Distribution: 47 Caravans · 25 Cities · 64 Baronies · 127 Counties
· 518 Duchies · 55 Kingdoms · 5 Empires.

### 3.2 Register — the governance culture

`agent/Governance { ARISTOCRATIC, PLUTOCRATIC, THEOCRATIC, TRIBAL }` — **the register is the ruling
estate** (`estate-system.md`). Resolved from `government`: `monarchy`→ARISTOCRATIC (Crown),
`republic`→PLUTOCRATIC (Burghers), `theocracy`→THEOCRATIC, `tribal`/`native`→TRIBAL. **`TRIBAL` doubles
as the neutral "no flavor" fallback** for any unmapped government. A closed **four** — a theocracy's
*mageocratic* (mage-ruled) and *technocratic* (science-ruled) variants are the **poles of a magic↔science
sub-scale** (`estate-system.md`), not separate registers. Race-specific tribal variants (gnoll pack,
orc horde) are a later
content question, not new enum values. A title becomes `title(governance, mode, gender)`; today's data
is the `ARISTOCRATIC` column.

### 3.3 Purpose — why a collective formed *(plural rungs only)*

**Runtime-only.** Since every start country is singular (§3.1), there are *no* collectives at game
start to flavor — Purpose only matters once countries centralize or federate *during play* (§5). So
start placement is just **Level × Register (× Sovereignty)**; Purpose enters when a League/March/
Federation first forms.

A collective is structurally one thing but wears a different institutional face by **why** its peers
banded together. This is its own axis, distinct from `TitleMode`:

| purpose | face | import signal |
|---|---|---|
| governance | a **Diet / council** — Legate/Speaker | oligarchic/administrative reforms |
| defense | a **March** (military league) — Warlord/Warden | border position, `historical_rival` pressure, martial reforms |
| trade | a **Hanse / trade league** — Consul/Chancellor | `merchants_reform`/`lake_republic`, trade `historical_idea_groups` |

So a bloc of neighboring counties is a *March* if a defensive pact and a *trade league* if commercial —
the same rung, different purpose.

### 3.4 Sovereignty — independent vs subordinate

Sovereignty is binary *here*, but it is really the sign of a full **−100…+100 axis** — Power Projection
(independent) vs vassalage (subject), with a vassal as a **cultural estate** of its overlord. That
numeric treatment (rivals, independence CBs, subinfeudation, peaceful vassalization) lives in
[`estate-system.md`](estate-system.md) §4; this section is just the *placement* half — who is a subject
of whom.

A plural rung ("a loose association of the rank below") can be **sovereign or subordinate**, and that
is orthogonal to rank:

- **Subordinate** → an estate of the polity above (a Duke's `MARCH`-estate: marcher-lords bound to him).
- **Sovereign** → an independent polity (a free `MARCH`: a league of Counts owing no Duke).

The same `MARCH` (9) is either. A sovereign plural polity often persists as a **buffer** between the
spheres of two rival Duchies, held there by the balance of power. Read from the import: the country
files give a polity's own rank; whether it is a *subject* comes from `history/diplomacy/*.txt` — typed
relations `march = { first=<overlord> second=<subject> }` (14), `vassal` (72), `union` (4), and
`dependency` with a `subject_type` (113 — incl. `tributary_state_anb`, `vic_league_member`, and
`sponsored_adventurer_subject`, the landless-company-to-patron SP hook). No incoming subject relation ⇒
sovereign. The `first`/`second` pairs are the subordination graph.

## 4. Estates & factions — the recursion

The internal politics of a realm are **relative to its own rank**:

> For a **centralized** polity at level **L**: its **Estates** sit at **L−1** and its **Factions** at
> **L−2**. A **plural (uncentralized) collective** has **no estates** — its members *are* its factions.

An **estate** is a formal power bloc — a plural body one rung below the polity, so a centralized
(even-L) polity's estates are the odd L−1 collectives. A **faction** is a power-holder bound into no
estate: in a centralized polity these are the unaligned L−2 members (a member enrolled *in* an estate is
just that, a *member*); in an uncentralized collective there are no estates, so all its L−1 members are
factions. Estates are thus a **feature of centralization** — a loose collective has only
members-as-factions until it centralizes (§5) and reorganizes them into estate-blocs.

Each L−1 estate rung lends its own **character**, so the *kind* of internal threat escalates with scale
— and `Rank.java` already carries each one's overthrow-your-liege casus belli at exactly that rung:

| Centralized polity | Estate (L−1) | Estate character (title / vs-higher CB) | Factions (L−2) |
|---|---|---|---|
| COUNTY (8) | VISCOUNTY (7) | scheming factions — *Factioneer* / **Vassal Uprising** (depose the Count) | BARONY (6) barons |
| DUCHY (10) | MARCH (9) | militarized marcher-lords — *Warlord* / **Marcher Treason** | COUNTY (8) counts |
| KINGDOM (12) | PRINCIPATE (11) | electoral council — *Elector* / **Aristocratic Revolt** | DUCHY (10) dukes |
| EMPIRE (14) | FEDERATION (13) | supranational breakaway — *Chancellor/Oathbreaker* / **Coalition War** | KINGDOM (12) kings |

So the plural rungs' `casusBelli(HIGHER)` slot **is** the estate-revolt mechanic, already written — you
read it off the ladder rather than build it. An estate carries a **register** (§3.2): the Nobility
estate is an aristocratic `VISCOUNTY`, the Clergy a theocratic `Canonry`, the Burghers a plutocratic
`Consulate` — same rung, competing registers.

**Cardinality ≥ 1.** A plural rung is a collective of *≥1* of the rank below — plurality of *role*, not
*membership*. "A federation of one is legit": a single member elevated to the collective rung is a valid
one-member estate.

**Worked example — the Empire of Anbennar** (the recursion at its top, an HRE analog; a centralized
EMPIRE, so it *has* estates):

| | rank | Anbennar | import |
|---|---|---|---|
| the polity | EMPIRE (14) | Empire of Anbennar | — |
| its estates | FEDERATION (13) | the **Electors** — 7 at the start | `elector = yes` (8 files ≈ 7 + Emperor) |
| members | KINGDOM (12) | kingdoms enrolled in an Elector-federation | HRE membership |
| factions | KINGDOM (12) | kingdoms in **no** Elector — free agents to court or crush | HRE member, non-elector |

Contrast the **Lake Federation** — an uncentralized FEDERATION (13), so **no estates**: it is just a
loose bunch of centaur kingdoms and duchies, and those members *are* its factions. When it centralizes
into **Kalsyto** (§5), it becomes an EMPIRE and *gains* the estate layer.

### 4.1 The estate *system* — see `docs/estate-system.md`

§4 above is the estate/faction *structure* on the rank ladder (estates at L−1, factions at L−2). The
**runtime estate system** — which estate *rules* (= the government type / register), the concrete
noble/religion estate rules, the register × −100…0…+100 **sub-scales** (republican tradition;
theocracy's magic↔science), and **influence → coups → government transitions** — grew into its own
subsystem and lives in **[`docs/estate-system.md`](estate-system.md)**. It makes the **register
dynamic** (a coup flips it, the mirror of §5's dynamic rank) and confirms the register stays the
**four** of §3.2 (mageocracy/technocracy are poles of theocracy, not a fifth register).

## 5. Dynamic rank — centralize & fragment

A polity's rank is a **starting** placement, not a fixed stamp; play moves it along the ladder, which is
the parity alternation in motion. The continuous driver is the **centralization** axis
(`estate-system.md`): the ladder's singular/plural parity is its discretization, and crossing a
threshold is a rank change.

- **Centralize** (plural → singular, +1): centralization rises and a loose collective consolidates into
  a single entity — the **Lake Federation** (13, centralization −100) → **Kalsyto**, one EMPIRE (14).
  Maps to Anbennar's **formable nations / centralization decisions**, and creates the estate layer (§4).
- **Fragment** (singular → plural, −1): centralization falls and an Empire shatters into a Federation of
  successor kingdoms.

This is **not new machinery** — it is the `SettlementTier` growth ladder (CAMP→METROPOLIS, head-`Rank`
derived from tier) at the polity scale. The engine already climbs a colony's rank; centralize/fragment
is the same mechanic for a country.

**Members are heterogeneous in rank.** The Lake Federation holds *kingdoms and duchies*. A collective is
**led by** L−1 members but may contain lesser ones; "estates at L−1, factions at L−2" describes the
*dominant* tier, with smaller members nesting below. Rank is overall scale, not uniform membership.

## 6. Titles & casus belli — the flavor registers

Administrative (legitimate) titles across the four registers. Parity holds: a **plural** rung (○) names
the *institution*, head in parentheses; a **singular** rung (◆) names the office.

| Lvl | Rank | Aristocratic *(have)* | Plutocratic | Theocratic | Tribal / neutral |
|--|--|--|--|--|--|
| 4 ◆ | CITY | Mayor | Burgomaster | Prior | Chieftain |
| 5 ○ | LEAGUE | Legate | Guild (Syndic) | Chapter (Dean) | Kinmoot (Speaker) |
| 6 ◆ | BARONY | Baron | Patrician | Confessor | War-chief |
| 7 ○ | VISCOUNTY | Viscount | Consulate (Consul) | Canonry (Canon) | War-band (Raid-leader) |
| 8 ◆ | COUNTY | Count | Magnate | Bishop | High Chief |
| 9 ○ | MARCH | Margrave | Consortium (Factor-General) | Militant Order (Inquisitor) | Horde (Warlord) |
| 10 ◆ | DUCHY | Duke | Doge | Archbishop | Over-chief |
| 11 ○ | PRINCIPATE | Prince | Directorate (Chairman) | Synod (Primate) | Confederation (Council of Chiefs) |
| 12 ◆ | KINGDOM | King | Merchant-Prince | Patriarch | Khan |
| 13 ○ | FEDERATION | High King | Company (Governor) | Conclave (Cardinal) | Great Horde (Khagan's Council) |
| 14 ◆ | EMPIRE | Emperor | Archon | Hierarch | Khagan |
| 15 ○ | HEGEMONY | Hegemon | Plutarch | Pontifex Maximus | World-Khan |

The **military (rebel)** and **diplomatic (envoy)** `TitleMode` registers split by governance the same
way (e.g. theocratic-rebel *Heresiarch/Antipope*). Casus belli flavor by register:

| Register | vs equal (the "war") | vs lower (subjugate) | vs higher (rebel) |
|---|---|---|---|
| Aristocratic | Ducal Conquest, War of Succession | Crown Centralization | Aristocratic Revolt |
| Plutocratic | Trade War, Hostile Takeover | Debt Bondage, Embargo | Burghers' Rebellion |
| Theocratic | Crusade, Holy War | Conversion, Excommunication | Heresy / Antipapal Schism |
| Tribal | Raid, Blood Feud | Vassal Tribute | Uprising, Great Migration |

> `TitleMode` stays a **legitimacy** axis (legitimate / rebel / envoy); **Purpose** (§3.3) is the
> separate axis that flavors a collective into Diet/March/Hanse. They do not compete.

## 7. Computing a country's rank

Calibrated and validated against all 794 territory-holders (throwaway pass, 2026-07-20). **Size is the
driver, not the title** — `government_rank` (1: 1168, 2: 169, 3: 78) is too coarse and often wrong at
the low end (Lorent is `gr=1` but reads a Kingdom by its 25 provinces; its kingship lives in a reform).
The algorithm:

1. **`adventurer_reform`:** landless → **`CARAVAN` (1)** — a wandering band of households under a
   captain (`HOLDING` is a *building*, not a people-band), which is exactly the engine's `Caravan`; the
   single-player start (47 landless of 72). Landed → **`CITY` (4)** — a company that holds a base is a
   complex urban organization with internal estates (its chapters) and factions (rival captains — see
   B02 Corintar's `disciplined_party_leaders` reform), not a rural Barony (25 landed, e.g. the B02–B20
   companies).
2. **Size → rung** (the §3.1 bands): 1 prov → `BARONY` (6) for a republic/theocracy (city-state /
   temple), else `COUNTY` (8); 2–9 → `DUCHY` (10); 10+ → `KINGDOM` (12).
3. **`government` → register** (§3.2). Purely a flavor axis — it changes titles, not level (except the
   1-province `BARONY` snap in step 2).
4. **Empire** = `government_rank = 3` **and** ≥10 provinces (catches **The Command**, **Kheterata**)
   **or** a **curated lore-empire**. The rule alone is insufficient — see below.
5. **No `HEGEMONY`, no plural rungs.** Every start country is singular (§3.1).

**The empire tier needs a curated overlay — the data can't do it alone.** Three of the five start
empires carry no imperial signal in their files: **Yezel Mora** (`gr=1`, a swamp-troll theocracy),
**Gnollakaz** (`gr=2` tribal), **Danggun** (`gr=2` monarchy). Nothing distinguishes them from a big
kingdom — Yezel Mora (35 prov) is *lower*-titled than the kingdom Gawed (40, `gr=2`), and size can't
split them. So the empire set is `{gr=3 & ≥10}` ∪ a hand-authored lore list, currently:

| tag | empire | why in the set |
|---|---|---|
| R62 | The Command | `gr=3`, 70 prov — by the rule (hobgoblin) |
| U01 | Kheterata | `gr=3`, 17 prov — by the rule (Bulwar) |
| S70 | Yezel Mora | curated (theocratic empire; `gr=1` in data) |
| U09 | Gnollakaz | curated (gnoll empire; `gr=2`) |
| Y93 | Danggun | curated (Haless empire; `gr=2`) |

Plus the **Empire of Anbennar** as the HRE meta-entity (not a province-holding row). One empire per
major region — a satisfying spread. The curated list is the small, honest cost of the game's rank
fields not encoding reform/theocratic empires.

Sovereignty (§3.4) is resolved separately from the diplomacy graph. The reform-based *kind* overrides
(horde/merchant/monastic → register + Purpose) matter for **runtime** collectives (§3.3), not start
placement, where `government` alone sets the register.

## 8. Anbennar field inventory

Every importable property, by the system it feeds. Sources: `history/countries/*.txt` (**H**),
`common/countries/*.txt` (**D**), `history/diplomacy/*.txt` (**X**). Counts = files with the key.

### Governance & standing — *rank (§7)*
| field | src | n | use |
|---|---|---|---|
| `add_government_reform` | H | 1472 | richest *kind* signal (feudalism/merchants/adventurer/horde/monastic) |
| `government` | H | 1448 | register + parity |
| `government_rank` | H | 1415 | tier floor |
| `elector` | H | 8 | Elector (Federation-estate of the Empire) |
| `setup_caste_estates`/`set_estate_privilege`/`change_estate_land_share` | H | 68/48/8 | estate structure |
| `historical_idea_groups` | D | 1458 | strategic character (trade/military/admin lean) → Purpose |
| diplomacy `march`/`vassal`/`union`/`dependency` | X | 14/72/4/113 | **sovereignty graph** (§3.4) |
| `historical_rival`/`historical_friend` | H | 292/225 | great-power weight; buffer geometry |

### Ruler & dynasty — *seeds the `Ruler` agent + dynasty*
`monarch`/`heir`/`queen` blocks: `name`,`dynasty`,`adm`/`dip`/`mil`,`birth_date`/`death_date`,`female`,
`culture`,`claim`,`*_personality` (H) — a real ruler + house instead of a generated one.

### Naming — *feeds `NameStore`/`RaceNameGenerator`*
`monarch_names`,`leader_names`,`army_names`,`fleet_names`,`ship_names` (D, ~1460 each) — per-nation name
banks.

### Identity — *race / calendar / tech overlay (partly imported)*
`primary_culture`/`add_accepted_culture`, `religion`/`secondary_religion`/`religious_school`,
`historical_magic_advancements` (H, 74), `technology_group`, `graphical_culture`.

### Geography — *location / realm*
`capital`/`fixed_capital` (H) → capital province lat/long, realm; `setup_vision_<region>` → continent.

### Misc
`national_focus`, `add_country_modifier`, `set_country_flag`, `color`/`revolutionary_colors` (D, map
color — already imported), `colonial_parent`.

## 9. Data model & build sketch

Keep `Rank.java` as the **structural spine** (`level`, `isPlural`, the `Relation` shape of casus belli).
Move the flavor *text* to a `ranks.json` keyed by `(level, governance, mode, gender)` (+ CB by
`(level, governance, relation)`), loaded through the `WorldSource` seam with the compiled table as the
fallback floor — the exact pattern of `EconomyCatalog` / `BalanceProfiles` / `ScenarioRegistry`
(content-with-compiled-floor; absent → defaults; malformed → throws). That gives the "localizing is
future work" note in `Rank.java` its home and lets studio author the theocratic/plutocratic ladders.

Ordered build steps, when it is time:

1. `agent/Governance` enum + `government`→register resolver.
2. `geo/Country` grows computed `rank` (§7) + `governance`; a placement pass over `provincesByOwner`.
3. The diplomacy import (§3.4) → a subordination graph on `Country` (sovereign vs subject).
4. Content-backed `RankTitles` loader (spine stays in `Rank.java`); author the four registers.
5. The `Purpose` axis, and the **estate system** (`docs/estate-system.md`) — ruling estate, influence,
   coups, sub-scales — on the ennoblement / `SocialMobility` layer.

`Country` growing a rank is the thin end of it becoming a **real polity record** (ruler, dynasty, name
banks, relations) rather than a rank-tagged stub — a scope decision to take before building.

## 10. Open questions

*Resolved by the 2026-07-20 calibration (§3.1, §7):* placement thresholds (bands locked, validated on
794 countries); size-vs-title (size drives, `government_rank` gates only Empire, no floor); the empire
set (rule + curated overlay of 5). Still open:

*Resolved 2026-07-21 (estate-system dialogue pass):* the **curated empire list lives in studio** as
content-with-compiled-floor (the §9 pattern); **Purpose stays out of the title table** — its own
small `(level, purpose) → institution` table in `ranks.json`. Still open:

- **Curated empire list lore review** — the hand-authored 3 of 5 (Yezel Mora, Gnollakaz, Danggun)
  need a completeness pass before the studio content is authored.
- **Rank ↔ SettlementTier** — is a country's imported rank the *ceiling* a founded colony climbs toward
  (§5), or independent of it?
- **`Country` scope** — rank-tagged stub, or grow it into a full polity record (§9)? The clock is
  decided — nations run a **coarse monthly tick** beside the daily settlements (`estate-system.md`
  §6) — so the question is now just how much state the record carries.
