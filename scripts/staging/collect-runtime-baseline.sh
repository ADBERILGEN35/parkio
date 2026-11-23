#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/lib/evidence-common.sh"

PROFILE="${PARKIO_BASELINE_PROFILE:-SMOKE}"
if [ "${PROFILE}" = "STAGING_SOAK_CANDIDATE" ] && [ "${PARKIO_ALLOW_STRESS:-}" != "yes" ]; then
  echo "ERROR: stress/soak profiles require PARKIO_ALLOW_STRESS=yes" >&2
  exit 1
fi

OUT="${PARKIO_EVIDENCE_DIR:-build/operational-evidence/manual}/runtime-baseline.json"
mkdir -p "$(dirname "${OUT}")"
cat > "${OUT}" <<EOF
{
  "profile": "${PROFILE}",
  "environmentType": "${PARKIO_ENVIRONMENT_TYPE:-unknown}",
  "representativeness": "NOT_REPRESENTATIVE",
  "classification": "MEASURED_LOCAL",
  "note": "Use benchmarks/k6/http-load.js for full baseline; WP-06.2 records profile metadata only in SMOKE CI"
}
EOF
echo "baseline metadata written to ${OUT}"