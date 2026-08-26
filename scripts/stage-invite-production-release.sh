#!/usr/bin/env bash
#
# Stage an invite-production runtime release (PROD-DEPLOY-01A-R8).
#
# Copies the non-secret runtime config of an exact checkout into an immutable,
# SHA-addressed release under the stable runtime root, verifies it is consumable
# by non-root container UIDs, and optionally activates it.
#
# Usage:
#   scripts/stage-invite-production-release.sh --sha <40hex> [--source DIR]
#       [--root DIR] [--activate] [--prune] [--keep N]

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib/runtime-release.sh"

SOURCE_DIR="$ROOT"
SHA=""
ACTIVATE=0
PRUNE=0
PRUNE_ONLY=0
KEEP="${PARKIO_RELEASE_KEEP:-5}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --sha) SHA="${2:-}"; shift 2 ;;
    --source) SOURCE_DIR="${2:-}"; shift 2 ;;
    --root) PARKIO_RUNTIME_ROOT="${2:-}"; export PARKIO_RUNTIME_ROOT; shift 2 ;;
    --activate) ACTIVATE=1; shift ;;
    --prune) PRUNE=1; shift ;;
    --prune-only) PRUNE=1; PRUNE_ONLY=1; shift ;;
    --keep) KEEP="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

[ -n "$SHA" ] || { echo "ERROR: --sha is required" >&2; exit 2; }
parkio_validate_sha "$SHA"

RUNTIME_ROOT="$(parkio_runtime_root)"
RELEASES="$(parkio_releases_dir)"

PARENT_ROOT="$(dirname "$RUNTIME_ROOT")"
if [ -d "$PARENT_ROOT" ] && [ ! -x "$PARENT_ROOT" ]; then
  cat >&2 <<MSG
ERROR: runtime root is unreachable because its parent is not traversable: $PARENT_ROOT
       observed=$(stat -c 'mode=%a owner=%U:%G' "$PARENT_ROOT" 2>/dev/null || echo unavailable)
       required: $PARENT_ROOT must be mode 0755 so parkio-runner can stage releases unde
       $RUNTIME_ROOT without sudo (see scripts/azure/install-invite-production-runtime-root.sh).
MSG
  exit 3
fi
if [ ! -d "$RUNTIME_ROOT" ]; then
  cat >&2 <<MSG
ERROR: runtime root does not exist: $RUNTIME_ROOT

  The stable runtime root is provisioned once, as root, by
  scripts/azure/install-invite-production-runner.sh. The deploy runner has no
  sudo by design, so it must never create or re-permission this path itself.
MSG
  exit 3
fi
if [ ! -w "$RUNTIME_ROOT" ]; then
  echo "ERROR: runtime root is not writable by $(id -un): $RUNTIME_ROOT" >&2
  echo "       observed=$(stat -c 'mode=%a owner=%U:%G' "$RUNTIME_ROOT" 2>/dev/null || echo unavailable)" >&2
  exit 3
fi

RELEASE="$(parkio_release_dir "$SHA")"
parkio_assert_release_is_stable "$RELEASE"

# The runner service runs with PrivateTmp=yes, which gives it a private /tmp
# mount namespace. dockerd lives in the host namespace, so a release staged
# under /tmp is INVISIBLE to it: every bind mount would silently auto-create an
# empty directory at the target instead of binding the config. That is exactly
# how acceptance run 32340585156 produced six misleading "cannot read" failures.
# Fail closed rather than deploy a runtime whose config the daemon cannot see.
case "$RUNTIME_ROOT" in
  /tmp/*|/var/tmp/*)
    if [ "${PARKIO_ALLOW_EPHEMERAL_ROOT:-0}" != "1" ]; then
      echo "ERROR: refusing to stage a runtime release under $RUNTIME_ROOT" >&2
      echo "       PrivateTmp=yes makes this path invisible to dockerd." >&2
      exit 3
    fi
    ;;
esac

install -d -m "$PARKIO_RELEASE_DIR_MODE" "$RELEASES"

if [ "$PRUNE_ONLY" -eq 0 ]; then
  echo "Staging invite-production runtime release..."
  echo "  source=$SOURCE_DIR"
  echo "  sha=$SHA"
  echo "  release=$RELEASE"
  parkio_stage_runtime_release "$SOURCE_DIR" "$SHA" >/dev/null
  parkio_assert_release_readable "$SHA"
fi

# The staged release must carry every bind-mount source the invite-production
# runtime consumes, or a container fails at start instead of at staging.
missing=0
if [ "$PRUNE_ONLY" -eq 1 ]; then required_list=(); else required_list=(1); fi
for required in \
  docker/docker-compose.yml \
  docker/docker-compose.apps.yml \
  docker/docker-compose.images.yml \
  docker/docker-compose.hosted-beta.yml \
  docker/docker-compose.managed-db.yml \
  docker/docker-compose.invite-dark.yml \
  docker/prometheus/prometheus.yml \
  docker/prometheus/alerts.yml \
  docker/alertmanager/alertmanager.yml \
  docker/alertmanager/render-config.sh \
  docker/loki/loki.yml \
  docker/tempo/tempo.yml \
  docker/blackbox/blackbox.yml \
  docker/grafana/provisioning ; do
  [ "${#required_list[@]}" -eq 0 ] && break
  if [ ! -e "$RELEASE/$required" ]; then
    echo "ERROR: staged release is missing $required" >&2
    missing=1
  fi
done
[ "$missing" -eq 0 ] || exit 3

# No resolved secret material may ever land in a release.
if find "$RELEASE" -name '.env' -o -name '.env.*' | grep -q .; then
  echo "ERROR: staged release contains env material" >&2
  exit 3
fi

if [ "$ACTIVATE" -eq 1 ]; then
  parkio_activate_release "$SHA"
fi

if [ "$PRUNE" -eq 1 ]; then
  if ! [[ "$KEEP" =~ ^[0-9]+$ ]] || [ "$KEEP" -lt 2 ]; then
    echo "ERROR: --keep must be an integer >= 2 (active + rollback)" >&2
    exit 2
  fi
  active="$(parkio_active_release_sha 2>/dev/null || true)"
  # Never reclaim a release that a running container still bind-mounts.
  in_use="$(docker ps -q 2>/dev/null | xargs -r docker inspect \
    -f '{{range .Mounts}}{{println .Source}}{{end}}' 2>/dev/null \
    | sed -n "s#^$RELEASES/\([0-9a-f]\{40\}\)/.*#\1#p" | sort -u || true)"
  kept=0
  while IFS= read -r dir; do
    sha_dir="$(basename "$dir")"
    [[ "$sha_dir" =~ ^[0-9a-f]{40}$ ]] || continue
    if [ "$sha_dir" = "$active" ] || [ "$sha_dir" = "$SHA" ] \
       || grep -qx "$sha_dir" <<<"$in_use"; then
      continue
    fi
    kept=$((kept + 1))
    if [ "$kept" -ge "$KEEP" ]; then
      echo "Pruning superseded release $sha_dir"
      rm -rf -- "$dir"
    fi
  done < <(find "$RELEASES" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' \
             | sort -rn | cut -d' ' -f2-)
fi

if [ "$PRUNE_ONLY" -eq 1 ]; then echo "Release retention pass complete."; else echo "Staged release: $RELEASE"; fi
