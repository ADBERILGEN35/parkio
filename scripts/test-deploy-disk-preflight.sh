#!/usr/bin/env bash
# Unit tests for scripts/lib/disk-space.sh (hosted-beta deploy disk gate).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/disk-space.sh
source "$ROOT/scripts/lib/disk-space.sh"

TESTS=0
FAILED=0

pass() { TESTS=$((TESTS + 1)); echo "PASS $1"; }
fail() { TESTS=$((TESTS + 1)); FAILED=$((FAILED + 1)); echo "FAIL $1: $2"; }

GIB=$PARKIO_GIB_BYTES
TWELVE=$((12 * GIB))
FIFTEEN=$((15 * GIB))

echo "=== deploy disk preflight unit tests ==="

# --- parse defaults / GiB / bytes ---
unset PARKIO_DEPLOY_MIN_FREE_BYTES PARKIO_DEPLOY_MIN_FREE_GIB
got="$(parkio_parse_min_free_bytes)"
if [ "$got" -eq "$TWELVE" ]; then pass "default threshold is 12 GiB"; else fail "default threshold" "got $got"; fi

PARKIO_DEPLOY_MIN_FREE_GIB=15
got="$(parkio_parse_min_free_bytes)"
if [ "$got" -eq "$FIFTEEN" ]; then pass "PARKIO_DEPLOY_MIN_FREE_GIB=15"; else fail "gib override" "got $got"; fi
unset PARKIO_DEPLOY_MIN_FREE_GIB

PARKIO_DEPLOY_MIN_FREE_BYTES=1000
got="$(parkio_parse_min_free_bytes)"
if [ "$got" -eq 1000 ]; then pass "PARKIO_DEPLOY_MIN_FREE_BYTES wins"; else fail "bytes override" "got $got"; fi
unset PARKIO_DEPLOY_MIN_FREE_BYTES

# bytes takes precedence over gib
PARKIO_DEPLOY_MIN_FREE_BYTES=2000
PARKIO_DEPLOY_MIN_FREE_GIB=15
got="$(parkio_parse_min_free_bytes)"
if [ "$got" -eq 2000 ]; then pass "bytes precedence over gib"; else fail "precedence" "got $got"; fi
unset PARKIO_DEPLOY_MIN_FREE_BYTES PARKIO_DEPLOY_MIN_FREE_GIB

# malformed
if PARKIO_DEPLOY_MIN_FREE_GIB=abc parkio_parse_min_free_bytes >/dev/null 2>&1; then
  fail "malformed gib" "expected non-zero"
else
  pass "malformed PARKIO_DEPLOY_MIN_FREE_GIB fails"
fi
if PARKIO_DEPLOY_MIN_FREE_BYTES=12G parkio_parse_min_free_bytes >/dev/null 2>&1; then
  fail "malformed bytes" "expected non-zero"
else
  pass "malformed PARKIO_DEPLOY_MIN_FREE_BYTES fails"
fi

# --- free-space gate ---
unset PARKIO_DEPLOY_ALLOW_LOW_DISK PARKIO_DEPLOY_MIN_FREE_BYTES PARKIO_DEPLOY_MIN_FREE_GIB

PARKIO_DISK_FREE_BYTES_FOR_TEST=$((TWELVE + 1))
if parkio_require_free_disk / >/dev/null 2>&1; then
  pass "free space above threshold 뿯↽ pass"
else
  fail "above threshold" "expected pass"
fi

PARKIO_DISK_FREE_BYTES_FOR_TEST=$((TWELVE - 1))
if parkio_require_free_disk / >/dev/null 2>&1; then
  fail "below threshold" "expected fail"
else
  pass "free space below threshold 뿯↽ fail"
fi

# boundary: exactly required passes
PARKIO_DISK_FREE_BYTES_FOR_TEST=$TWELVE
if parkio_require_free_disk / >/dev/null 2>&1; then
  pass "exactly 12 GiB free 뿯↽ pass"
else
  fail "exact boundary" "expected pass"
fi

# override
PARKIO_DISK_FREE_BYTES_FOR_TEST=$((TWELVE - 1))
PARKIO_DEPLOY_ALLOW_LOW_DISK=1
if parkio_require_free_disk / >/dev/null 2>&1; then
  pass "ALLOW_LOW_DISK override continues when low"
else
  fail "override" "expected pass with warn"
fi
unset PARKIO_DEPLOY_ALLOW_LOW_DISK PARKIO_DISK_FREE_BYTES_FOR_TEST

# GiB conversion spot-check: 1 GiB
PARKIO_DEPLOY_MIN_FREE_GIB=1
got="$(parkio_parse_min_free_bytes)"
if [ "$got" -eq "$GIB" ]; then pass "1 GiB == 1073741824 bytes"; else fail "1 GiB conversion" "got $got"; fi
unset PARKIO_DEPLOY_MIN_FREE_GIB

echo "=== summary: tests=$TESTS failed=$FAILED ==="
if [ "$FAILED" -ne 0 ]; then
  exit 1
fi
