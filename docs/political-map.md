# Political map

The world map has a **physical** default (recolored terrain raster + per-plot zoom) and a
**Political** map mode that colours each land province by its owning nation at the game-start
bookmark. Country, culture and religion are first-class engine reference data joined to provinces by
tag/key — the same way a province joins its region.

## Data pipeline

Canonical ownership comes from the Anbennar EU4 dev mod
(`https://gitlab.com/anbennar/anbennar-eu4-dev`, branch `new-master`), checked out under
`data/anbennar/` alongside the existing map rasters (build-time inputs; never read by the running
sim). These raw files are **gitignored** (`data/anbennar/history/`, `data/anbennar/common/` —
thousands of small files); only the *derived* JSON below is committed. Re-checkout them from the mod
to re-run the exporters. Sources:

- `history/provinces/<id> - <Name>.txt` — top-level `owner` / `controller` / `culture` / `religion`
  (plus any dated `YYYY.M.D = { ... }` overrides applied up to the 1444.11.11 start).
- `common/country_tags/*.txt` — `TAG = "countries/<Name>.txt"`.
- `common/countries/<Name>.txt` — `color = { r g b }`; the display name is the file stem.
- `common/religions/*.txt` — nested `group → religion → color`.
- `common/cultures/anb_cultures.txt` — nested `group → culture` (no colour in source → generated).

Four dev-tool exporters (`com.civstudio.geo.export`) turn these into committed resources, mirroring
the existing geo stamp-chain (`ClimateExporter` etc.):

- **`ProvinceHistoryExporter`** stamps `owner`/`controller`/`culture`/`religion` onto
  `map/provinces.json` (runs last in the stamp chain, after `ClimateExporter`). Uses a minimal
  brace-aware Clausewitz scan that skips nested blocks and honours dated overrides.
- **`CountryExporter`** / **`ReligionExporter`** / **`CultureExporter`** write
  `map/{countries,cultures,religions}.json` — `tag/key → {name, color[, group]}`. Shared parsing is
  in `ClausewitzBlocks`.

Re-run after any base `ProvinceExporter` regen:

```
mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.ProvinceHistoryExporter
mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.CountryExporter
mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.ReligionExporter
mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.CultureExporter
```

## Engine model

`Province` gains four nullable keys (`ownerTag`, `controllerTag`, `culture`, `religion`);
`Country`/`Culture`/`Religion` are records loaded by `WorldMap` from the optional
`countries/cultures/religions.json` resources. `WorldMap` exposes `country(tag)` / `culture(key)` /
`religion(key)`, the `countries()`/`cultures()`/`religions()` collections, and the derived
`provincesOwnedBy(tag)` / `provincesOfCulture(key)` / `provincesOfReligion(key)` membership indices
(built like `provincesByRegion`). An unowned/uncolonized/sea province simply has a null owner. Nothing
in the sim consumes ownership yet — it is the seam future taxation/claims plug into.

## Web rendering

`web/build.mjs` writes the political layer to a **separate `web/political.js`** (`window.POLITICAL` —
the trimmed country/culture/religion tables + per-province `{owner, controller, culture, religion}`),
fetched lazily by `panel.ensurePolitical()` the first time Political mode is entered, so World/Caravan
never download it. On load it enriches the in-memory province objects in place. (Provinces in
`data.js` carry only raw geo keys; display-name crumbs resolve client-side through the shipped
`BUNDLE.geoNames` dictionaries via `core.provGeo` — interning them cut ~850 KB of duplicated names
from `data.js`.) The `Political` toggle
(`index.html #modeToggle`, `panel.mjs setMode`, also reachable via a `#political` URL hash) switches
`S.mode`. A political-only sub-toggle (`#polByToggle`, **Nation / Culture / Faith**) sets `S.polBy`;
`core.polOf(p)` resolves the active dimension's `{name, color}` entry for a province, so the same
render path colours by nation, culture or religion. `main.mjs renderScene` fills province polygons by
that colour, **zoom-banded** on the existing `K_PLOT`/`K_TEX` constants so the political map yields to
the physical terrain as you dive in:

- `cam.k < K_PLOT` (≈1–5×): full-opacity fills — the overview.
- `K_PLOT ≤ cam.k < K_TEX` (≈5–16×): fill alpha fades (0.5→0.15) as the per-plot terrain appears.
- `cam.k ≥ K_TEX` (>16×): coloured province borders + a fill on only the hovered province.

A province with no value for the active dimension never fills. Hover tooltip shows the active
dimension's entry; the sidebar detail panel (`panel.mjs politicsBlock`) always shows nation, culture
and faith together.

Verify with `tools/webverify/political-shot.mjs` (serves `web/` with Range support, screenshots a
world overview + a zoomed province in Political mode).
