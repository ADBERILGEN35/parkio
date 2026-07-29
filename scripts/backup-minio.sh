#!/usr/bin/env bash
#
# Parkio - mirror the MinIO media bucket for hosted-beta backup.
#
# Usage:
#   PARKIO_ENV_FILE=docker/.env ./scripts/backup-minio.sh /var/backups/parkio/<stamp>
#   PARKIO_ENV_FILE=docker/.env ./scripts/backup-minio.sh /var/backups/parkio/<stamp> --dry-run
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/backup-common.sh
source "$ROOT/scripts/lib/backup-common.sh"

DEST_DIR=""
ENV_FILE="${PARKIO_ENV_FILE:-}"
DRY_RUN=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *)
      if [ -z "${DEST_DIR}" ]; then DEST_DIR="$1"; shift
      else echo "ERROR: unexpected argument '$1'" >&2; exit 2
      fi ;;
  esac
done

if [ -z "${DEST_DIR}" ]; then
  echo "Usage: $0 <destination-dir> [--dry-run] [--env-file <path>]" >&2
  exit 2
fi

parkio_backup_load_env "${ENV_FILE}"

BUCKET="${MINIO_BUCKET:-parkio-media}"
MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:?set MINIO_ROOT_PASSWORD in env}"
MC_IMAGE="${MINIO_MC_IMAGE:-minio/mc:RELEASE.2024-09-16T17-43-14Z}"
MIRROR_DEST="${DEST_DIR}/minio/${BUCKET}"

MINIO_CONTAINER="${PARKIO_MINIO_CONTAINER:-parkio-minio}"
if ! docker inspect "${MINIO_CONTAINER}" >/dev/null 2>&1; then
  echo "ERROR: MinIO container '${MINIO_CONTAINER}' not found." >&2
  exit 1
fi

NETWORK="$(parkio_backup_backend_network "${MINIO_CONTAINER}")"
if [ -z "${NETWORK}" ]; then
  echo "ERROR: could not resolve backend Docker network for ${MINIO_CONTAINER}." >&2
  exit 1
fi

echo "MinIO backup -> ${MIRROR_DEST} (bucket=${BUCKET}, container=${MINIO_CONTAINER}, network=${NETWORK}, dryRun=${DRY_RUN})"

if [ "$DRY_RUN" -eq 1 ]; then
  echo "DRY-RUN: would mirror local/${BUCKET} to ${MIRROR_DEST}"
  echo "0"
  exit 0
fi

mkdir -p "${MIRROR_DEST}"

docker run --rm \
  --network "${NETWORK}" \
  -v "${MIRROR_DEST}:/backup" \
  -e "MINIO_ROOT_USER=${MINIO_ROOT_USER:-minioadmin}" \
  -e "MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD}" \
  -e "BUCKET=${BUCKET}" \
  --entrypoint /bin/sh \
  "${MC_IMAGE}" \
  -c '
    set -eu
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
    mc mirror --overwrite "local/${BUCKET}" /backup
    mc ls --recursive "local/${BUCKET}" | wc -l
  '
