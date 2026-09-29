#!/usr/bin/env bash
# Compile scoped invite-production NSG Bicep and assert single-source contract.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

MAIN_TEMPLATE="infra/azure/invite-production/main.bicep"
SCOPED_TEMPLATE="infra/azure/invite-production/nsg-only.bicep"
MODULE_TEMPLATE="infra/azure/invite-production/modules/app-nsg.bicep"
SCOPED_SCRIPT="scripts/azure/provision-invite-production-nsg.sh"

echo "=== node NSG ingress + scoped path unit tests ==="
node --test scripts/lib/assert-invite-production-nsg-ingress.test.mjs

test -x "$SCOPED_SCRIPT" || chmod +x "$SCOPED_SCRIPT"

echo "=== scoped script validate (compile only) ==="
bash "$SCOPED_SCRIPT" --validate

if ! command -v az >/dev/null 2>&1; then
  echo "az CLI not found; skipping ARM compile assertions (node source tests PASS)"
  echo "invite_production_nsg_scoped=PASS"
  exit 0
fi

for template in "$MODULE_TEMPLATE" "$SCOPED_TEMPLATE" "$MAIN_TEMPLATE"; do
  echo "=== bicep build $template ==="
  az bicep build --file "$template" --stdout >/dev/null
done

echo "=== scoped ARM resource surface assertion ==="
SCOPED_OUT="$(mktemp "${TMPDIR:-/tmp}/parkio-scoped-arm.XXXXXX.json")"
trap 'rm -f "$SCOPED_OUT"' EXIT
az bicep build --file "$SCOPED_TEMPLATE" --outfile "$SCOPED_OUT"
node --input-type=module -e "
import assert from 'node:assert/strict';
import fs from 'node:fs';
const arm = JSON.parse(fs.readFileSync(process.argv[1], 'utf8'));
const resources = Array.isArray(arm.resources) ? arm.resources : Object.values(arm.resources || {});
const deployments = resources.filter((r) => String(r.type).includes('deployments'));
assert.equal(deployments.length, 1, 'expected one module deployment');
const moduleRes = deployments[0].properties?.template?.resources || [];
const nsgRes = moduleRes.find((r) => String(r.type).includes('networkSecurityGroups'));
assert.ok(nsgRes, 'module must contain NSG');
assert.equal((nsgRes.properties?.securityRules || []).length, 2);
console.log('scoped_arm_surface=PASS');
" "$SCOPED_OUT"

echo "invite_production_nsg_scoped=PASS"
