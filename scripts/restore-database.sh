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
#                [--recovery-cutoff <ISO-8601-UTC>] [--isolated-fixture] [--isolated-ticket FILE]
# Standalone production apply is BLOCKED: this path does not replay erasures
# and has no verified coverage evidence. --isolated-fixture does not
# self-authorize; a destination-bound orchestrator ticket is required.
#
#   <service>    one of: auth gateway user parking media gamification notification moderation
#                analytics ai-validation
#   <dump-file>  path to a .sql, .sql.gz, or .sql.gz.enc file
#   --yes        skip the interactive confirmation (for automation / drills). Command line only:
#                ASSUME_YES from the environment or the env file is ignored with a WARN.
#   --env-file   load POSTGRES_*_USER/DB (and BACKUP_ENCRYPT_PASSPHRASE) from this file
#                (defaults to $PARKIO_ENV_FILE if set). It is shell code: a line there that
#                assigns the service, the dump or the env file stops this script (exit 1), and one
#                that sets, changes or clears a PARKIO_RESTORE_* control (--recovery-cutoff,
#                --isolated-ticket, ...) stops it with exit 2.
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

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/backup-common.sh
source "${SCRIPT_DIR}/lib/backup-common.sh"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# Read-only like the command-line values below: an env-file line cannot point this run at the scripts
# of another checkout.
readonly SCRIPT_DIR ROOT
# shellcheck source=lib/restore-safe-preflight.sh
source "${SCRIPT_DIR}/lib/restore-safe-preflight.sh"

SERVICE=""
DUMP_FILE=""
# --yes counts only on the command line. The env file is sourced below as shell code under set -a, so
# the switch is parsed into a private name and becomes ASSUME_YES after it.
arg_assume_yes="no"
ENV_FILE="${PARKIO_ENV_FILE:-}"

# A value option must not take the next option as its value ("--env-file --yes").
option_value() {
  case "${2-}" in
    "" | -*)
      echo "ERROR: $1 needs a value; got '${2-}'" >&2
      exit 2
      ;;
  esac
}

# ---- parse args ----
while [ "$#" -gt 0 ]; do
  case "$1" in
    --yes) arg_assume_yes="yes"; shift ;;
    --env-file) option_value "$1" "${2-}"; ENV_FILE="$2"; shift 2 ;;
    --recovery-cutoff) option_value "$1" "${2-}"; PARKIO_RESTORE_RECOVERY_CUTOFF="$2"; shift 2 ;;
    --isolated-fixture) PARKIO_RESTORE_ISOLATED_FIXTURE=1; shift ;;
    --isolated-ticket) option_value "$1" "${2-}"; PARKIO_RESTORE_ISOLATED_TICKET="$2"; shift 2 ;;
    -h|--help) sed -n '2,35p' "$0"; exit 0 ;;
    -*) echo "ERROR: unknown flag '$1'" >&2; exit 2 ;;
    *)
      if [ -z "${SERVICE}" ]; then SERVICE="$1"
      elif [ -z "${DUMP_FILE}" ]; then DUMP_FILE="$1"
      else echo "ERROR: unexpected argument '$1'" >&2; exit 2
      fi
      shift ;;
  esac
done
# The env file is shell code: a line that assigns or unsets one of these (or DUMP_REAL / STAMP_DIR
# below) fails the load under set -e, and the run exits 1 before any restore step.
readonly SERVICE DUMP_FILE ENV_FILE arg_assume_yes

if [ -z "${SERVICE}" ] || [ -z "${DUMP_FILE}" ]; then
  echo "Usage: $0 <service> <dump-file> [--yes] [--env-file <path>]" >&2
  exit 2
fi

if [ ! -f "${DUMP_FILE}" ]; then
  echo "ERROR: dump file not found: ${DUMP_FILE}" >&2
  exit 2
fi

DUMP_REAL="$(parkio_restore_realpath "${DUMP_FILE}")"
if [ -n "${PARKIO_RESTORE_STAMP_DIR:-}" ]; then
  STAMP_DIR="$(parkio_restore_realpath "${PARKIO_RESTORE_STAMP_DIR}")"
else
  STAMP_DIR="$(cd "$(dirname "${DUMP_REAL}")" && pwd)"
fi
if ! parkio_restore_is_within "${DUMP_REAL}" "${STAMP_DIR}"; then
  echo "ERROR: dump path escapes the selected stamp directory." >&2
  exit 2
fi
case "${DUMP_REAL}" in
  *".."*) echo "ERROR: dump path must not contain .. after resolution." >&2; exit 2 ;;
esac
readonly DUMP_REAL STAMP_DIR

# ---- optional env file (caller secrets win over blank placeholders) ----
# The restore controls (PARKIO_RESTORE_*) come from the command line or the caller's environment
# only: an env file that sets, changes or clears one stops the run here (exit 2).
restore_controls="$(parkio_restore_controls)"
readonly restore_controls
parkio_backup_load_env "${ENV_FILE}"
parkio_restore_refuse_env_controls "${restore_controls}" || exit 2
if [ -n "${ASSUME_YES+set}" ]; then
  echo "WARN: ignoring ASSUME_YES from the environment or env file; only --yes counts." >&2
fi
ASSUME_YES="${arg_assume_yes}"
if ! parkio_restore_accept_isolated_fixture "${STAMP_DIR}"; then
  exit 2
fi
parkio_restore_refuse_standalone_database || exit 3
parkio_restore_refuse_unverified_production || exit 3

if ! parkio_restore_preflight_done; then
  if [ ! -f "${STAMP_DIR}/COMPLETE" ] || [ ! -f "${STAMP_DIR}/backup-manifest.json" ]; then
    echo "ERROR: refusing dump that is not inside a COMPLETE stamp: ${STAMP_DIR}" >&2
    exit 2
  fi
  echo "=== stamp preflight (databases) ==="
  if ! parkio_restore_run_stamp_preflight "${STAMP_DIR}" "databases"; then
    echo "ERROR: stamp preflight failed; nothing was decrypted or restored." >&2
    exit 1
  fi
  parkio_restore_require_cutoff_unless_exempt || exit 2
  echo "=== erasure coverage through ${PARKIO_RESTORE_RECOVERY_CUTOFF} ==="
  set +e
  parkio_restore_run_coverage "${STAMP_DIR}" "${PARKIO_RESTORE_RECOVERY_CUTOFF}"
  coverage_rc=$?
  set -e
  if [ "${coverage_rc}" -eq 3 ]; then
    echo "ERROR: erasure evidence does not reach the recovery cutoff; restore BLOCKED." >&2
    exit 3
  elif [ "${coverage_rc}" -ne 0 ]; then
    echo "ERROR: erasure coverage evidence is invalid." >&2
    exit 1
  fi
fi

# Isolated apply uses ticket identities after live re-inspect. Production
# names are only resolved on the blocked production path and must never be
# used as a fallback from --isolated-fixture.
APPLY_REF=""
if parkio_restore_isolated_fixture_ok; then
  DEST_JSON="$(parkio_restore_isolated_postgres_json "${SERVICE}")" || {
    echo "ERROR: isolated ticket does not authorize postgres service '${SERVICE}'." >&2
    exit 2
  }
  CONTAINER="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["containerName"])' <<<"${DEST_JSON}")"
  APPLY_REF="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["containerId"])' <<<"${DEST_JSON}")"
  USER_NAME="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["user"])' <<<"${DEST_JSON}")"
  DB_NAME="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["database"])' <<<"${DEST_JSON}")"
else
  resolve() {
    case "$1" in
      auth)          echo "parkio-postgres-auth:${POSTGRES_AUTH_USER:-parkio_auth}:${POSTGRES_AUTH_DB:-parkio_auth}" ;;
      gateway)       echo "parkio-postgres-gateway:${POSTGRES_GATEWAY_USER:-parkio_gateway}:${POSTGRES_GATEWAY_DB:-parkio_gateway}" ;;
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
    echo "       Valid: auth gateway user parking media gamification notification moderation analytics ai-validation" >&2
    exit 2
  fi
  IFS=":" read -r CONTAINER USER_NAME DB_NAME <<< "${TRIPLE}"
  APPLY_REF="${CONTAINER}"
  if ! docker inspect "${CONTAINER}" >/dev/null 2>&1; then
    echo "ERROR: container '${CONTAINER}' not found / not running." >&2
    exit 1
  fi
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

PROFILE_FILE="$(mktemp "${TMPDIR:-/tmp}/parkio-dump-profile.XXXXXX")"
if [ -n "${PARKIO_RESTORE_DUMP_PROFILE:-}" ] && [ -f "${PARKIO_RESTORE_DUMP_PROFILE}" ]; then
  cp "${PARKIO_RESTORE_DUMP_PROFILE}" "${PROFILE_FILE}"
elif [ -f "${STAMP_DIR}/${SERVICE}.dump-profile.json" ]; then
  cp "${STAMP_DIR}/${SERVICE}.dump-profile.json" "${PROFILE_FILE}"
else
  if ! decode | maybe_gunzip | python3 "${ROOT}/scripts/lib/restore-dump-profile.py" profile > "${PROFILE_FILE}"; then
    echo "ERROR: cannot profile dump for client compatibility; nothing was applied." >&2
    rm -f "${PROFILE_FILE}"
    exit 1
  fi
fi
if ! parkio_restore_check_client_compat "${PROFILE_FILE}" "${SERVICE}" "${APPLY_REF}" "${USER_NAME}" "${DB_NAME}"; then
  echo "ERROR: dump-client / restore-client / target-server compatibility failed; nothing was applied." >&2
  rm -f "${PROFILE_FILE}"
  exit 1
fi
rm -f "${PROFILE_FILE}"

# ---- stream dump -> (decrypt) -> gunzip -> psql ----
echo "Restoring ${SERVICE} ..."

if decode | maybe_gunzip \
    | docker exec -i "${APPLY_REF}" psql -v ON_ERROR_STOP=1 -U "${USER_NAME}" -d "${DB_NAME}" >/dev/null; then
  echo "Restore of '${SERVICE}' completed successfully."
else
  echo "ERROR: restore of '${SERVICE}' FAILED. The database may be partially restored." >&2
  exit 1
fi
