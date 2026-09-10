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
check "no-op still proves both exporters are on a valid stable release" \
  "grep -q 'is not on a valid immutable stable release' '$mig'"
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

# ---------------------------------------------------------------------------
# R8.3 stable-release mount verifier state matrix.
#
# Models the exact production state that failed run 32460819264: the exporters
# sit on an OLDER valid release while a NEWER candidate has just been staged.
# The old verifier demanded the candidate and rejected the correct state.
# ---------------------------------------------------------------------------
echo
echo "== R8.3 stable-release mount verifier =="

RROOT="$TMP/opt/parkio/invite-production"
export PARKIO_RUNTIME_ROOT="$RROOT"
OLD_SHA="7c2c8b80fa6429129c9cf3c008d7ca8443795365"
NEW_SHA="ad6c3ebcb6494650e01d6295a7a355928a6fe2c4"
mk_release() {
  local sha="$1"
  local d="$RROOT/releases/$sha"
  install -d -m 0755 "$d/docker/prometheus/textfile" "$d/docker/blackbox"
  install -m 0644 /dev/null "$d/docker/docker-compose.yml"
  install -m 0644 /dev/null "$d/docker/blackbox/blackbox.yml"
}
install -d -m 0755 "$RROOT/releases" "$RROOT/acceptance"
mk_release "$OLD_SHA"
mk_release "$NEW_SHA"

vok()  { # expect success and a specific SHA
  local desc="$1" src="$2" typ="$3" want="$4" got
  if got="$(parkio_release_sha_for_mount "$src" "$typ" 2>/dev/null)" && [ "$got" = "$want" ]; then
    ok "$desc"
  else
    bad "$desc (got '${got:-<fail>}', wanted '$want')"
  fi
}
vfail() { # expect rejection
  local desc="$1" src="$2" typ="$3"
  if parkio_release_sha_for_mount "$src" "$typ" >/dev/null 2>&1; then
    bad "$desc (was accepted)"
  else
    ok "$desc"
  fi
}

echo "-- L/D: already stable on an OLDER release while a newer candidate exists --"
vok "older release blackbox mount accepted, reports its own SHA" \
  "$RROOT/releases/$OLD_SHA/docker/blackbox/blackbox.yml" file "$OLD_SHA"
vok "older release node textfile dir accepted" \
  "$RROOT/releases/$OLD_SHA/docker/prometheus/textfile" dir "$OLD_SHA"
vok "candidate release also accepted" \
  "$RROOT/releases/$NEW_SHA/docker/blackbox/blackbox.yml" file "$NEW_SHA"

echo "-- E: two exporters on DIFFERENT valid releases are each independently valid --"
a="$(parkio_release_sha_for_mount "$RROOT/releases/$OLD_SHA/docker/prometheus/textfile" dir)"
b="$(parkio_release_sha_for_mount "$RROOT/releases/$NEW_SHA/docker/blackbox/blackbox.yml" file)"
{ [ "$a" = "$OLD_SHA" ] && [ "$b" = "$NEW_SHA" ]; } \
  && ok "different release SHAs per exporter both validate (no shared-release requirement)" \
  || bad "mixed-release validation failed ($a / $b)"

echo "-- F/G/H/I: malformed and escaping sources are rejected --"
install -d -m 0755 "$RROOT/releases/not-a-sha/docker"
install -m 0644 /dev/null "$RROOT/releases/not-a-sha/docker/blackbox.yml"
vfail "non-SHA release directory rejected" "$RROOT/releases/not-a-sha/docker/blackbox.yml" file
vfail "short/invalid SHA rejected" "$RROOT/releases/abc123/docker/blackbox.yml" file
install -d -m 0755 "$RROOT/acceptance/999/releases/$OLD_SHA/docker/blackbox"
install -m 0644 /dev/null "$RROOT/acceptance/999/releases/$OLD_SHA/docker/blackbox/blackbox.yml"
vfail "acceptance scratch path rejected" \
  "$RROOT/acceptance/999/releases/$OLD_SHA/docker/blackbox/blackbox.yml" file
vfail "runner _work path rejected" \
  "/opt/actions-runner/parkio-invite-production/_work/parkio/parkio/source-1-1/docker/blackbox/blackbox.yml" file
ln -sfn "$RROOT" "$RROOT/current" 2>/dev/null || true
vfail "mount through the mutable 'current' pointer rejected" \
  "$RROOT/current/docker/blackbox/blackbox.yml" file
printf 'x\n' > "$TMP/outside.yml"
ln -sfn "$TMP/outside.yml" "$RROOT/releases/$OLD_SHA/docker/escape.yml"
vfail "symlink escaping the release root rejected" \
  "$RROOT/releases/$OLD_SHA/docker/escape.yml" file
vfail "missing source rejected" "$RROOT/releases/$OLD_SHA/docker/absent.yml" file
vfail "arbitrary directory under /opt/parkio rejected" "$RROOT/docker/blackbox.yml" file
vfail "release root itself is not a valid mount" "$RROOT/releases/$OLD_SHA" dir

echo "-- type contract --"
vfail "file expected but a directory supplied" \
  "$RROOT/releases/$OLD_SHA/docker/prometheus/textfile" file
vfail "directory expected but a file supplied" \
  "$RROOT/releases/$OLD_SHA/docker/blackbox/blackbox.yml" dir

echo "-- release completeness (backward compatible: structure, not metadata) --"
install -d -m 0755 "$RROOT/releases/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/docker/blackbox"
install -m 0644 /dev/null "$RROOT/releases/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/docker/blackbox/blackbox.yml"
vfail "SHA-named directory without a staged compose model rejected" \
  "$RROOT/releases/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/docker/blackbox/blackbox.yml" file
check2() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
check2 "pre-R8.3 releases stay valid without retrofitted metadata" \
  "[ ! -e '$RROOT/releases/$OLD_SHA/release.json' ]"

echo "-- A/B/C: migration script distinguishes the two paths --"
check2 "no-op path accepts any valid stable release" \
  "grep -q 'does NOT have to adopt the candidate' '$mig'"
check2 "no-op path logs the actual bound release SHA" \
  "grep -q 'boundRelease=' '$mig'"
check2 "recreated exporters must adopt the candidate release" \
  "grep -q 'did not adopt the candidate release' '$mig'"
check2 "per-service mount type is asserted" \
  "grep -q 'parkio_expected_mount_type' '$mig'"
check2 "stale path still recreates only authorized exporters" \
  "grep -q 'up -d --no-deps --force-recreate' '$mig'"
check2 "verifier is shared, not duplicated between layers" \
  "grep -q 'parkio_release_sha_for_mount' '$ROOT/scripts/lib/runtime-release.sh'"
check2 "no weak substring test for releases/" \
  "! grep -q \"grep -q .releases/\" '$mig'"

echo "-- MANDATORY: the exact state that failed run 32460819264 --"
# candidate = ad6c3eb..., exporters already stable on 7c2c8b80..., zero _work.
# Exercised through parkio_verify_exporter_release, the function the migration
# script actually calls on its no-op path.
# shellcheck source=/dev/null
mig_fns="$(sed -n '/^parkio_expected_mount_type()/,/^}/p;/^parkio_verify_exporter_release()/,/^}/p' "$mig")"
eval "$mig_fns"

export PARKIO_FAKE_IDS="nx bx"
export PARKIO_FAKE_NAME_nx="parkio-node-exporter"
export PARKIO_FAKE_NAME_bx="parkio-blackbox-exporter"
export PARKIO_FAKE_MOUNTS_nx="/proc
/sys
/
$RROOT/releases/$OLD_SHA/docker/prometheus/textfile"
export PARKIO_FAKE_MOUNTS_bx="$RROOT/releases/$OLD_SHA/docker/blackbox/blackbox.yml"

set +e
nx_out="$(parkio_verify_exporter_release node-exporter nx 2>/dev/null)"; nx_rc=$?
bx_out="$(parkio_verify_exporter_release blackbox-exporter bx 2>/dev/null)"; bx_rc=$?
set -e
[ "$nx_rc" -eq 0 ] && ok "node-exporter on the older release verifies SUCCESS"   || bad "node-exporter rejected (rc=$nx_rc) — run 32460819264 defect still present"
[ "$bx_out" = "blackbox-exporter $OLD_SHA" ] && ok "blackbox-exporter reports boundRelease=$OLD_SHA"   || bad "blackbox-exporter reported '$bx_out'"
[ "$nx_out" = "node-exporter $OLD_SHA" ] && ok "node-exporter reports boundRelease=$OLD_SHA (not the candidate)"   || bad "node-exporter reported '$nx_out'"
# Host telemetry mounts (/proc, /sys, /) must not be mistaken for config.
grep -q '/proc' <<<"$nx_out" && bad "host telemetry mount leaked into the release report"   || ok "host /proc,/sys,/ mounts ignored, not treated as release config"

echo "-- a genuinely stale exporter is still rejected as unmigrated --"
export PARKIO_FAKE_MOUNTS_bx="/opt/actions-runner/parkio-invite-production/_work/parkio/parkio/source-1-1/docker/blackbox/blackbox.yml"
set +e
parkio_verify_exporter_release blackbox-exporter bx >/dev/null 2>&1; rc=$?
set -e
[ "$rc" -ne 0 ] && ok "exporter still on _work fails verification" || bad "_work exporter passed verification"

echo
echo "R8.2/R8.3 workspace-migration gates: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
