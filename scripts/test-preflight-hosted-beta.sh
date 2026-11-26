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
  case "$rc_fixture" in
    /*) rc_path="$rc_fixture" ;;
    *) rc_path="$FIXTURES/$rc_fixture" ;;
  esac
  TESTS=$((TESTS + 1))
  "$PREFLIGHT" --env-file "$rc_path" --skip-compose > "$OUT" 2>&1
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

# B-02: fixture harness supplies a PATH stub matching scripts/lib/cosign-pin.env
# so expected-pass cases remain focused on env validation. Negative cosign cases
# live in scripts/test-preflight-cosign-pin.sh.
# shellcheck disable=SC1091
. "$ROOT/scripts/lib/cosign-pin.env"
COSIGN_STUB_DIR="${TMPDIR:-/tmp}/preflight-cosign-stub-$$"
mkdir -p "$COSIGN_STUB_DIR"
cat >"$COSIGN_STUB_DIR/cosign" <<EOF
#!/bin/sh
echo "GitVersion:    $PARKIO_COSIGN_VERSION"
exit 0
EOF
chmod +x "$COSIGN_STUB_DIR/cosign"
PATH="$COSIGN_STUB_DIR:$PATH"
export PATH
trap 'rm -rf "$COSIGN_STUB_DIR"' EXIT


# ---- valid env passes -------------------------------------------------------
run_case "valid.env exits 0" valid.env 0
expect   "valid.env reports PASS" "PREFLIGHT: PASS"
expect_not "valid.env has no FAIL lines" "  FAIL "

# ---- Azure profile requires canonical host, API intake, and no tracing -------
AZURE_VALID="${TMPDIR:-/tmp}/preflight-azure-valid-$$.env"
sed \
  -e 's|PARKIO_DOMAIN=api.beta.parkio.dev|PARKIO_DOMAIN=api.parkio.dev|' \
  -e 's|PARKIO_WEB_DOMAIN=app.beta.parkio.dev|PARKIO_WEB_DOMAIN=app.parkio.dev|' \
  -e 's|PARKIO_MEDIA_DOMAIN=media.beta.parkio.dev|PARKIO_MEDIA_DOMAIN=media.parkio.dev|' \
  -e 's|PARKIO_CORS_ALLOWED_ORIGINS=https://app.beta.parkio.dev|PARKIO_CORS_ALLOWED_ORIGINS=https://app.parkio.dev|' \
  -e 's|PARKIO_WEB_BASE_URL=https://app.beta.parkio.dev|PARKIO_WEB_BASE_URL=https://app.parkio.dev|' \
  -e 's|VITE_API_BASE_URL=https://api.beta.parkio.dev/api/v1|VITE_API_BASE_URL=https://api.parkio.dev/api/v1|' \
  -e 's|PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT=https://media.beta.parkio.dev|PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT=https://media.parkio.dev|' \
  -e 's|PARKIO_TRACING_ENABLED=true|PARKIO_TRACING_ENABLED=false|' \
  "$FIXTURES/valid.env" > "$AZURE_VALID"
printf '\nPARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta\nVITE_WAITLIST_INTAKE_MODE=api\n' >> "$AZURE_VALID"
run_case "azure-hosted-beta valid env exits 0" "$AZURE_VALID" 0
expect "Azure profile reported" "deploymentProfile=azure-hosted-beta"
rm -f "$AZURE_VALID"

# ---- missing secrets fail with clear messages -------------------------------
run_case "missing-secret.env exits 1" missing-secret.env 1
expect "missing web base URL reported"  "PARKIO_WEB_BASE_URL: required URL is empty or unset"
expect "missing JWT key reported"       "PARKIO_JWT_PRIVATE_KEY_PEM: JWT private key is empty"
expect "missing auth DB pw reported"    "POSTGRES_AUTH_PASSWORD: required secret is empty or unset"
expect "empty Redis password reported"  "REDIS_PASSWORD: required secret is empty or unset"
expect "empty Expo token reported"      "PARKIO_EXPO_ACCESS_TOKEN: missing or placeholder"
expect "blocked verdict printed"        "PREFLIGHT: FAIL"

# ---- login-throttle HMAC key rules (CL-F15 v3) ------------------------------
THROTTLE_SAME="${TMPDIR:-/tmp}/preflight-test-$$-throttle-same.env"
python3 - "$FIXTURES/valid.env" "$THROTTLE_SAME" <<'PYS'
import re, sys
from pathlib import Path
src = Path(sys.argv[1]).read_text()
waitlist = re.search(r"^PARKIO_WAITLIST_HASH_SECRET=(.*)$", src, re.M).group(1)
Path(sys.argv[2]).write_text(re.sub(r"^PARKIO_LOGIN_THROTTLE_HMAC_KEY=.*$", "PARKIO_LOGIN_THROTTLE_HMAC_KEY=" + waitlist, src, flags=re.M))
PYS
run_case "throttle key equal to the waitlist secret exits 1" "$THROTTLE_SAME" 1
expect "throttle key distinctness reported" "PARKIO_LOGIN_THROTTLE_HMAC_KEY: must be distinct from PARKIO_WAITLIST_HASH_SECRET"
THROTTLE_PREV="${TMPDIR:-/tmp}/preflight-test-$$-throttle-previous.env"
python3 - "$FIXTURES/valid.env" "$THROTTLE_PREV" <<'PYS'
import re, sys
from pathlib import Path
src = Path(sys.argv[1]).read_text()
current = re.search(r"^PARKIO_LOGIN_THROTTLE_HMAC_KEY=(.*)$", src, re.M).group(1)
Path(sys.argv[2]).write_text(re.sub(r"^PARKIO_LOGIN_THROTTLE_HMAC_KEY_PREVIOUS=.*$", "PARKIO_LOGIN_THROTTLE_HMAC_KEY_PREVIOUS=" + current, src, flags=re.M))
PYS
run_case "previous throttle key equal to the current one exits 1" "$THROTTLE_PREV" 1
expect "previous-equals-current reported" "PARKIO_LOGIN_THROTTLE_HMAC_KEY_PREVIOUS: equals PARKIO_LOGIN_THROTTLE_HMAC_KEY"
THROTTLE_MISSING="${TMPDIR:-/tmp}/preflight-test-$$-throttle-missing.env"
sed 's/^PARKIO_LOGIN_THROTTLE_HMAC_KEY=.*$/PARKIO_LOGIN_THROTTLE_HMAC_KEY=/' "$FIXTURES/valid.env" > "$THROTTLE_MISSING"
run_case "missing throttle key exits 1" "$THROTTLE_MISSING" 1
expect "missing throttle key reported" "PARKIO_LOGIN_THROTTLE_HMAC_KEY: required secret is empty or unset"
rm -f "$THROTTLE_SAME" "$THROTTLE_PREV" "$THROTTLE_MISSING"

# ---- waitlist token HMAC / email previous rules (R-06 / #415) ---------------
WAITLIST_PREV_NO_TOKEN="${TMPDIR:-/tmp}/preflight-test-$$-waitlist-prev-no-token.env"
python3 - "$FIXTURES/valid.env" "$WAITLIST_PREV_NO_TOKEN" <<'PYS'
import re, sys
from pathlib import Path
src = Path(sys.argv[1]).read_text()
current = re.search(r"^PARKIO_WAITLIST_HASH_SECRET=(.*)$", src, re.M).group(1)
prev = "previous-waitlist-hash-secret-fixture-32chars!!"
src = re.sub(r"^PARKIO_WAITLIST_HASH_SECRET_PREVIOUS=.*$", "PARKIO_WAITLIST_HASH_SECRET_PREVIOUS=" + prev, src, flags=re.M)
src = re.sub(r"^PARKIO_WAITLIST_HASH_TOKEN_SECRET=.*$", "PARKIO_WAITLIST_HASH_TOKEN_SECRET=", src, flags=re.M)
Path(sys.argv[2]).write_text(src)
PYS
run_case "email previous without token secret exits 1" "$WAITLIST_PREV_NO_TOKEN" 1
expect "token secret required during email rotation" "PARKIO_WAITLIST_HASH_TOKEN_SECRET: required secret is empty or unset"
WAITLIST_TOKEN_EQ_CURRENT="${TMPDIR:-/tmp}/preflight-test-$$-waitlist-token-eq.env"
python3 - "$FIXTURES/valid.env" "$WAITLIST_TOKEN_EQ_CURRENT" <<'PYS'
import re, sys
from pathlib import Path
src = Path(sys.argv[1]).read_text()
current = re.search(r"^PARKIO_WAITLIST_HASH_SECRET=(.*)$", src, re.M).group(1)
prev = "previous-waitlist-hash-secret-fixture-32chars!!"
src = re.sub(r"^PARKIO_WAITLIST_HASH_SECRET_PREVIOUS=.*$", "PARKIO_WAITLIST_HASH_SECRET_PREVIOUS=" + prev, src, flags=re.M)
src = re.sub(r"^PARKIO_WAITLIST_HASH_TOKEN_SECRET=.*$", "PARKIO_WAITLIST_HASH_TOKEN_SECRET=" + current, src, flags=re.M)
Path(sys.argv[2]).write_text(src)
PYS
run_case "token secret equal to current email secret during rotation exits 1" "$WAITLIST_TOKEN_EQ_CURRENT" 1
expect "token secret distinctness during rotation" "PARKIO_WAITLIST_HASH_TOKEN_SECRET: must differ from PARKIO_WAITLIST_HASH_SECRET while HASH_SECRET_PREVIOUS is set"
WAITLIST_TOKEN_PREV_EQ="${TMPDIR:-/tmp}/preflight-test-$$-waitlist-token-prev-eq.env"
python3 - "$FIXTURES/valid.env" "$WAITLIST_TOKEN_PREV_EQ" <<'PYS'
import re, sys
from pathlib import Path
src = Path(sys.argv[1]).read_text()
token = "dedicated-waitlist-token-secret-fixture-32c!"
src = re.sub(r"^PARKIO_WAITLIST_HASH_TOKEN_SECRET=.*$", "PARKIO_WAITLIST_HASH_TOKEN_SECRET=" + token, src, flags=re.M)
src = re.sub(r"^PARKIO_WAITLIST_HASH_TOKEN_SECRET_PREVIOUS=.*$", "PARKIO_WAITLIST_HASH_TOKEN_SECRET_PREVIOUS=" + token, src, flags=re.M)
Path(sys.argv[2]).write_text(src)
PYS
run_case "token previous equal to current token secret exits 1" "$WAITLIST_TOKEN_PREV_EQ" 1
expect "token previous-equals-current reported" "PARKIO_WAITLIST_HASH_TOKEN_SECRET_PREVIOUS: equals PARKIO_WAITLIST_HASH_TOKEN_SECRET"
rm -f "$WAITLIST_PREV_NO_TOKEN" "$WAITLIST_TOKEN_EQ_CURRENT" "$WAITLIST_TOKEN_PREV_EQ"

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
expect "localhost email-link base URL"  "PARKIO_WEB_BASE_URL: must be HTTPS"
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

# ---- heartbeat URL shape (U06 dead-man's switch) ----------------------------
HB="${TMPDIR:-/tmp}/preflight-heartbeat-$$.env"
cp "$FIXTURES/valid.env" "$HB"
echo 'PARKIO_ALERT_HEARTBEAT_URL=http://heartbeat.fixture.parkio.dev/ping/not-a-secret' >> "$HB"
run_case "http heartbeat URL exits 1" "$HB" 1
expect "http heartbeat URL rejected" "PARKIO_ALERT_HEARTBEAT_URL: must be HTTPS"
cp "$FIXTURES/valid.env" "$HB"
echo 'PARKIO_ALERT_HEARTBEAT_URL=https://heartbeat.PLACEHOLDER.parkio.dev/ping' >> "$HB"
run_case "placeholder heartbeat URL exits 1" "$HB" 1
expect "placeholder heartbeat URL rejected" "PARKIO_ALERT_HEARTBEAT_URL: is a placeholder"
cp "$FIXTURES/valid.env" "$HB"
echo 'PARKIO_ALERT_HEARTBEAT_URL=https://heartbeat.fixture.parkio.dev/ping/not-a-secret' >> "$HB"
run_case "https heartbeat URL exits 0" "$HB" 0
expect_not "https heartbeat URL has no heartbeat WARN" "WARN heartbeat"
run_case "valid.env without a heartbeat URL still exits 0" valid.env 0
expect "missing heartbeat URL is a WARN, not a FAIL" "WARN heartbeat: PARKIO_ALERT_HEARTBEAT_URL is empty"
rm -f "$HB"

# ---- Expo send bounds (#595): optional, bounded, connect shorter than read ---
EXPO_BOUNDS="${TMPDIR:-/tmp}/preflight-expo-bounds-$$.env"
# expo_bounds CONNECT READ — valid.env plus the given values ('-' leaves the key out).
expo_bounds() {
  cp "$FIXTURES/valid.env" "$EXPO_BOUNDS"
  [ "$1" = "-" ] || echo "PARKIO_EXPO_PUSH_CONNECT_TIMEOUT=$1" >> "$EXPO_BOUNDS"
  [ "$2" = "-" ] || echo "PARKIO_EXPO_PUSH_READ_TIMEOUT=$2" >> "$EXPO_BOUNDS"
}
expo_bounds 2s 5s
run_case "the documented Expo defaults exit 0" "$EXPO_BOUNDS" 0
expect_not "the defaults raise no Expo bound failure" "FAIL PARKIO_EXPO_PUSH_"
expo_bounds 300ms 1500ms
run_case "millisecond Expo bounds exit 0" "$EXPO_BOUNDS" 0
expo_bounds - 0s
run_case "a zero read timeout exits 1" "$EXPO_BOUNDS" 1
expect "zero read timeout rejected" "PARKIO_EXPO_PUSH_READ_TIMEOUT: '0s' is not a bounded read timeout"
expo_bounds - 61s
run_case "a read timeout above 60s exits 1" "$EXPO_BOUNDS" 1
expect "read timeout above the limit rejected" "PARKIO_EXPO_PUSH_READ_TIMEOUT: '61s' is not a bounded read timeout"
expo_bounds 31000ms -
run_case "a connect timeout above 30s exits 1" "$EXPO_BOUNDS" 1
expect "connect timeout above the limit rejected" "PARKIO_EXPO_PUSH_CONNECT_TIMEOUT: '31000ms' is not a bounded connect timeout"
expo_bounds PT2S -
run_case "an ISO-8601 connect timeout exits 1" "$EXPO_BOUNDS" 1
expect "unsupported duration form rejected" "PARKIO_EXPO_PUSH_CONNECT_TIMEOUT: 'PT2S' is not a bounded connect timeout"
expo_bounds 5s -
run_case "a connect timeout equal to the default read timeout exits 1" "$EXPO_BOUNDS" 1
expect "connect not shorter than read rejected" "PARKIO_EXPO_PUSH_CONNECT_TIMEOUT: 5000ms is not shorter than PARKIO_EXPO_PUSH_READ_TIMEOUT (5000ms)"
expo_bounds - 1s
run_case "a read timeout below the default connect timeout exits 1" "$EXPO_BOUNDS" 1
expect "default connect against a short read rejected" "PARKIO_EXPO_PUSH_CONNECT_TIMEOUT: 2000ms is not shorter than PARKIO_EXPO_PUSH_READ_TIMEOUT (1000ms)"
rm -f "$EXPO_BOUNDS"

# ---- municipal feed timings (#615, #637, #654) ------------------------------
# parking-service refuses to start, even with the feed disabled, unless each HTTP feed's connect and
# read timeouts are at least 1ms and connect + read is less than max-response-time. The preflight reads
# the values as docker compose does and as Spring Boot binds them, with the overlay's defaults.

# The duration parser, extracted from the preflight, must give Spring Boot 4.1's result for each value.
# The expected column was generated with Spring's Binder: nanoseconds, INVALID (Spring rejects it),
# NEGATIVE, or TOO_LONG (above 2^63-1 ns, where BoundedFeedResponses' deadline overflows).
DURATIONS="${TMPDIR:-/tmp}/preflight-durations-$$.sh"
sed -n '/^# >>> spring-duration/,/^# <<< spring-duration/p' "$PREFLIGHT" > "$DURATIONS"
while IFS='|' read -r sd_value sd_expected; do
  TESTS=$((TESTS + 1))
  sd_out=$(sh -c '. "$1"; sd_ns=$(spring_duration_ns "$2"); printf "%s %s" "$?" "$sd_ns"' _ "$DURATIONS" "$sd_value" </dev/null 2>&1)
  case $sd_out in
    "0 "?*) sd_actual=${sd_out#0 } ;;
    "1 ") sd_actual=INVALID ;;
    "2 ") sd_actual=TOO_LONG ;;
    "3 ") sd_actual=NEGATIVE ;;
    *) sd_actual="unexpected output '$sd_out'" ;;
  esac
  if [ "$sd_actual" = "$sd_expected" ]; then
    echo "PASS Spring duration '$sd_value' is $sd_expected"
  else
    echo "FAIL Spring duration '$sd_value': expected $sd_expected, got $sd_actual"
    FAILED=$((FAILED + 1))
  fi
done <<'DURATIONS'
20s|20000000000
20S|20000000000
20000|20000000000
+20s|20000000000
0007s|7000000000
1500ms|1500000000
1500MS|1500000000
1ms|1000000
999us|999000
1500us|1500000
999999ns|999999
1m|60000000000
1h|3600000000000
1d|86400000000000
PT20S|20000000000
pt20s|20000000000
+PT20S|20000000000
PT0.5S|500000000
PT0,5S|500000000
PT1.S|1000000000
PT1.123456789S|1123456789
PT1M-30S|30000000000
P1DT-23H-59M-30S|30000000000
-PT-20S|20000000000
P5Dt|432000000000000
0s|0
-0s|0
-1s|NEGATIVE
PT-0.5S|NEGATIVE
-PT0.5S|NEGATIVE
9223372036854775807ns|9223372036854775807
9223372036s|9223372036000000000
9223372037s|TOO_LONG
PT9223372036.854775807S|9223372036854775807
PT9223372036.854775808S|TOO_LONG
106751d|9223286400000000000
106752d|TOO_LONG
153722867280912930m|TOO_LONG
153722867280912931m|INVALID
9223372036854775808ns|INVALID
P106751991167300D|TOO_LONG
P106751991167301D|INVALID
PT-9223372036854775808S|NEGATIVE
-PT-9223372036854775808S|INVALID
PT-9223372036854775808.5S|INVALID
20 s|INVALID
 20s|INVALID
20s |INVALID
20sec|INVALID
20x|INVALID
1.5s|INVALID
--1s|INVALID
0x10s|INVALID
PT|INVALID
P|INVALID
P5DT|INVALID
PT1.1234567891S|INVALID
PT1H30|INVALID
T20S|INVALID
PT1M2H|INVALID
PT.5S|INVALID
P1W|INVALID
PT1.5.5S|INVALID
２0s|INVALID
DURATIONS
rm -f "$DURATIONS"

MUNI="${TMPDIR:-/tmp}/preflight-municipal-$$.env"
# municipal LINE... — valid.env plus the given env-file lines.
municipal() {
  cp "$FIXTURES/valid.env" "$MUNI"
  for municipal_line in "$@"; do
    printf '%s\n' "$municipal_line" >> "$MUNI"
  done
}
municipal
run_case "the overlay's municipal timing defaults exit 0" "$MUNI" 0
expect_not "the defaults raise no municipal timing failure" "FAIL PARKIO_MUNICIPAL_"
municipal "PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT=30s"
run_case "the former Konya read timeout exits 1" "$MUNI" 1
expect "connect + read reaching max-response-time rejected" "FAIL PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT: KONYA connect-timeout (5s, the default) + read-timeout (30s) must be less than max-response-time (30s, the default)"
expect "the fix names both variables" "raise PARKIO_MUNICIPAL_KONYA_MAX_RESPONSE_TIME together with PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT"
municipal "PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT=40s" "PARKIO_MUNICIPAL_KONYA_MAX_RESPONSE_TIME=PT1M"
run_case "raising max-response-time with the read timeout exits 0" "$MUNI" 0
municipal "PARKIO_MUNICIPAL_ANPARK_MAX_RESPONSE_TIME=PT12S"
run_case "max-response-time equal to the default connect + read exits 1" "$MUNI" 1
expect "an ISO-8601 bound is compared with the defaults" "FAIL PARKIO_MUNICIPAL_ANPARK_READ_TIMEOUT: ANPARK connect-timeout (2s, the default) + read-timeout (10s, the default) must be less than max-response-time (PT12S)"
municipal "PARKIO_MUNICIPAL_IZUM_CONNECT_TIMEOUT=0s"
run_case "a zero İZUM connect timeout exits 1" "$MUNI" 1
expect "zero connect timeout rejected" "FAIL PARKIO_MUNICIPAL_IZUM_CONNECT_TIMEOUT: IZUM connect-timeout (0s) must be at least 1ms"
municipal "PARKIO_MUNICIPAL_ISPARK_READ_TIMEOUT=999us"
run_case "a read timeout under 1ms exits 1" "$MUNI" 1
expect "sub-millisecond read timeout rejected" "FAIL PARKIO_MUNICIPAL_ISPARK_READ_TIMEOUT: ISPARK read-timeout (999us) must be at least 1ms"
municipal "PARKIO_MUNICIPAL_ISPARK_CONNECT_TIMEOUT=1ms" "PARKIO_MUNICIPAL_ISPARK_READ_TIMEOUT=1000us"
run_case "1ms timeouts exit 0" "$MUNI" 0
municipal 'PARKIO_MUNICIPAL_KAYSERI_READ_TIMEOUT="20 s"'
run_case "a value Spring cannot bind exits 1" "$MUNI" 1
expect "unbindable value rejected" "FAIL PARKIO_MUNICIPAL_KAYSERI_READ_TIMEOUT: KAYSERI read-timeout ('20 s') is not a duration parking-service accepts"
municipal "PARKIO_MUNICIPAL_KAYSERI_MAX_RESPONSE_TIME=106752d"
run_case "max-response-time above 2^63-1 ns exits 1" "$MUNI" 1
expect "overflowing response deadline rejected" "FAIL PARKIO_MUNICIPAL_KAYSERI_MAX_RESPONSE_TIME: KAYSERI max-response-time (106752d) is longer than 2^63-1 nanoseconds"
# Values as docker compose reads the env file.
municipal "PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT=20s # the #637 default" "PARKIO_MUNICIPAL_KONYA_CONNECT_TIMEOUT=5s   "
run_case "an inline comment and trailing blanks are not part of the value" "$MUNI" 0
municipal "export PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT=30s"
run_case "an exported former read timeout exits 1" "$MUNI" 1
expect "export prefix read" "FAIL PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT: KONYA connect-timeout (5s, the default) + read-timeout (30s) must be"
municipal "PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT: 30s"
run_case "a YAML-style former read timeout exits 1" "$MUNI" 1
municipal "PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT=30s" "PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT="
run_case "an empty last definition takes the default and exits 0" "$MUNI" 0
# shellcheck disable=SC2016 # the env-file line must keep its $ for the preflight to refuse it
municipal 'PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT=$KONYA_READ'
run_case "a value with \$ exits 1" "$MUNI" 1
expect "expansion not resolved" "FAIL PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT: '\$KONYA_READ' uses \$"
municipal 'PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT="20s'
run_case "an unclosed quote exits 1" "$MUNI" 1
expect "unclosed quote rejected" "FAIL PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT: the quote that opens the value is not closed"
# The defaults are the overlay's: for each feed, read its overlay defaults and set one key where the
# rule flips. If the preflight assumed another default, one run of each pair would end the other way.
OVERLAY="$ROOT/docker/docker-compose.azure-hosted-beta.yml"
# overlay_ms KEY -> the overlay default of KEY in ms; only whole seconds are expected there.
overlay_ms() {
  om_s=$(sed -n "s/^ *$1: \${$1:-\([0-9][0-9]*\)s}\$/\1/p" "$OVERLAY")
  [ -n "$om_s" ] && echo $((om_s * 1000))
}
for feed in IZUM ISPARK ANPARK KONYA KAYSERI; do
  key=PARKIO_MUNICIPAL_$feed
  connect=$(overlay_ms "${key}_CONNECT_TIMEOUT")
  read_timeout=$(overlay_ms "${key}_READ_TIMEOUT")
  max=$(overlay_ms "${key}_MAX_RESPONSE_TIME")
  TESTS=$((TESTS + 1))
  if [ -z "$connect" ] || [ -z "$read_timeout" ] || [ -z "$max" ]; then
    echo "FAIL the overlay maps ${key}_{CONNECT_TIMEOUT,READ_TIMEOUT,MAX_RESPONSE_TIME} with whole-second defaults"
    FAILED=$((FAILED + 1))
    continue
  fi
  echo "PASS $feed overlay defaults: connect ${connect}ms, read ${read_timeout}ms, max ${max}ms"
  municipal "${key}_READ_TIMEOUT=$((max - connect))ms"
  run_case "$feed read timeout that reaches the default max with the default connect exits 1" "$MUNI" 1
  municipal "${key}_READ_TIMEOUT=$((max - connect - 1))ms"
  run_case "$feed read timeout 1ms inside the defaults exits 0" "$MUNI" 0
  municipal "${key}_CONNECT_TIMEOUT=$((max - read_timeout))ms"
  run_case "$feed connect timeout that reaches the default max with the default read exits 1" "$MUNI" 1
  municipal "${key}_CONNECT_TIMEOUT=$((max - read_timeout - 1))ms"
  run_case "$feed connect timeout 1ms inside the defaults exits 0" "$MUNI" 0
  municipal "${key}_MAX_RESPONSE_TIME=$((connect + read_timeout))ms"
  run_case "$feed max-response-time equal to the default connect + read exits 1" "$MUNI" 1
  municipal "${key}_MAX_RESPONSE_TIME=$((connect + read_timeout + 1))ms"
  run_case "$feed max-response-time 1ms above the default connect + read exits 0" "$MUNI" 0
done
# invite-production's Compose files do not pass these keys to parking-service, which then runs on its
# application.yml values, so its env file is not checked for them.
municipal "PARKIO_DEPLOYMENT_PROFILE=invite-production" "PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT=30s"
"$PREFLIGHT" --env-file "$MUNI" --skip-compose > "$OUT" 2>&1
expect "the invite-production checks ran" "deploymentProfile=invite-production"
expect_not "invite-production does not check municipal timings" "FAIL PARKIO_MUNICIPAL_"
rm -f "$MUNI"

# ---- known CI synthetic MapTiler key must fail hosted deploy preflight ------
SYNTH_MAP="${TMPDIR:-/tmp}/preflight-synthetic-map-$$.env"
sed -e 's/^VITE_APP_ENV=hosted-beta$/VITE_APP_ENV=hosted-beta\nVITE_MAPTILER_KEY=ci-web-build-security-synthetic/' \
  "$FIXTURES/valid.env" > "$SYNTH_MAP"
run_case "synthetic MapTiler key exits 1" "$SYNTH_MAP" 1
expect "synthetic MapTiler key blocked" "VITE_MAPTILER_KEY"
rm -f "$SYNTH_MAP"

# ---- committed templates must never pass ------------------------------------
run_case "docker/.env.hosted-beta.example exits 1" ../../docker/.env.hosted-beta.example 1
expect "template blocked" "PREFLIGHT: FAIL"
expect_not "the template's Expo bounds are valid" "FAIL PARKIO_EXPO_PUSH_"
expect_not "the template's municipal timings are valid" "FAIL PARKIO_MUNICIPAL_"
run_case "docker/.env.azure-hosted-beta.example exits 1" ../../docker/.env.azure-hosted-beta.example 1
expect "Azure template blocked" "PREFLIGHT: FAIL"
expect_not "the Azure template's municipal timings are valid" "FAIL PARKIO_MUNICIPAL_"

rm -f "$OUT"

echo ""
if [ "$FAILED" -gt 0 ]; then
  echo "=== RESULT: FAIL — $FAILED of $TESTS assertions failed ==="
  exit 1
fi
echo "=== RESULT: PASS — all $TESTS assertions passed ==="
exit 0
