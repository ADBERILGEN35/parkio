#!/usr/bin/env bash
# Resolve invite-production edge Compose models and assert gateway runtime
# identity wiring (PROD-DEPLOY-01B-02D).
#
# Usage:
#   ./scripts/assert-invite-production-runtime-identity.sh [--env-file FILE]
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
export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-sha-runtime-identity}"
export PARKIO_GIT_SHA="$TEST_SHA"
export PARKIO_IMAGE_CREATED="${PARKIO_IMAGE_CREATED:-1970-01-01T00:00:00Z}"
export PARKIO_ENVIRONMENT=invite-production

assert_identity() {
  local label="$1"
  parkio_compose "$ENV_FILE" config --format json \
    | "$NODE_BINARY" "$ROOT/scripts/lib/assert-invite-production-runtime-identity.mjs" \
        --profile-label "$label" \
        --expected-environment invite-production \
        --expected-git-sha "$TEST_SHA"
}

echo "=== invite-production dark runtime identity ==="
export PARKIO_INVITE_EDGE_MODE=dark
export PARKIO_INVITE_ACME_AUTHORIZED=false
parkio_configure_deployment_profile "$ENV_FILE"
assert_identity dark

echo "=== invite-production public-staged runtime identity ==="
export PARKIO_INVITE_EDGE_MODE=public
export PARKIO_INVITE_ACME_AUTHORIZED=false
parkio_configure_deployment_profile "$ENV_FILE"
assert_identity public-staged

echo "=== invite-production public-candidate runtime identity ==="
export PARKIO_INVITE_EDGE_MODE=public
export PARKIO_INVITE_ACME_AUTHORIZED=true
parkio_configure_deployment_profile "$ENV_FILE"
assert_identity public-candidate

echo "=== missing PARKIO_GIT_SHA must fail closed for public overlays ==="
unset PARKIO_GIT_SHA || true
export PARKIO_INVITE_EDGE_MODE=public
export PARKIO_INVITE_ACME_AUTHORIZED=false
parkio_configure_deployment_profile "$ENV_FILE"
if parkio_compose "$ENV_FILE" config --quiet >/dev/null 2>&1; then
  echo "ERROR: public-staged Compose must fail when PARKIO_GIT_SHA is unset" >&2
  exit 4
fi
echo "missing_git_sha_fail_closed=PASS"

echo "invite_production_runtime_identity=PASS"
