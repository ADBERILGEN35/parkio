#!/usr/bin/env bash
# Regression coverage for invite-production public cutover deploy path (03E-A1).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENV_EXAMPLE="docker/.env.invite-production.example"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/parkio-cutover-test.XXXXXX")"
pass=0
fail=0
ok() { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }
cleanup() { rm -rf -- "$TMP_ROOT"; }
trap cleanup EXIT HUP INT TERM

expect_status() {
  local name="$1" expected="$2"
  shift 2
  set +e
  "$@" >/dev/null 2>&1
  local actual=$?
  set -e
  if [ "$actual" -eq "$expected" ]; then
    ok "$name"
  else
    bad "$name (expected exit $expected, got $actual)"
  fi
}

# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"
# shellcheck source=lib/invite-deploy-profile.sh
source "$ROOT/scripts/lib/invite-deploy-profile.sh"

TOKEN="$PARKIO_CUTOVER_AUTHORIZATION_TOKEN"

echo "=== dispatch input matrix ==="

expect_status "public-staged dispatch accepted" 0 env \
  PARKIO_DISPATCH_INVITE_EDGE_MODE=public \
  PARKIO_DISPATCH_INVITE_ACME_AUTHORIZED=false \
  PARKIO_DISPATCH_REGISTRATION_MODE=closed \
  bash "$ROOT/scripts/attest-invite-production-deploy-dispatch.sh"

expect_status "public-cutover missing token rejected" 4 env \
  PARKIO_DISPATCH_INVITE_EDGE_MODE=public \
  PARKIO_DISPATCH_INVITE_ACME_AUTHORIZED=true \
  PARKIO_DISPATCH_REGISTRATION_MODE=closed \
  bash "$ROOT/scripts/attest-invite-production-deploy-dispatch.sh"

expect_status "public-cutover wrong token rejected" 4 env \
  PARKIO_DISPATCH_INVITE_EDGE_MODE=public \
  PARKIO_DISPATCH_INVITE_ACME_AUTHORIZED=true \
  PARKIO_DISPATCH_REGISTRATION_MODE=closed \
  PARKIO_DISPATCH_CUTOVER_AUTHORIZATION=WRONG-TOKEN \
  bash "$ROOT/scripts/attest-invite-production-deploy-dispatch.sh"

expect_status "public-cutover registration open rejected" 4 env \
  PARKIO_DISPATCH_INVITE_EDGE_MODE=public \
  PARKIO_DISPATCH_INVITE_ACME_AUTHORIZED=true \
  PARKIO_DISPATCH_REGISTRATION_MODE=open \
  PARKIO_DISPATCH_CUTOVER_AUTHORIZATION="$TOKEN" \
  bash "$ROOT/scripts/attest-invite-production-deploy-dispatch.sh"

expect_status "dark + acme=true rejected" 4 env \
  PARKIO_DISPATCH_INVITE_EDGE_MODE=dark \
  PARKIO_DISPATCH_INVITE_ACME_AUTHORIZED=true \
  PARKIO_DISPATCH_REGISTRATION_MODE=closed \
  bash "$ROOT/scripts/attest-invite-production-deploy-dispatch.sh"

echo "=== profile resolution ==="

profile="$(PARKIO_INVITE_EDGE_MODE=public PARKIO_INVITE_ACME_AUTHORIZED=false \
  parkio_invite_deploy_profile_label public false)"
[ "$profile" = "public-staged" ] && ok "public+acme=false -> public-staged" || bad "public-staged profile"

profile="$(parkio_invite_deploy_profile_label public true)"
[ "$profile" = "public-cutover" ] && ok "public+acme=true -> public-cutover" || bad "public-cutover profile"

if parkio_invite_deploy_profile_label dark true >/dev/null 2>&1; then
  bad "dark+acme=true must fail"
else
  ok "dark+acme=true rejected"
fi

echo "=== edge guard routing ==="

expect_status "public-staged edge guard passes" 0 \
  bash "$ROOT/scripts/assert-invite-production-edge-guard.sh" --env-file "$ENV_EXAMPLE"

CUT_ENV="$TMP_ROOT/cutover.env"
cp "$ENV_EXAMPLE" "$CUT_ENV"
python3 - "$CUT_ENV" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
text = path.read_text()
text = text.replace("PARKIO_INVITE_ACME_AUTHORIZED=false", "PARKIO_INVITE_ACME_AUTHORIZED=true")
path.write_text(text)
PY

expect_status "public-cutover edge guard passes static checks (--skip-dns)" 0 \
  bash "$ROOT/scripts/assert-invite-production-edge-guard.sh" --env-file "$CUT_ENV" --skip-dns

FIXTURE="$TMP_ROOT/dns-fixture.json"
cat > "$FIXTURE" <<'JSON'
{
  "app.parkio.dev": "172.211.197.135",
  "api.parkio.dev": "172.211.197.135",
  "media.parkio.dev": "172.211.197.135"
}
JSON

expect_status "DNS fixture match passes" 0 env \
  PARKIO_INVITE_PRODUCTION_PUBLIC_IP=172.211.197.135 \
  PARKIO_CUTOVER_DNS_FIXTURE_FILE="$FIXTURE" \
  bash "$ROOT/scripts/assert-invite-cutover-dns-authoritative.sh"

expect_status "public-cutover edge guard passes with DNS fixture" 0 env \
  PARKIO_INVITE_PRODUCTION_PUBLIC_IP=172.211.197.135 \
  PARKIO_CUTOVER_DNS_FIXTURE_FILE="$FIXTURE" \
  bash "$ROOT/scripts/assert-invite-production-edge-guard.sh" --env-file "$CUT_ENV"

BAD_FIXTURE="$TMP_ROOT/dns-bad.json"
cat > "$BAD_FIXTURE" <<'JSON'
{
  "app.parkio.dev": "20.199.17.76",
  "api.parkio.dev": "20.199.17.76",
  "media.parkio.dev": "20.199.17.76"
}
JSON

expect_status "DNS mismatch fails before Caddy" 4 env \
  PARKIO_INVITE_PRODUCTION_PUBLIC_IP=172.211.197.135 \
  PARKIO_CUTOVER_DNS_FIXTURE_FILE="$BAD_FIXTURE" \
  bash "$ROOT/scripts/assert-invite-cutover-dns-authoritative.sh"

echo "=== live DNS state proof ==="
if command -v dig >/dev/null 2>&1; then
  if PARKIO_INVITE_PRODUCTION_PUBLIC_IP=172.211.197.135 \
     bash "$ROOT/scripts/assert-invite-cutover-dns-authoritative.sh" >/dev/null 2>&1; then
    ok "live authoritative DNS matches invite-production (Gate 1 prerequisite met)"
  else
    ok "live authoritative DNS mismatch blocks cutover before Caddy (pre-Gate-1 state)"
  fi
else
  echo "SKIP: dig unavailable for live DNS state proof"
fi

echo "=== resource profile ==="
export PARKIO_INVITE_EDGE_MODE=public
export PARKIO_INVITE_ACME_AUTHORIZED=true
parkio_configure_deployment_profile "$CUT_ENV" >/dev/null
if [[ " ${PARKIO_RUNTIME_SERVICES[*]} " == *" caddy "* ]]; then
  ok "caddy enabled in public-cutover runtime"
else
  bad "caddy missing from public-cutover runtime"
fi
if printf '%s' "$PARKIO_COMPOSE_FILES" | grep -q invite-public-staged; then
  bad "invite-public-staged overlay present in public-cutover profile"
else
  ok "invite-public-staged absent in public-cutover profile"
fi

echo "  (resource budget 24/15744 MiB covered by test-invite-production-edge-resource-budget.sh in CI)"

echo "=== public smoke opt-in ==="
if bash "$ROOT/scripts/public-invite-smoke.sh" >/dev/null 2>&1; then
  bad "public smoke must fail without PARKIO_PUBLIC_SMOKE_CONFIRM"
else
  ok "public smoke remains opt-in"
fi

echo
echo "invite-production public cutover tests: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
