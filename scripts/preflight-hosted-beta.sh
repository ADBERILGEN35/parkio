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
#   ./scripts/preflight-hosted-beta.sh --env-file docker/.env --deployment-profile azure-hosted-beta
#   ./scripts/preflight-hosted-beta.sh --env-file docker/.env --skip-compose
#
# Options:
#   --env-file <path>   env file to validate (default: $PARKIO_ENV_FILE or docker/.env)
#   --deployment-profile <name>  hosted-beta, azure-hosted-beta, or invite-production
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
DEPLOYMENT_PROFILE="${PARKIO_DEPLOYMENT_PROFILE:-}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --deployment-profile) DEPLOYMENT_PROFILE="${2:-}"; shift 2 ;;
    --skip-compose) SKIP_COMPOSE=1; shift ;;
    -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

cd "$ROOT" || exit 2

parkio_preflight_require_cosign() {
  local base="${ROOT:-${PARKIO_ROOT:-.}}"
  local pin_file="$base/scripts/lib/cosign-pin.env"
  if [ ! -f "$pin_file" ]; then
    echo "ERROR: preflight: missing $pin_file" >&2
    return 1
  fi
  # shellcheck disable=SC1090
  # POSIX sh: use `.` (bash `source` is unavailable under #!/bin/sh)
  . "$pin_file"
  if ! command -v cosign >/dev/null 2>&1; then
    echo "ERROR: preflight: cosign not on PATH (install pinned $PARKIO_COSIGN_VERSION; see docs/operations/supply-chain-security.md)" >&2
    return 1
  fi
  local ver_out ver_ec parsed
  set +e
  ver_out="$(cosign version 2>&1)"
  ver_ec=$?
  set -e
  if [ "$ver_ec" -ne 0 ]; then
    echo "ERROR: preflight: cosign version failed (exit $ver_ec)" >&2
    printf '%s\n' "$ver_out" >&2
    return 1
  fi
  # Exact GitVersion field (not substring of the whole blob).
  parsed="$(printf '%s\n' "$ver_out" | sed -n 's/^[[:space:]]*GitVersion:[[:space:]]*//p' | head -n 1)"
  if [ -z "$parsed" ]; then
    echo "ERROR: preflight: cosign version output missing GitVersion field" >&2
    printf '%s\n' "$ver_out" >&2
    return 1
  fi
  if [ "$parsed" != "$PARKIO_COSIGN_VERSION" ]; then
    echo "ERROR: preflight: cosign version mismatch (have: $parsed; want: $PARKIO_COSIGN_VERSION)" >&2
    return 1
  fi
  echo "preflight: cosign $PARKIO_COSIGN_VERSION ok"
  return 0
}


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

if [ -z "$DEPLOYMENT_PROFILE" ]; then
  DEPLOYMENT_PROFILE=$(env_get PARKIO_DEPLOYMENT_PROFILE)
fi
DEPLOYMENT_PROFILE=${DEPLOYMENT_PROFILE:-hosted-beta}
case "$DEPLOYMENT_PROFILE" in
  hosted-beta|azure-hosted-beta|invite-production) ;;
  *)
    echo "ERROR: unsupported PARKIO_DEPLOYMENT_PROFILE='$DEPLOYMENT_PROFILE'" >&2
    exit 2
    ;;
esac
export PARKIO_DEPLOYMENT_PROFILE="$DEPLOYMENT_PROFILE"

# is_placeholder VALUE -> 0 if the value looks like an unreplaced template value.
is_placeholder() {
  echo "$1" | grep -qiE 'CHANGE_ME|CHANGEME|CHANGE-ME|PLACEHOLDER|REPLACE_ME|REPLACEME|YOUR_|<[A-Za-z_-]+>|DUMMY|SAMPLE_|TODO|FIXME|00000000-0000-0000-0000-000000000000'
}

# is_jwt_pem_placeholder VALUE -> 0 only for known env-template markers.
# Short tokens (TODO/FIXME/YOUR_/DUMMY/SAMPLE_) must NOT be used on PKCS#8 PEM
# bodies: they collide with random base64 and false-fail CI dry-run fixtures.
is_jwt_pem_placeholder() {
  echo "$1" | grep -qiE 'CHANGE_ME|CHANGEME|CHANGE-ME|PLACEHOLDER|REPLACE_ME|REPLACEME|<[A-Za-z_-]+>|00000000-0000-0000-0000-000000000000'
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
echo "deploymentProfile=$DEPLOYMENT_PROFILE"

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
elif is_jwt_pem_placeholder "$JWT_PEM"; then
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
require_secret PARKIO_WAITLIST_HASH_SECRET 32 "openssl rand -base64 48"
if [ -n "$(env_get PARKIO_WAITLIST_HASH_SECRET)" ] \
  && [ "$(env_get PARKIO_WAITLIST_HASH_SECRET)" = "$(env_get PARKIO_GATEWAY_INTERNAL_SECRET)" ]; then
  fail "PARKIO_WAITLIST_HASH_SECRET" "must be distinct from PARKIO_GATEWAY_INTERNAL_SECRET" "generate an independent HMAC secret"
else
  ok
fi
# R-06 / #415: waitlist email previous + token HMAC secrets (optional until rotation).
# Blank PARKIO_WAITLIST_HASH_TOKEN_SECRET shares the email secret — incompatible with
# PARKIO_WAITLIST_HASH_SECRET_PREVIOUS (gateway startup refuses that combination).
if [ -n "$(env_get PARKIO_WAITLIST_HASH_SECRET_PREVIOUS)" ]; then
  require_secret PARKIO_WAITLIST_HASH_SECRET_PREVIOUS 32 "the waitlist hash secret in use before rotation, or leave it empty"
  if [ "$(env_get PARKIO_WAITLIST_HASH_SECRET_PREVIOUS)" = "$(env_get PARKIO_WAITLIST_HASH_SECRET)" ]; then
    fail "PARKIO_WAITLIST_HASH_SECRET_PREVIOUS" "equals PARKIO_WAITLIST_HASH_SECRET" "leave it empty when no rotation is in progress"
  fi
  require_secret PARKIO_WAITLIST_HASH_TOKEN_SECRET 32 "copy the pre-rotation PARKIO_WAITLIST_HASH_SECRET (or openssl rand -base64 48) before email-secret rotation"
  if [ "$(env_get PARKIO_WAITLIST_HASH_TOKEN_SECRET)" = "$(env_get PARKIO_WAITLIST_HASH_SECRET)" ]; then
    fail "PARKIO_WAITLIST_HASH_TOKEN_SECRET" "must differ from PARKIO_WAITLIST_HASH_SECRET while HASH_SECRET_PREVIOUS is set" "use the pre-rotation email hash secret (or another dedicated ≥32-char value)"
  fi
else
  ok
fi
if [ -n "$(env_get PARKIO_WAITLIST_HASH_TOKEN_SECRET)" ]; then
  require_secret PARKIO_WAITLIST_HASH_TOKEN_SECRET 32 "openssl rand -base64 48"
fi
if [ -n "$(env_get PARKIO_WAITLIST_HASH_TOKEN_SECRET_PREVIOUS)" ]; then
  require_secret PARKIO_WAITLIST_HASH_TOKEN_SECRET_PREVIOUS 32 "the token HMAC secret in use before rotation, or leave it empty"
  require_secret PARKIO_WAITLIST_HASH_TOKEN_SECRET 32 "set the current token HMAC secret before keeping a previous one"
  if [ "$(env_get PARKIO_WAITLIST_HASH_TOKEN_SECRET_PREVIOUS)" = "$(env_get PARKIO_WAITLIST_HASH_TOKEN_SECRET)" ]; then
    fail "PARKIO_WAITLIST_HASH_TOKEN_SECRET_PREVIOUS" "equals PARKIO_WAITLIST_HASH_TOKEN_SECRET" "leave it empty when no token-secret rotation is in progress"
  fi
fi
# CL-F15 v3: the login throttle's keyed-hashing secret is managed separately from every other secret.
require_secret PARKIO_LOGIN_THROTTLE_HMAC_KEY 32 "openssl rand -base64 48"
for other in PARKIO_GATEWAY_INTERNAL_SECRET PARKIO_WAITLIST_HASH_SECRET; do
  if [ -n "$(env_get PARKIO_LOGIN_THROTTLE_HMAC_KEY)" ] \
    && [ "$(env_get PARKIO_LOGIN_THROTTLE_HMAC_KEY)" = "$(env_get "$other")" ]; then
    fail "PARKIO_LOGIN_THROTTLE_HMAC_KEY" "must be distinct from $other" "generate an independent HMAC secret"
  fi
done
# The previous secret is optional (rotation overlap only); when set it must be a real, different secret.
if [ -n "$(env_get PARKIO_LOGIN_THROTTLE_HMAC_KEY_PREVIOUS)" ]; then
  require_secret PARKIO_LOGIN_THROTTLE_HMAC_KEY_PREVIOUS 32 "the secret in use before the rotation, or leave it empty"
  if [ "$(env_get PARKIO_LOGIN_THROTTLE_HMAC_KEY_PREVIOUS)" = "$(env_get PARKIO_LOGIN_THROTTLE_HMAC_KEY)" ]; then
    fail "PARKIO_LOGIN_THROTTLE_HMAC_KEY_PREVIOUS" "equals PARKIO_LOGIN_THROTTLE_HMAC_KEY" "leave it empty when no rotation is in progress"
  fi
fi

for svc in AUTH GATEWAY USER PARKING MEDIA GAMIFICATION NOTIFICATION MODERATION ANALYTICS AIVALIDATION; do
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

# Expo remains mandatory for hosted environments that intentionally include
# mobile push. The web-only invite-production profile enforces disabled/noop in
# its dedicated wrapper instead.
if [ "$DEPLOYMENT_PROFILE" = "invite-production" ]; then
  ok
else
  EXPO_TOKEN=$(env_get PARKIO_EXPO_ACCESS_TOKEN)
  if [ -z "$EXPO_TOKEN" ] || is_placeholder "$EXPO_TOKEN"; then
    fail "PARKIO_EXPO_ACCESS_TOKEN" "missing or placeholder" "create an access token at expo.dev (Account settings -> Access tokens)"
  else
    ok
  fi
fi

SLACK_URL=$(env_get PARKIO_ALERT_SLACK_WEBHOOK_URL)
GENERIC_URL=$(env_get PARKIO_ALERT_WEBHOOK_URL)
if [ "$DEPLOYMENT_PROFILE" = "azure-hosted-beta" ]; then
  ok
elif is_placeholder "$SLACK_URL"; then
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

# Heartbeat to an external dead-man's switch (U06). Empty is allowed but visible: a silent
# Prometheus, Alertmanager or host is then not detected from outside the failure domain.
HEARTBEAT_URL=$(env_get PARKIO_ALERT_HEARTBEAT_URL)
if [ "$DEPLOYMENT_PROFILE" = "azure-hosted-beta" ]; then
  ok
elif [ -z "$HEARTBEAT_URL" ]; then
  warn "heartbeat" "PARKIO_ALERT_HEARTBEAT_URL is empty — no external dead-man's switch; a silent Prometheus, Alertmanager or host is not detected (docs/operations/alerting.md#heartbeat)"
elif is_placeholder "$HEARTBEAT_URL"; then
  fail "PARKIO_ALERT_HEARTBEAT_URL" "is a placeholder" "paste the real heartbeat ping URL from the external monitor, or clear it"
else
  case "$HEARTBEAT_URL" in
    https://*) ok ;;
    *) fail "PARKIO_ALERT_HEARTBEAT_URL" "must be HTTPS" "the heartbeat receiver must be an https:// URL" ;;
  esac
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

if [ "$DEPLOYMENT_PROFILE" = "azure-hosted-beta" ]; then
  if [ "$(env_get PARKIO_DOMAIN)" != "api.parkio.dev" ]; then
    fail "PARKIO_DOMAIN" "Azure hosted beta must use 'api.parkio.dev'" "set PARKIO_DOMAIN=api.parkio.dev"
  else
    ok
  fi
  if [ "$(env_get VITE_API_BASE_URL)" != "https://api.parkio.dev/api/v1" ]; then
    fail "VITE_API_BASE_URL" "Azure hosted beta must use the canonical API URL" "set VITE_API_BASE_URL=https://api.parkio.dev/api/v1"
  else
    ok
  fi
  if [ "$(env_get VITE_WAITLIST_INTAKE_MODE)" != "api" ]; then
    fail "VITE_WAITLIST_INTAKE_MODE" "Azure web must explicitly use API-backed waitlist intake" "set VITE_WAITLIST_INTAKE_MODE=api"
  else
    ok
  fi
  if [ "$(env_get PARKIO_WEB_BASE_URL)" != "https://app.parkio.dev" ]; then
    fail "PARKIO_WEB_BASE_URL" "Azure hosted beta must use the canonical web origin for email links" "set PARKIO_WEB_BASE_URL=https://app.parkio.dev"
  else
    ok
  fi
fi

# Transactional email links (verification, password reset) are built from this
# base URL; an unset value silently falls back to the local Vite dev server and
# beta users receive localhost links they cannot open.
if require_https_url PARKIO_WEB_BASE_URL; then
  WEB_BASE_HOST=$(env_get PARKIO_WEB_BASE_URL | sed -e 's|^https://||' -e 's|[/?].*$||')
  WEB_DOMAIN=$(env_get PARKIO_WEB_DOMAIN)
  if [ -n "$WEB_DOMAIN" ] && [ "$WEB_BASE_HOST" != "$WEB_DOMAIN" ]; then
    fail "PARKIO_WEB_BASE_URL" "host '$WEB_BASE_HOST' does not match PARKIO_WEB_DOMAIN '$WEB_DOMAIN'" "email links must open the public SPA: set PARKIO_WEB_BASE_URL=https://\$PARKIO_WEB_DOMAIN"
  else
    ok
  fi
fi

# Optional per-link overrides: when set they must also be public https URLs.
for v in PARKIO_EMAIL_VERIFICATION_URL PARKIO_PASSWORD_RESET_URL; do
  if env_has "$v"; then
    require_https_url "$v"
  else
    ok
  fi
done

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
if [ "$DEPLOYMENT_PROFILE" = "invite-production" ]; then
  # Exact disabled/noop semantics are checked by preflight-invite-production.sh.
  ok
elif [ "$PUSH_PROVIDER" = "expo" ]; then
  ok
elif [ "$ALLOW_PROVIDER" = "1" ] && [ -n "$PUSH_PROVIDER" ] && [ "$PUSH_PROVIDER" != "noop" ]; then
  warn "PARKIO_PUSH_DELIVERY_PROVIDER" "'$PUSH_PROVIDER' (non-default) explicitly allowed — document the rationale in the deploy notes"
elif [ "$PUSH_PROVIDER" = "noop" ] || [ -z "$PUSH_PROVIDER" ]; then
  fail "PARKIO_PUSH_DELIVERY_PROVIDER" "'${PUSH_PROVIDER:-<unset -> compose default 'noop'>}' — the noop provider silently drops every push notification" "set PARKIO_PUSH_DELIVERY_PROVIDER=expo"
else
  fail "PARKIO_PUSH_DELIVERY_PROVIDER" "'$PUSH_PROVIDER' is not the supported hosted-beta provider" "set PARKIO_PUSH_DELIVERY_PROVIDER=expo (or acknowledge with PARKIO_PREFLIGHT_ALLOW_PROVIDER_OVERRIDE=1)"
fi

# duration_ms VALUE -> VALUE in milliseconds for '<digits>ms' or '<digits>s'; fails for any other form.
duration_ms() {
  case "$1" in
    *ms) dm_num="${1%ms}"; dm_unit=1 ;;
    *s) dm_num="${1%s}"; dm_unit=1000 ;;
    *) return 1 ;;
  esac
  case "$dm_num" in
    ""|*[!0-9]*) return 1 ;;
  esac
  # Past nine digits the value is far above any limit; keep the arithmetic in range.
  if [ "${#dm_num}" -gt 9 ]; then
    echo 999999999999
    return 0
  fi
  echo $(( $(echo "$dm_num" | sed 's/^0*\([0-9]\)/\1/') * dm_unit ))
}

# Expo send bounds (#595), optional: notification-service refuses to start unless
# 0 < connect <= 30s, 0 < read <= 60s and connect < read (the read timeout also runs
# while connecting). Unset values take the defaults, 2s and 5s.
if [ "$DEPLOYMENT_PROFILE" != "invite-production" ] && [ "$PUSH_PROVIDER" = "expo" ]; then
  EXPO_BOUNDS_OK=1
  EXPO_CONNECT_MS=2000
  EXPO_READ_MS=5000
  EXPO_CONNECT_RAW=$(env_get PARKIO_EXPO_PUSH_CONNECT_TIMEOUT)
  if [ -n "$EXPO_CONNECT_RAW" ]; then
    if EXPO_CONNECT_MS=$(duration_ms "$EXPO_CONNECT_RAW") \
      && [ "$EXPO_CONNECT_MS" -gt 0 ] && [ "$EXPO_CONNECT_MS" -le 30000 ]; then
      ok
    else
      fail "PARKIO_EXPO_PUSH_CONNECT_TIMEOUT" "'$EXPO_CONNECT_RAW' is not a bounded connect timeout" "use <n>ms or <n>s, greater than 0 and at most 30s (default 2s)"
      EXPO_BOUNDS_OK=0
    fi
  fi
  EXPO_READ_RAW=$(env_get PARKIO_EXPO_PUSH_READ_TIMEOUT)
  if [ -n "$EXPO_READ_RAW" ]; then
    if EXPO_READ_MS=$(duration_ms "$EXPO_READ_RAW") \
      && [ "$EXPO_READ_MS" -gt 0 ] && [ "$EXPO_READ_MS" -le 60000 ]; then
      ok
    else
      fail "PARKIO_EXPO_PUSH_READ_TIMEOUT" "'$EXPO_READ_RAW' is not a bounded read timeout" "use <n>ms or <n>s, greater than 0 and at most 60s (default 5s)"
      EXPO_BOUNDS_OK=0
    fi
  fi
  if [ "$EXPO_BOUNDS_OK" -eq 1 ]; then
    if [ "$EXPO_CONNECT_MS" -lt "$EXPO_READ_MS" ]; then
      ok
    else
      fail "PARKIO_EXPO_PUSH_CONNECT_TIMEOUT" "${EXPO_CONNECT_MS}ms is not shorter than PARKIO_EXPO_PUSH_READ_TIMEOUT (${EXPO_READ_MS}ms)" "keep the connect timeout below the read timeout, which also runs while connecting"
    fi
  fi
fi

# Municipal feed timings (#615, #637, #654). parking-service refuses to start, whether or not a feed
# is enabled, unless each HTTP feed's connect-timeout and read-timeout are at least 1ms and
# connect-timeout + read-timeout is less than max-response-time (MunicipalSourceProperties#validate).
# A max-response-time above 2^63-1 ns also stops it: BoundedFeedResponses holds the deadline in
# nanoseconds. A value Spring cannot bind stops it too. The production Compose overlay
# (docker-compose.azure-hosted-beta.yml) passes these keys with the defaults given below. The
# invite-production Compose files pass none of them, so parking-service keeps its application.yml values.

# >>> spring-duration (scripts/test-preflight-hosted-beta.sh extracts this block)
# spring_duration_ns VALUE prints VALUE in nanoseconds, read as parking-service binds a Duration:
# Spring Boot 4.1's DurationStyle, with no @DurationUnit. Two forms are accepted:
#   - simple: an optional sign, digits, and an optional unit ns, us, ms, s, m, h or d in any case.
#     No unit means ms.
#   - ISO-8601: anything else that starts with an optional sign and P goes to
#     java.time.Duration.parse, e.g. PT20S, pt0,5s or PT1M-30S.
# Returns 0 with the value printed, or prints nothing and returns:
#   1 when Spring rejects VALUE (no form matches, or a long overflows as Java checks it);
#   2 when VALUE is longer than 2^63-1 ns (about 292 years);
#   3 when VALUE is negative.
SD_MAX=9223372036854775807
SD_LOWER=abcdefghijklmnopqrstuvwxyz
SD_UPPER=ABCDEFGHIJKLMNOPQRSTUVWXYZ

# sd_le A B: A <= B, for decimal digit strings without leading zeros; B has at most 19 digits.
sd_le() {
  if [ "${#1}" -ne "${#2}" ]; then [ "${#1}" -lt "${#2}" ]; return; fi
  if [ "${#1}" -le 18 ]; then [ "$1" -le "$2" ]; return; fi
  sd_lea=${1%?}; sd_leb=${2%?}
  [ "$sd_lea" -lt "$sd_leb" ] && return 0
  [ "$sd_lea" -eq "$sd_leb" ] && [ "${1#"$sd_lea"}" -le "${2#"$sd_leb"}" ]
}

# sd_long TEXT: SD_LONG = Long.parseLong(TEXT) for [+-]?[0-9]+; returns 1 when that fails.
sd_long() {
  sd_lt=$1; sd_ln=0
  case $sd_lt in -*) sd_ln=1; sd_lt=${sd_lt#?} ;; +*) sd_lt=${sd_lt#?} ;; esac
  case $sd_lt in ''|*[!0123456789]*) return 1 ;; esac
  sd_lt=${sd_lt#"${sd_lt%%[!0]*}"}
  [ -n "$sd_lt" ] || sd_lt=0
  if [ "$sd_ln" -eq 1 ] && [ "$sd_lt" = 9223372036854775808 ]; then
    SD_LONG=$(( -SD_MAX - 1 ))
    return 0
  fi
  sd_le "$sd_lt" "$SD_MAX" || return 1
  if [ "$sd_ln" -eq 1 ]; then SD_LONG=$(( -sd_lt )); else SD_LONG=$sd_lt; fi
}

# The arithmetic below reads values through variable names: dash reads a variable's value with its
# sign, but a literal -9223372036854775808 is the negation of an out-of-range number.

# sd_mul V K LIMIT: SD_MUL = Math.multiplyExact(V, K), where LIMIT = Long.MAX_VALUE / K bounds |V|.
sd_mul() {
  sd_mv=$1; sd_mk=$2
  if [ "$sd_mv" -gt "$3" ] || [ "$sd_mv" -lt "-$3" ]; then return 1; fi
  SD_MUL=$(( sd_mv * sd_mk ))
}

# sd_add A B: SD_ADD = Math.addExact(A, B).
sd_add() {
  sd_aa=$1; sd_ab=$2
  if [ "$sd_ab" -gt 0 ] && [ "$sd_aa" -gt $(( SD_MAX - sd_ab )) ]; then return 1; fi
  if [ "$sd_ab" -lt 0 ] && [ "$sd_aa" -lt $(( -SD_MAX - 1 - sd_ab )) ]; then return 1; fi
  SD_ADD=$(( sd_aa + sd_ab ))
}

# sd_print SECONDS NANOS: print a Duration, 0 <= NANOS < 10^9, in nanoseconds (see the return codes).
sd_print() {
  sd_ps=$1; sd_pn=$2
  if [ "$sd_ps" -lt 0 ]; then return 3; fi
  if [ "$sd_ps" -gt 9223372036 ] || { [ "$sd_ps" -eq 9223372036 ] && [ "$sd_pn" -gt 854775807 ]; }; then return 2; fi
  echo $(( sd_ps * 1000000000 + sd_pn ))
}

spring_duration_ns() {
  case $1 in
    [+-][pP]*|[pP]*) sd_iso "$1" ;;
    *) sd_simple "$1" ;;
  esac
}

# SIMPLE: ^([+-]?\d+)([a-zA-Z]{0,2})$, then Duration.of(Long.parseLong(group 1), unit).
sd_simple() {
  sd_v=$1; sd_sign=
  case $sd_v in [+-]*) sd_sign=${sd_v%"${sd_v#?}"}; sd_v=${sd_v#?} ;; esac
  sd_num=${sd_v%%[!0123456789]*}
  sd_unit=${sd_v#"$sd_num"}
  [ -n "$sd_num" ] || return 1
  sd_long "$sd_sign$sd_num" || return 1
  sd_n=$SD_LONG
  # m, h and d become seconds first, with Java's overflow check (the limit is Long.MAX_VALUE / factor);
  # sd_ns_per is then the nanoseconds per unit that remains.
  case $sd_unit in
    [nN][sS]) sd_ns_per=1 ;;
    [uU][sS]) sd_ns_per=1000 ;;
    ''|[mM][sS]) sd_ns_per=1000000 ;;
    [sS]) sd_ns_per=1000000000 ;;
    [mM]) sd_mul "$sd_n" 60 153722867280912930 || return 1; sd_n=$SD_MUL; sd_ns_per=1000000000 ;;
    [hH]) sd_mul "$sd_n" 3600 2562047788015215 || return 1; sd_n=$SD_MUL; sd_ns_per=1000000000 ;;
    [dD]) sd_mul "$sd_n" 86400 106751991167300 || return 1; sd_n=$SD_MUL; sd_ns_per=1000000000 ;;
    *) return 1 ;;
  esac
  if [ "$sd_n" -lt 0 ]; then return 3; fi
  if [ "$sd_n" -gt $(( SD_MAX / sd_ns_per )) ]; then return 2; fi
  echo $(( sd_n * sd_ns_per ))
}

# sd_isint TEXT: TEXT matches [-+]?[0-9]+.
sd_isint() {
  sd_it=$1
  case $sd_it in [+-]*) sd_it=${sd_it#?} ;; esac
  case $sd_it in ''|*[!0123456789]*) return 1 ;; esac
  return 0
}

# sd_part TEXT K LIMIT: SD_PART = Math.multiplyExact(Long.parseLong(TEXT), K), 0 for an absent part.
sd_part() {
  if [ -z "$1" ]; then SD_PART=0; return 0; fi
  sd_long "$1" || return 1
  if [ "$2" -eq 1 ]; then SD_PART=$SD_LONG; return 0; fi
  sd_mul "$SD_LONG" "$2" "$3" || return 1
  SD_PART=$SD_MUL
}

# ISO-8601: java.time.Duration.parse, case-insensitive:
#   ([-+]?)P(?:([-+]?[0-9]+)D)?(T(?:([-+]?[0-9]+)H)?(?:([-+]?[0-9]+)M)?(?:([-+]?[0-9]+)(?:[.,]([0-9]{0,9}))?S)?)?
# with at least one part, and at least one time part after a T.
sd_iso() {
  # JDK quirk: an empty time section is refused only after an upper-case T (P5DT), not after t (P5Dt).
  sd_tcase=${1#*[Tt]}
  sd_tcase=${1%"$sd_tcase"}
  sd_tcase=${sd_tcase#"${sd_tcase%?}"}
  sd_v=$(printf '%s' "$1" | tr "$SD_LOWER" "$SD_UPPER")
  sd_negate=0
  case $sd_v in -*) sd_negate=1; sd_v=${sd_v#?} ;; +*) sd_v=${sd_v#?} ;; esac
  case $sd_v in P*) sd_v=${sd_v#P} ;; *) return 1 ;; esac
  sd_d=; sd_h=; sd_m=; sd_s=; sd_f=; sd_t=
  case $sd_v in *T*) sd_t=${sd_v#*T}; sd_v=${sd_v%%T*}; sd_hast=1 ;; *) sd_hast=0 ;; esac
  case $sd_v in
    '') ;;
    *D) sd_d=${sd_v%D}; sd_isint "$sd_d" || return 1 ;;
    *) return 1 ;;
  esac
  if [ "$sd_hast" -eq 1 ]; then
    case $sd_t in *H*) sd_h=${sd_t%%H*}; sd_t=${sd_t#*H}; sd_isint "$sd_h" || return 1 ;; esac
    case $sd_t in *M*) sd_m=${sd_t%%M*}; sd_t=${sd_t#*M}; sd_isint "$sd_m" || return 1 ;; esac
    case $sd_t in
      '') ;;
      *S)
        sd_s=${sd_t%S}
        case $sd_s in
          *[.,]*) sd_f=${sd_s#*[.,]}; sd_s=${sd_s%%[.,]*}
                  case $sd_f in *[!0123456789]*) return 1 ;; esac
                  [ "${#sd_f}" -le 9 ] || return 1 ;;
        esac
        sd_isint "$sd_s" || return 1 ;;
      *) return 1 ;;
    esac
    [ -n "$sd_h$sd_m$sd_s" ] || [ "$sd_tcase" = t ] || return 1
  fi
  [ -n "$sd_d$sd_h$sd_m$sd_s" ] || return 1
  sd_part "$sd_d" 86400 106751991167300 || return 1; sd_pd=$SD_PART
  sd_part "$sd_h" 3600 2562047788015215 || return 1; sd_ph=$SD_PART
  sd_part "$sd_m" 60 153722867280912930 || return 1; sd_pm=$SD_PART
  sd_part "$sd_s" 1 0 || return 1; sd_ps=$SD_PART
  sd_add "$sd_pm" "$sd_ps" || return 1
  sd_add "$sd_ph" "$SD_ADD" || return 1
  sd_add "$sd_pd" "$SD_ADD" || return 1
  sd_secs=$SD_ADD
  sd_nanos=0
  if [ -n "$sd_f" ]; then
    sd_f=${sd_f}000000000
    sd_f=${sd_f%"${sd_f#?????????}"}
    sd_f=${sd_f#"${sd_f%%[!0]*}"}
    sd_nanos=${sd_f:-0}
    case $sd_s in -*) sd_nanos=$(( -sd_nanos )) ;; esac
  fi
  # Duration.ofSeconds(seconds, nanos), then negated() for a leading '-'.
  if [ "$sd_nanos" -lt 0 ]; then
    sd_add "$sd_secs" -1 || return 1
    sd_secs=$SD_ADD
    sd_nanos=$(( sd_nanos + 1000000000 ))
  fi
  if [ "$sd_negate" -eq 1 ]; then
    if [ "$sd_nanos" -eq 0 ]; then
      [ "$sd_secs" -ne $(( -SD_MAX - 1 )) ] || return 1
      sd_secs=$(( -sd_secs ))
    else
      sd_secs=$(( -(sd_secs + 1) ))
      sd_nanos=$(( 1000000000 - sd_nanos ))
    fi
  fi
  sd_print "$sd_secs" "$sd_nanos"
}
# <<< spring-duration

# compose_env_value KEY prints KEY's value as docker compose reads it from the env file, which env_get
# does not quite do. The last line that sets KEY wins. A line may start with `export `, and `=` or `:`
# may have blanks around it. A quoted value is what lies between its quotes. An unquoted value ends at
# " #" (a comment) and loses its surrounding blanks, a CR included. Prints nothing for an unset or
# empty KEY. Returns 4 for an opening quote with no closing one: docker compose cannot read the file.
compose_env_value() {
  cev_v=$(grep -E "^[[:space:]]*(export[[:space:]]+)?$1[[:space:]]*[=:]" "$ENV_FILE" | tail -n 1 \
    | sed -E "s/^[[:space:]]*(export[[:space:]]+)?$1[[:space:]]*[=:]//")
  cev_v=${cev_v#"${cev_v%%[![:space:]]*}"}
  case $cev_v in
    \"*|\'*)
      cev_q=${cev_v%"${cev_v#?}"}
      cev_v=${cev_v#?}
      case $cev_v in *"$cev_q"*) ;; *) return 4 ;; esac
      cev_v=${cev_v%%"$cev_q"*} ;;
    *)
      cev_v=${cev_v%%" #"*}
      cev_v=${cev_v%"${cev_v##*[![:space:]]}"} ;;
  esac
  printf '%s' "$cev_v"
}

# municipal_timing KEY DEFAULT NAME SOURCE sets MT_SHOWN (the value in effect, as messages show it),
# and MT_NS and MT_RC (spring_duration_ns's output and return code). An unset or empty KEY takes
# DEFAULT, as the overlay's ${KEY:-DEFAULT} does. Reports a value that parking-service cannot bind, and
# returns 1 for it.
municipal_timing() {
  MT_NS=; MT_RC=1
  mt_raw=$(compose_env_value "$1")
  if [ "$?" -eq 4 ]; then
    fail "$1" "the quote that opens the value is not closed, so docker compose cannot read the env file" "close the quote"
    return 1
  fi
  if [ -z "$mt_raw" ]; then
    mt_raw=$2
    MT_SHOWN="$2, the default"
  else
    MT_SHOWN=$mt_raw
  fi
  case $mt_raw in
    *'$'*)
      fail "$1" "'$mt_raw' uses \$, which docker compose or Spring would expand before parking-service reads it" "write the duration out, such as 20s"
      return 1 ;;
  esac
  MT_NS=$(spring_duration_ns "$mt_raw")
  MT_RC=$?
  case $MT_RC in
    0|3) return 0 ;;
    1) fail "$1" "$4 $3 ('$mt_raw') is not a duration parking-service accepts" "use a number with ns, us, ms, s, m, h or d (no unit means ms), or ISO-8601 such as PT20S" ;;
    2) fail "$1" "$4 $3 ($mt_raw) is longer than 2^63-1 nanoseconds (about 292 years)" "use a duration below 292 years" ;;
  esac
  return 1
}

# municipal_feed_check SOURCE CONNECT READ MAX checks one feed. CONNECT, READ and MAX are the overlay's
# defaults for its unset keys.
municipal_feed_check() {
  mf_key=PARKIO_MUNICIPAL_$1
  mf_ok=1
  for mf_part in CONNECT_TIMEOUT READ_TIMEOUT; do
    if [ "$mf_part" = CONNECT_TIMEOUT ]; then
      mf_name=connect-timeout; mf_default=$2
    else
      mf_name=read-timeout; mf_default=$3
    fi
    if ! municipal_timing "${mf_key}_$mf_part" "$mf_default" "$mf_name" "$1"; then
      mf_ok=0
      continue
    fi
    if [ "$MT_RC" -eq 3 ] || [ "$MT_NS" -lt 1000000 ]; then
      fail "${mf_key}_$mf_part" "$1 $mf_name ($MT_SHOWN) must be at least 1ms" "the HTTP client counts whole milliseconds and treats 0 or less as no timeout"
      mf_ok=0
      continue
    fi
    ok
    if [ "$mf_part" = CONNECT_TIMEOUT ]; then
      mf_c=$MT_NS; mf_c_shown=$MT_SHOWN
    else
      mf_r=$MT_NS; mf_r_shown=$MT_SHOWN
    fi
  done
  municipal_timing "${mf_key}_MAX_RESPONSE_TIME" "$4" max-response-time "$1" || mf_ok=0
  [ "$mf_ok" -eq 1 ] || return 0
  # A negative max-response-time (MT_RC 3) is never above connect + read.
  if [ "$MT_RC" -eq 0 ] && [ "$mf_r" -lt $(( MT_NS - mf_c )) ]; then
    ok
  else
    fail "${mf_key}_READ_TIMEOUT" "$1 connect-timeout ($mf_c_shown) + read-timeout ($mf_r_shown) must be less than max-response-time ($MT_SHOWN)" "the body is read only until max-response-time after the request starts: raise ${mf_key}_MAX_RESPONSE_TIME together with ${mf_key}_READ_TIMEOUT"
  fi
}

if [ "$DEPLOYMENT_PROFILE" != "invite-production" ]; then
  municipal_feed_check IZUM 2s 27s 30s
  municipal_feed_check ISPARK 2s 10s 30s
  municipal_feed_check ANPARK 2s 10s 30s
  municipal_feed_check KONYA 5s 20s 30s
  municipal_feed_check KAYSERI 5s 20s 30s
fi

# AI vision: hosted beta should run the real provider so valid parking photos can
# become ACTIVE. The heuristic placeholder fails closed (everything -> PENDING_REVIEW)
# which is safe but routes every upload to manual review.
VISION_PROVIDER=$(env_get PARKIO_AI_VISION_PROVIDER)
if [ "$VISION_PROVIDER" = "gemini" ]; then
  VISION_KEY=$(env_get PARKIO_AI_VISION_GEMINI_API_KEY)
  if [ -z "$VISION_KEY" ] || is_placeholder "$VISION_KEY"; then
    fail "PARKIO_AI_VISION_GEMINI_API_KEY" "missing or placeholder while PARKIO_AI_VISION_PROVIDER=gemini — ai-validation-service will refuse to start" "create a key at aistudio.google.com/apikey and set it in the git-ignored .env"
  else
    ok
  fi
elif [ "$ALLOW_PROVIDER" = "1" ] && [ -n "$VISION_PROVIDER" ] && [ "$VISION_PROVIDER" != "heuristic" ]; then
  warn "PARKIO_AI_VISION_PROVIDER" "'$VISION_PROVIDER' (non-default) explicitly allowed — document the rationale in the deploy notes"
elif [ "$VISION_PROVIDER" = "heuristic" ] || [ -z "$VISION_PROVIDER" ]; then
  warn "PARKIO_AI_VISION_PROVIDER" "'${VISION_PROVIDER:-<unset -> compose default 'heuristic'>}' — no real vision model: every upload fails closed into PENDING_REVIEW (never ACTIVE without manual review)"
else
  fail "PARKIO_AI_VISION_PROVIDER" "'$VISION_PROVIDER' is not a supported vision provider" "set PARKIO_AI_VISION_PROVIDER=gemini (plus PARKIO_AI_VISION_GEMINI_API_KEY), or heuristic for fail-closed manual review"
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
EXPECTED_ENVIRONMENT="hosted-beta"
if [ "$DEPLOYMENT_PROFILE" = "invite-production" ]; then
  EXPECTED_ENVIRONMENT="invite-production"
fi
if [ "$ENVIRONMENT" != "$EXPECTED_ENVIRONMENT" ]; then
  fail "PARKIO_ENVIRONMENT" "'${ENVIRONMENT:-<unset -> compose default 'local'>}' — observability labels and alert routing key off this" "set PARKIO_ENVIRONMENT=$EXPECTED_ENVIRONMENT"
else
  ok
fi

# Known CI synthetic MapTiler key must never be selected for a hosted deploy.
# Mock CI image acceptance still uses this value and does not run this preflight.
MAP_KEY=$(env_get VITE_MAPTILER_KEY)
if [ "$MAP_KEY" = "ci-web-build-security-synthetic" ]; then
  fail "VITE_MAPTILER_KEY" "known CI synthetic test map key must not be deployed" "use the authorized production MapTiler configuration; keep the synthetic key only in mock CI acceptance"
else
  ok
fi

APP_ENV=$(env_get VITE_APP_ENV)
EXPECTED_APP_ENV="hosted-beta"
if [ "$DEPLOYMENT_PROFILE" = "invite-production" ]; then
  EXPECTED_APP_ENV="invite-production"
fi
if [ -n "$APP_ENV" ] && [ "$APP_ENV" != "$EXPECTED_APP_ENV" ]; then
  fail "VITE_APP_ENV" "'$APP_ENV' — the web build must match the deployment environment" "set VITE_APP_ENV=$EXPECTED_APP_ENV"
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

if [ "$DEPLOYMENT_PROFILE" = "azure-hosted-beta" ]; then
  if [ "$(env_get PARKIO_TRACING_ENABLED)" != "false" ]; then
    fail "PARKIO_TRACING_ENABLED" "Azure profile requires tracing to be explicitly disabled" "set PARKIO_TRACING_ENABLED=false"
  else
    ok
  fi
fi

# --------------------------------------------------------------------------- #
category "Compose"
# --------------------------------------------------------------------------- #

if [ "$SKIP_COMPOSE" -eq 1 ]; then
  echo "  SKIP compose render (--skip-compose)"
elif [ "$FAILURES" -gt 0 ]; then
  echo "  SKIP compose render (fix the failures above first)"
else
  if PARKIO_DEPLOYMENT_PROFILE="$DEPLOYMENT_PROFILE" PARKIO_ENV_FILE="$ENV_FILE" "$ROOT/scripts/validate-hosted-beta-compose.sh" >/dev/null 2>&1; then
    echo "  PASS compose config renders (validate-hosted-beta-compose.sh)"
    ok
  else
    fail "compose" "validate-hosted-beta-compose.sh failed" "run: PARKIO_ENV_FILE=$ENV_FILE ./scripts/validate-hosted-beta-compose.sh"
  fi
fi

# --------------------------------------------------------------------------- #
# B-02 cosign pin (fail-closed for deploy provenance)
category "Cosign"
if parkio_preflight_require_cosign; then
  ok
else
  fail "cosign" "pinned cosign missing or wrong version" "install PARKIO_COSIGN_VERSION from scripts/lib/cosign-pin.env"
fi

echo ""
if [ "$FAILURES" -gt 0 ]; then
  echo "=== PREFLIGHT: FAIL — $FAILURES failure(s), $WARNINGS warning(s), $CHECKS check(s) passed ==="
  echo "Deployment is blocked. Fix every FAIL above, then re-run:"
  echo "  PARKIO_ENV_FILE=$ENV_FILE ./scripts/preflight-hosted-beta.sh"
  exit 1
fi
echo "=== PREFLIGHT: PASS — $CHECKS check(s) passed, $WARNINGS warning(s) ==="
exit 0

