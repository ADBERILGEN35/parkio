#!/usr/bin/env bash
# PRIV-001A — read-only residual classification for a synthetic principal.
#
# Never mutates. Never dumps email, password hash, tokens, or message bodies.
# Requires the same production opt-in flags as the create harness.
#
# Usage:
#   scripts/acceptance/inspect-priv001-synthetic-residue.sh \
#     --environment invite-production \
#     --confirm-synthetic-only \
#     --auth-user-id <uuid> \
#     [--email priv001a-...@priv001a.parkio.invalid]
#
# shellcheck shell=bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=scripts/lib/priv001-synthetic.sh
source "${ROOT}/scripts/lib/priv001-synthetic.sh"

ENVIRONMENT=""
CONFIRM=""
AUTH_USER_ID=""
EMAIL=""

usage() {
  cat <<'EOF'
Usage:
  scripts/acceptance/inspect-priv001-synthetic-residue.sh \
    --environment invite-production \
    --confirm-synthetic-only \
    --auth-user-id <uuid> \
    [--email priv001a-<id>@priv001a.parkio.invalid]

Prints classification counts only. Read-only.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --environment) ENVIRONMENT="${2-}"; shift 2 ;;
    --confirm-synthetic-only) CONFIRM="yes"; shift ;;
    --auth-user-id) AUTH_USER_ID="${2-}"; shift 2 ;;
    --email) EMAIL="${2-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

export PARKIO_PRIV001_ENVIRONMENT="$ENVIRONMENT"
export PARKIO_PRIV001_CONFIRM_SYNTHETIC_ONLY="$CONFIRM"
if [ -z "${PARKIO_DEPLOYMENT_PROFILE:-}" ] && [ "$ENVIRONMENT" = "invite-production" ]; then
  export PARKIO_DEPLOYMENT_PROFILE=invite-production
fi

parkio_priv001_require_invite_production_opt_in

if ! printf '%s' "$AUTH_USER_ID" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'; then
  echo "ERROR: --auth-user-id must be a lowercase UUID." >&2
  exit 2
fi

if [ -n "$EMAIL" ]; then
  parkio_priv001_require_email "$EMAIL"
fi

# Parameterized inspection. Never SELECT email/password_hash/token columns into output.
SQL=$(cat <<'SQL'
SELECT json_build_object(
  'auth_state', COALESCE((SELECT status FROM auth_users WHERE id = :'uid'::uuid), 'ABSENT'),
  'email_verified', COALESCE((SELECT email_verified::text FROM auth_users WHERE id = :'uid'::uuid), 'ABSENT'),
  'auth_row_count', (SELECT count(*)::int FROM auth_users WHERE id = :'uid'::uuid),
  'erasure_request_count', (SELECT count(*)::int FROM erasure_requests WHERE auth_user_id = :'uid'::uuid),
  'participant_ack_count', (
      SELECT count(*)::int
      FROM erasure_service_acks a
      JOIN erasure_requests r ON r.id = a.erasure_request_id
      WHERE r.auth_user_id = :'uid'::uuid
  ),
  'tombstone_count', (SELECT count(*)::int FROM erased_user_tombstones WHERE auth_user_id = :'uid'::uuid),
  'hard_delete_residue_count', (
      SELECT count(*)::int FROM auth_users
      WHERE id = :'uid'::uuid
        AND status NOT IN ('ERASED', 'ERASURE_IN_PROGRESS')
        AND email_verified = TRUE
  ),
  'deidentified_residue_count', (
      SELECT count(*)::int FROM auth_users
      WHERE id = :'uid'::uuid
        AND status = 'ERASED'
  ),
  'forbidden_user_id_hits', (
      SELECT count(*)::int FROM auth_users
      WHERE id = :'uid'::uuid
        AND email NOT LIKE 'erased-%@invalid.localhost'
        AND status = 'ERASED'
  ),
  'media_object_count', 0,
  'cross_service_sql', 'NOT_EXECUTED_BY_CONTRACT'
);
SQL
)

OUT="$(parkio_priv001_auth_psql -tAc -v uid="$AUTH_USER_ID" -c "$SQL")"
# Validate JSON-ish and scan for secrets.
printf '%s\n' "$OUT" | parkio_priv001_json_loads_stdin >/dev/null
TMP="$(mktemp)"
printf '%s\n' "$OUT" > "$TMP"
parkio_priv001_secret_scan_text "$TMP"
rm -f "$TMP"

echo "PRIV-001A residue inspection (read-only)"
echo "auth_user_id=${AUTH_USER_ID}"
if [ -n "$EMAIL" ]; then
  echo "email_allowlist=PASS"
fi
printf '%s\n' "$OUT"
