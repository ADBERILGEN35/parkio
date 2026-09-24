#!/usr/bin/env bash
# Shared production restore preflight. Source from restore-hosted-beta.sh and
# restore-database.sh. Rejects unsafe inputs before decrypt or destructive apply.
# Does not start applications, publishers, schedulers, Slack, or Fluent Bit.

parkio_restore_isolated_drill() {
  case "${PARKIO_RESTORE_ISOLATED_DRILL:-0}" in
    1|true|yes|on|TRUE|YES|ON) return 0 ;;
    *) return 1 ;;
  esac
}

parkio_restore_preflight_done() {
  case "${PARKIO_RESTORE_PREFLIGHT_DONE:-0}" in
    1|true|yes|on|TRUE|YES|ON) return 0 ;;
    *) return 1 ;;
  esac
}

parkio_restore_allow_plaintext() {
  case "${PARKIO_RESTORE_ALLOW_PLAINTEXT:-0}" in
    1|true|yes|on|TRUE|YES|ON) return 0 ;;
    *) return 1 ;;
  esac
}

parkio_restore_realpath() {
  python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$1"
}

parkio_restore_is_within() {
  local child parent
  child="$(parkio_restore_realpath "$1")"
  parent="$(parkio_restore_realpath "$2")"
  python3 -c 'import os,sys; c,p=sys.argv[1],sys.argv[2]; sys.exit(0 if (c==p or c.startswith(p+os.sep)) else 1)' "$child" "$parent"
}

# Resolve the stamp directory for a manifest. Prefer the directory that
# actually holds the manifest/COMPLETE over a stale .destination path.
# Sets PARKIO_RESTORE_STAMP_DIR.
parkio_restore_resolve_stamp_from_manifest() {
  local manifest="$1"
  local override="${2:-}"
  local manifest_dir declared stamp
  manifest_dir="$(cd "$(dirname "$manifest")" && pwd)"
  declared="$(jq -r '.destination // empty' "$manifest")"
  if [ -n "$override" ]; then
    stamp="$(parkio_restore_realpath "$override")"
  elif [ -f "${manifest_dir}/COMPLETE" ] || [ -f "${manifest_dir}/backup-manifest.json" ]; then
    stamp="$(parkio_restore_realpath "$manifest_dir")"
  elif [ -n "$declared" ] && [ -d "$declared" ]; then
    stamp="$(parkio_restore_realpath "$declared")"
  else
    stamp="$manifest_dir"
  fi
  if [ -n "$declared" ] && [ -d "$declared" ]; then
    local declared_real
    declared_real="$(parkio_restore_realpath "$declared")"
    if [ "$declared_real" != "$stamp" ]; then
      echo "ERROR: manifest destination ${declared_real} is not the selected stamp ${stamp}" >&2
      echo "Refusing to restore from a different directory than the reviewed copy." >&2
      return 2
    fi
  fi
  PARKIO_RESTORE_STAMP_DIR="$stamp"
  export PARKIO_RESTORE_STAMP_DIR
}

parkio_restore_run_stamp_preflight() {
  local stamp="$1"
  local scope="${2:-full}"
  local args=("$stamp" --scope "$scope")
  if parkio_restore_allow_plaintext; then
    args+=(--allow-plaintext)
  fi
  python3 "${ROOT}/scripts/lib/restore-stamp-preflight.py" "${args[@]}"
}

# Coverage through an explicit cutoff. Never lower the cutoff. An empty
# ledger, stamp-time ledger, caller timestamp, file mtime, or supplemental
# covered-through does not certify later erasures or commit visibility.
parkio_restore_run_coverage() {
  local stamp="$1"
  local cutoff="$2"
  shift 2
  if [ -z "$cutoff" ]; then
    echo "ERROR: production restore requires --recovery-cutoff. A stamp timestamp is not a cutoff." >&2
    return 2
  fi
  local out="${PARKIO_RESTORE_MERGED_LEDGER:-}"
  if [ -z "$out" ]; then
    out="$(mktemp "${TMPDIR:-/tmp}/parkio-restore-erasure.XXXXXX")"
    PARKIO_RESTORE_MERGED_LEDGER="$out"
  fi
  local args=(--data-stamp "$stamp" --recovery-cutoff "$cutoff" --out "$out")
  local extra
  for extra in "$@"; do
    args+=(--ledger-stamp "$extra")
  done
  if [ -n "${PARKIO_RESTORE_SUPPLEMENTAL_LEDGER:-}" ]; then
    if [ -z "${PARKIO_RESTORE_SUPPLEMENTAL_THROUGH:-}" ]; then
      echo "ERROR: --supplemental-ledger requires --supplemental-covered-through" >&2
      return 2
    fi
    args+=(--supplemental "${PARKIO_RESTORE_SUPPLEMENTAL_LEDGER}")
    args+=(--supplemental-covered-through "${PARKIO_RESTORE_SUPPLEMENTAL_THROUGH}")
  fi
  python3 "${ROOT}/scripts/lib/restore-erasure-ledger.py" "${args[@]}"
}

parkio_restore_check_client_compat() {
  local profile="$1"
  local service="$2"
  local container="$3"
  local user_name="$4"
  local db_name="$5"
  if parkio_restore_isolated_drill; then
    return 0
  fi
  if [ "${PARKIO_RESTORE_SKIP_CLIENT_COMPAT:-0}" = "1" ]; then
    echo "WARN: client compatibility check skipped (PARKIO_RESTORE_SKIP_CLIENT_COMPAT=1)" >&2
    return 0
  fi
  local restore_ver target_ver postgis
  restore_ver="${PARKIO_RESTORE_CLIENT_VERSION:-}"
  target_ver="${PARKIO_RESTORE_TARGET_SERVER_VERSION:-}"
  postgis="${PARKIO_RESTORE_POSTGIS_VERSION:-}"
  if [ -z "$restore_ver" ]; then
    restore_ver="$(docker exec "${container}" psql --version)" || return 1
  fi
  if [ -z "$target_ver" ]; then
    target_ver="$(docker exec "${container}" psql -U "${user_name}" -d postgres -At -c 'show server_version')" || return 1
  fi
  if [ -z "$postgis" ] && [ "$service" = "parking" ]; then
    postgis="$(docker exec "${container}" psql -U "${user_name}" -d postgres -At -c "select default_version from pg_available_extensions where name='postgis'")" || true
  fi
  python3 "${ROOT}/scripts/lib/restore-client-compat.py" \
    --dump-profile "$profile" \
    --restore-client-version "$restore_ver" \
    --target-server-version "$target_ver" \
    --postgis-available-version "$postgis"
}

parkio_restore_require_cutoff_unless_exempt() {
  if parkio_restore_isolated_drill; then
    return 0
  fi
  if [ "${1:-}" = "dry-run" ]; then
    return 0
  fi
  if [ -z "${PARKIO_RESTORE_RECOVERY_CUTOFF:-}" ]; then
    echo "ERROR: --recovery-cutoff is required for the production restore path." >&2
    echo "A stamp-time ledger, local directory, or caller assertion is not coverage." >&2
    return 2
  fi
}
