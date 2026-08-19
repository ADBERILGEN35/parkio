#!/usr/bin/env bash
#
# Parkio controlled-invite production foundation deploy.
#
# Builds images from the current git SHA, deploys the managed-DB profile with
# full observability, waits for health, runs smoke checks, and writes a
# deployment manifest. This script does NOT migrate real production data and is
# intended for the isolated invite-production foundation only.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib/deploy-common.sh"
source "$ROOT/scripts/lib/disk-space.sh"

ENV_FILE="${PARKIO_ENV_FILE:-docker/.env.invite-production}"
ARTIFACT_DIR="${PARKIO_DEPLOY_ARTIFACT_DIR:-deploy-artifacts/invite-production}"
OPERATOR="${PARKIO_DEPLOY_OPERATOR:-${USER:-unknown}}"
ALLOW_DIRTY=0
DRY_RUN=0
SKIP_SMOKE=0
HEALTH_TIMEOUT="${PARKIO_DEPLOY_HEALTH_TIMEOUT:-1200}"
EXPECTED_SHA="${PARKIO_EXPECTED_GIT_SHA:-}"
NODE_BINARY="${PARKIO_NODE_BINARY:-node}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --artifact-dir) ARTIFACT_DIR="${2:-}"; shift 2 ;;
    --allow-dirty) ALLOW_DIRTY=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --skip-smoke) SKIP_SMOKE=1; shift ;;
    --expected-sha) EXPECTED_SHA="${2:-}"; shift 2 ;;
    --operator) OPERATOR="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

cd "$ROOT"

if [ "$DRY_RUN" -eq 1 ]; then
  PARKIO_NODE_BINARY="$NODE_BINARY" "$ROOT/scripts/verify-node-runtime.sh"
else
  PARKIO_NODE_BINARY="$NODE_BINARY" "$ROOT/scripts/verify-invite-production-toolchain.sh"
fi

CURRENT_SHA="$(parkio_git_sha)"
if [ -n "$EXPECTED_SHA" ]; then
  if ! [[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]]; then
    echo "ERROR: --expected-sha must be a full lowercase 40-character commit SHA." >&2
    exit 2
  fi
  if [ "$CURRENT_SHA" != "$EXPECTED_SHA" ]; then
    echo "ERROR: checkout SHA does not match the explicitly requested deploy SHA." >&2
    echo "       expected=$EXPECTED_SHA" >&2
    echo "       actual=$CURRENT_SHA" >&2
    exit 2
  fi
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: env file not found: $ENV_FILE" >&2
  echo "       Copy docker/.env.invite-production.example first." >&2
  exit 2
fi

echo "Running invite-production preflight..."
if ! "$ROOT/scripts/preflight-invite-production.sh" --env-file "$ENV_FILE" --skip-compose; then
  echo "ERROR: invite-production preflight failed." >&2
  exit 3
fi

if parkio_git_is_dirty && [ "$ALLOW_DIRTY" -ne 1 ]; then
  echo "ERROR: working tree is dirty. Commit/stash changes, or pass --allow-dirty." >&2
  git status --short >&2
  exit 2
fi

GIT_SHA="$CURRENT_SHA"
BRANCH="$(parkio_git_branch)"
IMAGE_TAG="${PARKIO_IMAGE_TAG:-$(parkio_image_tag_for_sha "$GIT_SHA")}"
CREATED="${PARKIO_IMAGE_CREATED:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
VERSION="${PARKIO_IMAGE_VERSION:-0.0.1-SNAPSHOT}"
if git -C "$ROOT" describe --tags --exact-match HEAD >/dev/null 2>&1; then
  VERSION="$(git -C "$ROOT" describe --tags --exact-match HEAD)"
fi

parkio_configure_deployment_profile "$ENV_FILE"
if [ "$PARKIO_DEPLOYMENT_PROFILE" != "invite-production" ]; then
  echo "ERROR: env file must resolve PARKIO_DEPLOYMENT_PROFILE=invite-production" >&2
  exit 2
fi

# Fail closed on the acceptance target BEFORE building or starting anything, so a
# bad target can never reach a running stack (PROD-DEPLOY-01A / D1).
parkio_validate_dark_gateway_url "${PARKIO_GATEWAY_URL:-$(parkio_default_gateway_url)}" || exit 2

# Fail closed on public ACME BEFORE starting anything (PROD-DEPLOY-01A-R4 / D3).
# api/app/media.parkio.dev still resolve to hosted-beta, so a dark stack that
# starts Caddy would emit ACME orders it cannot validate — an externally visible
# side effect that also burns the failed-validation budget PROD-DEPLOY-01B needs.
# A real deploy demands the merged-model proof: on the production runner an
# unresolvable model means the guard cannot see what will start. --dry-run runs
# against a synthetic env whose model need not resolve, and its static
# assertions already catch a reintroduced ACME edge.
acme_guard_args=(--env-file "$ENV_FILE")
if [ "$DRY_RUN" -ne 1 ]; then
  acme_guard_args+=(--require-model)
fi
if ! "$ROOT/scripts/assert-invite-dark-acme-isolation.sh" "${acme_guard_args[@]}"; then
  echo "ERROR: dark ACME isolation failed; refusing to start the invite-production stack." >&2
  exit 3
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

echo "=== Parkio invite-production deploy ==="
echo "gitSha=$GIT_SHA"
echo "branch=$BRANCH"
echo "imageTag=$IMAGE_TAG"
echo "envFile=$ENV_FILE"
echo "deploymentProfile=$PARKIO_DEPLOYMENT_PROFILE"
echo "composeFiles=$PARKIO_COMPOSE_FILES"
echo "runtimeServices=${PARKIO_RUNTIME_SERVICES[*]}"
echo "disabledServices=${PARKIO_DISABLED_SERVICES[*]}"
echo "manifest=$MANIFEST_PATH"
echo "dryRun=$DRY_RUN"

echo "Checking free disk capacity..."
if ! parkio_require_free_disk /; then
  echo "ERROR: disk preflight failed." >&2
  exit 3
fi

echo "Rendering compose config..."
mkdir -p "$ARTIFACT_DIR"
parkio_compose "$ENV_FILE" config --quiet
COMPOSE_STRUCTURE="$ARTIFACT_DIR/compose-structure.json"
COMPOSE_STRUCTURE_TMP="$ARTIFACT_DIR/.compose-structure.json.tmp"
rm -f -- "$COMPOSE_STRUCTURE_TMP"
if ! parkio_compose "$ENV_FILE" config --format json \
  | "$NODE_BINARY" "$ROOT/scripts/lib/sanitize-compose-config.mjs" > "$COMPOSE_STRUCTURE_TMP"; then
  rm -f -- "$COMPOSE_STRUCTURE_TMP"
  echo "ERROR: failed to produce secret-free Compose structural evidence." >&2
  exit 3
fi
mv -f -- "$COMPOSE_STRUCTURE_TMP" "$COMPOSE_STRUCTURE"

parkio_write_manifest "$MANIFEST_PATH" "deploy" "$OPERATOR" "$ENV_FILE" \
  "$IMAGE_TAG" "$GIT_SHA" "$BRANCH" "$CREATED" "$VERSION" "$PREVIOUS" "$COMPOSE_STRUCTURE"

python3 "$ROOT/scripts/assert-invite-production-artifacts-safe.py" \
  --env-file "$ENV_FILE" "$ARTIFACT_DIR"

if [ "$DRY_RUN" -eq 1 ]; then
  echo "DRY-RUN: would build images, start invite-production stack, wait healthy, and smoke it."
  echo "Manifest written: $MANIFEST_PATH"
  jq -r .rollbackCommand "$MANIFEST_PATH"
  exit 0
fi

# Operational payload for the scheduled backup (PROD-DEPLOY-01A-R3 / D2). This
# runs BEFORE the stack starts so the backup runtime is versioned alongside the
# deployed commit, and it never enables the timer — enablement is an explicit
# backup-acceptance decision, not a deploy side effect.
SCHEDULER_INSTALLER="$ROOT/scripts/azure/install-invite-production-backup-scheduler.sh"
if [ "${PARKIO_INSTALL_BACKUP_SCHEDULER:-0}" = "1" ]; then
  echo "Installing invite-production backup scheduler payload (timer stays disabled)..."
  if [ "$(id -u)" -eq 0 ]; then
    "$SCHEDULER_INSTALLER"
  elif sudo -n true 2>/dev/null; then
    sudo -n "$SCHEDULER_INSTALLER"
  else
    echo "ERROR: PARKIO_INSTALL_BACKUP_SCHEDULER=1 requires root or passwordless sudo." >&2
    exit 3
  fi
  python3 "$ROOT/scripts/assert-invite-production-artifacts-safe.py" \
    --env-file "$ENV_FILE" /opt/parkio/invite-production
else
  echo "Backup scheduler payload not installed (PARKIO_INSTALL_BACKUP_SCHEDULER unset)."
  echo "  Install it as root during backup acceptance:"
  echo "    $SCHEDULER_INSTALLER"
  echo "  Then enable the timer explicitly: $SCHEDULER_INSTALLER --enable"
fi

echo "Building images from current source..."
for svc in "${PARKIO_APP_SERVICES[@]}"; do
  echo "=== Building $svc ==="
  parkio_compose "$ENV_FILE" build "$svc"
done

echo "Tagging invite-production-latest..."
for svc in "${PARKIO_APP_SERVICES[@]}"; do
  docker tag "$(parkio_image_ref "$svc" "$IMAGE_TAG")" "$(parkio_image_ref "$svc" "invite-production-latest")"
done

# Rewrite after build so the manifest contains immutable local image IDs as
# well as the SHA-derived tags. The pre-build write remains useful for dry-run.
parkio_write_manifest "$MANIFEST_PATH" "deploy" "$OPERATOR" "$ENV_FILE" \
  "$IMAGE_TAG" "$GIT_SHA" "$BRANCH" "$CREATED" "$VERSION" "$PREVIOUS" "$COMPOSE_STRUCTURE"

echo "Starting invite-production stack..."
parkio_compose_up "$ENV_FILE"

echo "Waiting for health checks..."
parkio_wait_healthy "$ENV_FILE" "$HEALTH_TIMEOUT"

if [ "$SKIP_SMOKE" -ne 1 ]; then
  echo "Running smoke checks..."
  PARKIO_ENV_FILE="$ENV_FILE" \
    PARKIO_DEPLOYMENT_PROFILE="$PARKIO_DEPLOYMENT_PROFILE" \
    PARKIO_GATEWAY_URL="${PARKIO_GATEWAY_URL:-$(parkio_default_gateway_url)}" \
    PARKIO_EXPECTED_GIT_SHA="$GIT_SHA" \
    PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED="${PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED:-1}" \
    "$ROOT/scripts/smoke-hosted-beta.sh" | tee "$ARTIFACT_DIR/smoke-${GIT_SHA:0:12}.log"
fi

python3 "$ROOT/scripts/assert-invite-production-artifacts-safe.py" \
  --env-file "$ENV_FILE" "$ARTIFACT_DIR"

cp "$MANIFEST_PATH" "$ARTIFACT_DIR/current.json"
echo "Invite-production foundation deployed at commit: $GIT_SHA"
echo "Manifest: $MANIFEST_PATH"
echo "Current pointer: $ARTIFACT_DIR/current.json"
echo "Rollback command:"
jq -r .rollbackCommand "$MANIFEST_PATH"
