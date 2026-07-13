# The Privy Council — a Civ4-style advisor-mode framework

**Status:** design / plan (nothing implemented). Written 2026-07-13; decisions locked with owner.
The presentation foundation the tech-tree building work sits on — it **precedes**
[`c2c-building-import.md`](c2c-building-import.md) (whose per-node building grid + rail inspector plug
into the Technology Advisor this creates). Further-out in-world work: [`district-generator.md`](district-generator.md).

## Context

The frontend's tech tree is a **full-screen modal** (`#techModal`) that covers the map *and* the right
rail, and the top bar carries three ad-hoc toggle groups (POV God/Timeline · plane Overworld/Underworld
· overlay None/Nation/Culture/Faith/Spectate). This reorganizes them into an **extensible, Civ4-style
"Advisor" mode system**: one top-bar advisor selector where each advisor is a mode that fills the canvas
region (the tech tree *replaces* the map; the political advisors *recolor* it), with Civ4 names +
hotkeys and **race-based Anbennar advisor portraits** of the leader's court. Converting the tech tree
from a modal into the **Technology Advisor map-mode** is the specific unblock: it frees the right rail
to host the tech/building inspector (which the modal makes impossible — see `c2c-building-import.md`
§3b/§4).

**Scope:** the advisor-mode **presentation framework** + the tech-modal→Technology-Advisor conversion +
advisor portraits + Zeitgeist + the unified search. The **C2C building import** is the follow-on
(*Roadmap*).

## Locked decisions

- **Advisors use Civ4 names + hotkeys.** The framework is **extensible** (future Domestic F1 / Financial
  F2 / Civics F3 / Military F5 slot in). v1 advisor set, each a top-bar segment:

  | Advisor | Key | Canvas | Underlying state today | Per-advisor sub-controls | Portrait role |
  |---|---|---|---|---|---|
  | **Main Map** (default) | `` ` `` / Esc-to-map | physical map | `overlay=none` | cost toggle | — |
  | **Foreign Advisor** | **F4** | map recolored | `overlay=nation` (+ **Culture folded here**) | Nation / Culture | diplomat |
  | **Religion Advisor** | **F7** | map recolored | `overlay=faith` | — | theologian |
  | **Technology Advisor** | **F6** | **tech tree replaces map** | `techOpen=true` | era tabs, zoom | natural scientist |
  | **Globe View** | **F11** | map + plane | `plane` (Overworld/Underworld) + `pov=god` | Overworld / Underworld | navigator |
  | **Zeitgeist** (live) | `Z` | map + live SSE feed | `overlay=live` | play/speed clock · **login/logout + player handle** | — (LIVE badge) |

  - Culture folds **under Foreign (F4)** (a sub-view, not its own mode).
  - Zeitgeist = the Spectate/live feed; its indicator is a **Twitch-style "LIVE" badge** (pulsing red
    dot) — "easy to recognise, the way Twitch shows a streamer is live".
  - **Map-only controls disappear** when not in their advisor (each shows only its sub-controls).
    **Zoom (`+`/`-`) persists** across all advisors.
- **Advisors are real people from the leader's court — needs server support.** Each advisor slot is
  filled by a named member of the **leader's court — the aristocracy/nobles** (currently Dhenijansar's):
  the advisor's **name** is that person's name and their **race** selects the portrait.
  > **Terminology (contradiction check):** `Retinue` is a **specific class = the faceless peasant labor
  > pool** (CLAUDE.md; `docs/peasant-pool.md` — laborers have "no account of their own"), so advisors do
  > **not** come from `Retinue`. They come from the **named aristocracy** (raised by ennoblement, with
  > households + same-dynasty succession) and the ruler's household. The tech tree is likewise
  > "ruler-funded science from the **aristocracy's INTELLECTUAL labor**" (CLAUDE.md), so drawing the
  > Technology advisor from the nobles is consistent, not new.
  So the server must expose the colony's advisor roster — per role, `{ personId, name, race, gender }` —
  in the render snapshot / bundle (new data). Which noble fills which role is an **engine design point**
  (e.g. by skill: top INTELLECTUAL noble → Technology; or a fixed court mapping) — a server contract,
  not a fixed label.
- **Portrait art = Anbennar race-based.** `gfx/interface/advisors/<Race>/<role>_<gender>.dds` (verified:
  158+ files, races × ~26 roles × male/female — natural scientist, statesman, diplomat, theologian,
  treasurer, grand captain, navigator, …), fetched via `anbennar.mjs`, decoded by `web/dds.mjs`, baked
  to WebP. The portrait shown = `role × the assigned noble's race × gender`; fallback to a generic race
  when a race lacks the art.
- **Search unifies into the one top-bar box, mode-scoped:** provinces in map-based advisors, techs (+
  buildings later) in Technology; per-mode placeholder; a **quick-open hotkey** (`/` or Ctrl-K);
  interleaved results + kind chips (per `c2c-building-import.md` §4a).
- **Top-bar layout:**
  - **Selector = a row of race-portrait buttons** — one small Anbennar portrait per advisor, active one
    highlighted (Zeitgeist shows the red LIVE dot rather than a portrait). Replaces today's three groups.
  - **Future advisors show as greyed placeholders** — the unimplemented Civ4 advisors (Domestic F1,
    Financial F2, Civics F3, Military F5) appear disabled with their F-keys (like Timeline today).
    Suggested order: Civ4 F-key order for the F-keyed advisors, with **Main Map** first and **Zeitgeist**
    last as CivStudio-specific.
  - **A second row below the top bar** holds the **active advisor's sub-control strip** (swaps per
    advisor): Foreign→Nation/Culture, Globe→Overworld/Underworld, Technology→era tabs + tech zoom,
    Zeitgeist→auth handle/logout + play/speed transport, Main Map→cost toggle. The top row stays stable
    (brand · zoom/band chip · portrait selector · search).
  - **Large portrait in the rail header** — entering an advisor heads the right rail with its large
    race-portrait + advisor name (Civ4-style), above the inspector content.

## Approach — advisor selector *over* the existing render states (least-invasive)

Do **not** rewrite the render pipeline. Every functional mode already follows one seam: `.segToggle`
markup → a field on `S` (`core.mjs`) → a `setX()` handler in `panel.mjs` (sets state, repaints
`aria-pressed`, `draw()`) → render responds via `gate`/`z` predicates in the `LAYERS` registry
(`layers.mjs`) and helpers `isPolitical()`/`activeZ()`. `setOverlay`/`#overlayToggle` (panel.mjs
366-389) is the exact precedent; the `pov`/`#povToggle` slot is **inert today** (set, never read) — the
natural graft point.

So the **advisor selector** is a thin grouping *above* the existing `S.overlay` / `S.plane` /
`S.techOpen` states: choosing an advisor sets those low-level states (which the unchanged `LAYERS` gates
already honor) and swaps which sub-controls + portrait + search corpus are shown. The render layer
barely changes; the work is top-bar restructure + the tech-modal→stage conversion + the portrait bake.

## Implementation

**New modules (don't grow the existing ones).** Feature code lives in **new** `.mjs` files; existing
modules are touched only at thin integration seams.
- `web/js/advisors.mjs` — advisor framework: `advisorMeta` table, `setAdvisor()`, the portrait selector,
  the second-row sub-control strip, advisor→(overlay/plane/techOpen) mapping, `init()`.
- `web/js/advisor-detail.mjs` — the person + household **character sheet** rail render + roster read +
  succession handling.
- `web/js/toast.mjs` — a small toast primitive (only if none exists to reuse).
- `web/build-advisors.mjs` — the portrait bake.
- Existing files touched only at seams: `core.mjs` (one `S.advisor` field), `panel.mjs` (`renderRail`
  **dispatches** to `advisor-detail.mjs`; existing rail fns stay), `index.html` (markup), `shortcuts.mjs`
  (keys call `advisors.mjs`), `layers.mjs` (gates unchanged), `main.mjs` (paint suspend), `techtree.mjs`
  (retarget container; render pipeline unchanged).

### 0. Server support — the advisor roster + person/household detail (engine → feed)
The advisor names + races come from the **leader's court**, are **mortal**, and the player can drill
into each person's full record — none of which the feed exposes today.
- **Engine — roster**: assemble a per-colony **advisor roster** — for each role, the assigned **court
  member (a named noble)** `{ personId, name, race, gender }`, drawn from the **aristocracy + ruler
  household** (not the faceless `Retinue` peasant pool). Selection rule is an engine design point
  (recommend **by skill/sector** — top INTELLECTUAL noble → Technology, top diplomat/statesman →
  Foreign, … falling back to any noble). Deterministic, no economic RNG.
- **Engine — mortality & succession**: advisors die (mortality is always on) — when the noble holding a
  role dies, the **selection rule re-runs and auto-picks a successor** from the aristocracy, so the
  roster is **dynamic over time** (name + portrait change on succession). Reuses the existing successor/
  replacement machinery; no new lifecycle.
- **Server/feed — roster**: add the roster to the render snapshot / bundle (keyed by advisor role) for
  the POV/leader colony (Dhenijansar in the demo); allow-list through `WorldBundle` if it rides the
  bundle. It updates as advisors succeed.
- **Server — person + household detail**: expose a **person detail** the inspector requests by
  `personId` — the full record of that advisor and **their household** (name, race, age, skills/passions,
  family/household members, and their fates). A read-only endpoint (e.g. `/api/person/<id>`) or an
  expanded roster entry; reuses the engine's household/skill/mortality state.
- The frontend reads the roster to label each advisor + pick its portrait, and the person-detail to fill
  the rail inspector (§2b).

### 1. Advisor state + top-bar selector — `web/js/advisors.mjs` (new)
- The `advisorMeta` table (id → `{label, key, role, subControls}`) — the single extensible source
  (future advisors = one row). The current advisor is a single `S.advisor` field on `core.mjs`
  (alongside `S.overlay`/`S.plane`); `advisors.mjs` owns the table + all logic.
- `setAdvisor(id)` (modelled on `panel.mjs setOverlay`): set the advisor, **map it to the low-level
  render states** (`S.overlay`/`S.plane`/`S.techOpen` — the render truth the unchanged `LAYERS` gates
  honor), swap the second-row sub-controls + portrait + search corpus, `draw()`.
- Builds/wires the `#advisorToggle` portrait selector and the `#advisorSubbar`.
- **`index.html`**: replace the three groups in `#mapControls` (POV/plane/overlay) with `#advisorToggle`
  (race-portrait buttons, `data-advisor`, unimplemented ones greyed `disabled`) and add a **second bar**
  `#advisorSubbar` — the existing Nation/Culture, Overworld/Underworld, cost toggle, `#clock` transport,
  and tech era tabs **move here**, shown per advisor. Top row: brand · `#zoomLevel` chip · `#advisorToggle`
  · `#search`; the zoom `.zoomctl` untouched (persists).
- **Seams only:** `panel.mjs`'s `setOverlay`/`setPlane`/`setPov` become the sub-control handlers
  `advisors.mjs` calls (not rewritten); `boot()` calls `advisors.init()`.

### 2. Tech modal → Technology Advisor map-mode (`techtree.mjs`)
The render pipeline (`ensureLoaded`, `build`, cards/edges, `highlight`, `select`, search, zoom/pan) is
container-agnostic; only `cacheEls()`, `open()/close()` (toggling `#techModal.hidden`), and the
full-screen `#techModal` markup are modal-coupled.
- Render the tech **body over `#stage`** instead of the top-level `#techModal` (drop the `dialog`/
  backdrop chrome; era tabs move into `#advisorSubbar`). The rail is a **sibling of `#stage`**, so it
  stays.
- **Keep `S.techOpen`** as the `paint()` suspend hook (`main.mjs:183`) — set when
  `S.advisor==="technology"`; leaving the advisor calls `draw()` to resume the map.
- **Inspector in the right rail:** reuse `panel.mjs showRail()`/`renderRail()` — `select(tech)` opens
  the rail (its own `techRail` render, not in panel.mjs) instead of the modal-local `#techDetail`.

### 2b. Advisor person + household rail detail (character sheet) — `web/js/advisor-detail.mjs` (new)
When in an advisor, the right-rail header shows the advisor's **large portrait + name + role/race**;
clicking it **expands the rail into a scrollable character sheet**. `panel.mjs renderRail` gains one
branch **dispatching** to `advisorRail(personId)` (follows the `provinceRail`/`cityRail` template,
reuses `showRail`). Contents, from the server person-detail (§0):
- **Stats**: name, race, age, role; **skills as bars + passion (flame) icons** with numeric level —
  highlight the skill that earned the advisor role.
- **Household**: a **flat member list** — each `name · relation · age`, lightly linked (click → re-render
  the sheet for that `personId`; a back link returns).
- Lazy-loads by `personId` (like `loadPlots` refreshing the rail on arrival).

**Succession notification (toast + event log):** when the roster changes (an advisor died and a
successor was auto-picked), show a **transient toast** (`web/js/toast.mjs`) + append a line to the
**event log** (`web/js/livelog.mjs` / the SimLog feed), and update the portrait + name in the selector
and rail. Handled in `advisor-detail.mjs` on roster change.

### 3. Advisor portraits (new bake) — `web/build-advisors.mjs`
For each advisor **role** × each race with art, fetch `gfx/interface/advisors/<Race>/<role>_<gender>.dds`
via `anbennar.mjs`, decode with `web/dds.mjs`, pack to `web/assets/advisors/advisor-portraits.webp` +
a manifest `advisorPortraits` index `{race:{role:cell}}`. Add the key to `web-asset-manifest.json`
**and `WorldBundle`'s allow-list**. Fallback race when art missing; log coverage. Frontend: per advisor,
read its roster entry → show the portrait in the selector + the large portrait + name in the rail header.

### 4. Zeitgeist — live + account (LIVE badge, login/logout, player handle)
Zeitgeist is the **live/social/account** advisor. It coheres: the play/pause/speed transport **requires
a signed-in user** (`auth.mjs`, `docs/authentication.md`), so live + auth belong together.
- **LIVE badge:** pulsing red "LIVE" badge (CSS, Twitch-style), active only while the live SSE feed is
  connected — reuse `startLive`/`stopLive` from `setOverlay("live")`.
- **Login/logout + player handle:** move the top-bar `#siteAuth` (rendered by `auth.mjs`; shows the
  handle `me.displayName || me.id`, `logout()`) and the `#clock` transport **into the Zeitgeist
  sub-control strip**. `auth.mjs` still renders into `#siteAuth`; only its DOM home moves.

### 5. Unified mode-scoped search
One box (`#search`/`searchbox.mjs`); `updateSearchContext()` goes **per-advisor** — provinces (map-based
advisors), techs (Technology; buildings later); per-mode placeholder; interleaved results + kind chips
for the tech corpus. Retire the modal-local `#techSearch` (its `searchApi` moves onto the top-bar box).
**Quick-open hotkey** via `shortcuts.mjs` (`/` or Ctrl-K → focus `#search`).

### 6. Hotkeys (`shortcuts.mjs`)
REGISTRY entries: **F4** Foreign, **F6** Technology (F7 was tech → becomes Religion), **F7** Religion,
**F11** Globe View, **Z** Zeitgeist, `` ` ``/Esc → Main Map — each calling `setAdvisor(id)`. The existing
`S.techOpen`-gated "suppress map controls" logic keeps working for the Technology advisor automatically.

## Contradiction check (vs. CLAUDE.md + docs)

- **`Retinue` clash — resolved:** advisors draw from the **named aristocracy + ruler household**, not
  the `Retinue` peasant-pool class. Wording corrected throughout.
- **`docs/tech-tree.md` supersession:** it states the tree "renders as a **full-screen modal**" — this
  replaces that with the Technology Advisor map-mode; update that section when this lands.
- **Vocabulary:** avoid **"regime"** (reserved for the zoom band-spine, `docs/zoom-bands.md`); use
  "advisor mode".
- **No conflict** with the read-only spectator model: the roster + person detail are additional
  **read-only** feed data / a read-only endpoint.

## Roadmap (follow-on)

**Sequencing: this lands _before_ `c2c-building-import`** — that plan's per-node building grid + rail
inspector plug into the Technology Advisor map-mode + freed right rail created here. Only the building
plan's pure **data** side (`BuildingInfoExporter` → `buildings.json` + the `building-icons.webp` bake)
is independent and may run in parallel.

- **C2C building import** (`c2c-building-import.md`): the building grid + inspector + engine
  `Unlock`/auto-build in the Technology Advisor.
- **District generator** (`district-generator.md`): the in-world district view over the district-plot map.

## Verification

- **Bake**: `node web/build-advisors.mjs` produces `advisor-portraits.webp` with the Anbennar cache
  present; and completes (skipping portraits) with it absent — the graceful-fallback smoke test.
  `node --test web/*.test.mjs` stays green.
- **Server**: `mvn -pl civstudio-engine install -DskipTests` then `mvn -pl civstudio-server spring-boot:run`.
- **Visual (`tools/webverify`)**: screenshot each advisor — Main Map, Foreign (Nation+Culture),
  Religion, **Technology (tree fills `#stage`, top bar + rail present, click a tech → rail inspector)**,
  Globe View (Overworld/Underworld), Zeitgeist (LIVE badge, live feed, sign-in/handle/logout +
  play-speed transport — signed-out and signed-in). Confirm each advisor shows the **court noble's name +
  race-appropriate portrait** (from Dhenijansar's roster) in the selector + rail header, greyed
  placeholders for the unbuilt advisors, the sub-control second row swaps per advisor, map-only controls
  hide per mode, and zoom persists. Exercise the hotkeys and the unified search.
- **Regression**: political overlays still recolor; underworld plane still filters `z:[-1]` layers;
  Escape returns to Main Map; the live SSE feed still drives Zeitgeist.

*Written 2026-07-13, decisions locked with owner. Precedes `c2c-building-import.md`; when it lands,
revise `docs/tech-tree.md` (modal → map-mode) and add a one-line pointer in `CLAUDE.md`.*
