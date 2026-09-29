#!/usr/bin/env pwsh
# PP-01B-SPIKE-02 Mode A — local PostGIS runtime parity (Windows PowerShell).
# Does NOT provision Azure, touch hosted-beta, or start SPIKE-03.
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$EvidenceRoot = if ($env:PARKIO_SPIKE02_EVIDENCE_DIR) { $env:PARKIO_SPIKE02_EVIDENCE_DIR } else { Join-Path $Root 'deploy-artifacts\pp-01b-spike-02' }
$RunId = 'modea-{0:yyyyMMddTHHmmssZ}-{1}' -f (Get-Date).ToUniversalTime(), $PID
$Work = Join-Path $EvidenceRoot $RunId
New-Item -ItemType Directory -Force -Path $Work | Out-Null

$BaselineImage = if ($env:PARKIO_POSTGIS_BASELINE_IMAGE) { $env:PARKIO_POSTGIS_BASELINE_IMAGE } else { 'postgis/postgis:16-3.4' }
$NewerImage = if ($env:PARKIO_POSTGIS_NEWER_IMAGE) { $env:PARKIO_POSTGIS_NEWER_IMAGE } else { 'imresamu/postgis:16-3.6.1-bookworm' }

function Invoke-Cleanup {
  docker ps -aq --filter "label=parkio.pp01b.spike02=$RunId" | ForEach-Object { docker rm -f $_ 2>$null }
  "cleanup_complete=true" | Tee-Object -FilePath (Join-Path $Work 'cleanup.log') -Append | Out-Null
}
trap { Invoke-Cleanup }

@"
git_sha=$(git -C $Root rev-parse HEAD)
timestamp_utc=$((Get-Date).ToUniversalTime().ToString('o'))
docker_version=$(docker version --format '{{.Server.Version}}')
host_arch=$(docker info --format '{{.Architecture}}')
"@ | Set-Content (Join-Path $Work 'preflight.txt')

docker pull $BaselineImage
docker pull $NewerImage
docker image inspect $BaselineImage --format 'baseline.digest={{index .RepoDigests 0}} arch={{.Architecture}}' |
  Tee-Object (Join-Path $Work 'images.txt')
docker image inspect $NewerImage --format 'newer.digest={{index .RepoDigests 0}} arch={{.Architecture}}' |
  Tee-Object (Join-Path $Work 'images.txt') -Append

$env:PARKIO_SPIKE02_EVIDENCE_DIR = $Work
Set-Location $Root

function Invoke-Its([string]$Label, [string]$Image) {
  $out = Join-Path $Work "gradle-$Label.txt"
  & "$Root\gradlew.bat" ":services:parking-service:integrationTest" `
    "-Pparkio.integrationTest.requireDocker=true" `
    "-Dparkio.postgis.image=$Image" `
    --no-daemon *>&1 | Tee-Object -FilePath $out
  return $LASTEXITCODE
}

$baseRc = Invoke-Its 'baseline' $BaselineImage
$newRc = Invoke-Its 'newer' $NewerImage
@"
baseline_exit=$baseRc
newer_exit=$newRc
"@ | Set-Content (Join-Path $Work 'summary.txt')
Invoke-Cleanup
if ($baseRc -ne 0) { exit $baseRc }
exit $newRc
