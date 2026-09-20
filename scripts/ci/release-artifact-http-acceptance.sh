#!/usr/bin/env bash
# Disposable HTTP acceptance for BC/PA-06 candidate images.
# Requires: COMPOSE_ENV_FILE, COMPOSE_FILES, running compose project.
set -euo pipefail

PA06_ONLY=0
if [ "${1:-}" = "--pa06-only" ]; then
  PA06_ONLY=1
fi

EVIDENCE_DIR="${EVIDENCE_DIR:-../out/http}"
mkdir -p "$EVIDENCE_DIR"

compose() {
  # shellcheck disable=SC2086
  docker compose --env-file "$COMPOSE_ENV_FILE" $COMPOSE_FILES "$@"
}

capture() {
  local service="$1" artifact="$2" url="$3"
  local container_artifact="/tmp/${artifact}"
  local status
  status="$(compose exec -T "$service" \
    curl --path-as-is -sS -o "$container_artifact" -w '%{http_code}' "$url" || true)"
  compose cp "${service}:${container_artifact}" "${EVIDENCE_DIR}/${artifact}" >/dev/null 2>&1 || true
  printf '%s' "$status"
}

assert_json_null_field() {
  local file="$1" field="$2"
  python3 - "$file" "$field" <<'PY'
import json, sys
path, field = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as f:
    data = json.load(f)
if field not in data:
    raise SystemExit(f"MISSING_FIELD:{field}")
if data[field] is not None:
    raise SystemExit(f"EXPECTED_JSON_NULL:{field}={data[field]!r}")
print(f"OK {field} is JSON null")
PY
}

assert_http() {
  local got="$1" want="$2" label="$3"
  if [ "$got" != "$want" ]; then
    echo "::error::${label}: expected HTTP ${want}, got ${got}"
    exit 1
  fi
  echo "OK ${label}: HTTP ${got}"
}

echo "=== PA-06 anonymous public explore (empty / baseline) ==="
status="$(capture gateway-service pa06-explore-baseline.json \
  'http://localhost:8080/api/v1/public/explore/facilities')"
assert_http "$status" "200" "gateway public explore baseline"
assert_json_null_field "${EVIDENCE_DIR}/pa06-explore-baseline.json" "communitySpotCountInScope"
# Explicitly reject numeric zero disclosure form.
if grep -q '"communitySpotCountInScope":0' "${EVIDENCE_DIR}/pa06-explore-baseline.json"; then
  echo "::error::communitySpotCountInScope must not be JSON 0"
  exit 1
fi
python3 - <<PY
import json
from pathlib import Path
data = json.loads(Path("${EVIDENCE_DIR}/pa06-explore-baseline.json").read_text())
assert "facilities" in data and isinstance(data["facilities"], list)
assert "municipalTotalInScope" in data
assert "municipalHiddenCount" in data
print("OK municipal envelope fields present; municipalTotalInScope=", data["municipalTotalInScope"])
PY

echo "=== PA-06 community variation (seed 2 visible spots; still withhold) ==="
# Seed via parking DB. Synthetic only.
compose cp scripts/ci/release-artifact-seed-pa06.sql postgres-parking:/tmp/release-artifact-seed-pa06.sql
compose exec -T postgres-parking \
  psql -U "${POSTGRES_PARKING_USER:-parkio_parking}" -d "${POSTGRES_PARKING_DB:-parkio_parking}" \
  -v ON_ERROR_STOP=1 \
  -f /tmp/release-artifact-seed-pa06.sql \
  | tee "${EVIDENCE_DIR}/seed-pa06.txt"

status="$(capture gateway-service pa06-explore-after-seed2.json \
  'http://localhost:8080/api/v1/public/explore/facilities?lat=38.4237&lng=27.1428&radiusMeters=5000')"
assert_http "$status" "200" "gateway public explore after seed2"
assert_json_null_field "${EVIDENCE_DIR}/pa06-explore-after-seed2.json" "communitySpotCountInScope"

if [ "$PA06_ONLY" -eq 1 ]; then
  echo "PA06_ONLY complete"
  exit 0
fi

echo "=== Authenticated nearby remains protected (no token => 401) ==="
status="$(capture gateway-service nearby-401.json \
  'http://localhost:8080/api/v1/parking/spots/nearby?latitude=41.0&longitude=29.0&radiusMeters=1000')"
assert_http "$status" "401" "gateway nearby unauthenticated"

status="$(capture parking-service parking-direct-401.json \
  'http://localhost:8083/api/v1/parking/spots/nearby?latitude=41.0&longitude=29.0&radiusMeters=1000')"
assert_http "$status" "401" "parking direct without gateway auth"

echo "=== Gateway routes to candidate parking (public explore via gateway) ==="
# Direct parking without X-Gateway-Auth must fail closed (not a public bypass).
status="$(capture parking-service parking-explore-direct.json \
  'http://localhost:8083/api/v1/public/explore/facilities')"
assert_http "$status" "401" "parking direct explore without gateway auth"
if ! grep -q "GATEWAY_AUTH_REQUIRED" "${EVIDENCE_DIR}/parking-explore-direct.json" 2>/dev/null; then
  echo "WARN: GATEWAY_AUTH_REQUIRED marker not found in parking direct body (status was 401)"
fi
# Gateway path already returned 200 with PA-06 null above — that is the public surface.

echo "=== Candidate health ==="
for pair in "gateway-service:8080" "parking-service:8083" "media-service:8084"; do
  svc="${pair%%:*}"
  port="${pair##*:}"
  status="$(capture "$svc" "${svc}-readiness.json" "http://localhost:${port}/actuator/health/readiness")"
  assert_http "$status" "200" "${svc} readiness"
done

echo "=== Media smoke (readiness already; scanner/storage via actuator) ==="
status="$(capture media-service media-health.json 'http://localhost:8084/actuator/health')"
assert_http "$status" "200" "media health"

echo "=== Telemetry / vendor export disabled ==="
# Confirm compose env overrides left vendor keys empty / false (redacted dump already).
python3 - <<'PY'
from pathlib import Path
text = Path("docker/.env.secpriv-ci").read_text()
checks = {
    "PARKIO_ALERT_SLACK_WEBHOOK_URL": "",
    "PARKIO_EMAIL_PROVIDER": "logging",
}
for key, want in checks.items():
    line = next((l for l in text.splitlines() if l.startswith(key + "=")), None)
    if line is None:
        print(f"WARN missing {key} (treated as unset)")
        continue
    val = line.split("=", 1)[1]
    if want and val != want:
        raise SystemExit(f"UNEXPECTED {key}={val!r}")
    if key.endswith("WEBHOOK_URL") and val not in ("", '""'):
        raise SystemExit(f"SLACK WEBHOOK MUST BE EMPTY, got {val!r}")
print("OK disposable env keeps Slack webhook empty and email=logging")
PY

# Network egress smoke: no NR/PostHog keys in running parking/gateway env (names only).
for svc in gateway-service parking-service media-service; do
  compose exec -T "$svc" sh -c \
    'env | grep -Ei "NEW_RELIC|POSTHOG|SLACK_WEBHOOK|SEGMENT" || true' \
    | tee "${EVIDENCE_DIR}/${svc}-vendor-env.txt"
  if grep -Ei 'NEW_RELIC_LICENSE_KEY=.+' "${EVIDENCE_DIR}/${svc}-vendor-env.txt" \
    | grep -v '=$' >/dev/null; then
    # Allow empty assignments only
    if grep -E 'NEW_RELIC_LICENSE_KEY=.+' "${EVIDENCE_DIR}/${svc}-vendor-env.txt" \
      | grep -Ev 'NEW_RELIC_LICENSE_KEY=$' >/dev/null; then
      echo "::error::Non-empty NEW_RELIC_LICENSE_KEY in ${svc}"
      exit 1
    fi
  fi
done

echo "ALL_HTTP_ACCEPTANCE_CHECKS_PASSED" | tee "${EVIDENCE_DIR}/RESULT.txt"
