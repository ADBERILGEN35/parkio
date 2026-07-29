#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/lib/safety-guards.sh"
source "${SCRIPT_DIR}/lib/evidence-common.sh"

RUN_RESTORE="${PARKIO_STAGING_RUN_RESTORE:-no}"
RUN_JOURNEYS="${PARKIO_STAGING_RUN_JOURNEYS:-no}"
RUN_MINIO="${PARKIO_STAGING_RUN_MINIO:-no}"
RUN_WP05="${PARKIO_STAGING_RUN_WP05_REPLAY:-no}"
RUN_APP="${PARKIO_STAGING_RUN_APP_SEMANTICS:-no}"

export PARKIO_ENVIRONMENT_TYPE="${PARKIO_ENVIRONMENT_TYPE:-CI_EPHEMERAL}"
export PARKIO_STAGING_ISOLATION_MARKER="${PARKIO_STAGING_ISOLATION_MARKER:-wp062-isolated-run}"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-parkio-wp062-$(date +%s)}"
assert_staging_safety

init_evidence_run "${PARKIO_EVIDENCE_RUN_ID:-}" >/dev/null
EVIDENCE_DIR="${PARKIO_EVIDENCE_DIR}"
export PARKIO_EVIDENCE_RUN_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
write_environment_manifest
OVERALL=0

"${SCRIPT_DIR}/verify-prerequisites.sh" > "${EVIDENCE_DIR}/logs/prerequisites.log" 2>&1 \
  && write_json_stage prerequisites PASSED || { write_json_stage prerequisites FAILED; OVERALL=1; }

"${SCRIPT_DIR}/test-safety-guards.sh" > "${EVIDENCE_DIR}/logs/safety-guards.log" 2>&1 \
  && write_json_stage safety_guards PASSED || { write_json_stage safety_guards FAILED; OVERALL=1; }

if docker compose --env-file "${ROOT_DIR}/docker/.env.example" \
  -f "${ROOT_DIR}/docker/docker-compose.yml" \
  -f "${ROOT_DIR}/docker/docker-compose.staging-verification.yml" config \
  > "${EVIDENCE_DIR}/logs/compose-config.log" 2>&1; then
  write_json_stage compose_validation PASSED
else
  write_json_stage compose_validation FAILED; OVERALL=1
fi

if [ "${RUN_RESTORE}" = yes ]; then
  export PARKIO_STAGING_ALLOW_DESTRUCTIVE=yes
  PARKIO_BACKUP_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if PARKIO_ENV_FILE="${ROOT_DIR}/docker/.env" "${ROOT_DIR}/scripts/restore-drill.sh" \
    > "${EVIDENCE_DIR}/logs/restore-drill.log" 2>&1; then
    PARKIO_BACKUP_COMPLETED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    write_json_stage restore_drill RESTORE_SUCCEEDED
    write_backup_manifest "backups" BACKUP_VERIFIED
    if PARKIO_ENV_FILE="${ROOT_DIR}/docker/.env" "${SCRIPT_DIR}/verify-semantic-integrity.sh" \
      > "${EVIDENCE_DIR}/logs/semantic-integrity.log" 2>&1; then
      write_json_stage semantic_integrity SEMANTIC_VERIFICATION_SUCCEEDED
    else
      write_json_stage semantic_integrity PARTIALLY_VERIFIED; OVERALL=1
    fi
  else
    write_json_stage restore_drill FAILED; OVERALL=1
  fi
else
  write_json_stage restore_drill NOT_RUN
  write_json_stage semantic_integrity NOT_RUN
fi

if [ "${RUN_MINIO}" = yes ]; then
  if PARKIO_STAGING_ALLOW_DESTRUCTIVE=yes PARKIO_ENV_FILE="${ROOT_DIR}/docker/.env" \
    "${SCRIPT_DIR}/verify-minio-roundtrip.sh" > "${EVIDENCE_DIR}/logs/minio-roundtrip.log" 2>&1; then
    write_json_stage minio_roundtrip PASSED
  else
    write_json_stage minio_roundtrip FAILED; OVERALL=1
  fi
else
  write_json_stage minio_roundtrip NOT_RUN
fi

if [ "${RUN_WP05}" = yes ]; then
  if "${SCRIPT_DIR}/verify-wp05-replay.sh" > "${EVIDENCE_DIR}/logs/wp05-replay.log" 2>&1; then
    write_json_stage wp05_replay PASSED
  else
    write_json_stage wp05_replay FAILED; OVERALL=1
  fi
else
  write_json_stage wp05_replay NOT_RUN
fi

if [ "${RUN_JOURNEYS}" = yes ] || [ "${RUN_APP}" = yes ]; then
  if [ "${PARKIO_STAGING_RUN_RESTORED_APIS:-no}" = yes ]; then
    if PARKIO_STAGING_ALLOW_DESTRUCTIVE=yes "${SCRIPT_DIR}/verify-restored-application-apis.sh" \
      > "${EVIDENCE_DIR}/logs/journey-results.log" 2>&1; then
      write_json_stage critical_journeys APPLICATION_VERIFICATION_SUCCEEDED
    else
      write_json_stage critical_journeys FAILED; OVERALL=1
    fi
  elif "${SCRIPT_DIR}/run-critical-journeys.sh" > "${EVIDENCE_DIR}/logs/journey-results.log" 2>&1; then
    write_json_stage critical_journeys APPLICATION_VERIFICATION_SUCCEEDED
  else
    write_json_stage critical_journeys FAILED; OVERALL=1
  fi
else
  write_json_stage critical_journeys NOT_RUN
fi

"${SCRIPT_DIR}/generate-evidence-summary.sh" "${OVERALL}" || true
checksum_artifacts
"${SCRIPT_DIR}/validate-evidence-schema.sh" "${EVIDENCE_DIR%/*}" || true
exit "${OVERALL}"