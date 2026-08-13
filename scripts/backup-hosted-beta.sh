#!/usr/bin/env bash
#
# Parkio - hosted-beta backup orchestrator (Postgres + MinIO + manifest + metrics).
#
# Usage:
#   PARKIO_ENV_FILE=docker/.env ./scripts/backup-hosted-beta.sh
#   PARKIO_ENV_FILE=docker/.env ./scripts/backup-hosted-beta.sh --dry-run
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/backup-common.sh
source "$ROOT/scripts/lib/backup-common.sh"

ENV_FILE="${PARKIO_ENV_FILE:-}"
OPERATOR="${PARKIO_BACKUP_OPERATOR:-${USER:-unknown}}"
ARTIFACT_DIR="${PARKIO_BACKUP_ARTIFACT_DIR:-backup-artifacts}"
DRY_RUN=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --artifact-dir) ARTIFACT_DIR="${2:-}"; shift 2 ;;
    --operator) OPERATOR="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

parkio_backup_load_env "${ENV_FILE}"
parkio_backup_validate_deployment_profile

BACKUP_DIR="${BACKUP_DIR:-./backups}"
STAMP="$(parkio_backup_stamp)"
GIT_SHA="$(parkio_backup_git_sha)"
DEST_DIR="${BACKUP_DIR}/${STAMP}"
MANIFEST_PATH="${ROOT}/${ARTIFACT_DIR}/backup-${STAMP}.json"
STAMP_EPOCH="$(date -u +%s)"

echo "=== Parkio hosted-beta backup ==="
echo "destination=${DEST_DIR}"
echo "gitSha=${GIT_SHA}"
echo "operator=${OPERATOR}"
echo "deploymentProfile=${PARKIO_DEPLOYMENT_PROFILE}"
echo "dryRun=${DRY_RUN}"

if [ "$DRY_RUN" -eq 1 ]; then
  echo "DRY-RUN: would run backup-databases.sh and backup-minio.sh into ${DEST_DIR}"
  parkio_backup_write_manifest "${MANIFEST_PATH}" "${STAMP}" "${GIT_SHA}" "${OPERATOR}" \
    "${ENV_FILE:-<env>}" "${DEST_DIR}" "${#PARKIO_DB_SERVICES[@]}" 0 1 0
  echo "Manifest (dry-run): ${MANIFEST_PATH}"
  exit 0
fi

mkdir -p "${DEST_DIR}"

# Dump first, then MinIO mirror, THEN offsite — so BACKUP_MC_DEST includes objects.
# backup-databases.sh would otherwise upload dumps before MinIO exists in DEST_DIR.
DB_FAILED=0
if ! PARKIO_ENV_FILE="${ENV_FILE}" BACKUP_DIR="${BACKUP_DIR}" BACKUP_DEST_DIR="${DEST_DIR}" \
    BACKUP_SKIP_MC_UPLOAD=1 "${ROOT}/scripts/backup-databases.sh"; then
  # backup-databases reports aggregate failure only. Do not invent a partial
  # success count when one or more dumps failed.
  DB_FAILED=${#PARKIO_DB_SERVICES[@]}
fi

MINIO_OBJECTS=0
MINIO_OK=1
if ! MINIO_OBJECTS="$("${ROOT}/scripts/backup-minio.sh" "${DEST_DIR}" ${ENV_FILE:+--env-file "$ENV_FILE"})"; then
  MINIO_OK=0
  MINIO_OBJECTS=0
fi

OFFSITE_OK=1
if ! parkio_backup_offsite_upload "${DEST_DIR}" "${BACKUP_MC_DEST:-}" "$(basename "${DEST_DIR}")"; then
  OFFSITE_OK=0
fi

if [ "${DB_FAILED}" -eq 0 ]; then
  DB_OK=${#PARKIO_DB_SERVICES[@]}
else
  DB_OK=0
fi
SUCCESS=0
if [ "${DB_FAILED}" -eq 0 ] && [ "${MINIO_OK}" -eq 1 ] && [ "${OFFSITE_OK}" -eq 1 ]; then
  SUCCESS=1
fi

parkio_backup_write_manifest "${MANIFEST_PATH}" "${STAMP}" "${GIT_SHA}" "${OPERATOR}" \
  "${ENV_FILE:-<env>}" "${DEST_DIR}" "${DB_OK}" "${DB_FAILED}" "${MINIO_OK}" "${MINIO_OBJECTS}"
parkio_backup_write_metrics "${PARKIO_DEPLOYMENT_PROFILE}" "${SUCCESS}" "${STAMP_EPOCH}" "${DB_FAILED}" "${MINIO_OBJECTS}"

cp "${MANIFEST_PATH}" "${ROOT}/${ARTIFACT_DIR}/backup-current.json" 2>/dev/null || true

if [ "${SUCCESS}" -ne 1 ]; then
  echo "Backup completed with failures (dbFailed=${DB_FAILED}, minioOk=${MINIO_OK}, offsiteOk=${OFFSITE_OK})." >&2
  exit 1
fi

echo "Backup completed successfully."
echo "Manifest: ${MANIFEST_PATH}"
echo "MinIO objects mirrored: ${MINIO_OBJECTS}"
