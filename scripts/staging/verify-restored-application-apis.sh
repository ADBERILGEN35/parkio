#!/usr/bin/env bash
# WP-06.2A.1 — seed fixtures, backup, restore into drill DBs, repoint apps, run journeys.
# Requires: running compose apps + DBs, PARKIO_STAGING_ALLOW_DESTRUCTIVE=yes.
# Scoped cleanup only for parkio-wp062-* resources and *_drill_* databases created here.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/lib/safety-guards.sh"
source "${SCRIPT_DIR}/lib/evidence-common.sh"
assert_staging_safety
assert_destructive_opt_in
assert_compose_project_isolated

ENV_FILE="${PARKIO_ENV_FILE:-${ROOT_DIR}/docker/.env}"
if [ -f "${ENV_FILE}" ]; then set -a; # shellcheck disable=SC1090
  . "${ENV_FILE}"; set +a; fi

GATEWAY_URL="${PARKIO_GATEWAY_URL:-http://127.0.0.1:8080}"
# Full JDBC repoint requires an isolated parkio-wp062-* app stack (port ownership).
# Do not hijack a developer compose project named "parkio".
if docker ps --format '{{.Names}}' 2>/dev/null | grep -qE '^parkio-gateway' \
  && [[ ! "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' parkio-gateway-service-1 2>/dev/null || true)" =~ ^parkio-wp062- ]]; then
  echo "EXTERNAL_STAGING_REQUIRED: restored application API verification needs an isolated parkio-wp062-* stack; refusing to repoint existing parkio gateway." >&2
  exit 3
fi
if ! curl -sf --connect-timeout 5 "${GATEWAY_URL}/actuator/health" >/dev/null; then
  echo "BLOCKED: gateway not reachable for restored application API verification" >&2
  exit 2
fi

RUN_SUFFIX="$(date -u +%Y%m%d%H%M%S)"
MARKER="wp062a1-${RUN_SUFFIX}"
export PARKIO_JOURNEY_STORE_MODE=restored_drill

SRC_AUTH="${POSTGRES_AUTH_DB:-parkio_auth}"
SRC_USER="${POSTGRES_USER_DB:-parkio_user}"
SRC_PARKING="${POSTGRES_PARKING_DB:-parkio_parking}"
SRC_MEDIA="${POSTGRES_MEDIA_DB:-parkio_media}"

DST_AUTH="${SRC_AUTH}_drill_${MARKER}"
DST_USER="${SRC_USER}_drill_${MARKER}"
DST_PARKING="${SRC_PARKING}_drill_${MARKER}"
DST_MEDIA="${SRC_MEDIA}_drill_${MARKER}"

assert_safe_database_name "${DST_AUTH}"
assert_safe_database_name "${DST_USER}"
assert_safe_database_name "${DST_PARKING}"
assert_safe_database_name "${DST_MEDIA}"
assert_restore_target_not_source "${SRC_AUTH}" "${DST_AUTH}"
assert_restore_target_not_source "${SRC_PARKING}" "${DST_PARKING}"

cleanup() {
  # Repoint apps back to source DBs if we changed them
  if [ "${APPS_REPOINTED:-no}" = "yes" ]; then
    echo "==> Restoring app JDBC targets to source databases"
    POSTGRES_AUTH_DB="${SRC_AUTH}" POSTGRES_USER_DB="${SRC_USER}" \
    POSTGRES_PARKING_DB="${SRC_PARKING}" POSTGRES_MEDIA_DB="${SRC_MEDIA}" \
      docker compose --env-file "${ENV_FILE}" \
        -f "${ROOT_DIR}/docker/docker-compose.yml" \
        -f "${ROOT_DIR}/docker/docker-compose.apps.yml" \
        up -d --no-deps --force-recreate \
        auth-service user-service parking-service media-service gateway-service >/dev/null 2>&1 || true
  fi
  for pair in \
    "parkio-postgres-auth:${POSTGRES_AUTH_USER:-parkio_auth}:${DST_AUTH}" \
    "parkio-postgres-user:${POSTGRES_USER_USER:-parkio_user}:${DST_USER}" \
    "parkio-postgres-parking:${POSTGRES_PARKING_USER:-parkio_parking}:${DST_PARKING}" \
    "parkio-postgres-media:${POSTGRES_MEDIA_USER:-parkio_media}:${DST_MEDIA}"; do
    IFS=":" read -r c u d <<< "${pair}"
    docker exec -i "${c}" psql -U "${u}" -d postgres -c "DROP DATABASE IF EXISTS \"${d}\";" >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT

echo "==> Seeding live synthetic fixtures via critical journeys (pre-backup)"
export PARKIO_JOURNEY_STORE_MODE=live_pre_backup
if ! "${SCRIPT_DIR}/run-critical-journeys.sh"; then
  echo "WARN: live fixture journey did not fully pass; continuing if restore still useful" >&2
fi

echo "==> Backup selected databases"
BACKUP_ROOT="${ROOT_DIR}/backups"
PARKIO_ENV_FILE="${ENV_FILE}" BACKUP_DIR="${BACKUP_ROOT}" "${ROOT_DIR}/scripts/backup-databases.sh"
BACKUP_DIR="$(ls -dt "${BACKUP_ROOT}"/*/ 2>/dev/null | head -1)"
BACKUP_DIR="${BACKUP_DIR%/}"
[ -n "${BACKUP_DIR}" ] || { echo "ERROR: no backup dir"; exit 1; }

decode_and_restore() {
  local container="$1" user="$2" src_db="$3" dst_db="$4" dump="$5"
  docker exec -i "${container}" psql -U "${user}" -d postgres -c "DROP DATABASE IF EXISTS \"${dst_db}\";" >/dev/null
  docker exec -i "${container}" psql -U "${user}" -d postgres -c "CREATE DATABASE \"${dst_db}\";" >/dev/null
  if [[ "${dump}" == *.gz ]]; then
    gunzip -c "${dump}" | docker exec -i "${container}" psql -v ON_ERROR_STOP=1 -U "${user}" -d "${dst_db}" >/dev/null
  else
    docker exec -i "${container}" psql -v ON_ERROR_STOP=1 -U "${user}" -d "${dst_db}" < "${dump}" >/dev/null
  fi
  # restore-run marker inside target
  docker exec -i "${container}" psql -U "${user}" -d "${dst_db}" -c \
    "CREATE TABLE IF NOT EXISTS parkio_wp062_restore_marker (marker text PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT now());
     INSERT INTO parkio_wp062_restore_marker(marker) VALUES ('${MARKER}') ON CONFLICT DO NOTHING;" >/dev/null
}

find_dump() {
  local svc="$1"
  for c in "${BACKUP_DIR}/${svc}.sql.gz" "${BACKUP_DIR}/${svc}.sql"; do
    [ -f "${c}" ] && { echo "${c}"; return 0; }
  done
  return 1
}

echo "==> Restoring into isolated drill databases"
decode_and_restore parkio-postgres-auth "${POSTGRES_AUTH_USER:-parkio_auth}" "${SRC_AUTH}" "${DST_AUTH}" "$(find_dump auth)"
decode_and_restore parkio-postgres-user "${POSTGRES_USER_USER:-parkio_user}" "${SRC_USER}" "${DST_USER}" "$(find_dump user)"
decode_and_restore parkio-postgres-parking "${POSTGRES_PARKING_USER:-parkio_parking}" "${SRC_PARKING}" "${DST_PARKING}" "$(find_dump parking)"
if dump_media="$(find_dump media 2>/dev/null)"; then
  decode_and_restore parkio-postgres-media "${POSTGRES_MEDIA_USER:-parkio_media}" "${SRC_MEDIA}" "${DST_MEDIA}" "${dump_media}"
fi

echo "==> Repointing application JDBC URLs to drill databases"
APPS_REPOINTED=yes
export POSTGRES_AUTH_DB="${DST_AUTH}"
export POSTGRES_USER_DB="${DST_USER}"
export POSTGRES_PARKING_DB="${DST_PARKING}"
export POSTGRES_MEDIA_DB="${DST_MEDIA}"
# Write a temporary env overlay so compose substitution picks drill DBs
TMP_ENV="$(mktemp)"
{
  cat "${ENV_FILE}"
  echo "POSTGRES_AUTH_DB=${DST_AUTH}"
  echo "POSTGRES_USER_DB=${DST_USER}"
  echo "POSTGRES_PARKING_DB=${DST_PARKING}"
  echo "POSTGRES_MEDIA_DB=${DST_MEDIA}"
} > "${TMP_ENV}"

docker compose --env-file "${TMP_ENV}" \
  -f "${ROOT_DIR}/docker/docker-compose.yml" \
  -f "${ROOT_DIR}/docker/docker-compose.apps.yml" \
  up -d --no-deps --force-recreate \
  auth-service user-service parking-service media-service gateway-service

echo "==> Waiting for gateway readiness after repoint"
for i in $(seq 1 40); do
  if curl -sf --connect-timeout 3 "${GATEWAY_URL}/actuator/health" >/dev/null; then
    break
  fi
  sleep 3
done

echo "==> Running critical journeys against RESTORED databases"
export PARKIO_JOURNEY_STORE_MODE=restored_drill
export PARKIO_ENV_FILE="${TMP_ENV}"
"${SCRIPT_DIR}/run-critical-journeys.sh"
RC=$?

# Record JDBC distinction evidence
if [ -n "${PARKIO_EVIDENCE_DIR:-}" ]; then
  cat > "${PARKIO_EVIDENCE_DIR}/jdbc-source-vs-restore.json" <<EOF
{"source":{"auth":"${SRC_AUTH}","parking":"${SRC_PARKING}"},"restore":{"auth":"${DST_AUTH}","parking":"${DST_PARKING}"},"composeProject":"${COMPOSE_PROJECT_NAME}","isolationMarker":"${PARKIO_STAGING_ISOLATION_MARKER}","restoreMarker":"${MARKER}"}
EOF
fi

rm -f "${TMP_ENV}"
exit "${RC}"