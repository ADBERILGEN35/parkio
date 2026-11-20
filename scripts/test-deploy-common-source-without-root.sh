#!/usr/bin/env bash
# Z-01 / #448: deploy-common and the non-root volume gate must source under set -u
# without ROOT. Production callers still set ROOT before running the gate.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

pass=0
fail=0
ok() { echo "PASS: $*"; pass=$((pass + 1)); }
bad() { echo "FAIL: $*"; fail=$((fail + 1)); }

# Clean environment: no ROOT, no leftover overrides. Keep only what bash needs.
if env -i PATH="/usr/bin:/bin" HOME="${HOME:-/tmp}" bash --noprofile --norc -c '
  set -euo pipefail
  cd "'"$ROOT"'"
  source scripts/lib/deploy-common.sh
  source scripts/lib/prod-nonroot-volume-gate.sh
  map="$(parkio_nonroot_volume_gate_map_path)"
  test -r "$map"
'; then
  ok "deploy-common + prod-nonroot-volume-gate source without ROOT (set -u)"
else
  bad "deploy-common + prod-nonroot-volume-gate source without ROOT (set -u)"
fi

echo "Summary: $pass passed, $fail failed"
test "$fail" -eq 0
