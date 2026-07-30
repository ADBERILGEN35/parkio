param(
    [switch]$AllowLiveInput
)

$ErrorActionPreference = "Stop"
if ($AllowLiveInput) {
    throw "Live input is intentionally unsupported by DATA-WP-04 dry-run tooling."
}

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    .\gradlew.bat :services:parking-service:test `
        --tests com.parkio.parking.externalsource.registry.RegistryCandidateDryRunTest
}
finally {
    Pop-Location
}
