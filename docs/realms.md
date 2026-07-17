# Realms

**Status:** design, not built. 2026-07-17.

A **Realm** is a map. Today CivStudio has exactly one — the whole cylindrical world, 5264 provinces,
wrapping horizontally at 360° of longitude. This doc splits it into three, each cropped to its own
pixels, each unaware the others exist.

The Old World realm is named **Halann** (decided). The name was free: the province of that name
(id 1173 — a dev-1/1/1 uncolonised Ringlet Isles islet) is on the map for an EU4 technical constraint
rather than as a place, which is the same single-map artifact as everything else in this doc. Halann is
strictly the planet's name and all three realms sit on it, so the label is a deliberate imprecision, not
an oversight.

| Realm | Land | Water | Total | Crop | Playable |
|---|---|---|---|---|---|
| **Halann** | 3423 — `europe`, `asia`, `africa`, `serpentspine` | 188 | **3611** | 2532px (45.0%) | yes |
| **Aelantir** | 1378 — `north_america`, `south_america` | 178 | **1556** | 2369px (42.1%) | yes |
| **Hinuilands** | 2 — `oceania` | 0 | **2** | 162px (2.9%) | no — viewable only |
| *(no realm)* | 3 quirks + 1 continent-less | 91 deep ocean | **95** | — | fogged everywhere |

3611 + 1556 + 2 + 91 + 3 + 1 = **5264** — it balances against the imported map exactly. (Land counts are
below the raw continent totals because ~50 `SEA`/`LAKE` provinces carry a continent and are counted as
water here: water is assigned by adjacency, never by continent.) Phase 0 adds 4 more.

The Serpentspine underworld stays **inside Halann** as its existing `z:[-1]` plane — realm and z are
orthogonal axes (§Realm is not z).

## Why this exists

**EU4 cannot have separate maps.** Everything must live on one cylinder, so Anbennar's modders faked
their second and third worlds: Aelantir is a real landmass across a real ocean, but the Hinuilands —
meant to be elsewhere entirely — became two isolated provinces stranded in the Pacific with no route in,
plus 135 reserved-but-unpainted placeholders and a teleporter network wired to nothing.

CivStudio is not EU4 and has no such limit. **Realms is us lifting the limitation Anbennar worked
around.** That framing decides the open questions below: where the data looks broken, it is usually a
workaround for the single-map constraint, and the fix is to stop reproducing the workaround.

## What the map data actually says

Every claim here is verified against `civstudio-engine/src/main/resources/generated/map/` and the
Anbennar source at `.anbennar-cache` (→ `C:\Code\anbennar-eu4-dev`).

### Hinuilands is not painted, and nothing reaches it

`hinuilands_superregion` has three regions — Titanoflora Riverlands, Lakelands, Forests — whose areas
reference **135 province ids**. Exactly **one** is real. The rest are reserved placeholders in
`map/definition.csv`, unpainted on `provinces.bmp`, so the importer correctly skips them:

```
3333;190;125;119;UnusedLand143_#be7d77;x
3083;47;252;249;Unused70_#2ffcf9;x
```

Every `titanoflora_forests_region` area (`arihan_area`, `manuata_area`, …) has **zero** provinces. The
modders reserved the names and never drew the map.

What survives is `Continent.OCEANIA` — **Vyr Pas** (3060, `GLADEWAY`, 739 plots) and **Vyr Cirentyn**
(3061, `LAND`, owner N57, culture holoino). Both are **graph islands: zero neighbors**. In Anbennar's own
source, Vyr Pas has no adjacency at all, and Vyr Cirentyn has exactly one:

```
3061;3370;sea;3598;-1;-1;-1;-1;Insyaa        # 3370 = UnusedLand180, 3598 = Anbennar3598
```

Both endpoints are **painted** (364px and 5414px) but placeholder-*named*, so our name filter drops them
(§Teleporters). Even with them, the route reaches only more reserved ground.

So in practice there is **no route to Hinuilands**. You reach it by switching the dropdown, not by
travelling. Hence: viewable, not playable. This is the single-map limitation in its rawest form — a
realm that exists only as coordinates, because EU4 gave it nowhere else to be.

### Teleporters are real, and we drop half of them

Anbennar's `adjacencies.csv` names them literally:

```
7025;3050;;-1;-1;-1;-1;-1;Deepwoods_Teleporter
3050;3051;;-1;-1;-1;-1;-1;Deepwoods_Teleporter
6258;3050;canal;...;deepwoods_fey_portal
6241;6242;canal;...;domandrod_fey_portal
```

92 portal rows in total: 64 `Deepwoods_Teleporter`, 14 `deepwoods_fey_portal`, 9 `domandrod_fey_portal`,
and five seasonal gates (`domandrod_summer_gate`, `spring`, `autumn`, `winter`, `winter2`).

**We import them.** They arrive in `adjacencies.json` as `type:""` rows carrying the comment, they ride
in `WorldMap.combinedNeighbors`, and **`LandRouter` already traverses them today.** The teleporter
mechanic exists and works; it is only the *marker* that is missing, because the `teleport` flag is a
rendering heuristic (`WorldBundle.java:233`, `gcKm > TELEPORT_KM` where `TELEPORT_KM = 800`) and the
gladeways sit close together, so they draw as ordinary connection lines.

> **We drop 41 of the 92 portal rows, and all of them are needed.** Every dropped row has an endpoint of
> `7025`, `7027`, `7030` or `7033` — absent from `provinces.json`. What is lost, by row type:
>
> ```
> Deepwoods_Teleporter    kept 28 | dropped 36
> deepwoods_fey_portal    kept 10 | dropped  4
> domandrod_fey_portal    kept  8 | dropped  1
> the five seasonal gates kept  5 | dropped  0   <- fully intact
> ```
>
> So the *gates* survive; it is the **Deepwoods mesh** that is gutted — more than half of it.
>
> **This is not an importer bug — it is a deliberate filter with an unanticipated consequence.**
> `ProvinceExporter.java:134-138` skips provinces whose `definition.csv` name is a placeholder
> (`RNW*`, `Unused*`, or the auto-generated `Anbennar<digits>` pattern), documented at `:82-85`. That
> filter is *correct*: 6661 provinces are painted on `provinces.bmp`, we keep 5264, and the 1397 dropped
> are overwhelmingly RNW filler and unnamed ocean (`Anbennar1405`, 99418px).
>
> The four portal endpoints are collateral. They **are** painted — 100px, 91px, 36px, 33px — and they are
> not junk land: **Anbennar uses placeholder-named provinces as functional teleporter waypoints.** The
> name is a placeholder; the role is not. This is the single-map limitation again — a hub that exists
> only to make the portal graph work has no reason to be given a name.
>
> **Fix: whitelist provinces referenced by a portal adjacency row**, rather than loosening the name
> filter (which would drag in all 1397). Independent of Realms; fix it regardless.
>
> The same filter strands the one sea route Anbennar drew toward Hinuilands —
> `3061;3370;sea;3598;…;Insyaa` dies because `3370` is `UnusedLand180` (364px, painted) and `3598` is
> `Anbennar3598` (5414px, painted, a sea province). Both are real pixels behind placeholder names. We
> leave that route dead by decision, not by accident (§Deferred).

The only rows the 800km flag *does* fire on are four accidents, all within one continent:

```
2193km  canal  Ee Teah -> Fospont            [north_america]
1137km  canal  Sachkriok -> Fospont          [north_america]
1057km  sea    Talyasgam -> Taldaayo         [asia]
 935km  sea    Xaybatencos -> Crooked Island [north_america]
```

Nothing links Halann to Aelantir.

### The partition is free — zero cross-realm edges

Not one land province in Halann pixel-touches Aelantir, and not one of the 408 water provinces touches
land in more than one realm. The only three cross-*continent* adjacencies all stay within a realm:

```
395km  sea    Altarcliff (north_america) -> Chesh (south_america)      # both Aelantir
 35km  canal  Marrhold (europe) -> Natvirod 2 (serpentspine)           # both Halann (underworld)
200km  canal  Nooks Cranny (serpentspine) -> Noms10 (asia)             # both Halann (underworld)
```

The realms are already disconnected components of the province graph. Splitting them **cuts no edge**,
so `WorldMap.path()` and `LandRouter` need no realm-awareness: a route that could never leave a realm
cannot start doing so because we drew a smaller map.

### The ocean splits cleanly — by adjacency, not by reachability

BFS over water from each coast is useless: 419 water provinces reachable from Halann, 336 from
Aelantir, **301 shared**. Multi-hop reachability says nothing about ownership.

**Anbennar's sea superregions cannot help — they are empty shells.** `map/superregion.txt` names all
eight, and every one has an empty body:

```
north_pacific_sea_superregion = {
	
}
```

(An earlier draft of this doc proposed importing them. There is nothing to import. Verified against all
eight: `west_american_sea`, `east_american_sea`, `north_european_sea`, `south_european_sea`,
`west_african_sea`, `east_african_sea`, `indian_pacific_sea`, `north_pacific_sea`.)

**Adjacency answers it exactly.** Assign each water province the realm of the land it *touches*:

```
CLASSIC   188 water provinces
AELANTIR  178
deep ocean (touches no land)  91   -> fog
CONFLICTS (touch 2+ realms)    0
```

188 + 178 + 91 = 457 — all of it, unambiguously. Zero conflicts is not luck: no water province touches
land in more than one realm (§The partition is free).

**And "deep ocean" is a real category, not an invented one** — 91 provinces touch no land at all. That
is precisely the water that should be fogged, and the data volunteers the set. No threshold, no
heuristic, no tuning.

Crops stay contiguous with each realm's water included — Halann 2532px (45.0%), Aelantir 2369px
(42.1%) — so this costs the pure crop nothing.

## The model

**A realm is a partition of provinces.** No new province ids, no generated geography. `Continent`
already carries the Anbennar display names (`Continent.java:33-39`) — both Americas map to Aelantir,
`OCEANIA` to Hinuilands. The enum was written for this.

**Two sources, one field.** Realm is *not* a pure function of `Continent`: the 408 water provinces have
`continent: null`, so their realm comes from the sea superregion (§The ocean is one body) while land's
comes from `Continent`. Resolve both **in the exporter** and ship a single `realm` key per province in
the bundle. The frontend must never re-derive it — that is how `CONTINENT_NAME` ended up with three
copies.

> **Drift warning.** `CONTINENT_NAME` is hardcoded a second time in `WorldBundle.java:72-81` and a third
> in `web/build.mjs`. A realm mapping must not become a fourth copy. Ship the realm key **in the bundle
> per province** and let the frontend read it, rather than re-deriving continent→realm in JS.

### Realm is not z

The underworld is `z:[-1]` *within Halann* — you walk into it from Cannor; no ocean, no portal. So:

- **realm** — which map you are looking at. Dropdown. Crops the view.
- **z** — which level of that map. Button. Filters `LAYERS` via `activeZ()` (`core.mjs:102`).

They compose: `(Halann, z=0)`, `(Halann, z=-1)`, `(Aelantir, z=0)`. Aelantir has no underworld (zero
Dwarovar provinces outside `europe`/`asia`/`serpentspine`), so its plane button hides.

**Decided: two separate controls**, not one flat list with "Halann (Underworld)" in it. `layers.mjs`
already filters on a z-**set** and generalises cleanly; folding realm into it would overload one axis
with two meanings and tangle `activeZ()`.

### Ocean and fog

Fog is **decorative**: it marks *this is not here*, not *you have not explored this*. The rule is
symmetric — you cannot see Aelantir from Halann, and **on the Aelantir and Hinuilands maps there is no
middle landmass**. Each realm keeps the water touching its own coast (§The ocean splits cleanly); every
other realm's land, every other realm's water, and the **91 deep-ocean provinces that touch no land at
all** are fog.

The baked art is already in the tree and has never had a consumer: `FOW_TILE` (`web/civ6.mjs:217-246` —
`HATCH_MED`, `HATCH_MED_LIGHT`, `HATCH_LIGHT`, `PARCHMENT`), baked by `bakeFowTiles()`
(`build.mjs:1226-1244`) as tileable greyscale luminance masks, shipped as `fow`
(`WorldBundle.java:246-251`). This is its first use.

> `build.mjs:1230` notes the art was baked ahead of the per-settlement `RevealedMap` (explorer-caravan
> Phase 6, unbuilt). **Realm fog is a different consumer of the same art**, and the two are orthogonal:
> realm fog says "not here", explored fog says "not seen". If Phase 6 lands they stack.

Hinuilands is ~all fog with two revealed provinces, so fog does 99% of its visual work. It uses **hatch,
not parchment** (decided) — the realm reads as dim and unexplored rather than as blank paper, which is
the honest impression: Anbennar reserved 135 provinces there and drew two.

### The fog must not be mute

Decorative fog has a failure mode: it says *nothing is here*, when the truth is *something is here, on
another map*. A player who never opens the dropdown never learns Aelantir exists.

**The cue belongs on the realm's outline** — the province edges where the realm meets the fog — not on an
interior marker. That outline *is* the place you leave from, so it should read as one: a border you cross,
not a border that stops you. **The whole outline is rimmed** (decided), so no stretch of the boundary is
mute; where a teleporter sits on that edge, **a red arrow expands outward over the fog**, pointing the way
to a place this map cannot show.

The arrow is **not animated** and **carries a text label** — `to Aelantir`, `to Halann` (decided). A bare
arrow says *something is out there*; a labelled one says *what*, which is the entire point. And it is
**clickable** (decided): clicking it switches realm. So the arrow is the discovery path and the dropdown is
the power-user route, rather than the dropdown being the only way to learn the other realms exist. Both
fire the same switch-realm action (Phase 5).

Red because the fog tiles are greyscale luminance masks (`FOW_TILE`) with no colour of their own, so a
warm hue owns the layer without fighting it. There is no arrow art in the tree — the existing teleport
marker is a hand-drawn cave-mouth glyph at `TELEPORT_SCALE = 4` (`main.mjs:305-307`), and the arrow joins
it as canvas paths.

**The pattern already exists one level down.** `drawCavernRims` (`layers.mjs:68`, `z:[-1]`) rims the
underworld plane's boundary in amber for exactly this reason — to say *the plane ends here, and here is
where it opens*. A realm rim is that move one level up, and should be built as its own layer entry beside
it rather than folded into the fog draw.

This is what makes realms **discoverable rather than merely available**: the fog stops being an absence
and becomes a signpost. The arrow is only correct for an **off-realm** destination — the 92 Deepwoods
portal rows teleport *within* Halann, both endpoints on the same map, so they must not draw one.

## Rendering: the cylinder goes away

Each realm crops to its own provinces' pixel extent. There is no 360°, so there is no wrap.

### The trap

`worldW()` (`core.mjs:210`) is documented as *"one full 360° of longitude — the horizontal wrap period
of the cylindrical map."* It actually returns `cam.k * VIEW.dw`, and `VIEW.dw` comes from the **baked
crop rect** (`cw = MAP.x1 - MAP.x0`, `core.mjs:26-32`). It is 360° today only by coincidence — the
shipped bundle crops to the whole raster:

```json
"map": { "x0": 0, "y0": 0, "x1": 5631, "y1": 2047, "W": 5632, "H": 2048, "dw": 2816, "dh": 1024 }
```

The moment a realm crops smaller, `worldW()` silently becomes the realm's width and every wrap consumer
keeps working — **wrongly**. It tiles the realm side-by-side across the viewport forever, with no seam
and no error. This fails silently, not loudly, and is the most dangerous property of the change.

**Make `worldW()` honest: return `0` when the map is cropped.** `main.mjs:159` and `hittest.mjs:17-18`
already treat `period <= 0` as "single copy", so most of the wrap collapses correctly *by construction*.

### Three quirk provinces, and then no realm needs a roll

A naive bounding box of Aelantir spans **5344px — 94.9% of the world** — because three provinces sit on
the far side of the antimeridian from the rest:

```
North Toreiel  lon 173.16  LAND        sarmadfar_region  owner=undefined
South Toreiel  lon 169.00  LAND        sarmadfar_region  owner=undefined
Ekyunimoy      lon 124.12  IMPASSABLE  region=null       owner=undefined, zero neighbors
```

They are a **data quirk, and are dropped from the realm** (decided). In EU4 this is not a quirk at all —
`sarmadfar_region`'s other provinces sit at lon −150, and on a wrapping cylinder the region is perfectly
contiguous across the date line. It only becomes a quirk when you crop. That is the single-map
limitation showing up one last time, in the geometry.

With them gone, **every realm is contiguous and nothing rolls**:

| realm | crop width | contiguous? |
|---|---|---|
| Halann | 2532px (45.0%) | yes |
| Aelantir | **2279px (40.5%)** (was 5344px) | yes, once the three are dropped |
| Hinuilands | 162px (2.9%) | yes |

So Phase 3 is a **pure crop** — no roll, no per-realm x offset, no seam-straddling polygons. Keep it
that way: if a future province re-introduces a seam crossing, drop it or fix its continent rather than
resurrecting the roll.

> Hinuilands' two provinces are ~6000km apart (Vyr Pas at lat 25.96, Vyr Cirentyn at lat −28.3), so its
> 162px-wide crop is 321px tall and almost entirely empty. That is the honest picture of the realm.

### Keep the pixels absolute

The projection is **Mercator in y, linear in x** (`core.mjs:22-23`), and lat/lon are computed *from*
pixels at export (`ProvinceExporter.java:314-320`), not the reverse. Polygons are absolute source pixels
on the 5632×2048 raster (`ProvinceBorderExporter.java:45`) — `rings`, `bbox`, `lab` share that space.

The chain is already parameterised on the crop:

```
lon/lat --sxSrc/sySrc--> source px (5632x2048) --baseXr/baseYr--> base screen --pxr/pyr--> screen
                                                ^ normalises by (sp - MAP.x0) / (MAP.x1 - MAP.x0)
```

**So: hold `MAP.W`/`MAP.H` global at 5632×2048; crop only the window `x0..x1`/`y0..y1`.** The whole
polygon/label/plot stack then follows for free, and every engine consumer is untouched — `gcKm`
haversine (`WorldMap.java:939-964`), `SolarClock` (real-lat daylight), `WorldMap.path()` (province-id
topology) all read baked lat/lon and touch no pixels.

> **Re-basing pixels to a realm-local origin would corrupt solar times and caravan march distances with
> no exception thrown — just wrong numbers.** Don't. `build.mjs:472-480` already computes a clamped,
> margined crop rect; the bundle is ready to crop. Nothing downstream is ready to notice.

### The work

**Safe, no change:** all engine lat/lon consumers; `ProvincePlotStore`/`PlotService` (province-keyed,
seed-independent); `plotIndex`; `provGeo`/`GEO_NAMES`; `rings`/`bbox`/`lab`.

**Wrap-dependent, must change:**

| site | today | after |
|---|---|---|
| `main.mjs:154-170` | renders once per world copy, `period = worldW()` | single pass (`:159` guard already handles `period <= 0`) |
| `core.mjs:212-215` `clampPan` | `cam.x = ((cam.x % w) + w) % w` | clamp, not modulo — else panning east teleports you west |
| `hittest.mjs:12-20,35-37,59` | `wrapCopies()` shifts the cursor per copy | single copy |
| `minimap.mjs:70-76,102-104` | `fx0 % 1`, two-piece seam rect | clamp; single-piece branch |
| `political.mjs:86-87` | `for (k = -1; k <= 1)` tests ±1 copy | just the one |
| `bandcaption.mjs:95` | `worldW()` | — |

Labels, sea, borders, routes and adjacency lines have **no wrap code of their own** — they inherit it by
drawing inside the loop, and need nothing beyond the loop collapsing.

**Extent-dependent:** `sea.mjs` doesn't know where ocean is. It fills the *whole viewport* with a
latitude gradient (`:57-63`) and relies on the raster's ocean pixels being transparent. Cropped, it will
paint blue across the void. It needs an X clip (`main.mjs:140-147` already does the `#070a10` void fill +
Y clip — extend to X), or its existing off-ramp (`sea.mjs:32`, no `SEA_BANDS` → flat fill).

**Recompute per realm:** `rollupTier` geo label centroids (`WorldBundle.java:209,340`) are computed
globally; a continent/superregion centroid can land outside a realm's crop, putting labels in the void.

## UI

The masthead becomes the realm selector. Today `advisors.mjs:23` builds the globe entry as
`"Halann v" + BUNDLE.mapVersion`. It becomes a dropdown:

```
Lobby
────────────
Halann v9        <- current
Aelantir v9
Hinuilands v9
```

**The lobby lives in the dropdown.** This matters because the brand (`index.html:180`) is *currently*
the way home — `role="button"`, `data-tip="Back to the lobby · reset to the world view"` — and the brand
is losing its "Anbennar" half (`CivStudio: Anbennar` → `CivStudio`). Moving the lobby into the dropdown
lets the brand shrink without orphaning the affordance.

The plane (surface/underworld) button stays separate, and hides outside Halann.

### Deep links need a realm

`main.mjs:432-440` keys on `?p=<id>&z=<zoom>` — province id and zoom, no realm. A link to province 4211
is meaningless if the active realm's crop doesn't contain it: today it would silently frame empty void
rather than erroring. **Add the realm to the link**, and resolve province → realm on load so old links
still work (a legacy `?p=` auto-switches the realm under itself).

**Switching realms pushes history** (decided) — so back/forward navigates realms, and a realm is a
shareable URL. Each switch is a history entry; that is intended.

**An omitted realm defaults to Halann** (decided). So every legacy `?p=` link keeps working with no
migration, and a bare URL opens where it always did.

## Cost

**No plot rebake for Realms itself.** `MAP_VERSION` (`ProvincePlotStore.java:62`, currently 9) keys the
plot cache, and plot grids are per-province and seed-independent — *"a province's geography is a property
of the map, not of a run"*. Phases 1–6 change `provinces.json`/`superregions.json` and the bundle; they
do not change a single plot grid. So: no `MAP_VERSION` bump, no cache drop. The dropdown keeps saying
**v9**; only the word before it changes.

> **Phase 0 adds four provinces.** Whitelisting the portal endpoints means four *new* plot grids. The
> cache is keyed `<id>.json.gz`, so existing grids are untouched and the four generate lazily. They are
> **not** GeoNames candidates — prod cannot bake names anyway ([[plot-place-naming]]), and a real Earth
> place name would be wrong here: this is **space inside the portal**. They are named **Portal 1–4**
> (decided), authored at export, not drawn from GeoNames. So Phase 0 needs no CI bake either.

The server still needs a redeploy — the bundle is assembled from engine resources
(`WorldBundle.ensureCached()`), and `web/` auto-deploys on push while the server is manual. **Deploy the
server first** or the frontend ships against a bundle with no realm field.

## Phases

Ordering principle: **data before pixels, and the silent failure before the thing that triggers it.**
Phase 2 exists solely so the wrap can be killed and verified *while the map is still whole* — if the
crop lands first, every wrap bug appears at once, silently, with no baseline to diff against.

**Phase 0 — Restore the portal network.** Ships alone, no Realms code, independent value. Whitelist
provinces referenced by a portal adjacency row, defeating the placeholder-name filter for those only
(`ProvinceExporter.java:134-138`), and name them **Portal 1–4**. → 5268 provinces, **92/92 portal rows
survive** (from 51). Verify by count, not by eye.

> **Phase 0 is NOT behaviour-neutral, despite changing nothing visible.** The 41 restored rows become 41
> new edges in `WorldMap.combinedNeighbors`, which is what `LandRouter` walks. Caravans that route near
> the Deepwoods can start taking portal shortcuts the day this lands — emergent, unasked-for, and
> arguably correct, but it is a **sim change, not a data change**. Run the full engine suite, not just the
> server one, and expect the possibility of route-length fallout.

**Phase 0b — Author the realm portal.** The Halann↔Aelantir sea teleporter is **authored now** (decided),
as a visible landmark rather than a working route — travel needs boats and is deferred, so nothing can
use it yet. It exists so the arrow has something to mark and the realms are discoverable in v1.
 - **Sea-to-sea** — a boat sails to the ocean province holding the teleporter. **Land-to-land teleporters
   are deferred** (decided), so this is not the coastal land pair.
 - Authored data is not imported data: it needs an **overlay merged at export**, the pattern
   `building-unlocks.json` already uses for `TechTree`. Do not hand-edit `adjacencies.json` — it is
   regenerated from Anbennar's `adjacencies.csv` and an edit there is a landmine.

**Phase 1 — Realm as data.** Resolve realm in the exporter — `Continent` for land, **adjacent land** for
water, fog for the 91 deep-ocean provinces, and none for the three quirks. Ship one `realm` key per
province plus a `realms` block (crop rect per realm). **One bundle for all realms** (decided) — the
client filters and crops, so switching is instant and `WorldBundle`'s two `static volatile` cache fields
stay as they are. Nothing renders differently. Guarded by the bundle golden test.

**Phase 2 — Make the wrap explicit.** Add `MAP.wrap`; `worldW()` returns `0` when `!wrap`. Fix the six
wrap sites to honour it. **Ship with `wrap:true` → zero visual change**, and test with `wrap:false`
against the uncropped map. This is the whole de-risking of §The trap: the wrap dies and is verified
before any crop exists to hide the failure.

**Phase 3 — Roll and crop.** Per-realm x-roll + crop rect in `build.mjs`/`WorldBundle`, `MAP.W/H` held
global. Flip `wrap:false`. Verify per realm with `tools/webverify`.

**Phase 4 — Fog the void.** First consumer of the baked `FOW_TILE` art; sea X-clip; per-realm
`rollupTier` label centroids. Plus the **realm rim + red teleport arrow** (§The fog must not be mute) as
its own layer entry, modelled on `drawCavernRims` — this is what makes the other realms discoverable, so
it is not cosmetic polish to be cut.

**Phase 5 — The dropdown.** Realm selector + Lobby entry; brand loses "Anbennar"; plane button hides
outside Halann; deep links gain a realm. Owns the **switch-realm action** that both the dropdown and the
Phase 4 arrow fire — so the arrow's click lands here, not in Phase 4.

**Phase 6 — Hinuilands.** Falls out of 0–5 for free — a realm with two provinces and a lot of fog.

Not in this list: travel (deferred).

## Deferred

**Inter-realm travel.** The intended shape: a teleporter is an authored adjacency between two **sea**
provinces; a player's boat sails to the ocean province holding the teleporter and is carried to Aelantir,
and back. This needs boats, which do not exist — caravans are land-only. Until then the realms are three
views of one world and nothing crosses.

The crossing is already chosen (§Phase 0b). Narrowest sea-to-sea pair per latitude band:

```
POLAR   lat 84    719km   Fjordsbay -> Sealpod Route
NORTH   lat 56/51 1548km  Coast of Venail (1265) -> Eastern Lastsight Islands (1567)   <- chosen
MID     lat 27/30 2361km  Sandspite Approach -> Banished Sea
TROPIC  lat  3/8  2278km  Corsair Reaches -> Bay of Hope
```

The polar pair is shorter but it is a technicality — lat 84 sits in the stretched dead zone at the top of
the Mercator projection, and an Arctic hop is a strange colonisation route. **Coast of Venail → Eastern
Lastsight Islands** is the Cannor→Aelantir route proper: Venail is Cannor's west coast (`venail_area`,
`lencenor_region`, the human heartland), at a latitude the projection treats honestly. And Anbennar named
the far side **Lastsight** — the last sight of land. It named the edge of the known world for us.

**Hinuilands gets no ocean teleporter** — it has no coast (nearest water is 1295km from Vyr Cirentyn) and
it is not playable. It is reached by the dropdown. If it ever becomes reachable, it is via the existing
gladeway teleporter network, not by sea.

**Whether a session can span realms.** Deferred with the above. If a colony lives in one realm, realm is
a bundle filter plus a crop — small. If something crosses mid-session, the snapshot needs a realm field,
the viewer must follow the caravan across maps, and Ranked (one shared world, colonies in lockstep) has
colonies on different maps at once. Much larger, **not** in scope.

## Adjacent opportunity: the Domandrod Seasonal Court

Not part of Realms; recorded here because Phase 0 is what surfaces it, and it would otherwise be lost.

Anbennar authored **four seasonal gates**, all fully intact in our import, all leading into
`domandrod_region` — a fey enclave in **Aelantir**:

```
domandrod_summer_gate   Sidpar (sarmadfar)    -> Blastgat  (domandrod)
domandrod_spring_gate   Arankid (glorelthir)  -> Lilebogg  (domandrod)
domandrod_autumn_gate   Orachran (glorelthir) -> Anrachran (domandrod)
domandrod_winter_gate   Dungat (randrunnse)   -> Bastnadd  (domandrod)
domandrod_winter_gate2  Fogrim (randrunnse)   -> Bastnadd  (domandrod)
```

One gate per season, each from a different outer region, every endpoint `ANCIENT_FOREST` or `LAND`, plus
five `domandrod_fey_portal` rows and five gladeways inside. That is a **Seasonal Court** — the classic fey
Spring/Summer/Autumn/Winter structure — and Anbennar built the whole thing in map data.

**A gate that opens only in its season is nearly free here.** It is a date predicate on an adjacency, and
every input already exists: a real solar calendar, seasons, and hemisphere-aware winter (the explorer
levies already muster "every winter, by hemisphere" — `docs/explorer-caravan.md`). `LandRouter` already
traverses these edges; the mechanic is *gating an edge we already walk*, not building a system.

It also gives Aelantir a signature the way the Serpentspine gives Halann one: **Halann has an underworld;
Aelantir has a fey court that is only reachable a quarter of the year.** A caravan that misses its season
waits, or takes the long way — a real routing decision that costs nothing to author, because Anbennar
already authored it.

## Rejected

**The generated Feyrealm.** Anbennar's cosmology says the Core Planes are parallels — *"every planet in
the Prime Material Plane being reflected by a planet in the other Core Planes"* — which reads as licence
to generate a Feyrealm as a mirror of Halann: same geography, overgrown, drenched in positive energy.
Lore-true, and it sidesteps the unpainted-Hinuilands problem entirely.

**Rejected: no mirrored provinces, and the name "Feyrealm" is not used.** A mirror doubles the province
count, needs an id-offset scheme, and buys a plane nobody can play. The third realm is **Hinuilands**,
standing as itself — two real provinces — and treated with the same logic as Aelantir: a partition of
existing provinces, cropped, fogged, reached (eventually) by teleporter rather than by being conjured.

If this is ever revisited, do not conflate the two: Hinuilands is a *location on the planet Halann* (the
Titanoflora, Prime Material — Anbennar simply has not drawn it). A Feyrealm would be a *parallel plane*
of the whole world. Building one is not building the other.

**The fey content that is painted lives in Halann** — the Deepwoods (`deepwoods_superregion`, 66
provinces: 44 `ANCIENT_FOREST`, 11 `GLADEWAY`, 8 `FEY_GLADEWAY`), inside continent `europe`, plus five
gladeways in Aelantir (`domandrod_region`). Anbennar groups them (`deepwoods_feytouched_gladeways`,
`deepwoods_outward_gladeways`, `deepwoods_inner_gladeways`). They stay where they are.

## Open

- **Where does the gamemaster's island live?** Province 1173 (Halann) is **kept, not dropped** (decided) —
  reserved for a future **gamemaster's island**. Its continent is `asia`, so today it falls into the
  Halann realm and would be a settleable dev-1 islet like any other. Options: leave it there and gate it
  by role; or make it **its own admin-only realm** — a fourth dropdown entry visible only to
  `ROLE_ADMIN`/`civstudio.auth.admins`, which the realm dropdown makes nearly free. The second reads
  better (a GM vantage shouldn't be somewhere a player can sail to) but it is a v2 question; for now,
  just don't let Phase 1 quietly make it ordinary land.
- **One land province has no continent** and so lands in no realm. Harmless (it fogs everywhere) but it
  is one line to identify — worth a look during Phase 1 rather than shipping a province nobody can see.
- **Does the plane button hide or grey out** in Aelantir/Hinuilands?
- **`HALANN_TIP`** (`advisors.mjs:24`) — *"Halann is the center of the Material Plane, which is the
  center of all of the Planes of Existence."* — is written for one world. Per-realm tips, or drop it?
