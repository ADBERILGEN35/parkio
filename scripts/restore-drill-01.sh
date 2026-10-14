#!/usr/bin/env bash
#
# Parkio — restore drill 01 procedure: isolated, database-only restore of ONE retrieved
# stamp into a disposable PostgreSQL/PostGIS container. Starts no application service,
# relay, scheduler or sender. Documented in docs/operations/restore-drill-01-isolated-database.md
# and executed with synthetic encrypted fixtures by .github/workflows/restore-drill-01-procedure.yml.
#
# Usage:
#   PARKIO_DRILL_ID=rd-YYYYMMDD-01 BACKUP_ENCRYPT_PASSPHRASE=... \
#   scripts/restore-drill-01.sh --stamp DIR [--ledger-stamp DIR ...] \
#     [--supplemental-ledger FILE --supplemental-covered-through TS] \
#     --recovery-cutoff TS --container NAME --work DIR --evidence DIR \
#     [--env-file FILE] [--max-age-hours N] [--probe-default-egress] [--allow-privacy-blocked]
#
# Phases (each fails closed):
#   1. stamp preflight for the data stamp and every ledger stamp (never decrypts);
#   2. authoritative erasure set through --recovery-cutoff (BLOCKED => exit 3 before decrypting,
#      unless --allow-privacy-blocked, in which case the copy must be destroyed unexposed);
#   3. isolation preflight (credentials, providers, pollers, containers, optional egress);
#   4. per database: profile dump, create roles/database, restore with ON_ERROR_STOP,
#      row-count parity against the dump;
#   5. erasure replay of the authoritative set, then assert no tombstoned account is ACTIVE;
#   6. unpublished outbox counts (duplicate exposure if this copy became live).
#
# --work receives the merged ledger (identifiers), counts and restore stderr: keep it on the
# drill host and delete it at cleanup. --evidence receives only secret-free files.
#
# Exit: 0 = PASS, 1 = FAIL, 2 = usage, 3 = BLOCKED (privacy evidence incomplete).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/backup-common.sh
source "${ROOT}/scripts/lib/backup-common.sh"
# shellcheck source=lib/erasure-tombstones.sh
source "${ROOT}/scripts/lib/erasure-tombstones.sh"
# shellcheck source=lib/recovery-coordination.sh
source "${ROOT}/scripts/lib/recovery-coordination.sh"

STAMP_DIR=""; CUTOFF=""; CONTAINER=""; WORK=""; EVIDENCE=""; ENV_FILE=""
SUPPLEMENTAL=""; SUPPLEMENTAL_THROUGH=""; MAX_AGE=""; PROBE_EGRESS=0; ALLOW_BLOCKED=0
ADMIN_USER="${PARKIO_DRILL_ADMIN_USER:-rd_admin}"
LEDGER_STAMPS=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --stamp) STAMP_DIR="${2:-}"; shift 2 ;;
    --ledger-stamp) LEDGER_STAMPS+=("${2:-}"); shift 2 ;;
    --supplemental-ledger) SUPPLEMENTAL="${2:-}"; shift 2 ;;
    --supplemental-covered-through) SUPPLEMENTAL_THROUGH="${2:-}"; shift 2 ;;
    --recovery-cutoff) CUTOFF="${2:-}"; shift 2 ;;
    --container) CONTAINER="${2:-}"; shift 2 ;;
    --work) WORK="${2:-}"; shift 2 ;;
    --evidence) EVIDENCE="${2:-}"; shift 2 ;;
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --max-age-hours) MAX_AGE="${2:-}"; shift 2 ;;
    --probe-default-egress) PROBE_EGRESS=1; shift ;;
    --allow-privacy-blocked) ALLOW_BLOCKED=1; shift ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done
for required in STAMP_DIR CUTOFF CONTAINER WORK EVIDENCE; do
  if [ -z "${!required}" ]; then echo "ERROR: missing --${required,,}" >&2; exit 2; fi
done
if [ -z "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then
  echo "ERROR: BACKUP_ENCRYPT_PASSPHRASE must be set (hidden prompt, never a file)." >&2
  exit 2
fi
umask 077
mkdir -p "${WORK}" "${EVIDENCE}"
LIB="${ROOT}/scripts/lib"
export PARKIO_RESTORE_REQUIRE_ERASURE_LEDGER=1
STARTED="$(date -u +%s)"

fail() { echo "FAIL: $*" >&2; write_summary FAIL "$*"; exit 1; }
write_summary() {
  python3 - "$EVIDENCE/summary.json" "$1" "$2" "$STARTED" "$EVIDENCE" <<'PY'
import json, sys, time, pathlib
path, verdict, reason, started, evidence = sys.argv[1:]
ev = pathlib.Path(evidence)
parity = {p.name.split(".")[0]: json.loads(p.read_text()).get("verdict") for p in sorted(ev.glob("*.parity.json"))}
summary = {"tool": "restore-drill-01", "schemaVersion": 1, "verdict": verdict, "reason": reason,
           "elapsedSeconds": int(time.time()) - int(started), "parity": parity}
pathlib.Path(path).write_text(json.dumps(summary, indent=2) + "\n")
PY
}

# ---- 1. stamp integrity -------------------------------------------------------------
preflight_args=()
[ -n "${MAX_AGE}" ] && preflight_args+=(--max-age-hours "${MAX_AGE}")
[ -n "${PARKIO_DRILL_NOW:-}" ] && preflight_args+=(--now "${PARKIO_DRILL_NOW}")
echo "==> [1] stamp preflight"
python3 "${LIB}/restore-stamp-preflight.py" "${STAMP_DIR}" "${preflight_args[@]}" \
  > "${EVIDENCE}/stamp-preflight.json" || fail "data stamp preflight"
i=0
for ledger_stamp in "${LEDGER_STAMPS[@]}"; do
  i=$((i + 1))
  # Newer stamps contribute their ledger only; their dumps are not restored here.
  python3 "${LIB}/restore-stamp-preflight.py" "${ledger_stamp}" \
    > "${EVIDENCE}/ledger-stamp-${i}-preflight.json" || fail "ledger stamp ${i} preflight"
done

# ---- 2. authoritative erasure set -----------------------------------------------------
echo "==> [2] erasure set through ${CUTOFF}"
PRIVACY="PASS"
# Optional #102 recover. Never lowers CUTOFF. Directory store is not off-host.
if [ -n "${PARKIO_OFFHOST_STORE_DIR:-}" ]; then
  set +e
  OFFHOST_REPORT="$(parkio_offhost_erasure_supplement "${CUTOFF}" "${STAMP_DIR}" \
    "${WORK}/offhost-supplement.json" 2>"${WORK}/offhost-recover.err")"
  offhost_rc=$?
  set -e
  printf '%s\n' "${OFFHOST_REPORT}" > "${EVIDENCE}/offhost-erasure-recover.json"
  case "${offhost_rc}" in
    0)
      OFFHOST_THROUGH="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("coverageThrough") or "")' \
        <<< "${OFFHOST_REPORT}")"
      if [ -z "${OFFHOST_THROUGH}" ]; then
        fail "offhost recover PASS without coverageThrough"
      fi
      if [ -n "${SUPPLEMENTAL}" ]; then
        fail "do not mix a handwritten supplemental with offhost recover"
      fi
      SUPPLEMENTAL="${WORK}/offhost-supplement.json"
      SUPPLEMENTAL_THROUGH="${OFFHOST_THROUGH}"
      ;;
    3)
      PRIVACY="BLOCKED"
      if [ "${ALLOW_BLOCKED}" -ne 1 ]; then
        write_summary BLOCKED "offhost erasure coverage does not reach the recovery cutoff"
        echo "BLOCKED: offhost watermark does not reach ${CUTOFF}; do not lower cutoff; nothing decrypted." >&2
        exit 3
      fi
      echo "WARN: offhost coverage BLOCKED; this copy must never be exposed." >&2
      ;;
    *) fail "offhost erasure recover failed" ;;
  esac
fi
ledger_args=(--data-stamp "${STAMP_DIR}" --recovery-cutoff "${CUTOFF}" --out "${WORK}/erasure-set.json")
for ledger_stamp in "${LEDGER_STAMPS[@]}"; do ledger_args+=(--ledger-stamp "${ledger_stamp}"); done
if [ -n "${SUPPLEMENTAL}" ]; then
  ledger_args+=(--supplemental "${SUPPLEMENTAL}" --supplemental-covered-through "${SUPPLEMENTAL_THROUGH}")
fi
set +e
python3 "${LIB}/restore-erasure-ledger.py" "${ledger_args[@]}" > "${EVIDENCE}/erasure-set.json"
ledger_rc=$?
set -e
case "${ledger_rc}" in
  0) ;;
  3)
    PRIVACY="BLOCKED"
    if [ "${ALLOW_BLOCKED}" -ne 1 ]; then
      write_summary BLOCKED "erasure evidence does not reach the recovery cutoff"
      echo "BLOCKED: erasure evidence does not reach ${CUTOFF}; nothing was decrypted." >&2
      exit 3
    fi
    echo "WARN: continuing with privacy BLOCKED; this copy must never be exposed." >&2
    ;;
  *) fail "erasure set evidence invalid" ;;
esac

# ---- 3. isolation --------------------------------------------------------------------
echo "==> [3] isolation preflight"
docker ps --format '{{.Names}}' > "${WORK}/containers.txt"
iso_args=(--containers-file "${WORK}/containers.txt")
[ -n "${ENV_FILE}" ] && iso_args+=(--env-file "${ENV_FILE}")
[ "${PROBE_EGRESS}" -eq 1 ] && iso_args+=(--probe-default-egress)
python3 "${LIB}/restore-drill-isolation-preflight.py" "${iso_args[@]}" \
  > "${EVIDENCE}/isolation.json" || fail "isolation preflight"

# ---- 4. restore + parity --------------------------------------------------------------
# Admin queries use the target server's local psql (any patch is fine; they are not dumps).
# The restore stream uses PARKIO_RESTORE_PSQL_IMAGE when set so \\restrict dumps are
# applied by an identified client at least as new as the dump client. Dumps are never rewritten.
q() { docker exec -i "${CONTAINER}" psql -v ON_ERROR_STOP=1 -X -q -U "${ADMIN_USER}" "$@"; }
dec() {
  openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_ENCRYPT_PASSPHRASE \
    < "${STAMP_DIR}/$1.sql.gz.enc" | gunzip
}
restore_psql() {
  local role="$1" db="$2"
  if [ -n "${PARKIO_RESTORE_PSQL_IMAGE:-}" ]; then
    docker run --rm -i \
      --network "${PARKIO_RESTORE_PSQL_NETWORK:-rd-net}" \
      -e PGPASSWORD=dummy-drill \
      "${PARKIO_RESTORE_PSQL_IMAGE}" \
      psql -h "${PARKIO_RESTORE_PSQL_HOST:-${CONTAINER}}" -X -q -v ON_ERROR_STOP=1 \
        -U "${role}" -d "${db}"
  else
    docker exec -i "${CONTAINER}" psql -X -q -v ON_ERROR_STOP=1 -U "${role}" -d "${db}"
  fi
}
if [ -n "${PARKIO_RESTORE_PSQL_IMAGE:-}" ]; then
  RESTORE_CLIENT_VERSION="$(docker run --rm "${PARKIO_RESTORE_PSQL_IMAGE}" psql --version)"
else
  RESTORE_CLIENT_VERSION="$(docker exec "${CONTAINER}" psql --version)"
fi
TARGET_SERVER_VERSION="$(q -d postgres -At -c 'show server_version')"
POSTGIS_AVAILABLE="$(q -d postgres -At -c "select default_version from pg_available_extensions where name='postgis'")"
printf 'dump_client=(per profile)\nrestore_client=%s\ntarget_server=%s\npostgis_available=%s\nrestore_image=%s\n' \
  "${RESTORE_CLIENT_VERSION}" "${TARGET_SERVER_VERSION}" "${POSTGIS_AVAILABLE}" \
  "${PARKIO_RESTORE_PSQL_IMAGE:-container-local}" > "${EVIDENCE}/client-tooling.txt"
: > "${EVIDENCE}/timings.txt"
echo "==> [4] restore (auth first)"
for entry in "${PARKIO_DB_SERVICES[@]}"; do
  IFS=":" read -r svc _ role db <<< "${entry}"
  if [ "$(q -d postgres -At -c "select count(*) from pg_database where datname='${db}'")" != "0" ]; then
    fail "target database ${db} already exists; the drill needs a fresh container"
  fi
  dec "${svc}" | python3 "${LIB}/restore-dump-profile.py" profile > "${EVIDENCE}/${svc}.profile.json" \
    || fail "profile ${svc} (decryption, gzip or truncated dump)"
  python3 "${LIB}/restore-client-compat.py" \
    --dump-profile "${EVIDENCE}/${svc}.profile.json" \
    --restore-client-version "${RESTORE_CLIENT_VERSION}" \
    --target-server-version "${TARGET_SERVER_VERSION}" \
    --postgis-available-version "${POSTGIS_AVAILABLE}" \
    > "${EVIDENCE}/${svc}.compat.json" || fail "client compatibility ${svc} (do not strip dump meta-commands)"
  roles="$(python3 -c 'import json,sys; print(" ".join(json.load(open(sys.argv[1]))["grantRoles"]))' \
    "${EVIDENCE}/${svc}.profile.json")"
  for r in ${roles} "${role}"; do
    q -d postgres -c "DO \$\$BEGIN CREATE ROLE \"${r}\" LOGIN PASSWORD 'dummy-drill'; EXCEPTION WHEN duplicate_object THEN NULL; END\$\$"
  done
  # Mirrors production, where each service user is its container's superuser; the --clean
  # dump drops/recreates extensions (postgis) and needs that privilege.
  q -d postgres -c "ALTER ROLE \"${role}\" SUPERUSER"
  q -d postgres -c "CREATE DATABASE \"${db}\" OWNER \"${role}\""
  started_svc="$(date -u +%s)"
  if ! dec "${svc}" | restore_psql "${role}" "${db}" \
      > /dev/null 2> "${WORK}/${svc}.restore.err"; then
    fail "restore ${svc} (details in work dir only; may quote data)"
  fi
  python3 "${LIB}/restore-dump-profile.py" count-sql "${EVIDENCE}/${svc}.profile.json" \
    | q -d "${db}" -At -F'|' > "${WORK}/${svc}.counts"
  python3 "${LIB}/restore-dump-profile.py" compare "${EVIDENCE}/${svc}.profile.json" "${WORK}/${svc}.counts" \
    > "${EVIDENCE}/${svc}.parity.json" || fail "row-count parity ${svc}"
  echo "${svc} restore_seconds=$(( $(date -u +%s) - started_svc ))" >> "${EVIDENCE}/timings.txt"
done

# ---- 5. erasure replay before any exposure --------------------------------------------
echo "==> [5] erasure replay"
auth_entry="$(printf '%s\n' "${PARKIO_DB_SERVICES[@]}" | grep '^auth:')"
IFS=":" read -r _ _ auth_role auth_db <<< "${auth_entry}"
ids_sql() {
  python3 - "${WORK}/erasure-set.json" <<'PY'
import json, sys
ids = [e["authUserId"] for e in json.load(open(sys.argv[1]))]
print("(VALUES " + ",".join(f"('{i}'::uuid)" for i in ids) + ") AS s(id)" if ids else "(SELECT NULL::uuid WHERE false) AS s(id)")
PY
}
active_in_set() {
  { echo "SELECT count(*) FROM auth_users u JOIN $(ids_sql) ON s.id = u.id WHERE u.status = 'ACTIVE';"; } \
    | q -d "${auth_db}" -At
}
before="$(active_in_set)"
parkio_replay_erasure_tombstones "${WORK}/erasure-set.json" "${CONTAINER}" "${auth_role}" "${auth_db}" >/dev/null \
  || fail "erasure replay"
after="$(active_in_set)"
tombstones="$(q -d "${auth_db}" -At -c "select count(*) from erased_user_tombstones")"
printf 'active_in_erasure_set_before_replay=%s\nactive_in_erasure_set_after_replay=%s\nrestored_tombstones=%s\nprivacy=%s\n' \
  "${before}" "${after}" "${tombstones}" "${PRIVACY}" > "${EVIDENCE}/erasure-replay.txt"
[ "${after}" = "0" ] || fail "tombstoned accounts still ACTIVE after replay"

# ---- 6. duplicate exposure: unpublished outbox rows --------------------------------------
echo "==> [6] outbox exposure"
: > "${EVIDENCE}/outbox-pending.txt"
for entry in "${PARKIO_DB_SERVICES[@]}"; do
  IFS=":" read -r svc _ _ db <<< "${entry}"
  if [ "$(q -d "${db}" -At -c "select to_regclass('public.outbox_events') is not null")" = "t" ]; then
    q -d "${db}" -At -F'|' -c "select '${svc}','outbox_events','unpublished',count(*) from outbox_events where not published" \
      >> "${EVIDENCE}/outbox-pending.txt"
  fi
done
IFS=":" read -r _ _ _ gw_db <<< "$(printf '%s\n' "${PARKIO_DB_SERVICES[@]}" | grep '^gateway:')"
if [ "$(q -d "${gw_db}" -At -c "select to_regclass('public.waitlist_ops_notification_outbox') is not null")" = "t" ]; then
  q -d "${gw_db}" -At -F'|' -c "select 'gateway','waitlist_ops_notification_outbox',status,count(*) from waitlist_ops_notification_outbox group by status order by status" \
    >> "${EVIDENCE}/outbox-pending.txt"
fi

echo "total_seconds=$(( $(date -u +%s) - STARTED ))" >> "${EVIDENCE}/timings.txt"
if [ "${PRIVACY}" = "BLOCKED" ]; then
  write_summary BLOCKED "restore PASS but erasure evidence incomplete; destroy unexposed"
  echo "BLOCKED: restore succeeded, privacy evidence incomplete." >&2
  exit 3
fi
write_summary PASS "restore, parity and erasure replay passed"
echo "PASS: restore drill 01 (${PARKIO_DRILL_ID:-no-id})"
