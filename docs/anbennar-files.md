# Design & plan: de-vendoring the Anbennar mod files (on-demand fetch)

**Status:** ✅ **Implemented (2026-07-11).** The on-demand file **provider**
(`com.civstudio.data.AnbennarFiles`) + Spring-configured **source** shipped: `ProvinceRaster` and
all ~16 exporters fetch through it, `data/anbennar` is removed from the repo (and its git history,
via a rewrite), the `Dockerfile`/CI no longer carry it, and the full reactor suite (255 engine + 23
server) is green. The related "retire the plot caches → server calls", "retire the web bakers", and
"relocate generated outputs out of `src/main/resources`" changes are explicitly **out of scope /
later** (see §Deferred). Original design/plan preserved below.

**Canonical source:** `https://gitlab.com/anbennar/anbennar-eu4-dev`, default branch
`new-master`. The mod is in **active development**, so we track that branch but **lock** the exact
commit the derived resources were built from (currently `7216a7525bc971eac989ebfcddf34833814802df`)
— see §Staying current with upstream. GitLab, *not* GitHub (GitHub only has stale mirrors — see the
`anbennar-canonical-source` note).

---

## Goal

Stop vendoring the Anbennar EU4 mod files in the repo. Today `data/anbennar/` holds a
hand-picked, **flattened** subset — 15 tracked files (~95 MB of `.bmp` rasters + Clausewitz
metadata) at the top level, plus gitignored `common/`, `history/`, `loadingscreens/` copies.
Every one of these comes verbatim from the mod. We want them **out of the repo**, fetched
**individually, when needed**, from the canonical GitLab source, with **no `data/anbennar`
location** at all.

## Background — who actually reads these files (verified 2026-07-11)

Two disjoint groups, and it matters which is which:

- **Runtime (the live server needs it).** The world map itself loads from committed classpath
  JSON (`/map/provinces.json`, `/map/areas.json`, …) baked into the engine jar — **no** mod
  files. But **plot fields** are different: `ProvincePlotStore.load()` tries an external cache
  file, then the packaged classpath resource, and on a miss **generates** the field from the
  province rasters via `ProvinceRaster` (`ProvincePlotPool.loadOrGenerate`, `plots == null`
  branch). The per-province caches (`civstudio-engine/src/main/resources/map/provinces/*.json.gz`,
  ~32 MB, 5185 provinces) are **git-ignored** — 0 are tracked — so the CI/Docker jar ships
  **without** them. The deployed server therefore regenerates plot fields at runtime and reads
  the map BMPs from disk. `GameSession.provincePlotPool` eagerly calls `ProvinceRaster.load()`
  on the first plot-pool request. This is why `Dockerfile` copies `data/anbennar` into the image.

  The rasters `ProvinceRaster` reads: `definition.csv`, `provinces.bmp`, `rivers.bmp`,
  `terrain.bmp`, `trees.bmp`, `heightmap.bmp`, `default.map`.

- **Dev-time only.** The ~30 exporters in `civstudio-engine/.../geo/export/` (province, area,
  region, culture, country, religion, history, adjacency, cavern, climate, continent, …) and
  the two node web bakers (`web/build.mjs` → terrain raster; `web/bake-loading.mjs` →
  loading-screen art) read the metadata `.txt`/`.csv`, `common/`, `history/provinces/`, and
  `gfx/loadingscreens/`. None of this is read by the running server; it produces the committed
  `map/*.json` resources and the committed web assets.

## The design

### A single engine-side provider

A plain-Java provider in the **engine** — `com.civstudio.data.AnbennarFiles` — is the one place
that knows how to turn a **mod-relative path** into a local file, downloading it on demand:

```java
Path p = AnbennarFiles.get("map/provinces.bmp");   // downloads on cache miss, returns local path
```

- **Why the engine, not the server.** The on-demand consumers (`ProvinceRaster`, the exporters)
  are plain-Java engine code with no Spring context, and the engine module builds **before** the
  server in the reactor. The provider must live where its callers do. The engine carries
  standalone defaults so `mvn -pl civstudio-engine exec:exec` and the test suite work without any
  server.
- **Fetch.** `{baseUrl}/-/raw/{ref}/{modRelativePath}` over `java.net.http.HttpClient` (the same
  client style as `HttpSteamOpenId`). A non-200 is a hard error naming the path + ref.
- **Auth.** An optional GitLab **token** is sent as the `PRIVATE-TOKEN` header on every fetch and
  tree listing, moving from the anonymous rate limit to the authenticated one. The repo is public,
  so a blank token still works — but the directory-walking exporters make many calls, so a
  read-only token (`read_api` / `read_repository`) avoids throttling. The token is a deployment
  secret (env `ANBENNAR_TOKEN`), never committed and never logged.
- **Cache.** Files are written to `{cacheDir}/{ref}/{modRelativePath}` — **keyed by `ref`**, so
  bumping the lock invalidates the cache for free. Download to a temp file then atomic-move, so a
  crashed/partial download never poisons the cache. `get()` is safe under concurrent colony
  threads requesting the same file (per-path lock or move-idempotency).
- **Config holder.** `baseUrl`, `ref`, `cacheDir`, `token`, with hardcoded engine defaults (the
  ref from the lock; token from env `ANBENNAR_TOKEN`). Overridable by the server (below).

### Configured in Spring Boot (the server overrides the engine defaults)

`civstudio.anbennar.*` is bound in `CivStudioProperties.Anbennar` and defaulted in
`application.yml`:

```yaml
civstudio:
  anbennar:
    base-url: ${ANBENNAR_BASE_URL:https://gitlab.com/anbennar/anbennar-eu4-dev}
    ref:      ${ANBENNAR_REF:7216a7525bc971eac989ebfcddf34833814802df}
    cache-dir: ${ANBENNAR_CACHE_DIR:.anbennar-cache}
```

A small server bean (e.g. an `ApplicationRunner` / `@PostConstruct`) pushes these into the engine
provider at startup: `AnbennarFiles.configure(baseUrl, ref, cacheDir)`. The server depends on the
engine, so this is a direct call — no system-property round-trip. Net effect: **"configured in
Spring Boot"** for the deployment, **standalone defaults** for the engine.

### Consumer repointing

Every hardcoded `data/anbennar/...` path is replaced by an `AnbennarFiles.get(...)` call with the
**real mod-relative path**. The mapping (verified against the pinned checkout):

| Current (flattened) | Mod-relative path passed to `AnbennarFiles.get(...)` |
|---|---|
| `data/anbennar/<x>.bmp` | `map/<x>.bmp` (`terrain`, `provinces`, `rivers`, `heightmap`, `trees`) |
| `data/anbennar/definition.csv` | `map/definition.csv` |
| `data/anbennar/default.map` | `map/default.map` |
| `data/anbennar/{terrain,area,region,superregion,continent,climate}.txt` | `map/…` |
| `data/anbennar/adjacencies.csv` | `map/adjacencies.csv` |
| `data/anbennar/anb_cultures.txt` | `common/cultures/anb_cultures.txt` |
| `data/anbennar/common/…` | `common/…` (unchanged path, now fetched) |
| `data/anbennar/history/…` | `history/…` (unchanged path, now fetched) |
| `data/anbennar/loadingscreens/…` | `gfx/loadingscreens/…` |

Directory-valued reads (the exporters that walk `common/countries`, `common/country_tags`,
`common/religions`, `history/provinces`, `gfx/loadingscreens`) need a **listing** as well as a
fetch. `AnbennarFiles` grows a `list(modRelativeDir)` that queries the GitLab tree API
(`/api/v4/projects/anbennar%2Fanbennar-eu4-dev/repository/tree?path=…&ref=…&recursive=true`,
paginated) and then `get()`s each blob. This directory path is dev-time only (exporters), never
the server runtime.

## Runtime behavior & the accepted GitLab dependency

With the caches un-shipped (status quo) and `data/anbennar` gone, the **live server downloads the
map BMPs from GitLab on demand** — on the first plot-field generation after a cold start, into the
`ref`-keyed cache. **This is accepted:** the deployment may depend on GitLab reachability.
`ProvinceRaster` loading stays lazy (via the provider), so a boot that never generates a plot never
touches GitLab.

**The cache is a mounted volume, not container-ephemeral.** `cacheDir` points at a persistent
volume (on Azure Container Apps, an Azure Files share mounted at e.g. `/mnt/anbennar` via
`ANBENNAR_CACHE_DIR`), so downloaded files survive restarts and are shared across replicas — a
container cold-starts against a warm cache and only fetches what a lock bump introduced. An
optional GitLab **token** (`ANBENNAR_TOKEN`, a Container App secret) lifts the fetch onto the
authenticated rate limit. Local dev keeps the gitignored working-dir default.

Determinism is unaffected: the **locked** `ref` fixes the exact bytes, and terrain generation
already rides its own salted RNG stream.

## Staying current with upstream (active development)

Anbennar is actively developed — devs commit to the mod repo, and we want those changes to land in
CivStudio. But the mod files are **not** the runtime source of truth: the exporters turn them into
committed derived resources (`map/provinces.json`, `cultures.json`, `adjacencies.json`,
`borders.json`, …) and the runtime `WorldMap` loads *those*. `ProvinceRaster` then maps a pixel →
province **id** via `definition.csv` + `provinces.bmp`, and those ids **must match** the ones frozen
in `provinces.json`. So fetching a newer raster at runtime while the committed JSON stays old is an
inconsistency (a re-coloured/renumbered province yields wrong or empty plots). **Runtime fetch and
the committed derived resources must be at the same commit.**

The model is a **dependency lock**, not a live tail:

- **The locked commit is the single source of truth for `ref`.** Record it in a committed engine
  resource (e.g. `map/anbennar-source.lock`) that both `AnbennarFiles` (runtime fetch) and the
  exporters read, so the raster the server downloads always matches the committed `map/*.json`. The
  Spring `civstudio.anbennar.ref` property overrides it only for ad-hoc/testing.
- **Upstream lands via a refresh workflow**, deliberately, as one atomic commit: resolve
  `new-master`'s current tip → run the **full exporter suite** (regenerate `map/*.json` + the plot
  caches from the new files) → write the new SHA into the lock → commit → redeploy. Reproducible
  between refreshes, deterministic per seed, and each upstream bump is reviewable.
- **Optional automation.** A scheduled job can watch `new-master`, run the regenerate, and commit
  the bump (deploy stays manual — the deploy identity is a guest that can't self-authorize, see
  `spectator-server-deployment`). Opt-in, because it auto-commits; otherwise refresh is a manual
  `mvn` run of the exporters when you choose to pull updates.

Rejected: fetching `new-master`'s tip live and regenerating the whole world map at runtime on each
boot — it discards seed-reproducibility and lets the live world change unpredictably underneath a
running session.

## Removal / cutover steps (when this is implemented)

1. Add `com.civstudio.data.AnbennarFiles` (fetch + cache + `list`) with engine defaults, reading
   the locked commit from a committed `map/anbennar-source.lock` resource (§Staying current).
2. Repoint `ProvinceRaster` and every `geo/export/*` constant to `AnbennarFiles.get(...)` per the
   table above.
3. Add the server bridge bean that calls `AnbennarFiles.configure(...)` from `civstudio.anbennar.*`
   (base-url, ref, cache-dir, token).
4. Point `web/build.mjs` / `bake-loading.mjs` at the raw URL + `ref` (a tiny node helper) — or
   leave them until they're retired (§Deferred); if left, they keep needing a local copy, so
   prefer the helper.
5. `git rm` the 15 tracked `data/anbennar/*` files; delete the `data/anbennar/*` entries from
   `.gitignore`; add `.anbennar-cache/` to `.gitignore`.
6. `Dockerfile`: delete `COPY data/anbennar ./data/anbennar` (the server now fetches on demand into
   the cache). `.dockerignore` already excludes `.git`; add `.anbennar-cache` there too.
7. CI (`.github/workflows/deploy-server.yml`): drop the `data/anbennar/**` path trigger.
8. Deploy (manual az): create an Azure Files share, add it as a Container App environment storage +
   a volume/`volumeMount`, and set `ANBENNAR_CACHE_DIR` to the mount path; add `ANBENNAR_TOKEN` as a
   Container App secret and reference it as an env var.
9. Engine tests that call `ProvinceRaster.load()` directly now fetch on demand — they hit GitLab
   the first run, then the cache. (No `assumeTrue` guard needed once fetch is automatic; keep the
   suite offline-friendly by relying on the cache.)
10. Verify: `mvn test` green; `mvn -pl civstudio-server -am spring-boot:run` boots and the demo
    serves plots with `data/anbennar` absent; `node web/build.mjs` still bakes (if not yet retired).

**History rewrite (done):** `git rm` alone leaves the old BMP blobs in `.git` history, so the repo
was also purged with `git filter-repo --path data/anbennar --invert-paths` and force-pushed. This
rewrote all commit SHAs from the first `data/anbennar` commit onward — any other clone must re-clone.

## Deferred (explicitly out of scope for this cut)

- **Retiring the `.json.gz` plot caches → direct calls to the server.** The plan is for the client
  to fetch plot data from the server rather than from baked/cached `.json.gz`. When that lands, the
  server generates and serves plot fields directly; this provider is what feeds that generation its
  rasters. Separate change, not coupled to this one.
- **Retiring the web bakers.** `web/build.mjs` / `bake-loading.mjs` will eventually stop needing mod
  files once plot/terrain data comes from the server. Until then they either use a small node fetch
  helper or are simply not run offline.
- **Relocating generated exporter outputs out of `src/main/resources`.** Every exporter writes its
  product (`map/*.json`, and the gitignored `map/provinces/*.json.gz` plot caches) into the
  hand-authored resource tree, conflating generated data with source. The clean fix is a dedicated
  committed generated-resources root declared in the engine POM (so the JSONs still land on the
  classpath) with the regenerable caches moving to a real cache/`target` dir. Orthogonal to this
  de-vendoring — touches all ~25 exporters + the POM — so tracked as its own follow-up. The
  `map/anbennar-source.lock` added here rides along with that move when it happens.

## Open questions

- **Volume provisioning (deploy).** The Azure Files share + Container App volume/mount and the
  `ANBENNAR_TOKEN` secret are manual az steps (guest deploy identity — see
  `spectator-server-deployment`); document the exact commands when the provider lands.
- **Refresh cadence & automation.** Manual `mvn` re-export when you choose to pull updates, or a
  scheduled job that watches `new-master` and auto-commits the regenerated resources + lock bump?
  The latter is opt-in (it auto-commits, and deploy stays manual). Undecided.
- **Committing the 32 MB plot caches instead** would make the live server self-contained (no
  runtime GitLab dependency) and is far smaller than the gitignore comment ("hundreds of MB")
  implies. Rejected for now in favour of the server-side generation direction above, but recorded
  as the cheap alternative if the runtime dependency ever bites.

---

*When this lands, add a one-line pointer to this doc from `CLAUDE.md`'s subsystem map (the
geography/plots line) and update `docs/geography.md` / `docs/province-plots.md` where they still
describe `data/anbennar` as an on-disk vendored input.*
