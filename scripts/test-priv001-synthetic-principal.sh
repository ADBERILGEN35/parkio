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

echo "=== PRIV-001A psql option-ordering regression (f1b9d6f defect) ==="

# GNU psql clusters -tAc as -t -A -c; the next argv becomes SQL for -c.
# Broken harness: psql ... -tAc -v email=x -c "$sql" → SQL executed is "-v".
simulate_tAc_sql() {
  local next="${1-}"
  printf '%s' "$next"
}
BROKEN_SQL="$(simulate_tAc_sql '-v')"
[ "$BROKEN_SQL" = '-v' ]
echo "PASS: -tAc clusters consume following argv as -c SQL (root cause)"
pass=$((pass + 1))

echo "=== PRIV-001A verification SQL contract (fake psql) ==="

mkdir -p "$tmp/bin"
cat > "$tmp/bin/psql" <<'FAKE_PSQL'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\0' "$@" > "${PARKIO_TEST_PSQL_ARGV}"
orig="$*"
case "$orig" in
  *-tAc\ -v*)
    if [ "${PARKIO_TEST_PSQL_MODE:-}" = "old_tAc_pattern" ]; then
      echo "ERROR: syntax error at or near \"-\"" >&2
      exit 1
    fi
    ;;
esac

on_error_stop=0
use_t=0
use_a=0
use_tac=0
sql=""
email=""
uid=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --set=ON_ERROR_STOP=1) on_error_stop=1; shift ;;
    -v)
      case "${2-}" in
        email=*) email="${2#email=}" ;;
        max_age=*) ;;
        uid=*) uid="${2#uid=}" ;;
        ON_ERROR_STOP=*) on_error_stop=1 ;;
      esac
      shift 2
      ;;
    -t) use_t=1; shift ;;
    -A) use_a=1; shift ;;
    -tAc)
      use_tac=1
      use_t=1
      use_a=1
      if [ -n "${2-}" ]; then
        sql="$2"
        shift 2
      else
        shift
      fi
      ;;
    -c) sql="${2-}"; shift 2 ;;
    -h|-p|-U|-d) shift 2 ;;
    *) shift ;;
  esac
done

case "${PARKIO_TEST_PSQL_MODE:-ok}" in
  old_tAc_pattern)
    if [ "$use_tac" = "1" ] && [ "$sql" = "-v" ]; then
      echo "ERROR: syntax error at or near \"-\"" >&2
      exit 1
    fi
    echo "FAIL: old_tAc_pattern should have failed" >&2
    exit 1
    ;;
  multi)
    echo "ERROR: more than one row returned by a subquery used as an expression" >&2
    exit 1
    ;;
  zero)
    exit 0
    ;;
  already_verified|erased)
    echo "ERROR: PRIV001_VERIFY_PRECONDITION_FAILED" >&2
    exit 1
    ;;
  inspect)
    [ "$on_error_stop" = "1" ] || exit 1
    [ "$use_t" = "1" ] && [ "$use_a" = "1" ] || exit 1
    [ "$use_tac" = "0" ] || exit 1
    printf '%s' "$sql" | grep -q "11111111-1111-4111-8111-111111111111" || exit 1
    printf '%s' "$sql" | grep -q ":'uid'" && exit 1
    printf '%s\n' '{"auth_state":"ERASED","email_verified":"false","auth_row_count":1,"erasure_request_count":1,"participant_ack_count":8,"tombstone_count":1,"hard_delete_residue_count":0,"deidentified_residue_count":1,"forbidden_user_id_hits":0,"media_object_count":0,"cross_service_sql":"NOT_EXECUTED_BY_CONTRACT"}'
    exit 0
    ;;
  *)
    [ "$on_error_stop" = "1" ] || { echo "ERROR: ON_ERROR_STOP not set" >&2; exit 1; }
    [ "$use_tac" = "0" ] || { echo "ERROR: deprecated -tAc clustering" >&2; exit 1; }
    [ "$use_t" = "1" ] && [ "$use_a" = "1" ] || { echo "ERROR: expected -t -A output flags" >&2; exit 1; }
    printf '%s' "$sql" | grep -q "PENDING_VERIFICATION" || exit 1
    printf '%s' "$sql" | grep -Fq "FOR UPDATE" || exit 1
    printf '%s' "$sql" | grep -q "erased_user_tombstones" || exit 1
    printf '%s' "$sql" | grep -q "erasure_requests" || exit 1
    printf '%s' "$sql" | grep -q ":'email'" && exit 1
    case "$sql" in
      *DROP\ *|*TRUNCATE\ *) exit 1 ;;
    esac
    if [ -n "$uid" ]; then
      printf '%s' "$sql" | grep -q "$uid" || exit 1
      exit 0
    fi
    if [ -n "$email" ]; then
      printf '%s' "$sql" | grep -q "email = '${email}'" || exit 1
      printf '%s\n' '11111111-1111-4111-8111-111111111111'
      exit 0
    fi
    if printf '%s' "$sql" | grep -q "email = 'priv001a-"; then
      printf '%s\n' '11111111-1111-4111-8111-111111111111'
      exit 0
    fi
    exit 1
    ;;
esac
FAKE_PSQL
chmod +x "$tmp/bin/psql"

REAL_PATH_SAVE="$PATH"
export PATH="$tmp/bin:$PATH"
export PARKIO_PG_MODE=managed
export PARKIO_PG_HOST=pg.example.postgres.database.azure.com
export PARKIO_PG_SSLMODE=verify-full
export PGPASSWORD='test-db-password-not-for-output'
export PARKIO_PRIV001_ENVIRONMENT=invite-production
export PARKIO_PRIV001_CONFIRM_SYNTHETIC_ONLY=yes
export PARKIO_DEPLOYMENT_PROFILE=invite-production
export PARKIO_TEST_PSQL_ARGV="$tmp/psql.argv"
export PARKIO_TEST_PSQL_MODE=old_tAc_pattern
assert_fail "f1b9d6f -tAc option-ordering fails closed" \
  parkio_priv001_auth_psql -tAc -v email='priv001a-20260830120000abcd@priv001a.parkio.invalid' -c "BEGIN; SELECT 1;"

export PARKIO_TEST_PSQL_MODE=ok
GOOD_EMAIL='priv001a-20260830120000abcd@priv001a.parkio.invalid'
VERIFIED="$(parkio_priv001_mark_verified "$GOOD_EMAIL" 900)"
[ "$VERIFIED" = '11111111-1111-4111-8111-111111111111' ]
tr '\0' ' ' < "$tmp/psql.argv" | grep -q -- '--set=ON_ERROR_STOP=1'
tr '\0' ' ' < "$tmp/psql.argv" | grep -q -- '-t'
tr '\0' ' ' < "$tmp/psql.argv" | grep -q -- '-A'
tr '\0' ' ' < "$tmp/psql.argv" | grep -qv -- '-tAc'
echo "PASS: mark_verified hotfix invocation (-t -A, --set=ON_ERROR_STOP=1, allowlist literal)"
pass=$((pass + 1))

assert_fail "gmail cannot be verified" \
  parkio_priv001_mark_verified 'attacker@gmail.com' 900
assert_fail "arbitrary domain rejected" \
  parkio_priv001_mark_verified 'priv001a-abc@evil.com' 900
assert_fail "sql-injection local-part rejected before psql" \
  parkio_priv001_mark_verified "priv001a-';DROP TABLE auth_users;--@priv001a.parkio.invalid" 900
assert_fail "email-as-psql-option rejected" \
  parkio_priv001_mark_verified 'priv001a-abc@priv001a.parkio.invalid -c DROP' 900

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
if grep -En '^\s*(INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER)\b' \
    scripts/acceptance/inspect-priv001-synthetic-residue.sh \
    | grep -vE '^[0-9]+:#'; then
  echo "FAIL: inspect script contains mutating SQL" >&2
  fail=$((fail + 1))
else
  echo "PASS: inspect script read-only (no mutating SQL)"
  pass=$((pass + 1))
fi

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

echo "=== PRIV-001A managed psql integration (ephemeral postgres) ==="
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  pg_cname="priv001-pg-it-$$"
  pg_port="55432"
  if docker run -d --rm --name "$pg_cname" \
      -e POSTGRES_PASSWORD=pgitsecret \
      -e POSTGRES_USER=parkio_auth \
      -e POSTGRES_DB=parkio_auth \
      -p "127.0.0.1:${pg_port}:5432" \
      postgres:16-alpine >/dev/null 2>&1; then
    trap 'docker rm -f "$pg_cname" >/dev/null 2>&1 || true' EXIT
  else
    pg_cname=""
  fi
  if [ -n "$pg_cname" ]; then
    ready=0
    for _ in $(seq 1 30); do
      if docker exec "$pg_cname" pg_isready -U parkio_auth -d parkio_auth >/dev/null 2>&1; then
        ready=1
        break
      fi
      sleep 1
    done
    if [ "$ready" = "1" ]; then
      docker exec -i "$pg_cname" psql -U parkio_auth -d parkio_auth -v ON_ERROR_STOP=1 <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE auth_users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email text NOT NULL,
  email_verified boolean NOT NULL DEFAULT false,
  status text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  email_verified_at timestamptz,
  email_verification_token_hash text,
  email_verification_expires_at timestamptz,
  email_verification_sent_at timestamptz,
  version int NOT NULL DEFAULT 1,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE erased_user_tombstones (auth_user_id uuid PRIMARY KEY);
CREATE TABLE erasure_requests (id uuid PRIMARY KEY, auth_user_id uuid NOT NULL);
INSERT INTO auth_users (email, email_verified, status)
VALUES ('priv001a-20260830120000abcd@priv001a.parkio.invalid', false, 'PENDING_VERIFICATION');
SQL
      saved_path="$REAL_PATH_SAVE"
      export PATH="$saved_path"
      export PARKIO_PG_MODE=managed
      export PARKIO_PG_HOST=127.0.0.1
      export PARKIO_PG_PORT="$pg_port"
      export PARKIO_PG_SSLMODE=prefer
      export PARKIO_PG_USER=parkio_auth
      export PARKIO_PG_DB=parkio_auth
      export PGPASSWORD=pgitsecret
      export PARKIO_PRIV001_ENVIRONMENT=invite-production
      export PARKIO_PRIV001_CONFIRM_SYNTHETIC_ONLY=yes
      export PARKIO_DEPLOYMENT_PROFILE=invite-production
      IT_EMAIL='priv001a-20260830120000abcd@priv001a.parkio.invalid'
      IT_UUID="$(parkio_priv001_mark_verified "$IT_EMAIL" 900)"
      IT_STATUS="$(PGPASSWORD=pgitsecret psql -h 127.0.0.1 -p "$pg_port" -U parkio_auth -d parkio_auth -t -A \
        -c "SELECT status FROM auth_users WHERE id='${IT_UUID}'::uuid;")"
      if [ "$IT_STATUS" = "ACTIVE" ]; then
        echo "PASS: managed postgres mark_verified end-to-end"
        pass=$((pass + 1))
      else
        echo "FAIL: managed postgres mark_verified status=$IT_STATUS" >&2
        fail=$((fail + 1))
      fi
      # Prove f1b9d6f psql-variable path fails on real postgres when variables are not expanded.
      if PGPASSWORD=pgitsecret psql -h 127.0.0.1 -p "$pg_port" -U parkio_auth -d parkio_auth \
          --set=ON_ERROR_STOP=1 -t -A \
          -v email="$IT_EMAIL" \
          -c "SELECT email = :'email' FROM auth_users LIMIT 1;" >/dev/null 2>&1; then
        echo "FAIL: psql :'email' variable unexpectedly expanded" >&2
        fail=$((fail + 1))
      else
        echo "PASS: psql :'email' variable binding fails on managed-style invocation"
        pass=$((pass + 1))
      fi
    else
      echo "SKIP: ephemeral postgres not ready"
    fi
    docker rm -f "$pg_cname" >/dev/null 2>&1 || true
    trap - EXIT
    export PATH="$REAL_PATH_SAVE"
  else
    echo "SKIP: could not start ephemeral postgres"
  fi
else
  echo "SKIP: docker unavailable for managed psql integration"
fi

echo "=== summary pass=${pass} fail=${fail} ==="
if [ "$fail" -ne 0 ]; then
  exit 1
fi
echo "PRIV-001A synthetic principal harness contract: PASS"
