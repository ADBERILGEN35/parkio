#!/usr/bin/env bash
#
# Parkio — rollback hosted-beta to a previous deploy manifest.
#
# Does NOT rebuild images. Requires the previous sha-* images to exist locally
# (or in a registry you have already pulled).
#
# Usage:
#   PARKIO_ENV_FILE=docker/.env ./scripts/rollback-hosted-beta.sh --manifest deploy-artifacts/deploy-....json
#   PARKIO_ENV_FILE=docker/.env ./scripts/rollback-hosted-beta.sh --manifest deploy-artifacts/current.json --dry-run
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"

ENV_FILE="${PARKIO_ENV_FILE:-docker/.env}"
ARTIFACT_DIR="${PARKIO_DEPLOY_ARTIFACT_DIR:-deploy-artifacts}"
OPERATOR="${PARKIO_DEPLOY_OPERATOR:-${USER:-unknown}}"
MANIFEST=""
DRY_RUN=0
SKIP_SMOKE=0
HEALTH_TIMEOUT="${PARKIO_DEPLOY_HEALTH_TIMEOUT:-900}"
USE_HOSTED_BETA=1

while [ "$#" -gt 0 ]; do
  case "$1" in
    --manifest) MANIFEST="${2:-}"; shift 2 ;;
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --artifact-dir) ARTIFACT_DIR="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --skip-smoke) SKIP_SMOKE=1; shift ;;
    --no-hosted-beta-overlay) USE_HOSTED_BETA=0; shift ;;
    --operator) OPERATOR="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

cd "$ROOT"

if [ -z "$MANIFEST" ] || [ ! -f "$MANIFEST" ]; then
  echo "ERROR: --manifest <path> is required and must exist." >&2
  exit 2
fi
if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: env file not found: $ENV_FILE" >&2
  exit 2
fi

IMAGE_TAG="$(jq -r .imageTag "$MANIFEST")"
GIT_SHA="$(jq -r .gitSha "$MANIFEST")"
BRANCH="$(jq -r .branch "$MANIFEST")"
VERSION="$(jq -r .imageVersion "$MANIFEST")"
CREATED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

if [ -z "$IMAGE_TAG" ] || [ "$IMAGE_TAG" = "null" ]; then
  echo "ERROR: manifest missing imageTag" >&2
  exit 2
fi

PARKIO_COMPOSE_FILES="-f docker/docker-compose.yml -f docker/docker-compose.apps.yml -f docker/docker-compose.images.yml"
if [ "$USE_HOSTED_BETA" -eq 1 ] && [ -f docker/docker-compose.hosted-beta.yml ]; then
  PARKIO_COMPOSE_FILES="$PARKIO_COMPOSE_FILES -f docker/docker-compose.hosted-beta.yml"
fi
export PARKIO_COMPOSE_FILES
export PARKIO_IMAGE_TAG="$IMAGE_TAG"
export PARKIO_GIT_SHA="$GIT_SHA"
export PARKIO_IMAGE_CREATED="$CREATED"
export PARKIO_IMAGE_VERSION="$VERSION"

PREVIOUS=""
if [ -f "$ARTIFACT_DIR/current.json" ]; then
  PREVIOUS="$(cd "$ARTIFACT_DIR" && pwd)/current.json"
fi

OUT_NAME="rollback-to-${GIT_SHA:0:12}-$(date -u +%Y%m%dT%H%M%SZ).json"
OUT_PATH="$ARTIFACT_DIR/$OUT_NAME"
mkdir -p "$ARTIFACT_DIR"

echo "=== Parkio hosted-beta rollback ==="
echo "targetManifest=$MANIFEST"
echo "imageTag=$IMAGE_TAG"
echo "gitSha=$GIT_SHA"
echo "dryRun=$DRY_RUN"

parkio_write_manifest "$OUT_PATH" "rollback" "$OPERATOR" "$ENV_FILE" \
  "$IMAGE_TAG" "$GIT_SHA" "$BRANCH" "$CREATED" "$VERSION" "$PREVIOUS"

if [ "$DRY_RUN" -eq 1 ]; then
  echo "DRY-RUN: would verify local images and run parkio_compose $ENV_FILE up -d (no --build)"
  echo "Rollback manifest: $OUT_PATH"
  exit 0
fi

# Verify images exist locally (live rollback only)
missing=0
for svc in "${PARKIO_APP_SERVICES[@]}"; do
  ref="$(parkio_image_ref "$svc" "$IMAGE_TAG")"
  if ! docker image inspect "$ref" >/dev/null 2>&1; then
    echo "ERROR: image not found locally: $ref" >&2
    missing=1
  fi
done
if [ "$missing" -ne 0 ]; then
  echo "ERROR: cannot rollback; build or pull the previous images first." >&2
  exit 2
fi

echo "Starting previous images (no rebuild)..."
parkio_compose "$ENV_FILE" up -d

echo "Waiting for health checks..."
parkio_wait_healthy "$ENV_FILE" "$HEALTH_TIMEOUT"

if [ "$SKIP_SMOKE" -ne 1 ]; then
  echo "Running smoke checks..."
  PARKIO_ENV_FILE="$ENV_FILE" \
    PARKIO_GATEWAY_URL="${PARKIO_GATEWAY_URL:-http://127.0.0.1:8080}" \
    PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED="${PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED:-0}" \
    "$ROOT/scripts/smoke-hosted-beta.sh" | tee "$ARTIFACT_DIR/smoke-rollback-${GIT_SHA:0:12}.log"
fi

cp "$OUT_PATH" "$ARTIFACT_DIR/current.json"
echo "Rolled back to commit: $GIT_SHA"
echo "Manifest: $OUT_PATH"