#!/usr/bin/env bash
#
# PROD-MUNI-01 / M5 — Municipal web rollback drill (executable, no deploy by default).
#
# Validates that a municipal rollback target changes only the web image ref while
# backend image refs remain identical. Does not recreate containers unless
# PARKIO_PROD_MUNI_EXECUTE=1 is explicitly set (forbidden in readiness CI).
#
# Usage:
#   ./scripts/prod-muni-01/drill-municipal-rollback.sh
#   ./scripts/prod-muni-01/drill-municipal-rollback.sh \
#     --current scripts/prod-muni-01/fixtures/rollback-current.json \
#     --previous scripts/prod-muni-01/fixtures/rollback-previous.json
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=lib/common.sh
source "$ROOT/scripts/prod-muni-01/lib/common.sh"

cd "$ROOT"
NODE_BIN="$(prod_muni_node)"

CURRENT="${PARKIO_PROD_MUNI_ROLLBACK_CURRENT:-$ROOT/scripts/prod-muni-01/fixtures/rollback-current.json}"
PREVIOUS="${PARKIO_PROD_MUNI_ROLLBACK_PREVIOUS:-$ROOT/scripts/prod-muni-01/fixtures/rollback-previous.json}"
EXECUTE="${PARKIO_PROD_MUNI_EXECUTE:-0}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --current) CURRENT="${2:-}"; shift 2 ;;
    --previous) PREVIOUS="${2:-}"; shift 2 ;;
    --execute) EXECUTE=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

fail=0
ok() { echo "PASS: $1"; }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

echo "=== PROD-MUNI-01 M5 municipal rollback drill ==="
prod_muni_require_file "$CURRENT"
prod_muni_require_file "$PREVIOUS"

BACKENDS='["gateway-service","auth-service","user-service","parking-service","media-service","gamification-service","notification-service","moderation-service","ai-validation-service","analytics-service"]'

"$NODE_BIN" - "$CURRENT" "$PREVIOUS" "$BACKENDS" <<'JS'
const fs = require('fs');
const cur = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const prev = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
const backends = JSON.parse(process.argv[4]);
const errors = [];

for (const key of ['schemaVersion', 'imageTag', 'gitSha', 'images']) {
  if (!(key in cur)) errors.push(`current missing ${key}`);
  if (!(key in prev)) errors.push(`previous missing ${key}`);
}

const cimg = cur.images || {};
const pimg = prev.images || {};
if (!cimg.web || !pimg.web) {
  errors.push('both manifests must include images.web');
} else if (cimg.web === pimg.web) {
  errors.push('web image refs must differ between current and previous for a rollback drill');
} else {
  console.log(`PASS: web image changes ${cimg.web} -> ${pimg.web}`);
}

for (const svc of backends) {
  if (!cimg[svc] || !pimg[svc]) {
    errors.push(`missing backend image for ${svc}`);
  } else if (cimg[svc] !== pimg[svc]) {
    errors.push(
      `backend ${svc} must be unchanged across municipal web rollback (${cimg[svc]} != ${pimg[svc]})`,
    );
  } else {
    console.log(`PASS: backend unchanged ${svc}=${cimg[svc]}`);
  }
}

if (!cur.rollbackCommand) {
  errors.push('current manifest missing rollbackCommand');
} else {
  console.log('PASS: current manifest has rollbackCommand');
}

if (errors.length) {
  for (const e of errors) console.error(`FAIL: ${e}`);
  process.exit(1);
}
JS

# Optional local digest inspection when images exist (does not pull/deploy).
if command -v docker >/dev/null 2>&1; then
  web_prev="$("$NODE_BIN" -e "const m=require(process.argv[1]); process.stdout.write(m.images.web);" "$PREVIOUS")"
  if docker image inspect "$web_prev" >/dev/null 2>&1; then
    digest="$(docker image inspect --format '{{.Id}}' "$web_prev")"
    ok "previous web image present locally digest=$digest"
  else
    echo "INFO: previous web image not present locally (dry-run OK): $web_prev"
  fi
else
  echo "INFO: docker not available; skipping local digest inspection"
fi

if [ "$EXECUTE" = "1" ]; then
  echo "ERROR: PARKIO_PROD_MUNI_EXECUTE/ --execute would recreate hosted-beta web." >&2
  echo "ERROR: PROD-MUNI-01 readiness forbids deployment. Refusing execute." >&2
  exit 3
fi
ok "execute path refused (no deploy) — dry-run drill complete"

if [ "$fail" -ne 0 ]; then
  echo "=== M5 FAILED ===" >&2
  exit 1
fi
echo "=== M5 OK ==="
exit 0
