#!/usr/bin/env bash
# Regression check: an integrationTest run with requireDocker must execute, never reuse a result.
#
# Run it after a real `integrationTest -Pparkio.integrationTest.requireDocker=true` run. It runs one
# module's integrationTest again with a `docker` stub on PATH that always fails, twice:
#   - up-to-date: the outputs of the real run are still there;
#   - from-cache: the test results are deleted, so a cacheable task would be restored.
# Each time the task must execute, reach the Docker check and fail with its message. Passing means a
# reused result stood in for tests that never ran (#205 review B1). Exit 0 when both runs fail
# closed, 1 otherwise.
#
#   scripts/ci/check-integration-test-not-reused.sh --module :PROJECT:PATH [--gradle-args "ARGS"]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
module=""
gradle_args=""
while [ $# -gt 0 ]; do
  case "$1" in
    --module) module="${2:?}"; shift 2 ;;
    --gradle-args) gradle_args="${2:?}"; shift 2 ;;
    *) echo "check-integration-test-not-reused: unknown argument $1" >&2; exit 2 ;;
  esac
done
[[ "$module" =~ ^(:[a-z0-9-]+)+$ ]] || { echo "check-integration-test-not-reused: --module must be a project path such as :services:NAME" >&2; exit 2; }
project_dir="${module#:}"
results="$ROOT/${project_dir//://}/build/test-results/integrationTest"
[ -d "$results" ] || { echo "check-integration-test-not-reused: no results in $results; run integrationTest first" >&2; exit 2; }

work="$(mktemp -d)"
trap 'rm -rf -- "${work:?}"' EXIT
printf '#!/bin/sh\necho "docker stub: no daemon (integration-test reuse check)" >&2\nexit 1\n' > "$work/docker"
chmod +x "$work/docker"

cd "$ROOT"
failed=0
for case in up-to-date from-cache; do
  if [ "$case" = from-cache ]; then rm -rf -- "${results:?}"; fi
  log="$work/$case.log"
  set +e
  # shellcheck disable=SC2086
  PATH="$work:$PATH" ./gradlew --no-daemon --console=plain $gradle_args "$module:integrationTest" \
    -Pparkio.integrationTest.requireDocker=true > "$log" 2>&1
  rc=$?
  set -e
  outcome="$(grep -E "> Task $module:integrationTest" "$log" | tail -1 || true)"
  if [ "$rc" -ne 0 ] && grep -q 'requireDocker=true but no Docker daemon is reachable' "$log"; then
    echo "ok: $case: the task executed and failed closed on the Docker check ($outcome)"
  else
    echo "::error::$case: integrationTest did not fail closed without Docker (exit $rc; $outcome)"
    tail -20 "$log"
    failed=1
  fi
done
exit "$failed"
