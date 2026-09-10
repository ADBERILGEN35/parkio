#!/usr/bin/env bash
# Fail-closed attestation for PROD-DEPLOY-01B-03E-B public cutover deploy inputs.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${PARKIO_ENV_FILE:-}"
CANDIDATE_SHA="${PARKIO_EXPECTED_GIT_SHA:-}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --candidate-sha) CANDIDATE_SHA="${2:-}"; shift 2 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

[ -n "$ENV_FILE" ] || { echo "ERROR: --env-file is required" >&2; exit 2; }
[ -f "$ENV_FILE" ] || { echo "ERROR: env file not found: $ENV_FILE" >&2; exit 2; }

# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"
# shellcheck source=lib/invite-edge-mode.sh
source "$ROOT/scripts/lib/invite-edge-mode.sh"
# shellcheck source=lib/invite-deploy-profile.sh
source "$ROOT/scripts/lib/invite-deploy-profile.sh"
# shellcheck source=lib/resolve-invite-production-public-ip.sh
source "$ROOT/scripts/lib/resolve-invite-production-public-ip.sh"

edge_mode="$(parkio_invite_edge_mode_from_env "$ENV_FILE")" || exit 2
acme_authorized="$(parkio_invite_acme_authorized_from_env "$ENV_FILE")" || exit 2
registration_mode="$(parkio_env_value "$ENV_FILE" PARKIO_REGISTRATION_MODE || true)"
profile="$(parkio_invite_deploy_profile_label "$edge_mode" "$acme_authorized")" || exit 4

if [ "$profile" != "public-cutover" ]; then
  echo "ERROR: public cutover attestation requires edge=public and acme=true" >&2
  exit 4
fi

parkio_validate_invite_registration_mode "$registration_mode" || exit 4

expected_ip="$(parkio_resolve_invite_production_public_ip)" || exit 2
CANDIDATE_SHA="${CANDIDATE_SHA:-$(parkio_git_sha)}"

export PARKIO_DEPLOYMENT_PROFILE=invite-production
export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-sha-cutover-attest}"
export PARKIO_GIT_SHA="${CANDIDATE_SHA}"
export PARKIO_IMAGE_CREATED="${PARKIO_IMAGE_CREATED:-1970-01-01T00:00:00Z}"
parkio_configure_deployment_profile "$ENV_FILE"

echo "=== invite-production public cutover attestation ==="
echo "candidate_sha=$CANDIDATE_SHA"
echo "invite_edge_mode=$edge_mode"
echo "invite_acme_authorized=$acme_authorized"
echo "registration_mode=$registration_mode"
echo "target_resource_group=${PARKIO_INVITE_PRODUCTION_RESOURCE_GROUP}"
echo "target_public_ip_name=${PARKIO_INVITE_PRODUCTION_PUBLIC_IP_NAME}"
echo "target_public_ip=$expected_ip"
echo "compose_files=$PARKIO_COMPOSE_FILES"
echo "continuous_service_count=${#PARKIO_RUNTIME_SERVICES[@]}"
echo "caddy_expected_state=runtime"
echo "gateway_expected_state=internal_only"

"$ROOT/scripts/assert-invite-public-cutover-deploy.sh" --env-file "$ENV_FILE" --require-model
"$ROOT/scripts/assert-invite-production-edge-resource-budget.sh" \
  --env-file "$ENV_FILE" \
  --candidate-sha "$CANDIDATE_SHA" \
  --evidence "${PARKIO_DEPLOY_ARTIFACT_DIR:-deploy-artifacts/invite-production}/invite-edge-resource-budget.json"

echo "invite_production_public_cutover_attest=PASS"
