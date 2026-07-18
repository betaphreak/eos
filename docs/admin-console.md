# The server admin console

Ops surface for managing the running CivStudio spectator server — **now Strapi admin homepage
widgets** (in the Strapi admin at `civstudio.com/admin`), backed by the server's admin-gated
`/api/admin/**` + `/api/sessions/**` on the server host (`dev.civstudio.com`). Every action is
admin-gated server-side. Companion to [`docs/client-server.md`](client-server.md) (§Deployment,
auth) and [`docs/plot-serving.md`](plot-serving.md) (the plot cache it manages).

## What it is

Two homepage widgets registered in the Strapi admin (`studio/src/admin/app.tsx` →
`app.widgets.register`, components under `studio/src/admin/components/`):

- **Server ops** (`ServerOpsWidget`) — live server + plot-cache status, **Drop plot cache** and
  **Clear lobby chat**.
- **Live sessions** (`SessionsWidget`) — the live sessions with per-session **pause / resume /
  stop** (POST `/api/sessions/{id}/control` with a `{action}` body; admins bypass the ownership
  check).

Both poll every 3 s and, on a `401`/`403`, render a **sign-in gate** (a Steam link to the server)
instead of data. This **replaced** the old self-contained `web/admin.html` page: the page is
retired and `GET /` on the server now **302-redirects** to the Strapi admin
(`civstudio.admin.console-url`, default `https://civstudio.com/admin`). The spectator chat lobby
stays at `/lobby`; the full map site is unaffected (Static Web Apps, `/api/**` only).

### Cross-origin, but same-site — no new auth

The widgets run at `civstudio.com` and call the server at `dev.civstudio.com` **directly** with
`credentials:'include'`. This works with **zero new auth machinery** because the two hosts share
the registrable domain `civstudio.com`:

- the server's CORS already allows the `civstudio.com` origins **with credentials** (`WebConfig` /
  `application.yml`), and
- the login session cookie is `SameSite=Lax` scoped to `civstudio.com`, so it rides these
  cross-subdomain requests (same-site, **not** a blocked third-party cookie).

So the server enforces the **existing `ROLE_ADMIN`** exactly as before. The one UX consequence: the
operator needs a *server* login (Steam/Google) in addition to their Strapi admin login — the gate
above handles the not-signed-in case. The server base URL is a build-time env
(`VITE_CIVSTUDIO_SERVER`, default `https://dev.civstudio.com`; see `studio/src/admin/lib/serverApi.ts`).

## Auth — the existing `ROLE_ADMIN`, enforced server-side

No new auth machinery. The server already grants **`ROLE_ADMIN`** at login to users on the
**`civstudio.auth.admins`** allow-list (env `CIVSTUDIO_AUTH_ADMINS`, comma-separated — match by
`app_user` id, provider subject / SteamID64 / Google sub, `provider:subject`, or OIDC email).
`AdminController` gates **every** endpoint with `CurrentUserResolver.isAdmin(http)` → `403`
otherwise (the same gate `SessionController.denyWrite` uses). The widgets render for any Strapi
admin, but do nothing without a *server* admin session — the endpoints are the real boundary.

> **Config prerequisite.** The allow-list is **empty by default** — nobody is an admin until
> `CIVSTUDIO_AUTH_ADMINS` is set on the Container App to an operator's identity, e.g.
> `az containerapp update -n civstudio-server -g civstudio --set-env-vars CIVSTUDIO_AUTH_ADMINS='steam:7656...'`.
> Until then the console shows the gate for everyone (including the deployer).

Gating is covered by `AdminControllerTest` (anonymous + a plain user get `403`; an allow-listed
admin gets `200`), mirroring `SessionOwnershipTest`.

## Functions (v1 widgets)

| Widget | Action | Endpoint | Backend |
|---|---|---|---|
| **Server ops** | status: uptime / heap / sessions / admins / you / plots / map version | `GET /api/admin/status` | `Runtime` + `ManagementFactory` + `SessionHost.list()` + `PlotService.status()` + `CurrentUserResolver` |
| | Drop plot cache | `POST /api/admin/plots/clear` | `PlotService.clear()` — LRU + volume `*.json.gz`; provinces regenerate on demand |
| | Clear lobby chat | `POST /api/admin/chat/clear` | `ChatStore.clearAll()` |
| **Live sessions** | list, pause / resume / stop | `GET /api/sessions`, `POST /api/sessions/{id}/control` `{action}` | the existing `SessionController` (admins bypass its ownership check) |

Destructive actions (drop cache, clear chat, stop session) confirm first.

**Deferred from the widgets (were in admin.html):** the read-only **region → country map**
(`GET /api/admin/region-map`) — it is now modeled as the `region-name` Strapi **content type**
(see `docs/studio-datamodel-rebuild-plan.md`), so it belongs in content, not an ops widget; and the
per-session **taxation** levers (`setTaxRate` via `/api/sessions/{id}/commands`) — easy to add as a
third widget later. Both endpoints remain on the server.

## Deploy notes

- **Dockerfile** no longer copies `web/admin.html` (retired). `web/lobby.html` is still copied.
- The Strapi admin (widgets) deploys with **studio** (`tools/deploy-studio.ps1`); the
  `/api/admin/**` backend deploys with the **server** (`tools/deploy-server.ps1`). Set
  `CIVSTUDIO_AUTH_ADMINS` (above) so an operator can actually use it. Point the widgets at a
  non-default server with `VITE_CIVSTUDIO_SERVER` at studio build time.
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
