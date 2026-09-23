#!/usr/bin/env bash
# Regression tests for scripts/guard-web-synthetic-map-deploy.sh
# Does not require docker and does not change mock CI image acceptance.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GUARD="$ROOT/scripts/guard-web-synthetic-map-deploy.sh"
TMP="${TMPDIR:-/tmp}/web-map-guard-$$"
mkdir -p "$TMP"
TESTS=0
FAILED=0

run_case() {
  name="$1"
  expected="$2"
  shift 2
  TESTS=$((TESTS + 1))
  set +e
  "$GUARD" "$@" >"$TMP/out" 2>"$TMP/err"
  actual=$?
  set -e
  if [ "$actual" -eq "$expected" ]; then
    echo "PASS $name (exit $actual)"
  else
    echo "FAIL $name: expected $expected, got $actual"
    sed 's/^/     | /' "$TMP/out" "$TMP/err"
    FAILED=$((FAILED + 1))
  fi
}

PIN_OK="$TMP/pin-ok.yml"
PIN_BAD="$TMP/pin-bad.yml"
ENV_OK="$TMP/env-ok.env"
ENV_BAD="$TMP/env-bad.env"
ENV_FIXTURE="$TMP/env-fixture.env"

cat >"$PIN_OK" <<'EOF'
services:
  web:
    image: ghcr.io/adberilgen35/parkio/web@sha256:aacf9dc9ab8ef412dee01429da2b2bbc33099c4560a7904f181f9fe6db381f6e
EOF

cat >"$PIN_BAD" <<'EOF'
services:
  web:
    image: ghcr.io/adberilgen35/parkio/web@sha256:8d9bfca43d577afd62d20f7ffe3fcb2566fbf3bce9dbd758368562aec641487d
EOF

printf 'VITE_MAPTILER_KEY=authorized-non-synthetic-fixture\n' >"$ENV_OK"
printf 'VITE_MAPTILER_KEY=ci-web-build-security-synthetic\n' >"$ENV_BAD"
printf 'VITE_MAPTILER_KEY=fixture-public-map-key-never-use-in-production\n' >"$ENV_FIXTURE"

run_case "ok pin and env pass" 0 --env-file "$ENV_OK" --pin-file "$PIN_OK"
run_case "known-bad digest is blocked" 1 --env-file "$ENV_OK" --pin-file "$PIN_BAD"
run_case "synthetic env key is blocked" 1 --env-file "$ENV_BAD" --pin-file "$PIN_OK"
run_case "other fixture keys are not this guard" 0 --env-file "$ENV_FIXTURE" --pin-file "$PIN_OK"
run_case "repo web pin is not the synthetic digest" 0 --pin-file "$ROOT/docker/docker-compose.web-release-pin.yml"

rm -rf "$TMP"

if [ "$FAILED" -gt 0 ]; then
  echo "=== RESULT: FAIL — $FAILED of $TESTS assertions failed ==="
  exit 1
fi
echo "=== RESULT: PASS — all $TESTS assertions passed ==="
exit 0
