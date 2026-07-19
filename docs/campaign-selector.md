# Design & plan: the campaign selector (Phase G)

**Status:** **DESIGN + interactive mockup** (no engine/Strapi code yet). **Date:** 2026-07-19.
**Depends on:** [`docs/session-management.md`](session-management.md) (the founding model — `SessionKind`
/ `mode` / `difficulty`, Phases C–F), [`docs/flags.md`](flags.md) (the Anbennar flag atlas),
[`docs/studio-datamodel-rebuild-plan.md`](studio-datamodel-rebuild-plan.md) (the Strapi → world-bundle
pipeline this rides), [`docs/civ4-files.md`](civ4-files.md) (the handicap catalog, Phase F).

This is **Phase G** of session management: the screen a player uses to *choose what they play*, and the
data + exporter pipeline behind it. Phase F gave the numeric difficulty (Civ4 handicap); this is the
**campaign** — the Anbennar realm and its cause — chosen on a moral wheel.

## The interactive mockup

**https://claude.ai/code/artifact/b6515138-43d0-44a2-9eaa-ef25572eb442** (private Claude artifact).

It is the real prototype, in CivStudio's own palette/type (`#0d1119`, gold `#e6b04a`,
Constantia/Cambria serif), driven by the owner's grid data and the real flag atlas. It shows the whole
interaction the build should reproduce, so the UI work is "port this to the lobby," not "design this."

## The idea: an alignment astrolabe

The prototype is a **Good–Evil × Order–Chaos** grid
([`docs/reference/anbennar-alignment-grid.csv`](reference/anbennar-alignment-grid.csv)). Rendered as a
spreadsheet it clips the corners — where the most extreme (and most interesting) realms live — and
fights the lore's own "wheel of Order and Chaos" language. So the picker is a **circular astrolabe**:

- **Good** at top, **Evil** at bottom, **Order** left, **Chaos** right; **True Neutral** at the hub.
- The square alignment domain is **circumscribed** by the disc: an on-axis extreme sits partway out,
  the four diagonal **corners ride the rim** — so nothing is clipped and the extremes read as extreme.
- Each realm is a mark at its `(order, good)` alignment, **flying its real heraldry** where one exists
  (a plain diamond otherwise), colour-keyed to its content pack.
- **Difficulty is the ground:** a wash from the luminous Order-&-Good quarter (a settled, supported
  start) to the dark Chaos-&-Evil reach (the outcast's uphill fight).
- **Zoom + pan** (scroll / drag / double-click to reset) to sort out a crowded quarter — the interior
  moves inside a fixed cardinal ring, like a real astrolabe.
- Selecting a realm fills a rail: heraldry, blurb, alignment readout, a ★ starting-difficulty rating,
  the **Civ4 handicap** dropdown, and *Begin as …*. Pack chips dim non-matching realms; search filters.

It lives at the front of the **New Single Player** flow (and later the ranked join), replacing the raw
seed + province form.

## The source: Anbennar **mission trees** are the campaign catalog

Decisions (owner, 2026-07-19): **the mod is the master list, the grid places it.** The Anbennar
**mission files** (`.anbennar-cache/<ref>/missions/*.txt`) are the canonical, complete,
auto-updating catalog of who has a flavored campaign — the alignment grid is a curated *placement
overlay* keyed by tag, not the list itself.

Each mission file holds one or more **mission series**, each gated to a country:

```
arakeprun1_missions = {          # a mission series
    slot = 1
    generic = no                 # a nation's OWN flavored tree (not a shared/generic pack)
    has_country_shield = yes     # it flies a unique coat of arms
    potential = { tag = H01 }    # ← the tag this campaign belongs to
    arakeprun_ruins_of_greatness = { icon = mission_city_of_victory_vij … }
    …
}
```

So a campaign is `{ tag, name, icon, flavored }`. **Catalog only** — the exporter reads the identity
(tag, series name, first-mission/shield icon) and stops; the mission *mechanics* (triggers, effects,
the tree graph) are a separate, much larger import (à la `docs/c2c-building-import.md`) and out of
scope for the picker.

## Scope: the **adventurer companies** first

The single-player campaign set is the **19 Anbennar adventurer companies — tags `B02`–`B20` exactly**
(`B01` is Greentide, not a company). These are the low-Rank mercenary outfits a player begins as and
climbs from (see the SP design: a company + a ±1 rank window). They anchor at **neutral `(0,0)`** on the
wheel (an adventurer starts unaligned; its cause emerges through play), spreading out as they are
curated. The broader flavored-nation catalog can grow behind them, but the companies are the first-class
set.

**The set is a tag range — no company override map.** Every tag in the block is named and flies a flag,
so the exporter takes `B02`–`B20` directly; name/heraldry come free from the tag. The roster:

| tag | company | tag | company |
|---|---|---|---|
| B02 | Corintar | B12 | Brave Brothers · grid (−5,3) |
| B03 | Cobalt Company | B13 | Stalwart Band · grid (0,0) |
| B04 | Order of the Ashen Rose · grid (−4,2) | B14 | Raven Banner |
| B05 | Pioneer's Guild | B15 | New Wanderers |
| B06 | Warriors of Ancard | B16 | Freeflower Vanguard |
| B07 | Sons of Dameria | B17 | Asra Expedition |
| B08 | Gallant Friends | B18 | Iron Hammers |
| B09 | House of Riches | B19 | Sword Covenant |
| B10 | Small Fellows | B20 | Order of the Iron Sceptre |
| B11 | Company of the Thorn | | |

3 already sit on the grid (B04, B12, B13); the other 16 default to neutral `(0,0)` and get the
culture/religion/government heuristic + hand-tuning.

## What the missions can give (exportable fields)

Per **mission series** (`X_missions = {}`): the **tag** (`potential { tag = … }` — but also
`always = yes` generics and `capital_scope`/culture-gated formables, so a resolver + override map is
needed), **`slot`** (tree column), **`generic`** (flavored vs shared pack), **`has_country_shield`**,
**`ai`**.

Per **mission node**: its key → **title + description** (real localised prose in
`localisation/*_missions_l_english.yml`, keyed `<key>_title`/`_desc` — incl. `anb_adventurer_missions`),
the **`icon`** (a GFX sprite → `.dds` art, bakeable like the C2C icons), **`position`** +
**`required_missions`** (the tree graph), **`provinces_to_highlight`** (province/area/region/culture),
and the **`trigger`** (goals) / **`effect`** (rewards) blocks.

Joinable **from the tag** (not in the missions): country **name**, **heraldry**, **capital province**,
and **culture/religion/government** (the last three feed the placement heuristic).

**BUILT — `MissionExporter` imports all of it, for all countries.**
`com.civstudio.mission.export.MissionExporter` (with the `Mission` / `MissionSeries` records and a
promoted-public `ClausewitzBlocks`) reads every `missions/*.txt` via the `AnbennarFiles` seam, joins the
`*_l_english.yml` loc, and writes `generated/missions.json`:

```
3114 mission series (2293 tag-gated), 13078 missions (12978 localised) from 357 files
coverage: icon 100% · position 99.6% · trigger 99.8% · effect 99.8% · requiredMissions 89% · highlight 34%
```

Each series carries `{id, tag, slot, generic, ai, hasCountryShield, potential, file, missions[]}`; each
mission `{key, title, description, icon, position, requiredMissions[], highlightProvinces[], trigger,
effect}` — the trigger/effect DSL kept as raw (whitespace-collapsed) text rather than modelled. Run:
`mvn -pl civstudio-engine exec:exec -Dsim.main=com.civstudio.mission.export.MissionExporter`. Pinned by
`MissionExporterTest`. The **campaign layer** then takes the thin slice it needs (tag, name, icon,
`flavored`, pitch) and joins placement; the full tree data is there for a later "mission trees as
content" feature.

## The data model — a `Campaign`

Keyed by **`countryTag`** (missions are the source of truth):

| field | source | notes |
|-------|--------|-------|
| `countryTag` | mission `potential { tag = … }` | primary key (→ heraldry + real localised name) |
| `name` | tag → country loc | canonical name; the grid's display name is a fallback |
| `icon` | mission `icon` / country shield | the mark on the wheel when no flag |
| `flavored` | `generic = no` | a real nation tree vs a shared pack |
| `isAdventurer` | the company list (G1) | scopes the SP wheel |
| `goodEvil` / `orderChaos` | **grid overlay, by tag** | −5…+5; **heuristic** where the grid is silent (below) |
| `contentPack` | grid column / mission DLC gate | `base` \| `final_empire` \| `fires_of_conviction` \| `forbidden_valley` \| `scions_of_sarhal` |
| `blurb` | grid pitch where present, else mission loc | the curated CSV pitch is nicer marketing copy; fall back to the tree's own loc |
| `provinceId` | tag → capital province | needs the tag→capital map (from EU4 history, `docs/political-map.md`) |

**Placement of the unplaced (owner decision): heuristic, then hand-correct.** A campaign the grid does
not place gets an *estimated* `(goodEvil, orderChaos)` from its **culture / religion / government** (evil
faiths → Evil, monstrous cultures → Chaos, ordered theocracies/legalist reforms → Order, …), flagged
`aligned: "auto"` so a curator can see and fix it. The grid always wins where it speaks.

## The exporter pipeline

Mirrors every other reference dataset — **exporter → Strapi seed → `/api/world-bundle` →
`StrapiWorldSource` → `/api/bundle` → web** (`docs/studio-datamodel-rebuild-plan.md`). Concretely:

1. **`CampaignExporter`** (dev tool) reads `missions/*.txt` via the `AnbennarFiles` seam, groups mission
   series by their `potential` tag, and emits a `Campaign` per tag (name, icon, `flavored`).
2. **Company filter.** Intersect with the adventurer-company set (G1) → `isAdventurer`.
3. **Placement.** Join the alignment grid **by tag** for authored `(goodEvil, orderChaos)` + curated
   blurb (the mockup's normaliser already resolves 27/49 grid names→tags; the rest go in a committed
   override map). Where the grid is silent → the culture/religion/government **heuristic**.
4. **Province.** `countryTag` → founding province via a tag→capital map (the one new lookup to build).
5. **Seed + serve.** Seed a Strapi `campaign` collection (extend `seed.js`) → `/api/world-bundle` → the
   web bundle. Campaigns are content, so they ride the **content-version**, not the RNG seed.

This is the natural first customer of a **Strapi MCP** (the owner's earlier question): once the exporter
seeds the collection, "reposition Arakeprun to Order +1" or "write a pitch for the Sword Covenant" is
exactly the content-authoring an MCP over the collection makes conversational — against **local/dev
Strapi**, with the exporter/seed as the committed source of truth.

## Heraldry

Reuses the shipped flag atlas (`docs/flags.md`: `web/build-flags.mjs` → `flag-atlas.webp` +
`window.FLAGS`, keyed by tag). The picker draws a realm's flag by `FLAGS.index[countryTag]`; the
mockup pre-crops the 27 matched flags to keep the artifact self-contained, but in the app the picker
reads the live atlas exactly as the Nations overlay does (`js/overlays/political.mjs`). Coverage grows
as the override map fills in tags for the reworks/formables.

## Difficulty — two selectors, reconciled

Per `docs/session-management.md` §Two difficulty selectors:

1. **Civ4 handicap** — the numeric knob, from the Phase-F catalog (`/handicaps.json`). The player-facing
   list is the ladder **Settler → Deity plus Nightmare**; `Nightmare Plus` and `AI Boosted` are dropped
   from the selector (AI-testing rungs, not player difficulties) — the catalog still imports all 12, the
   *selectable* set is the subset.
2. **Alignment position** — the *flavour*: Order-&-Good is a gentler start than an Evil-Chaos underdog.
   Illustrative in the mockup (the ★ rating); it does not change the sim until handicap effects land.

## Founding integration

The picker's *Begin* carries the choice into the existing create path (`POST /api/sessions`, the
`SessionKind`/`mode`/`difficulty` model): `provinceId` from the campaign, `difficulty` from the
handicap dropdown, and a new `campaign`/`startTag` founding field (the alignment `(goodEvil,
orderChaos)` + `contentPack` recorded alongside — the deferred columns noted in
`docs/session-management.md` §The persisted model).

## Phasing

| Step | What |
|------|------|
| G1 | **Mission importer — DONE** (`MissionExporter`: all `missions/*.txt` → `missions.json`, all fields, all countries). Next: a `CampaignExporter` that folds it to one `Campaign` per tag (name/icon/`flavored`) and scopes the SP set to **`B02`–`B20`** (`isAdventurer`) |
| G2 | **Placement:** grid overlay by tag (27/49 auto + override map) + the culture/religion/government **heuristic** for the rest; tag → capital-province map |
| G3 | Strapi `campaign` collection + `seed.js` + `/api/world-bundle` + bundle reader (the picker reads live data, not the baked CSV) |
| G4 | the astrolabe picker in the lobby's New-Single-Player flow (port the mockup); scoped to the companies, others behind a search/list |
| G5 | wire *Begin* → the founding path (`campaign`/`startTag`/`difficulty`); content-pack gating |
| G6 | apply the alignment/handicap to the sim (difficulty *effects* — the long pole, shared with Phase F) |

Open interpretations to confirm (all cheap to change): the corner ethos names ("The Establishment /
Free Hearts / Iron Fist / Damned"), and the expansion→column mapping used for the pack filter — both are
readings of the owner's grid, not decisions.
