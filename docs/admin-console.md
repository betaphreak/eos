# The server admin console

A single-page ops console served at **`/`** on the server host (`dev.civstudio.com`) for
managing the running CivStudio spectator server. Every action is admin-gated server-side.
Companion to [`docs/client-server.md`](client-server.md) (§Deployment, auth) and
[`docs/plot-serving.md`](plot-serving.md) (the plot cache it manages).

## What it is

`web/admin.html` — a self-contained (no build step, no framework) dark-themed console.
`PageController` serves it at `/`; the old spectator chat lobby moved to `/lobby`. The page
calls `GET /api/admin/status` on load: a `403`/`401` shows an **"admin access required"** gate
(with a Steam sign-in link), a `200` renders the console and auto-refreshes every 3 s.

The full map site is unaffected — it lives on Static Web Apps (`civstudio.com`) and only talks
to this server's `/api/**`. Making the server host's root the admin console just repurposes a
page few visitors hit directly.

## Auth — the existing `ROLE_ADMIN`, enforced server-side

No new auth machinery. The server already grants **`ROLE_ADMIN`** at login to users on the
**`civstudio.auth.admins`** allow-list (env `CIVSTUDIO_AUTH_ADMINS`, comma-separated — match by
`app_user` id, provider subject / SteamID64 / Google sub, `provider:subject`, or OIDC email).
`AdminController` gates **every** endpoint with `CurrentUserResolver.isAdmin(http)` → `403`
otherwise (the same gate `SessionController.denyWrite` uses). The page HTML itself is public,
but it does nothing without an admin session — the actions are the real boundary.

> **Config prerequisite.** The allow-list is **empty by default** — nobody is an admin until
> `CIVSTUDIO_AUTH_ADMINS` is set on the Container App to an operator's identity, e.g.
> `az containerapp update -n civstudio-server -g civstudio --set-env-vars CIVSTUDIO_AUTH_ADMINS='steam:7656...'`.
> Until then the console shows the gate for everyone (including the deployer).

Gating is covered by `AdminControllerTest` (anonymous + a plain user get `403`; an allow-listed
admin gets `200`), mirroring `SessionOwnershipTest`.

## Functions (v1)

| Panel | Action | Endpoint | Backend |
|---|---|---|---|
| **Plot cache** | Drop plot cache | `POST /api/admin/plots/clear` | `PlotService.clear()` — LRU + volume `*.json.gz`; provinces regenerate on demand |
| | Warm the world | `POST /api/admin/plots/warm` | `PlotService.warmAll()` on a background virtual thread — generate every province (under the sim pause) so visitors never hit a cold gen; progress from the status cached count |
| | status (cached/total/generating/warming) | `GET /api/admin/status` | `PlotService.status()` |
| **Fetch caches** | Drop Anbennar cache | `POST /api/admin/caches/anbennar/clear` | recursively delete the `ANBENNAR_CACHE_DIR` (force GitLab re-fetch). *(Civ4 art cache is build-time only — nothing to drop server-side.)* |
| **Server** | uptime / heap / sessions / admins / you | `GET /api/admin/status` | `Runtime` + `ManagementFactory` + `SessionHost.list()` + `CurrentUserResolver` |
| **Sessions** | list, pause / resume / stop | `GET /api/sessions`, `POST /api/sessions/{id}/control` | the existing `SessionController` (admins bypass its ownership check) |

Every action button carries a `title` **tooltip** explaining its effect; destructive drops
(`clear`, Anbennar) confirm first.

## Deploy notes

- **Dockerfile** copies `web/admin.html` into the image (alongside `web/lobby.html`) — a page
  not copied 404s in prod (`PageController` reads from disk).
- Deploying the server (`tools/deploy-server.ps1`) ships the console. Set
  `CIVSTUDIO_AUTH_ADMINS` (above) so an operator can actually use it.
- The **Drop plot cache** button drops the cache for the CURRENT `MAP_VERSION` so the server
  regenerates provinces on demand — useful when generation changed *without* a version bump (a
  developer slip), or to force a re-read. It is **not** part of the normal deployment flow: a real
  generation change bumps `MAP_VERSION`, which invalidates by pointing at a fresh `map/v<new>` (see
  `docs/client-server.md` §Deployment). Note dropping a version whose plots carry **GeoNames names**
  loses them until the next CI bake — production cannot regenerate names.

## Future

Session **re-found the demo** and **create** buttons; per-session tick-rate control; dropping
other caches; a live event-log tail; a proper generation-version on the plot cache so the drop
is rarely needed (`docs/urban-plots.md` open items).
