#!/usr/bin/env bash
# N-05 / #354: stub-driven end-to-end tests for the non-root volume readiness gate
# in scripts/parkio-prod-compose.sh. Runs the real wrapper; stubs docker and the
# migration plan script. A grep-only test does not count.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/prod-nonroot-ready.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
TESTS=0
FAILED=0

pass() { TESTS=$((TESTS + 1)); echo "PASS $1"; }
bad() { TESTS=$((TESTS + 1)); FAILED=$((FAILED + 1)); echo "FAIL $1"; sed 's/^/     | /' "$TMP/out" "$TMP/err" 2>/dev/null | tail -n 30 || true; }

# --- fake docker: log mutations; allow compose config/ps/version ---
mkdir -p "$TMP/bin"
cat >"$TMP/bin/docker" <<'SH'
#!/usr/bin/env bash
d="${FAKE_DOCKER_DIR:?}"
echo "$*" >>"$d/calls.log"
case "$1" in
  version) echo "linux/amd64"; exit 0 ;;
  compose)
    for a in "$@"; do
      if [ "$a" = "config" ]; then
        echo "{}" # unused when web guard skipped / non-web targets
        exit 0
      fi
    done
    # Mutating compose subcommands
    echo "MUTATION $*" >>"$d/mutations.log"
    # Record whether the non-root overlay file was on the argv
    prev=""
    for a in "$@"; do
      if [ "$prev" = "-f" ] && [[ "$a" == *docker-compose.civo-nonroot.yml ]]; then
        echo "NONROOT_OVERLAY $a" >>"$d/mutations.log"
      fi
      prev="$a"
    done
    exit 0
    ;;
  *) echo "unexpected docker: $*" >&2; exit 1 ;;
esac
SH
chmod +x "$TMP/bin/docker"
export FAKE_DOCKER_DIR="$TMP"
export PATH="$TMP/bin:$PATH"

# --- stub migration plan ---
SERVICES=(
  postgres-auth postgres-gateway postgres-user postgres-parking postgres-media
  postgres-gamification postgres-notification postgres-moderation postgres-analytics
  postgres-ai-validation redis minio
)
write_plan_stub() {
  local mode="$1" # compliant | needs_chown | absent | omit_redis | fail
  cat >"$TMP/nonroot-volume-migration.sh" <<STUB
#!/usr/bin/env bash
set -euo pipefail
echo "STUB_PLAN \$*" >>"$TMP/plan-calls.log"
mode="$mode"
project=""
services=()
while [ \$# -gt 0 ]; do
  case "\$1" in
    plan) shift ;;
    --project) project="\$2"; shift 2 ;;
    --service) services+=("\$2"); shift 2 ;;
    *) echo "stub: bad arg \$1" >&2; exit 2 ;;
  esac
done
[ -n "\$project" ] || { echo "stub: missing project" >&2; exit 2; }
echo "\$project" >"$TMP/last-project"
if [ "\$mode" = fail ]; then
  echo "stub plan boom" >&2
  exit 4
fi
for s in "\${services[@]}"; do
  case "\$mode" in
    omit_redis)
      [ "\$s" = redis ] && continue
      echo "service=\$s volume=\$project/\${s}-data target=1:1 readiness=ready status=COMPLIANT owners=\"1:1\""
      ;;
    needs_chown)
      if [ "\$s" = minio ]; then
        echo "service=\$s volume=\$project/minio-data target=10001:10001 readiness=ready status=NEEDS_CHOWN owners=\"0:0\""
      else
        echo "service=\$s volume=\$project/\${s}-data target=1:1 readiness=ready status=COMPLIANT owners=\"1:1\""
      fi
      ;;
    absent)
      if [ "\$s" = redis ]; then
        echo "service=\$s volume=\$project/redis-data target=999:1000 readiness=ready status=ABSENT"
      else
        echo "service=\$s volume=\$project/\${s}-data target=1:1 readiness=ready status=COMPLIANT owners=\"1:1\""
      fi
      ;;
    all_absent)
      echo "service=\$s volume=\$project/\${s}-data target=1:1 readiness=ready status=ABSENT"
      ;;
    *)
      echo "service=\$s volume=\$project/\${s}-data target=1:1 readiness=ready status=COMPLIANT owners=\"1:1\""
      ;;
  esac
done
STUB
  chmod +x "$TMP/nonroot-volume-migration.sh"
}

reset_logs() {
  rm -f "$TMP/mutations.log" "$TMP/calls.log" "$TMP/plan-calls.log" "$TMP/out" "$TMP/err" "$TMP/last-project"
  : >"$TMP/mutations.log"
  : >"$TMP/calls.log"
}

# Minimal env so the wrapper can open an env file.
ENV_FILE="$ROOT/docker/.env.azure-hosted-beta.example"
[ -f "$ENV_FILE" ] || { echo "missing $ENV_FILE" >&2; exit 1; }

wrap() {
  # Usage: wrap expect_rc expect_mutation label args...
  local expect_rc="$1" expect_mut="$2" label="$3"
  shift 3
  reset_logs
  local rc=0
  set +e
  PARKIO_ENV_FILE="$ENV_FILE" \
    PARKIO_NONROOT_VOLUME_MIGRATION_SCRIPT="$TMP/nonroot-volume-migration.sh" \
    PARKIO_SKIP_WEB_MAP_GUARD=I_ACCEPT_UNVERIFIED_WEB_IMAGE \
    PARKIO_SKIP_WEB_CONF_D_CHECK=I_ACCEPT_UNCHECKED_WEB_CONF_D \
    bash scripts/parkio-prod-compose.sh "$@" >"$TMP/out" 2>"$TMP/err"
  rc=$?
  set -e
  if [ "$rc" -ne "$expect_rc" ]; then
    bad "$label (exit $rc want $expect_rc)"
    return
  fi
  local mut=0
  if grep -q '^MUTATION ' "$TMP/mutations.log" 2>/dev/null; then mut=1; fi
  if [ "$expect_mut" = yes ] && [ "$mut" -eq 0 ]; then
    bad "$label (expected compose mutation)"
    return
  fi
  if [ "$expect_mut" = no ] && [ "$mut" -ne 0 ]; then
    bad "$label (compose mutation must not run)"
    return
  fi
  pass "$label"
}

# --- Before-fix style: with stub reporting NEEDS_CHOWN, up must refuse (zero mutations) ---
write_plan_stub needs_chown
wrap 1 no "refuse up when a volume is NEEDS_CHOWN" up -d --no-build --no-deps redis
grep -q 'not COMPLIANT' "$TMP/err" && pass "NEEDS_CHOWN error names the gate" || bad "NEEDS_CHOWN error names the gate"
grep -q 'STUB_PLAN' "$TMP/plan-calls.log" && pass "plan stub was invoked for refusal" || bad "plan stub was invoked for refusal"

write_plan_stub absent
wrap 0 yes "allow up when a volume is ABSENT (fresh/DR)" up -d --no-build --no-deps redis
grep -q 'COMPLIANT or ABSENT' "$TMP/err" && pass "ABSENT pass message names the gate" || bad "ABSENT pass message names the gate"

write_plan_stub omit_redis
wrap 1 no "refuse up when plan omits a required service" up -d --no-build --no-deps gateway-service

write_plan_stub fail
wrap 1 no "refuse up when plan exits nonzero" up -d --no-build --no-deps redis

# Wrong break-glass value refused before mutation
write_plan_stub needs_chown
reset_logs
set +e
PARKIO_ENV_FILE="$ENV_FILE" \
  PARKIO_NONROOT_VOLUME_MIGRATION_SCRIPT="$TMP/nonroot-volume-migration.sh" \
  PARKIO_SKIP_NONROOT_VOLUME_GATE=1 \
  PARKIO_SKIP_WEB_MAP_GUARD=I_ACCEPT_UNVERIFIED_WEB_IMAGE \
  PARKIO_SKIP_WEB_CONF_D_CHECK=I_ACCEPT_UNCHECKED_WEB_CONF_D \
  bash scripts/parkio-prod-compose.sh up -d --no-build --no-deps redis >"$TMP/out" 2>"$TMP/err"
rc=$?
set -e
[ "$rc" -ne 0 ] && ! grep -q '^MUTATION ' "$TMP/mutations.log" \
  && pass "legacy PARKIO_SKIP_NONROOT_VOLUME_GATE=1 is refused" \
  || bad "legacy PARKIO_SKIP_NONROOT_VOLUME_GATE=1 is refused"

# Explicit break-glass allows mutation despite NEEDS_CHOWN
write_plan_stub needs_chown
reset_logs
set +e
PARKIO_ENV_FILE="$ENV_FILE" \
  PARKIO_NONROOT_VOLUME_MIGRATION_SCRIPT="$TMP/nonroot-volume-migration.sh" \
  PARKIO_SKIP_NONROOT_VOLUME_GATE=I_ACCEPT_UNMIGRATED_VOLUMES \
  PARKIO_SKIP_WEB_MAP_GUARD=I_ACCEPT_UNVERIFIED_WEB_IMAGE \
  PARKIO_SKIP_WEB_CONF_D_CHECK=I_ACCEPT_UNCHECKED_WEB_CONF_D \
  bash scripts/parkio-prod-compose.sh up -d --no-build --no-deps redis >"$TMP/out" 2>"$TMP/err"
rc=$?
set -e
[ "$rc" -eq 0 ] && grep -q '^MUTATION ' "$TMP/mutations.log" \
  && grep -q 'break-glass' "$TMP/err" \
  && pass "owner break-glass allows up with warning" \
  || bad "owner break-glass allows up with warning"

# Compliant: mutating up proceeds; non-root overlay once on argv
write_plan_stub compliant
wrap 0 yes "compliant volumes allow up" up -d --no-build --no-deps redis
grep -c 'NONROOT_OVERLAY' "$TMP/mutations.log" | grep -qx 1 \
  && pass "non-root overlay appears exactly once on mutating compose argv" \
  || bad "non-root overlay appears exactly once on mutating compose argv"
[ "$(cat "$TMP/last-project")" = parkio ] && pass "default project name is parkio" || bad "default project name is parkio"

# Project override via -p
write_plan_stub compliant
wrap 0 yes "project override -p is passed to plan" -p customproj up -d --no-build --no-deps redis
[ "$(cat "$TMP/last-project")" = customproj ] && pass "plan used -p customproj" || bad "plan used -p customproj"

# Non-mutating ps: no plan required even if stub would fail
write_plan_stub fail
wrap 0 yes "ps does not require volume COMPLIANT (non-mutating)" ps
# ps still mutates? Our fake docker logs MUTATION for any non-config compose. For ps we should NOT treat as gate-mutating.
# Fix: fake docker still logs MUTATION for ps — that's OK for "compose ran"; gate must not call plan.
if grep -q 'STUB_PLAN' "$TMP/plan-calls.log" 2>/dev/null; then
  bad "ps must not invoke the volume plan"
else
  pass "ps does not invoke the volume plan"
fi

# Caller-supplied file list without hosted-beta: no overlay, no plan, up proceeds
write_plan_stub needs_chown
LIST="$TMP/custom.files"
printf '%s\n' docker/docker-compose.yml >"$LIST"
reset_logs
set +e
PARKIO_ENV_FILE="$ENV_FILE" \
  PARKIO_COMPOSE_FILES_LIST="$LIST" \
  PARKIO_NONROOT_VOLUME_MIGRATION_SCRIPT="$TMP/nonroot-volume-migration.sh" \
  PARKIO_SKIP_WEB_MAP_GUARD=I_ACCEPT_UNVERIFIED_WEB_IMAGE \
  PARKIO_SKIP_WEB_CONF_D_CHECK=I_ACCEPT_UNCHECKED_WEB_CONF_D \
  bash scripts/parkio-prod-compose.sh up -d --no-build --no-deps >/dev/null 2>"$TMP/err"
rc=$?
set -e
[ "$rc" -eq 0 ] && ! grep -q 'STUB_PLAN' "$TMP/plan-calls.log" 2>/dev/null \
  && ! grep -q 'NONROOT_OVERLAY' "$TMP/mutations.log" 2>/dev/null \
  && pass "caller file list without hosted-beta skips overlay and gate" \
  || bad "caller file list without hosted-beta skips overlay and gate"

# create and run are also gated
write_plan_stub needs_chown
wrap 1 no "refuse create when not COMPLIANT" create redis
wrap 1 no "refuse run when not COMPLIANT" run --rm redis true

write_plan_stub compliant
wrap 0 yes "compliant create proceeds" create redis


# --- R-05 / #414: fresh host all ABSENT, value-taking flags, private stderr ---
write_plan_stub all_absent
wrap 0 yes "fresh host all ABSENT allows up" up -d --no-build --no-deps redis

write_plan_stub needs_chown
wrap 1 no "refuse --parallel 2 up when NEEDS_CHOWN" --parallel 2 up -d --no-build --no-deps redis
grep -q 'not COMPLIANT/ABSENT\|not COMPLIANT' "$TMP/err" && pass "--parallel 2 NEEDS_CHOWN error names the gate" || bad "--parallel 2 NEEDS_CHOWN error names the gate"

# Private mktemp stderr: force plan failure and ensure no world-readable fixed /tmp path is required
write_plan_stub fail
reset_logs
rm -f /tmp/parkio-nonroot-volume-gate.err
set +e
PARKIO_ENV_FILE="$ENV_FILE" \
  PARKIO_NONROOT_VOLUME_MIGRATION_SCRIPT="$TMP/nonroot-volume-migration.sh" \
  PARKIO_SKIP_WEB_MAP_GUARD=I_ACCEPT_UNVERIFIED_WEB_IMAGE \
  PARKIO_SKIP_WEB_CONF_D_CHECK=I_ACCEPT_UNCHECKED_WEB_CONF_D \
  bash scripts/parkio-prod-compose.sh up -d --no-build --no-deps redis >"$TMP/out" 2>"$TMP/err"
rc=$?
set -e
[ "$rc" -ne 0 ] && ! grep -q '^MUTATION ' "$TMP/mutations.log" \
  && ! test -e /tmp/parkio-nonroot-volume-gate.err \
  && grep -q 'stub plan boom' "$TMP/err" \
  && pass "plan failure uses private mktemp stderr (no fixed /tmp file)" \
  || bad "plan failure uses private mktemp stderr (no fixed /tmp file)"

echo
echo "=== summary: $TESTS tests, $FAILED failed ==="
[ "$FAILED" -eq 0 ]
