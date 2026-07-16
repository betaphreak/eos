<#
.SYNOPSIS
  Increment the plot-cache MAP_VERSION, push the locally-baked (named) plot cache to the Azure
  Files share the live server reads, and prune stale cache versions.

.DESCRIPTION
  Production can't bake plot names — there is no GeoNames dump on the server — so the names
  produced by a local `WorldPlotGenerator` run must be shipped to the persistent AzureFile
  share (`anbennar`, mounted at PLOT_CACHE_DIR=/mnt/anbennar/plot-cache). The cache is
  versioned (`plot-cache/v<MAP_VERSION>/<id>.json.gz`), so rolling names into production is:

    1. Read MAP_VERSION from ProvincePlotStore.java; compute the next value (or -NewVersion).
    2. Bump the constant in ProvincePlotStore.java (busts the client ?v= via the server bundle).
    3. Move the local baked cache .plot-cache/v<old> -> .plot-cache/v<new> (generation is
       unchanged — only names were added — so the same fields serve as the new version).
    4. Upload .plot-cache/v<new> to  <share>/plot-cache/v<new>  on the storage account.
    5. Prune: keep only v<new> and v<new-1> (see -KeepPrevious); delete older version dirs both
       locally and on the share.

  It does NOT deploy the server or the web site. Afterwards (see docs/client-server.md
  §Deployment): commit the MAP_VERSION bump, run `pwsh tools/deploy-server.ps1` (new image
  serves v<new> + the StoredPlot `name` field), and SWA-deploy `web/` (the hover JS). The
  uploaded v<new> cache waits on the share until the v<new> server reads it.

  Storage account + share are discovered from the Container App's AzureFile volume when not
  passed. Auth uses -AccountKey if given, else the fetched account key, else `--auth-mode login`.

.PARAMETER NewVersion       Target MAP_VERSION. Default: current + 1.
.PARAMETER StorageAccount   Storage account behind the share. Discovered from the app if omitted.
.PARAMETER Share            Azure Files share name. Discovered, then defaults to 'anbennar'.
.PARAMETER AccountKey       Storage key for the upload/prune. Else fetched, else --auth-mode login.
.PARAMETER KeepPrevious     Older versions to retain besides v<new> (default 1 → keep v<new>+v<new-1>).
.PARAMETER SkipUpload       Do the local bump + move + local prune only; skip all Azure calls.
.PARAMETER NoPrune          Keep every old version (skip the prune step).

.EXAMPLE
  pwsh tools/deploy-plot-cache.ps1 -WhatIf       # dry run: show every step, change nothing
  pwsh tools/deploy-plot-cache.ps1               # bump + move + upload + prune (prompts on Azure writes)
  pwsh tools/deploy-plot-cache.ps1 -StorageAccount civstudiofiles -AccountKey $k
#>
[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
  [int]$NewVersion,
  [string]$StorageAccount,
  [string]$Share,
  [string]$AccountKey,
  [int]$KeepPrevious = 1,
  [switch]$SkipUpload,
  [switch]$NoPrune
)
$ErrorActionPreference = 'Stop'

$RG           = 'civstudio'
$APP          = 'civstudio-server'
$CACHE_SUBDIR = 'plot-cache'        # dir within the share; PLOT_CACHE_DIR = /mnt/anbennar/plot-cache
$MIN_EXPECTED = 4000                # a full world is ~4.6k named land provinces; warn if far fewer

$repoRoot  = Split-Path -Parent $PSScriptRoot
$storeFile = Join-Path $repoRoot 'civstudio-engine/src/main/java/com/civstudio/settlement/ProvincePlotStore.java'
$cacheRoot = Join-Path $repoRoot '.plot-cache'

function Write-NextSteps([int]$v) {
  Write-Host ""
  Write-Host "==> NEXT STEPS (this script only bumped MAP_VERSION + pushed/pruned the cache):" -ForegroundColor Cyan
  Write-Host "    1. Update the MAP_VERSION inline comment, then commit ProvincePlotStore.java." -ForegroundColor Gray
  Write-Host "    2. pwsh tools/deploy-server.ps1   # new image serves v$v + the StoredPlot 'name' field" -ForegroundColor Gray
  Write-Host "    3. SWA-deploy web/ (the hover JS): npx @azure/static-web-apps-cli deploy ./web --env production" -ForegroundColor Gray
  Write-Host "    4. Verify: hover a plot at deep zoom on https://anbennar.civstudio.com (the world-map site; dev.civstudio.com is the server it fetches from) — names should show." -ForegroundColor Gray
}

# oldest version to keep; anything strictly older is pruned
function Get-OldestKept([int]$new) { return $new - [Math]::Max(0, $KeepPrevious) }

# --- 1. read current MAP_VERSION --------------------------------------------------------------
if (-not (Test-Path $storeFile)) { throw "ProvincePlotStore.java not found at $storeFile" }
$srcText = Get-Content -Raw $storeFile
if ($srcText -notmatch 'MAP_VERSION\s*=\s*(\d+)\s*;') { throw "MAP_VERSION constant not found in ProvincePlotStore.java" }
$current = [int]$Matches[1]
if (-not $NewVersion) { $NewVersion = $current + 1 }
if ($NewVersion -le $current) { throw "NewVersion ($NewVersion) must be greater than the current MAP_VERSION ($current)" }

$srcDir = Join-Path $cacheRoot "v$current"
$dstDir = Join-Path $cacheRoot "v$NewVersion"
$oldestKept = Get-OldestKept $NewVersion

Write-Host "==> MAP_VERSION $current -> $NewVersion  (keep v$NewVersion..v$oldestKept, prune older)" -ForegroundColor Cyan
Write-Host "    local baked cache: $srcDir  ->  $dstDir" -ForegroundColor DarkGray

# --- 2. sanity-check the local baked cache ----------------------------------------------------
if (Test-Path $dstDir) { throw "Target cache dir already exists: $dstDir (a v$NewVersion cache is already here — resolve by hand)" }
if (-not (Test-Path $srcDir)) { throw "No local baked cache at $srcDir. Bake first: mvn -pl civstudio-engine compile exec:exec -Dsim.main=com.civstudio.geo.export.WorldPlotGenerator" }
$count = @(Get-ChildItem -Path $srcDir -Filter '*.json.gz' -File -ErrorAction SilentlyContinue).Count
Write-Host "    baked provinces in v$current : $count" -ForegroundColor DarkGray
if ($count -lt $MIN_EXPECTED) {
  Write-Warning "Only $count province files (< $MIN_EXPECTED expected for a full world). The bake may be incomplete."
  if (-not $PSCmdlet.ShouldContinue("Continue with a possibly-incomplete cache of $count provinces?", "Incomplete cache")) {
    throw "Aborted by user — re-run the full bake first."
  }
}

# --- 3. bump the MAP_VERSION constant ---------------------------------------------------------
if ($PSCmdlet.ShouldProcess($storeFile, "Bump MAP_VERSION $current -> $NewVersion")) {
  $bumped = ([regex]'(MAP_VERSION\s*=\s*)\d+(\s*;)').Replace($srcText, "`${1}$NewVersion`${2}", 1)
  [System.IO.File]::WriteAllText($storeFile, $bumped)   # UTF-8, no BOM
  Write-Host "    bumped MAP_VERSION in ProvincePlotStore.java" -ForegroundColor Green
  Write-Warning "The inline comment on the MAP_VERSION line still describes v$current — update it (v${NewVersion}: plot place names) before committing."
}

# --- 4. move the local baked cache to the new version dir -------------------------------------
if ($PSCmdlet.ShouldProcess($srcDir, "Move to $dstDir")) {
  Move-Item -Path $srcDir -Destination $dstDir
  Write-Host "    moved baked cache to v$NewVersion" -ForegroundColor Green
}

# --- 5. prune older LOCAL version dirs --------------------------------------------------------
if (-not $NoPrune) {
  Get-ChildItem -Path $cacheRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^v(\d+)$' -and [int]$Matches[1] -lt $oldestKept } |
    ForEach-Object {
      if ($PSCmdlet.ShouldProcess($_.FullName, "Delete stale local cache version")) {
        Remove-Item -Recurse -Force $_.FullName
        Write-Host "    pruned local $($_.Name)" -ForegroundColor Green
      }
    }
}

if ($SkipUpload) {
  Write-Host "==> -SkipUpload set: local bump + move + prune done; skipping Azure." -ForegroundColor Yellow
  Write-NextSteps $NewVersion
  return
}

# --- 6. discover storage account + share (if not passed) --------------------------------------
if (-not $StorageAccount -or -not $Share) {
  Write-Host "==> discovering the AzureFile share from Container App '$APP'..." -ForegroundColor Cyan
  try {
    $storageName = az containerapp show -n $APP -g $RG `
      --query "properties.template.volumes[?storageType=='AzureFile'].storageName | [0]" -o tsv
    if (-not $storageName) { throw "no AzureFile volume on the app" }
    $envId   = az containerapp show -n $APP -g $RG --query "properties.environmentId" -o tsv
    $envName = Split-Path $envId -Leaf
    $af = az containerapp env storage show -n $envName -g $RG --storage-name $storageName `
      --query "properties.azureFile.{acct:accountName, share:shareName}" -o json | ConvertFrom-Json
    if (-not $StorageAccount) { $StorageAccount = $af.acct }
    if (-not $Share) { $Share = $af.share }
    Write-Host "    storage account = $StorageAccount, share = $Share (storageName '$storageName')" -ForegroundColor DarkGray
  } catch {
    throw "Could not auto-discover the storage account/share ($_). Pass -StorageAccount and -Share."
  }
}
if (-not $Share) { $Share = 'anbennar' }
if (-not $StorageAccount) { throw "No storage account resolved. Pass -StorageAccount." }

# --- 7. resolve auth: -AccountKey, else fetch the key, else AAD login --------------------------
$authArgs = @()
if ($AccountKey) {
  $authArgs = @('--account-key', $AccountKey)
} else {
  try {
    $key = az storage account keys list -n $StorageAccount --query "[0].value" -o tsv 2>$null
    if ($key) { $authArgs = @('--account-key', $key); Write-Host "    using fetched account key" -ForegroundColor DarkGray }
  } catch { }
  if (-not $authArgs) {
    $authArgs = @('--auth-mode', 'login')
    Write-Host "    no account key available — using --auth-mode login (needs a Storage File Data role on your identity)" -ForegroundColor DarkGray
  }
}

# --- 8. upload the baked cache to  <share>/plot-cache/v<new>  ---------------------------------
$destPath = "$CACHE_SUBDIR/v$NewVersion"
if ($PSCmdlet.ShouldProcess("$StorageAccount/$Share/$destPath", "Upload $count province files from v$NewVersion")) {
  Write-Host "==> uploading v$NewVersion cache to $StorageAccount/$Share/$destPath (~37 MB of gzipped fields)..." -ForegroundColor Cyan
  $uploadArgs = @('storage','file','upload-batch','--account-name',$StorageAccount) + $authArgs +
                @('--destination',$Share,'--destination-path',$destPath,'--source',$dstDir,'--pattern','*.json.gz')
  az @uploadArgs
  if ($LASTEXITCODE -ne 0) { throw "az storage file upload-batch failed" }
  Write-Host "    uploaded $count files to $destPath" -ForegroundColor Green
}

# --- 9. prune older REMOTE version dirs on the share -----------------------------------------
if (-not $NoPrune) {
  Write-Host "==> pruning remote cache versions older than v$oldestKept..." -ForegroundColor Cyan
  $listArgs = @('storage','file','list','--account-name',$StorageAccount) + $authArgs +
              @('--share-name',$Share,'--path',$CACHE_SUBDIR,'-o','json')
  $entries = az @listArgs | ConvertFrom-Json
  $stale = @($entries | Where-Object { $_.name -match '^v(\d+)$' -and [int]($_.name -replace '^v','') -lt $oldestKept })
  if (-not $stale) {
    Write-Host "    nothing to prune on the share." -ForegroundColor DarkGray
  }
  foreach ($d in $stale) {
    $verPath = "$CACHE_SUBDIR/$($d.name)"
    if ($PSCmdlet.ShouldProcess("$StorageAccount/$Share/$verPath", "Delete stale remote cache version")) {
      $delArgs = @('storage','file','delete-batch','--account-name',$StorageAccount) + $authArgs +
                 @('--source',$Share,'--pattern',"$verPath/*")
      az @delArgs | Out-Null
      $dirArgs = @('storage','directory','delete','--account-name',$StorageAccount) + $authArgs +
                 @('--share-name',$Share,'--name',$verPath)
      az @dirArgs 2>$null | Out-Null   # best-effort: the dir must be empty; harmless if it lingers
      Write-Host "    pruned remote $($d.name)" -ForegroundColor Green
    }
  }
}

Write-NextSteps $NewVersion
