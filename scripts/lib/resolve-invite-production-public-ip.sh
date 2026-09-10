#!/usr/bin/env bash
# Resolve the invite-production public IPv4 without printing secrets.
# shellcheck shell=bash

PARKIO_INVITE_PRODUCTION_RESOURCE_GROUP="${PARKIO_INVITE_PRODUCTION_RESOURCE_GROUP:-rg-parkio-invite-production-we}"
PARKIO_INVITE_PRODUCTION_PUBLIC_IP_NAME="${PARKIO_INVITE_PRODUCTION_PUBLIC_IP_NAME:-pip-parkio-invite-prod}"

# parkio_resolve_invite_production_public_ip
# Prints the expected invite-production public IPv4.
# Honors PARKIO_INVITE_PRODUCTION_PUBLIC_IP when set (tests / operator override).
# Falls back to Azure CLI lookup of pip-parkio-invite-prod.
parkio_resolve_invite_production_public_ip() {
  if [ -n "${PARKIO_INVITE_PRODUCTION_PUBLIC_IP:-}" ]; then
    printf '%s' "$PARKIO_INVITE_PRODUCTION_PUBLIC_IP"
    return 0
  fi

  if ! command -v az >/dev/null 2>&1; then
    echo "ERROR: Azure CLI is required to resolve invite-production public IP (or set PARKIO_INVITE_PRODUCTION_PUBLIC_IP)" >&2
    return 2
  fi

  local ip
  ip="$(az network public-ip show \
    --resource-group "$PARKIO_INVITE_PRODUCTION_RESOURCE_GROUP" \
    --name "$PARKIO_INVITE_PRODUCTION_PUBLIC_IP_NAME" \
    --query ipAddress \
    --output tsv 2>/dev/null || true)"

  if [ -z "$ip" ]; then
    echo "ERROR: could not resolve public IP for ${PARKIO_INVITE_PRODUCTION_PUBLIC_IP_NAME}" >&2
    return 2
  fi

  if ! [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "ERROR: resolved invite-production public IP is not a valid IPv4 address" >&2
    return 2
  fi

  printf '%s' "$ip"
}
