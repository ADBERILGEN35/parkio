#!/usr/bin/env bash
# Read-only invite-production edge validation (PROD-DEPLOY-01B-01).
# Validates resolved compose/config for dark or public candidate modes without
# mutating infrastructure or printing secrets.
#
# Usage:
#   ./scripts/validate-invite-production-edge.sh [--env-file FILE] [--mode dark|public]
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${PARKIO_ENV_FILE:-docker/.env.invite-production.example}"
MODE_OVERRIDE=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --mode) MODE_OVERRIDE="${2:-}"; shift 2 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"
# shellcheck source=lib/invite-edge-mode.sh
source "$ROOT/scripts/lib/invite-edge-mode.sh"

[ -f "$ENV_FILE" ] || { echo "ERROR: env file not found: $ENV_FILE" >&2; exit 2; }

export PARKIO_DEPLOYMENT_PROFILE=invite-production
export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-sha-edge-validate}"
export PARKIO_GIT_SHA="${PARKIO_GIT_SHA:-edge-validate}"
export PARKIO_IMAGE_CREATED="${PARKIO_IMAGE_CREATED:-1970-01-01T00:00:00Z}"

if [ -n "$MODE_OVERRIDE" ]; then
  export PARKIO_INVITE_EDGE_MODE="$MODE_OVERRIDE"
fi

edge_mode="$(parkio_invite_edge_mode_from_env "$ENV_FILE")" || exit 2
acme_authorized="$(parkio_invite_acme_authorized_from_env "$ENV_FILE")" || exit 2
parkio_configure_deployment_profile "$ENV_FILE"

registration_mode="$(parkio_env_value "$ENV_FILE" PARKIO_REGISTRATION_MODE || true)"
registration_mode="${registration_mode:-closed}"
case "$registration_mode" in
  closed|invite|open) ;;
  *) echo "ERROR: unsupported PARKIO_REGISTRATION_MODE='$registration_mode'" >&2; exit 4 ;;
esac

cors_origin="$(parkio_env_value "$ENV_FILE" PARKIO_CORS_ALLOWED_ORIGINS || true)"
media_public="$(parkio_env_value "$ENV_FILE" PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT || true)"
hsts="$(parkio_env_value "$ENV_FILE" PARKIO_HSTS_HEADER_VALUE || true)"
trusted="$(parkio_env_value "$ENV_FILE" PARKIO_TRUSTED_PROXIES || true)"
max_upload="$(parkio_env_value "$ENV_FILE" PARKIO_MAX_UPLOAD_SIZE || true)"
max_upload="${max_upload:-25MB}"

echo "edge_mode=$edge_mode"
echo "acme_authorized=$acme_authorized"
echo "registration_mode=$registration_mode"
echo "cors_origin=$cors_origin"
echo "media_public_endpoint=$media_public"
echo "hsts_header_value=${hsts:-max-age=86400}"
echo "trusted_proxies_configured=$([ -n "$trusted" ] && echo true || echo false)"
echo "max_upload_size=$max_upload"

if [ "$edge_mode" = "dark" ]; then
  if printf '%s\n' "${PARKIO_DISABLED_SERVICES[@]}" | grep -qx caddy; then
    echo "caddy_expected=absent"
  else
    echo "ERROR: dark mode must disable caddy" >&2
    exit 4
  fi
  if printf '%s' "$PARKIO_COMPOSE_FILES" | grep -q invite-dark; then
    echo "compose_overlay=invite-dark"
  else
    echo "ERROR: dark mode must include invite-dark overlay" >&2
    exit 4
  fi
else
  if printf '%s' "$PARKIO_COMPOSE_FILES" | grep -q invite-public; then
    echo "compose_overlay=invite-public"
  else
    echo "ERROR: public mode must include invite-public overlay" >&2
    exit 4
  fi
  if [ "$acme_authorized" = "true" ]; then
    echo "caddy_expected=runtime"
  else
    if printf '%s\n' "${PARKIO_DISABLED_SERVICES[@]}" | grep -qx caddy; then
      echo "caddy_expected=staged_disabled"
    else
      echo "ERROR: public mode without ACME authorization must keep caddy disabled" >&2
      exit 4
    fi
    if ! printf '%s' "$PARKIO_COMPOSE_FILES" | grep -q invite-public-staged; then
      echo "ERROR: public staged mode must include invite-public-staged overlay" >&2
      exit 4
    fi
    echo "compose_staged_overlay=invite-public-staged"
  fi
fi

if [ "$cors_origin" = "*" ]; then
  echo "ERROR: CORS wildcard is forbidden" >&2
  exit 4
fi

if [ -n "$media_public" ] && ! printf '%s' "$media_public" | grep -q '^https://'; then
  echo "ERROR: media public endpoint must be https" >&2
  exit 4
fi

if [ "$acme_authorized" != "true" ] && [ "$edge_mode" = "dark" ]; then
  "$ROOT/scripts/assert-invite-dark-acme-isolation.sh" --env-file "$ENV_FILE" || exit 4
fi

echo "invite_production_edge_validation=PASS"
