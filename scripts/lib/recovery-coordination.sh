# Default-off hooks for ordinary operational snapshot and restore erasure refusal.
# Sourced by backup-hosted-beta.sh and restore-drill-01.sh. No production activation.

parkio_ops_state_enabled() {
  case "${PARKIO_OPS_STATE_BACKUP_ENABLED:-0}" in
    1|true|yes|on) return 0 ;;
    *) return 1 ;;
  esac
}

parkio_coordinator_enabled() {
  case "${PARKIO_RECOVERY_COORDINATOR_ENABLED:-0}" in
    1|true|yes|on) return 0 ;;
    *) return 1 ;;
  esac
}

# After a COMPLETE stamp: optional ordinary operational snapshot. Failure must
# resume the pre-existing running set only; it does not retract COMPLETE.
# Production writer-control orchestration: NOT IMPLEMENTED.
# Remaining adapter work: inventory host units, pause/resume with pre-state
# recording, configured budget 15m, hard ceiling 20m, exporter-only pause that
# does not disable outbox admission, measured drill. Do not remove this refusal
# to call the package ready.
parkio_ordinary_ops_snapshot_after_complete() {
  local stamp="$1"
  local dest_dir="$2"
  if ! parkio_ops_state_enabled || ! parkio_coordinator_enabled; then
    echo "operational-state snapshot: skipped (default off)"
    return 0
  fi
  echo "ERROR: production orchestration NOT IMPLEMENTED; live writer control is refused." >&2
  echo "ERROR: refusing to pause production units from this hook." >&2
  return 0
}

# Optional #102 recover before restore-erasure-ledger. Never lowers --recovery-cutoff.
# Directory store is not off-host durability. BLOCKED remains exit 3.
parkio_offhost_erasure_supplement() {
  local cutoff="$1"
  local data_stamp="$2"
  local out="$3"
  if [ -z "${PARKIO_OFFHOST_STORE_DIR:-}" ]; then
    return 0
  fi
  if [ "${PARKIO_OFFHOST_ERASURE_ENABLED:-0}" != "1" ]; then
    echo "ERROR: PARKIO_OFFHOST_STORE_DIR set but PARKIO_OFFHOST_ERASURE_ENABLED is not 1" >&2
    return 1
  fi
  echo "offhost erasure: directory backend is not off-host durability" >&2
  python3 "${ROOT}/scripts/offhost-erasure-recover.py" \
    --store-dir "${PARKIO_OFFHOST_STORE_DIR}" \
    --recovery-cutoff "${cutoff}" \
    --data-stamp "${data_stamp}" \
    --out "${out}"
}
