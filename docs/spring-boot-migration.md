# Design & plan: Spring Boot 4 server + Jackson 3

**Status:** Proposed (no code yet). **Date:** design 2026-07-10.

**Decision inputs (2026-07-10):**
- Target is a **real multiplayer backend** long-term (player accounts, auth, persisted
  command logs, eventually multi-node) — so the framework investment is justified, not a
  premature modernization. See project direction (playable game long-term).
- Framework is **Spring Boot 4 on Java 25**.
- **Upgrade all Jackson usage to Jackson 3** (`tools.jackson.*`) — one JSON library across
  the whole codebase, which also matches Boot 4's default JSON engine.
- The deployment box is **sized up** to absorb Spring's footprint.

**Depends on / supersedes:** builds directly on the Phase-A/B server in
[`docs/client-server.md`](client-server.md) — this document is the *how we host it* layer
under that design, and does not change the tick-authority / command-log / snapshot model.
That document stays the source of truth for the session semantics; this one owns the
framework, module layout, JSON library, build, and deployment.

---

## Scope and non-goals

Two "Java parts" are treated **oppositely**, and that split is the backbone of the whole
plan:

- **The engine (`com.civstudio.*` sim core) stays plain Java 25 — no Spring, ever.** Its
  value is determinism: per-instance state, salted RNG streams, "never consume from the
  economic RNG," byte-reproducible runs. Spring's DI / proxies / component-scanning /
  autoconfiguration buy it nothing and add reproducibility risk and startup cost.
  Component-scanning must never reach `com.civstudio.agent/market/bank/settlement`.
- **The server layer (`com.civstudio.server.*`) becomes a Spring Boot 4 application.** This
  is the edge that grows toward a real backend (accounts, persistence, many endpoints), and
  it is where Spring earns its keep.

**Non-goals:** no reactive rewrite (see "Spring MVC, not WebFlux"); no change to the tick
loop, snapshot projection, or command-log semantics; no engine behaviour change (the
migration must be run-for-run identical); no move to buildpacks for image builds (the deploy
constraints forbid it — see Deployment).

---

## The central move: split into two Maven modules

This is ~80% of the work and the thing that protects the engine. Convert the single-module
build into a reactor:

```
civstudio-parent (pom packaging)
├── civstudio-engine     ← everything under com.civstudio.* EXCEPT server
│                          plain Java 25, Lombok, Jackson 3, JUnit 5. Library jar.
│                          includes the dev-tool exporters (geo/name/tech .export.*),
│                          the scenarios (simulation.*), WorldMap/resources, SimLog.
│
└── civstudio-server     ← com.civstudio.server.*  (depends on civstudio-engine)
                           Spring Boot 4 app: controllers, SseEmitter feed, Actuator,
                           config properties. Produces the Boot fat jar the image runs.
```

Consequences to wire:

- **`WorldBundle`** (`com.civstudio.server.web`) stays in the **server** module — it is
  server-only and its golden test moves with it.
- **`exec:exec`** scenario running (`com.civstudio.simulation.*` mains) stays a
  **engine-module** concern — the exec-plugin config moves there, unchanged.
- **Dev-tool exporters** (`geo/export/*`, `name/export/*`, `tech/export/*`) and
  `web/build.mjs`'s Java feeders live in **engine** (they generate the committed `map/`
  resources; they never touch Spring).
- **Tests split by module.** Engine keeps the scenario smoke tests + `tech/geo/good`
  tests (pure JUnit, full colonies, `reuseForks=true`, assertions on). Server keeps
  `FeedServerTest`, `HostedSessionTest`, `SetTaxRateCommandTest`,
  `SimLogSessionRoutingTest`, `WorldBundleGoldenTest`.
- **Runtime disk resources** (`data/anbennar/` rasters, `web/live.html`) are read by the
  engine/server at runtime from the working directory — unchanged; the Dockerfile still
  copies them (see Deployment).

---

## Jackson 2 → 3 across the whole codebase

Jackson 3 relocates the core/databind packages to the **`tools.jackson.*`** namespace and
makes its exceptions unchecked; **`jackson-annotations` deliberately stays at
`com.fasterxml.jackson.annotation.*`** so annotated POJOs need no edits. Boot 4 ships
Jackson 3 as its default JSON engine, so aligning the engine to 3 gives **one mapper across
both modules**.

**Scope (from the current tree): 53 files import Jackson.** They fall into three buckets:

| Bucket | ~Count | Change |
|---|---|---|
| **Annotation-only** — `@JsonProperty`/`@JsonCreator`/`@JsonValue`/`@JsonSubTypes`/`@JsonTypeInfo`/`@JsonIgnoreProperties` (most `geo/*` records + enums, `tech/TechEffect`) | ~13 | **None.** `com.fasterxml.jackson.annotation.*` is unchanged in Jackson 3. |
| **`databind` / `core.type` importers** — `ObjectMapper`, `TypeReference`, `JsonNode`/`ObjectNode`/`ArrayNode`/`JsonNodeFactory`, `DeserializationFeature` (the exporters, `NameTable`, `TechTree`, `WorldMap`, `TerrainRegistry`, `ProvincePlotStore`, `WorldBundle`, …) | ~38 | **Import rewrite** `com.fasterxml.jackson.{databind,core}` → `tools.jackson.{databind,core}`. Mechanical. |
| **Config + checked-exception call sites** | a handful | **API change** (below). |

**Concrete API changes to handle (not just imports):**

1. **Immutable mapper / builder config.** Jackson 3's `ObjectMapper` is configured at
   build time; the instance `configure(...)`/`enable`/`disable` mutators are gone. Every
   site that does `new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_
   PROPERTIES, false)` — `NameTable`, `TechTree`, `TechEffects`, `WorldMap`,
   `TerrainRegistry`, `LiturgicalCalendar`, `ProvincePlotStore` — moves to
   `JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()`.
   (Jackson 3 also flips `FAIL_ON_UNKNOWN_PROPERTIES` to *off* by default, so some of these
   become redundant — keep them explicit for clarity rather than relying on the default.)
2. **Unchecked exceptions.** `com.fasterxml.jackson.core.JsonProcessingException` (checked)
   is replaced by `tools.jackson.core.JacksonException` (**unchecked**). `FeedServer.toJson`
   currently catches `JsonProcessingException`; that `catch` clause and any `throws`
   declarations on read/write helpers get simplified/removed.
3. **Dependency coordinates.** Engine `pom` swaps `com.fasterxml.jackson.core:jackson-
   databind:2.18.2` for the Jackson 3 databind artifact (`tools.jackson.core:jackson-
   databind:3.x`); annotations stay on `com.fasterxml.jackson.core:jackson-annotations` (now
   3.x). Server gets Jackson 3 transitively from the Boot starter — pin the same version via
   Boot's BOM so the two modules never diverge.

**The one real risk — the golden test.** `WorldBundleGoldenTest` pins `WorldBundle`
**byte-for-byte** against the committed gzipped `data.js`, and `index.html`'s boot path
depends on that bundle. Jackson 3 can change number/float formatting and defaults even
though `ObjectNode`/`ArrayNode` preserve insertion order. **Plan:** migrate `WorldBundle` +
its golden test together, run it first, and if the bytes drift, decide deliberately —
re-baseline the golden fixture (and confirm the web still renders headless) rather than
letting it drift silently. A single shared `JsonMapper` bean (below) keeps server responses
and `WorldBundle` on identical serialization settings.

---

## Spring Boot 4 server design

**Spring MVC, not WebFlux — with virtual threads.** The existing feed is one
blocking-thread-per-SSE-connection on virtual threads, with a **drop-oldest bounded queue**
so "a slow spectator never stalls the sim." That maps exactly onto Spring MVC +
`spring.threads.virtual.enabled=true` on Java 25, and keeps the code imperative. WebFlux
would force a reactive rewrite of the feed for no gain. **Decision: MVC + virtual threads.**

**Endpoint port (behaviour-parity with today's `FeedServer`):**

| Today (`FeedServer.dispatch` switch) | Spring |
|---|---|
| `GET /` → `web/live.html` | static resource or a small `@Controller` |
| `GET /api/bundle` | `@GetMapping` returning the gzipped `WorldBundle` bytes |
| `GET/POST /api/sessions` | `@GetMapping`/`@PostMapping` on a `SessionController` |
| `GET /api/sessions/{id}/stream` (SSE) | `@GetMapping(produces = TEXT_EVENT_STREAM)` → `SseEmitter` |
| `POST /api/sessions/{id}/control` | `@PostMapping`, typed `ControlRequest` body |
| `POST /api/sessions/{id}/commands` | `@PostMapping`, typed `CommandRequest` body |

- **`SseEmitter` keeps the drop-oldest queue.** Spring's `SseEmitter` gives lifecycle +
  wiring but **not** backpressure — retain the per-connection `ArrayBlockingQueue` + the
  session-thread `offer`/drop-oldest logic verbatim inside the emitter's producer.
- **`SessionHost` becomes a `@Component` singleton bean**; `HostedSession` lifecycle is
  managed by the host, and shutdown moves from the manual `addShutdownHook`/`CountDownLatch`
  park in `ServerMain` to Spring's graceful-shutdown + `@PreDestroy host.stopAll()`.
  `ServerMain` becomes a thin `@SpringBootApplication` that seeds the demo session on
  `ApplicationReadyEvent`.
- **Declarative CORS** replaces `applyCors`/`allowOriginFor`/`preflight`: a `CorsConfig`
  bean (or `@CrossOrigin`) reads the same allow-list; `EOS_CORS_ORIGINS` becomes a config
  property.
- **`@ConfigurationProperties("civstudio")`** + `application.yml` with `dev`/`prod`
  profiles replaces the ad-hoc `PORT` / `EOS_CORS_ORIGINS` / seed / province / tick-rate
  env parsing in `ServerMain`. `$PORT` (the container convention) maps to `server.port`.
- **One shared `tools.jackson.databind.JsonMapper` bean** registered for both Spring's HTTP
  message conversion and `WorldBundle`, so nothing serializes two different ways.
- **Actuator on from day one:** `/actuator/health/{liveness,readiness}` become the Container
  App probes (there are none today), and Micrometer exposes session count, per-session tick,
  dropped-frame count, and heap — the observability an always-on stateful JVM needs.

**What lands later, on Spring rails (aligned to the multiplayer roadmap):**

- **Spring Security** → player accounts, OIDC/guest identities, session tokens (the Phase-B
  authority story: a command is validated against session state *and the actor* before it
  enters the log).
- **Spring Data (Postgres on Azure)** → the durable command log. "The log *is* the save
  file" (state = *f*(spec, command-log)) is a natural append-only table; this is Phase C
  persistence + resume.
- **Sticky routing stays ours.** Spring does **not** solve "a session lives in one
  replica's memory" — multi-node still needs session-affinity routing by session id
  (Container Apps affinity or a gateway). Flagged so it isn't assumed into Spring's column.

---

## Build & packaging

- **Parent reactor pom** with `maven.compiler.release=25`, Lombok annotation-processor path,
  and the Boot BOM imported for the server module.
- **Engine module:** plain `jar`, keeps the exec plugin (scenarios) and Surefire config
  (`reuseForks=true`, assertions on). No Boot.
- **Server module:** `spring-boot-maven-plugin` `repackage` **replaces `maven-shade`** for
  the executable fat jar (`Main-Class` = the `@SpringBootApplication`). Shade is removed.
- Boot's layered jar is compatible with the existing Dockerfile copy approach; **do not**
  switch to `bootBuildImage`/buildpacks (needs a Docker daemon the dev box lacks — see
  Deployment).

---

## Deployment (sized up)

The two-deployable shape is unchanged (static site on Static Web Apps; server on Azure
Container Apps, `min-replicas = 1` so sessions survive). What changes is the **box size**:
Spring Boot adds ~50–100 MB heap + slower cold start on top of each session's WorldMap +
rasters, so the current **1 vCPU / 2 GiB** is bumped.

| | Before | After |
|---|---|---|
| Container App resources | 1 vCPU / 2 GiB | **2 vCPU / 4 GiB** (starting point) |
| Replicas | min/max 1 | min/max 1 (unchanged; sessions are in-memory) |

`az containerapp update -n civstudio-server -g civstudio --cpu 2 --memory 4Gi --image …`.
Consumption profile caps at **4 vCPU / 8 GiB per replica** — enough headroom for several
concurrent sessions; go to a Dedicated workload profile only if a single replica must hold
many sessions. All the identity/ACR-region constraints from
[`docs/client-server.md`](client-server.md) §Deployment still hold (guest identity can't
create role assignments → admin-cred ACR pull, manual `az` deploy; ACR Tasks absent in
belgiumcentral → throwaway-westeurope-ACR build-and-import, or Docker-on-runner + push). The
Boot repackage jar drops into that pipeline unchanged.

---

## Suggested sequencing (each step independently verifiable)

1. **Module split, no Spring, no Jackson change.** Carve engine vs server, keep the JDK
   `HttpServer`. Prove `mvn test` (both modules) + the live demo are byte-identical to
   today. De-risks everything else.
2. **Jackson 2 → 3 across the engine + server (still no Spring).** Do `WorldBundle` + its
   golden test first and settle any byte-drift, then the rest. Full suite green.
3. **Boot-ify the server module.** Spring Boot 4, MVC + virtual threads, port endpoints to
   controllers, port the SSE feed into `SseEmitter` **keeping the drop-oldest queue**,
   `SessionHost` as a bean, Actuator on. Behaviour-parity with today; `repackage` replaces
   shade.
4. **Config + declarative CORS**, retire the env-var parsing; size up the Container App.
5. **Multiplayer features land on Spring** as Phase B/C arrives: Security (accounts) then
   Data (durable command log).

Steps 1–4 are pure infrastructure with a run-for-run-identical engine; only step 5 adds
gameplay surface.

---

## Risks & open questions

- **Golden-test byte drift** under Jackson 3 (see JSON section) — the one thing that can
  force a visible change; handle it explicitly in step 2.
- **Boot 4 baseline is Java 17; we run 25.** Boot 4 runs fine on 25, but confirm Lombok
  1.18.42's annotation processor is happy under the Boot build for the server module (the
  engine already answers this — it compiles on 25 today).
- **Startup time** on Container Apps readiness probes — set the readiness probe's
  initial-delay to accommodate Boot cold start + WorldMap load, so the replica isn't killed
  during warm-up.
- **Two Jacksons transitively?** Ensure nothing drags in Jackson 2 alongside 3 (verify the
  dependency tree); pin everything through the Boot BOM + engine's explicit 3.x coords.
- **Test-suite shape** in the server module — **decided: `@SpringBootTest` (full context)**,
  not the `@WebMvcTest` slice. The current tests (`FeedServerTest`, `HostedSessionTest`,
  `SetTaxRateCommandTest`, `WorldBundleGoldenTest`) exercise the real transport against a live
  `SessionHost` + running `HostedSession` — a sliced web layer with a mocked host wouldn't
  cover the SSE feed or the tick-exact command application these tests exist to guard. Run
  `@SpringBootTest(webEnvironment = RANDOM_PORT)` with a `WebTestClient`/HTTP client against the
  booted context (mirroring how `FeedServer` is exercised on an ephemeral port today), and lean
  on Boot's per-class context caching so the suite doesn't pay a fresh context per test.
