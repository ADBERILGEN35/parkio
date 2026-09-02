#!/usr/bin/env bash
# Deterministic regression coverage for public MinIO console isolation.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/parkio-public-smoke-test.XXXXXX")"
MOCK_BIN="$TMP_ROOT/bin"
mkdir -p "$MOCK_BIN"

cleanup() { rm -rf -- "$TMP_ROOT"; }
trap cleanup EXIT HUP INT TERM

cat >"$MOCK_BIN/curl" <<'MOCK_CURL'
#!/usr/bin/env bash
set -euo pipefail

url=""
headers=false
for arg in "$@"; do
  case "$arg" in
    -D) headers=true ;;
    http://*|https://*) url="$arg" ;;
  esac
done

case "$url" in
  https://media.parkio.dev:9001/)
    case "${MOCK_CONSOLE_MODE:?}" in
      refused) printf '000'; exit 7 ;;
      timeout) printf '000'; exit 28 ;;
      http-403) printf '403'; exit 0 ;;
      http-200) printf '200'; exit 0 ;;
      *) exit 98 ;;
    esac
    ;;
  https://app.parkio.dev/)
    if [ "$headers" = true ]; then
      printf 'HTTP/2 200\r\nstrict-transport-security: max-age=86400\r\n\r\n'
    else
      printf '200'
    fi
    ;;
  https://api.parkio.dev/actuator/health) printf '200' ;;
  https://api.parkio.dev/actuator/info|https://api.parkio.dev/actuator/env|https://api.parkio.dev/actuator/configprops) printf '404' ;;
  https://api.parkio.dev/api/v1/auth/login) printf '401' ;;
  https://api.parkio.dev/api/v1/parking/spots) printf '401' ;;
  https://media.parkio.dev/) printf '403' ;;
  http://*) printf '308 https://%s' "${url#http://}" ;;
  *) exit 99 ;;
esac
MOCK_CURL

cat >"$MOCK_BIN/openssl" <<'MOCK_OPENSSL'
#!/usr/bin/env bash
set -euo pipefail

case " ${*} " in
  *" s_client "*) printf '%s\n' 'mock certificate' ;;
  *" x509 "*)
    case " ${*} " in
      *" -subject "*) printf '%s\n' 'subject=DNS:app.parkio.dev,DNS:api.parkio.dev,DNS:media.parkio.dev' ;;
    esac
    case " ${*} " in
      *" -issuer "*) printf '%s\n' "issuer=Let's Encrypt" ;;
    esac
    ;;
  *) exit 97 ;;
esac
MOCK_OPENSSL

chmod +x "$MOCK_BIN/curl" "$MOCK_BIN/openssl"

pass=0
fail=0
ok() { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

run_case() {
  local name="$1" mode="$2" expected_status="$3" expected_exit="$4"
  local output actual_exit

  set +e
  output="$(
    env \
      PATH="$MOCK_BIN:$PATH" \
      MOCK_CONSOLE_MODE="$mode" \
      PARKIO_PUBLIC_SMOKE_CONFIRM=1 \
      PARKIO_PUBLIC_SMOKE_BASE_URL=https://api.parkio.dev \
      PARKIO_PUBLIC_WEB_URL=https://app.parkio.dev \
      PARKIO_PUBLIC_MEDIA_URL=https://media.parkio.dev \
      bash "$ROOT/scripts/public-invite-smoke.sh" 2>&1
  )"
  actual_exit=$?
  set -e

  if [ "$actual_exit" -ne "$expected_exit" ]; then
    bad "$name exit (expected $expected_exit, got $actual_exit)"
    printf '%s\n' "$output" >&2
    return
  fi

  if [ "$expected_status" = "000" ]; then
    if grep -Fq "PASS: MinIO console :9001 not publicly exposed (000)" <<<"$output" \
        && grep -Fq "public_invite_smoke=PASS" <<<"$output"; then
      ok "$name normalizes to exactly 000 and passes as inaccessible"
    else
      bad "$name did not retain the exact 000/PASS contract"
    fi
  elif grep -Fq "FAIL: unexpected response from media :9001 ($expected_status)" <<<"$output" \
      && grep -Fq "public_invite_smoke=FAIL" <<<"$output"; then
    ok "$name retains HTTP $expected_status and fails as reachable"
  else
    bad "$name did not retain the HTTP $expected_status/FAIL contract"
  fi
}

run_case "connection refused" refused 000 0
run_case "connection timeout" timeout 000 0
run_case "real HTTP response" http-403 403 1
run_case "unexpected exposed console" http-200 200 1

echo "public invite smoke regression tests: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
