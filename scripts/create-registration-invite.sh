#!/usr/bin/env bash
set -euo pipefail

# Creates a one-time registration invite via auth-service internal endpoint.
#
# Required:
#   PARKIO_GATEWAY_INTERNAL_SECRET
#   PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN
#   PARKIO_REGISTRATION_INVITE_CREATION_ENABLED=true (on auth-service)
#
# Optional:
#   AUTH_SERVICE_URL (default http://auth-service:8081)
#   PARKIO_REGISTRATION_INVITE_CREATED_BY (operator label)
#   PARKIO_INVITE_PRODUCTION_CONFIRM=1 (required when environment is invite-production)
#
# Example:
#   PARKIO_GATEWAY_INTERNAL_SECRET=... \
#   PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN=... \
#   PARKIO_REGISTRATION_INVITE_CREATION_ENABLED=true \
#   PARKIO_INVITE_PRODUCTION_CONFIRM=1 \
#   ./scripts/create-registration-invite.sh

AUTH_SERVICE_URL="${AUTH_SERVICE_URL:-http://auth-service:8081}"
PARKIO_GATEWAY_INTERNAL_SECRET="${PARKIO_GATEWAY_INTERNAL_SECRET:?PARKIO_GATEWAY_INTERNAL_SECRET is required}"
PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN="${PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN:?PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN is required}"
CREATED_BY="${PARKIO_REGISTRATION_INVITE_CREATED_BY:-operator}"

if [ "${PARKIO_ENVIRONMENT:-}" = "invite-production" ] || [ "${PARKIO_DEPLOYMENT_PROFILE:-}" = "invite-production" ]; then
  if [ "${PARKIO_INVITE_PRODUCTION_CONFIRM:-}" != "1" ]; then
    echo "ERROR: invite-production invite creation requires PARKIO_INVITE_PRODUCTION_CONFIRM=1" >&2
    exit 2
  fi
fi

response="$(curl --fail --silent --show-error \
  -X POST "${AUTH_SERVICE_URL}/internal/auth/registration-invites" \
  -H "Content-Type: application/json" \
  -H "X-Gateway-Auth: ${PARKIO_GATEWAY_INTERNAL_SECRET}" \
  -H "X-Parkio-Registration-Invite-Operator-Token: ${PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN}" \
  -d "{\"createdBy\":\"${CREATED_BY}\"}")"

echo "$response"
echo "Registration invite created (token shown once in JSON above)."
