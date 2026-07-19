<#
.SYNOPSIS
  Build, push, and deploy the CivStudio Studio (Strapi 5 CMS, studio/) to Azure Container Apps.

.DESCRIPTION
  The local-Docker path (needs Rancher Desktop / Docker Desktop with dockerd + an authenticated
  `az` session) — the sibling of tools/deploy-server.ps1. Pushes the image to GitHub Container
  Registry (ghcr.io/betaphreak/civstudio-backend) and rolls the Container App
  `civstudio-backend-app`, which serves the Strapi admin + API at https://civstudio.com. The GHCR
  package is PUBLIC, so the Container App pulls it with no registry credentials (one-time: set the
  package to Public in the repo's Packages settings after the first push).

  Why local (not fully-automated CI): this repo's Azure subscription is reached by a *guest*
  identity that cannot create role assignments, so no CI service principal exists — a plain
  `az containerapp update --image` rolls the app from an authenticated az session.
  .github/workflows/strapi-deploy.yml is a build-only backup for when this box is off; it cannot
  roll the app.

  Steps: verify docker + az -> docker login ghcr.io -> docker build (studio/ context, multi-stage:
  npm ci + strapi build) -> docker push -> az containerapp update -> poll until the app's ACTIVE
  revision runs THIS image tag and /_health answers 204 -> prune old local images (only on a
  verified-live deploy; the registry keeps them for rollback).

  Auth for the push: `$env:GHCR_TOKEN` (a PAT with write:packages) if set, else the gh CLI token
  (`gh auth token`; needs write:packages — `gh auth refresh -h github.com -s write:packages`).

.PARAMETER Tag
  Image tag. Defaults to the current git commit SHA (the deploy convention). A dirty tree gets a
  "-dirty" suffix so an uncommitted build is never mistaken for a commit.

.PARAMETER SkipBuild
  Skip build+push and just roll the Container App to an image tag already in GHCR.

.PARAMETER ForceBuild
  Force a local build+push even when the tag already exists on GHCR (the default auto-skips it).

.EXAMPLE
  pwsh tools/deploy-studio.ps1
  pwsh tools/deploy-studio.ps1 -Tag 90db0ac... -SkipBuild
#>
[CmdletBinding()]
param(
  [string]$Tag,
  [switch]$SkipBuild,
  [switch]$ForceBuild
)
$ErrorActionPreference = 'Stop'

$RG       = 'civstudio'
$APP      = 'civstudio-backend-app'
$LOGIN    = 'ghcr.io'                          # registry login server
$OWNER    = 'betaphreak'                       # ghcr namespace (GitHub owner, lowercase)
$REPO     = 'civstudio-backend'               # image repo (matches the app's current image)
$HEALTH   = 'https://civstudio.com/_health'   # Strapi health endpoint (204 when serving)

# Does OWNER/REPO:TAG already exist on GHCR? `docker manifest inspect` queries the registry directly
# using Docker's stored credential (incl. helpers like wincred) and — verified — needs NO running
# daemon (works with a dead DOCKER_HOST), so a clean-HEAD deploy can roll without starting Docker.
# Exit 0 = present; any failure (absent / docker CLI missing / no cred) → $false, so we fall back to
# building. It uses Docker's own auth, so it needs no packages scope on the gh/GHCR token.
function Test-GhcrTagExists {
  param([string]$Owner, [string]$Repo, [string]$ImageTag)
  docker manifest inspect "$LOGIN/$Owner/$Repo`:$ImageTag" 2>$null | Out-Null
  return ($LASTEXITCODE -eq 0)
}

$repoRoot = Split-Path -Parent $PSScriptRoot  # tools/ -> repo root
Push-Location $repoRoot
try {
  if (-not $Tag) {
    # string-coerce: a clean tree makes `git status --porcelain` emit nothing -> $null, and .Trim()
    # on $null throws. "$(...)" flattens null/multi-line output to a single string first.
    $sha   = "$(git rev-parse HEAD)".Trim()
    $dirty = "$(git status --porcelain)".Trim()
    $Tag   = if ($dirty) { "$sha-dirty" } else { $sha }
  }
  $image = "$LOGIN/$OWNER/$REPO`:$Tag"
  Write-Host "==> Deploying image: $image" -ForegroundColor Cyan

  if ($SkipBuild -and $ForceBuild) { throw "Pass at most one of -SkipBuild / -ForceBuild." }

  # AUTO-SKIP THE LOCAL BUILD when the tag is already on GHCR — CI (strapi-deploy.yml) builds+pushes
  # every committed SHA in ~3 min, so a clean-HEAD deploy is normally just a roll (no Docker needed).
  # A dirty tree tags "<sha>-dirty" (never on GHCR) so it always builds. -ForceBuild overrides.
  if (-not $SkipBuild -and -not $ForceBuild) {
    Write-Host "==> checking GHCR for $Tag" -ForegroundColor Cyan
    if (Test-GhcrTagExists $OWNER $REPO $Tag) {
      Write-Host "    already on GHCR (CI built it) — roll only; skipping local build. Use -ForceBuild to rebuild." -ForegroundColor Green
      $SkipBuild = $true
    } else {
      Write-Host "    not on GHCR — will build locally." -ForegroundColor Yellow
    }
  }

  # sanity: the roll always needs an authed az session; Docker only matters when we actually build.
  az account show --query name -o tsv | Out-Null

  if (-not $SkipBuild) {
    docker info --format '{{.ServerVersion}}' | Out-Null
    Write-Host "==> docker login ghcr.io" -ForegroundColor Cyan
    # a PAT with write:packages (env GHCR_TOKEN) wins; else fall back to the gh CLI token, which
    # needs that scope too (`gh auth refresh -h github.com -s write:packages`).
    $ghcrToken = if ($env:GHCR_TOKEN) { $env:GHCR_TOKEN } else { (gh auth token 2>$null) }
    if (-not $ghcrToken) { throw "No GHCR token: set `$env:GHCR_TOKEN (PAT w/ write:packages) or sign in with `gh auth login`." }
    $ghcrToken | docker login $LOGIN --username $OWNER --password-stdin | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker login $LOGIN failed (token needs write:packages: gh auth refresh -h github.com -s write:packages)" }

    # Build context is studio/ so the dockerfile's COPY paths and studio/.dockerignore apply.
    Write-Host "==> docker build (studio/)" -ForegroundColor Cyan
    docker build -t $image -f studio/dockerfile studio
    if ($LASTEXITCODE -ne 0) { throw "docker build failed" }

    Write-Host "==> docker push" -ForegroundColor Cyan
    docker push $image
    if ($LASTEXITCODE -ne 0) { throw "docker push failed" }
  }

  Write-Host "==> az containerapp update" -ForegroundColor Cyan
  $state = az containerapp update -n $APP -g $RG --image $image `
    --query "properties.provisioningState" -o tsv
  Write-Host "    provisioningState = $state"

  # POST-ROLL VERIFY -- provisioningState is NOT enough (it reports Succeeded while the OLD revision
  # may still serve). The app is in Single-revision mode, so a successful roll makes exactly one
  # ACTIVE revision that (a) runs THIS image tag, (b) is Running, and (c) holds 100% traffic. Strapi
  # exposes no build-info endpoint, so the image tag (unique per commit) IS the "this build is live"
  # proof. Also require /_health to answer 204 so we know ingress actually serves the new revision.
  Write-Host "==> verifying the new revision is live (image $Tag, then $HEALTH)" -ForegroundColor Cyan
  $ok = $false
  for ($i = 1; $i -le 30; $i++) {
    try {
      $active = az containerapp revision list -n $APP -g $RG `
        --query "[?properties.active] | [0].{image:properties.template.containers[0].image, running:properties.runningState, traffic:properties.trafficWeight}" `
        -o json 2>$null | ConvertFrom-Json
      $imgOk = $active -and $active.image -eq $image -and $active.traffic -eq 100 -and
               ($active.running -eq 'Running' -or $active.running -eq 'RunningAtMaxScale')
      $healthCode = $null
      if ($imgOk) {
        try {
          $resp = Invoke-WebRequest -Uri $HEALTH -Method Head -TimeoutSec 10 -SkipHttpErrorCheck
          $healthCode = [int]$resp.StatusCode
        } catch { }
      }
      if ($imgOk -and ($healthCode -eq 204 -or $healthCode -eq 200)) {
        Write-Host "    live: active revision runs $Tag, running=$($active.running), /_health=$healthCode" -ForegroundColor Green
        $ok = $true; break
      }
      $seen = if ($active) { "image=$($active.image) running=$($active.running) traffic=$($active.traffic) health=$healthCode" } else { "no active revision yet" }
      Write-Host "    ...$seen ($i/30)"
    } catch { Write-Host "    ...not ready yet ($i/30)" }
    Start-Sleep -Seconds 10
  }
  if (-not $ok) {
    throw ("POST-ROLL VERIFY FAILED: the active revision is not serving this build ($Tag) after ~5 min — " +
      "the new revision did not take over (stale image, unhealthy revision, or traffic not shifted). " +
      "Inspect: az containerapp revision list -n $APP -g $RG")
  }

  # HOUSEKEEPING -- only now that the new build is verified live: drop old LOCAL images. The registry
  # keeps every tag, so rollback (`-SkipBuild -Tag <old>`) still pulls from GHCR; locally we only need
  # the one just deployed. Best-effort: the deploy already succeeded, so a cleanup hiccup must never
  # fail it.
  # Only when we built locally — a roll-only deploy has no local images (and no Docker daemon needed).
  if (-not $SkipBuild) {
    Write-Host "==> cleaning up old local images (keeping $Tag)" -ForegroundColor Cyan
    $prevEAP = $ErrorActionPreference
    try {
      $ErrorActionPreference = 'Continue'
      $old = @(docker images "$LOGIN/$OWNER/$REPO" --format '{{.Tag}}' | Where-Object { $_ -and $_ -ne $Tag })
      foreach ($t in $old) { docker rmi "$LOGIN/$OWNER/$REPO`:$t" 2>$null | Out-Null }
      docker image prune -f 2>$null | Out-Null
      Write-Host "    removed $($old.Count) old local tag(s) + dangling images; kept $Tag" -ForegroundColor Green
    } catch {
      Write-Warning "image cleanup skipped (deploy already succeeded): $_"
    } finally {
      $ErrorActionPreference = $prevEAP
    }
  }

  Write-Host "==> done." -ForegroundColor Cyan
}
finally {
  Pop-Location
}
