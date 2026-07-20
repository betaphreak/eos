#requires -Version 7
<#
.SYNOPSIS
    Launch the full CivStudio stack locally, offline — the Spring Boot server plus the web/ site
    (served by a zero-dependency node server), whose URL is logged for you to open.

.DESCRIPTION
    Runs entirely with no internet connection:
      * Maven runs offline (-o), so all dependencies come from ~/.m2.
      * The engine resolves its Anbennar/Civ4 mod sources from the local caches
        (the .anbennar-cache / .civ4-cache junctions), so founding the demo needs no network.
      * World data comes from the committed world-bundle FIXTURE by default. This is required, not
        optional: `generated/` is no longer committed (studio is the content authority), so the
        default `classpath` world source has no /terrains.json and the server dies on boot with
        "Terrain resource not found". Use -WorldSource strapi to run against a live CMS instead.

    `mvn spring-boot:run` activates the `dev` Spring profile (wired in civstudio-server/pom.xml),
    so DevFrontendLauncher starts web/dev-server.mjs and opens http://localhost:<WebPort>/?live=...
    once the server is fully started. This script just adds the two things the plugin can't:
    the offline flag, and a fresh engine jar (spring-boot:run reads the engine from ~/.m2, which is
    stale until the engine module is re-installed — see the server-run-am-fresh-engine note).

.EXAMPLE
    pwsh tools/dev-local.ps1
    pwsh tools/dev-local.ps1 -WebPort 4000
    pwsh tools/dev-local.ps1 -SkipEngineBuild        # engine unchanged since last install
    pwsh tools/dev-local.ps1 -Online                 # allow Maven to reach the network
    # open a webverify-style deep link (province 4411 @ zoom 150), same URL shape tools/webverify builds:
    pwsh tools/dev-local.ps1 -OpenPath '/?p=4411&z=150&live={live}#none'
#>
[CmdletBinding()]
param(
    [int]    $WebPort         = 3000,
    [int]    $ServerPort      = 8080,
    # Path + query appended to http://localhost:<WebPort> in the logged URL. Placeholders:
    # {live} -> http://localhost:<ServerPort>, {server} -> port, {webPort} -> port. Blank = default.
    [string] $OpenPath        = '',
    # Where invariant world data comes from (civstudio.world-source.mode). 'fixture' is the default
    # because it is the only source that works offline with no committed generated/ resources.
    [ValidateSet('fixture', 'strapi', 'classpath')]
    [string] $WorldSource     = 'fixture',
    # fixture mode: the world-bundle snapshot to boot from. Defaults to the committed test fixture —
    # the same one `mvn test` uses, and the only committed copy in the repo.
    [string] $WorldBundle     = 'civstudio-engine/src/test/resources/world-bundle.json.gz',
    # strapi mode: the CMS bundle endpoint + its shared secret (WORLD_BUNDLE_TOKEN).
    [string] $WorldSourceUrl  = 'http://localhost:1337/api/world-bundle',
    [string] $WorldSourceToken = '',
    [switch] $SkipEngineBuild,
    [switch] $Online
)

$ErrorActionPreference = 'Stop'
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $repoRoot

$offline = $Online ? @() : @('-o')

# Local dev expects the two node toolchains present (installed, offline-cached): sharp powers the
# web asset bake (web/build.mjs) and playwright-core drives the headless-browser checks
# (tools/webverify/*.mjs). Warn (don't fail) if either is missing so `node web/build.mjs` /
# `node tools/webverify/*.mjs` are ready to run against this stack.
foreach ($dep in @(
        @{ Name = 'sharp';          Path = 'web/node_modules/sharp';                       Install = 'npm --prefix web install' },
        @{ Name = 'playwright-core'; Path = 'tools/webverify/node_modules/playwright-core'; Install = 'npm --prefix tools/webverify install' })) {
    if (-not (Test-Path (Join-Path $repoRoot $dep.Path))) {
        Write-Warning "$($dep.Name) not installed ($($dep.Path)). Web build / webverify need it — run: $($dep.Install)"
    }
}

if (-not $SkipEngineBuild) {
    # `spring-boot:run -pl civstudio-server` resolves BOTH the parent pom and the engine jar from
    # ~/.m2, so install both. -N installs just the parent (aggregator) pom; then the engine jar so
    # spring-boot:run picks up fresh engine classes (see the server-run-am-fresh-engine note).
    Write-Host '==> Installing the parent pom + engine jar into ~/.m2 (spring-boot:run resolves them from there)...' -ForegroundColor Cyan
    & mvn @offline -N install -q
    if ($LASTEXITCODE -ne 0) { throw "parent pom install failed ($LASTEXITCODE)" }
    & mvn @offline -pl civstudio-engine install -DskipTests -q
    if ($LASTEXITCODE -ne 0) { throw "engine install failed ($LASTEXITCODE)" }
}

Write-Host "==> Starting the server (offline=$(-not $Online)); the browser opens at http://localhost:$WebPort once ready..." -ForegroundColor Cyan
# App config must reach the FORKED app JVM, not the Maven JVM — a plain `-Dkey=value` on the mvn
# command line binds only to Maven. spring-boot:run forwards `spring-boot.run.arguments` to the app
# as `--key=value` argv, which Spring binds into the Environment at the highest precedence.
$appArgs = @(
    "--server.port=$ServerPort",
    "--civstudio.dev.frontend.web-port=$WebPort",
    "--civstudio.world-source.mode=$WorldSource"
)
if ($OpenPath)  { $appArgs += "--civstudio.dev.frontend.open-path=$OpenPath" }

# The world source is installed by WorldSourceInitializer before any bean loads, so a bad value here
# is a boot failure, not a degraded run — fail early with the fix rather than a Spring stack trace.
switch ($WorldSource) {
    'fixture' {
        $bundlePath = Join-Path $repoRoot $WorldBundle
        if (-not (Test-Path $bundlePath)) {
            throw "World bundle fixture not found: $bundlePath`n" +
                  "Regenerate it with: node tools/make-world-bundle.mjs"
        }
        # forward slashes: the value rides a space-joined argument string into the forked JVM
        $appArgs += "--civstudio.world-source.fixture=$($bundlePath -replace '\\', '/')"
    }
    'strapi' {
        $appArgs += "--civstudio.world-source.url=$WorldSourceUrl"
        if ($WorldSourceToken) { $appArgs += "--civstudio.world-source.token=$WorldSourceToken" }
        Write-Host "==> World data from Strapi at $WorldSourceUrl (it must be running)" -ForegroundColor Cyan
    }
    'classpath' {
        Write-Warning ("classpath world source: generated/ is no longer committed, so this boots " +
            "only if you have restored those resources yourself. Expect 'Terrain resource not found'.")
    }
}
$runArgs = @(
    '-pl', 'civstudio-server', 'spring-boot:run',
    "-Dspring-boot.run.arguments=$($appArgs -join ' ')"
)

& mvn @offline @runArgs
exit $LASTEXITCODE
