#!/usr/bin/env bash
#
# Parkio — restore ONE service database from a dump produced by scripts/backup-databases.sh.
#
# !!! DESTRUCTIVE !!!  The dumps are taken with `pg_dump --clean --if-exists`, so restoring
# DROPs and recreates every object in the target database, OVERWRITING current data. There is
# no undo. Take a fresh backup first, and prefer restoring into a disposable database (see
# scripts/verify-backup.sh) when you only want to test a dump.
#
# Usage:
#   scripts/restore-database.sh <service> <dump-file> [--yes] [--env-file <path>]
#
#   <service>    one of: auth user parking media gamification notification moderation
#                analytics ai-validation
#   <dump-file>  path to a .sql, .sql.gz, or .sql.gz.enc file
#   --yes        skip the interactive confirmation (for automation / drills)
#   --env-file   load POSTGRES_*_USER/DB (and BACKUP_ENCRYPT_PASSPHRASE) from this file
#                (defaults to $PARKIO_ENV_FILE if set)
#
# Examples:
#   scripts/restore-database.sh auth /var/backups/parkio/<stamp>/auth.sql.gz
#   PARKIO_ENV_FILE=docker/.env scripts/restore-database.sh analytics ./backups/<stamp>/analytics.sql.gz --yes
#   # encrypted dump (.enc) needs BACKUP_ENCRYPT_PASSPHRASE in the environment/.env:
#   scripts/restore-database.sh media ./backups/<stamp>/media.sql.gz.enc
#
# Restores run with ON_ERROR_STOP=1 inside a single transaction-aware psql session, so a
# broken dump fails loudly instead of leaving a half-applied schema.

set -euo pipefail

SERVICE=""
DUMP_FILE=""
ASSUME_YES="no"
ENV_FILE="${PARKIO_ENV_FILE:-}"

# ---- parse args ----
while [ "$#" -gt 0 ]; do
  case "$1" in
    --yes) ASSUME_YES="yes"; shift ;;
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    -*) echo "ERROR: unknown flag '$1'" >&2; exit 2 ;;
    *)
      if [ -z "${SERVICE}" ]; then SERVICE="$1"
      elif [ -z "${DUMP_FILE}" ]; then DUMP_FILE="$1"
      else echo "ERROR: unexpected argument '$1'" >&2; exit 2
      fi
      shift ;;
  esac
done

if [ -z "${SERVICE}" ] || [ -z "${DUMP_FILE}" ]; then
  echo "Usage: $0 <service> <dump-file> [--yes] [--env-file <path>]" >&2
  exit 2
fi

if [ ! -f "${DUMP_FILE}" ]; then
  echo "ERROR: dump file not found: ${DUMP_FILE}" >&2
  exit 2
fi

# ---- optional env file ----
if [ -n "${ENV_FILE}" ]; then
  if [ -f "${ENV_FILE}" ]; then
    set -a; # shellcheck disable=SC1090
    . "${ENV_FILE}"; set +a
  else
    echo "WARN: env file '${ENV_FILE}' not found; relying on current environment." >&2
  fi
fi

# ---- resolve service -> container:user:db ----
# Mirrors docker/docker-compose.yml container_name + POSTGRES_* env (defaults match .env.example).
resolve() {
  case "$1" in
    auth)          echo "parkio-postgres-auth:${POSTGRES_AUTH_USER:-parkio_auth}:${POSTGRES_AUTH_DB:-parkio_auth}" ;;
    user)          echo "parkio-postgres-user:${POSTGRES_USER_USER:-parkio_user}:${POSTGRES_USER_DB:-parkio_user}" ;;
    parking)       echo "parkio-postgres-parking:${POSTGRES_PARKING_USER:-parkio_parking}:${POSTGRES_PARKING_DB:-parkio_parking}" ;;
    media)         echo "parkio-postgres-media:${POSTGRES_MEDIA_USER:-parkio_media}:${POSTGRES_MEDIA_DB:-parkio_media}" ;;
    gamification)  echo "parkio-postgres-gamification:${POSTGRES_GAMIFICATION_USER:-parkio_gamification}:${POSTGRES_GAMIFICATION_DB:-parkio_gamification}" ;;
    notification)  echo "parkio-postgres-notification:${POSTGRES_NOTIFICATION_USER:-parkio_notification}:${POSTGRES_NOTIFICATION_DB:-parkio_notification}" ;;
    moderation)    echo "parkio-postgres-moderation:${POSTGRES_MODERATION_USER:-parkio_moderation}:${POSTGRES_MODERATION_DB:-parkio_moderation}" ;;
    analytics)     echo "parkio-postgres-analytics:${POSTGRES_ANALYTICS_USER:-parkio_analytics}:${POSTGRES_ANALYTICS_DB:-parkio_analytics}" ;;
    ai-validation) echo "parkio-postgres-ai-validation:${POSTGRES_AIVALIDATION_USER:-parkio_aivalidation}:${POSTGRES_AIVALIDATION_DB:-parkio_aivalidation}" ;;
    *) return 1 ;;
  esac
}

if ! TRIPLE="$(resolve "${SERVICE}")"; then
  echo "ERROR: unknown service '${SERVICE}'." >&2
  echo "       Valid: auth user parking media gamification notification moderation analytics ai-validation" >&2
  exit 2
fi
IFS=":" read -r CONTAINER USER_NAME DB_NAME <<< "${TRIPLE}"

if ! docker inspect "${CONTAINER}" >/dev/null 2>&1; then
  echo "ERROR: container '${CONTAINER}' not found / not running." >&2
  exit 1
fi

# ---- decoder pipeline based on file suffix ----
NEEDS_DECRYPT="no"
case "${DUMP_FILE}" in
  *.enc) NEEDS_DECRYPT="yes" ;;
esac
if [ "${NEEDS_DECRYPT}" = "yes" ] && [ -z "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then
  echo "ERROR: '${DUMP_FILE}' is encrypted but BACKUP_ENCRYPT_PASSPHRASE is not set." >&2
  exit 2
fi

# ---- confirmation ----
echo "About to RESTORE (overwrite) the '${SERVICE}' database:"
echo "    container : ${CONTAINER}"
echo "    database  : ${DB_NAME} (user ${USER_NAME})"
echo "    from dump : ${DUMP_FILE}"
echo "    encrypted : ${NEEDS_DECRYPT}"
echo "*** This DROPs and recreates existing objects — current data will be OVERWRITTEN. ***"
if [ "${ASSUME_YES}" != "yes" ]; then
  printf "Type the service name '%s' to proceed: " "${SERVICE}"
  read -r reply
  if [ "${reply}" != "${SERVICE}" ]; then
    echo "Aborted (no changes made)." >&2
    exit 1
  fi
fi

# ---- stream dump -> (decrypt) -> gunzip -> psql ----
echo "Restoring ${SERVICE} ..."
decode() {
  if [ "${NEEDS_DECRYPT}" = "yes" ]; then
    openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_ENCRYPT_PASSPHRASE -in "${DUMP_FILE}"
  else
    cat "${DUMP_FILE}"
  fi
}
maybe_gunzip() {
  case "${DUMP_FILE}" in
    *.gz|*.gz.enc) gunzip -c ;;
    *) cat ;;
  esac
}

if decode | maybe_gunzip \
    | docker exec -i "${CONTAINER}" psql -v ON_ERROR_STOP=1 -U "${USER_NAME}" -d "${DB_NAME}" >/dev/null; then
  echo "Restore of '${SERVICE}' completed successfully."
else
  echo "ERROR: restore of '${SERVICE}' FAILED. The database may be partially restored." >&2
  exit 1
fi
