#!/usr/bin/env bash
#
# Build a Parkio web image from a canonical bake profile and verify the compiled
# bundle (not just the bake file keys).
#
# Usage (repo root):
#   VITE_MAPTILER_KEY=... ./scripts/build-web-from-bake.sh docker/web-hosted-beta.release-bake.env
#   VITE_MAPTILER_KEY=... ./scripts/build-web-from-bake.sh docker/web-hosted-beta.municipal-on.bake.env \
#     parkio/web:municipal-on-candidate
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

BAKE="${1:?bake env file required}"
TAG="${2:-parkio/web:bake-local}"
GIT_SHA="${IMAGE_REVISION:-}"
if [ -z "$GIT_SHA" ]; then
  GIT_SHA="$(git rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
fi
CREATED="${IMAGE_CREATED:-$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo 1970-01-01T00:00:00Z)}"

if [ ! -f "$BAKE" ]; then
  echo "ERROR: missing bake file $BAKE" >&2
  exit 2
fi
if [ -z "${VITE_MAPTILER_KEY:-}" ]; then
  echo "ERROR: VITE_MAPTILER_KEY must be set in the environment (public MapTiler key)." >&2
  exit 2
fi

set -a
# shellcheck disable=SC1090
source "$BAKE"
set +a

REQUIRE_EXPLORE=true
REQUIRE_MUNICIPAL=false
if [ "${VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED:-false}" = "true" ]; then
  REQUIRE_MUNICIPAL=true
fi

echo "=== build web from $BAKE → $TAG (sha=$GIT_SHA) ==="
echo "VITE_APP_ENV=${VITE_APP_ENV}"
echo "VITE_PUBLIC_EXPLORE_ENABLED=${VITE_PUBLIC_EXPLORE_ENABLED}"
echo "VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=${VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED}"
echo "VERIFY_REQUIRE_PUBLIC_EXPLORE=${REQUIRE_EXPLORE}"
echo "VERIFY_REQUIRE_MUNICIPAL=${REQUIRE_MUNICIPAL}"

docker build -f frontend/apps/web/Dockerfile \
  --build-arg "VITE_API_BASE_URL=${VITE_API_BASE_URL}" \
  --build-arg "VITE_APP_ENV=${VITE_APP_ENV}" \
  --build-arg "VITE_MAPTILER_KEY=${VITE_MAPTILER_KEY}" \
  --build-arg "VITE_MAPTILER_STYLE=${VITE_MAPTILER_STYLE:-streets-v2}" \
  --build-arg "VITE_FRONTEND_ERROR_REPORTING=${VITE_FRONTEND_ERROR_REPORTING:-disabled}" \
  --build-arg "VITE_WAITLIST_INTAKE_MODE=${VITE_WAITLIST_INTAKE_MODE:-api}" \
  --build-arg "VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=${VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED:-false}" \
  --build-arg "VITE_SMART_PARKING_ASSISTANT_ENABLED=${VITE_SMART_PARKING_ASSISTANT_ENABLED:-false}" \
  --build-arg "VITE_PUBLIC_EXPLORE_ENABLED=${VITE_PUBLIC_EXPLORE_ENABLED:-false}" \
  --build-arg "VITE_SMART_RETURN_ENABLED=${VITE_SMART_RETURN_ENABLED:-true}" \
  --build-arg "VITE_REGISTRATION_MODE=${VITE_REGISTRATION_MODE:-closed}" \
  --build-arg "VERIFY_REQUIRE_PUBLIC_EXPLORE=${REQUIRE_EXPLORE}" \
  --build-arg "VERIFY_REQUIRE_MUNICIPAL=${REQUIRE_MUNICIPAL}" \
  --build-arg "IMAGE_REVISION=${GIT_SHA}" \
  --build-arg "IMAGE_CREATED=${CREATED}" \
  -t "$TAG" \
  .

echo "=== extract dist and verify compiled bundle ==="
cid="$(docker create "$TAG")"
tmp="$(mktemp -d)"
cleanup() { docker rm -f "$cid" >/dev/null 2>&1 || true; rm -rf "$tmp"; }
trap cleanup EXIT
docker cp "$cid:/usr/share/nginx/html" "$tmp/dist"
docker rm -f "$cid" >/dev/null
cid=""

verify_args=(--dist "$tmp/dist" --app-env "${VITE_APP_ENV}" --require-public-explore true)
if [ "$REQUIRE_MUNICIPAL" = "true" ]; then
  verify_args+=(--require-municipal true)
else
  verify_args+=(--require-municipal false)
fi
NODE_BIN="${NODE:-}"
if [ -z "$NODE_BIN" ]; then
  if command -v node.exe >/dev/null 2>&1; then NODE_BIN="$(command -v node.exe)"
  else NODE_BIN="$(command -v node)"; fi
fi
"$NODE_BIN" frontend/apps/web/scripts/verify-bundle-env.mjs "${verify_args[@]}"

digest="$(docker image inspect "$TAG" --format '{{index .RepoDigests 0}}' 2>/dev/null || true)"
id="$(docker image inspect "$TAG" --format '{{.Id}}')"
echo "BUILD_OK tag=$TAG id=$id repo_digest=${digest:-n/a}"
