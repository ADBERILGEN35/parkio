#!/usr/bin/env bash
# Fail-closed attestation for invite-production workflow dispatch inputs (03E-A1).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${PARKIO_ENV_FILE:-}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

# shellcheck source=lib/invite-deploy-profile.sh
source "$ROOT/scripts/lib/invite-deploy-profile.sh"

profile="$(parkio_validate_invite_dispatch_inputs)" || exit 4

echo "invite_dispatch_profile=$profile"
echo "invite_edge_mode=${PARKIO_DISPATCH_INVITE_EDGE_MODE}"
echo "invite_acme_authorized=${PARKIO_DISPATCH_INVITE_ACME_AUTHORIZED}"
echo "registration_mode=${PARKIO_DISPATCH_REGISTRATION_MODE}"

if [ -n "${PARKIO_DISPATCH_CUTOVER_AUTHORIZATION:-}" ]; then
  echo "cutover_authorization_present=true"
else
  echo "cutover_authorization_present=false"
fi

if [ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ]; then
  "$ROOT/scripts/apply-invite-production-deploy-dispatch.sh" --env-file "$ENV_FILE"
fi

case "$profile" in
  public-staged)
    if [ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ]; then
      "$ROOT/scripts/attest-invite-production-dark-stage-deploy.sh" --env-file "$ENV_FILE"
    fi
    ;;
  public-cutover)
    if [ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ]; then
      "$ROOT/scripts/attest-invite-production-public-cutover-deploy.sh" --env-file "$ENV_FILE"
    fi
    ;;
  dark)
    echo "ERROR: workflow deploy path does not support edge=dark (use runner acceptance)" >&2
    exit 4
    ;;
esac

echo "invite_production_deploy_dispatch_attest=PASS"
