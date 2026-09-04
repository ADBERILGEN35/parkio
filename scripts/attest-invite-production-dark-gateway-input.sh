#!/usr/bin/env bash
# Attest the raw invite-production deploy input before the environment reviewer gate.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/dark-gateway-url.sh
source "$ROOT/scripts/lib/dark-gateway-url.sh"

event_name="${GITHUB_EVENT_NAME:-}"
dispatch_action="${PARKIO_DISPATCH_ACTION:-}"
raw_input="${PARKIO_DARK_GATEWAY_INPUT-}"
github_env="${GITHUB_ENV:-}"

if [ "$event_name" != "workflow_dispatch" ] || [ "$dispatch_action" != "deploy" ]; then
  echo "ERROR: dark gateway input attestation is valid only for workflow_dispatch action=deploy." >&2
  exit 2
fi

# R9 requires an actually blank input. An explicitly supplied equivalent URL and
# whitespace-only input are both non-empty and fail before environment review.
if [ -n "$raw_input" ]; then
  echo "ERROR: invite-production deploy requires dark_gateway_url to be blank." >&2
  exit 2
fi

if [ -z "$github_env" ]; then
  echo "ERROR: GITHUB_ENV is required to publish dark gateway input evidence." >&2
  exit 2
fi

effective_url="$PARKIO_DARK_GATEWAY_ALLOWED_URL"
parkio_validate_dark_gateway_url "$effective_url"

{
  echo 'PARKIO_REQUESTED_DARK_GATEWAY_URL_INPUT_EVIDENCE=<blank>'
  echo 'PARKIO_RAW_DARK_GATEWAY_INPUT_BLANK=true'
  echo "PARKIO_EFFECTIVE_DARK_GATEWAY_URL=$effective_url"
  echo 'PARKIO_DARK_GATEWAY_INPUT_SOURCE=workflow_dispatch'
  echo "PARKIO_GATEWAY_URL=$effective_url"
} >> "$github_env"

echo 'requestedDarkGatewayUrlInput=<blank>'
echo 'rawDarkGatewayInputBlank=true'
echo "effectiveDarkGatewayUrl=$effective_url"
echo 'darkGatewayInputSource=workflow_dispatch'
