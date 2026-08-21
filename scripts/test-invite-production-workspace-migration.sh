#!/usr/bin/env bash
#
# PROD-DEPLOY-01A-R8.2 — workspace-migration helper and idempotency contract.
#
# Pins the defect that made run 32454177524 red. The old helper ended its loop
# body with `grep -q '/_work/' && echo "$name"`, so a zero-match scan returned 1;
# under `set -euo pipefail` the command substitution inherited that and killed the
# script at the exact moment the migration had succeeded. The helper could only
# report success while the very condition it checks for was still present.
#
# Docker is faked so every branch — including inspection failure — is exercised
# deterministically, on any machine, without touching a container.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

PASS=0
FAIL=0
ok()  { echo "  PASS  $*"; PASS=$((PASS + 1)); }
bad() { echo "  FAIL  $*" >&2; FAIL=$((FAIL + 1)); }

TMP="$(mktemp -d)"
chmod 0755 "$TMP"
trap 'rm -rf -- "$TMP"' EXIT

WORK=/opt/actions-runner/parkio-invite-production/_work/parkio/parkio/source-1-1
REL=/opt/parkio/invite-production/releases/aaaa

# Fake docker driven by PARKIO_FAKE_*: FAKE_IDS is the container list, and each
# container's mounts/name come from FAKE_MOUNTS_<id> / FAKE_NAME_<id>.
mkdir -p "$TMP/bin"
cat > "$TMP/bin/docker" <<'FAKE'
#!/usr/bin/env bash
set -u
[ "${PARKIO_FAKE_PS_FAIL:-0}" = "1" ] && [ "$1" = "ps" ] && exit 1
case "$1 ${2:-}" in
  "ps -q") printf '%s\n' ${PARKIO_FAKE_IDS:-} ;;
  "inspect -f")
    fmt="$3"; cid="$4"
    [ "${PARKIO_FAKE_INSPECT_FAIL:-0}" = "1" ] && exit 1
    case "$fmt" in
      *Mounts*) eval "printf '%s\n' \"\${PARKIO_FAKE_MOUNTS_$cid:-}\"" ;;
      *Name*)   eval "printf '%s\n' \"\${PARKIO_FAKE_NAME_$cid:-}\"" ;;
      *)        printf '\n' ;;
    esac
    ;;
  *) exit 0 ;;
esac
FAKE
chmod +x "$TMP/bin/docker"
export PATH="$TMP/bin:$PATH"

# shellcheck source=/dev/null
source "$ROOT/scripts/lib/runtime-release.sh"

echo "== 1. zero stale mounts -> exit 0, empty list (the R8.2 regression) =="
export PARKIO_FAKE_IDS="c1 c2"
export PARKIO_FAKE_MOUNTS_c1="$REL/docker/blackbox/blackbox.yml"
export PARKIO_FAKE_MOUNTS_c2="/proc"
export PARKIO_FAKE_NAME_c1="parkio-blackbox-exporter"
export PARKIO_FAKE_NAME_c2="parkio-node-exporter"
set +e
out="$(parkio_stale_work_mounts)"; rc=$?
set -e
[ "$rc" -eq 0 ] && ok "returns 0 when nothing is stale" || bad "returned $rc, not 0"
[ -z "$out" ] && ok "emits an empty list" || bad "emitted '$out'"

# The precise failure shape of run 32454177524: assignment under set -e.
probe() { local v; v="$(parkio_stale_work_mounts)"; printf 'reached:%s' "${#v}"; }
set +e
probe_out="$(set -euo pipefail; probe)"; probe_rc=$?
set -e
[ "$probe_rc" -eq 0 ] && [ "$probe_out" = "reached:0" ] \
  && ok "assignment under set -euo pipefail survives a zero-match scan" \
  || bad "assignment aborted (rc=$probe_rc out='$probe_out') — R8.2 defect present"

echo "== 2. one stale mount -> exit 0, correct container =="
export PARKIO_FAKE_MOUNTS_c2="$WORK/docker/prometheus/textfile"
set +e; out="$(parkio_stale_work_mounts)"; rc=$?; set -e
[ "$rc" -eq 0 ] && ok "returns 0 with one stale mount" || bad "returned $rc"
[ "$out" = "parkio-node-exporter" ] && ok "names the stale container" || bad "got '$out'"

echo "== 3. multiple stale mounts -> deterministic unique list =="
export PARKIO_FAKE_MOUNTS_c1="$WORK/docker/blackbox/blackbox.yml"
export PARKIO_FAKE_IDS="c1 c2 c1"
set +e; out="$(parkio_stale_work_mounts)"; rc=$?; set -e
expected="parkio-blackbox-exporter
parkio-node-exporter"
[ "$rc" -eq 0 ] && ok "returns 0 with several stale mounts" || bad "returned $rc"
[ "$out" = "$expected" ] && ok "sorted and de-duplicated" || bad "got '$out'"

echo "== 4. docker failure -> fail closed, never a false all-clear =="
export PARKIO_FAKE_IDS="c1"
PARKIO_FAKE_INSPECT_FAIL=1
export PARKIO_FAKE_INSPECT_FAIL
set +e; out="$(parkio_stale_work_mounts 2>/dev/null)"; rc=$?; set -e
[ "$rc" -ne 0 ] && ok "inspect failure returns non-zero (got $rc)" || bad "inspect failure returned 0"
[ -z "$out" ] && ok "inspect failure emits no container list" || bad "emitted '$out'"
unset PARKIO_FAKE_INSPECT_FAIL
PARKIO_FAKE_PS_FAIL=1
export PARKIO_FAKE_PS_FAIL
set +e; parkio_stale_work_mounts >/dev/null 2>&1; rc=$?; set -e
[ "$rc" -ne 0 ] && ok "docker ps failure returns non-zero (got $rc)" || bad "ps failure returned 0"
unset PARKIO_FAKE_PS_FAIL

echo "== 5-7. migration script contract =="
mig="$ROOT/scripts/migrate-invite-production-workspace-mounts.sh"
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
check "zero-stale path exits 0 as an explicit no-op" \
  "grep -q 'no-op: already migrated' '$mig'"
check "zero-stale path recreates nothing" \
  "grep -q 'No service will be recreated, restarted, or replaced' '$mig'"
check "no-op still proves both exporters bind the stable release" \
  "grep -q 'does not bind the stable release' '$mig'"
check "stale path still recreates only the authorized exporters" \
  "grep -q 'up -d --no-deps --force-recreate \"\${MIGRATE_SERVICES\[@\]}\"' '$mig'"
check "scope stays the two exporters" \
  "grep -q 'MIGRATE_SERVICES=(node-exporter blackbox-exporter)' '$mig'"
check "both before and after scans use the fail-closed helper" \
  "[ \"\$(grep -c 'parkio_stale_work_mounts' '$mig')\" -ge 2 ]"
check "the old fragile helper is gone" \
  "! grep -q 'stale_before' '$mig'"
check "no blanket '|| true' hides docker errors in the helper" \
  "! grep -q 'docker ps -q 2>/dev/null || true' '$ROOT/scripts/lib/runtime-release.sh'"
check "helper preserves set -euo pipefail in its script" \
  "grep -q 'set -euo pipefail' '$mig'"

echo "== 8. cleanup still proves zero _work mounts =="
check "cleanup asserts no running container mounts the workspace" \
  "grep -q 'bind-mount paths from the ephemeral workspace' '$ROOT/scripts/cleanup-invite-production-job.sh'"

echo
echo "R8.2 workspace-migration gates: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
