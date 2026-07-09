# The CivStudio spectator server (see docs/client-server.md §Deployment). Two stages:
# build the self-contained fat jar with Maven, then run it on a slim JRE with the
# runtime files the engine reads from disk. Browser thin-client; the JVM is authoritative.

# ---- build: produce the executable fat jar (Main-Class = com.civstudio.server.ServerMain) ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
# resolve dependencies against the POM first, so the layer caches unless the POM changes
COPY pom.xml .
RUN mvn -q -e -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- runtime: the jar + the on-disk resources the engine loads at runtime ----
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/civstudio-*.jar app.jar
# the province rasters ProvinceRaster reads from the filesystem (definition.csv +
# provinces/rivers/terrain/trees/heightmap BMPs — ~95 MB). The classpath JSON/name tables
# are already inside the jar; only these BMPs live on disk. See .dockerignore (data/civ4,
# used only by the web asset baker, is excluded).
COPY data/anbennar ./data/anbennar
# the thin-client demo page the server serves at "/"
COPY web/live.html ./web/live.html
EXPOSE 8080
# MaxRAMPercentage so the JVM honours the container's memory limit; full sim log to stdout
# for the platform's log capture. The server hosts the six-caravan demo (see ServerMain).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Deos.log.stdout=INFO", "-jar", "app.jar"]
