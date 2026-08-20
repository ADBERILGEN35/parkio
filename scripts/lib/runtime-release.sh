#!/usr/bin/env bash
#
# Parkio stable runtime releases (PROD-DEPLOY-01A-R8 / DEFECT-1 + DEFECT-2).
#
# Why this exists
# ---------------
# The invite-production deploy used to run Compose straight out of the Actions
# checkout, so every relative bind mount (`./prometheus/prometheus.yml`, ...)
# resolved under
#
#     /opt/actions-runner/parkio-invite-production/_work/parkio/parkio/source-<run>-<attempt>/docker
#
# That broke the deploy twice over:
#
#   DEFECT-1  The runner service is hardened with UMask=0077, so the runner
#             creates `_work`, `_work/<owner>`, `_work/<owner>/<repo>` as 0700.
#             Containers that drop to a non-root UID (prometheus/alertmanager
#             run as `nobody`, loki/tempo as 10001, grafana as 472) cannot
#             *traverse* that chain and every config open() returns EACCES.
#             Root-running containers were unaffected, which is exactly why only
#             the observability stack died.
#
#   DEFECT-2  The job's own cleanup step deletes the checkout. The long-lived
#             runtime therefore bind-mounted its config out of a directory that
#             the same job destroyed: Docker then re-created the vanished mount
#             sources as empty *directories*, so the containers could never come
#             back even after a permission fix.
#
# The fix is to stop deploying from the workspace at all. Non-secret runtime
# config is staged into an immutable, SHA-addressed release under a stable root
# that (a) outlives the Actions job and (b) is traversable by the non-root UIDs
# that actually have to read it:
#
#     <root>/releases/<sha>/docker/...      staged config, 0755 dirs / 0644 files
#     <root>/current -> releases/<sha>      atomic activation symlink
#
# The runner's UMask=0077 hardening stays exactly as it is — every mode here is
# set explicitly with `install`, so the umask cannot silently re-tighten a file
# that a container must read. Nothing under the release root is secret-bearing:
# secrets stay in the per-job tmpfs env file and are interpolated by Compose at
# `up` time, never written into a release.

set -euo pipefail

PARKIO_RUNTIME_ROOT_DEFAULT="/opt/parkio/invite-production"

# Directory modes must let a non-root container UID traverse; file modes must
# let it read. These are applied explicitly (never inherited from the umask).
PARKIO_RELEASE_DIR_MODE="0755"
PARKIO_RELEASE_FILE_MODE="0644"
PARKIO_RELEASE_EXEC_MODE="0755"

parkio_runtime_root() {
  echo "${PARKIO_RUNTIME_ROOT:-$PARKIO_RUNTIME_ROOT_DEFAULT}"
}

parkio_releases_dir() {
  echo "$(parkio_runtime_root)/releases"
}

parkio_release_dir() {
  local sha="$1"
  echo "$(parkio_releases_dir)/$sha"
}

parkio_current_link() {
  echo "$(parkio_runtime_root)/current"
}

parkio_validate_sha() {
  local sha="$1"
  if ! [[ "$sha" =~ ^[0-9a-f]{40}$ ]]; then
    echo "ERROR: release id must be a full lowercase 40-character commit SHA (got '$sha')" >&2
    return 2
  fi
}

# Runtime config that must never carry resolved secret values. `.env*` is
# excluded outright: the deploy renders secrets into a per-job tmpfs file and
# passes it with --env-file, so no env material has any business in a release.
parkio_release_is_excluded() {
  case "${1##*/}" in
    .env|.env.*) return 0 ;;
  esac
  return 1
}

# Stage the non-secret runtime config of a checkout into an immutable release.
#   $1 repo root (the ephemeral Actions checkout)
#   $2 commit SHA
parkio_stage_runtime_release() {
  local repo="$1" sha="$2"
  local release staged rel src dst mode
  parkio_validate_sha "$sha" || return 2
  [ -d "$repo/docker" ] || { echo "ERROR: no docker/ directory in $repo" >&2; return 2; }

  release="$(parkio_release_dir "$sha")"
  staged="$release.staging.$$"

  # A release is immutable and content-addressed by commit, so an existing one is
  # by definition already correct. Never delete and re-create it: a re-deploy or
  # a prune pass would otherwise yank the bind-mount sources out from under
  # containers that are currently running against this very release — exactly the
  # failure mode (DEFECT-2) this module exists to prevent.
  if [ -d "$release" ]; then
    echo "$release"
    return 0
  fi

  rm -rf -- "$staged"
  install -d -m "$PARKIO_RELEASE_DIR_MODE" "$staged"

  # Only tracked files: a clean checkout must never leak local scratch, build
  # logs, or an operator's real docker/.env into a production release.
  while IFS= read -r -d '' rel; do
    parkio_release_is_excluded "$rel" && continue
    src="$repo/$rel"
    [ -f "$src" ] || continue
    dst="$staged/$rel"
    install -d -m "$PARKIO_RELEASE_DIR_MODE" "$(dirname "$dst")"
    mode="$PARKIO_RELEASE_FILE_MODE"
    # Alertmanager's entrypoint is a mounted shell script; it must stay
    # executable for the container's non-root UID.
    case "$rel" in *.sh) mode="$PARKIO_RELEASE_EXEC_MODE" ;; esac
    install -m "$mode" "$src" "$dst"
  done < <(git -C "$repo" ls-files -z -- docker)

  # `context: ..` in docker-compose.apps.yml resolves to the release root. It is
  # never built from here (builds run against the checkout), but the path must
  # exist and be traversable for the model to load.
  #
  # rename(2) is atomic, so a concurrent reader sees either no release or the
  # complete one — never a half-staged tree.
  if ! mv -T --no-clobber -- "$staged" "$release" 2>/dev/null; then
    rm -rf -- "$staged"
    [ -d "$release" ] || { echo "ERROR: failed to publish release $release" >&2; return 3; }
  fi
  chmod "$PARKIO_RELEASE_DIR_MODE" "$release"
  echo "$release"
}

# Atomically repoint `current` at a staged release. rename(2) over an existing
# symlink is atomic, so no observer ever sees a missing `current`.
parkio_activate_release() {
  local sha="$1" release link tmp
  parkio_validate_sha "$sha" || return 2
  release="$(parkio_release_dir "$sha")"
  [ -d "$release" ] || { echo "ERROR: release not staged: $release" >&2; return 2; }
  link="$(parkio_current_link)"
  tmp="$link.tmp.$$"
  ln -sfn "$release" "$tmp"
  mv -T -- "$tmp" "$link"
  echo "$link -> $release"
}

parkio_active_release_sha() {
  local link target
  link="$(parkio_current_link)"
  [ -L "$link" ] || return 1
  target="$(readlink -f "$link")" || return 1
  basename "$target"
}

# Prove a staged release is actually consumable by a non-root container UID:
# every ancestor traversable, every staged file readable. This is the assertion
# that would have caught DEFECT-1 before it reached a deploy.
parkio_assert_release_readable() {
  local sha="$1" release dir bad=0 mode
  parkio_validate_sha "$sha" || return 2
  release="$(parkio_release_dir "$sha")"
  [ -d "$release" ] || { echo "ERROR: release not staged: $release" >&2; return 1; }

  dir="$release"
  while [ "$dir" != "/" ]; do
    mode="$(stat -c '%a' "$dir")"
    if [ "$(( 8#$mode & 8#0001 ))" -eq 0 ]; then
      echo "ERROR: $dir is mode $mode — not traversable by other (non-root container UIDs)" >&2
      bad=1
    fi
    dir="$(dirname "$dir")"
  done

  while IFS= read -r -d '' f; do
    mode="$(stat -c '%a' "$f")"
    if [ "$(( 8#$mode & 8#0004 ))" -eq 0 ]; then
      echo "ERROR: $f is mode $mode — not readable by other" >&2
      bad=1
    fi
  done < <(find "$release" -type f -print0)

  while IFS= read -r -d '' d; do
    mode="$(stat -c '%a' "$d")"
    if [ "$(( 8#$mode & 8#0001 ))" -eq 0 ]; then
      echo "ERROR: $d is mode $mode — not traversable by other" >&2
      bad=1
    fi
  done < <(find "$release" -type d -print0)

  [ "$bad" -eq 0 ] || return 1
  echo "Release $sha is readable by non-root container UIDs."
}

# A release must never be reachable from the Actions workspace, or DEFECT-2 is
# back: cleanup would delete the live runtime's bind-mount sources.
parkio_assert_release_is_stable() {
  local release="$1"
  case "$release" in
    *"/_work/"*|*"/source-"[0-9]*-[0-9]*/*)
      echo "ERROR: runtime release path is inside the Actions workspace: $release" >&2
      return 1
      ;;
  esac
  if [ -n "${GITHUB_WORKSPACE:-}" ]; then
    case "$release" in
      "$GITHUB_WORKSPACE"/*)
        echo "ERROR: runtime release path is inside GITHUB_WORKSPACE: $release" >&2
        return 1
        ;;
    esac
  fi
  return 0
}
