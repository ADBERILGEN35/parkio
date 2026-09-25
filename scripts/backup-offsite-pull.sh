#!/usr/bin/env bash
#
# Pull a completed backup stamp from offsite into a clean directory and verify checksums.
# Does not restore databases. Does not print secrets.
#
# Usage:
#   PARKIO_ENV_FILE=docker/.env ./scripts/backup-offsite-pull.sh --stamp <stamp> --dest /tmp/parkio-restore
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/backup-common.sh
source "${ROOT}/scripts/lib/backup-common.sh"

ENV_FILE="${PARKIO_ENV_FILE:-}"
STAMP=""
DEST=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --stamp) STAMP="${2:-}"; shift 2 ;;
    --dest) DEST="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [ -z "${STAMP}" ] || [ -z "${DEST}" ]; then
  echo "Usage: $0 --stamp <stamp> --dest <dir>" >&2
  exit 2
fi

parkio_backup_load_env "${ENV_FILE}"

if [ -e "${DEST}" ]; then
  echo "ERROR: destination must not already exist: ${DEST}" >&2
  exit 2
fi
mkdir -p "${DEST}"

echo "Pulling offsite stamp ${STAMP} -> ${DEST}"
parkio_backup_offsite_pull "${DEST}" "${STAMP}"
echo "RESULT: PASS — offsite stamp pulled and checksums verified."
