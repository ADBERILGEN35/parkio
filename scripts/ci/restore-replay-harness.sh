#!/usr/bin/env bash
# U02 isolated recovery harness: run every participant's restore-replay tests, and the auth
# coordinator's, against real disposable dependencies. Report the executed count per participant.
#
# Every test class starts its own PostgreSQL (Testcontainers). The participant classes also start
# Kafka, and media's also starts MinIO. requireDocker turns a missing Docker into a failure instead
# of a skip. Each integrationTest task runs with --rerun, so neither the build cache nor an
# up-to-date check can stand in for the run, and every counted suite must carry a JUnit timestamp
# from this invocation.
#
# This proves each service's restore-replay handling on its own. It is not an end-to-end replay
# through a restore entry point (auth start -> Kafka -> participant -> ACK -> COMPLETE).
#
# A participant passes only when all of these hold:
#   - at least its expected number of restore-replay cases ran (MIN_CASES);
#   - none failed or was skipped;
#   - every counted suite ran in this invocation.
# A restore-replay case is any case of a *RestoreReplay* class, or any case whose name contains
# "restore".
#
#   scripts/ci/restore-replay-harness.sh [--summary FILE] [--gradle-args "ARGS"]
# Exit 0 when every participant passes; 1 otherwise.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
summary=""
gradle_args=""
while [ $# -gt 0 ]; do
  case "$1" in
    --summary) summary="${2:?}"; shift 2 ;;
    --gradle-args) gradle_args="${2:?}"; shift 2 ;;
    *) echo "restore-replay-harness: unknown argument $1" >&2; exit 2 ;;
  esac
done
# The script changes into the repository root; a relative --summary stays relative to the caller.
case "$summary" in ""|/*) ;; *) summary="$PWD/$summary" ;; esac

# participant -> test class filters (Gradle --tests patterns), coordinator first, and the number
# of restore-replay cases each must run at least. Raise a minimum when cases are added; a drop
# below it fails the participant.
PARTICIPANTS=(auth user parking media moderation gamification notification analytics ai-validation)
declare -A FILTERS=(
  [auth]="*ErasureRestoreReplayPostgresIT"
  [user]="*AccountErasureAckOutboxPostgresIT"
  [parking]="*AccountErasureAckOutboxPostgresIT"
  [media]="*MediaAccountErasurePostgresMinioIT"
  [moderation]="*AccountErasureAckOutboxPostgresIT"
  [gamification]="*AccountErasureAckOutboxPostgresIT *AccountErasureRestoreReplayPostgresIT"
  [notification]="*AccountErasureAckOutboxPostgresIT"
  [analytics]="*AccountErasureAckOutboxPostgresIT"
  [ai-validation]="*AccountErasureAckOutboxPostgresIT"
)
# auth: ErasureRestoreReplayPostgresIT has eleven cases since #187 (U02 B13).
declare -A MIN_CASES=(
  [auth]=11 [user]=3 [parking]=3 [media]=3 [moderation]=3
  [gamification]=3 [notification]=3 [analytics]=3 [ai-validation]=3
)

start_utc="$(date -u +%Y-%m-%dT%H:%M:%S)"
tasks=()
expected=()
for p in "${PARTICIPANTS[@]}"; do
  module=":services:${p}-service"
  results="$ROOT/services/${p}-service/build/test-results/integrationTest"
  rm -rf -- "${results:?}"
  tasks+=("$module:integrationTest" --rerun)
  for f in ${FILTERS[$p]}; do tasks+=(--tests "$f"); done
  expected+=("$p=${MIN_CASES[$p]}")
done

cd "$ROOT"
set +e
# shellcheck disable=SC2086
./gradlew --no-daemon --continue $gradle_args "${tasks[@]}" -Pparkio.integrationTest.requireDocker=true
gradle_rc=$?
set -e

report="$(python3 - "$ROOT" "$start_utc" "${expected[@]}" <<'PY'
import datetime, glob, sys
import xml.etree.ElementTree as ET
root, start = sys.argv[1], datetime.datetime.fromisoformat(sys.argv[2])
# JUnit timestamps are UTC without a zone. Allow for a VM clock stepping back a little.
earliest = start - datetime.timedelta(seconds=5)
rows, ok = [], True
for spec in sys.argv[3:]:
    p, minimum = spec.split("=", 1)
    minimum = int(minimum)
    run = failed = skipped = stale = 0
    classes = set()
    for path in glob.glob(f"{root}/services/{p}-service/build/test-results/integrationTest/TEST-*.xml"):
        suite = ET.parse(path).getroot()
        try:
            fresh = datetime.datetime.fromisoformat(suite.get("timestamp", "")) >= earliest
        except ValueError:
            fresh = False
        counted = False
        for case in suite.iter("testcase"):
            cls, name = case.get("classname", ""), case.get("name", "")
            if "RestoreReplay" not in cls and "restore" not in name.lower():
                continue
            counted = True
            classes.add(cls.rsplit(".", 1)[-1])
            if case.find("skipped") is not None:
                skipped += 1
            elif case.find("failure") is not None or case.find("error") is not None:
                failed += 1
                run += 1
            else:
                run += 1
        if counted and not fresh:
            stale += 1
    passed = run >= minimum and failed == 0 and skipped == 0 and stale == 0
    ok &= passed
    rows.append(f"| {p} | {run} | {minimum} | {failed} | {skipped} | {stale} | "
                f"{', '.join(sorted(classes)) or '-'} | {'PASS' if passed else 'FAIL'} |")
print("| participant | restore-replay cases run | expected at least | failed | skipped "
      "| suites not from this run | classes | result |")
print("|---|---|---|---|---|---|---|---|")
print("\n".join(rows))
print(f"\nharness={'PASS' if ok else 'FAIL'}")
PY
)"
echo "$report"
if [ -n "$summary" ]; then echo "$report" > "$summary"; fi
if [ "$gradle_rc" -ne 0 ] || ! grep -q '^harness=PASS$' <<<"$report"; then
  echo "restore-replay-harness: FAIL (gradle exit $gradle_rc)" >&2
  exit 1
fi
