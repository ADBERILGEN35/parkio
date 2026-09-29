#!/usr/bin/env bash
# Least-privilege GHCR login for private parkio/minio and parkio/mc pulls.
# Workflows should prefer docker/login-action + GITHUB_TOKEN. This helper is
# for local operators and isolated fixtures. It never prints the token.
set -euo pipefail

if [[ "${GITHUB_EVENT_NAME:-}" == "pull_request" &&
      "${GITHUB_HEAD_REPO:-}" != "${GITHUB_REPOSITORY:-}" &&
      -n "${GITHUB_HEAD_REPO:-}" ]]; then
  echo "Fork PRs cannot read private ghcr.io/adberilgen35/parkio packages." >&2
  exit 1
fi

: "${GHCR_USERNAME:?set GHCR_USERNAME}"
: "${GHCR_TOKEN:?set GHCR_TOKEN}"

printf '%s' "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USERNAME" --password-stdin >/dev/null
echo "Logged in to ghcr.io as ${GHCR_USERNAME} (token not printed)."
