#!/usr/bin/env bash
# Read-only check for the documented real-e2e accounts on a hosted stack (owner decision 2026-10-08 item 7).
#
# Runs ON THE HOST, in the compose project directory, by the operator. It only SELECTs from the auth
# database through the postgres-auth container: no password hash, token or personal data is printed;
# the three documented e-mail addresses are test addresses on a .local domain. Exit code: 0 when none
# of the accounts exists, 10 when at least one exists (then follow docs/operations/real-e2e-account-rotation.md),
# 2 on a usage error, 1 when the query itself fails (compose or psql error: nothing can be concluded).
#
# Usage: PARKIO_ENV_FILE=docker/.env scripts/ops/verify-real-e2e-accounts.sh [-f docker/docker-compose.yml ...]
set -euo pipefail

ENV_FILE="${PARKIO_ENV_FILE:-docker/.env}"
[ -f "$ENV_FILE" ] || { echo "ERROR: env file '$ENV_FILE' not found (set PARKIO_ENV_FILE)" >&2; exit 2; }
COMPOSE_FILES=("$@")
[ ${#COMPOSE_FILES[@]} -gt 0 ] || COMPOSE_FILES=(-f docker/docker-compose.yml)
# A key missing from the env file is not an error (the defaults below apply): grep's exit 1 must not
# end the script under `set -o pipefail`.
env_get() { { grep -E "^$1=" "$ENV_FILE" || true; } | tail -1 | cut -d= -f2- | sed -e "s/^['\"]//" -e "s/['\"]$//"; }
DB="$(env_get POSTGRES_AUTH_DB)"; DB="${DB:-parkio_auth}"
USER_NAME="$(env_get POSTGRES_AUTH_USER)"; USER_NAME="${USER_NAME:-parkio_auth}"

# The documented addresses (scripts/seed-real-e2e.sh defaults) and anything else on the test domain.
SQL=$(cat <<'SQL'
SELECT u.email,
       u.status,
       to_char(u.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS created_utc,
       to_char(u.updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS updated_utc,
       COALESCE(string_agg(DISTINCT r.name, ',' ORDER BY r.name), '') AS roles,
       (SELECT count(*) FROM refresh_tokens t WHERE t.user_id = u.id AND NOT t.revoked AND t.expires_at > now()) AS active_refresh_tokens
  FROM auth_users u
  LEFT JOIN auth_user_roles ur ON ur.user_id = u.id
  LEFT JOIN roles r ON r.id = ur.role_id
 WHERE u.email IN ('user@real-e2e.parkio.local', 'moderator@real-e2e.parkio.local', 'admin@real-e2e.parkio.local')
    OR u.email LIKE '%@real-e2e.parkio.local'
 GROUP BY u.id, u.email, u.status, u.created_at, u.updated_at
 ORDER BY u.email;
SQL
)
echo "== real-e2e accounts on $(hostname) (database $DB; read-only; $(date -u +%Y-%m-%dT%H:%M:%SZ)) =="
OUT="$(docker compose --env-file "$ENV_FILE" "${COMPOSE_FILES[@]}" exec -T postgres-auth \
  psql -v ON_ERROR_STOP=1 -U "$USER_NAME" -d "$DB" --no-align --field-separator ' | ' --pset footer=off -c "$SQL")"
printf '%s\n' "$OUT"
ROWS=$(printf '%s\n' "$OUT" | tail -n +2 | grep -c . || true)
if [ "$ROWS" -eq 0 ]; then
  echo "RESULT: none of the real-e2e accounts exists on this host"
  exit 0
fi
echo "RESULT: $ROWS real-e2e account(s) exist — treat the documented password as live; see docs/operations/real-e2e-account-rotation.md"
exit 10
