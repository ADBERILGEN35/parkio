#!/usr/bin/env bash
# Validate the exact real invite-production Compose stack and emit safe evidence.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"
parkio_configure_deployment_profile docker/.env.invite-production.example

case " $PARKIO_COMPOSE_FILES " in
  *" docker/docker-compose.azure-hosted-beta.yml "*)
    echo "FAIL: invite-production must not inherit docker-compose.azure-hosted-beta.yml" >&2
    exit 1
    ;;
esac

export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-sha-feature-test}"
export PARKIO_GIT_SHA="${PARKIO_GIT_SHA:-0000000000000000000000000000000000000000}"
export PARKIO_IMAGE_CREATED="${PARKIO_IMAGE_CREATED:-1970-01-01T00:00:00Z}"

COMPOSE_BIN=""
for candidate in docker docker.exe; do
  if command -v "$candidate" >/dev/null 2>&1 \
      && "$candidate" compose version >/dev/null 2>&1; then
    COMPOSE_BIN="$candidate"
    break
  fi
done
if [ -z "$COMPOSE_BIN" ]; then
  echo "FAIL: Docker Compose is required for the invite-production feature model" >&2
  exit 2
fi

export WSLENV="${WSLENV:+$WSLENV:}PARKIO_IMAGE_TAG:PARKIO_GIT_SHA:PARKIO_IMAGE_CREATED"
# shellcheck disable=SC2086
"$COMPOSE_BIN" compose --env-file docker/.env.invite-production.example \
  $PARKIO_COMPOSE_FILES config --format json \
  | python3 "$ROOT/scripts/lib/assert-invite-production-feature-config.py"
