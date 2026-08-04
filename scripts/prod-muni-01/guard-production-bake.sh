#!/usr/bin/env bash
#
# PROD-MUNI-01 / M3 — Production municipal bake guard (executable).
#
# Fails when repository defaults or a production-shaped build attempt would bake
# VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true.
#
# Usage (repo root):
#   ./scripts/prod-muni-01/guard-production-bake.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=lib/common.sh
source "$ROOT/scripts/prod-muni-01/lib/common.sh"

cd "$ROOT"
fail=0
ok() { prod_muni_pass "$1"; }
bad() { prod_muni_fail "$1" || true; fail=$((fail + 1)); }

echo "=== PROD-MUNI-01 M3 production bake guard ==="

DOCKERFILE="frontend/apps/web/Dockerfile"
prod_muni_require_file "$DOCKERFILE"

if grep -qE '^ARG VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false$' "$DOCKERFILE"; then
  ok "Dockerfile ARG defaults municipal discovery to false"
else
  bad "Dockerfile must declare ARG VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false"
fi

for compose in docker/docker-compose.images.yml docker/docker-compose.hosted-beta.yml; do
  prod_muni_require_file "$compose"
  if grep -q 'VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED: ${VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED:-false}' "$compose"; then
    ok "$compose compose bake default is false"
  else
    bad "$compose must default VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED to false"
  fi
done

for envf in docker/.env.hosted-beta.example docker/.env.azure-hosted-beta.example frontend/apps/web/.env.example; do
  prod_muni_require_file "$envf"
  if grep -qE '^VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false' "$envf"; then
    ok "$envf documents municipal discovery false"
  else
    bad "$envf must set VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false"
  fi
done

# Executable production attempt must fail closed.
NODE="${NODE:-node}"
VALIDATE="$ROOT/frontend/apps/web/scripts/validate-build-env.mjs"
prod_muni_require_file "$VALIDATE"

set +e
out="$(
  VITE_APP_ENV=production \
  VITE_API_BASE_URL=https://api.fixture.invalid/api/v1 \
  VITE_MAPTILER_KEY=fixture-public-map-key-never-use-in-production \
  VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true \
  "$NODE" "$VALIDATE" 2>&1
)"
status=$?
set -e
if [ "$status" -ne 0 ]; then
  ok "validate-build-env rejects production + municipal=true (exit $status)"
else
  bad "validate-build-env must reject production + municipal=true"
  echo "$out" >&2
fi

set +e
out="$(
  VITE_APP_ENV=production \
  VITE_API_BASE_URL=https://api.fixture.invalid/api/v1 \
  VITE_MAPTILER_KEY=fixture-public-map-key-never-use-in-production \
  VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false \
  "$NODE" "$VALIDATE" 2>&1
)"
status=$?
set -e
if [ "$status" -eq 0 ]; then
  ok "validate-build-env accepts production + municipal=false"
else
  bad "validate-build-env should accept production + municipal=false"
  echo "$out" >&2
fi

# Hosted-beta leave-on must remain allowed by the bake gate (not production).
set +e
out="$(
  VITE_APP_ENV=hosted-beta \
  VITE_API_BASE_URL=https://api.fixture.invalid/api/v1 \
  VITE_MAPTILER_KEY=fixture-public-map-key-never-use-in-production \
  VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true \
  "$NODE" "$VALIDATE" 2>&1
)"
status=$?
set -e
if [ "$status" -eq 0 ]; then
  ok "validate-build-env allows hosted-beta + municipal=true"
else
  bad "validate-build-env must allow hosted-beta leave-on municipal=true"
  echo "$out" >&2
fi

if [ "$fail" -ne 0 ]; then
  echo "=== M3 FAILED ($fail) ===" >&2
  exit 1
fi
echo "=== M3 OK ==="
exit 0
