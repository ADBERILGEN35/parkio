#!/usr/bin/env bash
#
# Parkio — rollback hosted-beta to a previous deploy manifest.
#
# Does NOT rebuild images. Requires the previous sha-* images to exist locally
# (or in a registry you have already pulled).
#
# The default hosted-beta profile renders docker/compose.production.files exactly (CL-F12). For
# each service that list builds, it points the model's image name back at the sha-* tag the target
# manifest records, then starts the stack with `up --no-build`. Digest-pinned services keep the
# pins of this checkout: a pin is rolled back by reverting its pin file, not by this script.
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
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
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
MANIFEST_PROFILE="$(jq -r '.deploymentProfile // "hosted-beta"' "$MANIFEST")"
CREATED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

if [ -z "$IMAGE_TAG" ] || [ "$IMAGE_TAG" = "null" ]; then
  echo "ERROR: manifest missing imageTag" >&2
  exit 2
fi

if [ "$USE_HOSTED_BETA" -eq 1 ]; then
  parkio_configure_deployment_profile "$ENV_FILE"
  parkio_warn_deprecated_production_path
  if [ "$PARKIO_DEPLOYMENT_PROFILE" != "$MANIFEST_PROFILE" ]; then
    echo "ERROR: rollback profile '$PARKIO_DEPLOYMENT_PROFILE' does not match manifest profile '$MANIFEST_PROFILE'." >&2
    exit 2
  fi
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

OUT_NAME="rollback-to-${GIT_SHA:0:12}-$(date -u +%Y%m%dT%H%M%SZ).json"
OUT_PATH="$ARTIFACT_DIR/$OUT_NAME"
mkdir -p "$ARTIFACT_DIR"

if [ "$PARKIO_DEPLOYMENT_PROFILE" = "hosted-beta" ]; then
  # One plan for the manifest and the re-pointing (CL-F12).
  PARKIO_HOSTED_BETA_IMAGE_PLAN="$(parkio_hosted_beta_image_plan "$ENV_FILE")"
  export PARKIO_HOSTED_BETA_IMAGE_PLAN
  # The model always comes from this checkout. Say so when the target deploy rendered other files.
  TARGET_COMPOSE_FILES="$(jq -c '.composeFiles // []' "$MANIFEST")"
  CURRENT_COMPOSE_FILES="$(parkio_compose_files_json | jq -c .)"
  if [ "$TARGET_COMPOSE_FILES" != "$CURRENT_COMPOSE_FILES" ]; then
    echo "NOTE: the target deploy rendered $TARGET_COMPOSE_FILES;" >&2
    echo "      this rollback renders $CURRENT_COMPOSE_FILES, with this checkout's pins and settings." >&2
  fi
fi

echo "=== Parkio hosted-beta rollback ==="
echo "targetManifest=$MANIFEST"
echo "imageTag=$IMAGE_TAG"
echo "gitSha=$GIT_SHA"
echo "deploymentProfile=$PARKIO_DEPLOYMENT_PROFILE"
echo "composeFiles=$PARKIO_COMPOSE_FILES"
echo "runtimeServices=${PARKIO_RUNTIME_SERVICES[*]:-all}"
echo "disabledServices=${PARKIO_DISABLED_SERVICES[*]:-none}"
echo "dryRun=$DRY_RUN"
if [ "$PARKIO_DEPLOYMENT_PROFILE" = "hosted-beta" ]; then
  echo "imagePlan (kind, service, image, platform):"
  printf '%s\n' "$PARKIO_HOSTED_BETA_IMAGE_PLAN" | sed 's/^/  /'
fi

parkio_write_manifest "$OUT_PATH" "rollback" "$OPERATOR" "$ENV_FILE" \
  "$IMAGE_TAG" "$GIT_SHA" "$BRANCH" "$CREATED" "$VERSION" "$PREVIOUS"

if [ "$DRY_RUN" -eq 1 ]; then
  if [ "$PARKIO_DEPLOYMENT_PROFILE" = "hosted-beta" ]; then
    echo "DRY-RUN: would point each built service in the image plan at its tag in the target manifest,"
    echo "  check the pinned images, and run parkio_compose_up $ENV_FILE (--no-build)"
  else
    echo "DRY-RUN: would verify local images and run parkio_compose_up $ENV_FILE (no --build)"
  fi
  echo "Rollback manifest: $OUT_PATH"
  exit 0
fi

# Image/config rollback is not a DB restore (PA-12 / G03, F-INV-3). Refuse when the live schema, as
# the deployed release's recorded manifest describes it, has migrations the target lacks. That
# record lives outside any checkout (parkio_deployed_manifest_path); without it the rollback is
# refused. This checkout's deploy-artifacts/current.json is not used.
source "$ROOT/scripts/lib/runtime-release.sh"
DEPLOYED_MANIFEST="$(parkio_deployed_manifest_path)"
echo "deployedManifest=$DEPLOYED_MANIFEST"
parkio_assert_rollback_schema_compatible "$MANIFEST" "$DEPLOYED_MANIFEST" || exit 3

# Verify images exist locally (live rollback only)
missing=0
REPOINT=()
if [ "$PARKIO_DEPLOYMENT_PROFILE" = "hosted-beta" ]; then
  # CL-F12: every service the model builds needs the image the target deploy recorded for it. A
  # target that did not build such a service (it ran a pin then) cannot be rolled back here.
  while IFS=$'\t' read -r kind svc image platform; do
    case "$kind" in
      built)
        expected="$(parkio_image_ref "$svc" "$IMAGE_TAG")"
        recorded="$(jq -r --arg svc "$svc" '.images[$svc] // ""' "$MANIFEST")"
        if [ "$recorded" != "$expected" ]; then
          echo "ERROR: the target manifest records '${recorded}' for $svc, not $expected: that deploy did not build $svc" >&2
          missing=1
        elif ! docker image inspect "$recorded" >/dev/null 2>&1; then
          echo "ERROR: image not found locally: $recorded" >&2
          missing=1
        else
          REPOINT+=("$recorded" "$image")
        fi
        ;;
      pinned)
        echo "$svc keeps its pin $image from this checkout (revert its pin file to roll a pin back)"
        parkio_ensure_pinned_image "$svc" "$image" "$platform" || missing=1
        ;;
    esac
  done <<< "$PARKIO_HOSTED_BETA_IMAGE_PLAN"
else
  for svc in "${PARKIO_APP_SERVICES[@]}"; do
    ref="$(parkio_image_ref "$svc" "$IMAGE_TAG")"
    if ! docker image inspect "$ref" >/dev/null 2>&1; then
      echo "ERROR: image not found locally: $ref" >&2
      missing=1
    fi
  done
fi
if [ "$missing" -ne 0 ]; then
  echo "ERROR: cannot rollback; build or pull the previous images first." >&2
  exit 2
fi

# Roll back against the STABLE runtime release for the target commit, never the
# Actions checkout (PROD-DEPLOY-01A-R8 / DEFECT-2). Releases are SHA-addressed
# and retained precisely so a rollback never has to reconstruct config from a
# workspace that has already been cleaned up. hosted-beta has no runtime root and
# is unaffected.
if [ "${PARKIO_DEPLOYMENT_PROFILE:-}" = "invite-production" ]; then
  source "$ROOT/scripts/lib/runtime-release.sh"
  ROLLBACK_RELEASE="$(parkio_release_dir "$GIT_SHA")"
  if [ ! -d "$ROLLBACK_RELEASE" ]; then
    echo "ERROR: no stable runtime release staged for $GIT_SHA" >&2
    echo "       expected: $ROLLBACK_RELEASE" >&2
    echo "       Rollback refuses to run the previous images against another" >&2
    echo "       commit's configuration." >&2
    exit 3
  fi
  parkio_assert_release_is_stable "$ROLLBACK_RELEASE" || exit 3
  parkio_assert_release_readable "$GIT_SHA" >/dev/null || exit 3
  export PARKIO_COMPOSE_BASE_DIR="$ROLLBACK_RELEASE"
  echo "composeBaseDir=$PARKIO_COMPOSE_BASE_DIR"
  parkio_activate_release "$GIT_SHA"
fi

if [ "$PARKIO_DEPLOYMENT_PROFILE" = "hosted-beta" ]; then
  echo "Pointing the built services at the target's images..."
  for ((i = 0; i < ${#REPOINT[@]}; i += 2)); do
    echo "  ${REPOINT[i + 1]} -> ${REPOINT[i]}"
    docker tag "${REPOINT[i]}" "${REPOINT[i + 1]}"
  done
  export PARKIO_COMPOSE_UP_NO_BUILD=1
fi

# F-INV-3: the target release is about to start; record its manifest first.
parkio_record_deployed_manifest "$MANIFEST"
echo "Starting previous images (no rebuild)..."
parkio_compose_up "$ENV_FILE"

echo "Waiting for health checks..."
parkio_wait_healthy "$ENV_FILE" "$HEALTH_TIMEOUT"

if [ "$SKIP_SMOKE" -ne 1 ]; then
  echo "Running smoke checks..."
  PARKIO_ENV_FILE="$ENV_FILE" \
    PARKIO_DEPLOYMENT_PROFILE="$PARKIO_DEPLOYMENT_PROFILE" \
    PARKIO_GATEWAY_URL="${PARKIO_GATEWAY_URL:-$(parkio_default_gateway_url)}" \
    PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED="${PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED:-0}" \
    "$ROOT/scripts/smoke-hosted-beta.sh" | tee "$ARTIFACT_DIR/smoke-rollback-${GIT_SHA:0:12}.log"
fi

cp "$OUT_PATH" "$ARTIFACT_DIR/current.json"
echo "Rolled back to commit: $GIT_SHA"
echo "Manifest: $OUT_PATH"
