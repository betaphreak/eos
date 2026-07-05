# web — CivStudio visualization prototypes

Read-only presentation layer for the headless engine. Nothing here runs during a
simulation; each page is a **consumer** of a run's output under `output/<seed>/`,
so it can never perturb the seed-reproducible run. Pages are self-contained HTML
(all CSS/JS/data inline) — open directly in a browser or publish as an Artifact.

## The Dhenijansar Migration (map-led caravan replay)

`dashboard.html` replays a **parallel directed-march** run: six caravans muster at
one origin and trek across the world map to distant sites, foraging and hauling
cargo. It shows the province cloud (real lat/lon), the six routes, caravans
animated along their recorded paths on a timeline, and per-journey cargo/larder
sparklines and cargo-on-arrival composition.

### Regenerate

The build distils `output/<seed>/by-caravan/*-CaravanMarch.csv` plus the committed
province map (`src/main/resources/map/provinces.json`) into one inline JSON bundle
and injects it into `dashboard.template.html`.

```bash
# 1. produce a run (writes output/<seed>/by-caravan/…). e.g. the six-caravan run:
mvn -q test -Dtest=ParallelCaravansTest        # seed 24601
#    or the single long journey:
mvn -q test -Dtest=DhenijansarToWexkeepTest    # seed 90210

# 2. build the page (default seed 24601)
node web/build.mjs            # -> web/dashboard.html
node web/build.mjs 90210      # a different run
```

`output/` is gitignored, so a fresh clone must run step 1 before step 2. The built
`dashboard.html` is committed so the current run is viewable without rebuilding.

### Files

| file | role |
|------|------|
| `dashboard.template.html` | the page — layout, styles, and all rendering/interaction JS, with a `/*__BUNDLE__*/` data placeholder |
| `build.mjs` | reads a run's caravan journals + the province map, builds the JSON bundle, injects it into the template |
| `dashboard.html` | built output (data baked in) — the shareable artifact |

## Notes

- Province positions come from `provinces.json` (`lat`, and a `lon` derived from the
  map bounding box); the projection is aspect-preserving, so relative geometry is
  faithful but it is a schematic, not a georeferenced map.
- This is one directory in the engine's monorepo, not a separate frontend project;
  it has no build tooling beyond Node.
