#!/usr/bin/env bash
# U02 isolated recovery harness: run every participant's restore-replay tests, and the auth
# coordinator's, against real disposable dependencies. Report the executed count per participant.
#
# Every test class starts its own PostgreSQL (Testcontainers). The participant classes also start
# Kafka, and media's also starts MinIO. requireDocker turns a missing Docker into a failure instead
# of a skip.
#
# A participant passes only when both hold:
#   - at least one restore-replay case ran;
#   - none failed or was skipped.
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

# participant -> test class filters (Gradle --tests patterns), coordinator first.
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

tasks=()
for p in "${PARTICIPANTS[@]}"; do
  module=":services:${p}-service"
  results="$ROOT/services/${p}-service/build/test-results/integrationTest"
  rm -rf -- "${results:?}"
  tasks+=("$module:integrationTest")
  for f in ${FILTERS[$p]}; do tasks+=(--tests "$f"); done
done

cd "$ROOT"
set +e
# shellcheck disable=SC2086
./gradlew --no-daemon --continue $gradle_args "${tasks[@]}" -Pparkio.integrationTest.requireDocker=true
gradle_rc=$?
set -e

report="$(python3 - "$ROOT" "${PARTICIPANTS[@]}" <<'PY'
import glob, sys
import xml.etree.ElementTree as ET
root, participants = sys.argv[1], sys.argv[2:]
rows, ok = [], True
for p in participants:
    run = failed = skipped = 0
    classes = set()
    for path in glob.glob(f"{root}/services/{p}-service/build/test-results/integrationTest/TEST-*.xml"):
        for case in ET.parse(path).getroot().iter("testcase"):
            cls, name = case.get("classname", ""), case.get("name", "")
            if "RestoreReplay" not in cls and "restore" not in name.lower():
                continue
            classes.add(cls.rsplit(".", 1)[-1])
            if case.find("skipped") is not None:
                skipped += 1
            elif case.find("failure") is not None or case.find("error") is not None:
                failed += 1
                run += 1
            else:
                run += 1
    passed = run > 0 and failed == 0 and skipped == 0
    ok &= passed
    rows.append(f"| {p} | {run} | {failed} | {skipped} | {', '.join(sorted(classes)) or '-'} | {'PASS' if passed else 'FAIL'} |")
print("| participant | restore-replay cases run | failed | skipped | classes | result |")
print("|---|---|---|---|---|---|")
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
