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

## The pipeline (bake time)

1. **`GeoNamesFiles`** resolves the offline `allCountries` dump from a gitignored
   `.geonames-cache/` (sys-prop `civstudio.geonames.cacheDir` / env `GEONAMES_CACHE_DIR`), streaming
   the `.txt`/`.gz`/`.zip` transparently. Read **once**, at bake time only.
2. **`GeoNamesGazetteer`** parses `allCountries`, keeping feature classes P/A/T/L/H and population
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
`.plot-cache/v<GEN_VERSION>/<id>.json.gz`; a cache written before naming loads back as `null`. The
web reads the field automatically (`q.name`); `web/js/plotlabel.mjs` (`prettyKey`/`plotTip`, unit-
tested in `plotlabel.test.mjs`) + the hover handler in `web/js/panel.mjs` render
`name · terrain · feature`.

## Deploying names to production

Production can't bake (no dump), so the locally-baked cache must be shipped to the AzureFile share.
`pwsh tools/deploy-plot-cache.ps1` bumps `GEN_VERSION`, moves the local baked cache to the new
version dir, uploads it to `<share>/plot-cache/v<new>`, and prunes old versions (keeps
`v<new>` + `v<new-1>`). Run `tools/deploy-server.ps1` after it so the new image serves `v<new>` and
the client `?v=` flips (the web reads `plotVersion` from the server bundle). See
`docs/client-server.md` §Deployment. Verify by hovering a plot at deep zoom on the
public map at **anbennar.civstudio.com** (the world-map site; `dev.civstudio.com`
is the server/API it fetches from).

## Data source & licence

GeoNames `allCountries` (~400 MB zip, ~13M rows), pinned by `geonames-source.lock`. Licensed
**CC BY 4.0** — credited in the README.
