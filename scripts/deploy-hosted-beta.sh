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
# shellcheck source=lib/disk-space.sh
source "$ROOT/scripts/lib/disk-space.sh"

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

# R-005: secret/configuration preflight — refuse to build/deploy on placeholder
# secrets, local-dev leakage, non-HTTPS domains or unsafe toggles. Compose render
# is skipped here because this script renders the full config itself below.
if [ "$USE_HOSTED_BETA" -eq 1 ]; then
  echo "Running secret/configuration preflight (R-005)..."
  if ! "$ROOT/scripts/preflight-hosted-beta.sh" --env-file "$ENV_FILE" --skip-compose; then
    echo "ERROR: preflight failed — deployment aborted before build. Fix the FAIL lines above." >&2
    exit 3
  fi
else
  echo "WARN: --no-hosted-beta-overlay set — skipping hosted-beta preflight (local/dev deploy)." >&2
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

if [ "$USE_HOSTED_BETA" -eq 1 ]; then
  parkio_configure_deployment_profile "$ENV_FILE"
else
  PARKIO_DEPLOYMENT_PROFILE="local-dev"
  PARKIO_COMPOSE_FILES="-f docker/docker-compose.yml -f docker/docker-compose.apps.yml -f docker/docker-compose.images.yml"
  export PARKIO_DEPLOYMENT_PROFILE PARKIO_COMPOSE_FILES
fi
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
echo "deploymentProfile=$PARKIO_DEPLOYMENT_PROFILE"
echo "composeFiles=$PARKIO_COMPOSE_FILES"
echo "runtimeServices=${PARKIO_RUNTIME_SERVICES[*]:-all}"
echo "disabledServices=${PARKIO_DISABLED_SERVICES[*]:-none}"
echo "manifest=$MANIFEST_PATH"
echo "dryRun=$DRY_RUN"

# Fail closed before compose render / image builds when root free space is
# below the hosted-beta capacity gate (default 12 GiB). Does not auto-prune.
echo "Checking free disk capacity..."
if ! parkio_require_free_disk /; then
  echo "ERROR: disk preflight failed — deployment aborted before build." >&2
  exit 3
fi

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
  echo "  parkio_compose_up $ENV_FILE"
  echo "  wait healthy + smoke"
  echo "Manifest written: $MANIFEST_PATH"
  echo "Rollback would be:"
  jq -r .rollbackCommand "$MANIFEST_PATH"
  exit 0
fi

echo "Building images from current source sequentially (refuses stale jars)..."

for svc in "${PARKIO_APP_SERVICES[@]}"; do
  echo "=== Building $svc ==="
  parkio_compose "$ENV_FILE" build "$svc"
done

echo "Tagging beta-latest..."
for svc in "${PARKIO_APP_SERVICES[@]}"; do
  docker tag "$(parkio_image_ref "$svc" "$IMAGE_TAG")" "$(parkio_image_ref "$svc" "beta-latest")"
done

echo "Starting stack (Flyway migrates on startup)..."
parkio_compose_up "$ENV_FILE"

echo "Waiting for health checks..."
parkio_wait_healthy "$ENV_FILE" "$HEALTH_TIMEOUT"

if [ "$SKIP_SMOKE" -ne 1 ]; then
  echo "Running smoke checks..."
  PARKIO_ENV_FILE="$ENV_FILE" \
    PARKIO_DEPLOYMENT_PROFILE="$PARKIO_DEPLOYMENT_PROFILE" \
    PARKIO_GATEWAY_URL="${PARKIO_GATEWAY_URL:-$(parkio_default_gateway_url)}" \
    PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED="${PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED:-0}" \
    "$ROOT/scripts/smoke-hosted-beta.sh" | tee "$ARTIFACT_DIR/smoke-${GIT_SHA:0:12}.log"
fi

cp "$MANIFEST_PATH" "$ARTIFACT_DIR/current.json"
echo "Deployed commit: $GIT_SHA"
echo "Manifest: $MANIFEST_PATH"
echo "Current pointer: $ARTIFACT_DIR/current.json"
echo "Rollback command:"
jq -r .rollbackCommand "$MANIFEST_PATH"
