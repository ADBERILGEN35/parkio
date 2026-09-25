#!/usr/bin/env bash
#
# Mode-aware invite-production edge guard (PROD-DEPLOY-01B-03E-A1).
#
# Selects the correct fail-closed assertion for the resolved deployment profile:
#   dark            -> assert-invite-dark-acme-isolation.sh
#   public-staged   -> assert-invite-dark-acme-isolation.sh
#   public-cutover  -> assert-invite-public-cutover-deploy.sh
#
# Usage: assert-invite-production-edge-guard.sh [--env-file FILE] [--model FILE]
#                                                 [--require-model] [--skip-dns]
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${PARKIO_ENV_FILE:-docker/.env.invite-production}"
MODEL=""
REQUIRE_MODEL=0
SKIP_DNS=0
GUARD_ARGS=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; GUARD_ARGS+=(--env-file "$ENV_FILE"); shift 2 ;;
    --model) MODEL="${2:-}"; GUARD_ARGS+=(--model "$2"); shift 2 ;;
    --require-model) REQUIRE_MODEL=1; shift ;;
    --skip-dns) SKIP_DNS=1; shift ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"
# shellcheck source=lib/invite-edge-mode.sh
source "$ROOT/scripts/lib/invite-edge-mode.sh"
# shellcheck source=lib/invite-deploy-profile.sh
source "$ROOT/scripts/lib/invite-deploy-profile.sh"

parkio_configure_deployment_profile "$ENV_FILE" >/dev/null 2>&1 || true

if [ "${PARKIO_DEPLOYMENT_PROFILE:-}" != "invite-production" ]; then
  echo "SKIP: profile '${PARKIO_DEPLOYMENT_PROFILE:-unset}' is not invite-production"
  exit 0
fi

edge_mode="$(parkio_invite_edge_mode_from_env "$ENV_FILE")" || exit 2
acme_authorized="$(parkio_invite_acme_authorized_from_env "$ENV_FILE")" || exit 2
profile="$(parkio_invite_deploy_profile_label "$edge_mode" "$acme_authorized")" || exit 4

echo "=== invite-production edge guard (profile=$profile) ==="

case "$profile" in
  dark|public-staged)
  if [ "$REQUIRE_MODEL" = "1" ]; then
    GUARD_ARGS+=(--require-model)
  fi
  exec "$ROOT/scripts/assert-invite-dark-acme-isolation.sh" "${GUARD_ARGS[@]}"
  ;;
  public-cutover)
  if [ "$REQUIRE_MODEL" = "1" ]; then
    GUARD_ARGS+=(--require-model)
  fi
  if [ "$SKIP_DNS" = "1" ]; then
    GUARD_ARGS+=(--skip-dns)
  fi
  exec "$ROOT/scripts/assert-invite-public-cutover-deploy.sh" "${GUARD_ARGS[@]}"
  ;;
  *)
    echo "ERROR: unsupported invite-production deploy profile '$profile'" >&2
    exit 2
    ;;
esac
