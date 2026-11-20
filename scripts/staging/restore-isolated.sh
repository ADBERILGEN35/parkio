#!/usr/bin/env bash
# Wrapper: restore drill with WP-06.2 safety guards.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/lib/safety-guards.sh"
export PARKIO_STAGING_ALLOW_DESTRUCTIVE=yes
assert_staging_safety
assert_destructive_opt_in
exec PARKIO_ENV_FILE="${PARKIO_ENV_FILE:-${ROOT_DIR}/docker/.env}" "${ROOT_DIR}/scripts/restore-drill.sh" "$@"