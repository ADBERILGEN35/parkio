#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
HIST=build/operational-evidence/wp062b-20260728211226
NEW=build/operational-evidence/wp062b2-20260729073440
REG=build/operational-evidence/wp062b2-regression-20260729102728

export PARKIO_EVIDENCE_DIR="$NEW"
bash scripts/staging/validate-evidence-schema.sh "$NEW" | tee "$NEW/evidence-schema-validation.log"

python3 scripts/staging/lib/wp062b1-evidence-consistency-audit.py "$NEW" | tee "$NEW/evidence-consistency-audit-console.log" || true
# audit may write its own file; if script takes path differently, try --help
ls -la "$NEW"/*.json | head -80

python3 scripts/staging/lib/wp062b2-finalize-evidence.py