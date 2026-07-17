# Local dev server — one command, fully offline

**Status:** built (2026-07-14).

Run the whole stack locally — the Spring Boot server **and** the real `web/` map site, opened in
your browser — with a single command, and **with no internet connection**. This exists for local
debugging (including automated agent testing): iterate on the map against a live local server
without a manual `npx serve` step or any network round-trip.

## Run it

```powershell
pwsh tools/dev-local.ps1
```

That's it. It installs a fresh engine jar, starts the server offline, and — once the server is
**fully started** — serves `web/` and opens `http://localhost:3000/?live=http://localhost:8080` in
the URL logged for you to open (nothing opens itself — starting a server should not seize the screen).

Equivalently, the plain Maven command does the frontend part on its own (the engine-jar
refresh and the offline flag are what the script adds):

```powershell
mvn -o -pl civstudio-server spring-boot:run     # -o = offline
```

Useful flags:

```powershell
pwsh tools/dev-local.ps1 -WebPort 4000          # serve the site on :4000
pwsh tools/dev-local.ps1 -SkipEngineBuild       # engine unchanged since last install (faster)
pwsh tools/dev-local.ps1 -Online                # let Maven reach the network
```

Stop everything with `Ctrl-C` in the terminal — the node frontend is a child of the server JVM and
is torn down on shutdown.

## How it works

- **`web/dev-server.mjs`** — a zero-dependency Node static server for `web/` (no `npx serve`, so no
  network). Correct MIME types, `no-cache` for edit-reload, and HTTP **byte-range** support. Prints
  `DEV-SERVER-READY …` on its listen callback.
- **`DevFrontendLauncher`** (`server.dev`, `@Profile("dev")`) — on `ApplicationReadyEvent` (i.e.
  after the context is up **and** `DemoSessionSeeder` has founded the demo) it spawns
  `node web/dev-server.mjs`, waits for the ready line, then logs the site URL at
  `…/?live=http://localhost:<server port>`. The node process is destroyed on shutdown.
- **The `dev` profile** is activated for `spring-boot:run` only, via the `spring-boot-maven-plugin`
  `<profiles>` config in `civstudio-server/pom.xml` — so it never ships in the packaged production
  jar. Per-run overrides: `-Dcivstudio.dev.frontend.enabled=false`,
  `-Dcivstudio.dev.frontend.web-port=…`.

## Auto-restart on code change (DevTools)

`spring-boot-devtools` is a dev-scoped (`optional`) dependency of `civstudio-server`. While a
`spring-boot:run` server is running, DevTools watches this module's **compiled classes** and
hot-restarts the app context in place whenever they change — so recompiling the server module (an
IDE auto-build, or a plain `mvn -pl civstudio-server compile` from another terminal) bounces the
running server automatically, and `/mcp` + the SSE feed come right back on `:8080` with no manual
stop/start. You'll see the restart happen on the `restartedMain` thread in the log.

**Scope — server module only.** DevTools reloads only classes on its *restart* classloader (this
module's `target/classes`). The **engine** is a base-classloader jar, so **engine edits are not
picked up by a hot restart** — those still need `mvn -pl civstudio-engine install` plus a full
server restart (kill the `:8080` JVM first — `spring-boot:run` leaves a lingering listener).

`optional=true` keeps DevTools off downstream classpaths, and Boot excludes it from the repackaged
fat jar, so the production deploy is unaffected.

## Why offline works

- **Maven** runs with `-o`, so every dependency comes from `~/.m2` (nothing is fetched).
- **The engine** resolves its Anbennar/Civ4 mod sources from the local caches — the
  `.anbennar-cache` / `.civ4-cache` junctions to local clones (see `CLAUDE.md` §Local source
  caches). Founding the demo colony and generating its plot field therefore need no network.
- **The frontend** is a plain static folder served by the dependency-free node server; the browser
  fetches `window.BUNDLE` and `/api/*` from the local server, all same-machine.

The one prerequisite: the caches must be warm (the junctions present). A cold `~/.m2` or missing
junction needs a single online run first.

## Related

- [`client-server.md`](client-server.md) — the server this launches (SSE feed, `/api/bundle`, plot
  serving).
- [`web/README.md`](../web/README.md) — the site being served.
- [`plot-serving.md`](plot-serving.md) — per-province plot grids (`/api/plots/{id}`, the
  `.map/v<MAP_VERSION>` cache). Note the sim's own persisted fields are versioned the same
  way now — `map/provinces/v<MAP_VERSION>/` — so a generation bump keeps both in sync (see
  [`province-plots.md`](province-plots.md)).
