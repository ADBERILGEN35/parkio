#!/usr/bin/env bash
#
# Parkio — automated backup RESTORE DRILL.
#
# Proves end-to-end that a dump produced by scripts/backup-databases.sh is actually
# RESTORABLE into a fresh database — the drill the production-readiness plan flagged as
# "pending". It exercises the REAL backup -> REAL restore path against the REAL
# postgres / postgis images and asserts that data survives the round-trip:
#
#   1. seeds a uniquely-tagged canary row into every target service DB;
#   2. (parking only) ensures the REAL PostGIS schema exists — if the app/Flyway has not
#      already created it, the drill applies the actual V*.sql migrations — so the dump
#      contains the production objects (postgis extension, GiST index, location trigger);
#   3. runs scripts/backup-databases.sh (the real nightly backup path);
#   4. runs scripts/verify-backup.sh (generic "restores cleanly into a disposable DB");
#   5. restores the dump into its own temp DB and ASSERTS the canary survived, and for
#      parking ASSERTS the PostGIS extension, GiST index, sync trigger and a live spatial
#      query all work in the restored database;
#   6. drops every temp DB and the canary table, then prints a PASS/FAIL summary.
#
# Live service data is never overwritten (only a drill-owned canary table is added and
# removed; all restores target disposable *_drill_* / *_verify_* databases).
#
# Prerequisites: the Parkio database containers are running and reachable by their
# compose container names (docker compose -f docker/docker-compose.yml up -d the
# postgres-* services). Wired into CI by .github/workflows/backup-restore-drill.yml.
#
# Usage:
#   PARKIO_ENV_FILE=docker/.env scripts/restore-drill.sh                 # all nine services
#   PARKIO_ENV_FILE=docker/.env scripts/restore-drill.sh --service parking
#   scripts/restore-drill.sh --env-file docker/.env --keep-backups
#
# Exit code 0 = every targeted service backed up AND restored with its canary intact
# (and parking's PostGIS objects intact); non-zero = at least one drill step failed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ALL_SERVICES=(auth user parking media gamification notification moderation analytics ai-validation)

TARGET_SERVICE=""
ENV_FILE="${PARKIO_ENV_FILE:-}"
KEEP_BACKUPS="no"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --service) TARGET_SERVICE="${2:-}"; shift 2 ;;
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --keep-backups) KEEP_BACKUPS="yes"; shift ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    -*) echo "ERROR: unknown flag '$1'" >&2; exit 2 ;;
    *) echo "ERROR: unexpected argument '$1'" >&2; exit 2 ;;
  esac
done

if [ -n "${ENV_FILE}" ]; then
  if [ -f "${ENV_FILE}" ]; then
    set -a; # shellcheck disable=SC1090
    . "${ENV_FILE}"; set +a
  else
    echo "WARN: env file '${ENV_FILE}' not found; relying on current environment." >&2
  fi
fi

# service -> container:user:db  (mirrors docker/docker-compose.yml + scripts/backup-databases.sh)
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

# Determine the set of services to drill.
SERVICES=()
if [ -n "${TARGET_SERVICE}" ]; then
  if ! resolve "${TARGET_SERVICE}" >/dev/null; then
    echo "ERROR: unknown service '${TARGET_SERVICE}'." >&2
    echo "       Valid: ${ALL_SERVICES[*]}" >&2
    exit 2
  fi
  SERVICES=("${TARGET_SERVICE}")
else
  SERVICES=("${ALL_SERVICES[@]}")
fi

CANARY_TABLE="parkio_restore_drill"
RUN_ID="$(date -u +%Y%m%d%H%M%S)"

# Per-service expected canary marker.
declare -A EXPECTED_MARKER
declare -A RESULT

# ---- psql helpers (run inside the service's postgres container) ----
psql_db()    { docker exec -i "$1" psql -v ON_ERROR_STOP=1 -U "$2" -d "$3" "${@:4}"; }
psql_admin() { docker exec -i "$1" psql -v ON_ERROR_STOP=1 -U "$2" -d postgres "${@:3}"; }
scalar()     { docker exec -i "$1" psql -tA -U "$2" -d "$3" -c "$4" | tr -d '[:space:]'; }

require_container() {
  if ! docker inspect "$1" >/dev/null 2>&1; then
    echo "ERROR: container '$1' not found / not running. Start the DB stack first:" >&2
    echo "       docker compose -f docker/docker-compose.yml up -d" >&2
    exit 1
  fi
}

# parking only: make sure the real PostGIS schema exists (apply the actual migrations if
# the app/Flyway hasn't already), so the dump contains the production objects.
ensure_parking_schema() {
  local container="$1" user="$2" db="$3"
  local exists
  exists="$(scalar "$container" "$user" "$db" "SELECT to_regclass('public.parking_spots') IS NOT NULL;")"
  if [ "${exists}" = "t" ]; then
    echo "    parking schema already present — using it as-is."
    return 0
  fi
  echo "    parking schema absent — applying real V*.sql migrations for the drill."
  local dir="${ROOT_DIR}/services/parking-service/src/main/resources/db/migration"
  local f
  for f in $(ls "${dir}"/V*.sql | sort -V); do
    psql_db "$container" "$user" "$db" < "${f}" >/dev/null
  done
}

decode_dump() {
  case "$1" in
    *.enc) openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_ENCRYPT_PASSPHRASE -in "$1" ;;
    *) cat "$1" ;;
  esac
}
maybe_gunzip() {
  case "$1" in
    *.gz|*.gz.enc) gunzip -c ;;
    *) cat ;;
  esac
}

find_dump() {
  local dir="$1" service="$2"
  local candidate
  for candidate in "${dir}/${service}.sql.gz.enc" "${dir}/${service}.sql.gz" "${dir}/${service}.sql"; do
    if [ -f "${candidate}" ]; then echo "${candidate}"; return 0; fi
  done
  return 1
}

echo "==> Parkio restore drill ${RUN_ID} — services: ${SERVICES[*]}"

# 1) Pre-flight: containers up, seed canaries, ensure parking schema.
for svc in "${SERVICES[@]}"; do
  IFS=":" read -r container user db <<< "$(resolve "${svc}")"
  require_container "${container}"
  echo "--> [${svc}] seeding canary into ${db}"
  if [ "${svc}" = "parking" ]; then
    ensure_parking_schema "${container}" "${user}" "${db}"
  fi
  marker="drill-${RUN_ID}-${svc}"
  EXPECTED_MARKER["${svc}"]="${marker}"
  psql_db "${container}" "${user}" "${db}" -c \
    "CREATE TABLE IF NOT EXISTS ${CANARY_TABLE} (id int PRIMARY KEY, marker text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());" >/dev/null
  psql_db "${container}" "${user}" "${db}" -c \
    "INSERT INTO ${CANARY_TABLE} (id, marker) VALUES (1, '${marker}') ON CONFLICT (id) DO UPDATE SET marker = EXCLUDED.marker, created_at = now();" >/dev/null
done

# 2) Run the real backup (backs up every running service DB). Pin BACKUP_DIR to the repo
# root so the drill finds the dumps regardless of the caller's working directory.
echo "==> Running scripts/backup-databases.sh"
BACKUP_ROOT="${ROOT_DIR}/backups"
PARKIO_ENV_FILE="${ENV_FILE}" BACKUP_DIR="${BACKUP_ROOT}" "${ROOT_DIR}/scripts/backup-databases.sh"
BACKUP_DIR="$(ls -dt "${BACKUP_ROOT}"/*/ 2>/dev/null | head -1)"
if [ -z "${BACKUP_DIR}" ] || [ ! -d "${BACKUP_DIR}" ]; then
  echo "ERROR: no backup directory produced under ${BACKUP_ROOT}/." >&2
  exit 1
fi
BACKUP_DIR="${BACKUP_DIR%/}"
echo "    backup set: ${BACKUP_DIR}"

# 3) For each service: generic verify + canary/PostGIS restore assertions.
OVERALL=0
for svc in "${SERVICES[@]}"; do
  IFS=":" read -r container user db <<< "$(resolve "${svc}")"
  echo "==> [${svc}] verifying + restore-asserting"

  if ! dump="$(find_dump "${BACKUP_DIR}" "${svc}")"; then
    echo "    FAIL: no dump for '${svc}' in ${BACKUP_DIR}" >&2
    RESULT["${svc}"]="FAIL (no dump)"; OVERALL=1; continue
  fi

  # 3a) generic restorability (exercises scripts/verify-backup.sh).
  if ! PARKIO_ENV_FILE="${ENV_FILE}" "${ROOT_DIR}/scripts/verify-backup.sh" "${svc}" "${dump}" >/dev/null; then
    echo "    FAIL: verify-backup.sh could not restore '${svc}'." >&2
    RESULT["${svc}"]="FAIL (verify-backup)"; OVERALL=1; continue
  fi

  # 3b) restore into our own temp DB and assert the canary + PostGIS objects.
  tmp_db="${db}_drill_${RUN_ID}"
  svc_failed="no"
  psql_admin "${container}" "${user}" -c "DROP DATABASE IF EXISTS \"${tmp_db}\";" >/dev/null
  psql_admin "${container}" "${user}" -c "CREATE DATABASE \"${tmp_db}\";" >/dev/null

  if ! decode_dump "${dump}" | maybe_gunzip "${dump}" \
      | docker exec -i "${container}" psql -v ON_ERROR_STOP=1 -U "${user}" -d "${tmp_db}" >/dev/null; then
    echo "    FAIL: dump did not restore into ${tmp_db}." >&2
    svc_failed="yes"
  fi

  if [ "${svc_failed}" = "no" ]; then
    got="$(scalar "${container}" "${user}" "${tmp_db}" "SELECT marker FROM ${CANARY_TABLE} WHERE id = 1;")"
    if [ "${got}" != "${EXPECTED_MARKER[${svc}]}" ]; then
      echo "    FAIL: canary mismatch (expected='${EXPECTED_MARKER[${svc}]}' got='${got}')." >&2
      svc_failed="yes"
    else
      echo "    OK: canary row survived the round-trip."
    fi
  fi

  if [ "${svc_failed}" = "no" ] && [ "${svc}" = "parking" ]; then
    ext="$(scalar "${container}" "${user}" "${tmp_db}" "SELECT count(*) FROM pg_extension WHERE extname = 'postgis';")"
    gist="$(scalar "${container}" "${user}" "${tmp_db}" "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_parking_spots_location';")"
    trig="$(scalar "${container}" "${user}" "${tmp_db}" "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_parking_spots_sync_location';")"
    spatial="$(scalar "${container}" "${user}" "${tmp_db}" "SELECT ST_DWithin(ST_SetSRID(ST_MakePoint(0,0),4326)::geography, ST_SetSRID(ST_MakePoint(0,0.001),4326)::geography, 1000);")"
    echo "    PostGIS: extension=${ext} gist_index=${gist} sync_trigger=${trig} spatial_query=${spatial}"
    if [ "${ext}" != "1" ] || [ "${gist}" != "1" ] || [ "${trig}" != "1" ] || [ "${spatial}" != "t" ]; then
      echo "    FAIL: parking PostGIS objects did not restore intact." >&2
      svc_failed="yes"
    else
      echo "    OK: PostGIS extension, GiST index, sync trigger and spatial query all intact."
    fi
  fi

  psql_admin "${container}" "${user}" -c "DROP DATABASE IF EXISTS \"${tmp_db}\";" >/dev/null
  # Remove the drill-owned canary table from the live DB (never touches business tables).
  psql_db "${container}" "${user}" "${db}" -c "DROP TABLE IF EXISTS ${CANARY_TABLE};" >/dev/null

  if [ "${svc_failed}" = "yes" ]; then
    RESULT["${svc}"]="FAIL"; OVERALL=1
  else
    RESULT["${svc}"]="PASS"
  fi
done

# 4) Optionally discard the drill's backup artifacts.
if [ "${KEEP_BACKUPS}" = "no" ]; then
  rm -rf "${BACKUP_DIR}" 2>/dev/null || true
  echo "==> Removed drill backup set ${BACKUP_DIR} (pass --keep-backups to retain)."
fi

# 5) Summary.
echo ""
echo "==================== RESTORE DRILL SUMMARY (${RUN_ID}) ===================="
for svc in "${SERVICES[@]}"; do
  printf '  %-16s %s\n' "${svc}" "${RESULT[${svc}]:-FAIL (not run)}"
done
echo "=========================================================================="
if [ "${OVERALL}" -eq 0 ]; then
  echo "RESULT: PASS — every targeted dump restored with its canary (and parking PostGIS) intact."
else
  echo "RESULT: FAIL — at least one service failed the restore drill." >&2
fi
exit "${OVERALL}"
