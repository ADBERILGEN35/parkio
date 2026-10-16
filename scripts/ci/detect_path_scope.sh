#!/usr/bin/env bash
# Emit run=true|false for path-scoped CI (#418). Intended for GitHub Actions detect jobs.
#
# Env:
#   EVENT_NAME — github.event_name
#   BASE_REF   — github.base_ref (pull_request only; unused otherwise)
#   PATH_REGEX — ERE matched against changed paths (one pattern)
#   GITHUB_OUTPUT — Actions output file (optional; also prints run=...)
set -euo pipefail

: "${EVENT_NAME:?}"
: "${PATH_REGEX:?}"

emit() {
  local v="$1"
  echo "run=${v}"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "run=${v}" >> "$GITHUB_OUTPUT"
  fi
}

if [ "$EVENT_NAME" != "pull_request" ]; then
  emit true
  exit 0
fi

: "${BASE_REF:?}"
if ! git rev-parse --verify -q "origin/${BASE_REF}" >/dev/null; then
  git fetch --no-tags --prune origin "+refs/heads/${BASE_REF}:refs/remotes/origin/${BASE_REF}"
fi
base="$(git merge-base HEAD "origin/${BASE_REF}")"
changed="$(git diff --name-only "${base}...HEAD" || true)"
if printf '%s\n' "$changed" | grep -qE "${PATH_REGEX}"; then
  emit true
else
  emit false
fi
