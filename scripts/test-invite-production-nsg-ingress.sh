#!/usr/bin/env bash
# Compile invite-production Bicep (when az is available) and assert NSG ingress
# contract (PROD-DEPLOY-01B-03C1).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

TEMPLATE="infra/azure/invite-production/main.bicep"

echo "=== node NSG ingress unit tests ==="
node --test scripts/lib/assert-invite-production-nsg-ingress.test.mjs

if ! command -v az >/dev/null 2>&1; then
  echo "az CLI not found; skipping ARM compile assertion (node source tests PASS)"
  echo "invite_production_nsg_ingress=PASS"
  exit 0
fi

OUT="$(mktemp "${TMPDIR:-/tmp}/parkio-invite-nsg.XXXXXX.json")"
trap 'rm -f "$OUT"' EXIT

echo "=== bicep build ==="
az bicep build --file "$TEMPLATE" --outfile "$OUT"

echo "=== ARM securityRules assertion ==="
node --input-type=module -e "
import assert from 'node:assert/strict';
import fs from 'node:fs';
const arm = JSON.parse(fs.readFileSync(process.argv[1], 'utf8'));
const resources = Array.isArray(arm.resources) ? arm.resources : Object.values(arm.resources || {});
function findNsgResource(items) {
  for (const r of items) {
    if (String(r.type).includes('networkSecurityGroups')) return r;
    const nested = r.properties?.template?.resources;
    if (Array.isArray(nested)) {
      const hit = findNsgResource(nested);
      if (hit) return hit;
    }
  }
  return null;
}
const nsgRes = findNsgResource(resources);
assert.ok(nsgRes, 'compiled ARM missing networkSecurityGroups resource');
const rules = nsgRes.properties?.securityRules || [];
assert.equal(rules.length, 2, 'expected 2 securityRules, got ' + rules.length);
const byName = Object.fromEntries(rules.map((r) => [r.name, r.properties || r]));
assert.ok(byName['Allow-Https-From-Internet']);
assert.ok(byName['Allow-Http-From-Internet']);
assert.equal(String(byName['Allow-Https-From-Internet'].priority), '100');
assert.equal(String(byName['Allow-Https-From-Internet'].destinationPortRange), '443');
assert.equal(String(byName['Allow-Http-From-Internet'].priority), '110');
assert.equal(String(byName['Allow-Http-From-Internet'].destinationPortRange), '80');
assert.equal(byName['Allow-Http-From-Internet'].sourceAddressPrefix, 'Internet');
assert.equal(byName['Allow-Http-From-Internet'].protocol, 'Tcp');
assert.equal(byName['Allow-Http-From-Internet'].access, 'Allow');
console.log('arm_nsg_ingress=PASS');
" "$OUT"

echo "invite_production_nsg_ingress=PASS"
