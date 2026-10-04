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

# Fake Gradle: records its arguments, then writes one XML per participant class, stamped now
# (UTC, as Gradle stamps JUnit XML). By default every participant has a live-path case and six
# passing restore cases (auth eleven, its minimum); gamification also has a RestoreReplay class
# whose one case name has no "restore". FAKE_SCENARIO holds MODE:PARTICIPANT items:
#   zero    - only the live-path case;
#   count2  - two restore cases;
#   count5  - five restore cases;
#   count10 - ten restore cases, one short of auth's minimum;
#   skipped - six restore cases (auth eleven), one of them skipped;
#   failed  - six restore cases (auth eleven), one of them failing;
#   error   - six restore cases (auth eleven), one of them erroring;
#   stale   - the suites carry a timestamp from 2020, as a build-cache restore would;
#   recent  - the suites carry a timestamp two minutes before now, as a result restored from a
#             run that ended just before this one would;
#   nostamp - the suites carry no timestamp;
#   missing - no XML at all.
# FAKE_GRADLE_RC is the exit code.
cat > "$TREE/gradlew" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$@" > gradle-args
python3 - <<'PY'
import datetime, os
from pathlib import Path
modes = {}
for item in os.environ.get("FAKE_SCENARIO", "").split():
    mode, participant = item.split(":", 1)
    modes[participant] = mode
NOW = datetime.datetime.now(datetime.timezone.utc)
RECENT = (NOW - datetime.timedelta(minutes=2)).strftime("%Y-%m-%dT%H:%M:%S")
NOW = NOW.strftime("%Y-%m-%dT%H:%M:%S")
INNER = {"pass": "", "skipped": "<skipped/>", "failed": '<failure message="boom">boom</failure>',
         "error": '<error message="boom">boom</error>'}
def case(cls, name, outcome="pass"):
    return f'  <testcase name="{name}()" classname="com.parkio.fake.{cls}" time="0.1">{INNER[outcome]}</testcase>\n'
def write(out, cls, cases, mode):
    stamp = {"stale": ' timestamp="2020-01-01T00:00:00"', "nostamp": "",
             "recent": f' timestamp="{RECENT}"'}.get(mode, f' timestamp="{NOW}"')
    (out / f"TEST-com.parkio.fake.{cls}.xml").write_text(
        f'<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="com.parkio.fake.{cls}"{stamp}>\n{cases}</testsuite>\n')
for p in ["auth", "user", "parking", "media", "moderation", "gamification", "notification", "analytics", "ai-validation"]:
    mode = modes.get(p, "pass")
    if mode == "missing":
        continue
    out = Path(f"services/{p}-service/build/test-results/integrationTest")
    out.mkdir(parents=True, exist_ok=True)
    cls = "AccountErasureAckOutboxPostgresIT"
    count = {"zero": 0, "count2": 2, "count5": 5, "count10": 10}.get(mode, 11 if p == "auth" else 6)
    outcomes = ["pass"] * count
    if mode in ("skipped", "failed", "error"):
        outcomes[-1] = mode
    cases = case(cls, "coordinatorReplayWithNewRequestEventQueuesFreshAck")
    cases += "".join(case(cls, f"restoreReplayCase{i}", o) for i, o in enumerate(outcomes))
    write(out, cls, cases, mode)
    if p == "gamification":
        write(out, "AccountErasureRestoreReplayPostgresIT",
              case("AccountErasureRestoreReplayPostgresIT", "theReplayErasesTheUserAndTheRelayPublishesTheAck"), mode)
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
ACK=AccountErasureAckOutboxPostgresIT

run "" 0 --summary "$WORK/summary.md"
[ "$rc" = 0 ] && grep -qx "harness=PASS" <<<"$out" && [ "$(grep -c '| PASS |' <<<"$out")" = 9 ] \
  && ok "every participant at its expected count with fresh passing cases passes" || bad "all pass: rc=$rc $out"
[ "$(row user)" = "| user | 6 | 3 | 0 | 0 | 0 | $ACK | PASS |" ] \
  && ok "live-path cases in the same class are run but not counted" || bad "live-path count: $(row user)"
[ "$(row gamification)" = "| gamification | 7 | 3 | 0 | 0 | 0 | $ACK, AccountErasureRestoreReplayPostgresIT | PASS |" ] \
  && ok "every case of a RestoreReplay class counts" || bad "class count: $(row gamification)"
[ "$(row auth)" = "| auth | 11 | 11 | 0 | 0 | 0 | $ACK | PASS |" ] \
  && ok "auth is held to its own minimum of eleven" || bad "auth minimum: $(row auth)"
[ -f "$WORK/summary.md" ] && grep -qx "harness=PASS" "$WORK/summary.md" && grep -qF "| auth | 11 | 11 |" "$WORK/summary.md" \
  && ok "--summary writes the table" || bad "summary file"
mkdir -p "$WORK/caller"
(cd "$WORK/caller" && run "" 0 --summary relative-summary.md)
[ -f "$WORK/caller/relative-summary.md" ] && [ ! -e "$TREE/relative-summary.md" ] \
  && ok "a relative --summary is written relative to the caller" || bad "relative summary"

expected=(--no-daemon --continue
  :services:auth-service:integrationTest --rerun --tests "*ErasureRestoreReplayPostgresIT"
  :services:user-service:integrationTest --rerun --tests "*AccountErasureAckOutboxPostgresIT"
  :services:parking-service:integrationTest --rerun --tests "*AccountErasureAckOutboxPostgresIT"
  :services:media-service:integrationTest --rerun --tests "*MediaAccountErasurePostgresMinioIT"
  :services:moderation-service:integrationTest --rerun --tests "*AccountErasureAckOutboxPostgresIT"
  :services:gamification-service:integrationTest --rerun --tests "*AccountErasureAckOutboxPostgresIT"
  --tests "*AccountErasureRestoreReplayPostgresIT"
  :services:notification-service:integrationTest --rerun --tests "*AccountErasureAckOutboxPostgresIT"
  :services:analytics-service:integrationTest --rerun --tests "*AccountErasureAckOutboxPostgresIT"
  :services:ai-validation-service:integrationTest --rerun --tests "*AccountErasureAckOutboxPostgresIT"
  -Pparkio.integrationTest.requireDocker=true)
run "" 0
[ "$(cat "$TREE/gradle-args")" = "$(printf '%s\n' "${expected[@]}")" ] \
  && ok "Gradle reruns each participant's classes, with --continue and requireDocker" \
  || bad "gradle args: $(tr '\n' ' ' < "$TREE/gradle-args")"
run "" 0 --gradle-args "--max-workers=2 --console=plain"
sed -n 3,4p "$TREE/gradle-args" | tr '\n' ' ' | grep -qx -- "--max-workers=2 --console=plain " \
  && ok "--gradle-args reach Gradle" || bad "gradle-args: $(tr '\n' ' ' < "$TREE/gradle-args")"

for case in "zero:user:| user | 0 | 3 | 0 | 0 | 0 | - | FAIL |" \
  "count2:parking:| parking | 2 | 3 | 0 | 0 | 0 | $ACK | FAIL |" \
  "count10:auth:| auth | 10 | 11 | 0 | 0 | 0 | $ACK | FAIL |" \
  "skipped:notification:| notification | 5 | 3 | 0 | 1 | 0 | $ACK | FAIL |" \
  "failed:media:| media | 6 | 3 | 1 | 0 | 0 | $ACK | FAIL |" \
  "error:moderation:| moderation | 6 | 3 | 1 | 0 | 0 | $ACK | FAIL |" \
  "stale:analytics:| analytics | 6 | 3 | 0 | 0 | 1 | $ACK | FAIL |" \
  "recent:parking:| parking | 6 | 3 | 0 | 0 | 1 | $ACK | FAIL |" \
  "nostamp:user:| user | 6 | 3 | 0 | 0 | 1 | $ACK | FAIL |" \
  "missing:ai-validation:| ai-validation | 0 | 3 | 0 | 0 | 0 | - | FAIL |"; do
  mode="${case%%:*}"; rest="${case#*:}"; participant="${rest%%:*}"; want="${rest#*:}"
  run "$mode:$participant" 0
  [ "$rc" = 1 ] && grep -qx "harness=FAIL" <<<"$out" && [ "$(row "$participant")" = "$want" ] \
    && [ "$(grep -c '| PASS |' <<<"$out")" = 8 ] \
    && ok "$mode results for $participant fail the harness" || bad "$mode $participant: rc=$rc $(row "$participant")"
done
run "count5:user" 0
[ "$rc" = 0 ] && [ "$(row user)" = "| user | 5 | 3 | 0 | 0 | 0 | $ACK | PASS |" ] \
  && ok "five cases pass a participant whose minimum is three" || bad "count5 user: rc=$rc $(row user)"

stale="$TREE/services/analytics-service/build/test-results/integrationTest/TEST-stale.xml"
mkdir -p "$(dirname "$stale")"
printf '%s\n' '<testsuite timestamp="2999-01-01T00:00:00"><testcase name="restoreReplayStale()" classname="x.AccountErasureAckOutboxPostgresIT"/></testsuite>' > "$stale"
run "missing:analytics" 0
[ "$rc" = 1 ] && [ ! -e "$stale" ] && [ "$(row analytics)" = "| analytics | 0 | 3 | 0 | 0 | 0 | - | FAIL |" ] \
  && ok "results of an earlier run are removed before Gradle runs" || bad "stale file: rc=$rc $(row analytics)"

run "" 1
[ "$rc" = 1 ] && grep -qx "harness=PASS" <<<"$out" && grep -qF "FAIL (gradle exit 1)" <<<"$out" \
  && ok "a Gradle failure fails the harness even when every counted case passed" || bad "gradle rc: rc=$rc $out"

run "" 0 --bogus
[ "$rc" = 2 ] && grep -qF "unknown argument --bogus" <<<"$out" && ok "an unknown argument is refused" || bad "bogus: rc=$rc $out"

echo
echo "restore-replay-harness: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
