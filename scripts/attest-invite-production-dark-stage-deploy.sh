#!/usr/bin/env bash
# Fail-closed attestation for PROD-DEPLOY-01B-02 dark-stage deploy inputs.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${PARKIO_ENV_FILE:-}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

[ -n "$ENV_FILE" ] || { echo "ERROR: --env-file is required" >&2; exit 2; }
[ -f "$ENV_FILE" ] || { echo "ERROR: env file not found: $ENV_FILE" >&2; exit 2; }

# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"
# shellcheck source=lib/invite-edge-mode.sh
source "$ROOT/scripts/lib/invite-edge-mode.sh"

edge_mode="$(parkio_invite_edge_mode_from_env "$ENV_FILE")" || exit 2
acme_authorized="$(parkio_invite_acme_authorized_from_env "$ENV_FILE")" || exit 2
registration_mode="$(parkio_env_value "$ENV_FILE" PARKIO_REGISTRATION_MODE || true)"
registration_mode="${registration_mode:-closed}"

if [ "$edge_mode" != "public" ]; then
  echo "ERROR: 01B-02 requires PARKIO_INVITE_EDGE_MODE=public (got '$edge_mode')" >&2
  exit 4
fi
if [ "$acme_authorized" = "true" ]; then
  echo "ERROR: 01B-02 requires PARKIO_INVITE_ACME_AUTHORIZED=false" >&2
  exit 4
fi
if [ "$registration_mode" != "closed" ]; then
  echo "ERROR: 01B-02 requires PARKIO_REGISTRATION_MODE=closed (got '$registration_mode')" >&2
  exit 4
fi

export PARKIO_DEPLOYMENT_PROFILE=invite-production
export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-sha-attest}"
export PARKIO_GIT_SHA="${PARKIO_GIT_SHA:-attest}"
export PARKIO_IMAGE_CREATED="${PARKIO_IMAGE_CREATED:-1970-01-01T00:00:00Z}"
parkio_configure_deployment_profile "$ENV_FILE"

if printf '%s' "$PARKIO_COMPOSE_FILES" | grep -q invite-dark; then
  echo "ERROR: public staged deploy must not include invite-dark overlay" >&2
  exit 4
fi
if ! printf '%s' "$PARKIO_COMPOSE_FILES" | grep -q invite-public-staged; then
  echo "ERROR: public staged deploy must include invite-public-staged overlay" >&2
  exit 4
fi
if ! printf '%s\n' "${PARKIO_DISABLED_SERVICES[@]}" | grep -qx caddy; then
  echo "ERROR: Caddy must remain disabled for 01B-02" >&2
  exit 4
fi

echo "invite_edge_mode=$edge_mode"
echo "invite_acme_authorized=$acme_authorized"
echo "registration_mode=$registration_mode"
echo "compose_overlay=invite-public+invite-public-staged"
echo "caddy_runtime_state=disabled"
echo "invite_production_dark_stage_attest=PASS"
