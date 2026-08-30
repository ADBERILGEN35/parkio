#!/usr/bin/env bash
# Regression gates for PRIV-001A synthetic principal acceptance harness.
# Deterministic. No network. No production mutation.
#
# shellcheck shell=bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# shellcheck source=scripts/lib/priv001-synthetic.sh
source "${ROOT}/scripts/lib/priv001-synthetic.sh"

pass=0
fail=0

assert_pass() {
  local name="$1"
  shift
  if "$@"; then
    echo "PASS: $name"
    pass=$((pass + 1))
  else
    echo "FAIL: $name" >&2
    fail=$((fail + 1))
  fi
}

assert_fail() {
  local name="$1"
  shift
  if "$@"; then
    echo "FAIL: expected rejection: $name" >&2
    fail=$((fail + 1))
  else
    echo "PASS: rejected $name"
    pass=$((pass + 1))
  fi
}

echo "=== PRIV-001A synthetic allowlist ==="

assert_pass "fresh synthetic email" \
  parkio_priv001_email_allowed "priv001a-20260830120000abcd@priv001a.parkio.invalid"

assert_fail "gmail.com" \
  parkio_priv001_email_allowed "user@gmail.com"
assert_fail "outlook.com" \
  parkio_priv001_email_allowed "user@outlook.com"
assert_fail "parkio.dev" \
  parkio_priv001_email_allowed "ops@parkio.dev"
assert_fail "arbitrary domain" \
  parkio_priv001_email_allowed "priv001a-abc@example.com"
assert_fail "empty email" \
  parkio_priv001_email_allowed ""
assert_fail "uppercase domain trick still ok only if normalized — reject wrong local" \
  parkio_priv001_email_allowed "PRIV001A-ABC@GMAIL.COM"
assert_fail "subdomain confusion" \
  parkio_priv001_email_allowed "priv001a-abc@evil.priv001a.parkio.invalid"
assert_fail "suffix confusion" \
  parkio_priv001_email_allowed "priv001a-abc@priv001a.parkio.invalid.evil.com"
assert_fail "missing local prefix" \
  parkio_priv001_email_allowed "user@priv001a.parkio.invalid"
assert_fail "unicode lookalike domain" \
  parkio_priv001_email_allowed "priv001a-abc@priv001a.parkio.іnvalid"
assert_fail "short local id" \
  parkio_priv001_email_allowed "priv001a-ab@priv001a.parkio.invalid"
assert_fail "dot in local-part" \
  parkio_priv001_email_allowed "priv001a-ab.cd@priv001a.parkio.invalid"

# Uppercase synthetic email must normalize and pass.
assert_pass "uppercase synthetic email normalizes" \
  parkio_priv001_email_allowed "PRIV001A-20260830120000ABCD@PRIV001A.PARKIO.INVALID"

echo "=== PRIV-001A production opt-in guards ==="

unset PARKIO_PRIV001_ENVIRONMENT PARKIO_PRIV001_CONFIRM_SYNTHETIC_ONLY PARKIO_DEPLOYMENT_PROFILE || true
assert_fail "missing environment" parkio_priv001_require_invite_production_opt_in

export PARKIO_PRIV001_ENVIRONMENT=invite-production
export PARKIO_PRIV001_CONFIRM_SYNTHETIC_ONLY=
assert_fail "missing confirm flag" parkio_priv001_require_invite_production_opt_in

export PARKIO_PRIV001_CONFIRM_SYNTHETIC_ONLY=yes
export PARKIO_DEPLOYMENT_PROFILE=hosted-beta
assert_fail "wrong deployment profile" parkio_priv001_require_invite_production_opt_in

export PARKIO_DEPLOYMENT_PROFILE=invite-production
assert_pass "opt-in invite-production" parkio_priv001_require_invite_production_opt_in

export PARKIO_PRIV001_ENVIRONMENT=hosted-beta
assert_fail "wrong environment name" parkio_priv001_require_invite_production_opt_in

echo "=== PRIV-001A CLI dry-run / refuse defaults ==="

tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT

if bash scripts/acceptance/create-priv001-synthetic-principal.sh >"$tmp/noflags.out" 2>&1; then
  echo "FAIL: create without flags should fail" >&2
  fail=$((fail + 1))
else
  echo "PASS: create without flags fails"
  pass=$((pass + 1))
fi
grep -q 'requires --environment invite-production' "$tmp/noflags.out"

if bash scripts/acceptance/create-priv001-synthetic-principal.sh \
    --environment invite-production >"$tmp/noconfirm.out" 2>&1; then
  echo "FAIL: create without confirm should fail" >&2
  fail=$((fail + 1))
else
  echo "PASS: create without confirm fails"
  pass=$((pass + 1))
fi

export PARKIO_DEPLOYMENT_PROFILE=invite-production
bash scripts/acceptance/create-priv001-synthetic-principal.sh \
  --environment invite-production \
  --confirm-synthetic-only \
  --dry-run-guards >"$tmp/dry.out" 2>&1
grep -q 'PASS: dry-run guards' "$tmp/dry.out"
grep -q 'priv001a.parkio.invalid' "$tmp/dry.out"
grep -q 'allowlisted-managed-auth-sql' "$tmp/dry.out"
echo "PASS: dry-run guards"
pass=$((pass + 1))

# Refuse non-dark gateway
if bash scripts/acceptance/create-priv001-synthetic-principal.sh \
    --environment invite-production \
    --confirm-synthetic-only \
    --gateway-url 'https://api.parkio.dev' \
    --dry-run-guards >"$tmp/badgw.out" 2>&1; then
  echo "FAIL: public gateway should be refused" >&2
  fail=$((fail + 1))
else
  echo "PASS: public gateway refused"
  pass=$((pass + 1))
fi

echo "=== PRIV-001A verification SQL contract (fake psql) ==="

mkdir -p "$tmp/bin"
cat > "$tmp/bin/psql" <<'FAKE_PSQL'
#!/usr/bin/env bash
set -euo pipefail
# Refuse arbitrary -c that looks like operator paste of DROP / multi-statement abuse
# beyond the harness transaction. Record argv for assertions.
printf '%s\0' "$@" > "${PARKIO_TEST_PSQL_ARGV}"
email=""
max_age=""
uid=""
sql=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -v)
      case "$2" in
        email=*) email="${2#email=}" ;;
        max_age=*) max_age="${2#max_age=}" ;;
        uid=*) uid="${2#uid=}" ;;
      esac
      shift 2
      ;;
    -c) sql="$2"; shift 2 ;;
    *) shift ;;
  esac
done

# Simulate multi-row / precondition failures via env.
case "${PARKIO_TEST_PSQL_MODE:-ok}" in
  multi)
    echo "ERROR: more than one row returned by a subquery used as an expression" >&2
    exit 1
    ;;
  zero)
    # empty returning
    exit 0
    ;;
  already_verified)
    echo "ERROR: PRIV001_VERIFY_PRECONDITION_FAILED" >&2
    exit 1
    ;;
  erased)
    echo "ERROR: PRIV001_VERIFY_PRECONDITION_FAILED" >&2
    exit 1
    ;;
  inspect)
    printf '%s\n' '{"auth_state":"ERASED","email_verified":"false","auth_row_count":1,"erasure_request_count":1,"participant_ack_count":8,"tombstone_count":1,"hard_delete_residue_count":0,"deidentified_residue_count":1,"forbidden_user_id_hits":0,"media_object_count":0,"cross_service_sql":"NOT_EXECUTED_BY_CONTRACT"}'
    exit 0
    ;;
  *)
    # Prove SQL is bounded and parameterized (no raw email concatenation of attacker SQL).
    printf '%s' "$sql" | grep -q "email = :'email'"
    printf '%s' "$sql" | grep -q "PENDING_VERIFICATION"
    printf '%s' "$sql" | grep -Fq "FOR UPDATE"
    printf '%s' "$sql" | grep -q "erased_user_tombstones"
    printf '%s' "$sql" | grep -q "erasure_requests"
    # No arbitrary operator SQL channel: harness never accepts --sql.
    case "$sql" in
      *DROP\ *|*TRUNCATE\ *) exit 1 ;;
    esac
    if [ -z "$email" ] && [ -z "$uid" ]; then
      exit 1
    fi
    if [ -n "$email" ]; then
      printf '%s\n' '11111111-1111-4111-8111-111111111111'
    fi
    exit 0
    ;;
esac
FAKE_PSQL
chmod +x "$tmp/bin/psql"

export PATH="$tmp/bin:$PATH"
export PARKIO_PG_MODE=managed
export PARKIO_PG_HOST=pg.example.postgres.database.azure.com
export PARKIO_PG_SSLMODE=verify-full
export PGPASSWORD='test-db-password-not-for-output'
export PARKIO_PRIV001_ENVIRONMENT=invite-production
export PARKIO_PRIV001_CONFIRM_SYNTHETIC_ONLY=yes
export PARKIO_DEPLOYMENT_PROFILE=invite-production
export PARKIO_TEST_PSQL_ARGV="$tmp/psql.argv"
export PARKIO_TEST_PSQL_MODE=ok

GOOD_EMAIL='priv001a-20260830120000abcd@priv001a.parkio.invalid'
VERIFIED="$(parkio_priv001_mark_verified "$GOOD_EMAIL" 900)"
[ "$VERIFIED" = '11111111-1111-4111-8111-111111111111' ]
echo "PASS: mark_verified happy path"
pass=$((pass + 1))

assert_fail "gmail cannot be verified" \
  parkio_priv001_mark_verified 'attacker@gmail.com' 900
assert_fail "arbitrary uuid email rejected" \
  parkio_priv001_mark_verified 'priv001a-abc@evil.com' 900

export PARKIO_TEST_PSQL_MODE=multi
assert_fail "multi-row update rejected" \
  parkio_priv001_mark_verified "$GOOD_EMAIL" 900

export PARKIO_TEST_PSQL_MODE=zero
assert_fail "zero-row update rejected" \
  parkio_priv001_mark_verified "$GOOD_EMAIL" 900

export PARKIO_TEST_PSQL_MODE=already_verified
assert_fail "already verified rejected" \
  parkio_priv001_mark_verified "$GOOD_EMAIL" 900

export PARKIO_TEST_PSQL_MODE=erased
assert_fail "erased user rejected" \
  parkio_priv001_mark_verified "$GOOD_EMAIL" 900

# SSL disable refused
export PARKIO_PG_SSLMODE=disable
export PARKIO_TEST_PSQL_MODE=ok
assert_fail "sslmode=disable refused" \
  parkio_priv001_mark_verified "$GOOD_EMAIL" 900
export PARKIO_PG_SSLMODE=verify-full

# No --sql channel on create CLI
if bash scripts/acceptance/create-priv001-synthetic-principal.sh \
    --environment invite-production \
    --confirm-synthetic-only \
    --sql 'UPDATE auth_users SET email_verified=true' \
    --dry-run-guards >"$tmp/sqlarg.out" 2>&1; then
  echo "FAIL: --sql must not be accepted" >&2
  fail=$((fail + 1))
else
  echo "PASS: arbitrary --sql rejected"
  pass=$((pass + 1))
fi

echo "=== PRIV-001A inspect tooling ==="
export PARKIO_TEST_PSQL_MODE=inspect
bash scripts/acceptance/inspect-priv001-synthetic-residue.sh \
  --environment invite-production \
  --confirm-synthetic-only \
  --auth-user-id '11111111-1111-4111-8111-111111111111' \
  --email "$GOOD_EMAIL" >"$tmp/inspect.out" 2>&1
grep -q 'participant_ack_count' "$tmp/inspect.out"
grep -q 'tombstone_count' "$tmp/inspect.out"
if grep -Ei 'password|Bearer |eyJ|PGPASSWORD|verification_token' "$tmp/inspect.out"; then
  echo "FAIL: inspect output leaked secrets" >&2
  fail=$((fail + 1))
else
  echo "PASS: inspect output secret-clean"
  pass=$((pass + 1))
fi

# Arbitrary UUID format rejected
if bash scripts/acceptance/inspect-priv001-synthetic-residue.sh \
    --environment invite-production \
    --confirm-synthetic-only \
    --auth-user-id 'not-a-uuid' >"$tmp/baduuid.out" 2>&1; then
  echo "FAIL: bad uuid accepted" >&2
  fail=$((fail + 1))
else
  echo "PASS: arbitrary uuid rejected"
  pass=$((pass + 1))
fi

echo "=== PRIV-001A secret safety ==="
printf '%s\n' '{"ok":true,"package":"PRIV-001A"}' > "$tmp/clean.json"
assert_pass "clean evidence passes secret scan" \
  parkio_priv001_secret_scan_text "$tmp/clean.json"

printf '%s\n' 'access_token=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.aaaaaaaaaa.bbbbbbbbbb' > "$tmp/jwt.json"
assert_fail "jwt-like evidence fails secret scan" \
  parkio_priv001_secret_scan_text "$tmp/jwt.json"

printf '%s\n' 'postgres://parkio:SuperSecret@pg.example/auth' > "$tmp/dsn.json"
assert_fail "dsn evidence fails secret scan" \
  parkio_priv001_secret_scan_text "$tmp/dsn.json"

# Create dry-run output must not contain password material / jwt patterns
if grep -Ei 'password=|access_token=|Bearer |eyJ|PGPASSWORD|test-db-password' "$tmp/dry.out"; then
  echo "FAIL: dry-run leaked secrets" >&2
  fail=$((fail + 1))
else
  echo "PASS: dry-run secret-clean"
  pass=$((pass + 1))
fi

# Prove harness does not enable global verification bypass env
if grep -RniE 'PARKIO_EMAIL_VERIFICATION_BYPASS|email.verification.bypass|DISABLE_EMAIL_VERIFICATION' \
    scripts/lib/priv001-synthetic.sh \
    scripts/acceptance/create-priv001-synthetic-principal.sh \
    scripts/acceptance/inspect-priv001-synthetic-residue.sh; then
  echo "FAIL: global verification bypass marker found" >&2
  fail=$((fail + 1))
else
  echo "PASS: no global verification bypass"
  pass=$((pass + 1))
fi

echo "=== summary pass=${pass} fail=${fail} ==="
if [ "$fail" -ne 0 ]; then
  exit 1
fi
echo "PRIV-001A synthetic principal harness contract: PASS"
