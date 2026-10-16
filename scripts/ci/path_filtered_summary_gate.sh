#!/usr/bin/env bash
# Shared gate for path-filtered CI summary jobs (#418 / N-09c).
#
# Env:
#   DETECT_RESULT  — needs.detect.result (must be success)
#   RUN            — needs.detect.outputs.run ("true" | "false")
#   WORK_RESULTS   — space-separated needs.<job>.result for jobs that run when RUN=true
#
# Policy:
#   - detect failure/cancel/skip → fail
#   - RUN=false → every work result must be skipped → pass (intentionally out of scope)
#   - RUN=true  → every work result must be success → pass; failure/cancel/skip → fail
set -euo pipefail

: "${DETECT_RESULT:?}"
: "${RUN:?}"
: "${WORK_RESULTS:?}"

if [ "$DETECT_RESULT" != "success" ]; then
  echo "::error::Path detect job did not succeed (result=${DETECT_RESULT})."
  exit 1
fi

case "$RUN" in
  false)
    for r in $WORK_RESULTS; do
      if [ "$r" != "skipped" ]; then
        echo "::error::Out-of-scope run expected skipped work jobs; got '${r}'."
        exit 1
      fi
    done
    echo "Out of path scope: work jobs skipped; summary passes."
    exit 0
    ;;
  true)
    if [ -z "${WORK_RESULTS// }" ]; then
      echo "::error::In-scope run produced no work job results."
      exit 1
    fi
    for r in $WORK_RESULTS; do
      if [ -z "$r" ] || [ "$r" != "success" ]; then
        echo "::error::In-scope work job did not succeed (result=${r:-empty})."
        exit 1
      fi
    done
    echo "In path scope: all work jobs succeeded."
    exit 0
    ;;
  *)
    echo "::error::Unknown RUN value '${RUN}' (expected true|false)."
    exit 1
    ;;
esac
