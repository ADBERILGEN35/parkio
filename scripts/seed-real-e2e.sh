#!/usr/bin/env bash
#
# Parkio — idempotent seeding of the real-stack E2E test accounts.
#
# The real-stack Playwright suite (frontend/apps/web/playwright.real.config.ts,
# project `real-e2e`) needs ACTIVE + email-verified accounts with USER / MODERATOR /
# ADMIN roles. Production exposes no API to verify an email or elevate a role, so this
# script does the privileged setup directly in the database via `docker exec psql`
# (database-per-service; auth owns credentials/roles/verification, user owns profile).
#
# Why a DB script and not an HTTP endpoint: a public/test seed endpoint is an
# auth-bypass attack surface. SQL via `docker exec` requires shell/host access to the
# box that already controls the data plane, so it adds no new exposure (ai-context/07).
#
# Passwords are NEVER printed and NEVER passed on a command line: the value is exported
# into the container's process environment (`docker exec -e NAME`, value inherited, so it
# is not in argv) and read inside psql with `\getenv`. BCrypt hashing happens in-database
# via pgcrypto `crypt(..., gen_salt('bf', 10))`, matching Spring's BCryptPasswordEncoder
# (cost 10, $2a$), so the raw password never leaves the DB process.
#
# Idempotent: re-running upserts by email (stored lowercased), never duplicates a user or
# a role, always ensures email_verified=TRUE + status=ACTIVE, and sets the exact role set.
# The password is set on first creation; on re-run it is changed only with --update-passwords.
#
# Usage (local Docker — the default):
#   docker compose -f docker/docker-compose.yml -f docker/docker-compose.apps.yml up -d
#   export PARKIO_REAL_USER_EMAIL=user@real-e2e.parkio.local
#   export PARKIO_REAL_USER_PASSWORD='StrongParkio123'
#   # optional elevated accounts:
#   export PARKIO_REAL_MODERATOR_EMAIL=moderator@real-e2e.parkio.local
#   export PARKIO_REAL_MODERATOR_PASSWORD='StrongParkio123'
#   export PARKIO_REAL_ADMIN_EMAIL=admin@real-e2e.parkio.local
#   export PARKIO_REAL_ADMIN_PASSWORD='StrongParkio123'
#   PARKIO_ENV_FILE=docker/.env ./scripts/seed-real-e2e.sh            # --target local
#
# Usage (hosted-beta — SSH to the VPS FIRST, then run there):
#   ssh deploy@beta-host
#   cd /opt/parkio
#   export PARKIO_REAL_USER_EMAIL=...    PARKIO_REAL_USER_PASSWORD=...
#   PARKIO_ENV_FILE=docker/.env ./scripts/seed-real-e2e.sh --target hosted-beta
#
# Flags:
#   --target local|hosted-beta|production  Where the containers live (default: local).
#   --update-passwords                     Reset the password of an existing account.
#   --auth-only                            Skip user-service profile seeding (auth + roles only).
#   --print-env                            Print the matching PARKIO_REAL_* export lines (no secrets).
#   -h | --help                            This help.
#
# Safety:
#   * production refuses to run unless PARKIO_CONFIRM_PRODUCTION=I_UNDERSTAND is set.
#   * Use dedicated test emails (default domain real-e2e.parkio.local). Pointing the
#     PARKIO_REAL_*_EMAIL vars at real human accounts will overwrite their credentials.

set -euo pipefail

# --------------------------------------------------------------------------- #
# Args + environment                                                          #
# --------------------------------------------------------------------------- #
TARGET="local"
UPDATE_PASSWORDS="0"
AUTH_ONLY="0"
PRINT_ENV="0"

while [ $# -gt 0 ]; do
  case "$1" in
    --target) TARGET="${2:-}"; shift 2 ;;
    --target=*) TARGET="${1#*=}"; shift ;;
    --update-passwords) UPDATE_PASSWORDS="1"; shift ;;
    --auth-only) AUTH_ONLY="1"; shift ;;
    --print-env) PRINT_ENV="1"; shift ;;
    -h|--help) sed -n '2,60p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

case "$TARGET" in
  local|hosted-beta) ;;
  production)
    if [ "${PARKIO_CONFIRM_PRODUCTION:-}" != "I_UNDERSTAND" ]; then
      echo "REFUSING to seed test accounts against PRODUCTION." >&2
      echo "Set PARKIO_CONFIRM_PRODUCTION=I_UNDERSTAND to override (you almost never want this)." >&2
      exit 3
    fi
    echo "WARNING: seeding test accounts against PRODUCTION (explicitly confirmed)." >&2
    ;;
  *) echo "Invalid --target '$TARGET' (expected local|hosted-beta|production)." >&2; exit 2 ;;
esac

# Optionally load the stack's env file so POSTGRES_*_USER/DB resolve (like backup-databases.sh).
ENV_FILE="${PARKIO_ENV_FILE:-}"
if [ -n "${ENV_FILE}" ]; then
  if [ -f "${ENV_FILE}" ]; then
    set -a
    # shellcheck disable=SC1090
    . "${ENV_FILE}"
    set +a
  else
    echo "WARN: PARKIO_ENV_FILE='${ENV_FILE}' not found; relying on current environment." >&2
  fi
fi

AUTH_CONTAINER="${PARKIO_AUTH_PG_CONTAINER:-parkio-postgres-auth}"
AUTH_DB_USER="${POSTGRES_AUTH_USER:-parkio_auth}"
AUTH_DB="${POSTGRES_AUTH_DB:-parkio_auth}"
USER_CONTAINER="${PARKIO_USER_PG_CONTAINER:-parkio-postgres-user}"
USER_DB_USER="${POSTGRES_USER_USER:-parkio_user}"
USER_DB="${POSTGRES_USER_DB:-parkio_user}"

EMAIL_DOMAIN="${PARKIO_REAL_E2E_EMAIL_DOMAIN:-real-e2e.parkio.local}"

# --print-env: emit copy-paste exports (names only echo the values the caller already set).
if [ "$PRINT_ENV" = "1" ]; then
  echo "# Real-stack E2E account env (fill in passwords yourself; nothing is generated here):"
  echo "export PARKIO_REAL_USER_EMAIL=user@${EMAIL_DOMAIN}"
  echo "export PARKIO_REAL_USER_PASSWORD='change-me'"
  echo "export PARKIO_REAL_MODERATOR_EMAIL=moderator@${EMAIL_DOMAIN}"
  echo "export PARKIO_REAL_MODERATOR_PASSWORD='change-me'"
  echo "export PARKIO_REAL_ADMIN_EMAIL=admin@${EMAIL_DOMAIN}"
  echo "export PARKIO_REAL_ADMIN_PASSWORD='change-me'"
  exit 0
fi

# --------------------------------------------------------------------------- #
# Helpers                                                                     #
# --------------------------------------------------------------------------- #
lc() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

require_container() {
  local c="$1"
  if ! docker inspect "$c" >/dev/null 2>&1; then
    echo "ERROR: container '$c' not found. Is the $TARGET stack running?" >&2
    exit 4
  fi
}

# psql against a DB over the container's trusted local socket. Extra args appended.
psql_db() { # <container> <user> <db> [psql args...]
  local container="$1" user="$2" db="$3"; shift 3
  docker exec -i "$container" psql -v ON_ERROR_STOP=1 -X -U "$user" -d "$db" "$@"
}

if [ -z "${PARKIO_REAL_USER_EMAIL:-}" ] || [ -z "${PARKIO_REAL_USER_PASSWORD:-}" ]; then
  echo "ERROR: PARKIO_REAL_USER_EMAIL and PARKIO_REAL_USER_PASSWORD are required." >&2
  exit 5
fi

require_container "$AUTH_CONTAINER"
[ "$AUTH_ONLY" = "1" ] || require_container "$USER_CONTAINER"

# Ensure pgcrypto once (needed for in-DB BCrypt). Idempotent; requires the container's
# superuser, which is POSTGRES_USER on the official image.
psql_db "$AUTH_CONTAINER" "$AUTH_DB_USER" "$AUTH_DB" \
  -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;" >/dev/null

# --------------------------------------------------------------------------- #
# Seed one account                                                            #
# --------------------------------------------------------------------------- #
SEEDED=()

seed_account() { # <email> <password_env_var_name> <display_name> <roles_csv>
  local email_raw="$1" pw_var="$2" display_name="$3" roles_csv="$4"
  local email; email="$(lc "$email_raw")"

  # --- auth-service: upsert credentials, verification, status (BCrypt in-DB) ---
  # Password value is inherited via the container env (-e NAME) and read with \getenv,
  # so it never appears in argv or in this script's output.
  docker exec -e "$pw_var" -i "$AUTH_CONTAINER" \
    psql -v ON_ERROR_STOP=1 -X -U "$AUTH_DB_USER" -d "$AUTH_DB" \
      -v email="$email" -v update_pw="$UPDATE_PASSWORDS" <<SQL >/dev/null
-- Read the password from the container env into psql var :pw (never echoed). If it was
-- not passed through, :'pw' interpolation below fails under ON_ERROR_STOP — a clear error.
\getenv pw $pw_var

INSERT INTO auth_users (id, email, password_hash, status,
                        email_verified, email_verified_at, session_epoch,
                        created_at, updated_at)
VALUES (gen_random_uuid(), :'email', crypt(:'pw', gen_salt('bf', 10)), 'ACTIVE',
        TRUE, now(), 0, now(), now())
ON CONFLICT (email) DO UPDATE SET
  password_hash = CASE WHEN :update_pw = 1
                       THEN crypt(:'pw', gen_salt('bf', 10))
                       ELSE auth_users.password_hash END,
  status = 'ACTIVE',
  email_verified = TRUE,
  email_verified_at = COALESCE(auth_users.email_verified_at, now()),
  email_verification_token_hash = NULL,
  email_verification_expires_at = NULL,
  updated_at = now();

-- Exact role set: add the requested roles, drop any others (idempotent, no dupes).
WITH u AS (SELECT id FROM auth_users WHERE email = :'email')
INSERT INTO auth_user_roles (user_id, role_id)
SELECT u.id, r.id FROM u CROSS JOIN roles r
WHERE r.name = ANY (string_to_array('$roles_csv', ','))
ON CONFLICT DO NOTHING;

WITH u AS (SELECT id FROM auth_users WHERE email = :'email')
DELETE FROM auth_user_roles ar USING u
WHERE ar.user_id = u.id
  AND ar.role_id NOT IN (SELECT id FROM roles WHERE name = ANY (string_to_array('$roles_csv', ',')));
SQL

  # Canonical auth id (authoritative — reused for the cross-service profile link).
  # psql interpolates :vars only from stdin/-f, never from a -c SQL string, so feed the
  # query on stdin like the upsert blocks above; with -c the :'email' would reach the
  # server literally and error. The value still arrives as a quoted -v parameter.
  local auth_id
  auth_id="$(printf '%s' "SELECT id FROM auth_users WHERE email = :'email';" \
    | psql_db "$AUTH_CONTAINER" "$AUTH_DB_USER" "$AUTH_DB" -tAq -v email="$email")"

  # --- user-service: matching ACTIVE profile (+ trust/preferences projections) ---
  if [ "$AUTH_ONLY" != "1" ]; then
    psql_db "$USER_CONTAINER" "$USER_DB_USER" "$USER_DB" \
      -v auth_id="$auth_id" -v email="$email" -v display_name="$display_name" <<'SQL' >/dev/null
WITH up AS (
  INSERT INTO user_profiles (id, auth_user_id, email, display_name, status, created_at, updated_at)
  VALUES (gen_random_uuid(), :'auth_id', :'email', :'display_name', 'ACTIVE', now(), now())
  ON CONFLICT (auth_user_id) DO UPDATE SET
    email = EXCLUDED.email,
    display_name = EXCLUDED.display_name,
    status = 'ACTIVE',
    updated_at = now()
  RETURNING id
)
INSERT INTO user_preferences (id, user_profile_id, preferred_radius_meters, notifications_enabled)
SELECT gen_random_uuid(), up.id, 1000, TRUE FROM up
ON CONFLICT (user_profile_id) DO NOTHING;

INSERT INTO user_trust_profiles (id, user_profile_id, trust_score, trust_band, total_points, current_level)
SELECT gen_random_uuid(), p.id, 100, 'HIGH_TRUST', 0, 1
FROM user_profiles p WHERE p.auth_user_id = :'auth_id'
ON CONFLICT (user_profile_id) DO NOTHING;
SQL
  fi

  SEEDED+=("${email} -> [${roles_csv}] (${auth_id})")
  echo "  ok  ${email}  roles=[${roles_csv}]  status=ACTIVE verified=TRUE"
}

# --------------------------------------------------------------------------- #
# Drive the three accounts (MODERATOR/ADMIN optional)                         #
# --------------------------------------------------------------------------- #
echo "Seeding real-stack E2E accounts (target=${TARGET}, auth_only=${AUTH_ONLY}, update_passwords=${UPDATE_PASSWORDS})"

seed_account "$PARKIO_REAL_USER_EMAIL" PARKIO_REAL_USER_PASSWORD "Parkio Real USER" "USER"

if [ -n "${PARKIO_REAL_MODERATOR_EMAIL:-}" ] && [ -n "${PARKIO_REAL_MODERATOR_PASSWORD:-}" ]; then
  seed_account "$PARKIO_REAL_MODERATOR_EMAIL" PARKIO_REAL_MODERATOR_PASSWORD "Parkio Real MODERATOR" "USER,MODERATOR"
else
  echo "  skip MODERATOR (set PARKIO_REAL_MODERATOR_EMAIL/PASSWORD to enable)"
fi

if [ -n "${PARKIO_REAL_ADMIN_EMAIL:-}" ] && [ -n "${PARKIO_REAL_ADMIN_PASSWORD:-}" ]; then
  seed_account "$PARKIO_REAL_ADMIN_EMAIL" PARKIO_REAL_ADMIN_PASSWORD "Parkio Real ADMIN" "USER,ADMIN"
else
  echo "  skip ADMIN (set PARKIO_REAL_ADMIN_EMAIL/PASSWORD to enable)"
fi

echo
echo "Done. Seeded ${#SEEDED[@]} account(s):"
for s in "${SEEDED[@]}"; do echo "  - ${s}"; done
echo
echo "Existing sessions still carry old JWT roles; sign out/in (or wait for token expiry)"
echo "after a role change so the new roles take effect."
