#!/usr/bin/env bash
# CLI for the default-off production recovery evidence validation adapter (#367).
# See scripts/lib/production-recovery-evidence.sh. Never enables production restore.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/production-recovery-evidence.sh
source "${ROOT}/scripts/lib/production-recovery-evidence.sh"
export PARKIO_REPO_ROOT="${ROOT}"

usage() {
  cat <<'EOF'
Usage:
  production-recovery-evidence.sh evaluate \
    --bundle PATH --trust PATH --attempt UUID \
    --dataset ID --target-identity ID --out PATH

Requires PARKIO_PRODUCTION_RECOVERY_EVIDENCE_ADAPTER=1. Default is off (P-DEFAULT-OFF).
Exit 0 = adapter trusted the bundle (verifiedCoverage stays false; production restore BLOCKED).
Exit 3 = refused. Exit 2 = usage.
EOF
}

cmd="${1:-}"
shift || true
case "${cmd}" in
  evaluate)
    parkio_production_recovery_evidence_evaluate "$@"
    ;;
  -h|--help|help|"")
    usage
    exit 2
    ;;
  *)
    echo "ERROR: unknown command: ${cmd}" >&2
    usage
    exit 2
    ;;
esac
