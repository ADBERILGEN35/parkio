#!/usr/bin/env bash
# CL-F29.3: render every production Compose model and require container hardening on each
# service (scripts/lib/assert-compose-hardening.mjs; inventory in
# docs/operations/container-hardening-inventory.md). Example env files and placeholder image
# tags only: nothing is started and no resolved model is written.
#
#   invite-production dark     scripts/lib/deploy-common.sh, PARKIO_INVITE_EDGE_MODE=dark
#   invite-production public   same, edge mode public with ACME authorized (Caddy in the model)
#   Civo production            docker/compose.production.files plus the Civo Alertmanager
#                              overlay, as scripts/parkio-prod-compose.sh runs them
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NODE_BINARY="${PARKIO_NODE_BINARY:-node}"
GUARD="$ROOT/scripts/lib/assert-compose-hardening.mjs"
INVITE_ENV="docker/.env.invite-production.example"
CIVO_ENV="docker/.env.azure-hosted-beta.example"
# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"
cd "$ROOT"

export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-sha-hardening}"
export PARKIO_GIT_SHA="${PARKIO_GIT_SHA:-hardening}"
export PARKIO_IMAGE_CREATED="${PARKIO_IMAGE_CREATED:-1970-01-01T00:00:00Z}"

render_invite() {
  local edge_mode="$1" acme_authorized="$2"
  (
    export PARKIO_DEPLOYMENT_PROFILE=invite-production
    export PARKIO_INVITE_EDGE_MODE="$edge_mode"
    export PARKIO_INVITE_ACME_AUTHORIZED="$acme_authorized"
    parkio_configure_deployment_profile "$INVITE_ENV" >/dev/null
    parkio_compose "$INVITE_ENV" config --format json
  )
}

render_civo() {
  local args=()
  local line
  while IFS= read -r line || [ -n "$line" ]; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    args+=(-f "$line")
  done < docker/compose.production.files
  args+=(-f docker/docker-compose.civo-alertmanager.yml)
  docker compose --env-file "$CIVO_ENV" "${args[@]}" config --format json
}

failed=0
check() {
  local label="$1"
  shift
  local model
  if ! model="$("$@")"; then
    echo "FAIL [$label] could not render the Compose model" >&2
    failed=1
    return
  fi
  printf '%s' "$model" | "$NODE_BINARY" "$GUARD" --label "$label" || failed=1
}

check invite-production-dark render_invite dark false
check invite-production-public render_invite public true
check civo-production render_civo

if [ "$failed" -ne 0 ]; then
  echo "Container hardening check FAILED." >&2
  exit 1
fi
echo "Container hardening check PASSED."
