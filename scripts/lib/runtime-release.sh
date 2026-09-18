#!/usr/bin/env bash
#
# Parkio stable runtime releases (PROD-DEPLOY-01A-R8 / DEFECT-1 + DEFECT-2).
#
# Why this exists
# ---------------
# The invite-production deploy used to run Compose straight out of the Actions
# checkout, so every relative bind mount (`./prometheus/prometheus.yml`, ...)
# resolved unde
#
#     /opt/actions-runner/parkio-invite-production/_work/parkio/parkio/source-<run>-<attempt>/docke
#
# That broke the deploy twice over:
#
#   DEFECT-1  The runner service is hardened with UMask=0077, so the runne
#             creates `_work`, `_work/<owner>`, `_work/<owner>/<repo>` as 0700.
#             Containers that drop to a non-root UID (prometheus/alertmanage
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
#     <root>/releases/<sha>/scripts/...     narrow PRIV-001A operator tooling only
#     <root>/releases/<sha>/VERSION         exact gitSha + autoExecuteHarness=false
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

# Runtime config that must never carry resolved secret values. Rendered `.env*`
# files are excluded: the deploy writes secrets into per-job tmpfs and passes
# them with --env-file. Committed dotenv *templates* (`*.example`) are allowed
# because the backup scheduler installer copies
# `docker/.env.invite-production.example` into its stable payload.
parkio_release_is_excluded() {
  case "${1##*/}" in
    .env|.env.*)
      case "${1##*/}" in
        *.example) return 1 ;;
      esac
      return 0
      ;;
  esac
  return 1
}

# Explicit additional tracked paths staged into every invite-production release
# beyond docker/**. Narrow allowlist only — never scripts/** or the whole repo.
#
# PRIV-001A operator tooling (not auto-executed). create-priv001 sources
# dark-gateway-url.sh + priv001-synthetic.sh; inspect sources priv001-synthetic.sh.
#
# Backup scheduler installer closure (GOOGLE-STARTUP-REAPPLY-01E-B1-M1A): only the
# files required to invoke install-invite-production-backup-scheduler.sh from the
# activated release. Keep in sync with that installer's PAYLOAD_FILES plus the
# installer script and its systemd unit sources.
PARKIO_RUNTIME_RELEASE_EXTRA_TRACKED_PATHS=(
  scripts/acceptance/create-priv001-synthetic-principal.sh
  scripts/acceptance/inspect-priv001-synthetic-residue.sh
  scripts/lib/priv001-synthetic.sh
  scripts/lib/dark-gateway-url.sh
  scripts/azure/install-invite-production-backup-scheduler.sh
  scripts/azure/invite-production-backup-run.sh
  scripts/azure/render-invite-production-env.sh
  scripts/azure/render-invite-production-env.py
  scripts/backup-hosted-beta.sh
  scripts/backup-databases.sh
  scripts/backup-minio.sh
  scripts/lib/backup-common.sh
  scripts/lib/backup-metrics.py
  scripts/lib/erasure-tombstones.sh
  infra/systemd/parkio-invite-backup.service
  infra/systemd/parkio-invite-backup.timer
)

# parkio_release_assert_stageable_path <repo> <rel>
# Fail-closed validation for an explicit allowlisted relative path.
parkio_release_assert_stageable_path() {
  local repo="$1" rel="$2"
  local src repo_real src_real

  case "$rel" in
    ""|/*|*..*)
      echo "ERROR: release allowlist path is empty, absolute, or contains '..': '$rel'" >&2
      return 2
      ;;
  esac
  case "$rel" in
    *$'\n'*|*$'\r'*|*$'\t'*)
      echo "ERROR: release allowlist path contains control whitespace: '$rel'" >&2
      return 2
      ;;
  esac
  case "$rel" in
    *../*|*/..|../*|..)
      echo "ERROR: release allowlist path must not contain '..': '$rel'" >&2
      return 2
      ;;
  esac

  src="$repo/$rel"
  if [ ! -e "$src" ]; then
    echo "ERROR: required release path missing from checkout: $rel" >&2
    return 2
  fi
  if [ -L "$src" ]; then
    echo "ERROR: release path must not be a symlink: $rel" >&2
    return 2
  fi
  if [ ! -f "$src" ]; then
    echo "ERROR: release path must be a regular file: $rel" >&2
    return 2
  fi

  if ! git -C "$repo" ls-files --error-unmatch -- "$rel" >/dev/null 2>&1; then
    echo "ERROR: release path is not git-tracked: $rel" >&2
    return 2
  fi

  repo_real="$(parkio_realpath "$repo")" || {
    echo "ERROR: cannot resolve repository root: $repo" >&2
    return 2
  }
  src_real="$(parkio_realpath "$src")" || {
    echo "ERROR: cannot resolve release path: $rel" >&2
    return 2
  }
  case "$src_real/" in
    "$repo_real"/*) ;;
    *)
      echo "ERROR: release path escapes repository after canonicalization: $rel -> $src_real" >&2
      return 2
      ;;
  esac
  return 0
}

# parkio_release_stage_file <repo> <staged_root> <rel>
parkio_release_stage_file() {
  local repo="$1" staged="$2" rel="$3"
  local src dst mode
  parkio_release_assert_stageable_path "$repo" "$rel" || return $?
  src="$repo/$rel"
  dst="$staged/$rel"
  install -d -m "$PARKIO_RELEASE_DIR_MODE" "$(dirname "$dst")"
  mode="$PARKIO_RELEASE_FILE_MODE"
  case "$rel" in *.sh) mode="$PARKIO_RELEASE_EXEC_MODE" ;; esac
  install -m "$mode" "$src" "$dst"
}

# parkio_release_write_identity <staged_root> <sha>
# Non-secret VERSION + integrity digests so operators can prove release identity
# without a .git directory.
parkio_release_write_identity() {
  local staged="$1" sha="$2"
  local version_file integrity_file
  version_file="$staged/VERSION"
  integrity_file="$staged/release-integrity.sha256"

  {
    printf 'gitSha=%s\n' "$sha"
    printf 'stagedAt=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'operatorTooling=priv001a\n'
    printf 'autoExecuteHarness=false\n'
  } > "$version_file"
  chmod "$PARKIO_RELEASE_FILE_MODE" "$version_file"

  : > "$integrity_file"
  (
    cd "$staged" || exit 1
    # Sorted relative digests of every staged regular file (no secrets staged).
    if command -v sha256sum >/dev/null 2>&1; then
      find . -type f ! -name 'release-integrity.sha256' -print0 \
        | sort -z \
        | xargs -0 sha256sum
    else
      # Portable fallback (BusyBox / older hosts): openssl dgst per file.
      while IFS= read -r -d '' f; do
        digest="$(openssl dgst -sha256 "$f" | awk '{print $NF}')"
        printf '%s  %s\n' "$digest" "$f"
      done < <(find . -type f ! -name 'release-integrity.sha256' -print0 | sort -z)
    fi
  ) > "$integrity_file"
  chmod "$PARKIO_RELEASE_FILE_MODE" "$integrity_file"
}

# Stage the non-secret runtime config of a checkout into an immutable release.
#   $1 repo root (the ephemeral Actions checkout)
#   $2 commit SHA
parkio_stage_runtime_release() {
  local repo="$1" sha="$2"
  local release staged rel src dst mode extra
  parkio_validate_sha "$sha" || return 2
  [ -d "$repo/docker" ] || { echo "ERROR: no docker/ directory in $repo" >&2; return 2; }

  release="$(parkio_release_dir "$sha")"
  staged="$release.staging.$$"

  # A release is immutable and content-addressed by commit, so an existing one is
  # by definition already correct. Never delete and re-create it: a re-deploy o
  # a prune pass would otherwise yank the bind-mount sources out from unde
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
    if [ -L "$src" ]; then
      echo "ERROR: refusing to stage symlink under docker/: $rel" >&2
      rm -rf -- "$staged"
      return 2
    fi
    dst="$staged/$rel"
    install -d -m "$PARKIO_RELEASE_DIR_MODE" "$(dirname "$dst")"
    mode="$PARKIO_RELEASE_FILE_MODE"
    # Alertmanager's entrypoint is a mounted shell script; it must stay
    # executable for the container's non-root UID.
    case "$rel" in *.sh) mode="$PARKIO_RELEASE_EXEC_MODE" ;; esac
    install -m "$mode" "$src" "$dst"
  done < <(git -C "$repo" ls-files -z -- docker)

  # Narrow explicit operator tooling + backup installer closure — never scripts/**
  for extra in "${PARKIO_RUNTIME_RELEASE_EXTRA_TRACKED_PATHS[@]}"; do
    parkio_release_stage_file "$repo" "$staged" "$extra" || {
      rm -rf -- "$staged"
      return 2
    }
  done

  parkio_release_write_identity "$staged" "$sha" || {
    rm -rf -- "$staged"
    return 2
  }

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

# Canonicalize a path, failing if it does not exist.
parkio_realpath() {
  if command -v realpath >/dev/null 2>&1; then
    realpath -e -- "$1" 2>/dev/null
  else
    local 
    r="$(readlink -f -- "$1" 2>/dev/null)" || return 1
    [ -e "$r" ] || return 1
    printf '%s\n' "$r"
  fi
}

# Validate that a bind-mount source is a genuine immutable release path, and echo
# the release SHA it belongs to (PROD-DEPLOY-01A-R8.3).
#
#   $1 mount source, exactly as Docker recorded it
#   $2 expected type: "file" or "dir"
#
# The invariant is deliberately NOT "binds the candidate release". A migrated
# exporter may legitimately stay on the release it was migrated onto, and
# demanding the newest SHA would force a recreate on every commit — which is the
# opposite of the no-op contract. That confusion is what failed run 32460819264.
# What actually matters is that the source is an immutable, SHA-addressed,
# structurally complete release and not the ephemeral workspace.
#
# A substring test for "releases/" would be far too weak, so each property is
# checked explicitly.
parkio_release_sha_for_mount() {
  local src="$1" want_type="${2:-file}"
  local root root_real sha rest release release_real src_real

  # The literal path matters, not just where it resolves: `current` is a mutable
  # pointer, so a container recorded against it has no attributable release even
  # though the kernel pinned it at mount time. Require immutable provenance.
  case "$src" in
    */_work/*)      echo "ERROR: mount is under the runner workspace: $src" >&2; return 1 ;;
    */acceptance/*) echo "ERROR: mount is under the acceptance scratch area: $src" >&2; return 1 ;;
    */current/*)    echo "ERROR: mount goes through the mutable 'current' pointer: $src" >&2; return 1 ;;
  esac

  root="$(parkio_releases_dir)"
  case "$src" in
    "$root"/*) ;;
    *) echo "ERROR: mount is not under the releases root $root: $src" >&2; return 1 ;;
  esac

  rest="${src#"$root"/}"
  sha="${rest%%/*}"
  if ! [[ "$sha" =~ ^[0-9a-f]{40}$ ]]; then
    echo "ERROR: release component '$sha' is not a 40-hex commit SHA: $src" >&2
    return 1
  fi
  if [ "$rest" = "$sha" ]; then
    echo "ERROR: mount points at a release root rather than a file inside it: $src" >&2
    return 1
  fi

  if ! src_real="$(parkio_realpath "$src")"; then
    echo "ERROR: mount source does not exist: $src" >&2
    return 1
  fi

  release="$root/$sha"
  if ! release_real="$(parkio_realpath "$release")"; then
    echo "ERROR: release directory does not exist: $release" >&2
    return 1
  fi
  root_real="$(parkio_realpath "$root")" || root_real="$root"

  # After following every symlink the source must still live inside its release,
  # or a symlink inside the tree could smuggle in arbitrary host content.
  case "$src_real/" in
    "$release_real"/*) ;;
    *) echo "ERROR: mount escapes its release after canonicalization: $src -> $src_real" >&2; return 1 ;;
  esac
  case "$release_real/" in
    "$root_real"/*) ;;
    *) echo "ERROR: release escapes the releases root after canonicalization: $release" >&2; return 1 ;;
  esac

  case "$want_type" in
    file) [ -f "$src_real" ] || { echo "ERROR: expected a file: $src" >&2; return 1; } ;;
    dir)  [ -d "$src_real" ] || { echo "ERROR: expected a directory: $src" >&2; return 1; } ;;
    *)    echo "ERROR: unknown expected type '$want_type'" >&2; return 1 ;;
  esac

  # Distinguish a real staged release from any directory that merely happens to
  # be named like a SHA. Releases staged before R8.3 carry no manifest, so this
  # validates structure rather than metadata — deliberately backward compatible,
  # so the healthy 7c2c8b80 mounts stay valid without retrofitting anything.
  if [ ! -f "$release_real/docker/docker-compose.yml" ]; then
    echo "ERROR: $release is not a complete staged release (no docker/docker-compose.yml)" >&2
    return 1
  fi

  printf '%s\n' "$sha"
}

# Names of running containers that still bind-mount the runner workspace.
#
# EMPTY OUTPUT IS SUCCESS. That is the migrated steady state, and it must exit 0
# (PROD-DEPLOY-01A-R8.2). The previous formulation ended the loop body with
# `grep -q ... && echo "$name"`, so with `set -euo pipefail` a zero-match scan
# returned 1 and killed the caller at the exact moment migration had succeeded —
# the helper could only "pass" while the defect it checks for was still present.
#
# A Docker failure is emphatically NOT the same as "no matches": it returns 2 so
# the caller fails closed instead of concluding the workspace is clean. That is
# why there is no blanket `|| true` here — silencing docker would turn an
# inspection outage into a false all-clear.
parkio_stale_work_mounts() {
  local ids cid name sources found=""
  if ! ids="$(docker ps -q)"; then
    echo "ERROR: unable to list running containers" >&2
    return 2
  fi
  for cid in $ids; do
    if ! sources="$(docker inspect -f '{{range .Mounts}}{{println .Source}}{{end}}' "$cid")"; then
      echo "ERROR: unable to inspect container $cid" >&2
      return 2
    fi
    case "$sources" in
      *"/_work/"*)
        if ! name="$(docker inspect -f '{{slice .Name 1}}' "$cid")"; then
          echo "ERROR: unable to read the name of container $cid" >&2
          return 2
        fi
        found="$found$name
"
        ;;
    esac
  done
  # No matches: emit nothing and succeed.
  [ -n "$found" ] || return 0
  printf '%s' "$found" | sort -u
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

# ---------------------------------------------------------------------------
# Release prune inventory (PA-12)
# Fail-closed: docker/inventory errors abort deletion (exit 3). Never treat a
# failed inspect as an empty in-use set.
# Protected SHAs always include: active symlink, candidate SHA, container bind
# mounts under the release root, explicit PARKIO_PROTECTED_RELEASE_SHAS,
# PARKIO_PREVIOUS_RELEASE_SHA (rollback target), and gitSha from
# PARKIO_DEPLOY_ARTIFACT_DIR/current.json when present.
# ---------------------------------------------------------------------------

parkio_release_sha_from_path() {
  local path="$1"
  local root resolved
  root="$(parkio_releases_dir)"
  case "$path" in
    "$root"/*)
      resolved="${path#"$root"/}"
      resolved="${resolved%%/*}"
      if [[ "$resolved" =~ ^[0-9a-f]{40}$ ]]; then
        printf '%s\n' "$resolved"
      fi
      ;;
  esac
}

# Print one SHA per line for every release directory currently bind-mounted by
# a running container. Returns 2 if docker inventory cannot be trusted.
parkio_release_mount_shas() {
  local root cid mounts mount sha ids
  root="$(parkio_releases_dir)"
  if ! command -v docker >/dev/null 2>&1; then
    echo "ERROR: docker is required to inventory in-use release mounts" >&2
    return 2
  fi
  if ! ids="$(docker ps -q 2>/dev/null)"; then
    echo "ERROR: docker ps failed; refuse prune" >&2
    return 2
  fi
  while IFS= read -r cid; do
    [ -n "$cid" ] || continue
    if ! mounts="$(docker inspect -f '{{range .Mounts}}{{println .Source}}{{end}}' "$cid" 2>/dev/null)"; then
      echo "ERROR: docker inspect failed for container $cid; refuse prune" >&2
      return 2
    fi
    while IFS= read -r mount; do
      [ -n "$mount" ] || continue
      case "$mount" in
        "$root"/*)
          sha="$(parkio_release_sha_from_path "$mount")"
          [ -n "$sha" ] && printf '%s\n' "$sha"
          ;;
      esac
    done <<< "$mounts"
  done <<< "$ids"
}

# Merge protected SHA sources into a unique sorted list on stdout.
# Returns 2 if inventory is incomplete.
parkio_protected_release_shas() {
  local candidate="${1:-}"
  local sha active artifact_dir previous
  local -a protected=()

  if [ -n "$candidate" ]; then
    protected+=("$candidate")
  fi

  active="$(parkio_active_release_sha 2>/dev/null || true)"
  if [ -n "$active" ]; then
    protected+=("$active")
  fi

  previous="${PARKIO_PREVIOUS_RELEASE_SHA:-}"
  if [ -n "$previous" ]; then
    protected+=("$previous")
  fi

  # Explicit operator / scheduler overrides (space or comma separated).
  if [ -n "${PARKIO_PROTECTED_RELEASE_SHAS:-}" ]; then
    # shellcheck disable=SC2086
    for sha in ${PARKIO_PROTECTED_RELEASE_SHAS//,/ }; do
      [ -n "$sha" ] && protected+=("$sha")
    done
  fi

  artifact_dir="${PARKIO_DEPLOY_ARTIFACT_DIR:-}"
  if [ -n "$artifact_dir" ] && [ -f "$artifact_dir/current.json" ]; then
    sha="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1],encoding="utf-8")).get("gitSha") or "")' \
      "$artifact_dir/current.json" 2>/dev/null || true)"
    if [ -n "$sha" ] && [ "$sha" != "null" ]; then
      protected+=("$sha")
    fi
    prev_path="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1],encoding="utf-8")).get("previousManifest") or "")' \
      "$artifact_dir/current.json" 2>/dev/null || true)"
    if [ -n "$prev_path" ] && [ -f "$prev_path" ]; then
      sha="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1],encoding="utf-8")).get("gitSha") or "")' \
        "$prev_path" 2>/dev/null || true)"
      if [ -n "$sha" ] && [ "$sha" != "null" ]; then
        protected+=("$sha")
      fi
    fi
  fi

  # Fail-closed bind-mount inventory.
  local mount_shas
  if ! mount_shas="$(parkio_release_mount_shas)"; then
    return 2
  fi
  while IFS= read -r sha; do
    [ -n "$sha" ] && protected+=("$sha")
  done <<< "$mount_shas"

  if [ "${#protected[@]}" -eq 0 ]; then
    return 0
  fi
  printf '%s\n' "${protected[@]}" | sort -u
}

# Write a machine-readable cleanup status JSON (always when status_path set).
parkio_write_cleanup_status() {
  local status_path="$1"
  local outcome="$2"
  local keep="$3"
  local dry_run="$4"
  local deleted_json="$5"
  local protected_json="$6"
  local error_msg="${7:-}"
  local tmp
  [ -n "$status_path" ] || return 0
  mkdir -p "$(dirname "$status_path")"
  tmp="${status_path}.tmp.$$"
  PARKIO_CLEANUP_OUTCOME="$outcome" \
  PARKIO_CLEANUP_KEEP="$keep" \
  PARKIO_CLEANUP_DRY_RUN="$dry_run" \
  PARKIO_CLEANUP_DELETED_JSON="$deleted_json" \
  PARKIO_CLEANUP_PROTECTED_JSON="$protected_json" \
  PARKIO_CLEANUP_ERROR="$error_msg" \
  PARKIO_CLEANUP_RECORDED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  python3 - "$tmp" <<'PY'
import json, os, sys
path = sys.argv[1]
doc = {
  "outcome": os.environ["PARKIO_CLEANUP_OUTCOME"],
  "keep": int(os.environ["PARKIO_CLEANUP_KEEP"]),
  "dryRun": int(os.environ["PARKIO_CLEANUP_DRY_RUN"]),
  "deleted": json.loads(os.environ["PARKIO_CLEANUP_DELETED_JSON"]),
  "protected": json.loads(os.environ["PARKIO_CLEANUP_PROTECTED_JSON"]),
  "error": os.environ["PARKIO_CLEANUP_ERROR"] or None,
  "recordedAt": os.environ["PARKIO_CLEANUP_RECORDED_AT"],
}
with open(path, "w", encoding="utf-8") as fh:
  json.dump(doc, fh, indent=2)
  fh.write("\n")
PY
  mv -f "$tmp" "$status_path"
}

parkio_json_string_array() {
  if [ "$#" -eq 0 ]; then
    printf '%s\n' '[]'
    return 0
  fi
  printf '%s\n' "$@" | python3 -c 'import json,sys; print(json.dumps([ln.rstrip("\n") for ln in sys.stdin if ln.strip()]))'
}

# Prune old release directories under parkio_releases_dir.
# Exit: 0 ok, 2 usage, 3 inventory fail (nothing deleted), 4 deletion errors.
parkio_prune_releases() {
  local keep="${1:-5}"
  local dry_run="${2:-0}"
  local candidate="${3:-}"
  local status_path="${4:-}"
  local root d sha kept=0 skip=0 protected_raw="" lock_fd
  local -a protected_list=() candidates=() deleted=()
  local protected_json='[]' deleted_json='[]'
  local line rc=0

  if ! [[ "$keep" =~ ^[0-9]+$ ]] || [ "$keep" -lt 1 ]; then
    echo "ERROR: --keep must be a positive integer (got: $keep)" >&2
    return 2
  fi

  root="$(parkio_releases_dir)"
  if [ ! -d "$root" ]; then
    parkio_write_cleanup_status "$status_path" "noop" "$keep" "$dry_run" '[]' '[]' ""
    echo "No release root at $root; nothing to prune."
    return 0
  fi

  # Serialize prune decisions so concurrent deploys share one protection snapshot.
  # Always unlock on return so repeated prune calls in the same shell do not stall.
  lock_fd=""
  _parkio_prune_unlock() {
    if [ -n "${lock_fd:-}" ]; then
      flock -u "$lock_fd" 2>/dev/null || true
      eval "exec ${lock_fd}>&-" 2>/dev/null || true
      lock_fd=""
    fi
  }
  if command -v flock >/dev/null 2>&1; then
    exec {lock_fd}>"${root}/.prune.lock"
    if ! flock -w 120 "$lock_fd"; then
      _parkio_prune_unlock
      parkio_write_cleanup_status "$status_path" "lock_failed" "$keep" "$dry_run" '[]' '[]' \
        "could not acquire prune lock"
      echo "ERROR: could not acquire prune lock under $root" >&2
      return 3
    fi
  fi

  if ! protected_raw="$(parkio_protected_release_shas "$candidate")"; then
    _parkio_prune_unlock
    parkio_write_cleanup_status "$status_path" "inventory_failed" "$keep" "$dry_run" '[]' '[]' \
      "protected release inventory failed; refuse deletion"
    echo "ERROR: protected release inventory failed; refuse deletion" >&2
    return 3
  fi
  if [ -n "$protected_raw" ]; then
    mapfile -t protected_list <<< "$protected_raw"
    protected_json="$(parkio_json_string_array "${protected_list[@]}")"
  fi
  echo "Protected release SHAs: ${protected_list[*]:-<none>}"

  # Newest-first candidates that are not protected.
  while IFS= read -r d; do
    [ -n "$d" ] || continue
    sha="$(basename "$d")"
    [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || continue
    skip=0
    for line in "${protected_list[@]:-}"; do
      if [ "$line" = "$sha" ]; then
        skip=1
        break
      fi
    done
    if [ "$skip" -eq 1 ]; then
      echo "Protecting in-use/active/rollback release: $sha"
      continue
    fi
    candidates+=("$d")
  done < <(find "$root" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' 2>/dev/null | sort -nr | awk '{print $2}')

  for d in "${candidates[@]:-}"; do
    if [ "$kept" -lt "$keep" ]; then
      echo "Keeping recent release: $(basename "$d")"
      kept=$((kept + 1))
      continue
    fi
    sha="$(basename "$d")"
    if [ "$dry_run" -eq 1 ]; then
      echo "DRY-RUN: would remove $d"
      deleted+=("$sha")
      continue
    fi
    echo "Removing old release: $d"
    if rm -rf --one-file-system "$d"; then
      deleted+=("$sha")
    else
      echo "ERROR: failed to remove $d" >&2
      rc=4
    fi
  done

  if [ "${#deleted[@]}" -gt 0 ]; then
    deleted_json="$(parkio_json_string_array "${deleted[@]}")"
  fi

  if [ "$rc" -eq 4 ]; then
    parkio_write_cleanup_status "$status_path" "partial_failure" "$keep" "$dry_run" \
      "$deleted_json" "$protected_json" "one or more release deletions failed"
    _parkio_prune_unlock
    return 4
  fi
  if [ "$dry_run" -eq 1 ]; then
    parkio_write_cleanup_status "$status_path" "dry_run" "$keep" "$dry_run" \
      "$deleted_json" "$protected_json" ""
  else
    parkio_write_cleanup_status "$status_path" "ok" "$keep" "$dry_run" \
      "$deleted_json" "$protected_json" ""
  fi
  _parkio_prune_unlock
  return 0
}

# Refuse image/config rollback when the live schema has advanced past the
# target manifest's recorded migrations. Image rollback ≠ DB restore.
parkio_assert_rollback_schema_compatible() {
  local target_manifest="$1"
  local current_manifest="${2:-}"

  if [ -z "$current_manifest" ] || [ ! -f "$current_manifest" ]; then
    echo "WARN: no current manifest for schema comparison; operator must confirm DB compatibility." >&2
    return 0
  fi

  python3 - "$target_manifest" "$current_manifest" <<'PY'
import json, sys

target_path, current_path = sys.argv[1], sys.argv[2]
with open(target_path, encoding="utf-8") as fh:
    target = json.load(fh)
with open(current_path, encoding="utf-8") as fh:
    current = json.load(fh)

if "migrations" not in target:
    print("ERROR: target manifest missing migrations; refuse rollback without schema evidence.", file=sys.stderr)
    sys.exit(3)
if "migrations" not in current:
    print("WARN: current manifest missing migrations; operator must confirm DB compatibility.", file=sys.stderr)
    sys.exit(0)

extra = []
for service, cur_list in (current.get("migrations") or {}).items():
    tgt_set = set(target.get("migrations", {}).get(service) or [])
    for mig in cur_list or []:
        if mig not in tgt_set:
            extra.append({"service": service, "migration": mig})

if extra:
    print("ERROR: live schema has migrations not present in the rollback target.", file=sys.stderr)
    print("       Image/config rollback would leave an incompatible database.", file=sys.stderr)
    print("       This is not a DB restore. Refusing automatic rollback.", file=sys.stderr)
    print("       Extra migrations:", json.dumps(extra), file=sys.stderr)
    sys.exit(3)
sys.exit(0)
PY
}
