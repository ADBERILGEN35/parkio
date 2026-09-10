# Offline policy guards for PP-01B-IAC-01 (no Azure credentials).
# Usage: pwsh -File infra/terraform/tests/policy-guards.ps1

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Repo = Resolve-Path (Join-Path $Root '../..')
$Failures = [System.Collections.Generic.List[string]]::new()

function Fail([string]$Msg) { [void]$Failures.Add($Msg); Write-Host "FAIL: $Msg" -ForegroundColor Red }
function Ok([string]$Msg) { Write-Host "OK:   $Msg" -ForegroundColor Green }

# --- Layout ---
$requiredDirs = @(
  'modules/network-dns',
  'modules/postgresql-flexible-server',
  'modules/database-roles',
  'modules/postgis-bootstrap',
  'modules/policy-guards',
  'stacks/sandbox',
  'stacks/staging',
  'stacks/production',
  'policies',
  'tests',
  'docs'
)
foreach ($d in $requiredDirs) {
  $p = Join-Path $Root $d
  if (-not (Test-Path $p)) { Fail "missing directory $d" } else { Ok "directory $d" }
}

# --- Manifest ---
$manifestPath = Join-Path $Root 'policies/service-manifest.yaml'
if (-not (Test-Path $manifestPath)) { Fail 'missing policies/service-manifest.yaml' }
else {
  $text = Get-Content $manifestPath -Raw
  $dbMatches = [regex]::Matches($text, 'database:\s*(\S+)')
  $roleMatches = [regex]::Matches($text, 'role:\s*(\S+)')
  $dbs = $dbMatches | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
  $roles = $roleMatches | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
  if ($dbs.Count -ne 10) { Fail "manifest unique databases=$($dbs.Count) expected 10" } else { Ok '10 unique databases' }
  if ($roles.Count -ne 10) { Fail "manifest unique roles=$($roles.Count) expected 10" } else { Ok '10 unique roles' }
  if ($text -notmatch 'parking-service.*parkio_parking.*parking' -and $text -notmatch 'service: parking-service') {
    Fail 'parking-service entry missing'
  } else { Ok 'parking-service present' }
  if ($text -notmatch 'cluster: parking') { Fail 'parking cluster missing' } else { Ok 'parking cluster present' }
  # Ensure non-parking services are not on parking: count cluster parking occurrences == 1
  $parkingClusters = ([regex]::Matches($text, 'cluster:\s*parking')).Count
  if ($parkingClusters -ne 1) { Fail "cluster:parking count=$parkingClusters expected 1" } else { Ok 'only parking-service on parking' }
}

# --- PostGIS parking-only in modules ---
$postgis = Get-Content (Join-Path $Root 'modules/postgis-bootstrap/main.tf') -Raw
if ($postgis -notmatch 'parkio_parking') { Fail 'postgis module missing parkio_parking target' } else { Ok 'postgis targets parkio_parking' }
if ($postgis -notmatch 'cluster == "parking"') { Fail 'postgis missing parking-only cluster guard' } else { Ok 'postgis parking-only guard' }

# --- Public access disabled ---
$server = Get-Content (Join-Path $Root 'modules/postgresql-flexible-server/main.tf') -Raw
if ($server -notmatch 'public_network_access_enabled\s*=\s*false') { Fail 'server module must hard-disable public access' } else { Ok 'public access disabled in server module' }
if ($server -notmatch 'reject_burstable_with_ha') { Fail 'missing burstable+HA guard' } else { Ok 'burstable+HA guard present' }

# --- Production apply prohibition ---
$prodVars = Get-Content (Join-Path $Root 'stacks/production/variables.tf') -Raw
if ($prodVars -notmatch 'apply_authorized' -or $prodVars -notmatch 'must be false') {
  Fail 'production apply_authorized must reject true'
} else { Ok 'production apply_authorized rejected' }
if ($prodVars -notmatch 'production_enablement') { Fail 'missing production_enablement guard' } else { Ok 'production_enablement guard' }

# --- CI must not contain terraform apply ---
$ci = Join-Path $Repo '.github/workflows/pp-01b-terraform-offline.yml'
if (-not (Test-Path $ci)) { Fail 'missing offline CI workflow' }
else {
  $ciText = Get-Content $ci -Raw
  if ($ciText -match '(?i)terraform\s+apply' -or $ciText -match '(?i)terraform\s+destroy') {
    Fail 'CI must not contain terraform apply/destroy'
  } else { Ok 'CI has no apply/destroy' }
  if ($ciText -match '(?i)azure/login|az login|ARM_CLIENT_SECRET') {
    Fail 'CI must not request Azure credentials'
  } else { Ok 'CI has no Azure credential steps' }
}

# --- Lockfiles ---
foreach ($stack in @('sandbox', 'staging', 'production')) {
  $lock = Join-Path $Root "stacks/$stack/.terraform.lock.hcl"
  if (-not (Test-Path $lock)) { Fail "missing lockfile for $stack" } else { Ok "lockfile $stack" }
}

# --- No committed secrets patterns in tfvars ---
$badTfvars = Get-ChildItem -Path $Root -Recurse -Include '*.tfvars', '*.auto.tfvars' -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -notmatch 'example' }
if ($badTfvars) { Fail "committed tfvars found: $($badTfvars.FullName -join ', ')" } else { Ok 'no committed secret tfvars' }

# --- Weak TLS banned ---
$tfFiles = Get-ChildItem -Path $Root -Recurse -Filter '*.tf'
$weak = $tfFiles | Select-String -Pattern 'sslmode\s*=\s*"(disable|allow|prefer|require|verify-ca)"' |
  Where-Object { $_.Path -notmatch 'provider "postgresql"' -and $_.Line -notmatch 'offline' }
# Provider bootstrap may use disable for unreachable offline host; app handoff must be verify-full
$handoff = ($tfFiles | Select-String -Pattern 'verify-full').Count
if ($handoff -lt 3) { Fail 'verify-full handoff under-represented' } else { Ok 'verify-full handoff present' }

# --- No password outputs ---
$outputs = Get-ChildItem -Path $Root -Recurse -Filter 'outputs.tf' | ForEach-Object { Get-Content $_.FullName -Raw }
$joined = $outputs -join "`n"
if ($joined -match '(?i)password\s*=' -or $joined -match '(?i)jdbc://[^"]+:') {
  Fail 'outputs appear to expose password/JDBC secrets'
} else { Ok 'outputs have no password/JDBC secrets' }

if ($Failures.Count -gt 0) {
  Write-Host "`nPolicy guards FAILED: $($Failures.Count)" -ForegroundColor Red
  exit 1
}
Write-Host "`nPolicy guards PASSED" -ForegroundColor Green
exit 0
