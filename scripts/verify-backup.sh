#!/usr/bin/env bash
#
# Parkio — verify a database dump is actually RESTORABLE, without touching live data.
#
# It restores the given dump into a DISPOSABLE temporary database inside the same Postgres
# container (e.g. `parkio_auth_verify_<epoch>`), runs a smoke check (restore must apply with
# ON_ERROR_STOP=1 and the public schema must contain at least one table), then DROPs the
# temporary database. Live service databases are never modified.
#
# Usage:
#   scripts/verify-backup.sh <service> <dump-file> [--env-file <path>] [--keep]
#
#   <service>    one of: auth user parking media gamification notification moderation
#                analytics ai-validation
#   <dump-file>  a .sql, .sql.gz, or .sql.gz.enc produced by scripts/backup-databases.sh
#   --env-file   load POSTGRES_*_USER/DB (+ BACKUP_ENCRYPT_PASSPHRASE) from this file
#   --keep       keep the temporary database (skip cleanup) for manual inspection
#
# Example (verify the newest analytics dump):
#   PARKIO_ENV_FILE=docker/.env scripts/verify-backup.sh analytics \
#     "$(ls -dt ./backups/*/ | head -1)analytics.sql.gz"
#
# Exit code 0 = dump restored cleanly into a fresh DB; non-zero = verification failed.

set -euo pipefail

SERVICE=""
DUMP_FILE=""
ENV_FILE="${PARKIO_ENV_FILE:-}"
KEEP="no"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --keep) KEEP="yes"; shift ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
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
  echo "Usage: $0 <service> <dump-file> [--env-file <path>] [--keep]" >&2
  exit 2
fi
if [ ! -f "${DUMP_FILE}" ]; then
  echo "ERROR: dump file not found: ${DUMP_FILE}" >&2
  exit 2
fi

if [ -n "${ENV_FILE}" ]; then
  if [ -f "${ENV_FILE}" ]; then
    set -a; # shellcheck disable=SC1090
    . "${ENV_FILE}"; set +a
  else
    echo "WARN: env file '${ENV_FILE}' not found; relying on current environment." >&2
  fi
fi

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

case "${DUMP_FILE}" in
  *.enc)
    if [ -z "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then
      echo "ERROR: '${DUMP_FILE}' is encrypted but BACKUP_ENCRYPT_PASSPHRASE is not set." >&2
      exit 2
    fi ;;
esac

TMP_DB="${DB_NAME}_verify_$(date -u +%s)"

psql_admin() { docker exec -i "${CONTAINER}" psql -v ON_ERROR_STOP=1 -U "${USER_NAME}" -d postgres "$@"; }

cleanup() {
  if [ "${KEEP}" = "yes" ]; then
    echo "Keeping temporary database '${TMP_DB}' (--keep)."
    return
  fi
  psql_admin -c "DROP DATABASE IF EXISTS \"${TMP_DB}\";" >/dev/null 2>&1 \
    && echo "Dropped temporary database '${TMP_DB}'." \
    || echo "WARN: could not drop temporary database '${TMP_DB}'; drop it manually." >&2
}
trap cleanup EXIT

echo "Verifying '${SERVICE}' dump by restoring into disposable DB '${TMP_DB}' ..."
echo "    container : ${CONTAINER}"
echo "    dump      : ${DUMP_FILE}"

psql_admin -c "CREATE DATABASE \"${TMP_DB}\";" >/dev/null
echo "  created temp DB"

decode() {
  case "${DUMP_FILE}" in
    *.enc) openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_ENCRYPT_PASSPHRASE -in "${DUMP_FILE}" ;;
    *) cat "${DUMP_FILE}" ;;
  esac
}
maybe_gunzip() {
  case "${DUMP_FILE}" in
    *.gz|*.gz.enc) gunzip -c ;;
    *) cat ;;
  esac
}

if ! decode | maybe_gunzip \
    | docker exec -i "${CONTAINER}" psql -v ON_ERROR_STOP=1 -U "${USER_NAME}" -d "${TMP_DB}" >/dev/null; then
  echo "FAIL: dump did not restore cleanly into '${TMP_DB}'." >&2
  exit 1
fi
echo "  restore applied (ON_ERROR_STOP=1)"

TABLE_COUNT="$(docker exec -i "${CONTAINER}" psql -tA -U "${USER_NAME}" -d "${TMP_DB}" \
  -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';" | tr -d '[:space:]')"

if ! [ "${TABLE_COUNT}" -ge 1 ] 2>/dev/null; then
  echo "FAIL: restored DB has no tables in schema 'public' (count='${TABLE_COUNT}')." >&2
  exit 1
fi

echo "PASS: '${SERVICE}' dump is restorable — ${TABLE_COUNT} table(s) in the restored schema."
