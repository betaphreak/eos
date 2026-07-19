# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

CivStudio Backend is a **Strapi 5** headless CMS (TypeScript) that serves as the content/data
authority for a civilization/strategy game (the `civstudio-engine` Java sim). It exposes REST APIs
consumed by the engine + `web/` viewer. Content models mirror what the engine's exporters emit
(`docs/studio-exporter-datasets.md`): world-map geography, the C2C tech/building/unit/terrain
definitions, and naming/calendar/reference data.

> **Data-model rebuild — SHIPPED & live on prod** (`docs/studio-datamodel-rebuild-plan.md`). The 12
> legacy content types were replaced with a new model mirroring the exporter datasets, making Strapi
> the engine's **authoritative content store**. The full chain is live: the seeder loads the exporter
> JSON into Postgres, `GET /api/world-bundle` serves it version-stamped + token-gated, and the engine
> boots from it (prod server: `StrapiWorldSource`; tests/offline: a committed world-bundle fixture).
> `generated/` is **no longer committed** in the engine repo — the exporters now seed studio. Enums are
> plain `enumeration` attributes (no `era`/`rank`/`race`/`advisor`/`skill` tables); FK keys are
> relations; the geometry/art sidecars (`province-edge`/`province-portal`/`route-model`/`terrain-art`)
> are their own types.

## Commands

```bash
npm run develop      # Dev server with autoReload + admin panel hot reload (alias: npm run dev)
npm run start        # Production server, autoReload disabled (used by the Docker image)
npm run build        # Build the admin panel
npm run console      # Interactive Strapi REPL (query the DB, run Document Service calls)
npm run upgrade      # Upgrade Strapi to latest (npm run upgrade:dry for a dry run)

node scripts/gen-schemas.mjs       # (re)generate the 26 repetitive content-type schemas from the spec
node scripts/validate-schemas.mjs  # structural lint of every schema (no DB); CI-friendly
npx strapi ts:generate-types       # authoritative: load the whole registry + regen types/generated
```

There is **no test suite, linter, or formatter** configured in this project. `tsc` runs as part of
`strapi build`/`strapi develop`; there is no standalone typecheck script.

Requires Node 20–24 and a running PostgreSQL instance. Local DB connection is configured via `.env`
(`DATABASE_*` vars). The dev DB name is `strapi-civbox`.

## Architecture

Standard Strapi 5 layout under `src/api/<name>/` — each content type has `content-types/*/schema.json`
(the model), plus `controllers/`, `routes/`, and `services/`. Most controllers/routes/services are
thin factory wrappers (`factories.createCoreController` etc.) and are auto-generated; the
customizations below are what matter.

### The custom code that matters: seeder + world-bundle endpoint

Strapi is seeded and read through two custom pieces, not the admin CRUD (the old per-type
`POST /<plural>/bulk` ingestion endpoints have been **removed**):

- **`scripts/seed.js`** — a standalone Node ETL (CommonJS: `node scripts/seed.js`; `--wipe` to
  TRUNCATE first, `--bulk` for raw batch INSERTs, `SEED_CONCURRENCY` tunable). It boots Strapi
  programmatically and upserts every collection from the engine's exporter JSON via the **Document
  Service**, two-phase: scalars first (natural-key → `documentId`), then relink relations. This is
  how content gets in; `.github/workflows/seed-studio.yml` runs it as a `workflow_dispatch`.
- **`src/api/world-bundle/`** — a custom collectionless route serving `GET /api/world-bundle`: ONE
  gzipped, version-stamped, **path-keyed** bundle where `resources["/map/provinces.json"]` is
  byte-for-byte what the engine reads from that classpath resource, so the engine's `WorldSource`
  just re-serializes `resources[path]` and every parser is unchanged. `services/world-bundle.ts`
  REVERSES `seed.js` (Strapi attrs → committed keys, relations → natural keys); the response is cached
  keyed on content-version (rebuilt only on a version change or `?fresh=1`), with a
  `GET /api/world-bundle/version` + ETag / If-None-Match seam. Gated by the `WORLD_BUNDLE_TOKEN`
  shared secret (open when unset, for dev). `scripts/verify-bundle.js` fetches it and diffs per-record
  against the committed exporter JSON.

When adding a custom route, remember to **enable its action's permission in the Strapi Admin**
(Settings → Roles) — custom routes are not covered by the default CRUD permissions.

### Domain model notes

- The repetitive schemas are **generated**, not hand-edited: `node scripts/gen-schemas.mjs` emits the
  26 repetitive collection types (+ thin factory controller/route/service) from one declarative spec
  whose `ENUMS` block is the **single source** for every Strapi enum (mirrored from the engine's
  `com.civstudio.{era,skill,race,agent}` enums + the measured C2C/Anbennar datasets). The three
  odd/graph-heavy types — **`province`, `tech`, `recipe`** — are hand-written and guarded out of the
  generator (`HAND_WRITTEN`). Editing an enum or a repetitive type means editing the spec and re-running,
  not touching `schema.json` by hand.
- `node scripts/validate-schemas.mjs` structurally lints every schema (valid JSON, relation targets
  resolve, `inversedBy`/`mappedBy` pairs consistent) with **no DB / no running Strapi** — run it after
  any schema change. The authoritative check is `strapi ts:generate-types` (loads the whole registry).
- Content types with human-facing display strings are **i18n-localized** (`pluginOptions.i18n`): the
  `name`/`pedia`/help text is localized, numeric/geometry/key/enum fields are not. Terrain/plot
  reference types (terrain/feature/bonus/…) carry no display name and are non-i18n.
- `province` is the geography hub: FK keys (`owner`/`culture`/`religion`/`tradeGood`/`area`/`region`)
  are relations, `type`/`continent`/`realm`/`winter`/`monsoon`/`climate` are enums, and `neighbors`
  is a self many-to-many (`neighbors`/`isNeighborOf`). `tech` carries the And/Or prereq self-graphs
  and the inverse ends (`unlockedBuildings`/`unlockedUnits`) of the building/unit `prereqTech` unlock
  relations.
- **UID vs folder:** a content-type UID is `api::<apiFolder>.<contentTypeFolder>`; Strapi requires the
  *content-type* folder to equal `singularName` but the *api* folder can differ. Both api folders now
  match their content type (`api::era-modifier.era-modifier`, `api::region-name.region-name`).
- Generated TypeScript types for all content types live in `types/generated/` (regenerated by Strapi
  on build / `ts:generate-types` — do not hand-edit).

### Media uploads → Azure Blob Storage

- Files are stored in Azure Blob Storage via `strapi-provider-upload-azure-sa`
  (container `media`, host `civstudiostore.blob.core.windows.net`), configured in `config/plugins.ts`
  using `STORAGE_ACCOUNT` / `STORAGE_ACCOUNT_KEY` env vars.
- `src/extensions/upload/strapi-server.ts` overrides the upload plugin's `upload` service to
  **rewrite `file.hash` to a sanitized, lowercased version of the original filename**, producing
  human-readable/URL-safe blob names instead of Strapi's random hashes.
- The Azure host is allow-listed in the CSP `img-src`/`media-src` directives in `config/middlewares.ts`.

### Config sync

`strapi-plugin-config-sync` is installed and syncs admin config (roles, content-manager layouts,
plugin settings, i18n locales) as JSON files under `config/sync/`. These files are committed and are
the source of truth for admin/permission configuration across environments — regenerate them via the
plugin (Config Sync admin page or its CLI) rather than editing by hand.

### Other config

- `config/api.ts`: REST defaults are non-standard — `defaultLimit: 500`, `maxLimit: 10000` (tuned for
  bulk reads of large world datasets).
- `config/logger.ts`: minimal `level: message` Winston format (Azure prepends timestamps).
- `config/database.ts`: PostgreSQL only (`pg`), with a large pool (`DATABASE_POOL_MAX=50` in `.env`).

## Deployment

Deployed as a Docker container (multi-stage `dockerfile`, `node:22-alpine`, runs as non-root `node`
user, `npm run start`, port 1337). Env vars are injected via the Azure Container App config rather
than baked into the image. `sharp`/`libvips` OS deps are installed in the image for image processing.

Live as the Azure Container App **`civstudio-backend-app`** (resource group `civstudio`, image
`ghcr.io/betaphreak/civstudio-backend`), serving the admin + API at **https://civstudio.com**. The
image is a **public GHCR** package, so the app pulls it with no registry credentials.

Roll it with **`tools/deploy-studio.ps1`** from the monorepo root (needs local Docker + an
authenticated `az` session): it `docker login ghcr.io`s (`$env:GHCR_TOKEN` or the gh CLI token),
builds the `studio/` image, pushes it, runs `az containerapp update`, then polls until the active
revision runs the new image tag and `/_health` answers 204. This mirrors `tools/deploy-server.ps1`.
`.github/workflows/strapi-deploy.yml` (at the monorepo root) is a build-only CI backup — it
builds+pushes (via the built-in `GITHUB_TOKEN`) on a push to `studio/**` but cannot roll the app
(the repo's guest Azure identity has no deploy service principal). One-time: set the GHCR package to
Public after the first push.
