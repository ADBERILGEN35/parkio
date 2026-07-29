#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/lib/safety-guards.sh"
assert_staging_safety
GATEWAY="${PARKIO_GATEWAY_URL:-http://127.0.0.1:8080}"
if ! curl -sf --connect-timeout 5 "${GATEWAY}/actuator/health" >/dev/null; then
  echo "SKIP: gateway unavailable for application semantics (${GATEWAY})"
  exit 0
fi
export PARKIO_GATEWAY_URL="${GATEWAY}"
export PARKIO_ENV_FILE="${PARKIO_ENV_FILE:-$(cd "${SCRIPT_DIR}/../.." && pwd)/docker/.env}"
exec "${SCRIPT_DIR}/run-critical-journeys.sh"
