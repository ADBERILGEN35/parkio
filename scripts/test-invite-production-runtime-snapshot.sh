#!/usr/bin/env bash
#
# PROD-DEPLOY-01A-R8.4 — runner-acceptance no-mutation contract.
#
# Pins the assertion that failed run 32476161001. The old gate was
# `test -z "$(docker ps -q)"`, i.e. "no containers exist" — true only while the
# invite-production VM was empty, and unsatisfiable once the preserved dark
# runtime existed, because passing it required destroying the runtime it guards.
#
# The replacement compares a sanitized structural snapshot before and after
# acceptance. Docker is faked so every mutation shape is exercised deterministically
# without touching a container, and the fixture models the live 15-running /
# 6-exited topology — but the production logic stays topology-relative and never
# hard-codes those counts.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SNAP="$ROOT/scripts/snapshot-invite-production-runtime.sh"

PASS=0
FAIL=0
ok()  { echo "  PASS  $*"; PASS=$((PASS + 1)); }
bad() { echo "  FAIL  $*" >&2; FAIL=$((FAIL + 1)); }

TMP="$(mktemp -d)"; chmod 0755 "$TMP"
trap 'rm -rf -- "$TMP"' EXIT

mkdir -p "$TMP/bin"
cat > "$TMP/bin/docker" <<'FAKE'
#!/usr/bin/env bash
set -u
case "$1 ${2:-}" in
  "ps -aq") printf '%s\n' ${PARKIO_FAKE_IDS:-} ;;
  "inspect -f")
    cid="$4"
    [ "${PARKIO_FAKE_INSPECT_FAIL:-0}" = "1" ] && exit 1
    eval "printf '%s\n' \"\${PARKIO_FAKE_ROW_$cid:-}\""
    ;;
  *) exit 0 ;;
esac
FAKE
chmod +x "$TMP/bin/docker"
export PATH="$TMP/bin:$PATH"

# Fixture: the live topology — 15 running Parkio + 6 exited Parkio.
reset_fixture() {
  local i ids=""
  for i in $(seq 1 15); do
    eval "export PARKIO_FAKE_ROW_r$i='parkio-svc$i|aaaaaaaaaa$i|img:v1|running|2026-08-19T18:42:22Z|0'"
    ids="$ids r$i"
  done
  for i in $(seq 1 6); do
    eval "export PARKIO_FAKE_ROW_x$i='parkio-ex$i|bbbbbbbbbb$i|img:v1|exited|2026-08-19T18:42:22Z|8'"
    ids="$ids x$i"
  done
  export PARKIO_FAKE_IDS="$ids"
}
cap() { "$SNAP" capture "$1" >/dev/null; }
cmp_ok()   { if "$SNAP" compare "$1" "$2" >/dev/null 2>&1; then ok "$3"; else bad "$3"; fi; }
cmp_fail() { if "$SNAP" compare "$1" "$2" >/dev/null 2>&1; then bad "$3 (change went undetected)"; else ok "$3"; fi; }

echo "== A/L: identical before/after on a NON-EMPTY runtime -> PASS =="
reset_fixture; cap "$TMP/before"
cap "$TMP/after"
cmp_ok "$TMP/before" "$TMP/after" "unchanged 15-running/6-exited runtime passes"
[ "$(grep -c '^PROTECTED-' "$TMP/before")" = 21 ] \
  && ok "all 21 Parkio containers captured (running and exited)" \
  || bad "captured $(grep -c '^PROTECTED-' "$TMP/before") rows, expected 21"

echo "== the OLD assertion fails this same valid state =="
if [ -z "$(printf '%s' "${PARKIO_FAKE_IDS}")" ]; then
  bad "fixture is empty; cannot demonstrate the obsolete rule"
else
  ok "old rule 'test -z \"\$(docker ps -q)\"' would fail a healthy 21-container runtime"
fi

echo "== B: container added -> FAIL =="
reset_fixture; cap "$TMP/before"
export PARKIO_FAKE_ROW_new='parkio-intruder|cccccccccc1|img:v1|running|2026-08-21T09:00:00Z|0'
export PARKIO_FAKE_IDS="$PARKIO_FAKE_IDS new"; cap "$TMP/after"
cmp_fail "$TMP/before" "$TMP/after" "added container detected"

echo "== C: container removed -> FAIL =="
reset_fixture; cap "$TMP/before"
export PARKIO_FAKE_IDS="$(printf '%s\n' $PARKIO_FAKE_IDS | grep -vx r15 | tr '\n' ' ')"; cap "$TMP/after"
cmp_fail "$TMP/before" "$TMP/after" "removed container detected"

echo "== D: same name, new container ID -> FAIL =="
reset_fixture; cap "$TMP/before"
export PARKIO_FAKE_ROW_r1='parkio-svc1|dddddddddd1|img:v1|running|2026-08-19T18:42:22Z|0'; cap "$TMP/after"
cmp_fail "$TMP/before" "$TMP/after" "recreated container (ID change) detected"

echo "== E: StartedAt changes -> FAIL =="
reset_fixture; cap "$TMP/before"
export PARKIO_FAKE_ROW_r2='parkio-svc2|aaaaaaaaaa2|img:v1|running|2026-08-21T10:00:00Z|0'; cap "$TMP/after"
cmp_fail "$TMP/before" "$TMP/after" "restart (StartedAt change) detected"

echo "== F: RestartCount increments -> FAIL =="
reset_fixture; cap "$TMP/before"
export PARKIO_FAKE_ROW_r3='parkio-svc3|aaaaaaaaaa3|img:v1|running|2026-08-19T18:42:22Z|1'; cap "$TMP/after"
cmp_fail "$TMP/before" "$TMP/after" "RestartCount increment detected"

echo "== G: exited protected container becomes running -> FAIL =="
reset_fixture; cap "$TMP/before"
export PARKIO_FAKE_ROW_x1='parkio-ex1|bbbbbbbbbb1|img:v1|running|2026-08-21T10:00:00Z|8'; cap "$TMP/after"
cmp_fail "$TMP/before" "$TMP/after" "intentionally-exited container started detected"

echo "== H: running protected container becomes exited -> FAIL =="
reset_fixture; cap "$TMP/before"
export PARKIO_FAKE_ROW_r4='parkio-svc4|aaaaaaaaaa4|img:v1|exited|2026-08-19T18:42:22Z|0'; cap "$TMP/after"
cmp_fail "$TMP/before" "$TMP/after" "stopped container detected"

echo "== I: enumeration order differences only -> PASS =="
reset_fixture; cap "$TMP/before"
export PARKIO_FAKE_IDS="$(printf '%s\n' $PARKIO_FAKE_IDS | tac | tr '\n' ' ')"; cap "$TMP/after"
cmp_ok "$TMP/before" "$TMP/after" "canonical sorting absorbs docker enumeration order"

echo "== J: unrelated non-Parkio containers are classified, not compared =="
reset_fixture; cap "$TMP/before"
export PARKIO_FAKE_ROW_probe='busybox-probe-123|eeeeeeeeee1|busybox:1.36|running|2026-08-21T10:00:00Z|0'
export PARKIO_FAKE_IDS="$PARKIO_FAKE_IDS probe"; cap "$TMP/after"
cmp_ok "$TMP/before" "$TMP/after" "the gate's own --rm probe container does not trip the comparison"
grep -q '^CLASSIFIED-busybox-probe-123' "$TMP/after" \
  && ok "non-Parkio container is explicitly classified, not silently ignored" \
  || bad "non-Parkio container was not classified"

echo "== K: snapshots never contain environment or secret material =="
reset_fixture
export PARKIO_FAKE_ROW_r5='parkio-svc5|aaaaaaaaaa5|img:v1|running|2026-08-19T18:42:22Z|0'
cap "$TMP/before"
grep -qiE 'PASSWORD|SECRET|TOKEN|_KEY|PEM|webhook' "$TMP/before" \
  && bad "snapshot contains secret-looking material" \
  || ok "snapshot carries no secret-bearing fields"
grep -q '{{range .Config.Env' "$SNAP" && bad "snapshot inspects Config.Env" \
  || ok "snapshot never reads Config.Env"
grep -q 'Config.Env\|\.Mounts' "$SNAP" && bad "snapshot pulls env/mount sections" \
  || ok "snapshot template is limited to structural identity fields"

echo "== M: an empty host still passes when unchanged =="
export PARKIO_FAKE_IDS=""
cap "$TMP/before"; cap "$TMP/after"
cmp_ok "$TMP/before" "$TMP/after" "zero-container host passes when unchanged"

echo "== docker failure -> fail closed =="
reset_fixture
PARKIO_FAKE_INSPECT_FAIL=1 "$SNAP" capture "$TMP/x" >/dev/null 2>&1 \
  && bad "inspect failure produced a snapshot" || ok "inspect failure fails closed"

echo "== N/O: independent assertions retained in the workflow =="
wf="$ROOT/.github/workflows/invite-production-deploy.yml"
awk '/^  runner-acceptance:/,/^  workspace-migration:/' "$wf" > "$TMP/acceptance.yml"
# Strip comments for "must not contain" checks: the replacement step quotes the
# obsolete assertion in a comment explaining why it was removed.
sed 's/#.*//' "$TMP/acceptance.yml" > "$TMP/acceptance.nocomment.yml"
has()    { if grep -qF -- "$2" "$TMP/acceptance.yml"; then ok "$1"; else bad "$1"; fi; }
hasnot() { if grep -qF -- "$2" "$TMP/acceptance.nocomment.yml"; then bad "$1"; else ok "$1"; fi; }
hasnot "obsolete 'no containers exist' assertion is gone" 'test -z "$(docker ps -q)"'
has "BEFORE snapshot is captured before any gate" 'Capture runtime snapshot before acceptance'
has "AFTER snapshot is compared to BEFORE" 'snapshot-invite-production-runtime.sh compare'
has "PARKIO_ENV_FILE absence still asserted independently" 'test ! -e "$PARKIO_ENV_FILE"'
has "post-cleanup runtime re-verification exists" 'Prove cleanup itself mutated no runtime'
has "post-cleanup residue checks retained" 'test ! -e "$AZURE_CONFIG_DIR"'
has "per-job checkout residue still asserted" 'test ! -e "$PARKIO_SOURCE_DIR"'
has "zero _work mount gate still runs via the release-consumption gate" 'test-invite-production-release-containers.sh'
if grep -qE '\b15\b' "$SNAP"; then bad "production logic hard-codes 15"; else ok "production logic is topology-relative, not hard-coded to 15"; fi
# The BEFORE capture must precede the first gate that could mutate anything.
before_ln="$(grep -n 'Capture runtime snapshot before acceptance' "$TMP/acceptance.yml" | cut -d: -f1)"
gate_ln="$(grep -n 'test-invite-production-release-containers.sh' "$TMP/acceptance.yml" | cut -d: -f1)"
if [ -n "$before_ln" ] && [ -n "$gate_ln" ] && [ "$before_ln" -lt "$gate_ln" ]; then
  ok "BEFORE snapshot is ordered ahead of the container gate"
else
  bad "BEFORE snapshot is not ordered ahead of the container gate"
fi

echo
echo "R8.4 runtime no-mutation gates: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
