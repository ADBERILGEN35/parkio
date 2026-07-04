#!/usr/bin/env bash
#
# Parkio — hosted-beta deploy from the current git commit.
#
# Builds all app images from source, tags them with sha-<gitsha> and beta-latest,
# starts the stack (Flyway runs on startup), waits for health, runs smoke checks,
# and writes a deploy manifest under deploy-artifacts/.
#
# Usage (from repo root):
#   PARKIO_ENV_FILE=docker/.env ./scripts/deploy-hosted-beta.sh
#   PARKIO_ENV_FILE=docker/.env ./scripts/deploy-hosted-beta.sh --dry-run
#   PARKIO_ENV_FILE=docker/.env ./scripts/deploy-hosted-beta.sh --allow-dirty
#   PARKIO_ENV_FILE=docker/.env ./scripts/deploy-hosted-beta.sh --no-hosted-beta-overlay
#
# Requires: docker compose v2, git, jq, curl.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"

ENV_FILE="${PARKIO_ENV_FILE:-docker/.env}"
ARTIFACT_DIR="${PARKIO_DEPLOY_ARTIFACT_DIR:-deploy-artifacts}"
OPERATOR="${PARKIO_DEPLOY_OPERATOR:-${USER:-unknown}}"
ALLOW_DIRTY=0
DRY_RUN=0
USE_HOSTED_BETA=1
SKIP_SMOKE=0
HEALTH_TIMEOUT="${PARKIO_DEPLOY_HEALTH_TIMEOUT:-900}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --artifact-dir) ARTIFACT_DIR="${2:-}"; shift 2 ;;
    --allow-dirty) ALLOW_DIRTY=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --no-hosted-beta-overlay) USE_HOSTED_BETA=0; shift ;;
    --skip-smoke) SKIP_SMOKE=1; shift ;;
    --operator) OPERATOR="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

cd "$ROOT"

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: env file not found: $ENV_FILE" >&2
  echo "       Copy docker/.env.example (or .env.hosted-beta.example) first." >&2
  exit 2
fi

if parkio_git_is_dirty && [ "$ALLOW_DIRTY" -ne 1 ]; then
  echo "ERROR: working tree is dirty. Commit/stash changes, or pass --allow-dirty." >&2
  git status --short >&2
  exit 2
fi

GIT_SHA="$(parkio_git_sha)"
BRANCH="$(parkio_git_branch)"
IMAGE_TAG="$(parkio_image_tag_for_sha "$GIT_SHA")"
CREATED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
VERSION="${PARKIO_IMAGE_VERSION:-0.0.1-SNAPSHOT}"
if git -C "$ROOT" describe --tags --exact-match HEAD >/dev/null 2>&1; then
  VERSION="$(git -C "$ROOT" describe --tags --exact-match HEAD)"
fi

PARKIO_COMPOSE_FILES="-f docker/docker-compose.yml -f docker/docker-compose.apps.yml -f docker/docker-compose.images.yml"
if [ "$USE_HOSTED_BETA" -eq 1 ]; then
  if [ -f docker/docker-compose.hosted-beta.yml ]; then
    PARKIO_COMPOSE_FILES="$PARKIO_COMPOSE_FILES -f docker/docker-compose.hosted-beta.yml"
  else
    echo "WARN: hosted-beta overlay missing; continuing without it." >&2
  fi
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

MANIFEST_NAME="deploy-${GIT_SHA:0:12}-$(date -u +%Y%m%dT%H%M%SZ).json"
MANIFEST_PATH="$ARTIFACT_DIR/$MANIFEST_NAME"

echo "=== Parkio hosted-beta deploy ==="
echo "gitSha=$GIT_SHA"
echo "branch=$BRANCH"
echo "imageTag=$IMAGE_TAG"
echo "envFile=$ENV_FILE"
echo "composeFiles=$PARKIO_COMPOSE_FILES"
echo "manifest=$MANIFEST_PATH"
echo "dryRun=$DRY_RUN"

echo "Rendering compose config..."
mkdir -p "$ARTIFACT_DIR"
parkio_compose "$ENV_FILE" config > "$ARTIFACT_DIR/compose-config.rendered.yml"
parkio_compose "$ENV_FILE" config --quiet

parkio_write_manifest "$MANIFEST_PATH" "deploy" "$OPERATOR" "$ENV_FILE" \
  "$IMAGE_TAG" "$GIT_SHA" "$BRANCH" "$CREATED" "$VERSION" "$PREVIOUS"

if [ "$DRY_RUN" -eq 1 ]; then
  echo "DRY-RUN: would build images and run:"
  echo "  parkio_compose $ENV_FILE build"
  echo "  tag beta-latest for each service"
  echo "  parkio_compose $ENV_FILE up -d"
  echo "  wait healthy + smoke"
  echo "Manifest written: $MANIFEST_PATH"
  echo "Rollback would be:"
  jq -r .rollbackCommand "$MANIFEST_PATH"
  exit 0
fi

echo "Building images from current source (refuses stale jars)..."
parkio_compose "$ENV_FILE" build

echo "Tagging beta-latest..."
for svc in "${PARKIO_APP_SERVICES[@]}"; do
  docker tag "$(parkio_image_ref "$svc" "$IMAGE_TAG")" "$(parkio_image_ref "$svc" "beta-latest")"
done

echo "Starting stack (Flyway migrates on startup)..."
parkio_compose "$ENV_FILE" up -d

echo "Waiting for health checks..."
parkio_wait_healthy "$ENV_FILE" "$HEALTH_TIMEOUT"

if [ "$SKIP_SMOKE" -ne 1 ]; then
  echo "Running smoke checks..."
  PARKIO_ENV_FILE="$ENV_FILE" \
    PARKIO_GATEWAY_URL="${PARKIO_GATEWAY_URL:-http://127.0.0.1:8080}" \
    PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED="${PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED:-0}" \
    "$ROOT/scripts/smoke-hosted-beta.sh" | tee "$ARTIFACT_DIR/smoke-${GIT_SHA:0:12}.log"
fi

cp "$MANIFEST_PATH" "$ARTIFACT_DIR/current.json"
echo "Deployed commit: $GIT_SHA"
echo "Manifest: $MANIFEST_PATH"
echo "Current pointer: $ARTIFACT_DIR/current.json"
echo "Rollback command:"
jq -r .rollbackCommand "$MANIFEST_PATH"