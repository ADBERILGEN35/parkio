#!/usr/bin/env bash
# Resolve invite-production edge Compose models and assert Level-B public-surface
# wiring: explicit CLOSED registration + public actuator info false (01B-03B).
#
# Usage:
#   ./scripts/assert-invite-production-public-surface.sh [--env-file FILE]
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${PARKIO_ENV_FILE:-docker/.env.invite-production.example}"
NODE_BINARY="${PARKIO_NODE_BINARY:-node}"
TEST_SHA="${PARKIO_RUNTIME_IDENTITY_TEST_SHA:-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"
cd "$ROOT"

[ -f "$ENV_FILE" ] || { echo "ERROR: env file not found: $ENV_FILE" >&2; exit 2; }

export PARKIO_DEPLOYMENT_PROFILE=invite-production
export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-sha-public-surface}"
export PARKIO_GIT_SHA="$TEST_SHA"
export PARKIO_IMAGE_CREATED="${PARKIO_IMAGE_CREATED:-1970-01-01T00:00:00Z}"
export PARKIO_ENVIRONMENT=invite-production

assert_surface() {
  local label="$1"
  parkio_compose "$ENV_FILE" config --format json \
    | "$NODE_BINARY" "$ROOT/scripts/lib/assert-invite-production-public-surface.mjs" \
        --profile-label "$label" \
        --expected-registration-mode closed \
        --expected-public-actuator-info false
}

echo "=== invite-production dark public-surface ==="
export PARKIO_INVITE_EDGE_MODE=dark
export PARKIO_INVITE_ACME_AUTHORIZED=false
parkio_configure_deployment_profile "$ENV_FILE"
assert_surface dark

echo "=== invite-production public-staged public-surface ==="
export PARKIO_INVITE_EDGE_MODE=public
export PARKIO_INVITE_ACME_AUTHORIZED=false
parkio_configure_deployment_profile "$ENV_FILE"
assert_surface public-staged

echo "=== invite-production public-candidate public-surface ==="
export PARKIO_INVITE_EDGE_MODE=public
export PARKIO_INVITE_ACME_AUTHORIZED=true
parkio_configure_deployment_profile "$ENV_FILE"
assert_surface public-candidate

echo "=== missing PARKIO_REGISTRATION_MODE must fail closed for public overlays ==="
unset PARKIO_REGISTRATION_MODE || true
# Compose interpolates from the env-file AND the process environment. Strip the
# key from a temporary env copy so resolution cannot soft-default.
TMP_ENV="$(mktemp)"
trap 'rm -f "$TMP_ENV"' EXIT
grep -v '^PARKIO_REGISTRATION_MODE=' "$ENV_FILE" > "$TMP_ENV" || true
export PARKIO_INVITE_EDGE_MODE=public
export PARKIO_INVITE_ACME_AUTHORIZED=false
parkio_configure_deployment_profile "$TMP_ENV"
if parkio_compose "$TMP_ENV" config --quiet >/dev/null 2>&1; then
  echo "ERROR: public-staged Compose must fail when PARKIO_REGISTRATION_MODE is unset" >&2
  exit 4
fi
echo "missing_registration_mode_fail_closed=PASS"

echo "=== missing PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED must fail closed ==="
grep -v '^PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED=' "$ENV_FILE" > "$TMP_ENV" || true
unset PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED || true
export PARKIO_INVITE_EDGE_MODE=public
export PARKIO_INVITE_ACME_AUTHORIZED=true
parkio_configure_deployment_profile "$TMP_ENV"
if parkio_compose "$TMP_ENV" config --quiet >/dev/null 2>&1; then
  echo "ERROR: public-candidate Compose must fail when PUBLIC_ACTUATOR flag is unset" >&2
  exit 4
fi
echo "missing_public_actuator_fail_closed=PASS"

echo "invite_production_public_surface=PASS"
