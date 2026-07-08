#!/usr/bin/env bash
#
# Parkio — hosted-beta smoke checks against a running gateway.
#
# Usage:
#   PARKIO_GATEWAY_URL=https://api.beta.example.com ./scripts/smoke-hosted-beta.sh
#
# Optional credentials (seeded accounts recommended):
#   PARKIO_REAL_USER_EMAIL / PARKIO_REAL_USER_PASSWORD
#
# Optional:
#   PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1  — expect direct :8083 access to fail (hosted-beta)
#
set -euo pipefail

GATEWAY_URL="${PARKIO_GATEWAY_URL:-http://127.0.0.1:8080}"
API="$GATEWAY_URL/api/v1"
EMAIL="${PARKIO_REAL_USER_EMAIL:-user@real-e2e.parkio.local}"
PASSWORD="${PARKIO_REAL_USER_PASSWORD:-StrongParkio123}"
EXPECT_DIRECT_BLOCKED="${PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED:-0}"
CLIENT_HEADER="X-Parkio-Client: mobile"

pass=0
fail=0

ok() { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

http_code() {
  local url="$1"
  shift
  curl -sS -o /tmp/parkio-smoke-body.json -w '%{http_code}' "$@" "$url" || echo "000"
}

echo "=== Parkio smoke ($GATEWAY_URL) ==="

# Gateway health (actuator may be on gateway root)
code="$(http_code "$GATEWAY_URL/actuator/health" -H "$CLIENT_HEADER")"
if [ "$code" = "200" ]; then ok "gateway health ($code)"; else bad "gateway health ($code)"; fi

code="$(http_code "$API/auth/.well-known/jwks.json" -H "$CLIENT_HEADER")"
if [ "$code" = "200" ]; then
  if jq -e '.keys | length > 0' /tmp/parkio-smoke-body.json >/dev/null 2>&1; then
    ok "auth JWKS"
  else
    bad "auth JWKS empty body"
  fi
else
  bad "auth JWKS ($code)"
fi

# Unauthenticated nearby must be 401
code="$(http_code "$API/parking/spots/nearby?latitude=41.0&longitude=29.0&radiusMeters=1000" -H "$CLIENT_HEADER")"
if [ "$code" = "401" ]; then ok "nearby requires auth ($code)"; else bad "nearby unauth expected 401 got $code"; fi

# Login
code="$(http_code "$API/auth/login" -H "$CLIENT_HEADER" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")"
if [ "$code" = "200" ]; then
  ACCESS="$(jq -r .accessToken /tmp/parkio-smoke-body.json)"
  REFRESH="$(jq -r .refreshToken /tmp/parkio-smoke-body.json)"
  if [ -n "$ACCESS" ] && [ "$ACCESS" != "null" ]; then
    ok "login"
  else
    bad "login missing accessToken"
    ACCESS=""
  fi
else
  bad "login ($code) — seed accounts with scripts/seed-real-e2e.sh if needed"
  ACCESS=""
  REFRESH=""
fi

if [ -n "${ACCESS:-}" ]; then
  AUTH="Authorization: Bearer $ACCESS"

  if [ -n "${REFRESH:-}" ] && [ "$REFRESH" != "null" ]; then
    code="$(http_code "$API/auth/refresh-token" -H "$CLIENT_HEADER" -H "Content-Type: application/json" \
      -d "{\"refreshToken\":\"$REFRESH\"}")"
    if [ "$code" = "200" ]; then
      ACCESS="$(jq -r .accessToken /tmp/parkio-smoke-body.json)"
      AUTH="Authorization: Bearer $ACCESS"
      ok "refresh"
    else
      bad "refresh ($code)"
    fi
  else
    bad "refresh skipped (no refresh token in login response)"
  fi

  code="$(http_code "$API/users/me" -H "$CLIENT_HEADER" -H "$AUTH")"
  if [ "$code" = "200" ]; then ok "user profile"; else bad "user profile ($code)"; fi

  code="$(http_code "$API/users/me/stats" -H "$CLIENT_HEADER" -H "$AUTH")"
  if [ "$code" = "200" ]; then ok "gamification/user stats"; else bad "user stats ($code)"; fi

  code="$(http_code "$API/parking/spots/nearby?latitude=41.0&longitude=29.0&radiusMeters=1000" \
    -H "$CLIENT_HEADER" -H "$AUTH")"
  if [ "$code" = "200" ]; then ok "parking nearby"; else bad "parking nearby ($code)"; fi

  code="$(http_code "$API/notifications/me" -H "$CLIENT_HEADER" -H "$AUTH")"
  if [ "$code" = "200" ]; then ok "notification list"; else bad "notification list ($code)"; fi

  code="$(http_code "$API/gamification/me/progress" -H "$CLIENT_HEADER" -H "$AUTH")"
  if [ "$code" = "200" ]; then ok "gamification progress"; else bad "gamification progress ($code)"; fi

  # Media readiness: probe the media-service container when compose env is available.
  if [ -n "${PARKIO_ENV_FILE:-}" ] && [ -f "${PARKIO_ENV_FILE}" ]; then
    if docker compose --env-file "$PARKIO_ENV_FILE" \
      -f docker/docker-compose.yml -f docker/docker-compose.apps.yml \
      exec -T media-service curl -fsS http://localhost:8084/actuator/health/readiness >/dev/null 2>&1; then
      ok "media readiness"
    else
      bad "media readiness"
    fi
  else
    echo "SKIP: media readiness (set PARKIO_ENV_FILE to probe via compose exec)"
  fi

  if [ "${PARKIO_SMART_RETURN_ENABLED:-false}" = "true" ] || [ "${PARKIO_SMART_RETURN_ENABLED:-0}" = "1" ]; then
    code="$(http_code "$API/users/me/smart-return" -H "$CLIENT_HEADER" -H "$AUTH")"
    if [ "$code" = "200" ]; then ok "smart return settings"; else bad "smart return ($code)"; fi
  else
    echo "SKIP: smart return (PARKIO_SMART_RETURN_ENABLED not set)"
  fi
fi

# Direct service access: on hosted-beta ports are not published (connection fails).
# On local apps overlay, parking requires gateway auth header (not a bare 200).
if [ "$EXPECT_DIRECT_BLOCKED" = "1" ]; then
  code="$(curl -sS -o /tmp/parkio-smoke-direct.json -w '%{http_code}' --connect-timeout 2 \
    "http://127.0.0.1:8083/api/v1/parking/spots/nearby?latitude=41.0&longitude=29.0&radiusMeters=1000" || echo "000")"
  if [ "$code" = "000" ] || [ "$code" = "403" ] || [ "$code" = "401" ]; then
    ok "direct service access blocked ($code)"
  else
    bad "direct service access unexpected ($code)"
  fi
else
  code="$(curl -sS -o /tmp/parkio-smoke-direct.json -w '%{http_code}' --connect-timeout 2 \
    "http://127.0.0.1:8083/api/v1/parking/spots/nearby?latitude=41.0&longitude=29.0&radiusMeters=1000" || echo "000")"
  if [ "$code" = "401" ] || [ "$code" = "403" ] || [ "$code" = "000" ]; then
    ok "direct parking without gateway auth rejected ($code)"
  else
    bad "direct parking without gateway auth got $code"
  fi
fi

echo "=== smoke summary: pass=$pass fail=$fail ==="
if [ "$fail" -ne 0 ]; then
  exit 1
fi
