#!/usr/bin/env bash

set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/parkio-runner-guards.XXXXXX")"
TESTS=0
FAILED=0

cleanup() { rm -rf -- "$TMP_ROOT"; }
trap cleanup EXIT HUP INT TERM

expect_status() {
  local name="$1" expected="$2"
  shift 2
  TESTS=$((TESTS + 1))
  "$@" > "$TMP_ROOT/out" 2>&1
  local actual=$?
  if [ "$actual" -eq "$expected" ]; then
    echo "PASS $name"
  else
    echo "FAIL $name: expected $expected got $actual"
    FAILED=$((FAILED + 1))
  fi
}

VERIFY_REPO="$TMP_ROOT/verify-repo"
mkdir -p "$VERIFY_REPO"
git -C "$VERIFY_REPO" init -q
git -C "$VERIFY_REPO" config user.name Parkio-Test
git -C "$VERIFY_REPO" config user.email parkio-test@example.invalid
printf '%s\n' fixture > "$VERIFY_REPO/fixture.txt"
git -C "$VERIFY_REPO" add fixture.txt
git -C "$VERIFY_REPO" commit -q -m fixture
SHA="$(git -C "$VERIFY_REPO" rev-parse HEAD)"
expect_status "exact api SHA accepted" 0 \
  "$ROOT/scripts/verify-invite-production-ref.sh" \
  --expected-sha "$SHA" --ref-sha "$SHA" --ref-name refs/heads/api --repo "$VERIFY_REPO"
expect_status "wrong SHA rejected" 2 \
  "$ROOT/scripts/verify-invite-production-ref.sh" \
  --expected-sha 0000000000000000000000000000000000000000 \
  --ref-sha "$SHA" --ref-name refs/heads/api --repo "$VERIFY_REPO"
expect_status "wrong ref rejected" 2 \
  "$ROOT/scripts/verify-invite-production-ref.sh" \
  --expected-sha "$SHA" --ref-sha "$SHA" --ref-name refs/heads/master --repo "$VERIFY_REPO"
printf '%s\n' dirty > "$VERIFY_REPO/fixture.txt"
expect_status "dirty tracked checkout rejected" 2 \
  "$ROOT/scripts/verify-invite-production-ref.sh" \
  --expected-sha "$SHA" --ref-sha "$SHA" --ref-name refs/heads/api --repo "$VERIFY_REPO"
git -C "$VERIFY_REPO" checkout -q -- fixture.txt

WORKSPACE="$TMP_ROOT/workspace"
SECURE="$TMP_ROOT/secure"
SOURCE="$WORKSPACE/source-123-1"
ENV_FILE="$SECURE/parkio-invite-production-123-1.env"
AZURE_DIR="$SECURE/parkio-azure-123-1"
mkdir -p "$SOURCE/docker" "$AZURE_DIR"
printf '%s\n' SECRET_SENTINEL_DB_PASSWORD > "$ENV_FILE"
printf '%s\n' SECRET_SENTINEL_SLACK_URL > "$SOURCE/docker/.env.invite-production"
printf '%s\n' token-cache > "$AZURE_DIR/cache"

expect_status "cleanup succeeds" 0 env \
  GITHUB_WORKSPACE="$WORKSPACE" \
  PARKIO_SECURE_TMP_ROOT="$SECURE" \
  PARKIO_SOURCE_DIR="$SOURCE" \
  PARKIO_ENV_FILE="$ENV_FILE" \
  AZURE_CONFIG_DIR="$AZURE_DIR" \
  "$ROOT/scripts/cleanup-invite-production-job.sh"

TESTS=$((TESTS + 1))
if [ ! -e "$SOURCE" ] && [ ! -e "$ENV_FILE" ] && [ ! -e "$AZURE_DIR" ]; then
  echo "PASS cleanup removes source, env, and Azure cache"
else
  echo "FAIL cleanup left per-job material"
  FAILED=$((FAILED + 1))
fi

expect_status "unsafe cleanup target rejected" 2 env \
  GITHUB_WORKSPACE="$WORKSPACE" \
  PARKIO_SECURE_TMP_ROOT="$SECURE" \
  PARKIO_SOURCE_DIR="$TMP_ROOT" \
  PARKIO_ENV_FILE="$SECURE/parkio-invite-production-123-1.env" \
  AZURE_CONFIG_DIR="$SECURE/parkio-azure-123-1" \
  "$ROOT/scripts/cleanup-invite-production-job.sh"

if [ "$FAILED" -ne 0 ]; then
  echo "RESULT: FAIL ($FAILED/$TESTS)"
  exit 1
fi
echo "RESULT: PASS ($TESTS/$TESTS)"
