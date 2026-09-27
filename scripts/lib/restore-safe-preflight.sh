#!/usr/bin/env bash
# Shared production restore preflight. Source from restore-hosted-beta.sh and
# restore-database.sh. Rejects unsafe inputs before decrypt or destructive apply.
# Does not start applications, publishers, schedulers, Slack, or Fluent Bit.
#
# Production refusals are not bypassed by PARKIO_RESTORE_ISOLATED_DRILL or
# PARKIO_RESTORE_PREFLIGHT_DONE alone. Isolation requires --isolated-fixture
# plus a validated ticket that binds the selected stamp.

parkio_restore_isolated_fixture_ok() {
  case "${PARKIO_RESTORE_ISOLATED_FIXTURE:-0}" in
    1|true|yes|on|TRUE|YES|ON) ;;
    *) return 1 ;;
  esac
  local ticket="${PARKIO_RESTORE_ISOLATED_TICKET:-}"
  if [ -z "${ticket}" ] || [ ! -f "${ticket}" ]; then
    return 1
  fi
  grep -qx 'parkio-isolated-fixture=1' "${ticket}" || return 1
  local ticket_stamp
  ticket_stamp="$(grep '^stamp=' "${ticket}" | head -1 | sed 's/^stamp=//')"
  if [ -z "${ticket_stamp}" ]; then
    return 1
  fi
  if [ -n "${PARKIO_RESTORE_STAMP_DIR:-}" ]; then
    local want
    want="$(parkio_restore_realpath "${PARKIO_RESTORE_STAMP_DIR}")"
    if [ "$(parkio_restore_realpath "${ticket_stamp}")" != "${want}" ]; then
      return 1
    fi
  fi
  return 0
}

# Env flags alone are not isolation or preflight proof.
parkio_restore_isolated_drill() {
  parkio_restore_isolated_fixture_ok
}

parkio_restore_preflight_done() {
  case "${PARKIO_RESTORE_PREFLIGHT_DONE:-0}" in
    1|true|yes|on|TRUE|YES|ON) ;;
    *) return 1 ;;
  esac
  parkio_restore_isolated_fixture_ok
}

parkio_restore_issue_isolated_ticket() {
  local stamp="$1"
  local ticket
  ticket="$(mktemp "${TMPDIR:-/tmp}/parkio-isolated-fixture.XXXXXX")"
  {
    echo "parkio-isolated-fixture=1"
    echo "stamp=$(parkio_restore_realpath "${stamp}")"
  } > "${ticket}"
  chmod 600 "${ticket}"
  PARKIO_RESTORE_ISOLATED_FIXTURE=1
  PARKIO_RESTORE_ISOLATED_TICKET="${ticket}"
  export PARKIO_RESTORE_ISOLATED_FIXTURE PARKIO_RESTORE_ISOLATED_TICKET
}

parkio_restore_accept_isolated_fixture() {
  local stamp="$1"
  case "${PARKIO_RESTORE_ISOLATED_FIXTURE:-0}" in
    1|true|yes|on|TRUE|YES|ON) ;;
    *) return 0 ;;
  esac
  if [ -z "${PARKIO_RESTORE_ISOLATED_TICKET:-}" ]; then
    parkio_restore_issue_isolated_ticket "${stamp}"
    return 0
  fi
  if ! parkio_restore_isolated_fixture_ok; then
    echo "ERROR: isolated-fixture ticket is missing or does not match the selected stamp." >&2
    return 2
  fi
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

# Snapshot-clock comparison only. Never certifies commit-visible coverage.
# Never lower the cutoff. An empty ledger, stamp-time ledger, caller
# timestamp, file mtime, or supplemental covered-through is not verification.
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

# No supported verified-coverage mechanism exists. Isolated fixtures may
# continue after snapshot-clock checks. Production must BLOCK.
parkio_restore_refuse_unverified_production() {
  if parkio_restore_isolated_fixture_ok; then
    return 0
  fi
  echo "ERROR: no verified erasure coverage evidence; production restore BLOCKED." >&2
  echo "A manifest timestamp is not certified commit-visible coverage." >&2
  echo "Merged identifiers and declared snapshot time are not verified coverage." >&2
  return 3
}

parkio_restore_refuse_unsupported_production_scope() {
  if parkio_restore_isolated_fixture_ok; then
    return 0
  fi
  case "${1:-}" in
    minio)
      echo "ERROR: MinIO-only restore does not apply erasures to restored objects." >&2
      echo "Unsupported production scope; restore BLOCKED." >&2
      return 3
      ;;
  esac
  return 0
}

parkio_restore_refuse_standalone_database() {
  if parkio_restore_isolated_fixture_ok; then
    return 0
  fi
  echo "ERROR: standalone restore-database.sh is not an erasure-safe production path." >&2
  echo "It does not replay erasures. Production apply BLOCKED." >&2
  return 3
}

parkio_restore_check_client_compat() {
  local profile="$1"
  local service="$2"
  local container="$3"
  local user_name="$4"
  local db_name="$5"
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
  if [ "${1:-}" = "dry-run" ]; then
    return 0
  fi
  if [ -z "${PARKIO_RESTORE_RECOVERY_CUTOFF:-}" ]; then
    echo "ERROR: --recovery-cutoff is required for the production restore path." >&2
    echo "A stamp-time ledger, local directory, or caller assertion is not coverage." >&2
    return 2
  fi
}
