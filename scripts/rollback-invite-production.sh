#!/usr/bin/env bash
# Wrapper for invite-production rollback manifests.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PARKIO_ENV_FILE="${PARKIO_ENV_FILE:-docker/.env.invite-production}"

exec "$ROOT/scripts/rollback-hosted-beta.sh" "$@"
