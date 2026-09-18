#!/usr/bin/env bash
# Resolve the exact invite-production Compose chain and enforce its canonical
# profile-aware configured-resource contract without persisting resolved values.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${PARKIO_ENV_FILE:-docker/.env.invite-production.example}"
EVIDENCE=""
NODE_BINARY="${PARKIO_NODE_BINARY:-node}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --evidence) EVIDENCE="${2:-}"; shift 2 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"
cd "$ROOT"

[ -f "$ENV_FILE" ] || { echo "ERROR: env file not found: $ENV_FILE" >&2; exit 2; }
export PARKIO_DEPLOYMENT_PROFILE=invite-production
export PARKIO_INVITE_EDGE_MODE=dark
export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-sha-resource-budget}"
export PARKIO_GIT_SHA="${PARKIO_GIT_SHA:-resource-budget}"
export PARKIO_IMAGE_CREATED="${PARKIO_IMAGE_CREATED:-1970-01-01T00:00:00Z}"
parkio_configure_deployment_profile "$ENV_FILE"

runtime_list="$(mktemp)"
cleanup() { rm -f -- "$runtime_list"; }
trap cleanup EXIT HUP INT TERM
printf '%s\n' "${PARKIO_RUNTIME_SERVICES[@]}" > "$runtime_list"

args=(
  --profile invite-production
  --runtime-services-file "$runtime_list"
)
if [ -n "$EVIDENCE" ]; then
  args+=(--evidence "$EVIDENCE")
fi

parkio_compose "$ENV_FILE" config --format json \
  | "$NODE_BINARY" "$ROOT/scripts/lib/assert-compose-resource-budget.mjs" "${args[@]}"
