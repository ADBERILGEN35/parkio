#!/usr/bin/env bash
# Offline policy guards for PP-01B-IAC-01 (no Azure credentials).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
fail=0
ok() { echo "OK:   $*"; }
bad() { echo "FAIL: $*"; fail=1; }

for d in modules/network-dns modules/postgresql-flexible-server modules/database-roles \
         modules/postgis-bootstrap modules/policy-guards stacks/sandbox stacks/staging \
         stacks/production policies tests docs; do
  [[ -d "$ROOT/$d" ]] && ok "directory $d" || bad "missing directory $d"
done

MAN="$ROOT/policies/service-manifest.yaml"
[[ -f "$MAN" ]] || bad "missing service-manifest.yaml"
dbs=$(grep -E '^\s+- \{.*database:' "$MAN" | sed -E 's/.*database: ([^,}+]+).*/\1/' | sort -u | wc -l | tr -d ' ')
roles=$(grep -E '^\s+- \{.*role:' "$MAN" | sed -E 's/.*role: ([^,}+]+).*/\1/' | sort -u | wc -l | tr -d ' ')
[[ "$dbs" == "10" ]] && ok "10 unique databases" || bad "databases=$dbs expected 10"
[[ "$roles" == "10" ]] && ok "10 unique roles" || bad "roles=$roles expected 10"
pc=$(grep -c 'cluster: parking' "$MAN" || true)
[[ "$pc" == "1" ]] && ok "only one parking cluster mapping" || bad "parking cluster count=$pc"

grep -q 'parkio_parking' "$ROOT/modules/postgis-bootstrap/main.tf" && ok "postgis parkio_parking" || bad "postgis target"
grep -q 'public_network_access_enabled\s*=\s*false' "$ROOT/modules/postgresql-flexible-server/main.tf" && ok "public disabled" || bad "public access"
grep -q 'must be false' "$ROOT/stacks/production/variables.tf" && ok "prod apply guard" || bad "prod apply guard"

CI="$REPO/.github/workflows/pp-01b-terraform-offline.yml"
[[ -f "$CI" ]] || bad "missing CI workflow"
if grep -Eiq 'terraform[[:space:]]+(apply|destroy)' "$CI"; then bad "CI has apply/destroy"; else ok "CI no apply"; fi
if grep -Eiq 'azure/login|az login|ARM_CLIENT_SECRET' "$CI"; then bad "CI has Azure creds"; else ok "CI no Azure creds"; fi

for s in sandbox staging production; do
  [[ -f "$ROOT/stacks/$s/.terraform.lock.hcl" ]] && ok "lockfile $s" || bad "missing lockfile $s"
done

if find "$ROOT" -name '*.tfvars' ! -name '*.example' 2>/dev/null | grep -q .; then
  bad "committed tfvars present"
else
  ok "no committed secret tfvars"
fi

if grep -R --include='outputs.tf' -Eiq 'password\s*=|jdbc://' "$ROOT"; then
  bad "secret-like outputs"
else
  ok "outputs clean"
fi

[[ "$fail" -eq 0 ]] && { echo "Policy guards PASSED"; exit 0; }
echo "Policy guards FAILED"; exit 1
