# The CivStudio spectator server (see docs/client-server.md §Deployment). Two stages:
# build the self-contained fat jar with Maven, then run it on a slim JRE with the
# runtime files the engine reads from disk. Browser thin-client; the JVM is authoritative.

# ---- build: produce the executable fat jar (Main-Class = com.civstudio.server.ServerMain) ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
# resolve dependencies against the POMs first, so the layer caches unless a POM changes.
# Multi-module reactor: the parent + both module POMs, then go-offline (excluding the
# reactor-sibling civstudio-engine, which isn't in a repo yet — the reactor supplies it).
COPY pom.xml .
COPY .mvn ./.mvn
COPY civstudio-engine/pom.xml ./civstudio-engine/
COPY civstudio-server/pom.xml ./civstudio-server/
RUN mvn -q -e -B dependency:go-offline -DexcludeArtifactIds=civstudio-engine
COPY civstudio-engine/src ./civstudio-engine/src
COPY civstudio-server/src ./civstudio-server/src
# build identity for /actuator/info. The image context has no .git (it's .dockerignored — 204 MB),
# so the git-commit-id plugin can't derive these here; the deploy supplies them as build-args (the
# host's `git rev-list --count HEAD` + short SHA) and we pass them as -D, overriding the plugin's
# (absent) values so build-info bakes the real number/commit. See tools/deploy-server.ps1.
ARG BUILD_NUMBER=0
ARG BUILD_COMMIT=docker
RUN mvn -q -B -DskipTests -Dgit.total.commit.count=${BUILD_NUMBER} -Dgit.commit.id.abbrev=${BUILD_COMMIT} package

# ---- runtime: the jar + the on-disk resources the engine loads at runtime ----
# Alpine (musl) JRE — ~200 MB smaller than the Ubuntu jre base. The build stage above stays on the
# glibc Maven image; a pure-Java jar runs the same on musl. The engine decodes the province BMPs via
# java.desktop's ImageIO (pure-Java BMP reader, no text rendering), which works headless on Alpine.
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /build/civstudio-server/target/civstudio-server-*.jar app.jar
# The Anbennar map rasters are NOT baked in: ProvinceRaster fetches them on demand from GitLab
# (pinned by map/anbennar-source.lock) into the cache dir, the first time a province's plot field
# is generated. Point ANBENNAR_CACHE_DIR at a mounted volume so the download survives restarts, and
# set ANBENNAR_TOKEN (a secret) for the authenticated rate limit. See docs/anbennar-files.md.
# The lobby page (chat + server status) the server serves at "/"
COPY web/lobby.html ./web/lobby.html
EXPOSE 8080
# MaxRAMPercentage so the JVM honours the container's memory limit; full sim log to stdout
# for the platform's log capture. The server hosts the six-caravan demo (see ServerMain).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Deos.log.stdout=INFO", "-jar", "app.jar"]
