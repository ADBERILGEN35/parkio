#!/usr/bin/env bash
# Post-cutover public invite-production smoke harness (PROD-DEPLOY-01B-03E-A1).
#
# Executable fail-closed acceptance checks for app/api/media after Caddy+TLS are
# live. Does not create users, invites, or synthetic media uploads.
#
# Usage:
#   PARKIO_PUBLIC_SMOKE_CONFIRM=1 ./scripts/public-invite-smoke.sh
#
# Optional:
#   PARKIO_PUBLIC_SMOKE_BASE_URL=https://api.parkio.dev
#   PARKIO_PUBLIC_WEB_URL=https://app.parkio.dev
#   PARKIO_PUBLIC_MEDIA_URL=https://media.parkio.dev
#   PARKIO_PUBLIC_SMOKE_PRESIGNED_URL=https://media.parkio.dev/... (read-only)
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ "${PARKIO_PUBLIC_SMOKE_CONFIRM:-}" != "1" ]; then
  echo "ERROR: public smoke requires PARKIO_PUBLIC_SMOKE_CONFIRM=1" >&2
  exit 2
fi

API_BASE="${PARKIO_PUBLIC_SMOKE_BASE_URL:-https://api.parkio.dev}"
WEB_BASE="${PARKIO_PUBLIC_WEB_URL:-https://app.parkio.dev}"
MEDIA_BASE="${PARKIO_PUBLIC_MEDIA_URL:-https://media.parkio.dev}"
PRESIGNED_URL="${PARKIO_PUBLIC_SMOKE_PRESIGNED_URL:-}"

pass=0
fail=0
ok() { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

http_code() {
  curl -sS -o /dev/null -w '%{http_code}' "$@"
}

fetch_headers() {
  curl -sS -D - -o /dev/null "$@"
}

verify_tls_host() {
  local host="$1"
  if ! echo | openssl s_client -connect "${host}:443" -servername "$host" 2>/dev/null \
      | openssl x509 -noout -subject -issuer -dates >/dev/null 2>&1; then
    bad "TLS certificate invalid for $host"
    return 1
  fi
  local subject issuer
  subject="$(echo | openssl s_client -connect "${host}:443" -servername "$host" 2>/dev/null \
    | openssl x509 -noout -subject 2>/dev/null || true)"
  issuer="$(echo | openssl s_client -connect "${host}:443" -servername "$host" 2>/dev/null \
    | openssl x509 -noout -issuer 2>/dev/null || true)"
  if [ -z "$subject" ] || [ -z "$issuer" ]; then
    bad "could not read certificate metadata for $host"
    return 1
  fi
  case "$issuer" in
    *Let's\ Encrypt*|*letsencrypt*|*R3*|*E1*|*R10*|*R11*) ok "public CA issuer for $host" ;;
    *) bad "unexpected certificate issuer for $host: $issuer"; return 1 ;;
  esac
  case "$subject" in
    *"$host"*) ok "certificate subject matches $host" ;;
    *) bad "certificate subject does not match $host: $subject"; return 1 ;;
  esac
}

echo "=== invite-production public smoke ==="

for host in app.parkio.dev api.parkio.dev media.parkio.dev; do
  verify_tls_host "$host" || true
done

# Web
web_status="$(http_code "$WEB_BASE/")"
if [ "$web_status" = "200" ]; then
  ok "web root returns 200"
else
  bad "web root returned $web_status"
fi

web_headers="$(fetch_headers "$WEB_BASE/")"
case "$web_headers" in
  *"strict-transport-security: max-age=86400"*)
    case "$web_headers" in
      *includeSubDomains*|*preload*) bad "web HSTS must not include includeSubDomains or preload" ;;
      *) ok "web HSTS is max-age=86400 without includeSubDomains/preload" ;;
    esac
    ;;
  *) bad "web HSTS header missing or incorrect" ;;
esac

# API
health_status="$(http_code "$API_BASE/actuator/health")"
if [ "$health_status" = "200" ]; then
  ok "api /actuator/health returns 200"
else
  bad "api /actuator/health returned $health_status"
fi

for path in /actuator/info /actuator/env /actuator/configprops; do
  status="$(http_code "$API_BASE$path")"
  if [ "$status" = "200" ]; then
    bad "api $path must be blocked on public edge (got 200)"
  else
    ok "api $path blocked with $status"
  fi
done

for path in /actuator/info /actuator/env /actuator/configprops; do
  spoof_status="$(curl -sS -o /dev/null -w '%{http_code}' -H 'X-Forwarded-For: 127.0.0.1' -H 'X-Real-IP: 127.0.0.1' -H 'Forwarded: for=127.0.0.1' "$API_BASE$path")"
  if [ "$spoof_status" = "200" ]; then
    bad "api $path must stay blocked with spoofed forwarding headers (got 200)"
  else
    ok "api $path blocked with spoofed forwarding headers ($spoof_status)"
  fi
done

login_status="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$API_BASE/api/v1/auth/login" -H 'Content-Type: application/json' -d '{"email":"public-smoke-negative@parkio.invalid","password":"Not-A-Real-Password"}')"
case "$login_status" in
  400|401) ok "negative login rejected with $login_status" ;;
  *) bad "negative login returned unexpected $login_status" ;;
esac

auth_required_status="$(http_code "$API_BASE/api/v1/parking/spots")"
if [ "$auth_required_status" = "401" ]; then
  ok "auth-required endpoint returns 401"
else
  bad "auth-required endpoint returned $auth_required_status"
fi

# Media
media_status="$(http_code "$MEDIA_BASE/")"
case "$media_status" in
  403|404) ok "media anonymous root blocked with $media_status" ;;
  *) bad "media anonymous root returned $media_status" ;;
esac

console_status="$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 5 "$MEDIA_BASE:9001/" 2>/dev/null || echo 000)"
if [ "$console_status" = "000" ] || [ "$console_status" = "403" ] || [ "$console_status" = "404" ]; then
  ok "MinIO console :9001 not publicly exposed"
else
  bad "unexpected response from media :9001 ($console_status)"
fi

if [ -n "$PRESIGNED_URL" ]; then
  presigned_status="$(http_code "$PRESIGNED_URL")"
  case "$presigned_status" in
    200|206) ok "presigned media fixture readable ($presigned_status)" ;;
    *) bad "presigned media fixture returned $presigned_status" ;;
  esac
else
  echo "SKIP: presigned media fixture not provided"
fi

# HTTP -> HTTPS redirects
for url in "http://app.parkio.dev/" "http://api.parkio.dev/actuator/health" "http://media.parkio.dev/"; do
  redirect="$(curl -sS -o /dev/null -w '%{http_code} %{redirect_url}' "$url" || true)"
  code="${redirect%% *}"
  target="${redirect#* }"
  if [ "$code" = "301" ] || [ "$code" = "308" ]; then
  case "$target" in
    https://*) ok "redirect $url -> $target" ;;
    *) bad "redirect for $url missing https target ($target)" ;;
  esac
  else
    bad "expected redirect for $url, got $code"
  fi
done

echo "public_invite_smoke_passed=$pass"
echo "public_invite_smoke_failed=$fail"
if [ "$fail" -ne 0 ]; then
  echo "public_invite_smoke=FAIL"
  exit 1
fi

echo "public_invite_smoke=PASS"
