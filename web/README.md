# web — CivStudio visualization prototypes

Read-only presentation layer for the headless engine. Nothing here runs during a
simulation; each page is a **consumer** of a run's output under `output/<seed>/`,
so it can never perturb the seed-reproducible run.

It is laid out as an ordinary static site — `index.html` + `styles.css` + `app.js`,
with the run's data in a generated `data.js` and the terrain as a real image asset
under `assets/`. `build.mjs` writes both. No bundler or dependencies — just Node for
the build; open `index.html` straight off disk.

## The caravan replay (map-led)

The page replays a **parallel directed-march** run: six caravans muster at one
origin and trek across the world map to distant sites, foraging and hauling cargo.
It leads with the **real EU4 terrain** — a dark-tinted crop of the actual
`terrain.bmp` the engine imports — with **province polygons** shaded as a
**choropleth of caravan-days** (where the bands lingered), the six routes drawn
over it, province labels, caravans animated along their recorded paths on a
timeline, and per-journey cargo/larder sparklines and cargo-on-arrival composition.
The map is **draggable to pan and scrollable to zoom** (plus on-screen zoom
controls); hovering picks the province by its real outline (point-in-polygon), and
a flame button toggles the traffic heat.

## Run it

Open `web/index.html` in a browser — it loads `styles.css` + `data.js` + `app.js`
and the `assets/terrain-<seed>.png` image alongside it. Works straight off disk, no
server needed.

### Regenerate

The build distils `output/<seed>/by-caravan/*-CaravanMarch.csv` plus the committed
province map (`src/main/resources/map/provinces.json`) and outlines
(`src/main/resources/map/borders.json`) into one JSON bundle written to `data.js`,
and bakes a dark-tinted crop of the real terrain raster (`data/anbennar/terrain.bmp`)
into a real image at `assets/terrain-<seed>.png` that the page references.

`borders.json` (the per-province polygon outlines) is a committed map resource, so
it is normally already present. Rebuild it only if the map sources change:

```bash
mvn -q compile exec:exec -Dsim.main=com.civstudio.geo.export.ProvinceBorderExporter
```

```bash
# 1. produce a run (writes output/<seed>/by-caravan/…). e.g. the six-caravan run:
mvn -q test -Dtest=ParallelCaravansTest        # seed 24601
#    or the single long journey:
mvn -q test -Dtest=DhenijansarToWexkeepTest    # seed 90210

# 2. build the page (default seed 24601)
node web/build.mjs            # -> web/data.js + web/assets/terrain-24601.png
node web/build.mjs 90210      # a different run
```

`output/` is gitignored, so a fresh clone must run step 1 before step 2. The built
`data.js` and `assets/terrain-<seed>.png` are committed so the current run is
viewable without rebuilding.

### Files

| file | role |
|------|------|
| `index.html` | the page markup — links `styles.css`, `data.js`, `app.js`, and the terrain image |
| `styles.css` | all styling (theme tokens, layout, map chrome) |
| `app.js` | rendering + interaction (projection, pan/zoom camera, choropleth, timeline, rail) |
| `data.js` | **generated** — `window.BUNDLE`, the run's data (no imagery inlined) |
| `assets/terrain-<seed>.png` | **generated** — the dark-tinted terrain crop, a real image asset |
| `build.mjs` | reads a run's caravan journals + the province map + outlines, tallies caravan-days per province, writes `data.js` and the terrain PNG |

The province outlines come from `ProvinceBorderExporter` (in the engine), which traces
each land province's silhouette from `provinces.bmp` into a simplified polygon and
writes `src/main/resources/map/borders.json` — canonical geometry any UI can reuse.

## Notes

- The map background is the real `terrain.bmp`, cropped to the caravan region and
  re-tinted into the dashboard's dark palette (water / land / hill / peak / snow /
  desert / marsh, classified as in `MapTerrainCodec`). It is projected in
  **web-mercator source-pixel space** — the exact inverse of the `lon`/`lat` maps
  `ProvinceExporter` used — so the province dots and routes pin to the terrain
  rather than floating over a schematic.
- This is one directory in the engine's monorepo, not a separate frontend project;
  it has no build tooling beyond Node.
