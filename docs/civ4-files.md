# Design & plan: de-vendoring the Civ4 / C2C files (on-demand fetch)

**Status:** ✅ **Implemented + cut (2026-07-11).** The on-demand **providers**
(`com.civstudio.data.Civ4Files` in the engine + `web/civ4.mjs` for the node bakers) shipped, every
consumer is repointed (all ten Java exporters regenerate **byte-identical** resource JSON fetching
from C2C; the node bakers fetch through `web/civ4.mjs`), and **`data/civ4/` is removed from the repo
and its history** (`git rm` + `git filter-repo --path data/civ4 --invert-paths` + force-push, ~24 MB
reclaimed). The lock pins the C2C `master` tip; the fuller web rebake was adopted (see below); and the
**scheduled auto-propagation job** is now built (`.github/workflows/propagate-c2c.yml`, §Removal
step 9). The companion to [[anbennar-files]].

**What the classification found (the big simplification):** **every one of the 58 files is verbatim
C2C** — 56 byte-identical to the current C2C tip, `CIV4ArtDefines_Terrain.xml` an older-but-strict-
subset revision (C2C has since added lunar/space terrains the exporter curates out anyway), and
`GameFont_120.tga` byte-identical to C2C's oddly-named `GameFont_120_(unused prrof of concept).tga`.
**There are zero CivStudio-derived files:** the `Regular_` / `SpecialBuildings_` / `zProviders_`
building infos and `Manufactured_CIV4BonusInfos.xml` are **C2C's own split filenames** (it ships the
building/bonus infos already split), fetchable 1:1 — not curated splits of a monolith. So the whole
"derived split" open question is moot: no split step, nothing kept committed.

**The one big difference from Anbennar, up front:** `data/civ4/` is **`.dockerignore`-excluded and
never read by the running server** — it is used **only at dev time** (the `geo/export/*` exporters
that bake the `generated/` `map/*.json` / resource JSON — which now **seed studio** and are no longer
committed (`docs/studio-datamodel-rebuild-plan.md`) — and the node web bakers that bake the committed
`web/assets`). So this is *simpler* than the Anbennar cut: **no runtime fetch, no mounted volume, no
Spring/server config, no `AnbennarSourceConfigurer` analogue, no Docker/CI/deploy changes.** Purely a
dev-time build-input concern.

**Canonical source:** the **Caveman2Cosmos GitHub repo**, fetched with authenticated **`gh api`**
(raw media type) — `raw.githubusercontent.com` rate-limits and writes HTML error pages as files (see
the `gh-api-for-civ4-files` note). The terrain art comes from C2C's **`UnpackedArt`** tree; the
resource-icon atlas is **`GameFont_120.tga`**. C2C is **also under active development**, and the
intent is to **propagate its changes automatically** — so, unlike Anbennar (a frozen runtime lock),
this **tracks C2C's default branch** and a scheduled job regenerates + commits the outputs (§Propagation).

---

## Goal

Stop vendoring the ~32 MB / 58 files under `data/civ4/` (the C2C art + XML). Fetch what the build
needs, on demand, from the pinned C2C GitHub source, into a gitignored cache — with **no
`data/civ4` in the repo**.

## What's under `data/civ4` (32 MB, 58 tracked files)

- **C2C info/schema XML** (top level): `CIV4{Bonus,BonusClass,Feature,Improvement,Route,RouteModel,
  Terrain}Infos.xml`, `CIV4ArtDefines_{Bonus,Terrain}.xml`, `C2C_CIV4TerrainSchema.xml`,
  `Caveman2Cosmos.xsd`, `C2C_Planet_Generator_0_68.py`.
- **Terrain art** (`assets/`, ~24 MB): land/water texture `.dds`, feature `.dds` (trees, palms,
  icepack…), river/wave `.dds`, and `res/Fonts/GameFont.tga` + `GameFont_120.tga` (the resource-icon
  atlases baked into `web/assets`).
- **Tech XML** (`assets/XML/…`): `Technologies/CIV4TechInfos.xml`, `GameText/Tech_CIV4GameText.xml`.
- **CivStudio-derived splits** (see below): `Regular_CIV4BuildingInfos.xml` (4.7 MB),
  `SpecialBuildings_CIV4BuildingInfos.xml`, `zProviders_CIV4BuildingInfos.xml`,
  `Manufactured_CIV4BonusInfos.xml`.

## Who reads it — all dev-time

- **Java exporters** (`civstudio-engine/.../geo/export/`): `BonusExporter`, `FeatureExporter`,
  `ImprovementExporter`, `RouteModelExporter`, `TerrainArtExporter`, `HousingExporter`,
  `RecipeExporter`, `ManufacturedBonusExporter` (+ `Civ4Xml` for the schema). Each reads a
  `data/civ4/*.xml` and emits a **committed** resource JSON (`terrains.json`, `features.json`,
  `improvements.json`, `bonuses.json`, `recipes.json`, `map/terrain-art.json`, …). Run rarely, by
  hand, to regenerate those resources.
- **Node web bakers**: `web/build.mjs` (resolves terrain `.dds` under `data/civ4/assets` and reads
  `CIV4BonusInfos.xml` + `CIV4ArtDefines_Bonus.xml` for the bonus icons via the GameFont atlas →
  bakes `web/assets`), `tools/build-techs.mjs` (tech icons — note the `build-techs.mjs`-art memory),
  and `gamefont.mjs` (reads `res/Fonts/GameFont*.tga`).
- **Nothing at runtime**: `.dockerignore` excludes `data/civ4`, and the committed resource JSON /
  web assets are what ship. Confirmed: no `data/civ4` read in the server runtime path.

## Classification (done): all 58 files are verbatim C2C

Step 1 is complete — verified by comparing each committed file's **git blob SHA** against the C2C
repo (`caveman2cosmos/Caveman2Cosmos`, default branch `master`). **Every file is verbatim**, so the
committed→C2C path map in `Civ4Files.FILE_MAP` (mirrored in `web/civ4.mjs`) is 1:1:

| committed under `data/civ4/` | C2C repo path |
| --- | --- |
| `CIV4{Terrain,Feature,Improvement,Bonus,BonusClass}Infos.xml`, `Manufactured_CIV4BonusInfos.xml` | `Assets/XML/Terrain/…` |
| `{Regular,SpecialBuildings,zProviders}_CIV4BuildingInfos.xml` | `Assets/XML/Buildings/…` |
| `CIV4ArtDefines_{Terrain,Bonus}.xml`, `CIV4RouteModelInfos.xml` | `Assets/XML/Art/…` |
| `C2C_CIV4TerrainSchema.xml`, `Caveman2Cosmos.xsd` | `Assets/XML/Schema/…` |
| `CIV4RouteInfos.xml` | `Assets/XML/Misc/…` |
| `assets/XML/Technologies/CIV4TechInfos.xml`, `assets/XML/GameText/Tech_CIV4GameText.xml` | `Assets/XML/…` (same) |
| `res/Fonts/GameFont.tga` | `Assets/res/Fonts/GameFont.tga` |
| `res/Fonts/GameFont_120.tga` | `Assets/res/Fonts/GameFont_120_(unused prrof of concept).tga` (renamed on vendoring) |
| `C2C_Planet_Generator_0_68.py` | `PrivateMaps/C2C_Planet_Generator_0_68.py` |
| `assets/terrain/**/*.dds` (38 files) | `UnpackedArt/art/terrain/**` (same relative path/case) |

Two wrinkles, both benign: (1) `CIV4ArtDefines_Terrain.xml` drifted from tip — C2C added lunar/space
`TerrainArtInfo`s since the file was vendored — but `TerrainArtExporter` curates to the 16-terrain
`KEEP` set, so those additions are filtered out and the output is unchanged at tip. (2) the split
building/bonus filenames are **C2C's own** (it ships them pre-split), so there is nothing to
regenerate or keep committed — the earlier "CivStudio-derived" hypothesis was wrong.

## The design as built (mirrors `AnbennarFiles`, GitHub-sourced, dev-time only)

- **`com.civstudio.data.Civ4Files`** (engine, plain Java) — `get("CIV4BonusInfos.xml")` /
  `getC2C("UnpackedArt/art/terrain/…")` → a local cached `Path`, downloading on a miss. Fetches from
  the C2C GitHub repo at the locked ref via the **contents API with `Accept: application/vnd.github.raw`**
  (not `raw.githubusercontent.com`), token from `GITHUB_TOKEN`/`civstudio.civ4.token` else a
  best-effort `gh auth token`. Cache keyed by ref (`<cacheDir>/<ref>/<c2cPath>`), atomic
  temp-then-move, per-`(ref,path)` lock — same shape as `AnbennarFiles`. `FILE_MAP` (the table above)
  + the `assets/terrain/`→`UnpackedArt/art/terrain/` prefix rule do the committed→C2C translation.
  `Civ4Xml.fetch(rel)` and `TechInfoExporter.parse(rel)` route the exporters through it. **No Spring
  bridge** (the server never reads Civ4).
- **`web/civ4.mjs`** — the node sibling for `build.mjs`/`build-techs.mjs`/`gamefont.mjs`, same
  `FILE_MAP` and cache layout (the two share `.civ4-cache/<ref>/`). The synchronous `get` /
  `resolveArt` (case-insensitive against `UnpackedArt/art`) the bakers call serve from the disk
  cache; an async **`prefetch({files, arts})`** warms that cache with a **parallel `fetch()`** pass
  (token once, bounded concurrency) so a bake of ~300 tech icons runs in ~8 s instead of ~300 serial
  `gh` spawns. A cold sync call still works via a single `gh api` subprocess (the fallback).
- **Ref = a pinned SHA, not the branch.** `civ4-source.lock` currently pins
  `f174979b7336ee42839077e997bea1b3c129dce5` (C2C `master` tip on 2026-07-11). This **departs from
  the original plan's "track the default branch"** on purpose: a pin makes dev-time regeneration
  reproducible and keeps the verify step meaningful. The providers use whatever ref string the lock
  holds, so switching to literal branch-tracking is a one-line change — the auto-propagation job
  (below) is what bumps the pin. *(Confirm the pin-vs-branch choice — see open questions.)*
- **Propagation (auto) — not yet built.** A scheduled job (cron/CI) bumps the lock to the current
  C2C tip, re-runs the exporters + web bakers, and commits regenerated outputs iff they changed (any
  server-affecting resource still needs a manual deploy — guest identity, see
  `spectator-server-deployment`).

## Consumer repointing — done

- All ten exporters (`Terrain`/`Feature`/`Improvement`/`Bonus`/`RouteModel`/`TerrainArt`/`Housing`/
  `ManufacturedBonus`/`Recipe`Exporter + `TechInfoExporter`) swapped their `"data/civ4/…"` constants
  for the committed-relative key and fetch via `Civ4Files`. **Verified byte-identical**: every one of
  the 11 regenerated resources (`terrains`/`features`/`improvements`/`bonuses`/`housing`/
  `manufactured-bonuses`/`recipes`/`tier1-providers`/`techs`.json + `map/route-models`/`terrain-art`)
  diffs clean against the committed baseline, fetching from C2C.
- `web/build.mjs` (`resolveArt` → `civ4.resolveArt`; bonus XML reads → `civ4.get`), `build-techs.mjs`
  (`resolveArt`) and `gamefont.mjs` (`loadGameFont`) repointed.

**Web-bake caveat — the rebake is *fuller*, not byte-identical.** The committed `web/assets` were
baked when the full art tree wasn't on disk (`UnpackedArt` had been removed; `data/civ4/assets` held
only a hand-picked subset), so some referenced art resolved to nothing and was skipped. Fetching from
C2C **restores that art**, so a rebake *adds* content rather than reproducing byte-for-byte:
`build-techs.mjs` now yields **292 tech icons vs the committed 291** (the one restored is
`TECH_INDUSTRIAL_LIFESTYLE` — the `build-techs.mjs`-art memory), and `build.mjs` would likewise
restore the bamboo/cactus/grass + NIF-rendered tree art it references but that was never vendored.
This is an improvement, but it means **adopting the fuller `web/assets` is a deliberate choice**, not
part of the byte-identical claim. (`build.mjs` also can't be run end-to-end until the Anbennar
`terrain.bmp` it reads is present — an orthogonal [[anbennar-files]] concern.)

## Removal / cutover steps — remaining (pending go-ahead)

1. ~~Classify every `data/civ4` file~~ — done (all verbatim; table above).
2. ~~Add `Civ4Files` + the node helper~~ — done.
3. ~~Repoint the exporters + node bakers~~ — done.
4. ~~Decide the derived split files~~ — moot (all verbatim C2C filenames).
5. ~~`git rm data/civ4`; cache in `.gitignore`; drop the `.dockerignore` line~~ — done.
6. ~~Verify byte-identical resource JSON~~ — done. Web assets: the fuller bake was adopted (above).
7. No Docker/CI/deploy changes needed.
8. ~~**History rewrite**: `git filter-repo --path data/civ4 --invert-paths --force` + force-push~~ —
   done (100 blob objects → 0; the now-empty `git rm` commit was auto-pruned). filter-repo drops the
   `origin` remote by design — re-added before pushing.
9. ~~**Scheduled auto-propagation** job~~ — done: `.github/workflows/propagate-c2c.yml` (weekly +
   manual) bumps the lock to the C2C tip, re-runs the C2C-sourced Java exporters + `build-techs.mjs`,
   validates with the test suite, and commits **iff** the outputs changed. Scope note: the
   Anbennar-sourced `map/*.json` (frozen GitLab pin) and `build.mjs`'s terrain/bonus/tree art (needs
   the Anbennar raster + plot caches) are **not** rebaked there. Engine-jar resources it changes are
   server-affecting → still a **manual** deploy (guest identity); `tech-icons.webp` auto-deploys via
   `deploy-web.yml`.

## Resolved & remaining open questions

- **All files verbatim; splits are C2C's own** — resolved (the former big open question).
- **Art + atlas sources** — resolved: terrain art from `UnpackedArt/art/terrain`, resource icons from
  `GameFont_120.tga` (C2C's `GameFont_120_(unused prrof of concept).tga`).
- **Pin vs branch** — *decision to confirm.* Shipped as a **pinned SHA** for reproducibility; the
  plan wanted branch-tracking. Trivially switchable; the auto-propagation job bumps the pin either
  way.
- **Adopt the fuller web bake?** — *decision to confirm.* Regenerating `web/assets` now restores
  art that had been lost, changing the committed binaries (a strict improvement, not byte-identical).
- **History rewrite + cutover** — planned (steps 5,8), **not yet executed** (destructive).

---

*Provider + repointing implemented 2026-07-11; the repo cut / history rewrite await go-ahead. When
those land, add a one-line pointer from `CLAUDE.md` and cross-link [[anbennar-files]].*
