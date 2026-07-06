#!/bin/sh
# Parkio — hosted-beta deployment PREFLIGHT (R-005).
#
# Validates the deployment env file BEFORE any image is built or container
# started. Catches every known operator/configuration mistake: placeholder
# secrets, local-dev value leakage, non-HTTPS/localhost domains, disabled
# security toggles, logging/noop providers, and unsafe compose defaults that
# an omitted variable would silently activate.
#
# Usage (from repo root):
#   PARKIO_ENV_FILE=docker/.env ./scripts/preflight-hosted-beta.sh
#   ./scripts/preflight-hosted-beta.sh --env-file docker/.env
#   ./scripts/preflight-hosted-beta.sh --env-file docker/.env --skip-compose
#
# Options:
#   --env-file <path>   env file to validate (default: $PARKIO_ENV_FILE or docker/.env)
#   --skip-compose      skip the docker-compose render (no docker needed; used by
#                       fixture tests and by deploy-hosted-beta.sh, which renders
#                       the compose config itself immediately afterwards)
#
# Intentional-deviation overrides (each prints a WARN so the decision is visible):
#   PARKIO_PREFLIGHT_ALLOW_PROVIDER_OVERRIDE=1   allow EMAIL/PUSH provider != resend/expo
#                                                (must be documented in the deploy notes)
#   PARKIO_PREFLIGHT_ALLOW_NO_ALERT_WEBHOOK=1    allow empty Slack/alert webhook
#                                                (alerts visible in Alertmanager only)
#
# All checks run (failures are collected, not aborted on first) so the operator
# gets the complete fix list in one pass; the script then exits non-zero.
# Exit codes: 0 = PASS, 1 = validation failures, 2 = usage/file error.
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENV_FILE="${PARKIO_ENV_FILE:-docker/.env}"
SKIP_COMPOSE=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --skip-compose) SKIP_COMPOSE=1; shift ;;
    -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

cd "$ROOT" || exit 2

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: env file not found: $ENV_FILE" >&2
  echo "       Copy docker/.env.hosted-beta.example to docker/.env and fill in real values." >&2
  exit 2
fi

FAILURES=0
WARNINGS=0
CHECKS=0
CATEGORY=""

category() {
  CATEGORY="$1"
  echo ""
  echo "[$1]"
}

# fail <subject> <message> [fix-hint]  (printf: hints may contain backslashes)
fail() {
  FAILURES=$((FAILURES + 1))
  printf '  FAIL %s: %s\n' "$1" "$2"
  if [ "${3:-}" != "" ]; then
    printf '       fix: %s\n' "$3"
  fi
}

warn() {
  WARNINGS=$((WARNINGS + 1))
  printf '  WARN %s: %s\n' "$1" "$2"
}

ok() {
  CHECKS=$((CHECKS + 1))
}

# env_get KEY -> value of the LAST occurrence in the env file (docker compose
# --env-file semantics), surrounding single/double quotes stripped. Empty if unset.
env_get() {
  grep "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- \
    | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'\$/\1/"
}

env_has() {
  grep -q "^$1=..*" "$ENV_FILE"
}

# is_placeholder VALUE -> 0 if the value looks like an unreplaced template value.
is_placeholder() {
  echo "$1" | grep -qiE 'CHANGE_ME|CHANGEME|CHANGE-ME|PLACEHOLDER|REPLACE_ME|REPLACEME|YOUR_|<[A-Za-z_-]+>|DUMMY|SAMPLE_|TODO|FIXME|00000000-0000-0000-0000-000000000000'
}

# Known committed local-dev values that must never reach a hosted environment.
is_local_dev_value() {
  case "$1" in
    *_local_dev_pw|local-dev-only-*|MkU3OEVBNTcwNTJENDM2Qk|admin|password|secret|changeit) return 0 ;;
  esac
  return 1
}

# require_secret VAR MIN_LEN GENERATE_HINT — non-empty, no placeholder,
# no local-dev leak, minimum length.
require_secret() {
  rs_var="$1"; rs_min="$2"; rs_hint="$3"
  rs_val=$(env_get "$rs_var")
  if [ -z "$rs_val" ]; then
    fail "$rs_var" "required secret is empty or unset" "$rs_hint"
    return 1
  fi
  if is_placeholder "$rs_val"; then
    fail "$rs_var" "still a placeholder value ('$(echo "$rs_val" | cut -c1-40)')" "$rs_hint"
    return 1
  fi
  if is_local_dev_value "$rs_val"; then
    fail "$rs_var" "is a committed LOCAL-DEV value — never reuse local dev secrets on a hosted environment" "$rs_hint"
    return 1
  fi
  if [ "${#rs_val}" -lt "$rs_min" ]; then
    fail "$rs_var" "too short (${#rs_val} chars; minimum $rs_min)" "$rs_hint"
    return 1
  fi
  ok
  return 0
}

# is_banned_host HOST -> 0 if host is a non-public / placeholder host.
is_banned_host() {
  case "$1" in
    localhost|localhost:*|127.0.0.1|127.0.0.1:*|0.0.0.0|0.0.0.0:*|10.0.2.2|10.0.2.2:*) return 0 ;;
    *.local|*.local:*) return 0 ;;
    example.com|*.example.com|example.com:*|*.example.com:*|example.org|*.example.org) return 0 ;;
  esac
  return 1
}

# require_public_hostname VAR — bare DNS hostname: no scheme, no banned host, has a dot.
require_public_hostname() {
  rh_var="$1"
  rh_val=$(env_get "$rh_var")
  if [ -z "$rh_val" ]; then
    fail "$rh_var" "required domain is empty or unset" "set the real public hostname (DNS A/AAAA -> this VPS)"
    return 1
  fi
  case "$rh_val" in
    http://*|https://*)
      fail "$rh_var" "must be a bare hostname, not a URL ('$rh_val')" "remove the scheme"
      return 1 ;;
  esac
  if is_banned_host "$rh_val"; then
    fail "$rh_var" "'$rh_val' is a localhost/placeholder host" "set the real public hostname (no localhost, 127.0.0.1, 10.0.2.2, *.local, example.com)"
    return 1
  fi
  case "$rh_val" in
    *.*) ok; return 0 ;;
    *) fail "$rh_var" "'$rh_val' is not a fully-qualified hostname" "set the real public hostname"; return 1 ;;
  esac
}

# require_https_url VAR — https:// URL whose host passes is_banned_host.
require_https_url() {
  ru_var="$1"
  ru_val=$(env_get "$ru_var")
  if [ -z "$ru_val" ]; then
    fail "$ru_var" "required URL is empty or unset" "set an https:// URL on the real public domain"
    return 1
  fi
  case "$ru_val" in
    https://*) : ;;
    *)
      fail "$ru_var" "must be HTTPS ('$ru_val')" "hosted beta is TLS-only; use https://"
      return 1 ;;
  esac
  ru_host=$(echo "$ru_val" | sed -e 's|^https://||' -e 's|[/?].*$||')
  if is_banned_host "$ru_host"; then
    fail "$ru_var" "host '$ru_host' is a localhost/placeholder host" "point it at the real public domain"
    return 1
  fi
  ok
  return 0
}

echo "=== Parkio hosted-beta preflight (R-005) ==="
echo "envFile=$ENV_FILE"

# --------------------------------------------------------------------------- #
category "Secrets"
# --------------------------------------------------------------------------- #

# Whole-file sweep: any surviving CHANGE_ME anywhere (including commented-out
# lines an operator uncommented halfway) is an unfinished template.
CHANGE_ME_LINES=$(grep -n "CHANGE_ME" "$ENV_FILE" | grep -v '^[0-9]*:#' || true)
if [ -n "$CHANGE_ME_LINES" ]; then
  fail "env file" "CHANGE_ME placeholder(s) remain:" "replace every CHANGE_ME with a real value"
  echo "$CHANGE_ME_LINES" | sed 's/^/       line /'
else
  ok
fi

# JWT signing key (RS256, PKCS#8). Services fail closed when blank, but we fail
# here first with a clearer message.
JWT_PEM=$(env_get PARKIO_JWT_PRIVATE_KEY_PEM)
if [ -z "$JWT_PEM" ]; then
  fail "PARKIO_JWT_PRIVATE_KEY_PEM" "JWT private key is empty" "openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 — store as one double-quoted line with \\n escapes"
elif is_placeholder "$JWT_PEM"; then
  fail "PARKIO_JWT_PRIVATE_KEY_PEM" "JWT private key is a placeholder" "generate a real PKCS#8 key (see .env.hosted-beta.example)"
else
  case "$JWT_PEM" in
    *"BEGIN PRIVATE KEY"*|*"BEGIN RSA PRIVATE KEY"*) ok ;;
    *) fail "PARKIO_JWT_PRIVATE_KEY_PEM" "does not look like a PEM private key (missing 'BEGIN ... PRIVATE KEY')" "store the PKCS#8 PEM as a single line with \\n escapes" ;;
  esac
fi

for v in PARKIO_JWT_KEY_ID PARKIO_JWT_ISSUER PARKIO_JWT_AUDIENCE; do
  if [ -z "$(env_get "$v")" ]; then
    fail "$v" "must be set explicitly per environment" "see docker/.env.hosted-beta.example"
  else
    ok
  fi
done

# Rotation list must only ever contain PUBLIC keys.
ADD_KEYS=$(env_get PARKIO_JWT_ADDITIONAL_PUBLIC_KEYS_JSON)
case "$ADD_KEYS" in
  *"PRIVATE KEY"*) fail "PARKIO_JWT_ADDITIONAL_PUBLIC_KEYS_JSON" "contains a PRIVATE key — this list is served via JWKS" "derive the public key: openssl pkey -in old.pem -pubout" ;;
  *) ok ;;
esac

require_secret PARKIO_GATEWAY_INTERNAL_SECRET 32 "openssl rand -base64 48"

for svc in AUTH USER PARKING MEDIA GAMIFICATION NOTIFICATION MODERATION ANALYTICS AIVALIDATION; do
  require_secret "POSTGRES_${svc}_PASSWORD" 16 "openssl rand -base64 24"
done

require_secret REDIS_PASSWORD 16 "openssl rand -base64 24 (required in hosted beta; empty disables Redis auth)"
require_secret MINIO_ROOT_PASSWORD 16 "openssl rand -base64 24"
require_secret GRAFANA_ADMIN_PASSWORD 12 "openssl rand -base64 24"

KAFKA_ID=$(env_get KAFKA_CLUSTER_ID)
if [ -z "$KAFKA_ID" ] || is_placeholder "$KAFKA_ID"; then
  fail "KAFKA_CLUSTER_ID" "missing or placeholder" "docker run --rm confluentinc/cp-kafka:7.7.1 kafka-storage random-uuid"
elif [ "$KAFKA_ID" = "MkU3OEVBNTcwNTJENDM2Qk" ]; then
  fail "KAFKA_CLUSTER_ID" "is the committed local-dev example cluster id" "generate a fresh one: docker run --rm confluentinc/cp-kafka:7.7.1 kafka-storage random-uuid"
elif [ "${#KAFKA_ID}" -ne 22 ]; then
  fail "KAFKA_CLUSTER_ID" "must be a 22-char base64 UUID (got ${#KAFKA_ID} chars)" "kafka-storage random-uuid"
else
  ok
fi

RESEND_KEY=$(env_get PARKIO_RESEND_API_KEY)
if [ -z "$RESEND_KEY" ] || is_placeholder "$RESEND_KEY"; then
  fail "PARKIO_RESEND_API_KEY" "missing or placeholder" "create an API key at resend.com (starts with 're_')"
else
  case "$RESEND_KEY" in
    re_*) ok ;;
    *) fail "PARKIO_RESEND_API_KEY" "does not look like a Resend key (expected 're_' prefix)" "copy the real key from the Resend dashboard" ;;
  esac
fi

EXPO_TOKEN=$(env_get PARKIO_EXPO_ACCESS_TOKEN)
if [ -z "$EXPO_TOKEN" ] || is_placeholder "$EXPO_TOKEN"; then
  fail "PARKIO_EXPO_ACCESS_TOKEN" "missing or placeholder" "create an access token at expo.dev (Account settings -> Access tokens)"
else
  ok
fi

SLACK_URL=$(env_get PARKIO_ALERT_SLACK_WEBHOOK_URL)
GENERIC_URL=$(env_get PARKIO_ALERT_WEBHOOK_URL)
if is_placeholder "$SLACK_URL"; then
  fail "PARKIO_ALERT_SLACK_WEBHOOK_URL" "is a placeholder" "paste the real Slack incoming-webhook URL, or clear it and set PARKIO_PREFLIGHT_ALLOW_NO_ALERT_WEBHOOK=1 to run alert-silent"
elif [ -n "$SLACK_URL" ]; then
  case "$SLACK_URL" in
    https://hooks.slack.com/*) ok ;;
    *) fail "PARKIO_ALERT_SLACK_WEBHOOK_URL" "not a Slack webhook URL ('$(echo "$SLACK_URL" | cut -c1-40)...')" "must start with https://hooks.slack.com/" ;;
  esac
elif [ -n "$GENERIC_URL" ]; then
  case "$GENERIC_URL" in
    https://*) ok ;;
    *) fail "PARKIO_ALERT_WEBHOOK_URL" "must be HTTPS" "use an https:// webhook receiver" ;;
  esac
elif [ "${PARKIO_PREFLIGHT_ALLOW_NO_ALERT_WEBHOOK:-0}" = "1" ]; then
  warn "alerting" "no outbound alert webhook configured (explicitly allowed) — alerts only visible in Alertmanager via SSH tunnel"
else
  fail "PARKIO_ALERT_SLACK_WEBHOOK_URL" "no alert webhook configured — critical alerts would go nowhere" "set the Slack webhook (or PARKIO_ALERT_WEBHOOK_URL), or acknowledge with PARKIO_PREFLIGHT_ALLOW_NO_ALERT_WEBHOOK=1"
fi

# --------------------------------------------------------------------------- #
category "Domains & URLs"
# --------------------------------------------------------------------------- #

require_public_hostname PARKIO_DOMAIN
require_public_hostname PARKIO_WEB_DOMAIN
require_public_hostname PARKIO_MEDIA_DOMAIN

ACME=$(env_get PARKIO_ACME_EMAIL)
case "$ACME" in
  "") fail "PARKIO_ACME_EMAIL" "required for Let's Encrypt registration" "set a monitored mailbox" ;;
  *@example.com|*@example.org) fail "PARKIO_ACME_EMAIL" "'$ACME' is a placeholder address" "set a monitored mailbox" ;;
  *@*.*) ok ;;
  *) fail "PARKIO_ACME_EMAIL" "'$ACME' is not an email address" "set a monitored mailbox" ;;
esac

if require_https_url VITE_API_BASE_URL; then
  API_HOST=$(env_get VITE_API_BASE_URL | sed -e 's|^https://||' -e 's|[/?].*$||')
  DOMAIN=$(env_get PARKIO_DOMAIN)
  if [ -n "$DOMAIN" ] && [ "$API_HOST" != "$DOMAIN" ]; then
    fail "VITE_API_BASE_URL" "host '$API_HOST' does not match PARKIO_DOMAIN '$DOMAIN'" "the SPA must call the public API domain: https://\$PARKIO_DOMAIN/api/v1"
  else
    ok
  fi
fi

CORS=$(env_get PARKIO_CORS_ALLOWED_ORIGINS)
if [ -z "$CORS" ]; then
  fail "PARKIO_CORS_ALLOWED_ORIGINS" "empty — the hosted SPA could not call the API" "set https://\$PARKIO_WEB_DOMAIN"
elif [ "$CORS" = "*" ]; then
  fail "PARKIO_CORS_ALLOWED_ORIGINS" "wildcard '*' is forbidden" "explicit allow-list of https origins only"
else
  CORS_BAD=0
  OLD_IFS=$IFS; IFS=,
  for origin in $CORS; do
    origin=$(echo "$origin" | sed 's/^ *//;s/ *$//')
    case "$origin" in
      https://*)
        o_host=$(echo "$origin" | sed -e 's|^https://||' -e 's|[/?].*$||')
        if is_banned_host "$o_host"; then
          fail "PARKIO_CORS_ALLOWED_ORIGINS" "origin '$origin' is a localhost/placeholder host" "use the real https SPA origin"
          CORS_BAD=1
        fi ;;
      *)
        fail "PARKIO_CORS_ALLOWED_ORIGINS" "origin '$origin' is not https" "hosted beta is TLS-only"
        CORS_BAD=1 ;;
    esac
  done
  IFS=$OLD_IFS
  [ "$CORS_BAD" -eq 0 ] && ok
fi

if [ "$(env_get PARKIO_CORS_ALLOW_CREDENTIALS)" != "true" ]; then
  fail "PARKIO_CORS_ALLOW_CREDENTIALS" "must be 'true' — the SPA sends credentialed auth requests (refresh cookie)" "set PARKIO_CORS_ALLOW_CREDENTIALS=true"
else
  ok
fi

# Presigned GET URLs embed this host in the SigV4 signature; it must be exactly
# the public media origin or every media URL 403s in the browser.
PUB_MEDIA=$(env_get PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT)
MEDIA_DOMAIN=$(env_get PARKIO_MEDIA_DOMAIN)
if [ -z "$PUB_MEDIA" ]; then
  fail "PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT" "unset — compose default is http://localhost:9000, which breaks all media in the browser" "set https://\$PARKIO_MEDIA_DOMAIN (literal value)"
elif [ -n "$MEDIA_DOMAIN" ] && [ "$PUB_MEDIA" != "https://$MEDIA_DOMAIN" ]; then
  fail "PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT" "'$PUB_MEDIA' must equal https://$MEDIA_DOMAIN exactly (SigV4 signs the Host header)" "set PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT=https://$MEDIA_DOMAIN"
else
  ok
fi

for v in PARKIO_EMAIL_FROM PARKIO_EMAIL_REPLY_TO; do
  ev=$(env_get "$v")
  case "$ev" in
    "") fail "$v" "required for transactional email" "set a real sender/reply-to on your verified domain" ;;
    *example.com*|*example.org*) fail "$v" "'$ev' uses a placeholder domain" "use your verified sending domain" ;;
    *) ok ;;
  esac
done

MAP_SRC=$(env_get PARKIO_MAP_CONNECT_SRC)
if [ -n "$MAP_SRC" ]; then
  case "$MAP_SRC" in
    https://*) ok ;;
    *) fail "PARKIO_MAP_CONNECT_SRC" "must be an https origin ('$MAP_SRC')" "e.g. https://api.maptiler.com" ;;
  esac
fi

# Trusted proxies feed the gateway's real-client-IP logic for rate limiting.
# Public ranges here would let anyone spoof their rate-limit identity.
PROXIES=$(env_get PARKIO_TRUSTED_PROXIES)
if [ -n "$PROXIES" ]; then
  PROXY_BAD=0
  OLD_IFS=$IFS; IFS=,
  for cidr in $PROXIES; do
    cidr=$(echo "$cidr" | sed 's/^ *//;s/ *$//')
    case "$cidr" in
      10.*|127.*|192.168.*|172.16.*|172.17.*|172.18.*|172.19.*|172.2?.*|172.30.*|172.31.*) : ;;
      *)
        fail "PARKIO_TRUSTED_PROXIES" "'$cidr' is not a private (RFC1918/loopback) range" "only Docker/proxy-internal ranges may be trusted"
        PROXY_BAD=1 ;;
    esac
  done
  IFS=$OLD_IFS
  [ "$PROXY_BAD" -eq 0 ] && ok
fi

# --------------------------------------------------------------------------- #
category "Providers"
# --------------------------------------------------------------------------- #

ALLOW_PROVIDER="${PARKIO_PREFLIGHT_ALLOW_PROVIDER_OVERRIDE:-0}"

EMAIL_PROVIDER=$(env_get PARKIO_EMAIL_PROVIDER)
if [ "$EMAIL_PROVIDER" = "resend" ]; then
  ok
elif [ "$ALLOW_PROVIDER" = "1" ] && [ -n "$EMAIL_PROVIDER" ] && [ "$EMAIL_PROVIDER" != "logging" ]; then
  warn "PARKIO_EMAIL_PROVIDER" "'$EMAIL_PROVIDER' (non-default) explicitly allowed — document the rationale in the deploy notes"
elif [ "$EMAIL_PROVIDER" = "logging" ] || [ -z "$EMAIL_PROVIDER" ]; then
  fail "PARKIO_EMAIL_PROVIDER" "'${EMAIL_PROVIDER:-<unset -> compose default 'logging'>}' — the logging provider prints emails to logs instead of sending them; beta users could never verify accounts or reset passwords" "set PARKIO_EMAIL_PROVIDER=resend"
else
  fail "PARKIO_EMAIL_PROVIDER" "'$EMAIL_PROVIDER' is not the supported hosted-beta provider" "set PARKIO_EMAIL_PROVIDER=resend (or acknowledge an intentional provider with PARKIO_PREFLIGHT_ALLOW_PROVIDER_OVERRIDE=1)"
fi

PUSH_PROVIDER=$(env_get PARKIO_PUSH_DELIVERY_PROVIDER)
if [ "$PUSH_PROVIDER" = "expo" ]; then
  ok
elif [ "$ALLOW_PROVIDER" = "1" ] && [ -n "$PUSH_PROVIDER" ] && [ "$PUSH_PROVIDER" != "noop" ]; then
  warn "PARKIO_PUSH_DELIVERY_PROVIDER" "'$PUSH_PROVIDER' (non-default) explicitly allowed — document the rationale in the deploy notes"
elif [ "$PUSH_PROVIDER" = "noop" ] || [ -z "$PUSH_PROVIDER" ]; then
  fail "PARKIO_PUSH_DELIVERY_PROVIDER" "'${PUSH_PROVIDER:-<unset -> compose default 'noop'>}' — the noop provider silently drops every push notification" "set PARKIO_PUSH_DELIVERY_PROVIDER=expo"
else
  fail "PARKIO_PUSH_DELIVERY_PROVIDER" "'$PUSH_PROVIDER' is not the supported hosted-beta provider" "set PARKIO_PUSH_DELIVERY_PROVIDER=expo (or acknowledge with PARKIO_PREFLIGHT_ALLOW_PROVIDER_OVERRIDE=1)"
fi

# --------------------------------------------------------------------------- #
category "Deployment safety"
# --------------------------------------------------------------------------- #

# Compose default is TRUE — an env file that omits this ships public API docs.
if [ "$(env_get PARKIO_OPENAPI_ENABLED)" != "false" ]; then
  fail "PARKIO_OPENAPI_ENABLED" "must be explicitly 'false' (compose default is true — omitting it exposes OpenAPI/Swagger publicly)" "set PARKIO_OPENAPI_ENABLED=false"
else
  ok
fi

for v in PARKIO_EMAIL_VERIFICATION_LOG_TOKEN PARKIO_PASSWORD_RESET_LOG_TOKEN; do
  tv=$(env_get "$v")
  if [ -n "$tv" ] && [ "$tv" != "false" ]; then
    fail "$v" "'$tv' — verification/reset tokens must NEVER be written to logs outside local dev" "set $v=false"
  else
    ok
  fi
done

TH=$(env_get PARKIO_SMART_RETURN_TEST_HOOKS_ENABLED)
if [ -n "$TH" ] && [ "$TH" != "false" ]; then
  fail "PARKIO_SMART_RETURN_TEST_HOOKS_ENABLED" "'$TH' — test hooks must stay disabled on any shared host" "set PARKIO_SMART_RETURN_TEST_HOOKS_ENABLED=false"
else
  ok
fi

SCAN=$(env_get PARKIO_MEDIA_SCANNER_ENABLED)
if [ -n "$SCAN" ] && [ "$SCAN" != "true" ]; then
  fail "PARKIO_MEDIA_SCANNER_ENABLED" "'$SCAN' — disabling ClamAV serves unscanned user uploads" "set PARKIO_MEDIA_SCANNER_ENABLED=true"
else
  ok
fi

ENVIRONMENT=$(env_get PARKIO_ENVIRONMENT)
if [ "$ENVIRONMENT" != "hosted-beta" ]; then
  fail "PARKIO_ENVIRONMENT" "'${ENVIRONMENT:-<unset -> compose default 'local'>}' — observability labels and alert routing key off this" "set PARKIO_ENVIRONMENT=hosted-beta"
else
  ok
fi

APP_ENV=$(env_get VITE_APP_ENV)
if [ -n "$APP_ENV" ] && [ "$APP_ENV" != "hosted-beta" ]; then
  fail "VITE_APP_ENV" "'$APP_ENV' — the web build must be a hosted-beta build" "set VITE_APP_ENV=hosted-beta (or leave unset; overlay default)"
else
  ok
fi

PROFILES=$(env_get SPRING_PROFILES_ACTIVE)
case "$PROFILES" in
  "") ok ;;
  *dev*|*local*|*test*)
    fail "SPRING_PROFILES_ACTIVE" "'$PROFILES' — dev/local/test profiles must not run on a hosted environment" "remove SPRING_PROFILES_ACTIVE from the env file" ;;
  *) ok ;;
esac

JTO=$(env_get JAVA_TOOL_OPTIONS)
case "$JTO" in
  *jdwp*|*Xdebug*)
    fail "JAVA_TOOL_OPTIONS" "contains a remote-debug agent (jdwp)" "remove debug agents from JAVA_TOOL_OPTIONS" ;;
  *) ok ;;
esac

# --------------------------------------------------------------------------- #
category "Compose"
# --------------------------------------------------------------------------- #

if [ "$SKIP_COMPOSE" -eq 1 ]; then
  echo "  SKIP compose render (--skip-compose)"
elif [ "$FAILURES" -gt 0 ]; then
  echo "  SKIP compose render (fix the failures above first)"
else
  if PARKIO_ENV_FILE="$ENV_FILE" "$ROOT/scripts/validate-hosted-beta-compose.sh" >/dev/null 2>&1; then
    echo "  PASS compose config renders (validate-hosted-beta-compose.sh)"
    ok
  else
    fail "compose" "validate-hosted-beta-compose.sh failed" "run: PARKIO_ENV_FILE=$ENV_FILE ./scripts/validate-hosted-beta-compose.sh"
  fi
fi

# --------------------------------------------------------------------------- #
echo ""
if [ "$FAILURES" -gt 0 ]; then
  echo "=== PREFLIGHT: FAIL — $FAILURES failure(s), $WARNINGS warning(s), $CHECKS check(s) passed ==="
  echo "Deployment is blocked. Fix every FAIL above, then re-run:"
  echo "  PARKIO_ENV_FILE=$ENV_FILE ./scripts/preflight-hosted-beta.sh"
  exit 1
fi
echo "=== PREFLIGHT: PASS — $CHECKS check(s) passed, $WARNINGS warning(s) ==="
exit 0
