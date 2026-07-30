param(
    [switch]$AllowLiveInput
)

$ErrorActionPreference = "Stop"
if ($AllowLiveInput) {
    throw "Live input is intentionally unsupported by DATA-WP-05 fixture tooling."
}

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    .\gradlew.bat :services:parking-service:test `
        --tests "com.parkio.parking.externalsource.registry.RegistryCandidate*Test"
}
finally {
    Pop-Location
}
