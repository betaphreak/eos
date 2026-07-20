# Design note: placing Anbennar countries on the rank ladder

**Status:** Design (not built). The `Rank` ladder and its titles/casus-belli exist
(`agent/Rank.java`), but they are a single **aristocratic** register, and nothing yet reads a
country onto them. This note specifies (a) a **governance axis** giving the ladder plutocratic,
theocratic and tribal registers, (b) how a country's **rank is computed** from imported data, and
(c) the full **field inventory** of the Anbennar country scripts, so the import can grow past rank
into whole nations.
**Date:** 2026-07-20
**Depends on:** `agent/Rank.java` (the ladder — `level`/`isPlural`, `TitleMode`, `CasusBelli`,
`Relation`); the imported political layer (`geo/Country`, `WorldMap.provincesByOwner`, per-province
`baseTax`/`baseProduction`/`baseManpower`); the Anbennar EU4 source
(`.anbennar-cache/<ref>/history/countries/*.txt` + `common/countries/*.txt`, fetched via
`data.AnbennarFiles`).
**Related:** `docs/political-map.md` (province ownership + `Country`), `docs/race.md` (per-race
overlays — the same "authored per axis, human/neutral fallback" shape used here),
`docs/rank-ladder-improvements.md` and `docs/settlement-tier-ladder-plan.md` (the tier↔rank coupling),
the memory notes on single-player starting as an adventurer company at the lowest rank.

---

## 1. Motivation

`Rank.java` is a 16-rung ladder (`HOUSEHOLD` 0 … `HEGEMONY` 15) whose rungs **alternate** singular
consolidated entities (even levels: `HOLDING`, `CITY`, `DUCHY`…) with plural collectives (odd levels:
`CARAVAN`, `VILLAGE`, `LEAGUE`…). Its titles are essentially **aristocratic** — Baron, Viscount,
Count, Duke, King, Emperor — with a couple of mercantile leaks (Mayor at 4, Legate at 5).

Anbennar's 1454 countries are **not** all feudal aristocracies. By `government`:

| government | count | register |
|---|---|---|
| `monarchy` | 563 | **Aristocratic** |
| `republic` | 341 | **Plutocratic** |
| `tribal` | 335 | **Tribal** |
| `theocracy` | 164 | **Theocratic** |
| `native` | 39 | **Tribal** |

Founding a Salahadesi merchant republic or a centaur horde with a *Duke* is wrong. So the ladder
needs a **governance register** dimension, and one register must be the plain/**tribal** fallback for
governments that carry no distinct courtly flavor.

## 2. The governance axis

Add `agent/Governance` — a fourth dimension on a title, orthogonal to the existing `TitleMode`
(administrative / military / diplomatic) and `Gender`:

```java
enum Governance { ARISTOCRATIC, PLUTOCRATIC, THEOCRATIC, TRIBAL }
```

Resolved from the imported `government` field (`monarchy`→ARISTOCRATIC, `republic`→PLUTOCRATIC,
`theocracy`→THEOCRATIC, `tribal`/`native`→TRIBAL). **`TRIBAL` doubles as the neutral "no flavor"
fallback**: any government that does not map to a courtly register reads it, so the axis never has a
hole. A handful of `add_government_reform`s refine the register past the bare `government` (a
`monarchy` running `merchants_reform` is really plutocratic — see §4).

A title becomes `title(governance, mode, gender)`; the current data is the `ARISTOCRATIC` column.

## 3. Title registers

Administrative (legitimate) register per rung. The parity holds across all four: a **plural** rung
(○) names the *institution*, with its head in parentheses; a **singular** rung (◆) names the office.
Plutocracies and theocracies live most naturally on the plural rungs (a bourse, a synod), which is
exactly what the odd levels model.

| Lvl | Rank | Aristocratic *(have)* | Plutocratic | Theocratic | Tribal / neutral |
|--|--|--|--|--|--|
| 2 ◆ | HOLDING | Builder | Proprietor | Almoner | Hearth-holder |
| 3 ○ | VILLAGE | Leader | Alderman | Parson | Headman |
| 4 ◆ | CITY | Mayor | Burgomaster | Prior | Chieftain |
| 5 ○ | LEAGUE | Legate | **Guild** (Syndic) | **Chapter** (Dean) | **Kinmoot** (Speaker) |
| 6 ◆ | BARONY | Baron | Patrician | Confessor | War-chief |
| 7 ○ | VISCOUNTY | Viscount | **Consulate** (Consul) | **Canonry** (Canon) | **War-band** (Raid-leader) |
| 8 ◆ | COUNTY | Count | Magnate | Bishop | High Chief |
| 9 ○ | MARCH | Margrave | **Consortium** (Factor-General) | **Militant Order** (Inquisitor) | **Horde** (Warlord) |
| 10 ◆ | DUCHY | Duke | Doge | Archbishop | Over-chief |
| 11 ○ | PRINCIPATE | Prince | **Directorate** (Chairman) | **Synod** (Primate) | **Confederation** (Council of Chiefs) |
| 12 ◆ | KINGDOM | King | Merchant-Prince | Patriarch | Khan |
| 13 ○ | FEDERATION | High King | **Company** (Governor) | **Conclave** (Cardinal) | **Great Horde** (Khagan's Council) |
| 14 ◆ | EMPIRE | Emperor | Archon | Hierarch | Khagan |
| 15 ○ | HEGEMONY | Hegemon | Plutarch | Pontifex Maximus | World-Khan |

Levels 0–1 (`HOUSEHOLD`/`CARAVAN`) are pre-polity and keep the neutral titles the enum already has.

The **military (rebel)** and **diplomatic (envoy)** registers follow the same governance split — e.g.
theocratic-military reads *Heresiarch / Antipope* where aristocratic reads *Usurper*, plutocratic
reads *Insolvent / Defaulter*. These are content, drafted alongside the administrative table.

## 4. Casus belli by governance

The bigger flavor payoff than titles: the *pretext* a rank invokes (`Rank.casusBelli(Relation)`)
should read off the governance register. A theocracy does not "conquer" a rival — it *crusades*.

| Register | vs equal (the "war") | vs lower (subjugate) | vs higher (rebel) |
|---|---|---|---|
| Aristocratic | Ducal Conquest, War of Succession | Crown Centralization | Aristocratic Revolt |
| Plutocratic | Trade War, Hostile Takeover | Debt Bondage, Embargo | Burghers' Rebellion |
| Theocratic | Crusade, Holy War | Conversion, Excommunication | Heresy / Antipapal Schism |
| Tribal | Raid, Blood Feud | Vassal Tribute | Uprising, Great Migration |

## 5. Computing a country's rank

**`government_rank` is a floor, not the driver.** EU4 has only three tiers, and they are lopsided:

| `government_rank` | count | EU4 meaning |
|---|---|---|
| 1 | 1168 | duchy — the floor for *any* independent realm |
| 2 | 169 | kingdom |
| 3 | 78 | empire |

Mapping `government_rank=1 → DUCHY` would make every one-province minor a "grand territory." The
**driver is size**, which the political import already carries: `WorldMap.provincesByOwner(tag)` gives
the province list, and each `Province` carries `baseTax`/`baseProduction`/`baseManpower` for a
development weight. A one-province realm is a **single fief → `BARONY` (6)**, *not* a `COUNTY` (8),
which the ladder defines as "a major regional power center."

Size → rung (by owned-province count; tune to Anbennar's distribution):

| provinces | rung |
|---|---|
| 1 | BARONY (6) |
| 2–3 | VISCOUNTY (7) |
| 4–8 | COUNTY (8) |
| 9–20 | DUCHY (10) |
| 21–50 | KINGDOM (12) |
| 50+ | EMPIRE (14) |

Then, in order:

1. **`government_rank` floor.** rank-2 floors at `DUCHY` (10), rank-3 at `KINGDOM` (12) — a small but
   titled empire earned its standing.
2. **`government` → register** (§2), and **snap parity**: a `republic`/`theocracy` sits on the nearest
   *plural* rung (a merchant realm is a `LEAGUE`/`FEDERATION`, not a singular `KINGDOM`); an
   aristocracy on the *singular* rungs. `HEGEMONY` (15) is reserved for the single continental top dog.
3. **`add_government_reform` overrides — for *kind*, not size:**
   - `adventurer_reform` / `adventurer_republic_reform` (42 countries) → **below** BARONY:
     `HOLDING` (2) / `VILLAGE` (3). This is the **single-player start** — an Anbennar adventurer
     company at the bottom of the ladder.
   - `*_horde` / `*_warband` / `*_pack` / `*_chiefdom` (~215 across `centaur_horde`, `gnoll_pack`,
     `greentide_horde`, `dwarovar_warband`, `steppe_horde`, …) → the TRIBAL register, `MARCH` (9) or
     the plural collective rungs.
   - `merchants_reform` / `lake_republic` → PLUTOCRATIC, `LEAGUE` (5).
   - `monastic_order_reform` → THEOCRATIC militant order, `MARCH` (9).
   - `elector = yes` (8 countries) → HRE elector, `PRINCIPATE` (11).

So rank is a **composite**: *size* sets the rung, `government` picks the register and snaps parity,
`government_rank` sets a floor, and named reforms override for special kinds. That spreads 1454
countries across `BARONY`→`HEGEMONY` instead of piling them at one tier.

## 6. Full Anbennar field inventory

Every importable property, grouped by the engine system it feeds. Two sources: `history/countries/*.txt`
(**H**, dated per-country history) and `common/countries/*.txt` (**D**, static definition). Counts are
files containing the key.

### Governance & standing — *rank (§5)*
| field | src | n | use |
|---|---|---|---|
| `add_government_reform` | H | 1472 | richest *kind* signal — feudalism/merchants/adventurer/horde/monastic |
| `government` | H | 1448 | register (§2) + parity |
| `government_rank` | H | 1415 | tier floor |
| `elector` | H | 8 | HRE elector → PRINCIPATE |
| `setup_caste_estates` / `set_estate_privilege` / `change_estate_land_share` | H | 68/48/8 | decentralization |
| `historical_idea_groups` | D | 1458 | strategic character (trade vs military vs admin lean) |
| `add_adm/dip/mil_tech`, `mercantilism`, `army_professionalism`, `army_tradition` | H | 12/52/6/5 | starting advancement/strength |
| `historical_rival` / `historical_friend` (+`add_*`) | H | 292/225 | diplomacy degree ≈ great-power weight |

### Ruler & dynasty — *seeds the `Ruler` agent + dynasty naming*
| field | src | use |
|---|---|---|
| `monarch`/`heir`/`queen` blocks: `name`,`dynasty`,`adm`/`dip`/`mil`,`birth_date`/`death_date`,`female`,`culture`,`claim`,`*_personality`,`monarch_name` | H | a real ruler + house (adm+dip+mil = competence) instead of a generated one |

### Naming — *feeds `NameStore` / `RaceNameGenerator`*
| field | src | n |
|---|---|---|
| `monarch_names`, `leader_names`, `army_names`, `fleet_names`, `ship_names` | D | ~1460 each — hand-curated per-nation name banks |

### Identity — *race / calendar / tech overlay (partly imported)*
| field | src | use |
|---|---|---|
| `primary_culture` / `add_accepted_culture` | H | founding race (via `WorldMap.raceOf`) + multi-culture = federal signal |
| `religion` / `secondary_religion` / `religious_school` | H | calendar + theocratic register |
| `historical_magic_advancements` | H (74) | magic |
| `technology_group`, `graphical_culture` | H/D | tech + art style |

### Geography — *location / realm*
| field | src | use |
|---|---|---|
| `capital` / `fixed_capital` | H | capital province → lat/long, realm; `fixed_capital` (137) = can't relocate |
| `setup_vision_<region>` (cannor/haless/sarhal/insyaa/aelantir/serpentspine) | H | region/continent tag |

### Diplomacy — *the future `Relation`/`CasusBelli` graph*
| field | src | use |
|---|---|---|
| `historical_rival`/`historical_friend`/`add_truce_with`/`reverse_add_opinion` | H | a ready-made starting relations graph |

### Misc / bonuses
| field | src | use |
|---|---|---|
| `national_focus`, `add_country_modifier`, `set_country_flag`, `set_variable` | H | ADM/DIP/MIL lean, bespoke flags |
| `color` / `revolutionary_colors` | D | map color (already imported for the political map) |
| `colonial_parent`, `historical_score`, `random_nation_chance` | D | colonial links, tie-break weight |

## 7. Data model — keep the spine, author the flavor as content

Do **not** explode the enum into `4 governances × 3 modes × 2 genders = 24 strings × 16 ranks` of
hardcoded literals. That flavor text is authored, localizable content — the same kind this codebase is
moving out of Java into the world bundle. Keep `Rank.java` as the **structural spine** (`level`,
`isPlural`, the `Relation` shape of casus belli) and move the title/CB *text* to a `ranks.json` keyed
by `(level, governance, mode, gender)`, loaded through the `WorldSource` seam with the compiled table
as the fallback floor — mirroring `EconomyCatalog` / `BalanceProfiles` / `ScenarioRegistry`
(content-with-compiled-floor, absent→defaults, malformed→throws). Then the theocratic ladder is a
content edit, studio can author it, and the "localizing it is future work" note already in
`Rank.java`'s class doc gets its home.

`geo/Country` grows a computed `rank` (§5) and a `governance` (§2); the richer per-country data (ruler,
name banks, relations) is the thin-end-of-the-wedge toward `Country` becoming a real polity record
rather than a rank-tagged stub — a scope decision to take before building.

## 8. Open questions

- **Rank ↔ SettlementTier.** A colony already derives a head `Rank` from its `SettlementTier`
  (`docs/settlement-tier-ladder-plan.md`). A country's imported rank and a founded colony's tier-rank
  must reconcile — is the country rank the *ceiling* a colony climbs toward, or independent?
- **Thresholds.** The size→rung table (§5) is a first guess; calibrate against the actual Anbennar
  province distribution so the spread looks right (few empires, many baronies).
- **Register snapping vs. history.** Some monarchies are titled `KINGDOM` but small; does the
  `government_rank` floor or the size win? Proposed: floor wins (a titled king is a king), size only
  raises.
- **Tribal race overrides.** `gnoll_pack` vs `centaur_horde` vs `greentide_horde` could each carry a
  race-specific tribal register (Pack-alpha, Herd-lord, Warboss) — content variants under the TRIBAL
  governance, resolved by founding race.
