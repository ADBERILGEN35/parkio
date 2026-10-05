#!/usr/bin/env bash
# Wrapper for invite-production rollback manifests.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PARKIO_ENV_FILE="${PARKIO_ENV_FILE:-docker/.env.invite-production}"

# F-INV-2 (#289 review B1): an invite-production rollback renders the invite-production model and its
# compose file-list guard. --no-hosted-beta-overlay would switch to the local-dev model instead.
for arg in "$@"; do
  if [ "$arg" = "--no-hosted-beta-overlay" ]; then
    echo "ERROR: rollback-invite-production.sh refuses --no-hosted-beta-overlay: the local-dev model would" >&2
    echo "       leave out the files the invite-production deploy rendered (F-INV-2)." >&2
    exit 3
  fi
done

exec "$ROOT/scripts/rollback-hosted-beta.sh" "$@"
