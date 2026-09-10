#!/usr/bin/env bash
# Regression gate for PROD-DEPLOY-01A-R9H: a clean invite-production database
# must not inherit hosted-beta's seeded real-E2E login assumption.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

tmp="$(mktemp -d)"
cleanup() { rm -rf -- "$tmp"; }
trap cleanup EXIT HUP INT TERM
mkdir -p "$tmp/bin"

cat > "$tmp/bin/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail

output=""
headers=""
url=""
data=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) output="$2"; shift 2 ;;
    -D) headers="$2"; shift 2 ;;
    -d|--data|--data-raw|--data-binary) data="$2"; shift 2 ;;
    -w|--write-out|--connect-timeout|-H|--header|--max-redirs)
      shift 2
      ;;
    -s|-S|-sS|-f|-fsS) shift ;;
    http://*|https://*) url="$1"; shift ;;
    *) shift ;;
  esac
done

[ -n "$headers" ] && : > "$headers"
body='{}'
code=200
case "$url" in
  */actuator/info)
    body='{"deployment":{"environment":"invite-production","gitSha":"0000000000000000000000000000000000000000"}}'
    ;;
  */actuator/health)
    body='{"status":"UP"}'
    ;;
  */auth/.well-known/jwks.json)
    body='{"keys":[{"kid":"test","kty":"RSA","alg":"RS256"}]}'
    ;;
  */parking/spots/nearby*)
    code=401
    body='{"code":"UNAUTHORIZED"}'
    ;;
  */auth/login)
    code=401
    body='{"code":"INVALID_CREDENTIALS","message":"Invalid email or password."}'
    printf '%s' "$data" > "$PARKIO_TEST_LOGIN_PAYLOAD"
    ;;
  http://127.0.0.1:8083/*)
    code=000
    ;;
esac

[ -n "$output" ] && printf '%s' "$body" > "$output"
printf '%s' "$code"
FAKE_CURL
chmod +x "$tmp/bin/curl"

output="$tmp/smoke.out"
payload="$tmp/login.json"
PATH="$tmp/bin:$PATH" \
  TMPDIR="$tmp" \
  PARKIO_TEST_LOGIN_PAYLOAD="$payload" \
  PARKIO_DEPLOYMENT_PROFILE=invite-production \
  PARKIO_GATEWAY_URL=http://127.0.0.1:8080 \
  PARKIO_EXPECTED_GIT_SHA=0000000000000000000000000000000000000000 \
  PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 \
  bash scripts/smoke-hosted-beta.sh > "$output" 2>&1

grep -q 'PASS: clean-production login rejects unprovisioned credentials (401 INVALID_CREDENTIALS)' "$output"
grep -q '=== smoke summary: pass=7 fail=0 ===' "$output"
if grep -q 'user@real-e2e.parkio.local\|StrongParkio123' "$payload"; then
  echo 'FAIL: invite-production smoke inherited hosted-beta credentials' >&2
  exit 1
fi
grep -q 'invite-production-smoke@parkio.invalid' "$payload"

if PATH="$tmp/bin:$PATH" \
    TMPDIR="$tmp" \
    PARKIO_TEST_LOGIN_PAYLOAD="$payload" \
    PARKIO_DEPLOYMENT_PROFILE=invite-production \
    PARKIO_GATEWAY_URL=http://127.0.0.1:8080 \
    PARKIO_REAL_USER_EMAIL=only-identifier-present \
    bash scripts/smoke-hosted-beta.sh > "$tmp/partial.out" 2>&1; then
  echo 'FAIL: partial invite-production smoke credentials were accepted' >&2
  exit 1
fi
grep -q 'must provide both identifier and password' "$tmp/partial.out"

echo 'Invite-production clean-login smoke contract: PASS'
