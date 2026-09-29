#!/usr/bin/env bash
set -euo pipefail

# Creates a one-time registration invite via auth-service internal endpoint.
#
# Required:
#   PARKIO_GATEWAY_INTERNAL_SECRET
#   PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN
#   PARKIO_REGISTRATION_INVITE_CREATION_ENABLED=true (on auth-service)
#   PARKIO_INVITE_UI_LANG=tr|en  (explicit UI/email locale for the register URL)
#
# Optional:
#   AUTH_SERVICE_URL (default http://auth-service:8081)
#   PARKIO_WEB_REGISTER_BASE (default https://app.parkio.dev/register)
#   PARKIO_REGISTRATION_INVITE_CREATED_BY (operator label)
#   PARKIO_INVITE_PRODUCTION_CONFIRM=1 (required when environment is invite-production)
#
# The register URL MUST include lang=tr or lang=en. Do not rely on the browser
# language. Do not commit invitation tokens; print the URL once to the operator.

AUTH_SERVICE_URL="${AUTH_SERVICE_URL:-http://auth-service:8081}"
PARKIO_GATEWAY_INTERNAL_SECRET="${PARKIO_GATEWAY_INTERNAL_SECRET:?PARKIO_GATEWAY_INTERNAL_SECRET is required}"
PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN="${PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN:?PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN is required}"
CREATED_BY="${PARKIO_REGISTRATION_INVITE_CREATED_BY:-operator}"
REGISTER_BASE="${PARKIO_WEB_REGISTER_BASE:-https://app.parkio.dev/register}"
UI_LANG="${PARKIO_INVITE_UI_LANG:-}"

case "${UI_LANG}" in
  tr|en) ;;
  *)
    echo "ERROR: set PARKIO_INVITE_UI_LANG to exactly tr or en (got '${UI_LANG:-<empty>}')" >&2
    exit 2
    ;;
esac

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

token="$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
expires="$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("expiresAt",""))')"
url="$(python3 -c 'import urllib.parse,sys; base=sys.argv[1]; tok=sys.argv[2]; lang=sys.argv[3];
qs=urllib.parse.urlencode({"invite": tok, "lang": lang});
print(base + ("&" if "?" in base else "?") + qs)' "$REGISTER_BASE" "$token" "$UI_LANG")"

echo "UI_LANG=${UI_LANG}"
echo "EXPIRES=${expires}"
echo "REGISTER_URL=${url}"
echo "Registration invite created (token embedded in REGISTER_URL only; do not commit)."
