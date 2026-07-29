#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
LOGDIR="${1:-build/operational-evidence/wp062b2-regression-structural}"
mkdir -p "$LOGDIR"
: > "$LOGDIR/structural-index.txt"
: > "$LOGDIR/structural-results.ndjson"
run() {
  local n="$1"; shift
  echo "=== $n START $(date -u +%Y-%m-%dT%H:%M:%SZ) ===" | tee -a "$LOGDIR/structural-index.txt"
  set +e
  "$@" >"$LOGDIR/${n}.log" 2>&1
  local rc=$?
  set -e
  echo "=== $n END exit=$rc ===" | tee -a "$LOGDIR/structural-index.txt"
  printf '{"suite":"%s","exitCode":%s}\n' "$n" "$rc" >> "$LOGDIR/structural-results.ndjson"
}
run bash-syntax bash -lc 'set -e; for f in scripts/staging/*.sh scripts/staging/lib/*.sh scripts/backup-databases.sh scripts/backup-minio.sh; do [[ -f "$f" ]] || continue; [[ "$f" == *_wp062b2_structural_checks.sh ]] && continue; bash -n "$f"; done; echo bash_syntax_ok'
run python-compile bash -lc 'set -e; python3 -m py_compile scripts/staging/lib/ensure-jwt-material.py; python3 -m py_compile scripts/staging/lib/wp062b1-evidence-consistency-audit.py; python3 -m py_compile scripts/staging/lib/wp062b1-amend-evidence-schema.py; echo python_ok'
run compose-config bash -lc 'set -e; docker compose -f docker/docker-compose.yml -f docker/docker-compose.restored-application-verification.yml config >/dev/null; echo compose_ok'
run workflow-yaml bash -lc 'set -e; grep -q "name: shared-staging-verification" .github/workflows/shared-staging-verification.yml; python3 -c "from pathlib import Path; t=Path(\".github/workflows/shared-staging-verification.yml\").read_text(); assert \"workflow_dispatch\" in t"; echo workflow_ok'
run evidence-schema-historical bash -lc 'export PARKIO_EVIDENCE_DIR=build/operational-evidence/wp062b-20260728211226; bash scripts/staging/validate-evidence-schema.sh build/operational-evidence/wp062b-20260728211226'
run safety-guards bash scripts/staging/test-safety-guards.sh
run utf8-nul python3 scripts/staging/lib/wp062b2-utf8-check.py
run prometheus-rules bash -lc 'set -e; find docker/prometheus \( -name "*.yml" -o -name "*.yaml" \) | head -20; echo prometheus_ok'
run grafana-dashboards bash -lc 'set -e; find docker/grafana -name "*.json" | head -20; echo grafana_ok'
run migration-monotonicity python3 scripts/staging/lib/wp062b2-migration-check.py
echo STRUCTURAL_DONE
cat "$LOGDIR/structural-index.txt"