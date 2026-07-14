#!/usr/bin/env bash
set -euo pipefail

# Bootstraps the first SUPER_ADMIN via auth-service internal endpoint.
#
# Required environment variables:
#   PARKIO_ADMIN_BOOTSTRAP_TOKEN  - shared bootstrap secret (must match auth-service config)
#   BOOTSTRAP_EMAIL               - existing ACTIVE, verified user email to promote
#
# Optional:
#   AUTH_SERVICE_URL              - default http://auth-service:8080 (docker network hostname)
#   PARKIO_GATEWAY_INTERNAL_SECRET - X-Gateway-Auth header value (required by GatewayAuthFilter)
#
# Example (from host against docker compose network):
#   PARKIO_GATEWAY_INTERNAL_SECRET=dev-secret \
#   PARKIO_ADMIN_BOOTSTRAP_TOKEN=change-me \
#   BOOTSTRAP_EMAIL=founder@parkio.example \
#   ./scripts/bootstrap-super-admin.sh

AUTH_SERVICE_URL="${AUTH_SERVICE_URL:-http://auth-service:8081}"
BOOTSTRAP_EMAIL="${1:-${BOOTSTRAP_EMAIL:-}}"
if [[ -z "${BOOTSTRAP_EMAIL}" ]]; then
  echo "Usage: $0 <email>   or set BOOTSTRAP_EMAIL" >&2
  exit 1
fi
PARKIO_GATEWAY_INTERNAL_SECRET="${PARKIO_GATEWAY_INTERNAL_SECRET:?PARKIO_GATEWAY_INTERNAL_SECRET is required}"
PARKIO_ADMIN_BOOTSTRAP_TOKEN="${PARKIO_ADMIN_BOOTSTRAP_TOKEN:?PARKIO_ADMIN_BOOTSTRAP_TOKEN is required}"

curl --fail --silent --show-error \
  -X POST "${AUTH_SERVICE_URL}/internal/auth/admin/bootstrap-super-admin" \
  -H "Content-Type: application/json" \
  -H "X-Gateway-Auth: ${PARKIO_GATEWAY_INTERNAL_SECRET}" \
  -H "X-Parkio-Admin-Bootstrap-Token: ${PARKIO_ADMIN_BOOTSTRAP_TOKEN}" \
  -d "{\"email\":\"${BOOTSTRAP_EMAIL}\"}"

echo "Bootstrapped SUPER_ADMIN for ${BOOTSTRAP_EMAIL}"
