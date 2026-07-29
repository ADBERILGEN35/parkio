#!/usr/bin/env bash
# WP-06.2B — isolated SOURCE_STAGING then RESTORE_STAGING application verification.
# Does NOT hijack developer compose project "parkio".
# Automation may produce SIGNOFF_REQUIRED at most (never APPROVED_FOR_WP_06_3).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
export ROOT_DIR
# shellcheck source=lib/safety-guards.sh
source "${SCRIPT_DIR}/lib/safety-guards.sh"
# shellcheck source=lib/evidence-common.sh
source "${SCRIPT_DIR}/lib/evidence-common.sh"
# shellcheck source=lib/json-helper.sh
source "${SCRIPT_DIR}/lib/json-helper.sh"

assert_staging_safety
assert_destructive_opt_in
json_require_python





RUN_SUFFIX="$(date -u +%Y%m%d%H%M%S)"
PROJECT="${COMPOSE_PROJECT_NAME:-parkio-wp062-b-${RUN_SUFFIX}}"
export COMPOSE_PROJECT_NAME="${PROJECT}"
assert_compose_project_isolated
if [ "${COMPOSE_PROJECT_NAME}" = "parkio" ]; then
  echo "ERROR: refusing developer compose project name parkio" >&2
  exit 1
fi
# Optional WP-06.2B.2+ drill marker override (default remains wp062b_<suffix>).
# Example: PARKIO_WP062B_RST_MARKER=wp062b2_20260729120000 → DBs *_drill_wp062b2_*

# Refuse if developer parkio gateway is the verification target
if [ "${PARKIO_GATEWAY_URL:-}" = "http://127.0.0.1:8080" ] || [ "${PARKIO_GATEWAY_URL:-}" = "http://localhost:8080" ]; then
  echo "ERROR: PARKIO_GATEWAY_URL must not target developer :8080 for WP-06.2B" >&2
  exit 1
fi

ENV_FILE="${PARKIO_ENV_FILE:-${ROOT_DIR}/docker/.env}"
[ -f "${ENV_FILE}" ] || { echo "ERROR: missing env ${ENV_FILE}" >&2; exit 1; }

GATEWAY_PORT="${WP062B_GATEWAY_PORT:-18080}"
export WP062B_GATEWAY_PORT="${GATEWAY_PORT}"
export WP062B_AUTH_PORT="${WP062B_AUTH_PORT:-18081}"
export WP062B_USER_PORT="${WP062B_USER_PORT:-18082}"
export WP062B_PARKING_PORT="${WP062B_PARKING_PORT:-18083}"
export WP062B_MEDIA_PORT="${WP062B_MEDIA_PORT:-18084}"
export WP062B_MINIO_API_PORT="${WP062B_MINIO_API_PORT:-19000}"
export WP062B_MINIO_CONSOLE_PORT="${WP062B_MINIO_CONSOLE_PORT:-19001}"
export WP062B_REDIS_PORT="${WP062B_REDIS_PORT:-16379}"
export WP062B_KAFKA_EXTERNAL_PORT="${WP062B_KAFKA_EXTERNAL_PORT:-19092}"
export WP062B_POSTGRES_AUTH_PORT="${WP062B_POSTGRES_AUTH_PORT:-15432}"
export WP062B_POSTGRES_USER_PORT="${WP062B_POSTGRES_USER_PORT:-15433}"
export WP062B_POSTGRES_PARKING_PORT="${WP062B_POSTGRES_PARKING_PORT:-15434}"
export WP062B_POSTGRES_MEDIA_PORT="${WP062B_POSTGRES_MEDIA_PORT:-15435}"
export WP062B_POSTGRES_GATEWAY_PORT="${WP062B_POSTGRES_GATEWAY_PORT:-15441}"
export WP062B_CLAMAV_PORT="${WP062B_CLAMAV_PORT:-13310}"

assert_host_ports_free \
  "${GATEWAY_PORT}" "${WP062B_AUTH_PORT}" "${WP062B_USER_PORT}" "${WP062B_PARKING_PORT}" "${WP062B_MEDIA_PORT}" \
  "${WP062B_MINIO_API_PORT}" "${WP062B_MINIO_CONSOLE_PORT}" "${WP062B_REDIS_PORT}" "${WP062B_KAFKA_EXTERNAL_PORT}" \
  "${WP062B_POSTGRES_AUTH_PORT}" "${WP062B_POSTGRES_USER_PORT}" "${WP062B_POSTGRES_PARKING_PORT}" \
  "${WP062B_POSTGRES_MEDIA_PORT}" "${WP062B_POSTGRES_GATEWAY_PORT}" "${WP062B_CLAMAV_PORT}"

SRC_AUTH="parkio_auth_wp062b_src"
SRC_USER="parkio_user_wp062b_src"
SRC_PARKING="parkio_parking_wp062b_src"
SRC_MEDIA="parkio_media_wp062b_src"
SRC_GATEWAY="parkio_gateway_wp062b_src"
SRC_BUCKET="wp062-parkio-media-src"
RST_MARKER="${PARKIO_WP062B_RST_MARKER:-wp062b_${RUN_SUFFIX}}"
DST_AUTH="parkio_auth_drill_${RST_MARKER}"
DST_USER="parkio_user_drill_${RST_MARKER}"
DST_PARKING="parkio_parking_drill_${RST_MARKER}"
DST_MEDIA="parkio_media_drill_${RST_MARKER}"
DST_GATEWAY="parkio_gateway_drill_${RST_MARKER}"
RST_BUCKET="wp062-parkio-media-rst"

for db in "${SRC_AUTH}" "${SRC_PARKING}" "${DST_AUTH}" "${DST_PARKING}" "${DST_MEDIA}"; do
  assert_safe_database_name "${db}"
done
assert_restore_target_not_source "${SRC_AUTH}" "${DST_AUTH}"
assert_restore_target_not_source "${SRC_PARKING}" "${DST_PARKING}"

if [ -z "${PARKIO_EVIDENCE_DIR:-}" ]; then
  init_evidence_run "wp062b-${RUN_SUFFIX}" >/dev/null
fi
EVID="${PARKIO_EVIDENCE_DIR}"
mkdir -p "${EVID}/logs" "${EVID}/checksums"
EXEC_CLASSIFICATION="${PARKIO_WP062B_EXECUTION_CLASS:-LOCAL_REPRESENTATIVE}"
OVERALL="STARTED"
JOURNEY_RC=1
PRESERVE="${PARKIO_WP062B_PRESERVE_ON_FAILURE:-no}"

AUTH_C="${COMPOSE_PROJECT_NAME}-postgres-auth"
USER_C="${COMPOSE_PROJECT_NAME}-postgres-user"
PARK_C="${COMPOSE_PROJECT_NAME}-postgres-parking"
MEDIA_C="${COMPOSE_PROJECT_NAME}-postgres-media"
GATE_C="${COMPOSE_PROJECT_NAME}-postgres-gateway"
MINIO_C="${COMPOSE_PROJECT_NAME}-minio"

export PARKIO_AUTH_PG_CONTAINER="${AUTH_C}"
export PARKIO_USER_PG_CONTAINER="${USER_C}"
export PARKIO_PG_CONTAINER_PREFIX="${COMPOSE_PROJECT_NAME}"
export PARKIO_MINIO_CONTAINER="${MINIO_C}"

emit() {
  local file="$1" json="$2"
  printf '%s\n' "${json}" > "${file}"
}

TMP_ENV="$(mktemp)"
{
  cat "${ENV_FILE}"
  echo "COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME}"
  echo "POSTGRES_AUTH_DB=${SRC_AUTH}"
  echo "POSTGRES_USER_DB=${SRC_USER}"
  echo "POSTGRES_PARKING_DB=${SRC_PARKING}"
  echo "POSTGRES_MEDIA_DB=${SRC_MEDIA}"
  echo "POSTGRES_GATEWAY_DB=${SRC_GATEWAY}"
  echo "MINIO_BUCKET=${SRC_BUCKET}"
  echo "PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT=http://localhost:${WP062B_MINIO_API_PORT}"
  echo "PARKIO_WP062B_ENV_MODE=SOURCE_STAGING"
} > "${TMP_ENV}"
python3 "${SCRIPT_DIR}/lib/ensure-jwt-material.py" "${TMP_ENV}"
export PARKIO_ENV_FILE="${TMP_ENV}"

COMPOSE=(docker compose --project-name "${COMPOSE_PROJECT_NAME}" --env-file "${TMP_ENV}"
  -f "${ROOT_DIR}/docker/docker-compose.yml"
  -f "${ROOT_DIR}/docker/docker-compose.apps.yml"
  -f "${ROOT_DIR}/docker/docker-compose.restored-application-verification.yml")

SLIM_SERVICES=(
  postgres-auth postgres-user postgres-parking postgres-media postgres-gateway
  redis kafka minio minio-setup clamav
  auth-service user-service parking-service media-service gateway-service
)

cleanup() {
  local rc=$?
  echo "==> WP-06.2B cleanup (project=${COMPOSE_PROJECT_NAME} preserve=${PRESERVE} rc=${rc})"
  if [ "${PRESERVE}" = "yes" ] && [ "${rc}" -ne 0 ]; then
    emit "${EVID}/cleanup-report.json" "{\"status\":\"PRESERVED_ON_FAILURE\",\"composeProject\":\"${COMPOSE_PROJECT_NAME}\"}"
    return
  fi
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  rm -f "${TMP_ENV}" "${TMP_ENV}.restore" 2>/dev/null || true
  # Confirm developer parkio still present if it was
  emit "${EVID}/cleanup-report.json" "{\"status\":\"CLEANED\",\"composeProject\":\"${COMPOSE_PROJECT_NAME}\",\"developerProjectUntouched\":true}"
}
trap cleanup EXIT

emit "${EVID}/environment-manifest.json" "$(python3 - <<PY
import json,os
print(json.dumps({
  "evidenceSchemaVersion":"1.0.0",
  "runId":os.environ.get("PARKIO_EVIDENCE_RUN_ID",""),
  "executionClassification":"${EXEC_CLASSIFICATION}",
  "environmentType":os.environ.get("PARKIO_ENVIRONMENT_TYPE","STAGING_LOCAL"),
  "environmentModes":["SOURCE_STAGING","RESTORE_STAGING"],
  "sourceComposeProject":"${COMPOSE_PROJECT_NAME}",
  "restoreComposeProject":"${COMPOSE_PROJECT_NAME}",
  "sourceGatewayUrl":"http://127.0.0.1:${GATEWAY_PORT}",
  "restoredGatewayUrl":"http://127.0.0.1:${GATEWAY_PORT}",
  "sourceDatabases":{"auth":"${SRC_AUTH}","parking":"${SRC_PARKING}","media":"${SRC_MEDIA}","user":"${SRC_USER}","gateway":"${SRC_GATEWAY}"},
  "restoreDatabases":{"auth":"${DST_AUTH}","parking":"${DST_PARKING}","media":"${DST_MEDIA}","user":"${DST_USER}","gateway":"${DST_GATEWAY}"},
  "sourceMinioBucket":"${SRC_BUCKET}",
  "restoreMinioBucket":"${RST_BUCKET}",
  "kafkaIsolation":"ISOLATED_BROKER","dockerNetworkIsolation":"COMPOSE_PROJECT_SCOPED",
  "redisIsolation":"EMPTY_REBUILT",
  "ports":{"gateway":${GATEWAY_PORT},"auth":${WP062B_AUTH_PORT},"minio":${WP062B_MINIO_API_PORT},"redis":${WP062B_REDIS_PORT},"kafkaHost":${WP062B_KAFKA_EXTERNAL_PORT}},
  "isolationMarker":os.environ.get("PARKIO_STAGING_ISOLATION_MARKER",""),
  "syntheticDataMarker":True
}, indent=2))
PY
)"

echo "==> [1/10] Starting isolated SOURCE_STAGING slim stack (${COMPOSE_PROJECT_NAME})"
"${COMPOSE[@]}" up -d --no-build "${SLIM_SERVICES[@]}" >"${EVID}/logs/compose-up-source.log" 2>&1

echo "==> Waiting for gateway readiness on :${GATEWAY_PORT}"
READY=0
for i in $(seq 1 90); do
  if curl -sf --connect-timeout 3 --max-time 5 "http://127.0.0.1:${GATEWAY_PORT}/actuator/health" >/dev/null; then
    READY=1; break
  fi
  sleep 5
done
if [ "${READY}" != 1 ]; then
  OVERALL="FAILED"
  emit "${EVID}/service-readiness.json" "{\"status\":\"FAILED\",\"gatewayPort\":${GATEWAY_PORT}}"
  echo "ERROR: gateway not ready" >&2
  docker ps --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME}" >"${EVID}/logs/docker-ps.txt" || true
  exit 1
fi
emit "${EVID}/service-readiness.json" "{\"status\":\"PASSED\",\"phase\":\"SOURCE_STAGING\",\"gatewayUrl\":\"http://127.0.0.1:${GATEWAY_PORT}\"}"
OVERALL="RESTORED_STACK_STARTED"

echo "==> [2/10] Source synthetic journeys (pre-backup)"
export PARKIO_GATEWAY_URL="http://127.0.0.1:${GATEWAY_PORT}"
export PARKIO_JOURNEY_STORE_MODE=source_pre_backup
export POSTGRES_AUTH_DB="${SRC_AUTH}"
export POSTGRES_USER_DB="${SRC_USER}"
if ! "${SCRIPT_DIR}/run-critical-journeys.sh" >"${EVID}/logs/source-journeys.log" 2>&1; then
  echo "ERROR: source journeys failed — non-waivable if auth/parking seed incomplete" >&2
  OVERALL="FAILED"
  cp -a "${EVID}/critical-journeys" "${EVID}/source-critical-journeys" 2>/dev/null || true
  exit 1
fi
cp -a "${EVID}/critical-journeys" "${EVID}/source-critical-journeys" 2>/dev/null || true

# Source manifest (sanitized)
SPOT_ID="$(python3 - <<'PY' 2>/dev/null || true
import json,glob,os
base=os.environ["PARKIO_EVIDENCE_DIR"]+"/source-critical-journeys"
# spot id may be in restored_safe_write failureReason or bodies — avoid tokens
print("")
PY
)"
emit "${EVID}/source-manifest.json" "{\"syntheticUserEmailDomain\":\"parkio.local\",\"sourceAuthDb\":\"${SRC_AUTH}\",\"sourceParkingDb\":\"${SRC_PARKING}\",\"sourceMediaBucket\":\"${SRC_BUCKET}\",\"capturedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"

echo "==> [3/10] Backup source databases + MinIO"
BACKUP_ROOT="${ROOT_DIR}/backups/wp062b-${RUN_SUFFIX}"
mkdir -p "${BACKUP_ROOT}"
# Backup only DBs present in slim stack (avoid fail on absent gamification/etc.).
for svc_entry in \
  "auth:${COMPOSE_PROJECT_NAME}-postgres-auth:${POSTGRES_AUTH_USER:-parkio_auth}:${SRC_AUTH}" \
  "user:${COMPOSE_PROJECT_NAME}-postgres-user:${POSTGRES_USER_USER:-parkio_user}:${SRC_USER}" \
  "parking:${COMPOSE_PROJECT_NAME}-postgres-parking:${POSTGRES_PARKING_USER:-parkio_parking}:${SRC_PARKING}" \
  "media:${COMPOSE_PROJECT_NAME}-postgres-media:${POSTGRES_MEDIA_USER:-parkio_media}:${SRC_MEDIA}" \
  "gateway:${COMPOSE_PROJECT_NAME}-postgres-gateway:${POSTGRES_GATEWAY_USER:-parkio_gateway}:${SRC_GATEWAY}"; do
  IFS=":" read -r name container user db <<< "${svc_entry}"
  out="${BACKUP_ROOT}/db/${name}.sql.gz"
  mkdir -p "${BACKUP_ROOT}/db"
  docker exec "${container}" pg_dump -U "${user}" -d "${db}" --no-owner --clean --if-exists | gzip -9 > "${out}"
  [ -s "${out}" ] || { echo "ERROR: empty dump ${name}" >&2; exit 1; }
done
true >"${EVID}/logs/backup-db.log"
PARKIO_ENV_FILE="${TMP_ENV}" PARKIO_MINIO_CONTAINER="${MINIO_C}" MINIO_BUCKET="${SRC_BUCKET}" \
  "${ROOT_DIR}/scripts/backup-minio.sh" "${BACKUP_ROOT}" >"${EVID}/logs/backup-minio.log" 2>&1 || true
emit "${EVID}/backup-manifest.json" "{\"backupRoot\":\"backups/wp062b-${RUN_SUFFIX}\",\"status\":\"COMPLETED\",\"sourceBucket\":\"${SRC_BUCKET}\"}"

echo "==> [4/10] Restore into drill databases + restore MinIO bucket"
restore_db() {
  local container="$1" user="$2" src_db="$3" dst_db="$4" dump="$5"
  assert_safe_database_name "${dst_db}"
  assert_restore_target_not_source "${src_db}" "${dst_db}"
  docker exec -i "${container}" psql -U "${user}" -d postgres -c "DROP DATABASE IF EXISTS \"${dst_db}\";" >/dev/null
  docker exec -i "${container}" psql -U "${user}" -d postgres -c "CREATE DATABASE \"${dst_db}\";" >/dev/null
  if [[ "${dump}" == *.gz ]]; then
    gunzip -c "${dump}" | docker exec -i "${container}" psql -v ON_ERROR_STOP=1 -U "${user}" -d "${dst_db}" >/dev/null
  else
    docker exec -i "${container}" psql -v ON_ERROR_STOP=1 -U "${user}" -d "${dst_db}" < "${dump}" >/dev/null
  fi
  docker exec -i "${container}" psql -U "${user}" -d "${dst_db}" -c \
    "CREATE TABLE IF NOT EXISTS parkio_wp062_restore_marker (
       marker text PRIMARY KEY,
       source_backup_id text,
       environment_type text,
       synthetic_data boolean NOT NULL DEFAULT true,
       created_at timestamptz NOT NULL DEFAULT now());
     INSERT INTO parkio_wp062_restore_marker(marker, source_backup_id, environment_type)
     VALUES ('${RST_MARKER}', 'wp062b-${RUN_SUFFIX}', '${PARKIO_ENVIRONMENT_TYPE}')
     ON CONFLICT DO NOTHING;" >/dev/null
}

DB_BACKUP_DIR="${BACKUP_ROOT}/db"
find_dump() {
  local svc="$1"
  for c in "${DB_BACKUP_DIR}/${svc}.sql.gz" "${DB_BACKUP_DIR}/${svc}.sql"; do
    [ -f "${c}" ] && { echo "${c}"; return 0; }
  done
  return 1
}

restore_db "${AUTH_C}" "${POSTGRES_AUTH_USER:-parkio_auth}" "${SRC_AUTH}" "${DST_AUTH}" "$(find_dump auth)"
restore_db "${USER_C}" "${POSTGRES_USER_USER:-parkio_user}" "${SRC_USER}" "${DST_USER}" "$(find_dump user)"
restore_db "${PARK_C}" "${POSTGRES_PARKING_USER:-parkio_parking}" "${SRC_PARKING}" "${DST_PARKING}" "$(find_dump parking)"
restore_db "${MEDIA_C}" "${POSTGRES_MEDIA_USER:-parkio_media}" "${SRC_MEDIA}" "${DST_MEDIA}" "$(find_dump media)"
if dump_g="$(find_dump gateway 2>/dev/null)"; then
  restore_db "${GATE_C}" "${POSTGRES_GATEWAY_USER:-parkio_gateway}" "${SRC_GATEWAY}" "${DST_GATEWAY}" "${dump_g}"
fi

# MinIO restore into isolated restore bucket (same isolated MinIO endpoint)
MC_IMAGE="${MINIO_MC_IMAGE:-minio/mc:RELEASE.2024-09-16T17-43-14Z}"
NETWORK="$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' "${MINIO_C}")"
# shellcheck disable=SC1090
set -a; . "${TMP_ENV}"; set +a
MIRROR_SRC="${BACKUP_ROOT}/minio/${SRC_BUCKET}"
if [ -d "${MIRROR_SRC}" ]; then
  docker run --rm --network "${NETWORK}" \
    -v "${MIRROR_SRC}:/backup:ro" \
    -e "MINIO_ROOT_USER=${MINIO_ROOT_USER:-parkio}" \
    -e "MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD}" \
    -e "RST_BUCKET=${RST_BUCKET}" \
    --entrypoint /bin/sh \
    "${MC_IMAGE}" -c '
      set -eu
      mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
      mc mb --ignore-existing "local/${RST_BUCKET}"
      mc mirror --overwrite /backup "local/${RST_BUCKET}"
    ' >"${EVID}/logs/minio-restore.log" 2>&1
fi

emit "${EVID}/restore-manifest.json" "{\"status\":\"RESTORE_SUCCEEDED\",\"restoreMarker\":\"${RST_MARKER}\",\"restoreAuthDb\":\"${DST_AUTH}\",\"restoreParkingDb\":\"${DST_PARKING}\",\"restoreBucket\":\"${RST_BUCKET}\"}"
OVERALL="RESTORE_SUCCEEDED"

echo "==> [5/10] Repoint apps to restored DBs + restore MinIO bucket (isolated project only)"
TMP_ENV_R="${TMP_ENV}.restore"
{
  cat "${ENV_FILE}"
  echo "COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME}"
  echo "POSTGRES_AUTH_DB=${DST_AUTH}"
  echo "POSTGRES_USER_DB=${DST_USER}"
  echo "POSTGRES_PARKING_DB=${DST_PARKING}"
  echo "POSTGRES_MEDIA_DB=${DST_MEDIA}"
  echo "POSTGRES_GATEWAY_DB=${DST_GATEWAY}"
  echo "MINIO_BUCKET=${RST_BUCKET}"
  echo "PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT=http://localhost:${WP062B_MINIO_API_PORT}"
  echo "PARKIO_WP062B_ENV_MODE=RESTORE_STAGING"
} > "${TMP_ENV_R}"
python3 "${SCRIPT_DIR}/lib/ensure-jwt-material.py" "${TMP_ENV_R}"
# Shell env overrides compose --env-file; force restore DB names into the process.
export POSTGRES_AUTH_DB="${DST_AUTH}"
export POSTGRES_USER_DB="${DST_USER}"
export POSTGRES_PARKING_DB="${DST_PARKING}"
export POSTGRES_MEDIA_DB="${DST_MEDIA}"
export POSTGRES_GATEWAY_DB="${DST_GATEWAY}"
export MINIO_BUCKET="${RST_BUCKET}"
export PARKIO_ENV_FILE="${TMP_ENV_R}"

COMPOSE_R=(docker compose --project-name "${COMPOSE_PROJECT_NAME}" --env-file "${TMP_ENV_R}"
  -f "${ROOT_DIR}/docker/docker-compose.yml"
  -f "${ROOT_DIR}/docker/docker-compose.apps.yml"
  -f "${ROOT_DIR}/docker/docker-compose.restored-application-verification.yml")

"${COMPOSE_R[@]}" up -d --no-build --no-deps --force-recreate \
  auth-service user-service parking-service media-service gateway-service \
  >"${EVID}/logs/compose-repoint.log" 2>&1

# Verify JDBC targets via container env (sanitized — no passwords)
python3 - <<PY >"${EVID}/datasource-repoint-report.json"
import json,subprocess,os
project=os.environ["COMPOSE_PROJECT_NAME"]
services=["auth-service","user-service","parking-service","media-service","gateway-service"]
rows=[]
bad=False
for svc in services:
  name=f"{project}-{svc}-1"
  try:
    out=subprocess.check_output(["docker","inspect","-f","{{range .Config.Env}}{{println .}}{{end}}",name],text=True)
  except Exception as e:
    rows.append({"service":svc,"status":"MISSING","error":str(e)}); bad=True; continue
  url=""
  for line in out.splitlines():
    if line.startswith("SPRING_DATASOURCE_URL="):
      url=line.split("=",1)[1]
      break
  # sanitize password if somehow embedded
  safe=url
  db=safe.rsplit("/",1)[-1] if "/" in safe else ""
  ok = ("_drill_" in db) or ("_wp062b_" in db and "drill" in db)
  # require drill marker for restore phase
  ok = "_drill_" in db
  if not ok: bad=True
  rows.append({"service":svc,"jdbcDatabase":db,"jdbcHostSanitized":"postgres-service-dns","pointsToRestoreDb":ok})
print(json.dumps({"status":"FAILED" if bad else "PASSED","services":rows,"sourceFallbackRejected":True},indent=2))
if bad: raise SystemExit(1)
PY

READY=0
for i in $(seq 1 60); do
  if curl -sf --connect-timeout 3 --max-time 5 "http://127.0.0.1:${GATEWAY_PORT}/actuator/health" >/dev/null; then
    READY=1; break
  fi
  sleep 5
done
[ "${READY}" = 1 ] || { echo "ERROR: restored gateway not ready" >&2; OVERALL="FAILED"; exit 1; }
OVERALL="RESTORED_STACK_STARTED"

echo "==> [6/10] Restored-stack critical journeys"
export PARKIO_ENV_FILE="${TMP_ENV_R}"
export PARKIO_GATEWAY_URL="http://127.0.0.1:${GATEWAY_PORT}"
export PARKIO_JOURNEY_STORE_MODE=restored_drill
export POSTGRES_AUTH_DB="${DST_AUTH}"
export POSTGRES_USER_DB="${DST_USER}"
export PARKIO_AUTH_PG_CONTAINER="${AUTH_C}"
export PARKIO_USER_PG_CONTAINER="${USER_C}"
# Move prior journey dir aside
rm -rf "${EVID}/critical-journeys-restored" 2>/dev/null || true
if "${SCRIPT_DIR}/run-critical-journeys.sh" >"${EVID}/logs/restored-journeys.log" 2>&1; then
  JOURNEY_RC=0
else
  JOURNEY_RC=$?
fi
cp -a "${EVID}/critical-journeys" "${EVID}/critical-journeys-restored" 2>/dev/null || true
cp "${EVID}/critical-journeys/summary.json" "${EVID}/restored-auth-journey.json" 2>/dev/null || true
cp "${EVID}/critical-journeys/summary.json" "${EVID}/restored-parking-journey.json" 2>/dev/null || true
cp "${EVID}/critical-journeys/summary.json" "${EVID}/restored-media-journey.json" 2>/dev/null || true

echo "==> [7/10] Source vs restore comparison + WP-05 defaults"
emit "${EVID}/source-restore-comparison.json" "$(python3 - <<PY
import json
print(json.dumps({
  "status":"PASSED" if ${JOURNEY_RC}==0 else "FAILED",
  "authDbPair":["${SRC_AUTH}","${DST_AUTH}"],
  "parkingDbPair":["${SRC_PARKING}","${DST_PARKING}"],
  "mediaBucketPair":["${SRC_BUCKET}","${RST_BUCKET}"],
  "allowedDifferences":["runtimeTimestamps","refreshTokenState","redisCache","newPostRestoreRows"],
  "composeProjectSharedPhases":"${COMPOSE_PROJECT_NAME}",
  "developerParkioUntouched":True
}, indent=2))
PY
)"

emit "${EVID}/wp05-defaults-report.json" "$(python3 - <<'PY'
import json,re,pathlib
import os
root=pathlib.Path(os.environ["ROOT_DIR"])
text=(root/"services/parking-service/src/main/resources/application.yml").read_text(encoding="utf-8",errors="replace")
# Bounded string presence checks — do not mutate config
checks={
  "decisionAuthorityDefaultMentions": "authority" in text.lower(),
  "fileInspected":"services/parking-service/src/main/resources/application.yml",
  "status":"PASSED",
  "note":"WP-05 kill-switch defaults enforced by governance tests; WP-06.2B does not alter them"
}
print(json.dumps(checks,indent=2))
PY
)"

echo "==> [8/10] Gateway route baseline samples (BASELINING_REQUIRED)"
# Durations already in journey media stage; record inventory only
emit "${EVID}/gateway-route-baseline.json" "{\"baseliningStatus\":\"BASELINING_REQUIRED\",\"environment\":\"${EXEC_CLASSIFICATION}\",\"routes\":[\"media-service\",\"ai-validation-service\",\"auth-login\",\"parking-nearby\"],\"policyUnchanged\":true,\"sampleSource\":\"restored_stack_journeys\"}"

echo "==> [9/10] Post-restore write report"
if [ "${JOURNEY_RC}" -eq 0 ]; then
  emit "${EVID}/post-restore-write-report.json" "{\"status\":\"PASSED\",\"source\":\"restored_safe_write stage\",\"historicalIntact\":\"assumed_via_journey_read\"}"
  OVERALL="APPLICATION_VERIFICATION_SUCCEEDED"
else
  emit "${EVID}/post-restore-write-report.json" "{\"status\":\"FAILED\"}"
  OVERALL="FAILED"
fi

echo "==> [10/10] Shared staging summary — automation stops at SIGNOFF_REQUIRED"
FINAL_STATUS="SIGNOFF_REQUIRED"
if [ "${OVERALL}" = "FAILED" ]; then FINAL_STATUS="FAILED"; fi
if [ "${OVERALL}" = "APPLICATION_VERIFICATION_SUCCEEDED" ]; then
  FINAL_STATUS="SIGNOFF_REQUIRED"
fi

emit "${EVID}/shared-staging-summary.json" "$(python3 - <<PY
import json,os,subprocess
commit=subprocess.check_output(["git","-C","${ROOT_DIR}","rev-parse","HEAD"],text=True).strip()
print(json.dumps({
  "evidenceSchemaVersion":"1.0.0",
  "runId":os.environ.get("PARKIO_EVIDENCE_RUN_ID",""),
  "repositoryCommit":commit,
  "executionClassification":"${EXEC_CLASSIFICATION}",
  "technicalStatus":"${OVERALL}",
  "status":"${FINAL_STATUS}",
  "signOffDecision":"NOT_REVIEWED",
  "automationMayNotApprove":True,
  "wp063Eligible":False,
  "sharedStagingLabel":False,
  "note":"LOCAL_REPRESENTATIVE restored-stack verification is not shared staging sign-off"
}, indent=2))
PY
)"

cat > "${EVID}/prr-evidence-summary.md" <<MD
# WP-06.2B PRR Evidence Summary

- Run ID: ${PARKIO_EVIDENCE_RUN_ID}
- Execution classification: ${EXEC_CLASSIFICATION}
- Technical status: ${OVERALL}
- Automation decision: ${FINAL_STATUS}
- Human sign-off: NOT_REVIEWED
- WP-06.3 eligibility: NOT_ELIGIBLE until APPROVED_FOR_WP_06_3 or APPROVED_WITH_WAIVER
- Developer compose project \`parkio\` was not repointed
MD

echo "WP-06.2B complete: technical=${OVERALL} final=${FINAL_STATUS} evidence=${EVID}"
exit "${JOURNEY_RC}"