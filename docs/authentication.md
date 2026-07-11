# Design & plan: authentication, users & session ownership

**Status:** **Phases 1–3 implemented (2026-07-11)** — ownership plumbing, Steam OpenID 2.0
browser sign-in (the default provider), and secondary OIDC (Google) + a persistent site-wide
login control; the native-client JWT path (Phase 4) is proposed. This note scopes the work so the
server picks up a real user model before interactive play (Phase B) opens the command channel to
the public. See §Suggested phasing for the per-phase status.

**Goal (decided 2026-07-11):** **player accounts + session ownership**, with **Steam as the
default sign-in** — both the web "Sign in through Steam" flow and the native Steam client's
session-ticket flow — since CivStudio is headed for a Steam release. Generic **OIDC/OAuth2**
providers (Google / Microsoft / GitHub) are offered as secondary options for players without
Steam. No CivStudio-managed passwords.

Reads on top of: [`docs/client-server.md`](client-server.md) (the tick-authoritative
`HostedSession`, the `state = f(spec, command-log)` model, and Phase B — "a client command
channel + player action model, server-authoritative"), and
[`docs/spring-boot-migration.md`](spring-boot-migration.md) §Deployment (the Azure
guest-identity constraints and the Static-Web-Apps-front / Container-App-API split that force
the cross-origin decisions below).

---

## Motivation & what changes

The server is **fully open today.** Every endpoint is anonymous (`SessionController`,
`BundleController`, `PageController`, `AssetController`); the only access control is CORS
(`WebConfig` + `application.yml`), which is not authentication. Anyone can found a session,
and — critically — anyone can `POST /control` (pause/**stop**/rate) or `POST /commands`
(`setTaxRate`) against **anyone's** session. That is fine for a spectator demo and untenable
the moment commands mean something.

The engine already gives us the hard half for free. Per `docs/client-server.md`, authoritative
state is `f(SessionSpec, ordered command-log)`. Auth adds exactly one thing to that model: an
**owner** on each session and each command, checked before a command enters the log. Spectating
stays open; *acting* becomes owned.

Two orthogonal pieces, kept separate on purpose:

1. **Authentication** — *who is this request?* Delegated to external providers. Produces a
   canonical `app_user`.
2. **Authorization** — *may this user drive this session?* A session has an owner; write
   endpoints check it. Read/spectate endpoints don't.

---

## Identity model

One canonical user, many external identities. A login through any provider resolves to (or
creates) a row keyed by `(provider, provider_subject)`:

- **Steam** (the default) → `provider_subject` is the **SteamID64** (a 64-bit account id), from
  either the OpenID 2.0 web flow or a validated session ticket.
- **OIDC providers** (Google / Microsoft-Entra / GitHub-as-OAuth2) → `provider_subject` is the
  ID-token `sub`.

```
app_user
  id                UUID   PK              -- CivStudio's own surrogate id (what sessions reference)
  provider          VARCHAR                -- 'google' | 'microsoft' | 'github' | 'steam'
  provider_subject  VARCHAR                -- OIDC 'sub', or SteamID64
  display_name      VARCHAR
  avatar_url        VARCHAR NULL
  email             VARCHAR NULL           -- absent for Steam; never a login key
  created_at        TIMESTAMP
  last_login_at     TIMESTAMP
  UNIQUE (provider, provider_subject)
```

**Account linking is out of scope for v1.** Logging in with Google and later with Steam yields
two `app_user` rows. A "link accounts" feature (merge under one `id`) is a later addition — see
Open questions. Keeping `app_user.id` a surrogate (not the provider subject) is what makes that
future merge cheap: sessions reference `app_user.id`, not the provider key.

Where it lives: the **existing Postgres seam** (`PersistenceConfig` builds a datasource only
when `spring.datasource.url` is set; `JdbcCommandStore` already creates its table with portable
`CREATE TABLE IF NOT EXISTS` DDL across H2 + Postgres). `app_user` and `game_session` (below)
follow the same pattern — a `JdbcUserStore` / `JdbcSessionRegistry` with idempotent schema init,
so the server still boots datasource-free for local spectating and tests.

---

## Authentication mechanisms

Spring Security is the front door for all of them; each provider is one authentication path that
ends at the same `app_user`. **Steam is the default** — it's the button the login UI leads with
and the only one guaranteed present; the OIDC providers are secondary and individually optional
(enabled only when their client-id/secret is configured).

### 1. Steam "Sign in through Steam" (browser, **default**) — **OpenID 2.0, hand-rolled**

Steam is an **OpenID 2.0** *provider*, not OIDC/OAuth2 — and **Spring Security removed OpenID
2.0 support** (the old `spring-security-openid` module is gone). So this is a small custom
`AuthenticationFilter`, not a `ClientRegistration`:

1. Redirect the browser to `https://steamcommunity.com/openid/login` with the OpenID 2.0
   `checkid_setup` params (realm + return-to = our callback).
2. On the return, take the asserted `claimed_id`
   (`https://steamcommunity.com/openid/id/<steamid64>`) and **verify it** by POSTing back to
   Steam with `openid.mode=check_authentication` (this is the anti-forgery step — never trust the
   redirect params without it).
3. Extract the SteamID64, optionally enrich display name/avatar via
   `ISteamUser/GetPlayerSummaries` (needs a Web API key), upsert `app_user(provider='steam')`,
   and establish the session the same way the OIDC path does.

It's ~one filter + one small OpenID-2.0 verify helper. No maintained Spring library for this;
treat it as bespoke and unit-test the verification path hard (a forged `claimed_id` that skips
`check_authentication` must be rejected).

### 2. Steam **session ticket** (native client) — Steamworks + Web API

When CivStudio runs as a Steam app, the native client already has a logged-in Steam user. It
calls `GetAuthSessionTicket` (Steamworks SDK) and sends the ticket (hex) to our API; the server
validates it server-to-server via **`ISteamUserAuth/AuthenticateUserTicket`** (publisher Web API
key + our `appid`), which returns the SteamID64. Same upsert → same `app_user`. This path issues
a **bearer token** (below), not a browser cookie, because the caller is a game process, not a
browser. Deferred until there's a native client, but the `app_user` shape is designed so it drops
in without a migration.

### 3. OIDC / OAuth2 login (browser, secondary) — `spring-boot-starter-oauth2-client`

For players without Steam. Standard authorization-code flow; register each provider under
`spring.security.oauth2.client.registration.*` (client-id/secret from env, never committed) —
each is optional and simply absent from the login UI when unconfigured. Spring handles the
redirect dance and ID-token validation; we supply an `OAuth2UserService` / `OidcUserService`
that upserts the `app_user` and attaches authorities. The low-risk, batteries-included path, and
the reason the identity model is provider-agnostic rather than Steam-only.

---

## Sessions, tokens & the cross-origin problem

This is the decision the deployment topology forces. The map site is served from **Static Web
Apps** (`anbennar.civstudio.com`) and the API is a **separate origin** (`dev.civstudio.com`).
Cookies and cross-origin don't mix casually.

**Recommendation — a hybrid keyed on caller type:**

- **Browser → httpOnly session cookie scoped to the parent domain `civstudio.com`.** Both the
  site and the API are subdomains of `civstudio.com`, so a `Domain=civstudio.com;
  SameSite=Lax; Secure; HttpOnly` cookie is *same-site* to both and rides along automatically —
  no token in JS, so XSS can't exfiltrate it. (Use `civstudio.com` with **no leading dot** —
  `SESSION_COOKIE_DOMAIN=civstudio.com`; Tomcat's RFC 6265 cookie processor rejects the obsolete
  `.civstudio.com` form outright, which crashes any request that creates the session.) CORS must switch to
  `allowCredentials(true)` with an explicit origin allow-list (no `*` with credentials), which
  we already maintain in `WebConfig`. The OIDC/Steam-OpenID redirect flows land on the API
  origin, set the cookie, and bounce back to the site.
  *(If we ever host the site on an unrelated apex domain, this breaks and we fall back to the
  bearer path for the browser too — noted so the choice is revisitable.)*
- **Native Steam client → short-lived bearer JWT** minted after ticket validation, sent as
  `Authorization: Bearer …`. Validated by `spring-boot-starter-oauth2-resource-server` as a
  self-issued JWT (our own signing key), so the same `SecurityFilterChain` accepts either a
  cookie session or a bearer token and resolves both to one `app_user` principal.

Server-side session state stays minimal (the principal + authorities); the sim itself is *not*
in the HTTP session — it lives in `SessionHost`, unchanged.

---

## Authorization: session ownership

`SessionSpec` stays exactly as it is — `(seed, scenario, provinceId)`, the **determinism root**.
Ownership must **not** leak into it (state is a pure function of spec+log; *who* owns a run must
not change the run). Ownership is metadata *alongside* the session.

Two consequences for the current code:

1. **Session identity needs a surrogate id.** `SessionHost` used to key by `spec.id()` =
   `"<scenario>-<seed>"`, idempotent by that id. With player-owned games, two players asking for
   the same scenario+seed must get **two** games, not a shared one. **Phase 1 shipped** this as a
   deterministic `SessionHost.sessionKey(spec, owner)` — unowned → `spec.id()` (the demo keeps its
   stable id), owned → `spec.id()@owner`. Deterministic derivation was chosen over a random
   UUID because it keeps `state = f(spec, log)` resume working with **no extra table**:
   `session_command.session_id` (already `VARCHAR(160)`) just carries this key. When Phase 2 wants
   real multiple-games-per-owner and opaque ids, it promotes this to a persisted `game_session`
   registry with a minted `session_id` (the column stays as-is):

   ```
   game_session                         -- Phase 2 (not built in Phase 1)
     session_id  VARCHAR PK        -- opaque minted id; the value in session_command.session_id
     owner_id    UUID  FK app_user
     seed        BIGINT
     scenario    VARCHAR
     province_id INT
     created_at  TIMESTAMP
   ```

2. **Write endpoints check ownership; read endpoints don't.** ✅ **Phase 1.** The caller
   (`CurrentUserResolver`) is compared against `HostedSession.owner()` in `SessionController`'s
   `canWrite` (`admin` override arrives with real roles in Phase 2).

**Endpoint protection matrix** (✅ = enforced in Phase 1):

| Endpoint | policy |
|---|---|
| `GET /`, `/assets/**`, `/api/bundle` | public (site shell + map) |
| `GET /actuator/health\|info` | public (Container-App probes + site splash) |
| `GET /api/sessions`, `GET /api/sessions/{id}/stream` | public — **spectating stays open** |
| `POST /api/sessions` (found a game) | anonymous-permissive in Phase 1 (founder → `owner`, or unowned); **authenticated** in Phase 2 |
| `POST /api/sessions/{id}/control` | ✅ **owner-only** when owned; open when unowned (admin override Phase 2) |
| `POST /api/sessions/{id}/commands` | ✅ **owner-only** when owned; open when unowned — checked *before* the command enters the log |

The ownership check on `/commands` is the real security boundary: `SessionController.command`
stamps the tick and validates the lever, and now (Phase 1) runs the `canWrite` guard *before*
`hs.submit(...)`. `CommandStore` is unchanged — ownership lives on the `HostedSession` (and, in
Phase 2, the `game_session` registry), so the command rows stay lean.

---

## Configuration & deployment

- **Secrets**: OIDC client-id/secret per provider, the Steam Web API key, and the JWT signing
  key are all env-injected (Container App secrets), never committed — same discipline as
  `ACR_*`/`EOS_CORS_ORIGINS`.
- **Datasource required in prod**: users + ownership need Postgres. The server keeps booting
  datasource-free (spectator-only, no login) for local dev and tests — when no datasource is
  present, the auth beans degrade to "spectate only, cannot found/own" rather than failing
  startup. Mirrors the `NoOpCommandStore` fallback.
- **No new Azure role assignments** are needed (the guest-identity constraint from
  `spring-boot-migration.md` §Deployment still bites) — OIDC/Steam are outbound HTTPS + our own
  Postgres, no ARM/role work.
- **Redirect URIs** for each provider must be registered for `dev.civstudio.com` (and localhost
  for dev). Steam's realm/return-to must match the API origin.

---

## Suggested phasing

1. **Ownership plumbing, no login yet.** ✅ **Implemented (2026-07-11).** `HostedSession` carries
   an `owner` (nullable `app_user` id; `null` = unowned/public), threaded through
   `SessionHost.create(spec, owner)`. The registry key is now a **deterministic**
   `SessionHost.sessionKey(spec, owner)` (unowned → the old `spec.id()`, so the demo keeps its
   stable id; owned → `spec.id()@owner`), which keeps `state = f(spec, log)` resume working
   *without* needing the `game_session` table yet — so that table and opaque/UUID ids are
   **deferred to Phase 2**, when real accounts and multiple-games-per-owner actually require them.
   Write endpoints (`/control`, `/commands`) are gated by `canWrite`: **unowned → open**
   (unchanged demo/spectator behavior), **owned → owner only** (else `403`); founding stays
   anonymous-permissive for now. The caller's identity comes from a `CurrentUserResolver` stub —
   a dev-only `X-CivStudio-User` header, honored only when `civstudio.auth.trust-dev-user-header`
   is set (default off), a spoof-safe stand-in that Phase 2 swaps for the `SecurityContext`.
   Covered by `SessionOwnershipTest`.
2. **Steam OpenID 2.0 browser login** (the default path). ✅ **Implemented (2026-07-11).**
   Spring Security is on the classpath with a **permit-all** `SecurityFilterChain` (authz stays
   per-session in `SessionController`; spectating stays anonymous) that only establishes and
   carries the principal. The Steam flow is `auth.SteamAuthController` over the hand-rolled
   `SteamOpenId` (`HttpSteamOpenId` does the `check_authentication` re-post; the interface lets
   tests stub the network): `GET /api/auth/steam/login` → Steam, `GET /api/auth/steam/return`
   verifies + upserts the `app_user` + saves an authenticated context into the session,
   `GET /api/auth/me`, `POST /api/auth/logout`. `UserStore` follows the command-store pattern
   (`InMemoryUserStore` default, `JdbcUserStore` + `app_user` table when a datasource is set).
   `CurrentUserResolver` now reads the `SecurityContext` (dev header only as fallback). CORS moved
   to a credentialed `CorsConfigurationSource` (Security + MVC share it); the session cookie is
   `HttpOnly` + `SameSite=Lax`, with `domain`/`secure` env-driven for the `civstudio.com`
   cross-subdomain case (domain set with **no leading dot** — Tomcat rejects `.civstudio.com`). **UI:** the same-origin `live.html` diagnostic client and the main map
   site both sign in. On the main site the live client is `web/js/overlays/live.mjs` — it already
   spectated + controlled + sent tax commands over the picked server (`LIVE_BASE`), so its
   owner-gated `/control` and `/commands` fetches gained `credentials: "include"` (cross-origin,
   so the cookie would otherwise be dropped) and its live HUD gained a "Sign in through Steam"
   control that targets `LIVE_BASE/api/auth/steam/login` and returns to the site. Because the
   cookie rides cross-origin only when site and server are *same-site*, this works for the
   `civstudio.com` cloud servers (and localhost dev); signing into a `local`/custom server from
   the cloud site is genuinely cross-site and out of scope. Covered by `SteamAuthTest` (login
   redirect + full sign-in → ownership → logout). **Deferred within this phase:** persona/avatar
   enrichment via `GetPlayerSummaries` (the SteamID is the display name until an API key is
   configured) and requiring login to found (still anonymous-permissive).
3. **OIDC browser login** (`oauth2-client`, secondary providers e.g. Google). ✅ **Implemented
   (2026-07-11).** `.oauth2Login(...)` is added to the chain **only when a provider is configured**
   — `GoogleOAuthConfig` creates the `ClientRegistrationRepository` conditionally on
   `civstudio.auth.google.client-id`, so a Steam-only server adds nothing (all existing tests boot
   unchanged). A custom `oidcUserService` upserts the `app_user` (provider = the registration id)
   and wraps the principal as `CivStudioOidcUser` so its name is our surrogate id — every provider
   resolves to the same identity, and `CurrentUserResolver`/ownership need no special casing. The
   post-login return-to-the-site is carried by `OAuth2RedirectCaptureFilter` (stashes the validated
   `redirect` on `/oauth2/authorization/**`) + `OidcAuthenticationSuccessHandler`, the OIDC
   analogue of what the Steam controller does inline (both now share `AuthRedirects`).
   `server.forward-headers-strategy=framework` lets `{baseUrl}` resolve to the real
   `https://dev.civstudio.com` behind the ingress so Google's redirect URI matches. **UI:** the
   provider-specific live-HUD button was replaced by a **persistent header control**
   (`web/js/auth.mjs` → `#siteAuth` in `index.html`) that reads `GET /api/auth/providers`
   (new; `AuthController`, which also now owns `/me` + `/logout`) and renders a sign-in dropdown of
   exactly the offered providers, targeting the picked server (`LIVE_BASE`). Covered by
   `SteamAuthTest#providersListsSteamOnlyWhenNoOidcConfigured` and verified in a real browser
   (`tools/webverify/site-auth-shot.mjs`). **To enable Google in a deployment:** set
   `CIVSTUDIO_AUTH_GOOGLE_ENABLED=true` + `CIVSTUDIO_AUTH_GOOGLE_CLIENT_ID` /
   `CIVSTUDIO_AUTH_GOOGLE_CLIENT_SECRET` (+ register
   `https://dev.civstudio.com/login/oauth2/code/google` as an authorized redirect URI in the Google
   Cloud console). The `enabled` gate is a dash-free flag on purpose — `@ConditionalOnProperty`
   won't reliably match a kebab property (`client-id`) against the underscore env-var form.
4. **Bearer/JWT + Steam session-ticket** path — only once a native client exists.

Each phase is independently shippable and leaves the server spectator-usable throughout.

---

## Open questions

- **Account linking** (Google-user later signs in via Steam → one identity?). Deferred; the
  surrogate `app_user.id` keeps it cheap later.
- **Anonymous founding**: do we still allow logged-out visitors to found a *throwaway* session
  (owner = null, ephemeral), or is login required to found anything? Leaning "login required to
  found" once Phase 2 lands, with the caravan **demo** session kept as a public, unowned,
  server-seeded exception (it's founded by `DemoSessionSeeder`, not a user).
- **Per-user session quota** (how many concurrent owned sessions) — a resource-abuse guard once
  founding is authenticated.
- **Admin authority source**: config-listed subjects vs. an `is_admin` column. Start with a
  config allow-list.

---

*When Phase 1 lands, add a one-line pointer to this doc from `CLAUDE.md`'s subsystem map and a
§Auth section to `docs/architecture.md`.*
