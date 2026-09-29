#!/usr/bin/env bash
#
# PROD-MUNI-01 / M1 — Municipal discovery smoke (executable companion).
#
# Verifies authenticated GET /parking/facilities/nearby contract against a live
# gateway. Does not start or redeploy stacks.
#
# Usage:
#   PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta ./scripts/prod-muni-01/smoke-municipal.sh
#   PARKIO_SMOKE_MUNICIPAL_CONTRACT_ONLY=1 ./scripts/prod-muni-01/smoke-municipal.sh
#
# Optional: PARKIO_REAL_USER_EMAIL / PARKIO_REAL_USER_PASSWORD
# Optional: PARKIO_GATEWAY_URL
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=lib/common.sh
source "$ROOT/scripts/prod-muni-01/lib/common.sh"

cd "$ROOT"
CONTRACT_ONLY="${PARKIO_SMOKE_MUNICIPAL_CONTRACT_ONLY:-0}"
NODE_BIN="$(prod_muni_node)"
pass=0
fail=0
ok() { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

echo "=== PROD-MUNI-01 M1 municipal smoke ==="

ENV_TS="frontend/apps/web/src/config/env.ts"
API_TS="frontend/packages/api-client/src/parking.ts"
prod_muni_require_file "$ENV_TS"
prod_muni_require_file "$API_TS"

if grep -q "municipalDiscovery: raw.VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED === 'true'" "$ENV_TS"; then
  ok "web flag contract: municipalDiscovery true only when bake=true"
else
  bad "web flag contract missing in env.ts"
fi

if grep -q "/parking/facilities/nearby" "$API_TS"; then
  ok "api-client facilities nearby path present"
else
  bad "api-client missing /parking/facilities/nearby"
fi

CTRL="services/parking-service/src/main/java/com/parkio/parking/presentation/MunicipalFacilityController.java"
prod_muni_require_file "$CTRL"
if grep -q '@RequestMapping("/api/v1/parking/facilities")' "$CTRL" \
  && grep -q '@GetMapping("/nearby")' "$CTRL"; then
  ok "backend facilities nearby mapping present"
else
  bad "MunicipalFacilityController nearby mapping missing"
fi

if [ "$CONTRACT_ONLY" = "1" ]; then
  echo "INFO: contract-only mode (PARKIO_SMOKE_MUNICIPAL_CONTRACT_ONLY=1) — skipping live HTTP"
  if [ "$fail" -ne 0 ]; then
    echo "=== M1 FAILED ($fail) ===" >&2
    exit 1
  fi
  echo "=== M1 OK (contract-only) pass=$pass ==="
  exit 0
fi

PROFILE="${PARKIO_DEPLOYMENT_PROFILE:-hosted-beta}"
case "$PROFILE" in
  azure-hosted-beta) DEFAULT_GATEWAY_URL="https://api.parkio.dev" ;;
  hosted-beta) DEFAULT_GATEWAY_URL="http://127.0.0.1:8080" ;;
  *) echo "ERROR: unsupported PARKIO_DEPLOYMENT_PROFILE='$PROFILE'" >&2; exit 2 ;;
esac
GATEWAY_URL="${PARKIO_GATEWAY_URL:-$DEFAULT_GATEWAY_URL}"
API="$GATEWAY_URL/api/v1"
EMAIL="${PARKIO_REAL_USER_EMAIL:-user@real-e2e.parkio.local}"
PASSWORD="${PARKIO_REAL_USER_PASSWORD:-StrongParkio123}"
CLIENT_HEADER="X-Parkio-Client: mobile"
LAT="${PARKIO_SMOKE_MUNICIPAL_LAT:-38.4192}"
LNG="${PARKIO_SMOKE_MUNICIPAL_LNG:-27.1287}"
RADIUS="${PARKIO_SMOKE_MUNICIPAL_RADIUS:-5000}"
LIMIT="${PARKIO_SMOKE_MUNICIPAL_LIMIT:-20}"
BODY="/tmp/parkio-muni-smoke-body.json"

http_code() {
  local url="$1"
  shift
  curl -sS -o "$BODY" -w '%{http_code}' "$@" "$url" || echo "000"
}

json_field() {
  "$NODE_BIN" -e "const d=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8')); const v=d[process.argv[2]]; process.stdout.write(v==null?'':String(v));" "$1" "$2"
}

echo "gateway=$GATEWAY_URL profile=$PROFILE"

code="$(http_code "$API/parking/facilities/nearby?lat=${LAT}&lng=${LNG}&radius=${RADIUS}&limit=${LIMIT}" \
  -H "$CLIENT_HEADER")"
if [ "$code" = "401" ]; then
  ok "facilities nearby requires auth ($code)"
else
  bad "facilities nearby unauth expected 401 got $code"
fi

code="$(http_code "$API/auth/login" -H "$CLIENT_HEADER" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")"
ACCESS=""
if [ "$code" = "200" ]; then
  ACCESS="$(json_field "$BODY" accessToken)"
  if [ -n "$ACCESS" ]; then
    ok "login"
  else
    bad "login missing accessToken"
  fi
else
  bad "login ($code)"
fi

if [ -n "$ACCESS" ]; then
  AUTH="Authorization: Bearer $ACCESS"
  code="$(http_code "$API/parking/facilities/nearby?lat=${LAT}&lng=${LNG}&radius=${RADIUS}&limit=${LIMIT}" \
    -H "$CLIENT_HEADER" -H "$AUTH")"
  if [ "$code" = "200" ]; then
    if "$NODE_BIN" - "$BODY" <<'JS'
const fs = require('fs');
const doc = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
if (!Array.isArray(doc)) {
  console.error('ERROR: facilities nearby must return a JSON array');
  process.exit(1);
}
const required = ['id', 'facilityType', 'latitude', 'longitude'];
for (let i = 0; i < Math.min(doc.length, 5); i += 1) {
  const row = doc[i];
  if (!row || typeof row !== 'object') {
    console.error(`ERROR: item ${i} is not an object`);
    process.exit(1);
  }
  const missing = required.filter((k) => !(k in row));
  if (missing.length) {
    console.error(`ERROR: item ${i} missing ${missing.join(',')}`);
    process.exit(1);
  }
}
console.log(`count=${doc.length}`);
JS
    then
      ok "facilities nearby authenticated + response shape"
    else
      bad "facilities nearby response shape invalid"
    fi
  else
    bad "facilities nearby authenticated ($code)"
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo "=== M1 FAILED fail=$fail pass=$pass ===" >&2
  exit 1
fi
echo "=== M1 OK pass=$pass ==="
exit 0
