#!/bin/sh
# Regression tests for scripts/preflight-hosted-beta.sh (R-005).
#
# Runs the preflight against the committed fixtures in scripts/preflight-fixtures/
# and asserts both the exit code and the presence of the expected failure
# messages. No docker needed (--skip-compose).
#
# Usage (from repo root):
#   ./scripts/test-preflight-hosted-beta.sh
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PREFLIGHT="$ROOT/scripts/preflight-hosted-beta.sh"
FIXTURES="$ROOT/scripts/preflight-fixtures"
OUT="${TMPDIR:-/tmp}/preflight-test-$$.out"
TESTS=0
FAILED=0

# run_case NAME FIXTURE EXPECTED_EXIT
run_case() {
  rc_name="$1"; rc_fixture="$2"; rc_expected="$3"
  TESTS=$((TESTS + 1))
  "$PREFLIGHT" --env-file "$FIXTURES/$rc_fixture" --skip-compose > "$OUT" 2>&1
  rc_actual=$?
  if [ "$rc_actual" -ne "$rc_expected" ]; then
    echo "FAIL $rc_name: expected exit $rc_expected, got $rc_actual"
    sed 's/^/     | /' "$OUT"
    FAILED=$((FAILED + 1))
    return 1
  fi
  echo "PASS $rc_name (exit $rc_actual)"
  return 0
}

# expect NAME PATTERN — the last run's output must contain PATTERN (fixed string).
expect() {
  TESTS=$((TESTS + 1))
  if grep -qF "$2" "$OUT"; then
    echo "PASS $1"
  else
    echo "FAIL $1: output does not contain: $2"
    sed 's/^/     | /' "$OUT"
    FAILED=$((FAILED + 1))
  fi
}

# expect_not NAME PATTERN — the last run's output must NOT contain PATTERN.
expect_not() {
  TESTS=$((TESTS + 1))
  if grep -qF "$2" "$OUT"; then
    echo "FAIL $1: output unexpectedly contains: $2"
    sed 's/^/     | /' "$OUT"
    FAILED=$((FAILED + 1))
  else
    echo "PASS $1"
  fi
}

echo "=== preflight-hosted-beta regression tests ==="

# ---- valid env passes -------------------------------------------------------
run_case "valid.env exits 0" valid.env 0
expect   "valid.env reports PASS" "PREFLIGHT: PASS"
expect_not "valid.env has no FAIL lines" "  FAIL "

# ---- missing secrets fail with clear messages -------------------------------
run_case "missing-secret.env exits 1" missing-secret.env 1
expect "missing JWT key reported"       "PARKIO_JWT_PRIVATE_KEY_PEM: JWT private key is empty"
expect "missing auth DB pw reported"    "POSTGRES_AUTH_PASSWORD: required secret is empty or unset"
expect "empty Redis password reported"  "REDIS_PASSWORD: required secret is empty or unset"
expect "empty Expo token reported"      "PARKIO_EXPO_ACCESS_TOKEN: missing or placeholder"
expect "blocked verdict printed"        "PREFLIGHT: FAIL"

# ---- CHANGE_ME placeholders fail -------------------------------------------
run_case "change-me.env exits 1" change-me.env 1
expect "whole-file CHANGE_ME sweep"     "CHANGE_ME placeholder(s) remain"
expect "gateway secret placeholder"     "PARKIO_GATEWAY_INTERNAL_SECRET: still a placeholder value"
expect "media DB placeholder"           "POSTGRES_MEDIA_PASSWORD: still a placeholder value"
expect "resend key placeholder"         "PARKIO_RESEND_API_KEY: missing or placeholder"

# ---- placeholder/localhost/local-dev/unsafe toggles fail ---------------------
run_case "placeholder.env exits 1" placeholder.env 1
expect "example.com domain rejected"    "PARKIO_DOMAIN: 'api.beta.example.com' is a localhost/placeholder host"
expect "localhost domain rejected"      "PARKIO_WEB_DOMAIN: 'localhost' is a localhost/placeholder host"
expect "127.0.0.1 domain rejected"      "PARKIO_MEDIA_DOMAIN: '127.0.0.1' is a localhost/placeholder host"
expect "http 10.0.2.2 URL rejected"     "VITE_API_BASE_URL: must be HTTPS"
expect "http CORS origin rejected"      "origin 'http://localhost:5173' is not https"
expect "localhost media endpoint"       "PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT"
expect "local-dev DB password leak"     "POSTGRES_AUTH_PASSWORD: is a committed LOCAL-DEV value"
# (the committed local-dev gateway secret ends in 'change-me', so the
# placeholder rule catches it first — either message blocks the deploy)
expect "local-dev gateway secret leak"  "PARKIO_GATEWAY_INTERNAL_SECRET: still a placeholder value"
expect "dev kafka cluster id leak"      "KAFKA_CLUSTER_ID: is the committed local-dev example cluster id"
expect "logging email provider"         "the logging provider prints emails to logs"
expect "noop push provider"             "the noop provider silently drops every push notification"
expect "openapi default-true trap"      "PARKIO_OPENAPI_ENABLED: must be explicitly 'false'"
expect "test hooks enabled"             "PARKIO_SMART_RETURN_TEST_HOOKS_ENABLED"
expect "token logging enabled"          "PARKIO_EMAIL_VERIFICATION_LOG_TOKEN"
expect "wrong environment label"        "PARKIO_ENVIRONMENT: 'local'"

# ---- slack webhook URL shape (no hooks.slack.com in committed fixtures) -------
BADSLACK="${TMPDIR:-/tmp}/preflight-bad-slack-$$.env"
grep -v '^PARKIO_ALERT_WEBHOOK_URL=' "$FIXTURES/valid.env" > "$BADSLACK"
echo 'PARKIO_ALERT_SLACK_WEBHOOK_URL=fixture-slack-webhook-url-not-a-secret' >> "$BADSLACK"
TESTS=$((TESTS + 1))
if "$PREFLIGHT" --env-file "$BADSLACK" --skip-compose > "$OUT" 2>&1; then
  echo "FAIL non-Slack PARKIO_ALERT_SLACK_WEBHOOK_URL must fail preflight"
  FAILED=$((FAILED + 1))
else
  echo "PASS non-Slack PARKIO_ALERT_SLACK_WEBHOOK_URL rejected"
fi
TESTS=$((TESTS + 1))
if grep -qF 'PARKIO_ALERT_SLACK_WEBHOOK_URL: not a Slack webhook URL' "$OUT"; then
  echo "PASS slack webhook shape failure message"
else
  echo "FAIL expected slack webhook shape failure message"
  sed 's/^/     | /' "$OUT"
  FAILED=$((FAILED + 1))
fi
rm -f "$BADSLACK"

# ---- alert-webhook acknowledgement path -------------------------------------
TESTS=$((TESTS + 1))
NOALERT="${TMPDIR:-/tmp}/preflight-noalert-$$.env"
grep -v '^PARKIO_ALERT_WEBHOOK_URL=' "$FIXTURES/valid.env" > "$NOALERT"
if "$PREFLIGHT" --env-file "$NOALERT" --skip-compose > "$OUT" 2>&1; then
  echo "FAIL empty alert webhook must fail without acknowledgement"
  FAILED=$((FAILED + 1))
else
  echo "PASS empty alert webhook fails without acknowledgement"
fi
TESTS=$((TESTS + 1))
if PARKIO_PREFLIGHT_ALLOW_NO_ALERT_WEBHOOK=1 "$PREFLIGHT" --env-file "$NOALERT" --skip-compose > "$OUT" 2>&1 \
   && grep -qF "WARN alerting" "$OUT"; then
  echo "PASS empty alert webhook passes with acknowledgement (as WARN)"
else
  echo "FAIL acknowledged empty alert webhook should pass with a WARN"
  sed 's/^/     | /' "$OUT"
  FAILED=$((FAILED + 1))
fi
rm -f "$NOALERT"

# ---- committed templates must never pass ------------------------------------
run_case "docker/.env.hosted-beta.example exits 1" ../../docker/.env.hosted-beta.example 1
expect "template blocked" "PREFLIGHT: FAIL"

rm -f "$OUT"

echo ""
if [ "$FAILED" -gt 0 ]; then
  echo "=== RESULT: FAIL — $FAILED of $TESTS assertions failed ==="
  exit 1
fi
echo "=== RESULT: PASS — all $TESTS assertions passed ==="
exit 0
