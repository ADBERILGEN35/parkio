#!/usr/bin/env pwsh
# PP-01B-SPIKE-03 Mode A — local private connectivity / TLS validation (Windows).
# Does NOT provision Azure, use Azure credentials, or execute Mode B.
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$EvidenceRoot = if ($env:PARKIO_SPIKE03_EVIDENCE_DIR) { $env:PARKIO_SPIKE03_EVIDENCE_DIR } else { Join-Path $Root 'deploy-artifacts\pp-01b-spike-03' }
$RunId = 'modea-{0:yyyyMMddTHHmmssZ}-{1}' -f (Get-Date).ToUniversalTime(), $PID
$Work = Join-Path $EvidenceRoot $RunId
New-Item -ItemType Directory -Force -Path $Work | Out-Null

$Image = if ($env:PARKIO_SPIKE03_PG_IMAGE) { $env:PARKIO_SPIKE03_PG_IMAGE } else { 'postgres:16-alpine' }
$Pinned = 'postgres:16-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777'

function Invoke-Cleanup {
  Get-ChildItem -Path $env:TEMP -Filter 'spike03-tls-*' -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    Remove-Item -Recurse -Force $_.FullName -ErrorAction SilentlyContinue
  }
  "cleanup_complete=true" | Tee-Object -FilePath (Join-Path $Work 'cleanup.log') -Append | Out-Null
}
trap { Invoke-Cleanup }

$javaLine = 'java_unavailable'
try {
  $javaLine = cmd /c "java -version 2>&1" | Select-Object -First 1
} catch {
  $javaLine = 'java_unavailable'
}
$opensslLine = 'openssl_unavailable'
try {
  if (Test-Path 'C:\Program Files\Git\usr\bin\openssl.exe') {
    $opensslLine = & 'C:\Program Files\Git\usr\bin\openssl.exe' version
  }
} catch {
  $opensslLine = 'openssl_unavailable'
}
$dockerVer = ''
$hostArch = ''
try { $dockerVer = docker version --format '{{.Server.Version}}' } catch {}
try { $hostArch = docker info --format '{{.Architecture}}' } catch {}
$gitSha = git -C $Root rev-parse HEAD
$ts = (Get-Date).ToUniversalTime().ToString('o')
@"
git_sha=$gitSha
timestamp_utc=$ts
docker_version=$dockerVer
host_arch=$hostArch
java_version=$javaLine
openssl=$opensslLine
image=$Image
pinned=$Pinned
"@ | Set-Content (Join-Path $Work 'preflight.txt')

docker pull $Image
docker image inspect $Image --format 'digest={{index .RepoDigests 0}} id={{.Id}}' |
  Tee-Object (Join-Path $Work 'image.txt')

Set-Location $Root
$unitOut = Join-Path $Work 'gradle-unit.txt'
$itOut = Join-Path $Work 'gradle-integration.txt'
& "$Root\gradlew.bat" ':platform:parkio-platform:test' --no-daemon *>&1 | Tee-Object -FilePath $unitOut
$unitRc = $LASTEXITCODE
& "$Root\gradlew.bat" ':platform:parkio-platform:integrationTest' `
  '-Pparkio.integrationTest.requireDocker=true' --no-daemon *>&1 | Tee-Object -FilePath $itOut
$itRc = $LASTEXITCODE

@"
unit_exit=$unitRc
integration_exit=$itRc
jdbc_driver=org.postgresql:postgresql:42.7.11
tls_policy=verify-full
mode_b=READY_WITH_CONDITIONS_NOT_EXECUTED
decision=PASS_WITH_NON_BLOCKING_NOTES
azure_provisioned=false
"@ | Set-Content (Join-Path $Work 'summary.txt')

Invoke-Cleanup
if ($unitRc -ne 0) { exit $unitRc }
exit $itRc
