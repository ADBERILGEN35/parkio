#!/usr/bin/env bash
#
# One-time migration of legacy workspace bind mounts (PROD-DEPLOY-01A-R8.1).
#
# The failed R7 deploy (run 32286702813) started the stack straight out of
# /opt/actions-runner/parkio-invite-production/_work/parkio/parkio/source-32286702813-1.
# Cleanup then deleted that checkout, so the surviving containers bind-mount
# paths that no longer hold real files — Docker re-created them as empty
# directories. Two of them are still running and otherwise healthy:
#
#   parkio-node-exporter      .../docker/prometheus/textfile -> /textfile-collector
#   parkio-blackbox-exporter  .../docker/blackbox/blackbox.yml -> /etc/blackbox/blackbox.yml
#
# They must be rebound onto the stable SHA-addressed release before the runner
# workspace can be reclaimed and before cleanup can prove zero _work mounts.
#
# Scope discipline: this recreates ONLY those two services, with --no-deps, from
# the stable release. It never runs `compose down`, never removes a volume,
# never touches a database, and never restarts a healthy application service.
#
# Usage:
#   PARKIO_ENV_FILE=<rendered env> scripts/migrate-invite-production-workspace-mounts.sh \
#       --sha <40hex> [--dry-run]

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib/deploy-common.sh"
source "$ROOT/scripts/lib/runtime-release.sh"

# Exactly the services whose mounts are known-stale. Deliberately hard-coded:
# this is a one-time migration, not a general restart tool.
MIGRATE_SERVICES=(node-exporter blackbox-exporter)

ENV_FILE="${PARKIO_ENV_FILE:-}"
SHA=""
DRY_RUN=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --sha) SHA="${2:-}"; shift 2 ;;
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

[ -n "$SHA" ] || { echo "ERROR: --sha is required" >&2; exit 2; }
parkio_validate_sha "$SHA"
[ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ] || { echo "ERROR: a rendered env file is required" >&2; exit 2; }

parkio_configure_deployment_profile "$ENV_FILE"
if [ "$PARKIO_DEPLOYMENT_PROFILE" != "invite-production" ]; then
  echo "ERROR: this migration is invite-production only (got '$PARKIO_DEPLOYMENT_PROFILE')" >&2
  exit 2
fi

RELEASE="$(parkio_release_dir "$SHA")"
if [ ! -d "$RELEASE" ]; then
  echo "ERROR: no stable runtime release staged for $SHA: $RELEASE" >&2
  echo "       Stage it first: scripts/stage-invite-production-release.sh --sha $SHA" >&2
  exit 3
fi
parkio_assert_release_is_stable "$RELEASE" || exit 3
parkio_assert_release_readable "$SHA" >/dev/null || exit 3
export PARKIO_COMPOSE_BASE_DIR="$RELEASE"
export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-$(parkio_image_tag_for_sha "$SHA")}"
export PARKIO_GIT_SHA="$SHA"

echo "=== Legacy workspace bind-mount migration ==="
echo "release=$RELEASE"
echo "services=${MIGRATE_SERVICES[*]}"

stale_before() {
  local cid name
  for cid in $(docker ps -q 2>/dev/null || true); do
    name="$(docker inspect -f '{{.Name}}' "$cid" | sed 's|^/||')"
    docker inspect -f '{{range .Mounts}}{{println .Source}}{{end}}' "$cid" 2>/dev/null \
      | grep -q '/_work/' && echo "$name"
  done | sort -u
}

echo "--- running containers bind-mounting _work (before) ---"
before="$(stale_before)"
[ -n "$before" ] && echo "$before" || echo "(none)"

# Refuse to migrate anything we did not explicitly scope. If a container other
# than the two known exporters is affected, that is new information and needs a
# decision, not an automatic restart.
unexpected=""
while IFS= read -r name; do
  [ -n "$name" ] || continue
  case "$name" in
    parkio-node-exporter|parkio-blackbox-exporter) ;;
    *) unexpected="$unexpected $name" ;;
  esac
done <<<"$before"
if [ -n "$unexpected" ]; then
  echo "ERROR: unexpected running containers bind-mount _work:$unexpected" >&2
  echo "       Refusing to act outside the audited migration scope." >&2
  exit 3
fi

if [ "$DRY_RUN" -eq 1 ]; then
  echo "DRY-RUN: would run, from $RELEASE:"
  echo "  docker compose --env-file <env> $PARKIO_COMPOSE_FILES up -d --no-deps --force-recreate ${MIGRATE_SERVICES[*]}"
  exit 0
fi

echo "--- recreating scoped services from the stable release ---"
parkio_compose "$ENV_FILE" up -d --no-deps --force-recreate "${MIGRATE_SERVICES[@]}"

echo "--- running containers bind-mounting _work (after) ---"
after="$(stale_before)"
if [ -n "$after" ]; then
  echo "$after"
  echo "ERROR: running containers still bind-mount the runner workspace." >&2
  exit 3
fi
echo "(none)"

for svc in "${MIGRATE_SERVICES[@]}"; do
  cid="$(parkio_compose "$ENV_FILE" ps -q "$svc" 2>/dev/null || true)"
  [ -n "$cid" ] || { echo "ERROR: $svc is not running after migration" >&2; exit 3; }
  echo "--- $svc ---"
  docker inspect -f '  state={{.State.Status}} restarts={{.RestartCount}}' "$cid"
  docker inspect -f '{{range .Mounts}}  mount {{.Source}} -> {{.Destination}}{{println}}{{end}}' "$cid" \
    | grep -v '^\s*$' || true
done

echo "Legacy workspace bind-mount migration passed."
