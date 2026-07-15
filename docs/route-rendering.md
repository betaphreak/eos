# Route rendering — baking Civ4 roads/trails/rails to map sprites

**Status (2026-07-15):** the **art bake is BUILT** (gap A). Route segment art is baked from
the Civ4/C2C `.nif` meshes to per-tier WebP sprite atlases and shipped in the map bundle
(`BUNDLE.routes`). **Not yet built:** the engine→client data channel that carries per-plot
`routeType` to the browser (gap B) and the draw layer that stamps the sprites along a plot
corridor (gap C). So the sprites ship but nothing draws them yet.

This is the follow-through on the owner's Phase-3 decision (see `docs/explorer-caravan.md`
§Phase 3 "Route art"): **use the real Civ4 route art via `tools/nifbake`, not procedural
ribbons.** Background on the engine-side route model (tiers, per-plot `Plot.routeType`,
movement cost) is in `RouteType`/`routes.json` and `docs/explorer-caravan.md`.

## The art, and why roads need their own bake path

Each C2C route **style** (`UnpackedArt/art/terrain/routes/<style>/`) is a set of ~70 flat
segment `.nif` meshes (the Civ4 auto-tiling adjacency variants — `roada00`, `roadb03`, …)
that all UV-map onto **one small tiled surface texture** (`roadprimitive.dds` 64×32 for the
dirt path, `roadroman.dds` for the stone road, `railroad.dds` for rails). The road *look* is
that one strip repeated down a segment; the mesh only supplies the quad shape + UV.

`tools/nifbake` already renders feature `.nif`s (cactus, city) to billboard sprites, but roads
differ in two ways that needed a dedicated **`renderRouteNif`** (in `tools/nifbake/render.mjs`):

1. **Top-down projection, not front view.** `renderNif` projects X-right / Z-up (correct for
   upright billboards). Roads lie flat (the mesh spans X–Y at Z≈0), so `renderRouteNif`
   projects **X–Y looking down Z** (higher Z wins), with world +Y (North) mapped to image-up.
2. **Direct geometry location, bypassing `parseNif`'s resync.** A road's `NiTriShape` and its
   `NiTriShapeData` are separated by a 7-block run — `NiTexturingProperty` / `NiSourceTexture`
   / `NiAlphaProperty` / `NiMaterialProperty` plus an **animated-alpha**
   `NiAlphaController → NiFloatInterpolator → NiFloatData` chain that `nif.mjs` has no parser
   for. `parseNif` tries to brute-force resync past that gap, but its `sane()` UV bound (0..3,
   tuned for 0..1 billboards) **rejects the road's tiling UVs** (V repeats down a long
   segment). So `renderRouteNif` instead scans for the `NiTriShapeData` whose parse consumes
   **exactly to EOF** — a strong, self-contained lock needing no resync heuristic. The tiling
   UVs are wrapped by the sampler (that repeat *is* the road surface running down the segment).

## The build slice — `bakeRoutes()` in `web/build.mjs`

Three **tiers** are baked, each a C2C style, keyed so the engine's `Plot.routeType` maps on via
`byType`:

| tier | style dir | texture | `RouteType.type` |
|---|---|---|---|
| `trail` | `path` | `roadprimitive.dds` (dirt) | `ROUTE_TRAIL`, `ROUTE_PATH` |
| `road` | `roman roads` | `roadroman.dds` (stone) | `ROUTE_ROAD`, `ROUTE_PAVED_ROAD` |
| `rail` | `modrailroads` | `railroad.dds` | `ROUTE_RAILROAD` |

Rather than all ~70 adjacency variants, each tier bakes the **small connection set** a
square-grid auto-tiler needs — the pieces a 2D map actually uses, chosen by their Civ4
route-model connection (`route-models.json`):

| piece | connection | Civ4 stem | shape |
|---|---|---|---|
| `iso` | `-` | `a00` | isolated nub |
| `end` | `N` | `a01` | terminus |
| `straight` | `N S` | `b03` | │ through |
| `corner` | `N E` | `b05` | └ L-turn |
| `tee` | `N NE S` | `c07` | Y/T junction |
| `cross` | `N E S W` | `d01` | ✕/+ crossroads |

Each piece renders top-down, then the tier's pieces pack into one horizontal-strip RGBA atlas
emitted as WebP (`web/assets/routes/routes-<tier>.webp`, ~8–12 KB each). The manifest records,
per tier, `{ src, w, h, cell:{ piece:[x,y,w,h] }, conn:{ piece:connString } }`. Art is resolved
through `civ4.mjs resolveArt` (on-demand C2C fetch + `.civ4-cache`), warmed by `routeArtPaths()`
in the top-of-file prefetch. If a style/texture doesn't resolve, that tier is skipped (the map
just draws no road for it) — same degrade-to-fallback contract as the other bakes.

The manifest lands in `civstudio-server/.../map/web-asset-manifest.json`; `WorldBundle` passes
the `routes` key through to `/api/bundle`; `core.mjs` exposes it as `ROUTES`.

## The correct zoom band for roads

Roads are **per-plot ground detail**, exactly like tree/feature sprites and trade-good icons.
Those draw from **band 4 (`BAND.TERRAIN`, `cam.k = K_TEX = 16×`)** upward — the point where a
plot is large enough on screen to carry ground-detail art; below it the map is in the
atlas/overland-strategy regime where a single plot is ≈1 px and per-plot road art would be
sub-pixel noise. **So the route draw layer (gap C) should gate on `atLeast(BAND.TERRAIN)`** and
not draw roads below 16×, matching `drawSurfacePlots`'s `textured` gate and `drawTradeGoodIcons`.
(A separate coarse *overland* schematic — a thin connecting line between settlements, drawn
earlier like the caravan trails in `overlays/live.mjs` — is a different, optional layer; it is
not these baked plot sprites.)

## Known follow-ups

- **Gap B — data channel.** `Plot.routeType` is per-session mutable and excluded from the static
  `plots.pack`, so it needs a **live** channel: expose the traversed/planned corridor window
  (plot raster → lat/long + routeType) in the render `SessionSnapshot`. This is
  `docs/explorer-caravan.md` §Phase 5.
- **Gap C — draw layer.** A route layer (in `plots.mjs` / the layer registry) that, per corridor
  plot, picks the connection piece from the neighbour mask and rotates it (Civ4
  `Rotations "0 90 180 270"`), gated at `BAND.TERRAIN`. Replaces the centroid polyline in
  `overlays/live.mjs`.
- **Rail canonical orientation.** The `modrailroads` segments bake in a **90°-rotated** canonical
  orientation vs `path`/`roman roads` (rail `straight` is E–W, not N–S). The draw layer must
  normalise per tier (rotate rail +90°) or the bake should pre-rotate rail pieces. Recorded so
  the mismatch isn't a surprise when gap C lands.
- **`PAVED_ROAD` / higher tiers** currently reuse the `road` (roman) art; `modern roads` has only
  2 nifs (no connection set), so a distinct paved look would bake from a different style or a
  hand-authored texture.
