#!/usr/bin/env bash
# Tests for restore-replay-harness.sh against a fake Gradle that writes synthetic JUnit XML.
# No Docker and no real Gradle.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf -- "${WORK:?}"' EXIT
pass=0
fail=0
ok() { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

TREE="$WORK/tree"
mkdir -p "$TREE/scripts/ci"
cp -- "$ROOT/scripts/ci/restore-replay-harness.sh" "$TREE/scripts/ci/"

# Fake Gradle: records its arguments, then writes one XML per participant class.
# FAKE_SCENARIO holds MODE:PARTICIPANT items:
#   zero     - only a live-path case;
#   skipped  - the restore case is skipped;
#   partskip - one restore case passes and another is skipped;
#   failed   - the restore case fails;
#   error    - the restore case errors;
#   missing  - no XML at all.
# Every participant has a live-path case and a passing restore case by default; gamification
# also has a RestoreReplay class whose case name has no "restore".
# FAKE_GRADLE_RC is the exit code.
cat > "$TREE/gradlew" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$@" > gradle-args
python3 - <<'PY'
import os
from pathlib import Path
modes = {}
for item in os.environ.get("FAKE_SCENARIO", "").split():
    mode, participant = item.split(":", 1)
    modes[participant] = mode
INNER = {"pass": "", "skipped": "<skipped/>", "failed": '<failure message="boom">boom</failure>',
         "error": '<error message="boom">boom</error>'}
def case(cls, name, outcome="pass"):
    return f'  <testcase name="{name}()" classname="com.parkio.fake.{cls}" time="0.1">{INNER[outcome]}</testcase>\n'
def write(out, cls, cases):
    (out / f"TEST-com.parkio.fake.{cls}.xml").write_text(
        f'<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="com.parkio.fake.{cls}">\n{cases}</testsuite>\n')
for p in ["auth", "user", "parking", "media", "moderation", "gamification", "notification", "analytics", "ai-validation"]:
    mode = modes.get(p, "pass")
    if mode == "missing":
        continue
    out = Path(f"services/{p}-service/build/test-results/integrationTest")
    out.mkdir(parents=True, exist_ok=True)
    cls = "AccountErasureAckOutboxPostgresIT"
    cases = case(cls, "coordinatorReplayWithNewRequestEventQueuesFreshAck")
    if mode != "zero":
        cases += case(cls, "restoreReplayErasesAndPublishesTheAttemptBoundAck", mode if mode in INNER else "pass")
    if mode == "partskip":
        cases += case(cls, "restoreReplayRedeliveryQueuesOneAck", "skipped")
    write(out, cls, cases)
    if p == "gamification":
        write(out, "AccountErasureRestoreReplayPostgresIT",
              case("AccountErasureRestoreReplayPostgresIT", "theReplayErasesTheUserAndTheRelayPublishesTheAck"))
PY
exit "${FAKE_GRADLE_RC:-0}"
SH
chmod +x "$TREE/gradlew"

run() { # scenario, gradle exit code, harness args...; sets out and rc
  local scenario="$1" gradle_rc="$2"
  shift 2
  if out="$(FAKE_SCENARIO="$scenario" FAKE_GRADLE_RC="$gradle_rc" \
    bash "$TREE/scripts/ci/restore-replay-harness.sh" "$@" 2>&1)"; then rc=0; else rc=$?; fi
}
row() { grep -F "| $1 |" <<<"$out" | head -1; }

run "" 0 --summary "$WORK/summary.md"
[ "$rc" = 0 ] && grep -qx "harness=PASS" <<<"$out" && [ "$(grep -c '| PASS |' <<<"$out")" = 9 ] \
  && ok "every participant with a passing restore case passes" || bad "all pass: rc=$rc $out"
[ "$(row user)" = "| user | 1 | 0 | 0 | AccountErasureAckOutboxPostgresIT | PASS |" ] \
  && ok "live-path cases in the same class are run but not counted" || bad "live-path count: $(row user)"
[ "$(row gamification)" = "| gamification | 2 | 0 | 0 | AccountErasureAckOutboxPostgresIT, AccountErasureRestoreReplayPostgresIT | PASS |" ] \
  && ok "every case of a RestoreReplay class counts" || bad "class count: $(row gamification)"
[ -f "$WORK/summary.md" ] && grep -qx "harness=PASS" "$WORK/summary.md" && grep -qF "| auth | 1 |" "$WORK/summary.md" \
  && ok "--summary writes the table" || bad "summary file"
mkdir -p "$WORK/caller"
(cd "$WORK/caller" && run "" 0 --summary relative-summary.md)
[ -f "$WORK/caller/relative-summary.md" ] && [ ! -e "$TREE/relative-summary.md" ] \
  && ok "a relative --summary is written relative to the caller" || bad "relative summary"

expected=(--no-daemon --continue
  :services:auth-service:integrationTest --tests "*ErasureRestoreReplayPostgresIT"
  :services:user-service:integrationTest --tests "*AccountErasureAckOutboxPostgresIT"
  :services:parking-service:integrationTest --tests "*AccountErasureAckOutboxPostgresIT"
  :services:media-service:integrationTest --tests "*MediaAccountErasurePostgresMinioIT"
  :services:moderation-service:integrationTest --tests "*AccountErasureAckOutboxPostgresIT"
  :services:gamification-service:integrationTest --tests "*AccountErasureAckOutboxPostgresIT"
  --tests "*AccountErasureRestoreReplayPostgresIT"
  :services:notification-service:integrationTest --tests "*AccountErasureAckOutboxPostgresIT"
  :services:analytics-service:integrationTest --tests "*AccountErasureAckOutboxPostgresIT"
  :services:ai-validation-service:integrationTest --tests "*AccountErasureAckOutboxPostgresIT"
  -Pparkio.integrationTest.requireDocker=true)
[ "$(cat "$TREE/gradle-args")" = "$(printf '%s\n' "${expected[@]}")" ] \
  && ok "Gradle runs each participant's classes with --continue and requireDocker" \
  || bad "gradle args: $(tr '\n' ' ' < "$TREE/gradle-args")"
run "" 0 --gradle-args "--max-workers=2 --console=plain"
sed -n 3,4p "$TREE/gradle-args" | tr '\n' ' ' | grep -qx -- "--max-workers=2 --console=plain " \
  && ok "--gradle-args reach Gradle" || bad "gradle-args: $(tr '\n' ' ' < "$TREE/gradle-args")"

for case in "zero:user:| user | 0 | 0 | 0 | - | FAIL |" \
  "skipped:parking:| parking | 0 | 0 | 1 | AccountErasureAckOutboxPostgresIT | FAIL |" \
  "partskip:notification:| notification | 1 | 0 | 1 | AccountErasureAckOutboxPostgresIT | FAIL |" \
  "failed:media:| media | 1 | 1 | 0 | AccountErasureAckOutboxPostgresIT | FAIL |" \
  "error:moderation:| moderation | 1 | 1 | 0 | AccountErasureAckOutboxPostgresIT | FAIL |" \
  "missing:ai-validation:| ai-validation | 0 | 0 | 0 | - | FAIL |"; do
  mode="${case%%:*}"; rest="${case#*:}"; participant="${rest%%:*}"; want="${rest#*:}"
  run "$mode:$participant" 0
  [ "$rc" = 1 ] && grep -qx "harness=FAIL" <<<"$out" && [ "$(row "$participant")" = "$want" ] \
    && [ "$(grep -c '| PASS |' <<<"$out")" = 8 ] \
    && ok "$mode restore case for $participant fails the harness" || bad "$mode $participant: rc=$rc $(row "$participant")"
done

stale="$TREE/services/analytics-service/build/test-results/integrationTest/TEST-stale.xml"
mkdir -p "$(dirname "$stale")"
printf '%s\n' '<testsuite><testcase name="restoreReplayStale()" classname="x.AccountErasureAckOutboxPostgresIT"/></testsuite>' > "$stale"
run "missing:analytics" 0
[ "$rc" = 1 ] && [ ! -e "$stale" ] && [ "$(row analytics)" = "| analytics | 0 | 0 | 0 | - | FAIL |" ] \
  && ok "results of an earlier run are removed before Gradle runs" || bad "stale: rc=$rc $(row analytics)"

run "" 1
[ "$rc" = 1 ] && grep -qx "harness=PASS" <<<"$out" && grep -qF "FAIL (gradle exit 1)" <<<"$out" \
  && ok "a Gradle failure fails the harness even when every counted case passed" || bad "gradle rc: rc=$rc $out"

run "" 0 --bogus
[ "$rc" = 2 ] && grep -qF "unknown argument --bogus" <<<"$out" && ok "an unknown argument is refused" || bad "bogus: rc=$rc $out"

echo
echo "restore-replay-harness: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
