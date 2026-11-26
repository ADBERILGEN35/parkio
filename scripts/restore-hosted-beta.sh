#!/usr/bin/env bash
#
# Parkio - restore hosted-beta backups from a manifest produced by backup-hosted-beta.sh.
# After DB restore, replay erasure-tombstones.json (PRIV-001). The same replay is
# required after managed PITR restore — see docs/architecture/pp-01-managed-postgresql-pitr-ha.md
#
# Usage:
#   PARKIO_ENV_FILE=docker/.env ./scripts/restore-hosted-beta.sh \\
#     --manifest /path/to/stamp/backup-manifest.json --recovery-cutoff 2026-09-24T12:00:00Z
#   PARKIO_ENV_FILE=docker/.env ./scripts/restore-hosted-beta.sh --manifest ... --dry-run
#   PARKIO_ENV_FILE=docker/.env ./scripts/restore-hosted-beta.sh --manifest ... --yes --only minio
#
# --dry-run and --yes count only on the command line: DRY_RUN or ASSUME_YES from the
# environment or the env file is ignored with a WARN. A dry run decrypts nothing.
# The env file is shell code. The manifest, --only, the env file and the other options are
# read-only before it is sourced, so a line there that assigns one stops the run (exit 1). A line
# that sets, changes or clears a PARKIO_RESTORE_* control, which carries --recovery-cutoff,
# --isolated-ticket and the other restore options, stops it with exit 2. Both happen before any
# restore step.
#
# Stage-4 isolated recovery (U02) adds, with the isolated ticket:
#   --erasure-evidence BUNDLE --erasure-trust TRUST --recovery-attempt UUID --recovery-dir DIR
# The off-host evidence bundle is verified before anything is decrypted: missing, corrupt or
# gap evidence exits 3 with nothing applied. On trust it writes DIR/trusted-erasure-set.json for
# the recovery-replay command (scripts/recovery-replay.sh) and a CLOSED expose gate, which
# scripts/lib/recovery-expose-gate.py opens only on a COMPLETE replay verdict. Coverage is reported
# only as the verified sequence.
# Production path is BLOCKED before decrypt or apply: a manifest timestamp
# is not verified coverage. --isolated-fixture plus a destination-bound
# ticket from restore-isolated-fixture.sh is the only supported synthetic
# path. The CLI flag does not self-authorize. Env flags alone do not bypass.
# Does not start applications.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Read-only like the command-line values below: an env-file ROOT line cannot point this run at the
# scripts of another checkout.
readonly ROOT
# shellcheck source=lib/backup-common.sh
source "$ROOT/scripts/lib/backup-common.sh"
# shellcheck source=lib/erasure-tombstones.sh
source "$ROOT/scripts/lib/erasure-tombstones.sh"
# shellcheck source=lib/restore-safe-preflight.sh
source "$ROOT/scripts/lib/restore-safe-preflight.sh"

ENV_FILE="${PARKIO_ENV_FILE:-}"
MANIFEST=""
# The per-run switches come from the command line only. The env file is sourced below under
# set -a, so they are parsed into private names and become DRY_RUN / ASSUME_YES after it.
arg_dry_run=0
arg_assume_yes="no"
ONLY=""
STAMP_OVERRIDE=""
LEDGER_STAMPS=()
ERASURE_EVIDENCE=""
ERASURE_TRUST=""
RECOVERY_ATTEMPT=""
RECOVERY_DIR=""

# A value option must not take the next option as its value ("--env-file --dry-run").
option_value() {
  case "${2-}" in
    "" | -*)
      echo "ERROR: $1 needs a value; got '${2-}'" >&2
      exit 2
      ;;
  esac
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --manifest) option_value "$1" "${2-}"; MANIFEST="$2"; shift 2 ;;
    --env-file) option_value "$1" "${2-}"; ENV_FILE="$2"; shift 2 ;;
    --dry-run) arg_dry_run=1; shift ;;
    --yes) arg_assume_yes="yes"; shift ;;
    --only) option_value "$1" "${2-}"; ONLY="$2"; shift 2 ;;
    --stamp-dir) option_value "$1" "${2-}"; STAMP_OVERRIDE="$2"; shift 2 ;;
    --recovery-cutoff) option_value "$1" "${2-}"; PARKIO_RESTORE_RECOVERY_CUTOFF="$2"; shift 2 ;;
    --ledger-stamp) option_value "$1" "${2-}"; LEDGER_STAMPS+=("$2"); shift 2 ;;
    --supplemental-ledger) option_value "$1" "${2-}"; PARKIO_RESTORE_SUPPLEMENTAL_LEDGER="$2"; shift 2 ;;
    --supplemental-covered-through)
      option_value "$1" "${2-}"; PARKIO_RESTORE_SUPPLEMENTAL_THROUGH="$2"; shift 2 ;;
    --isolated-fixture) PARKIO_RESTORE_ISOLATED_FIXTURE=1; shift ;;
    --isolated-ticket) option_value "$1" "${2-}"; PARKIO_RESTORE_ISOLATED_TICKET="$2"; shift 2 ;;
    --erasure-evidence) option_value "$1" "${2-}"; ERASURE_EVIDENCE="$2"; shift 2 ;;
    --erasure-trust) option_value "$1" "${2-}"; ERASURE_TRUST="$2"; shift 2 ;;
    --recovery-attempt) option_value "$1" "${2-}"; RECOVERY_ATTEMPT="$2"; shift 2 ;;
    --recovery-dir) option_value "$1" "${2-}"; RECOVERY_DIR="$2"; shift 2 ;;
    -h|--help) sed -n '2,33p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done
# The env file is shell code: a line there could reassign the private switches or anything else the
# command line set. A line that assigns or unsets one of these now fails the load under set -e, and
# the run exits 1 before any restore step.
readonly ENV_FILE MANIFEST ONLY STAMP_OVERRIDE LEDGER_STAMPS ERASURE_EVIDENCE ERASURE_TRUST \
  RECOVERY_ATTEMPT RECOVERY_DIR arg_dry_run arg_assume_yes

if [ -z "${MANIFEST}" ] || [ ! -f "${MANIFEST}" ]; then
  echo "ERROR: --manifest <path> is required and must exist." >&2
  exit 2
fi

# The restore controls (--recovery-cutoff, --isolated-ticket and the other PARKIO_RESTORE_* values)
# come from the command line or the caller's environment only: an env file that sets, changes or
# clears one stops the run here (exit 2).
restore_controls="$(parkio_restore_controls)"
readonly restore_controls
parkio_backup_load_env "${ENV_FILE}"
parkio_restore_refuse_env_controls "${restore_controls}" || exit 2
if [ -n "${DRY_RUN+set}" ]; then
  echo "WARN: ignoring DRY_RUN from the environment or env file; only --dry-run counts." >&2
fi
if [ -n "${ASSUME_YES+set}" ]; then
  echo "WARN: ignoring ASSUME_YES from the environment or env file; only --yes counts." >&2
fi
DRY_RUN="${arg_dry_run}"
ASSUME_YES="${arg_assume_yes}"
parkio_backup_validate_deployment_profile

if ! parkio_restore_resolve_stamp_from_manifest "${MANIFEST}" "${STAMP_OVERRIDE}"; then
  exit 2
fi
DEST_DIR="${PARKIO_RESTORE_STAMP_DIR}"
if ! parkio_restore_accept_isolated_fixture "${DEST_DIR}"; then
  exit 2
fi
BUCKET="$(jq -r '.minio.bucket // empty' "${MANIFEST}")"
GIT_SHA="$(jq -r '.gitSha // empty' "${MANIFEST}")"
STAMP="$(jq -r '.timestamp // empty' "${MANIFEST}")"
MANIFEST_PROFILE="$(jq -r '.deploymentProfile // "hosted-beta"' "${MANIFEST}")"

if [ "${PARKIO_DEPLOYMENT_PROFILE}" != "${MANIFEST_PROFILE}" ]; then
  echo "ERROR: restore profile '${PARKIO_DEPLOYMENT_PROFILE}' does not match manifest profile '${MANIFEST_PROFILE}'." >&2
  exit 2
fi

STAGE4_RECOVERY=0
if [ -n "${ERASURE_EVIDENCE}${ERASURE_TRUST}${RECOVERY_ATTEMPT}${RECOVERY_DIR}" ]; then
  STAGE4_RECOVERY=1
fi

# U02 stage 4: verify the off-host erasure evidence before anything is decrypted or applied.
# Isolated only; the production path below stays refused whatever these options say.
verify_erasure_evidence() {
  if [ -z "${ERASURE_EVIDENCE}" ] || [ -z "${ERASURE_TRUST}" ] || [ -z "${RECOVERY_ATTEMPT}" ] \
      || [ -z "${RECOVERY_DIR}" ]; then
    echo "ERROR: stage-4 recovery needs --erasure-evidence, --erasure-trust, --recovery-attempt and --recovery-dir together." >&2
    return 2
  fi
  if [ "${ONLY}" = "minio" ]; then
    echo "ERROR: stage-4 recovery restores the databases; --only minio cannot carry it." >&2
    return 2
  fi
  if ! parkio_restore_isolated_fixture_ok; then
    echo "ERROR: stage-4 recovery is isolated-only (--isolated-fixture with a destination-bound ticket); production restore stays refused." >&2
    return 3
  fi
  if [ ! -d "${RECOVERY_DIR}" ]; then
    echo "ERROR: --recovery-dir must be an existing directory." >&2
    return 2
  fi
  local target
  target="$(parkio_restore_isolated_postgres_json auth | python3 -c 'import json,sys; print(json.load(sys.stdin).get("databaseIdentity", ""))')" || target=""
  if [ -z "${target}" ]; then
    echo "ERROR: the isolated ticket pins no auth databaseIdentity; issue a new one with scripts/restore-isolated-fixture.sh." >&2
    return 2
  fi
  echo "=== erasure evidence (before decrypt) ==="
  local rc=0
  python3 "${ROOT}/scripts/lib/recovery-evidence.py" verify \
    --bundle "${ERASURE_EVIDENCE}" --trust "${ERASURE_TRUST}" \
    --attempt "${RECOVERY_ATTEMPT}" --dataset "${STAMP}" --target-identity "${target}" \
    --out "${RECOVERY_DIR}/trusted-erasure-set.json" || rc=$?
  if [ "${rc}" -eq 3 ]; then
    echo "ERROR: erasure evidence BLOCKED; nothing was decrypted or restored, and the copy stays unexposed." >&2
    return 3
  elif [ "${rc}" -ne 0 ]; then
    echo "ERROR: erasure evidence could not be checked; nothing was decrypted or restored." >&2
    return 2
  fi
  python3 "${ROOT}/scripts/lib/recovery-expose-gate.py" init --recovery-dir "${RECOVERY_DIR}"
}

SCOPE="full"
case "${ONLY}" in
  databases|db) SCOPE="databases" ;;
  ""|all|minio) SCOPE="full" ;;
esac

echo "=== Parkio hosted-beta restore ==="
echo "manifest=${MANIFEST}"
echo "destination=${DEST_DIR}"
echo "gitSha=${GIT_SHA}"
echo "stamp=${STAMP}"
echo "deploymentProfile=${PARKIO_DEPLOYMENT_PROFILE}"
echo "dryRun=${DRY_RUN}"
echo "only=${ONLY:-all}"
echo "scope=${SCOPE}"
echo "recoveryCutoff=${PARKIO_RESTORE_RECOVERY_CUTOFF:-}"

if [ ! -d "${DEST_DIR}" ]; then
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "WARN: backup destination ${DEST_DIR} not found (dry-run continues)."
  else
    echo "ERROR: backup destination not found: ${DEST_DIR}" >&2
    exit 2
  fi
elif [ -f "${DEST_DIR}/COMPLETE" ]; then
  echo "=== stamp preflight (${SCOPE}) ==="
  if ! parkio_restore_run_stamp_preflight "${DEST_DIR}" "${SCOPE}"; then
    echo "ERROR: stamp preflight failed; nothing was decrypted or restored." >&2
    exit 1
  fi
  if [ "${STAGE4_RECOVERY}" -eq 1 ]; then
    evidence_rc=0
    verify_erasure_evidence || evidence_rc=$?
    if [ "${evidence_rc}" -ne 0 ]; then
      exit "${evidence_rc}"
    fi
  fi
  if [ "$DRY_RUN" -ne 1 ]; then
    parkio_restore_require_cutoff_unless_exempt || exit 2
    echo "=== erasure snapshot clock through ${PARKIO_RESTORE_RECOVERY_CUTOFF} ==="
    set +e
    parkio_restore_run_coverage "${DEST_DIR}" "${PARKIO_RESTORE_RECOVERY_CUTOFF}" "${LEDGER_STAMPS[@]}"
    coverage_rc=$?
    set -e
    if [ "${coverage_rc}" -eq 3 ]; then
      echo "ERROR: declared snapshot time does not reach the recovery cutoff; restore BLOCKED." >&2
      echo "Never lower the cutoff to obtain PASS." >&2
      exit 3
    elif [ "${coverage_rc}" -ne 0 ]; then
      echo "ERROR: erasure coverage evidence is invalid." >&2
      exit 1
    fi
    parkio_restore_refuse_unsupported_production_scope "${ONLY:-all}" || exit 3
    parkio_restore_refuse_unverified_production || exit 3
  fi
elif [ "$DRY_RUN" -eq 1 ]; then
  echo "WARN: ${DEST_DIR} is not a COMPLETE stamp (dry-run continues)."
else
  echo "ERROR: refusing restore of incomplete stamp (missing COMPLETE): ${DEST_DIR}" >&2
  exit 2
fi

restore_databases() {
  local svc dump
  if [ "$DRY_RUN" -ne 1 ]; then
    if ! parkio_restore_isolated_fixture_ok; then
      echo "ERROR: production restore cannot apply databases without verified coverage." >&2
      return 3
    fi
    while IFS= read -r svc; do
      [ -z "${svc}" ] && continue
      svc="${svc//$'\r'/}"
      python3 -c 'import json,sys; t=json.load(open(sys.argv[1],encoding="utf-8")); sys.exit(0 if sys.argv[2] in (t.get("postgres") or {}) else 2)' \
        "${PARKIO_RESTORE_ISOLATED_TICKET}" "${svc}" || {
        echo "ERROR: isolated ticket does not authorize manifest postgres service '${svc}'." >&2
        return 2
      }
    done < <(jq -r '.databases[]' "${MANIFEST}")
  fi
  while IFS= read -r svc; do
    [ -z "${svc}" ] && continue
    svc="${svc//$'\r'/}"
    if [ "$DRY_RUN" -eq 1 ]; then
      echo "DRY-RUN: would restore ${svc}"
      continue
    fi
    dump=""
    for candidate in "${DEST_DIR}/${svc}.sql.gz.enc" "${DEST_DIR}/${svc}.sql.gz" "${DEST_DIR}/${svc}.sql"; do
      if [ -f "${candidate}" ]; then dump="${candidate}"; break; fi
    done
    if [ -z "${dump}" ]; then
      echo "ERROR: no dump for service '${svc}' under ${DEST_DIR}" >&2
      return 1
    fi
    echo "Restoring database '${svc}' from ${dump} ..."
    local args=(--yes)
    if [ -n "${ENV_FILE}" ]; then args+=(--env-file "${ENV_FILE}"); fi
    args+=(--isolated-fixture --isolated-ticket "${PARKIO_RESTORE_ISOLATED_TICKET}")
    PARKIO_RESTORE_PREFLIGHT_DONE=1 \
    PARKIO_RESTORE_ISOLATED_FIXTURE=1 \
    PARKIO_RESTORE_ISOLATED_TICKET="${PARKIO_RESTORE_ISOLATED_TICKET}" \
      "${ROOT}/scripts/restore-database.sh" "${svc}" "${dump}" "${args[@]}"
  done < <(jq -r '.databases[]' "${MANIFEST}")
}

restore_minio() {
  local stage=""
  local mirror_src=""
  local cleanup_stage=0

  # A dry run decrypts and copies nothing (docs/operations/restore-runbook.md); only the apply path
  # below opens the archive. The stamp preflight has checked it against SHA256SUMS only when the
  # stamp is COMPLETE: a dry run of a missing or incomplete stamp continues with a WARN and checks nothing.
  if [ "$DRY_RUN" -eq 1 ]; then
    if [ -f "${DEST_DIR}/minio.tar.gz.enc" ]; then
      echo "DRY-RUN: would decrypt ${DEST_DIR}/minio.tar.gz.enc and mirror it -> isolated MinIO destination (nothing decrypted, apply skipped)"
    else
      echo "DRY-RUN: would mirror ${DEST_DIR}/minio/${BUCKET} -> isolated MinIO destination (apply skipped)"
    fi
    return 0
  fi

  if [ -f "${DEST_DIR}/minio.tar.gz.enc" ] || [ -d "${DEST_DIR}/minio" ]; then
    stage="$(mktemp -d "${TMPDIR:-/tmp}/parkio-restore-minio.XXXXXX")"
    chmod 700 "${stage}"
    cleanup_stage=1
    if ! parkio_backup_unseal_minio "${DEST_DIR}" "${stage}"; then
      rm -rf "${stage}"
      return 1
    fi
    mirror_src="${stage}/minio/${BUCKET}"
    if [ ! -d "${mirror_src}" ]; then
      local alt
      alt="$(find "${stage}/minio" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -1 || true)"
      if [ -n "${alt}" ]; then
        mirror_src="${alt}"
      fi
    fi
  else
    mirror_src="${DEST_DIR}/minio/${BUCKET}"
    if [ ! -d "${mirror_src}" ]; then
      local alt
      alt="$(find "${DEST_DIR}/minio" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -1 || true)"
      if [ -n "${alt}" ]; then
        mirror_src="${alt}"
      fi
    fi
  fi

  if ! parkio_restore_isolated_fixture_ok; then
    echo "ERROR: MinIO apply requires a destination-bound isolated-fixture ticket." >&2
    if [ "${cleanup_stage}" -eq 1 ]; then rm -rf "${stage}"; fi
    return 3
  fi
  local dest_json restore_bucket minio_container network endpoint minio_user minio_password
  dest_json="$(parkio_restore_isolated_minio_json)" || {
    if [ "${cleanup_stage}" -eq 1 ]; then rm -rf "${stage}"; fi
    return 2
  }
  restore_bucket="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["bucket"])' <<<"${dest_json}")"
  minio_container="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["containerName"])' <<<"${dest_json}")"
  network="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["networkId"])' <<<"${dest_json}")"
  endpoint="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["endpoint"])' <<<"${dest_json}")"
  minio_user="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["user"])' <<<"${dest_json}")"
  minio_password="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["password"])' <<<"${dest_json}")"
  if [ ! -d "${mirror_src}" ]; then
    echo "ERROR: MinIO mirror not found: ${mirror_src}" >&2
    if [ "${cleanup_stage}" -eq 1 ]; then rm -rf "${stage}"; fi
    return 1
  fi
  local mc_image
  mc_image="${MINIO_MC_IMAGE:-ghcr.io/adberilgen35/parkio/mc@sha256:456b1e641897329fc9491f9bc8b31df351d728af9a328bf5653707af62d0d6bf}"
  # The credentials reach mc on stdin, never a process argument (see scripts/backup-minio.sh).
  printf '%s\n%s\n' "${minio_user}" "${minio_password}" | MSYS_NO_PATHCONV=1 docker run --rm -i \
    --network "${network}" \
    --entrypoint sh \
    -v "${mirror_src}:/restore:ro" \
    -e "BUCKET=${restore_bucket}" \
    -e "ENDPOINT=${endpoint}" \
    "${mc_image}" \
    -c '
      set -eu
      mc alias set local "$ENDPOINT"
      mc mb -p "local/${BUCKET}" >/dev/null 2>&1 || true
      mc mirror --overwrite /restore "local/${BUCKET}"
    '
  echo "MinIO restore completed from ${mirror_src} -> ${minio_container}/${restore_bucket}"
  if [ "${cleanup_stage}" -eq 1 ]; then rm -rf "${stage}"; fi
}

if [ "${ASSUME_YES}" != "yes" ] && [ "$DRY_RUN" -ne 1 ]; then
  echo "*** DESTRUCTIVE: this overwrites live databases and MinIO objects. ***"
  printf "Type RESTORE to proceed: "
  read -r reply
  if [ "${reply}" != "RESTORE" ]; then
    echo "Aborted." >&2
    exit 1
  fi
fi

replay_erasure_ledger() {
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "DRY-RUN: would replay ${DEST_DIR}/erasure-tombstones.json into isolated auth"
    return 0
  fi
  if ! parkio_restore_isolated_fixture_ok; then
    echo "ERROR: erasure replay requires a destination-bound isolated-fixture ticket." >&2
    return 3
  fi
  local dest_json auth_ref auth_user auth_db
  dest_json="$(parkio_restore_isolated_postgres_json auth)" || return 2
  auth_ref="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["containerId"])' <<<"${dest_json}")"
  auth_user="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["user"])' <<<"${dest_json}")"
  auth_db="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["database"])' <<<"${dest_json}")"
  local ledger="${PARKIO_RESTORE_MERGED_LEDGER:-${DEST_DIR}/erasure-tombstones.json}"
  PARKIO_RESTORE_REQUIRE_ERASURE_LEDGER="${PARKIO_RESTORE_REQUIRE_ERASURE_LEDGER:-1}" \
    parkio_replay_erasure_tombstones "${ledger}" \
      "${auth_ref}" \
      "${auth_user}" \
      "${auth_db}"
  echo "Erasure ledger replayed; do not serve traffic until auth POST /internal/erasure/replay (or Kafka) finishes participant erase."
}

case "${ONLY}" in
  ""|all)
    restore_databases
    replay_erasure_ledger
    restore_minio
    ;;
  databases|db)
    restore_databases
    replay_erasure_ledger
    ;;
  minio)
    restore_minio
    ;;
  *)
    echo "ERROR: --only must be all, databases, or minio" >&2
    exit 2
    ;;
esac

echo "Restore completed."
echo "Applications, publishers, schedulers, Slack and Fluent Bit were not started."
echo "A successful data restore is not authorization to expose applications."
if [ "${STAGE4_RECOVERY}" -eq 1 ]; then
  echo "Expose gate CLOSED in ${RECOVERY_DIR}: run the recovery-replay command (scripts/recovery-replay.sh up, run);"
  echo "scripts/lib/recovery-expose-gate.py open records OPEN only on its COMPLETE verdict."
fi
