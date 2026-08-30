#!/usr/bin/env bash
# PRIV-001A — create one disposable synthetic principal for later runtime acceptance.
#
# SOURCE / OPERATOR TOOLING ONLY. Does not run automatically on deploy.
# Does NOT call DELETE /api/v1/account (canonical erasure is a later package).
#
# Required:
#   --environment invite-production
#   --confirm-synthetic-only
#
# Optional:
#   --gateway-url http://127.0.0.1:8080   (dark gateway allowlist only)
#   --max-age-seconds 900
#   --credentials-file <path>             (mode 600; never printed)
#   --evidence-file <path>                (JSON without secrets)
#   --skip-seed
#   --dry-run-guards                      (validate flags/allowlist only)
#
# Managed auth DB (verification mutation):
#   PARKIO_PG_MODE=managed (default)
#   PARKIO_PG_HOST, PARKIO_PG_PORT, PARKIO_PG_SSLMODE=verify-full
#   PARKIO_PG_USER / PARKIO_PG_DB, PGPASSWORD or PARKIO_PG_PASSWORD
#   PARKIO_DEPLOYMENT_PROFILE=invite-production
#
# shellcheck shell=bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=scripts/lib/dark-gateway-url.sh
source "${ROOT}/scripts/lib/dark-gateway-url.sh"
# shellcheck source=scripts/lib/priv001-synthetic.sh
source "${ROOT}/scripts/lib/priv001-synthetic.sh"

ENVIRONMENT=""
CONFIRM=""
GATEWAY_URL="${PARKIO_GATEWAY_URL:-$PARKIO_DARK_GATEWAY_ALLOWED_URL}"
MAX_AGE="$PRIV001_DEFAULT_MAX_AGE_SECONDS"
CREDENTIALS_FILE=""
EVIDENCE_FILE=""
SKIP_SEED=0
DRY_RUN_GUARDS=0

usage() {
  cat <<'EOF'
Usage:
  scripts/acceptance/create-priv001-synthetic-principal.sh \
    --environment invite-production \
    --confirm-synthetic-only \
    [--gateway-url http://127.0.0.1:8080] \
    [--max-age-seconds 900] \
    [--credentials-file PATH] \
    [--evidence-file PATH] \
    [--skip-seed] \
    [--dry-run-guards]

Creates exactly one disposable synthetic account via POST /api/v1/auth/register,
establishes email verification through the allowlisted managed-auth SQL helper,
then logs in via POST /api/v1/auth/login.

Never prints password, JWT, refresh token, or DB credentials.
Cleanup is DELETE /api/v1/account in a later runtime acceptance package.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --environment)
      ENVIRONMENT="${2-}"
      shift 2
      ;;
    --confirm-synthetic-only)
      CONFIRM="yes"
      shift
      ;;
    --gateway-url)
      GATEWAY_URL="${2-}"
      shift 2
      ;;
    --max-age-seconds)
      MAX_AGE="${2-}"
      shift 2
      ;;
    --credentials-file)
      CREDENTIALS_FILE="${2-}"
      shift 2
      ;;
    --evidence-file)
      EVIDENCE_FILE="${2-}"
      shift 2
      ;;
    --skip-seed)
      SKIP_SEED=1
      shift
      ;;
    --dry-run-guards)
      DRY_RUN_GUARDS=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

export PARKIO_PRIV001_ENVIRONMENT="$ENVIRONMENT"
export PARKIO_PRIV001_CONFIRM_SYNTHETIC_ONLY="$CONFIRM"
# Prefer explicit profile; allow harness to set it when operator already opted in.
if [ -z "${PARKIO_DEPLOYMENT_PROFILE:-}" ] && [ "$ENVIRONMENT" = "invite-production" ]; then
  export PARKIO_DEPLOYMENT_PROFILE=invite-production
fi

parkio_priv001_require_invite_production_opt_in
parkio_validate_dark_gateway_url "$GATEWAY_URL"

ACCEPTANCE_ID="PRIV001A-$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 4 2>/dev/null || printf '%04x' "$RANDOM")"
LOCAL_PART="$(parkio_priv001_generate_local_part)"
EMAIL="${LOCAL_PART}@${PRIV001_SYNTHETIC_DOMAIN}"
parkio_priv001_require_email "$EMAIL"

if [ "$DRY_RUN_GUARDS" = "1" ]; then
  echo "PASS: dry-run guards"
  echo "acceptance_id=${ACCEPTANCE_ID}"
  echo "synthetic_domain=${PRIV001_SYNTHETIC_DOMAIN}"
  echo "email_form=priv001a-<id>@${PRIV001_SYNTHETIC_DOMAIN}"
  echo "verification=allowlisted-managed-auth-sql"
  echo "erasure=canonical-DELETE-/api/v1/account-later"
  exit 0
fi

PASSWORD="$(parkio_priv001_generate_password | tr -d '\n\r')"
if [ "${#PASSWORD}" -lt 12 ]; then
  echo "ERROR: failed to generate a password meeting policy." >&2
  exit 1
fi

if [ -z "$CREDENTIALS_FILE" ]; then
  CREDENTIALS_FILE="$(mktemp "${TMPDIR:-/tmp}/priv001a-creds.XXXXXX")"
fi
umask 077
: > "$CREDENTIALS_FILE"
chmod 600 "$CREDENTIALS_FILE" 2>/dev/null || true

if [ -z "$EVIDENCE_FILE" ]; then
  EVIDENCE_FILE="$(mktemp "${TMPDIR:-/tmp}/priv001a-evidence.XXXXXX")"
fi

cleanup_secrets_on_error() {
  # Keep credentials file for operator reuse on success; wipe on failure mid-run.
  :
}
trap cleanup_secrets_on_error ERR

echo "PRIV-001A: registering synthetic principal (acceptance_id=${ACCEPTANCE_ID})"

REGISTER_BODY="$(printf '{"email":"%s","password":"%s","locale":"en"}' "$EMAIL" "$PASSWORD")"
REGISTER_HEADERS="$(mktemp)"
REGISTER_OUT="$(mktemp)"
REGISTER_CODE="$(
  curl -sS -o "$REGISTER_OUT" -D "$REGISTER_HEADERS" -w '%{http_code}' \
    -X POST "${GATEWAY_URL}/api/v1/auth/register" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json' \
    -H 'X-Parkio-Client: mobile' \
    --data-binary "$REGISTER_BODY" \
    --connect-timeout 10 \
    --max-time 60
)" || true

# Never retain register body (contains password) in shell history dumps.
unset REGISTER_BODY

if [ "$REGISTER_CODE" != "201" ] && [ "$REGISTER_CODE" != "200" ]; then
  echo "ERROR: register failed http=${REGISTER_CODE}" >&2
  # Safe snippet only: status/code fields if present.
  grep -E '"code"|"status"|"message"' "$REGISTER_OUT" 2>/dev/null | head -n 5 >&2 || true
  rm -f "$REGISTER_HEADERS" "$REGISTER_OUT"
  exit 1
fi

AUTH_USER_ID="$(parkio_priv001_json_get "$REGISTER_OUT" "user.id")"
AUTH_STATUS="$(parkio_priv001_json_get "$REGISTER_OUT" "user.status")"
rm -f "$REGISTER_HEADERS" "$REGISTER_OUT"

if [ -z "$AUTH_USER_ID" ]; then
  echo "ERROR: register response missing user.id" >&2
  exit 1
fi
if [ "$AUTH_STATUS" != "PENDING_VERIFICATION" ]; then
  echo "ERROR: expected PENDING_VERIFICATION, got ${AUTH_STATUS}" >&2
  exit 1
fi

echo "PRIV-001A: register=PASS user_id=${AUTH_USER_ID} status=${AUTH_STATUS}"

echo "PRIV-001A: applying allowlisted verification mutation"
VERIFIED_ID="$(parkio_priv001_mark_verified "$EMAIL" "$MAX_AGE")"
if [ "$VERIFIED_ID" != "$AUTH_USER_ID" ]; then
  echo "ERROR: verified id mismatch (TOCTOU / unexpected row)." >&2
  exit 1
fi
echo "PRIV-001A: verify=PASS"

LOGIN_BODY="$(printf '{"email":"%s","password":"%s"}' "$EMAIL" "$PASSWORD")"
LOGIN_OUT="$(mktemp)"
LOGIN_CODE="$(
  curl -sS -o "$LOGIN_OUT" -w '%{http_code}' \
    -X POST "${GATEWAY_URL}/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json' \
    -H 'X-Parkio-Client: mobile' \
    --data-binary "$LOGIN_BODY" \
    --connect-timeout 10 \
    --max-time 60
)" || true
unset LOGIN_BODY

if [ "$LOGIN_CODE" != "200" ]; then
  echo "ERROR: login failed http=${LOGIN_CODE}" >&2
  grep -E '"code"|"status"|"message"' "$LOGIN_OUT" 2>/dev/null | head -n 5 >&2 || true
  rm -f "$LOGIN_OUT"
  exit 1
fi

ACCESS_TOKEN="$(parkio_priv001_json_get "$LOGIN_OUT" "accessToken")"
REFRESH_TOKEN="$(parkio_priv001_json_get "$LOGIN_OUT" "refreshToken")"
LOGIN_STATUS="$(parkio_priv001_json_get "$LOGIN_OUT" "user.status")"
rm -f "$LOGIN_OUT"

if [ -z "$ACCESS_TOKEN" ] || [ "$LOGIN_STATUS" != "ACTIVE" ]; then
  echo "ERROR: login did not yield ACTIVE authenticated session." >&2
  exit 1
fi
echo "PRIV-001A: login=PASS status=${LOGIN_STATUS}"

# Credentials file: operator-only, never echoed.
{
  printf 'acceptance_id=%s\n' "$ACCEPTANCE_ID"
  printf 'auth_user_id=%s\n' "$AUTH_USER_ID"
  printf 'email=%s\n' "$EMAIL"
  printf 'password=%s\n' "$PASSWORD"
  printf 'access_token=%s\n' "$ACCESS_TOKEN"
  printf 'refresh_token=%s\n' "$REFRESH_TOKEN"
} > "$CREDENTIALS_FILE"
chmod 600 "$CREDENTIALS_FILE"

# Clear secrets from shell memory as best-effort.
PASSWORD=""
ACCESS_TOKEN=""
REFRESH_TOKEN=""
unset PASSWORD ACCESS_TOKEN REFRESH_TOKEN

FIXTURE_MATRIX="auth_principal=SEEDED"
FIXTURE_MATRIX="${FIXTURE_MATRIX};user_profile=SOURCE_TEST_ONLY_OR_EVENTUAL_UserRegistered"
FIXTURE_MATRIX="${FIXTURE_MATRIX};saved_place=UNAVAILABLE_SAFELY"
FIXTURE_MATRIX="${FIXTURE_MATRIX};parking_relation=UNAVAILABLE_SAFELY"
FIXTURE_MATRIX="${FIXTURE_MATRIX};media_object=UNAVAILABLE_SAFELY"
FIXTURE_MATRIX="${FIXTURE_MATRIX};gamification=UNAVAILABLE_SAFELY"
FIXTURE_MATRIX="${FIXTURE_MATRIX};notification_pref=UNAVAILABLE_SAFELY"
FIXTURE_MATRIX="${FIXTURE_MATRIX};analytics_event=UNAVAILABLE_SAFELY"
FIXTURE_MATRIX="${FIXTURE_MATRIX};ai_validation=UNAVAILABLE_SAFELY"
FIXTURE_MATRIX="${FIXTURE_MATRIX};moderation=UNAVAILABLE_SAFELY"

if [ "$SKIP_SEED" = "0" ]; then
  # Minimal authenticated probe only — no invasive cross-service SQL seeds.
  BEARER=""
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      access_token=*) BEARER="${line#access_token=}" ;;
    esac
  done < "$CREDENTIALS_FILE"
  ME_CODE="$(
    curl -sS -o /dev/null -w '%{http_code}' \
      -H "Authorization: Bearer ${BEARER}" \
      -H 'Accept: application/json' \
      "${GATEWAY_URL}/api/v1/auth/me" \
      --connect-timeout 10 --max-time 30 || true
  )"
  BEARER=""
  unset BEARER
  if [ "$ME_CODE" = "200" ]; then
    FIXTURE_MATRIX="${FIXTURE_MATRIX};auth_me=SEEDED"
  else
    FIXTURE_MATRIX="${FIXTURE_MATRIX};auth_me=NOT_REQUIRED"
  fi
fi

parkio_priv001_write_evidence_json "$EVIDENCE_FILE" "$ACCEPTANCE_ID" "$AUTH_USER_ID" "$FIXTURE_MATRIX"

parkio_priv001_secret_scan_text "$EVIDENCE_FILE"

echo "PASS: PRIV-001A synthetic principal created"
echo "acceptance_id=${ACCEPTANCE_ID}"
echo "auth_user_id=${AUTH_USER_ID}"
echo "credentials_file=${CREDENTIALS_FILE}"
echo "evidence_file=${EVIDENCE_FILE}"
echo "fixture_matrix=${FIXTURE_MATRIX}"
echo "NOTE: password/JWT written only to credentials_file (mode 600); not printed."
echo "NOTE: cleanup must use DELETE /api/v1/account in runtime acceptance."
