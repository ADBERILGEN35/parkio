#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/lib/safety-guards.sh"
source "${SCRIPT_DIR}/lib/evidence-common.sh"
assert_staging_safety
BACKUP_DIR="${BACKUP_DIR:-${ROOT_DIR}/backups/wp062}"
PARKIO_ENV_FILE="${PARKIO_ENV_FILE:-${ROOT_DIR}/docker/.env}" BACKUP_DIR="${BACKUP_DIR}" "${ROOT_DIR}/scripts/backup-databases.sh"
write_json_stage "backup" "BACKUP_VERIFIED"