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
if ! parkio_backup_preflight; then
  exit 2
fi

BACKUP_DIR="${BACKUP_DIR:-./backups}"
STAMP="$(parkio_backup_stamp)"
GIT_SHA="$(parkio_backup_git_sha)"
DEST_DIR="${BACKUP_DIR}/${STAMP}"
case "${ARTIFACT_DIR}" in
  /*) MANIFEST_DIR="${ARTIFACT_DIR}" ;;
  *) MANIFEST_DIR="${ROOT}/${ARTIFACT_DIR}" ;;
esac
MANIFEST_PATH="${MANIFEST_DIR}/backup-${STAMP}.json"
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
PARKIO_BACKUP_FINALIZED=0
trap 'parkio_backup_clear_unfinalized_complete' INT TERM EXIT

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
if ! MINIO_RAW="$("${ROOT}/scripts/backup-minio.sh" "${DEST_DIR}" ${ENV_FILE:+--env-file "$ENV_FILE"})"; then
  MINIO_OK=0
  MINIO_OBJECTS=0
  echo "ERROR: MinIO mirror failed; partial tree will not be sealed." >&2
  parkio_backup_discard_partial_minio "${DEST_DIR}"
else
  MINIO_OBJECTS="$(printf '%s\n' "${MINIO_RAW}" | tail -1 | tr -cd '0-9')"
  MINIO_OBJECTS="${MINIO_OBJECTS:-0}"
fi

if [ -n "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then
  parkio_backup_assert_encrypted_dumps "${DEST_DIR}" || exit 1
  if [ "${MINIO_OK}" -eq 1 ]; then
    if ! parkio_backup_seal_minio "${DEST_DIR}"; then
      echo "ERROR: MinIO client-side encryption failed." >&2
      MINIO_OK=0
      parkio_backup_discard_partial_minio "${DEST_DIR}"
    fi
  fi
fi

ENCRYPT_ON=0
if [ -n "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then ENCRYPT_ON=1; fi

if [ "${DB_FAILED}" -eq 0 ]; then
  DB_OK=${#PARKIO_DB_SERVICES[@]}
else
  DB_OK=0
fi

PARKIO_BACKUP_OFFSITE_UPLOADED=0
parkio_backup_write_manifest "${MANIFEST_PATH}" "${STAMP}" "${GIT_SHA}" "${OPERATOR}" \
  "${ENV_FILE:-<env>}" "${DEST_DIR}" "${DB_OK}" "${DB_FAILED}" "${MINIO_OK}" "${MINIO_OBJECTS}"
cp "${MANIFEST_PATH}" "${DEST_DIR}/backup-manifest.json"

OFFSITE_OK=0
if parkio_backup_allow_complete "${DEST_DIR}" "${DB_FAILED}" "${MINIO_OK}"; then
  if parkio_backup_write_stamp_integrity "${DEST_DIR}" "${STAMP}"; then
    PARKIO_BACKUP_FINALIZED=1
    OFFSITE_OK=1
    if ! parkio_backup_offsite_upload "${DEST_DIR}" "${BACKUP_MC_DEST:-}" "$(basename "${DEST_DIR}")"; then
      OFFSITE_OK=0
    fi
  else
    echo "ERROR: stamp integrity/COMPLETE write failed." >&2
    rm -f "${DEST_DIR}/COMPLETE"
  fi
else
  echo "ERROR: stamp left incomplete (no COMPLETE, no offsite upload)." >&2
  rm -f "${DEST_DIR}/COMPLETE"
fi
if [ "${OFFSITE_OK}" -eq 1 ] && [ "$(parkio_backup_offsite_kind)" != "none" ]; then
  PARKIO_BACKUP_OFFSITE_UPLOADED=1
fi

SUCCESS=0
if [ "${DB_FAILED}" -eq 0 ] && [ "${MINIO_OK}" -eq 1 ] && [ "${OFFSITE_OK}" -eq 1 ]; then
  SUCCESS=1
fi

parkio_backup_write_manifest "${MANIFEST_PATH}" "${STAMP}" "${GIT_SHA}" "${OPERATOR}" \
  "${ENV_FILE:-<env>}" "${DEST_DIR}" "${DB_OK}" "${DB_FAILED}" "${MINIO_OK}" "${MINIO_OBJECTS}"
BACKUP_BYTES="$(du -sb "${DEST_DIR}" 2>/dev/null | awk '{print $1}')"
BACKUP_BYTES="${BACKUP_BYTES:-0}"
parkio_backup_write_metrics "${PARKIO_DEPLOYMENT_PROFILE}" "${SUCCESS}" "$(date -u +%s)" \
  "${DB_FAILED}" "${MINIO_OBJECTS}" "${PARKIO_BACKUP_OFFSITE_UPLOADED}" "${ENCRYPT_ON}" "${BACKUP_BYTES}"

if [ "${SUCCESS}" -eq 1 ]; then
  cp "${MANIFEST_PATH}" "${MANIFEST_DIR}/backup-current.json" 2>/dev/null || true
  parkio_backup_prune_expired_stamps "${BACKUP_DIR}" "${BACKUP_RETENTION_DAYS:-14}"
else
  echo "Backup completed with failures (dbFailed=${DB_FAILED}, minioOk=${MINIO_OK}, offsiteOk=${OFFSITE_OK})." >&2
  echo "Previous complete stamps were not pruned." >&2
  exit 1
fi

echo "Backup completed successfully."
echo "Manifest: ${MANIFEST_PATH}"
echo "MinIO objects mirrored: ${MINIO_OBJECTS}"
