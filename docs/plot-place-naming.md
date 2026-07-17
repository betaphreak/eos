# Plot place-naming

Every land plot carries a **real Earth place name** (from [GeoNames](https://www.geonames.org/)),
surfaced in the web viewer on hover as `«name» · «terrain» · «feature»`. Names are stamped in at
**bake time** and stored in the plot cache; the running sim and server never touch GeoNames.

Status: **shipped + baked whole-world** (2026-07-16). Code lives in `com.civstudio.geo.names`
(+ `RegionEarthMap` in `com.civstudio.geo`, `GeoNamesFiles` in `com.civstudio.data`).

## The idea

Anbennar is fictional, so a plot has no *real* Earth position. Instead each of the map's **146
land regions is mapped to one real Earth country** (a bijection — no country is reused), and a
plot borrows a real town/landmark from that country, placed by its position within the region.
The mapping is name-cued: Rahen→India, Yanshen→China, Western Cannor→France, the Sarhal regions→
Africa, the Insyaa regions→the Americas, the Serpentspine underworld→Alps/Himalaya, and so on.

## The mapping

`civstudio-engine/src/main/resources/geo/region-earth-map.json` — hand-authored, **editable**,
region `raw_key` → ISO-3166 alpha-2 code. `RegionEarthMap` loads + validates it: fail-fast on any
live land region that is unmapped (excluding `debug_region`), and reject a duplicate country (the
bijection invariant). `RegionEarthMapTest` also asserts coverage against every settleable region
of the live `WorldMap`, so a renamed/added region can't silently go unnamed.

Deliberately no micro-states — big provinces need rich gazetteers, so the tiny island/enclave
nations were swapped for large ones (a region can need up to ~80k distinct names; North Eltchamas
is the biggest single province).

## The committed subset (the default source)

The bake no longer needs the 372 MB dump. A committed **subset** —
`generated/geonames/subset.json.gz` (~4.5 MB, classpath `/geonames/subset.json.gz`) — ships in the
engine jar, so **any machine bakes names**: production, CI, a fresh clone. `GeoNamesSubset.load`
reads it into the same per-country `CountryGazetteer`s the full dump would yield;
`WorldPlotGenerator.nameWorld` prefers it, falling back to the full dump only when the subset is
absent (i.e. while rebuilding it), and skipping naming only if neither is present.

**How it is bounded (`GeoNamesSubsetExporter`).** For each mapped country it keeps the **top-K places
by population**, `K = ceil(maxProvincePlots(region) × 1.5)` (floored at 256). That bound is sound
because names are unique *per province* with cross-province reuse (`PlaceNamer`), so a country's pool
only needs to name its **largest single province** — `GeoNamesSubsetTest` asserts pool ≥ largest
province for every country (bar Kuwait/Trinidad, which recycle with the full dump too). The same
class/population filter is applied (`GeoNamesGazetteer.parse` reused), so the subset is a strict
sub-selection. **Names change once** vs the full-dump bake (bounding drops the low-population tail, so
the spatial picks differ) — a cosmetic, deterministic one-time re-bake. Rebuild it (needs the full
dump) with:

```
mvn -pl civstudio-engine compile exec:exec -Dsim.main=com.civstudio.geo.names.GeoNamesSubsetExporter
```

## The pipeline (bake time)

1. **`GeoNamesSubset`** loads the committed subset from the classpath (the default). Only when it is
   absent does **`GeoNamesFiles`** resolve the offline `allCountries` dump from a gitignored
   `.geonames-cache/` (sys-prop `civstudio.geonames.cacheDir` / env `GEONAMES_CACHE_DIR`), streaming
   the `.txt`/`.gz`/`.zip` transparently. Either way, read **once**, at bake time only.
2. **`GeoNamesGazetteer`** (dump path) parses `allCountries`, keeping feature classes P/A/T/L/H and population
   ≤ 250k (so no famous metropolis lands on a plot), UTF-8 names (the `name` column, not
   `asciiname` — native scripts survive). It builds one **`CountryGazetteer`** per mapped country: a
   lat/lon grid supporting *largest-population-in-rect* and *nearest-unused* queries.
3. **`PlaceNamer`** names one region's plots: a plot's pixel `(x,y)` is normalized within the
   region's **`PixelBox`** to `[0,1]²`, projected into the country's lat/lon box (north-up), then it
   takes the **largest place in its mapped cell** → nearest-unused → a nearest real name + numeric
   suffix on pool exhaustion. **Unique within a province** (used-set reset per province;
   cross-province duplicates are allowed). Fully deterministic — no RNG.
4. **`PlaceNamingPass.nameWorld`** drives it region by region over the warmed plot cache (one
   region's plots + the pre-loaded gazetteers in memory at a time), and **`WorldPlotGenerator`**
   runs it after generation *iff* the dump is present (else plots stay nameless, gracefully).

Whole-world bake: ~4.6k land provinces named across 127 regions, 0 countries starved, ~12 min after
a 78s generation pass; 100% of land plots named. Ocean/sea-region provinces are skipped.

## Storage & web

`Plot.placeName` (nullable) round-trips through `StoredPlot.name` in the gzipped
`.map/v<MAP_VERSION>/<id>.json.gz`; a cache written before naming loads back as `null`. The
web reads the field automatically (`q.name`); `web/js/plotlabel.mjs` (`prettyKey`/`plotTip`, unit-
tested in `plotlabel.test.mjs`) + the hover handler in `web/js/panel.mjs` render
`name · terrain · feature`.

## Deploying names to production

Naming itself no longer needs the dump — the committed subset (above) ships in the jar, so a bake on
any machine names plots. The plot **cache** is still baked once and shipped to the AzureFile share
(the cache is large and per-province; only its *source of names* changed).
`pwsh tools/deploy-plot-cache.ps1` bumps `MAP_VERSION`, moves the local baked cache to the new
version dir, uploads it to `<share>/map/v<new>`, and prunes old versions (keeps
`v<new>` + `v<new-1>`). Run `tools/deploy-server.ps1` after it so the new image serves `v<new>` and
the client `?v=` flips (the web reads `mapVersion` from the server bundle). See
`docs/client-server.md` §Deployment. Verify by hovering a plot at deep zoom on the
public map at **anbennar.civstudio.com** (the world-map site; `dev.civstudio.com`
is the server/API it fetches from).

## Data source & licence

GeoNames `allCountries` (~400 MB zip, ~13M rows), pinned by `geonames-source.lock` — now a *dev-only*
dependency, needed only to rebuild the committed subset. Licensed **CC BY 4.0** — credited in the
README (the committed subset is a derived work under the same licence).
