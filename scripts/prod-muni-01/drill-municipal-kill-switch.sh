#!/usr/bin/env bash
#
# PROD-MUNI-01 / M2 — Municipal kill-switch drill (executable, no backend restart).
#
# Proves OFF then ON municipal bake contract via validate-build-env +
# verify-bundle-env fixtures (and optional real Vite build).
#
# Usage:
#   ./scripts/prod-muni-01/drill-municipal-kill-switch.sh
#   PARKIO_PROD_MUNI_VITE_BUILD=1 ./scripts/prod-muni-01/drill-municipal-kill-switch.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=lib/common.sh
source "$ROOT/scripts/prod-muni-01/lib/common.sh"

cd "$ROOT"
NODE="${NODE:-node}"
VALIDATE="$ROOT/frontend/apps/web/scripts/validate-build-env.mjs"
VERIFY="$ROOT/frontend/apps/web/scripts/verify-bundle-env.mjs"
VITE_BUILD="${PARKIO_PROD_MUNI_VITE_BUILD:-0}"
FIXTURE_KEY="fixture-public-map-key-never-use-in-production"
API_URL="https://api.fixture.invalid/api/v1"

fail=0
ok() { echo "PASS: $1"; }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

echo "=== PROD-MUNI-01 M2 municipal kill-switch drill ==="
echo "INFO: no backend restart / no compose up (web bake only)"

make_fixture_bundle() {
  local dir="$1"
  local municipal="$2"
  local app_env="${3:-hosted-beta}"
  mkdir -p "$dir/assets"
  cat >"$dir/assets/app.js" <<EOF
const injected={VITE_APP_ENV:"${app_env}",VITE_API_BASE_URL:"${API_URL}",VITE_MAPTILER_KEY:"${FIXTURE_KEY}",VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED:"${municipal}"};
globalThis.__fixture=injected;
EOF
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# --- OFF ---
echo "--- kill-switch OFF ---"
set +e
out="$(
  VITE_APP_ENV=hosted-beta \
  VITE_API_BASE_URL="$API_URL" \
  VITE_MAPTILER_KEY="$FIXTURE_KEY" \
  VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false \
  "$NODE" "$VALIDATE" 2>&1
)"
status=$?
set -e
if [ "$status" -eq 0 ]; then ok "OFF validate-build-env"; else bad "OFF validate-build-env"; echo "$out" >&2; fi

make_fixture_bundle "$TMP/off" "false"
set +e
out="$("$NODE" "$VERIFY" --dist "$TMP/off" --app-env hosted-beta --require-municipal false 2>&1)"
status=$?
set -e
if [ "$status" -eq 0 ]; then ok "OFF bundle municipal discovery removed/false"; else bad "OFF bundle verify"; echo "$out" >&2; fi

# --- ON ---
echo "--- kill-switch ON ---"
set +e
out="$(
  VITE_APP_ENV=hosted-beta \
  VITE_API_BASE_URL="$API_URL" \
  VITE_MAPTILER_KEY="$FIXTURE_KEY" \
  VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true \
  "$NODE" "$VALIDATE" 2>&1
)"
status=$?
set -e
if [ "$status" -eq 0 ]; then ok "ON validate-build-env"; else bad "ON validate-build-env"; echo "$out" >&2; fi

make_fixture_bundle "$TMP/on" "true"
set +e
out="$("$NODE" "$VERIFY" --dist "$TMP/on" --app-env hosted-beta --require-municipal true 2>&1)"
status=$?
set -e
if [ "$status" -eq 0 ]; then ok "ON bundle municipal discovery restored/true"; else bad "ON bundle verify"; echo "$out" >&2; fi

# Production OFF remains mandatory even during ON drill of hosted-beta.
set +e
out="$(
  VITE_APP_ENV=production \
  VITE_API_BASE_URL="$API_URL" \
  VITE_MAPTILER_KEY="$FIXTURE_KEY" \
  VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true \
  "$NODE" "$VALIDATE" 2>&1
)"
status=$?
set -e
if [ "$status" -ne 0 ]; then ok "production ON still rejected by bake guard"; else bad "production ON must fail"; fi

if [ "$VITE_BUILD" = "1" ]; then
  echo "--- optional Vite builds (PARKIO_PROD_MUNI_VITE_BUILD=1) ---"
  if ! command -v pnpm >/dev/null 2>&1; then
    bad "pnpm required for Vite kill-switch build"
  else
    WEB_DIR="$ROOT/frontend/apps/web"
    for mode in false true; do
      dist="$TMP/vite-$mode"
      mkdir -p "$dist"
      (
        cd "$ROOT/frontend"
        export VITE_APP_ENV=hosted-beta
        export VITE_API_BASE_URL="$API_URL"
        export VITE_MAPTILER_KEY="$FIXTURE_KEY"
        export VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED="$mode"
        # Build into temp via vite --outDir if supported; else copy after build.
        pnpm --filter @parkio/web exec vite build --outDir "$dist" --emptyOutDir
      )
      "$NODE" "$VERIFY" --dist "$dist" --app-env hosted-beta --require-municipal "$mode"
      ok "Vite build municipal=$mode verified"
    done
  fi
else
  echo "INFO: skipping real Vite builds (set PARKIO_PROD_MUNI_VITE_BUILD=1 for full bake drill)"
fi

ok "backend services untouched (no docker compose / no restart invoked)"

if [ "$fail" -ne 0 ]; then
  echo "=== M2 FAILED ($fail) ===" >&2
  exit 1
fi
echo "=== M2 OK ==="
exit 0
