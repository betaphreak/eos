<#
.SYNOPSIS
  Build, push, and deploy the CivStudio spectator server to Azure Container Apps.

.DESCRIPTION
  The streamlined local-Docker path (needs Rancher Desktop / Docker Desktop with dockerd +
  an authenticated `az` session). Replaces the throwaway-West-Europe-ACR build trick: push
  straight to civstudio.azurecr.io (belgiumcentral push/pull works fine — only ACR *Tasks*
  are absent there) and roll the Container App.

  Always redeploy after a change that affects what the live server serves (engine resources,
  the web-asset manifest / plotIndex, the bundle, or server code) — the static site and server
  drift apart otherwise and the plot layer silently breaks. See docs/client-server.md §Deployment.

  Steps: verify docker + az → az acr login → docker build (multi-stage, bakes the engine jar +
  build-info) → docker push → az containerapp update → poll /actuator until the new version serves.

.PARAMETER Tag
  Image tag. Defaults to the current git commit SHA (the deploy convention). A dirty tree gets
  a "-dirty" suffix so an uncommitted build is never mistaken for a commit.

.PARAMETER SkipBuild
  Skip build+push and just roll the Container App to an image tag already in the ACR.

.EXAMPLE
  pwsh tools/deploy-server.ps1
  pwsh tools/deploy-server.ps1 -Tag 90db0ac... -SkipBuild
#>
[CmdletBinding()]
param(
  [string]$Tag,
  [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'

$RG       = 'civstudio'
$APP      = 'civstudio-server'
$REGISTRY = 'civstudio'                       # az ACR name
$LOGIN    = 'civstudio.azurecr.io'            # registry login server
$REPO     = 'civstudio-server'
$HEALTH   = 'https://dev.civstudio.com/actuator/info'   # where we confirm the roll landed

$repoRoot = Split-Path -Parent $PSScriptRoot  # tools/ -> repo root
Push-Location $repoRoot
try {
  if (-not $Tag) {
    $sha   = (git rev-parse HEAD).Trim()
    $dirty = (git status --porcelain).Trim()
    $Tag   = if ($dirty) { "$sha-dirty" } else { $sha }
  }
  $image = "$LOGIN/$REPO`:$Tag"
  Write-Host "==> Deploying image: $image" -ForegroundColor Cyan

  # sanity: tools present
  docker info --format '{{.ServerVersion}}' | Out-Null
  az account show --query name -o tsv | Out-Null

  if (-not $SkipBuild) {
    Write-Host "==> az acr login ($REGISTRY)" -ForegroundColor Cyan
    az acr login -n $REGISTRY | Out-Null

    # build identity baked into /actuator/info (the .git is not in the image context, so pass it in)
    $buildNumber = (git rev-list --count HEAD).Trim()      # auto-incrementing monotonic build number
    $buildCommit = (git rev-parse --short HEAD).Trim()
    Write-Host "==> docker build (build #$buildNumber, commit $buildCommit)" -ForegroundColor Cyan
    docker build -t $image `
      --build-arg BUILD_NUMBER=$buildNumber `
      --build-arg BUILD_COMMIT=$buildCommit `
      -f Dockerfile .
    if ($LASTEXITCODE -ne 0) { throw "docker build failed" }

    Write-Host "==> docker push" -ForegroundColor Cyan
    docker push $image
    if ($LASTEXITCODE -ne 0) { throw "docker push failed" }
  }

  Write-Host "==> az containerapp update" -ForegroundColor Cyan
  $state = az containerapp update -n $APP -g $RG --image $image `
    --query "properties.provisioningState" -o tsv
  Write-Host "    provisioningState = $state"

  # confirm the new revision actually serves (build.version comes from the build-info goal)
  Write-Host "==> waiting for the new revision to serve $HEALTH" -ForegroundColor Cyan
  $ver = $null
  for ($i = 1; $i -le 20; $i++) {
    try {
      $ver = (Invoke-RestMethod -Uri $HEALTH -TimeoutSec 10).build.version
      if ($ver) { Write-Host "    live version: $ver" -ForegroundColor Green; break }
    } catch { }
    Start-Sleep -Seconds 10
  }
  if (-not $ver) { Write-Warning "server did not report a version after ~3.5 min — check the revision manually" }
  Write-Host "==> done." -ForegroundColor Cyan
}
finally {
  Pop-Location
}
