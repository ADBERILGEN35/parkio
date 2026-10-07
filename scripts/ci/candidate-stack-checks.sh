#!/usr/bin/env bash
# Readiness and ingress-protection checks against a running candidate full stack
# (candidate-images.yml). The same checks the runtime-validation workflow performs on its
# source-built stack, here against the loaded candidate images.
#
#   COMPOSE_ENV_FILE=... COMPOSE_FILES="-f ... -f ..." scripts/ci/candidate-stack-checks.sh OUT_DIR
#
# Waits (up to 15 minutes) until every required container is healthy, then records each
# service's readiness JSON, the gateway JWKS, and the status codes that prove the gateway's
# protected routes answer 401, a service called directly without the gateway secret answers 401
# GATEWAY_AUTH_REQUIRED, and path traversal answers 400. Exit 1 on any failed check.
set -euo pipefail
OUT="${1:?OUT_DIR}"
: "${COMPOSE_ENV_FILE:?COMPOSE_ENV_FILE}"
: "${COMPOSE_FILES:?COMPOSE_FILES}"
mkdir -p "$OUT"
# shellcheck disable=SC2086
dc() { docker compose --env-file "$COMPOSE_ENV_FILE" $COMPOSE_FILES "$@"; }

required=(
  kafka redis minio clamav caddy web
  postgres-auth postgres-user postgres-parking postgres-media postgres-gamification
  postgres-notification postgres-moderation postgres-analytics postgres-ai-validation
  gateway-service auth-service user-service parking-service media-service
  gamification-service notification-service moderation-service ai-validation-service analytics-service
  prometheus grafana loki promtail alertmanager
)
wait_service() {
  local svc="$1" cid status
  cid="$(dc ps -q "$svc")"
  if [ -z "$cid" ]; then echo "::error::$svc has no container id"; return 1; fi
  status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid")"
  printf '%s\t%s\n' "$svc" "$status" >> "$OUT/health.tsv"
  [ "$status" = "healthy" ]
}
deadline=$((SECONDS + 900))
while true; do
  : > "$OUT/health.tsv"
  failed=0
  for svc in "${required[@]}"; do wait_service "$svc" || failed=1; done
  if [ "$failed" -eq 0 ]; then echo "All required healthchecks are healthy."; break; fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "::error::Timed out waiting for required containers to become healthy."
    cat "$OUT/health.tsv"; dc ps; exit 1
  fi
  sleep 15
done

declare -A ports=(
  [gateway-service]=8080 [auth-service]=8081 [user-service]=8082 [parking-service]=8083
  [media-service]=8084 [gamification-service]=8085 [notification-service]=8086
  [moderation-service]=8087 [ai-validation-service]=8088 [analytics-service]=8089
)
for svc in "${!ports[@]}"; do
  dc exec -T "$svc" curl -fsS "http://localhost:${ports[$svc]}/actuator/health/readiness" > "$OUT/${svc}-readiness.json"
done
dc exec -T gateway-service curl -fsS http://localhost:8080/api/v1/auth/.well-known/jwks.json > "$OUT/gateway-jwks.json"

capture_status() { # service artifact url
  local service="$1" artifact="$2" url="$3" status
  status="$(dc exec -T "$service" curl --path-as-is -sS -o "/tmp/${artifact}" -w '%{http_code}' "$url")"
  dc exec -T "$service" cat "/tmp/${artifact}" > "$OUT/${artifact}"
  printf '%s' "$status"
}
check() { # name expected actual
  if [ "$2" = "$3" ]; then printf 'PASS\t%s\t%s\n' "$1" "$3"; else printf 'FAIL\t%s\texpected %s got %s\n' "$1" "$2" "$3"; return 1; fi
}
# Every check prints one PASS or FAIL line; the verdict is read from the recorded lines, so nothing
# depends on a variable set inside the pipeline's subshell.
{
  check gateway-protected-401 401 "$(capture_status gateway-service gateway-protected-401.json \
    'http://localhost:8080/api/v1/parking/spots/nearby?latitude=41.0&longitude=29.0&radiusMeters=1000')" || true
  check gateway-geocoding-401 401 "$(capture_status gateway-service gateway-geocoding-401.json \
    'http://localhost:8080/api/v1/parking/geocode/reverse?latitude=41.0&longitude=29.0')" || true
  check direct-parking-gateway-auth-required 401 "$(capture_status parking-service direct-parking-gateway-auth-required.json \
    'http://localhost:8083/api/v1/parking/spots/nearby?latitude=41.0&longitude=29.0&radiusMeters=1000')" || true
  if grep -q GATEWAY_AUTH_REQUIRED "$OUT/direct-parking-gateway-auth-required.json"; then
    printf 'PASS\tdirect-parking-code\tGATEWAY_AUTH_REQUIRED\n'
  else
    printf 'FAIL\tdirect-parking-code\tGATEWAY_AUTH_REQUIRED missing\n'
  fi
  check gateway-traversal-400 400 "$(capture_status gateway-service gateway-traversal-400.json \
    'http://localhost:8080/api/v1/analytics/users/../overview')" || true
  if grep -q INVALID_REQUEST_PATH "$OUT/gateway-traversal-400.json"; then
    printf 'PASS\tgateway-traversal-code\tINVALID_REQUEST_PATH\n'
  else
    printf 'FAIL\tgateway-traversal-code\tINVALID_REQUEST_PATH missing\n'
  fi
  check gateway-encoded-traversal-400 400 "$(capture_status gateway-service gateway-encoded-traversal-400.json \
    'http://localhost:8080/api/v1/parking/%2e%2e/%2e%2e/internal')" || true
  check gateway-jwks-200 200 "$(capture_status gateway-service gateway-valid-route.json \
    'http://localhost:8080/api/v1/auth/.well-known/jwks.json')" || true
} | tee "$OUT/checks.tsv"
expected_checks=9
if grep -q '^FAIL' "$OUT/checks.tsv" || [ "$(grep -c '^PASS' "$OUT/checks.tsv")" -ne "$expected_checks" ]; then
  echo "::error::candidate stack checks failed (see $OUT/checks.tsv)"
  exit 1
fi
echo "All $expected_checks stack checks passed."
