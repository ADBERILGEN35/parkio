#!/usr/bin/env bash
set -euo pipefail
EVIDENCE_SCHEMA_VERSION="1.0.0"
EVIDENCE_ROOT="${PARKIO_EVIDENCE_ROOT:-build/operational-evidence}"

init_evidence_run() {
  local run_id="${1:-}"
  if [ -z "${run_id}" ]; then
    run_id="wp062-$(date -u +%Y%m%dT%H%M%SZ)-$(git rev-parse --short HEAD 2>/dev/null || echo nogit)"
  fi
  export PARKIO_EVIDENCE_RUN_ID="${run_id}"
  export PARKIO_EVIDENCE_DIR="${EVIDENCE_ROOT}/${run_id}"
  if declare -F assert_safe_evidence_path >/dev/null 2>&1; then
    assert_safe_evidence_path "${PARKIO_EVIDENCE_DIR}"
  fi
  mkdir -p "${PARKIO_EVIDENCE_DIR}/logs" "${PARKIO_EVIDENCE_DIR}/checksums"
  echo "${PARKIO_EVIDENCE_DIR}"
}

write_json_stage() {
  local stage="$1" status="$2"
  local started="${3:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
  local completed="${4:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
  cat > "${PARKIO_EVIDENCE_DIR}/${stage}.json" <<EOF
{"evidenceSchemaVersion":"${EVIDENCE_SCHEMA_VERSION}","stage":"${stage}","status":"${status}","startedAt":"${started}","completedAt":"${completed}","runId":"${PARKIO_EVIDENCE_RUN_ID}","environmentType":"${PARKIO_ENVIRONMENT_TYPE:-unknown}","composeProject":"${COMPOSE_PROJECT_NAME:-}","isolationMarker":"${PARKIO_STAGING_ISOLATION_MARKER:-}","syntheticDataMarker":true}
EOF
}

write_environment_manifest() {
  local commit; commit="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
  cat > "${PARKIO_EVIDENCE_DIR}/environment-manifest.json" <<EOF
{"evidenceSchemaVersion":"${EVIDENCE_SCHEMA_VERSION}","runId":"${PARKIO_EVIDENCE_RUN_ID}","repositoryCommit":"${commit}","environmentType":"${PARKIO_ENVIRONMENT_TYPE:-unknown}","composeProject":"${COMPOSE_PROJECT_NAME:-}","isolationMarker":"${PARKIO_STAGING_ISOLATION_MARKER:-}","syntheticDataMarker":true,"generatedAt":"$(date -u +%Y-%m-%dT%H:%M:%SZ)","excludedStores":["redis_cache","kafka_event_history"],"warnings":[]}
EOF
}

write_backup_manifest() {
  local backup_dir="${1:-}" status="${2:-BACKUP_VERIFIED}"
  cat > "${PARKIO_EVIDENCE_DIR}/backup-manifest.json" <<EOF
{"status":"${status}","backupDir":"${backup_dir}","startedAt":"${PARKIO_BACKUP_STARTED_AT:-}","completedAt":"${PARKIO_BACKUP_COMPLETED_AT:-}","syntheticDataMarker":true,"databaseArtifacts":[],"minioArtifacts":[],"checksumsFile":"checksums/manifest.sha256","excludedStores":["redis_cache","kafka_event_history"]}
EOF
}

checksum_artifacts() {
  mkdir -p "${PARKIO_EVIDENCE_DIR}/checksums"
  if command -v sha256sum >/dev/null 2>&1; then
    find "${PARKIO_EVIDENCE_DIR}" -maxdepth 2 -type f \( -name '*.json' -o -name '*.md' \) -print0 \
      | xargs -0 sha256sum > "${PARKIO_EVIDENCE_DIR}/checksums/manifest.sha256" 2>/dev/null || true
  fi
}