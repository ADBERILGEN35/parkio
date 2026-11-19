#!/usr/bin/env bash
#
# PROD-MUNI-01 / M6 — Evidence runner (machine-readable JSON bundle).
#
# Runs M3→M4→M1(contract)→M5→M2→M7 and writes a single JSON evidence file.
# Does not deploy. Does not enable production municipal discovery.
#
# Usage:
#   ./scripts/prod-muni-01/run-evidence.sh
#   PARKIO_PROD_MUNI_EVIDENCE_DIR=/tmp/prod-muni-01 ./scripts/prod-muni-01/run-evidence.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=lib/common.sh
source "$ROOT/scripts/prod-muni-01/lib/common.sh"

cd "$ROOT"
OUT_DIR="${PARKIO_PROD_MUNI_EVIDENCE_DIR:-$ROOT/deploy-artifacts/prod-muni-01}"
mkdir -p "$OUT_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
GIT_SHA="$(prod_muni_git_sha)"
BUNDLE="$OUT_DIR/evidence-${STAMP}-${GIT_SHA:0:12}.json"
LOG_DIR="$OUT_DIR/logs-${STAMP}"
mkdir -p "$LOG_DIR"
NODE_BIN="$(prod_muni_node)"

run_gate() {
  local id="$1"
  shift
  local log="$LOG_DIR/${id}.log"
  local status=0
  set +e
  "$@" >"$log" 2>&1
  status=$?
  set -e
  echo "$status"
}

echo "=== PROD-MUNI-01 M6 evidence runner ==="
echo "out=$BUNDLE"

M3_STATUS="$(run_gate m3 bash "$ROOT/scripts/prod-muni-01/guard-production-bake.sh")"
M4_STATUS="$(run_gate m4 bash "$ROOT/scripts/prod-muni-01/guard-production-dark-pins.sh")"
M1_STATUS="$(run_gate m1 env PARKIO_SMOKE_MUNICIPAL_CONTRACT_ONLY=1 bash "$ROOT/scripts/prod-muni-01/smoke-municipal.sh")"
M5_STATUS="$(run_gate m5 bash "$ROOT/scripts/prod-muni-01/drill-municipal-rollback.sh")"
M2_STATUS="$(run_gate m2 bash "$ROOT/scripts/prod-muni-01/drill-municipal-kill-switch.sh")"
M7_JSON="$LOG_DIR/m7.json"
bash "$ROOT/scripts/prod-muni-01/detect-alert-path-residual.sh" --json >"$M7_JSON"
M7_STATUS=0

LIVE_SMOKE_STATUS="skipped"
if [ "${PARKIO_SMOKE_MUNICIPAL_LIVE:-0}" = "1" ]; then
  LIVE_SMOKE_STATUS="$(run_gate m1-live env PARKIO_SMOKE_MUNICIPAL_CONTRACT_ONLY=0 bash "$ROOT/scripts/prod-muni-01/smoke-municipal.sh")"
fi

"$NODE_BIN" - "$BUNDLE" "$GIT_SHA" "$STAMP" "$OUT_DIR" "$LOG_DIR" \
  "$M3_STATUS" "$M4_STATUS" "$M1_STATUS" "$M5_STATUS" "$M2_STATUS" "$M7_STATUS" \
  "$LIVE_SMOKE_STATUS" "$M7_JSON" <<'JS'
const fs = require('fs');
const [
  bundlePath, gitSha, stamp, outDir, logDir,
  m3, m4, m1, m5, m2, m7, live, m7JsonPath,
] = process.argv.slice(2);

function statusOf(code) {
  if (code === 'skipped') {
    return { ran: false, exitCode: null, result: 'skipped' };
  }
  const exitCode = Number(code);
  return {
    ran: true,
    exitCode,
    result: exitCode === 0 ? 'pass' : 'fail',
  };
}

const m7Payload = JSON.parse(fs.readFileSync(m7JsonPath, 'utf8'));
let overallFail = [m3, m4, m1, m5, m2, m7].some((x) => Number(x) !== 0);
if (live !== 'skipped' && Number(live) !== 0) overallFail = true;

const generatedAt = `${stamp.slice(0, 4)}-${stamp.slice(4, 6)}-${stamp.slice(6, 8)}T${stamp.slice(9, 11)}:${stamp.slice(11, 13)}:${stamp.slice(13, 15)}Z`;

const doc = {
  schemaVersion: 1,
  artifact: 'PROD-MUNI-01-evidence',
  generatedAt,
  gitSha,
  evidenceDir: outDir,
  logDir,
  productionMunicipalDiscoveryEnabled: false,
  deployed: false,
  backendMutated: false,
  gates: {
    M3_productionBakeGuard: statusOf(m3),
    M4_productionDarkPinGate: statusOf(m4),
    M1_municipalSmokeContract: statusOf(m1),
    M1_municipalSmokeLive: statusOf(live),
    M5_municipalRollbackDrill: statusOf(m5),
    M2_municipalKillSwitchDrill: statusOf(m2),
    M7_alertPathResidual: {
      ...statusOf(m7),
      detector: m7Payload,
    },
  },
  images: {
    note: 'No deploy performed; image digests not mutated by PROD-MUNI-01 readiness gates.',
    web: null,
    backends: null,
  },
  flags: {
    VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED_production: false,
    linking: false,
    izelmanPublication: false,
    osmPublication: false,
  },
  overall: {
    result: overallFail ? 'fail' : 'pass',
    prodMuni01ExecutableReadiness: !overallFail,
    productionEnablementAuthorized: false,
  },
};

fs.writeFileSync(bundlePath, `${JSON.stringify(doc, null, 2)}\n`, 'utf8');
console.log(JSON.stringify({ bundle: bundlePath, overall: doc.overall }, null, 2));
process.exit(overallFail ? 1 : 0);
JS
