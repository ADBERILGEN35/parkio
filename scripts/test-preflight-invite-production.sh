#!/usr/bin/env bash
# Regression tests for the web-only invite-production push and secret contract.

set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PREFLIGHT="$ROOT/scripts/preflight-invite-production.sh"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/parkio-invite-preflight.XXXXXX")"
VALID_ENV="$TMP_ROOT/valid.env"
OUT="$TMP_ROOT/output.log"
TESTS=0
FAILED=0

cleanup() {
  rm -rf -- "$TMP_ROOT"
}
trap cleanup EXIT HUP INT TERM

python3 - "$ROOT/docker/.env.invite-production.example" "$VALID_ENV" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1])
target = Path(sys.argv[2])
exact = {
    "PARKIO_PG_HOST": "pg-invite-fixture.postgres.database.azure.com",
    "PARKIO_JWT_PRIVATE_KEY_PEM": (
        '"-----BEGIN ' + 'PRIVATE KEY-----\\nfixture-only\\n-----END ' + 'PRIVATE KEY-----"'
    ),
    "PARKIO_ACME_EMAIL": "ops@parkio.dev",
    "VITE_MAPTILER_KEY": "fixture-public-map-key",
    "PARKIO_RESEND_API_KEY": "re_fixture_validation_only",
    "PARKIO_PUSH_DELIVERY_ENABLED": "false",
    "PARKIO_PUSH_DELIVERY_PROVIDER": "noop",
    "PARKIO_EXPO_ACCESS_TOKEN": "",
    "PARKIO_ALERT_SLACK_WEBHOOK_URL": "",
    "PARKIO_ALERT_WEBHOOK_URL": "https://alerts.parkio.dev/test-receiver",
    "KAFKA_CLUSTER_ID": "Q0lJbnZpdGVQcm9kMDFBQQ",
}
lines = []
for line in source.read_text().splitlines():
    if not line or line.lstrip().startswith("#") or "=" not in line:
        lines.append(line)
        continue
    key, value = line.split("=", 1)
    if key in exact:
        lines.append(f"{key}={exact[key]}")
    elif "REPLACE_ME" in value:
        lines.append(f'{key}="fixture-{key.lower()}-0123456789abcdef"')
    else:
        lines.append(line)
target.write_text("\n".join(lines) + "\n")
PY

run_case() {
  local name="$1"
  local env_file="$2"
  local expected="$3"
  TESTS=$((TESTS + 1))
  "$PREFLIGHT" --env-file "$env_file" --skip-compose > "$OUT" 2>&1
  local actual=$?
  if [ "$actual" -ne "$expected" ]; then
    echo "FAIL $name: expected exit $expected, got $actual"
    sed 's/^/     | /' "$OUT"
    FAILED=$((FAILED + 1))
  else
    echo "PASS $name (exit $actual)"
  fi
}

expect_output() {
  local name="$1"
  local pattern="$2"
  TESTS=$((TESTS + 1))
  if grep -qF "$pattern" "$OUT"; then
    echo "PASS $name"
  else
    echo "FAIL $name: missing output: $pattern"
    sed 's/^/     | /' "$OUT"
    FAILED=$((FAILED + 1))
  fi
}

run_case "disabled/noop invite passes without Expo token" "$VALID_ENV" 0
expect_output "valid invite reports pass" "Invite-production preflight passed."

ENABLED_ENV="$TMP_ROOT/enabled.env"
sed 's/^PARKIO_PUSH_DELIVERY_ENABLED=false$/PARKIO_PUSH_DELIVERY_ENABLED=true/' "$VALID_ENV" > "$ENABLED_ENV"
run_case "enabled push is rejected" "$ENABLED_ENV" 1
expect_output "enabled failure is explicit" "FAIL PARKIO_PUSH_DELIVERY_ENABLED: expected 'false' but found 'true'"

EXPO_PROVIDER_ENV="$TMP_ROOT/expo-provider.env"
sed 's/^PARKIO_PUSH_DELIVERY_PROVIDER=noop$/PARKIO_PUSH_DELIVERY_PROVIDER=expo/' "$VALID_ENV" > "$EXPO_PROVIDER_ENV"
run_case "Expo provider is rejected" "$EXPO_PROVIDER_ENV" 1
expect_output "provider failure is explicit" "FAIL PARKIO_PUSH_DELIVERY_PROVIDER: expected 'noop' but found 'expo'"

EXPO_TOKEN_ENV="$TMP_ROOT/expo-token.env"
sed 's/^PARKIO_EXPO_ACCESS_TOKEN=$/PARKIO_EXPO_ACCESS_TOKEN=fixture-token-must-not-be-used/' "$VALID_ENV" > "$EXPO_TOKEN_ENV"
run_case "Expo token is rejected from web-only boundary" "$EXPO_TOKEN_ENV" 1
expect_output "token failure is explicit" "FAIL PARKIO_EXPO_ACCESS_TOKEN: expected empty value for the web-only invite boundary"

echo
if [ "$FAILED" -ne 0 ]; then
  echo "=== RESULT: FAIL — $FAILED of $TESTS assertions failed ==="
  exit 1
fi
echo "=== RESULT: PASS — all $TESTS assertions passed ==="
