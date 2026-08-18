#!/usr/bin/env bash
#
# Parkio — hosted-beta smoke checks against a running gateway.
#
# Usage:
#   PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta ./scripts/smoke-hosted-beta.sh
#
# Optional credentials (seeded accounts recommended):
#   PARKIO_REAL_USER_EMAIL / PARKIO_REAL_USER_PASSWORD
#
# Optional:
#   PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1  — expect direct :8083 access to fail (hosted-beta)
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Prefer Python-3 JSON helper; fail closed if python3 missing (jq is not required).
# shellcheck source=staging/lib/json-helper.sh
source "${SCRIPT_DIR}/staging/lib/json-helper.sh"
json_require_python
# Strict allowlist for the invite-production dark endpoint (PROD-DEPLOY-01A / D1).
# shellcheck source=lib/dark-gateway-url.sh
source "${SCRIPT_DIR}/lib/dark-gateway-url.sh"

PROFILE="${PARKIO_DEPLOYMENT_PROFILE:-hosted-beta}"
case "$PROFILE" in
  azure-hosted-beta) DEFAULT_GATEWAY_URL="https://api.parkio.dev" ;;
  hosted-beta) DEFAULT_GATEWAY_URL="http://127.0.0.1:8080" ;;
  invite-production) DEFAULT_GATEWAY_URL="$PARKIO_DARK_GATEWAY_ALLOWED_URL" ;;
  *) echo "ERROR: unsupported PARKIO_DEPLOYMENT_PROFILE='$PROFILE'" >&2; exit 2 ;;
esac
GATEWAY_URL="${PARKIO_GATEWAY_URL:-$DEFAULT_GATEWAY_URL}"
if [ "$PROFILE" = "invite-production" ]; then
  # Fail closed on anything but the single published dark endpoint. This is what
  # stops smoke from silently accepting a public Parkio host (which resolves to
  # the hosted-beta VM) and reporting a false green.
  parkio_validate_dark_gateway_url "$GATEWAY_URL" || exit 2
fi
if [ "$PROFILE" = "azure-hosted-beta" ] && [ "$GATEWAY_URL" != "https://api.parkio.dev" ]; then
  echo "ERROR: Azure hosted-beta smoke must target https://api.parkio.dev" >&2
  exit 2
fi
API="$GATEWAY_URL/api/v1"
EMAIL="${PARKIO_REAL_USER_EMAIL:-user@real-e2e.parkio.local}"
PASSWORD="${PARKIO_REAL_USER_PASSWORD:-StrongParkio123}"
EXPECT_DIRECT_BLOCKED="${PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED:-0}"
CLIENT_HEADER="X-Parkio-Client: mobile"
SMOKE_BODY="${TMPDIR:-/tmp}/parkio-smoke-body.$$.json"
SMOKE_DIRECT="${TMPDIR:-/tmp}/parkio-smoke-direct.$$.json"
SMOKE_HEADERS="${TMPDIR:-/tmp}/parkio-smoke-headers.$$.txt"

cleanup() {
  rm -f -- "$SMOKE_BODY" "$SMOKE_DIRECT" "$SMOKE_HEADERS"
  ACCESS=""
  REFRESH=""
  AUTH=""
}
trap cleanup EXIT HUP INT TERM

pass=0
fail=0

ok() { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

# `--max-redirs 0` is belt-and-braces: no call here passes -L, so curl already
# refuses to follow. Stating it explicitly makes "smoke never leaves the host it
# was pointed at" a property a regression test can assert rather than a habit.
http_code() {
  local url="$1"
  shift
  curl -sS --max-redirs 0 -D "$SMOKE_HEADERS" -o "$SMOKE_BODY" -w '%{http_code}' "$@" "$url" || echo "000"
}

# Last Location: header seen by http_code (empty when the response was not a
# redirect). Used by the invite-production guard below.
last_location() {
  [ -f "$SMOKE_HEADERS" ] || return 0
  tr -d '\r' < "$SMOKE_HEADERS" | awk 'tolower($1) == "location:" { print $2 }' | tail -1
}

echo "=== Parkio smoke ($GATEWAY_URL) ==="

# --------------------------------------------------------------------------- #
# Invite-production dark runtime identity (PROD-DEPLOY-01A / D1).              #
# The URL allowlist already makes it impossible to point at another host, but  #
# prove positively that the process answering on the dark endpoint really is   #
# THIS invite-production deployment before trusting any later assertion.       #
# --------------------------------------------------------------------------- #
if [ "$PROFILE" = "invite-production" ]; then
  echo "=== invite-production dark runtime identity ==="
  code="$(http_code "$GATEWAY_URL/actuator/info" -H "$CLIENT_HEADER")"

  if ! parkio_assert_dark_redirect_target "$(last_location)"; then
    bad "dark gateway identity redirected off ${PARKIO_DARK_GATEWAY_ALLOWED_URL}"
  elif [ "$code" != "200" ]; then
    bad "dark runtime identity unavailable (/actuator/info -> $code)"
  else
    runtime_env="$(json_get "$SMOKE_BODY" deployment.environment)"
    runtime_sha="$(json_get "$SMOKE_BODY" deployment.gitSha)"
    if [ "$runtime_env" != "invite-production" ]; then
      bad "dark runtime environment is '${runtime_env:-<absent>}', expected invite-production"
    else
      ok "dark runtime environment (invite-production)"
    fi
    if [ -n "${PARKIO_EXPECTED_GIT_SHA:-}" ]; then
      if [ "$runtime_sha" != "$PARKIO_EXPECTED_GIT_SHA" ]; then
        bad "dark runtime gitSha is '${runtime_sha:-<absent>}', expected ${PARKIO_EXPECTED_GIT_SHA}"
      else
        ok "dark runtime gitSha (${runtime_sha:0:12})"
      fi
    else
      echo "SKIP: dark runtime gitSha (PARKIO_EXPECTED_GIT_SHA not set)"
    fi
  fi
fi

# Gateway health (actuator may be on gateway root)
code="$(http_code "$GATEWAY_URL/actuator/health" -H "$CLIENT_HEADER")"
if [ "$code" = "200" ]; then ok "gateway health ($code)"; else bad "gateway health ($code)"; fi

code="$(http_code "$API/auth/.well-known/jwks.json" -H "$CLIENT_HEADER")"
if [ "$code" = "200" ]; then
  if json_assert_jwks "$SMOKE_BODY" >/dev/null 2>&1; then
    ok "auth JWKS"
  else
    bad "auth JWKS empty body"
  fi
else
  bad "auth JWKS ($code)"
fi

# Unauthenticated nearby must be 401
code="$(http_code "$API/parking/spots/nearby?lat=41.0&lng=29.0&radius=1000&limit=5" -H "$CLIENT_HEADER")"
if [ "$code" = "401" ]; then ok "nearby requires auth ($code)"; else bad "nearby unauth expected 401 got $code"; fi

# Login
code="$(http_code "$API/auth/login" -H "$CLIENT_HEADER" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")"
if [ "$code" = "200" ]; then
  ACCESS="$(json_get "$SMOKE_BODY" accessToken)"
  REFRESH="$(json_get "$SMOKE_BODY" refreshToken)"
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
      ACCESS="$(json_get "$SMOKE_BODY" accessToken)"
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

  code="$(http_code "$API/parking/spots/nearby?lat=41.0&lng=29.0&radius=1000&limit=5" \
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


  if [ "${PARKIO_SMOKE_PARKING_SESSION:-0}" = "1" ]; then
    echo "=== ParkingSession smoke (PARKIO_SMOKE_PARKING_SESSION=1) ==="
    if ! "$(cd "$(dirname "$0")" && pwd)/smoke-parking-session-hosted-beta.sh"; then
      bad "parking session smoke"
    else
      ok "parking session smoke"
    fi
  fi
# Direct service access: on hosted-beta ports are not published (connection fails).
# On local apps overlay, parking requires gateway auth header (not a bare 200).
if [ "$EXPECT_DIRECT_BLOCKED" = "1" ]; then
  code="$(curl -sS -o "$SMOKE_DIRECT" -w '%{http_code}' --connect-timeout 2 \
    "http://127.0.0.1:8083/api/v1/parking/spots/nearby?lat=41.0&lng=29.0&radius=1000&limit=5" || true)"
  if [ "$code" = "000" ] || [ "$code" = "403" ] || [ "$code" = "401" ]; then
    ok "direct service access blocked ($code)"
  else
    bad "direct service access unexpected ($code)"
  fi
else
  code="$(curl -sS -o "$SMOKE_DIRECT" -w '%{http_code}' --connect-timeout 2 \
    "http://127.0.0.1:8083/api/v1/parking/spots/nearby?lat=41.0&lng=29.0&radius=1000&limit=5" || true)"
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
