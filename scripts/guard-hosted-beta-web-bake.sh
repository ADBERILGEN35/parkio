#!/usr/bin/env bash
#
# P01F — Hosted-beta web bake + release-pin coherence guard.
#
# Fails when canonical release bake flags drift from the intended live contract
# or when the source-controlled web pin no longer matches the documented digest.
#
# Usage (repo root):
#   ./scripts/guard-hosted-beta-web-bake.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

fail=0
ok() { echo "PASS: $1"; }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

BAKE="docker/web-hosted-beta.release-bake.env"
MUNI_BAKE="docker/web-hosted-beta.municipal-on.bake.env"
PIN="docker/docker-compose.web-release-pin.yml"
FILES="docker/compose.production.files"
EXPECTED_DIGEST='sha256:d9999a020376cc89b86a92410ceb784a7290258f4d1c0c0d968a6a78d62c443a'

require_file() {
  if [ -f "$1" ]; then
    ok "present $1"
  else
    bad "missing $1"
  fi
}

require_kv() {
  local file="$1" key="$2" expected="$3"
  local line
  line="$(grep -E "^${key}=" "$file" | head -n1 | tr -d '\r' || true)"
  if [ -z "$line" ]; then
    bad "$file missing $key"
    return
  fi
  local value="${line#*=}"
  if [ "$value" = "$expected" ]; then
    ok "$file $key=$expected"
  else
    bad "$file $key expected=$expected got=$value"
  fi
}

echo "=== P01F hosted-beta web bake / pin guard ==="

require_file "$BAKE"
require_file "$MUNI_BAKE"
require_file "$PIN"
require_file "$FILES"

require_kv "$BAKE" VITE_APP_ENV hosted-beta
require_kv "$BAKE" VITE_API_BASE_URL 'https://api.parkio.dev/api/v1'
require_kv "$BAKE" VITE_PUBLIC_EXPLORE_ENABLED true
require_kv "$BAKE" VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED false
require_kv "$BAKE" PARKIO_PUBLIC_EXPLORE_ENABLED true

require_kv "$MUNI_BAKE" VITE_PUBLIC_EXPLORE_ENABLED true
require_kv "$MUNI_BAKE" VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED true
require_kv "$MUNI_BAKE" VITE_API_BASE_URL 'https://api.parkio.dev/api/v1'

if grep -q "$EXPECTED_DIGEST" "$PIN"; then
  ok "web release pin documents $EXPECTED_DIGEST"
else
  bad "web release pin must reference $EXPECTED_DIGEST"
fi

if tr -d '\r' < "$FILES" | grep -qx 'docker/docker-compose.web-release-pin.yml'; then
  ok "compose.production.files includes web-release-pin.yml"
else
  bad "compose.production.files must list docker/docker-compose.web-release-pin.yml"
fi

# Azure example must document Explore ON so operators do not rebuild API-base-only.
AZURE_EX="docker/.env.azure-hosted-beta.example"
require_file "$AZURE_EX"
require_kv "$AZURE_EX" VITE_PUBLIC_EXPLORE_ENABLED true
require_kv "$AZURE_EX" VITE_API_BASE_URL 'https://api.parkio.dev/api/v1'
# Municipal leave-on stays in the separate bake file (PROD-MUNI example contract).
require_kv "$AZURE_EX" VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED false

if [ "$fail" -ne 0 ]; then
  echo "=== P01F bake/pin guard FAILED ($fail) ===" >&2
  exit 1
fi
echo "=== P01F bake/pin guard OK ==="
exit 0
