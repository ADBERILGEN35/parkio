#!/usr/bin/env bash
#
# Parkio - dated backup acceptance for ONE stamp (U14). Five outcomes, each recorded on its own:
#
#   1. complete          the local stamp: COMPLETE binds SHA256SUMS, every listed file matches, and
#                        the manifest describes a full, encrypted stamp (restore-stamp-preflight.py).
#   2. remote-presence   the offsite listing holds every SHA256SUMS entry plus SHA256SUMS and
#                        COMPLETE, each at its local byte size. Names and sizes only; nothing is
#                        downloaded.
#   3. remote-integrity  the stamp pulled from offsite into a new directory passes its checksums,
#                        and its SHA256SUMS digest and COMPLETE equal the local stamp's.
#   4. decrypt           every encrypted artifact of the PULLED copy decrypts and decompresses,
#                        streamed; no plaintext is written anywhere (--decrypt).
#   5. isolated-restore  scripts/restore-drill-01.sh on the PULLED copy, into the disposable
#                        container the operator names (--isolated-restore).
#
# Each outcome is PASS, FAIL, BLOCKED (the restore drill's privacy gate) or NOT_RUN with a reason.
# A failure in one never changes another; 4 and 5 use only a pulled copy that passed 3.
# Running this against real backups needs separate authorization (U14): it reads the stamp,
# pulls it, and with the flags decrypts and restores it into the named disposable container.
# It never touches a live database.
#
# Usage:
#   PARKIO_ENV_FILE=docker/.env.invite-production scripts/backup-dated-acceptance.sh \
#     --stamp-dir /var/backups/parkio/<stamp> --evidence DIR --work DIR \
#     [--decrypt] [--isolated-restore -- --recovery-cutoff TS --container NAME [more drill args]]
#
# --evidence receives only secret-free files: acceptance.json, acceptance.md, the local preflight,
# the remote listing and the restore drill's own evidence. --work receives the pulled copy and the
# raw tool logs (which may name hosts); keep it on the host and delete it afterwards.
# Exit: 0 = all five PASS; 1 = at least one FAIL; 4 = no FAIL, but something BLOCKED or NOT_RUN;
#       2 = usage.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/backup-common.sh
source "$ROOT/scripts/lib/backup-common.sh"

STAMP_DIR=""
EVIDENCE=""
WORK=""
ENV_FILE="${PARKIO_ENV_FILE:-}"
DECRYPT=0
RESTORE=0
RESTORE_ARGS=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    --stamp-dir) STAMP_DIR="${2:-}"; shift 2 ;;
    --evidence) EVIDENCE="${2:-}"; shift 2 ;;
    --work) WORK="${2:-}"; shift 2 ;;
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --decrypt) DECRYPT=1; shift ;;
    --isolated-restore) RESTORE=1; shift ;;
    --) shift; RESTORE_ARGS=("$@"); break ;;
    -h|--help) sed -n '2,33p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [ -z "${STAMP_DIR}" ] || [ -z "${EVIDENCE}" ] || [ -z "${WORK}" ]; then
  echo "Usage: $0 --stamp-dir DIR --evidence DIR --work DIR [--decrypt] [--isolated-restore -- ARGS]" >&2
  exit 2
fi
if [ ! -d "${STAMP_DIR}" ]; then
  echo "ERROR: stamp directory not found: ${STAMP_DIR}" >&2
  exit 2
fi
for dir in "${EVIDENCE}" "${WORK}"; do
  if [ -e "${dir}" ]; then
    echo "ERROR: ${dir} must not exist yet; each acceptance run gets new directories." >&2
    exit 2
  fi
done
if [ "${RESTORE}" -eq 1 ] && [ "${#RESTORE_ARGS[@]}" -eq 0 ]; then
  echo "ERROR: --isolated-restore needs the restore drill's arguments after --." >&2
  exit 2
fi
for arg in "${RESTORE_ARGS[@]}"; do
  case "${arg}" in
    --stamp|--work|--evidence)
      echo "ERROR: ${arg} is set by this script (the pulled copy and its own directories)." >&2
      exit 2
      ;;
  esac
done

umask 077
mkdir -p "${EVIDENCE}" "${WORK}"
STAMP="$(basename "${STAMP_DIR%/}")"
OUTCOMES="${WORK}/outcomes.tsv"
: > "${OUTCOMES}"
STARTED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
parkio_backup_load_env "${ENV_FILE}"

record() { # id result detail
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "${OUTCOMES}"
  echo "$1: $2 - $3"
}

LOCAL_SUMS="$(sed -n 's/^sha256sums=//p' "${STAMP_DIR}/COMPLETE" 2>/dev/null | head -1 || true)"

# ---- 1. complete ---------------------------------------------------------------------------
if python3 "${ROOT}/scripts/lib/restore-stamp-preflight.py" "${STAMP_DIR}" --scope full \
    > "${EVIDENCE}/local-preflight.json" 2> "${WORK}/local-preflight.stderr"; then
  record complete PASS "stamp preflight PASS (full scope): COMPLETE binds SHA256SUMS, files verified, minioOk=1"
else
  failed_checks="$(python3 - "${EVIDENCE}/local-preflight.json" <<'PY' 2>/dev/null || echo "unreadable report"
import json, sys
report = json.load(open(sys.argv[1]))
print(", ".join(c["id"] for c in report["checks"] if c["status"] == "FAIL") or "see report")
PY
)"
  record complete FAIL "stamp preflight FAIL: ${failed_checks}"
fi

# ---- 2. remote-presence --------------------------------------------------------------------
OFFSITE_KIND="$(parkio_backup_offsite_kind)"
if [ "${OFFSITE_KIND}" = none ]; then
  record remote-presence NOT_RUN "no offsite is configured"
elif listing="$(parkio_backup_offsite_list "${STAMP}" 2> "${WORK}/remote-listing.stderr")"; then
  printf '%s\n' "${listing}" | sed '/^$/d' > "${EVIDENCE}/remote-listing.tsv"
  if presence="$(python3 - "${STAMP_DIR}" "${EVIDENCE}/remote-listing.tsv" <<'PY'
import os, sys
stamp, listing = sys.argv[1], sys.argv[2]
expected = {"SHA256SUMS", "COMPLETE"}
try:
    for line in open(os.path.join(stamp, "SHA256SUMS")):
        if line.strip():
            expected.add(line.split(None, 1)[1].strip().lstrip("*").removeprefix("./"))
except OSError:
    pass
remote = {}
for line in open(listing):
    name, _, size = line.rstrip("\n").partition("\t")
    remote[name] = size
missing = sorted(n for n in expected if n not in remote)
mismatched = sorted(n for n in expected if n in remote and os.path.isfile(os.path.join(stamp, n))
                    and remote[n] != str(os.path.getsize(os.path.join(stamp, n))))
extra = sorted(set(remote) - expected)
print(f"{len(expected) - len(missing)}/{len(expected)} expected objects listed; "
      f"missing={missing} size_mismatch={mismatched} unexpected={extra}")
sys.exit(1 if missing or mismatched else 0)
PY
  )"; then
    record remote-presence PASS "${presence}"
  else
    record remote-presence FAIL "${presence}"
  fi
else
  record remote-presence FAIL "offsite listing failed (raw error in the work directory)"
fi

# ---- 3. remote-integrity -------------------------------------------------------------------
PULLED="${WORK}/pulled"
PULLED_OK=0
if [ "${OFFSITE_KIND}" = none ]; then
  record remote-integrity NOT_RUN "no offsite is configured"
elif "${ROOT}/scripts/backup-offsite-pull.sh" ${ENV_FILE:+--env-file "${ENV_FILE}"} \
    --stamp "${STAMP}" --dest "${PULLED}" > "${WORK}/pull.log" 2>&1; then
  pulled_sums="$(sha256sum "${PULLED}/SHA256SUMS" | awk '{print $1}')"
  if [ -n "${LOCAL_SUMS}" ] && [ "${pulled_sums}" = "${LOCAL_SUMS}" ] \
      && cmp -s "${PULLED}/COMPLETE" "${STAMP_DIR}/COMPLETE"; then
    PULLED_OK=1
    record remote-integrity PASS "pulled copy passes its checksums; SHA256SUMS digest ${pulled_sums} and COMPLETE equal the local stamp's"
  else
    record remote-integrity FAIL "pulled copy passes its own checksums but is not the local sealed stamp (SHA256SUMS digest ${pulled_sums})"
  fi
else
  record remote-integrity FAIL "pull or checksum verification failed (raw log in the work directory)"
fi

# ---- 4. decrypt ----------------------------------------------------------------------------
if [ "${DECRYPT}" -ne 1 ]; then
  record decrypt NOT_RUN "--decrypt was not requested"
elif [ -z "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then
  record decrypt NOT_RUN "BACKUP_ENCRYPT_PASSPHRASE is not set"
elif [ "${PULLED_OK}" -ne 1 ]; then
  record decrypt NOT_RUN "no verified remote copy to decrypt (see remote-integrity)"
else
  decrypted=0
  failed=()
  shopt -s nullglob
  for artifact in "${PULLED}"/*.sql.gz.enc; do
    if openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_ENCRYPT_PASSPHRASE -in "${artifact}" 2>/dev/null \
        | gzip -t 2>/dev/null; then
      decrypted=$((decrypted + 1))
    else
      failed+=("$(basename "${artifact}")")
    fi
  done
  shopt -u nullglob
  if [ -f "${PULLED}/minio.tar.gz.enc" ]; then
    if openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_ENCRYPT_PASSPHRASE -in "${PULLED}/minio.tar.gz.enc" 2>/dev/null \
        | tar -tzf - > /dev/null 2>&1; then
      decrypted=$((decrypted + 1))
    else
      failed+=("minio.tar.gz.enc")
    fi
  fi
  if [ "${#failed[@]}" -eq 0 ] && [ "${decrypted}" -gt 0 ]; then
    record decrypt PASS "${decrypted} encrypted artifacts decrypt and decompress (streamed, nothing written)"
  elif [ "${decrypted}" -eq 0 ] && [ "${#failed[@]}" -eq 0 ]; then
    record decrypt FAIL "the pulled copy holds no encrypted artifact"
  else
    record decrypt FAIL "${#failed[@]} artifacts do not decrypt or decompress: ${failed[*]}"
  fi
fi

# ---- 5. isolated-restore -------------------------------------------------------------------
DESTINATION=""
for i in "${!RESTORE_ARGS[@]}"; do
  if [ "${RESTORE_ARGS[$i]}" = --container ]; then DESTINATION="${RESTORE_ARGS[$((i + 1))]:-}"; fi
done
if [ "${RESTORE}" -ne 1 ]; then
  record isolated-restore NOT_RUN "--isolated-restore was not requested"
elif [ "${PULLED_OK}" -ne 1 ]; then
  record isolated-restore NOT_RUN "no verified remote copy to restore (see remote-integrity)"
else
  set +e
  "${ROOT}/scripts/restore-drill-01.sh" --stamp "${PULLED}" --work "${WORK}/restore-work" \
    --evidence "${EVIDENCE}/isolated-restore" ${ENV_FILE:+--env-file "${ENV_FILE}"} "${RESTORE_ARGS[@]}" \
    > "${WORK}/restore-drill.log" 2>&1
  drill_rc=$?
  set -e
  case "${drill_rc}" in
    0) record isolated-restore PASS "restore-drill-01 PASS into container ${DESTINATION}" ;;
    3) record isolated-restore BLOCKED "restore-drill-01 BLOCKED (privacy evidence incomplete); nothing was applied" ;;
    *) record isolated-restore FAIL "restore-drill-01 exit ${drill_rc} (raw log in the work directory)" ;;
  esac
fi

# ---- report ---------------------------------------------------------------------------------
python3 - "${OUTCOMES}" "${EVIDENCE}" "${STAMP}" "${STARTED}" "${LOCAL_SUMS}" "${OFFSITE_KIND}" \
    "${DESTINATION}" "${STAMP_DIR%/}.offsite-receipt.json" <<'PY'
import datetime, json, pathlib, sys
outcomes_path, evidence, stamp, started, local_sums, kind, destination, receipt_path = sys.argv[1:]
outcomes = []
for line in open(outcomes_path):
    oid, result, detail = line.rstrip("\n").split("\t", 2)
    outcomes.append({"id": oid, "result": result, "detail": detail})
results = [o["result"] for o in outcomes]
verdict = "FAIL" if "FAIL" in results else ("INCOMPLETE" if set(results) - {"PASS"} else "PASS")
# The post-upload receipt beside the stamp (U14, when present) is recorded, not trusted: the
# outcomes above check the offsite copy directly.
receipt = {"present": False}
try:
    data = json.loads(pathlib.Path(receipt_path).read_text())
    receipt = {"present": True, "uploaded": data.get("uploaded"),
               "target": (data.get("offsite") or {}).get("target"),
               "bindsLocalComplete": (data.get("sealed") or {}).get("sha256sums") == local_sums}
except (OSError, ValueError):
    pass
report = {
    "tool": "backup-dated-acceptance", "schemaVersion": 1,
    "started": started, "finished": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "stamp": stamp, "localSha256sums": local_sums or None, "offsiteKind": kind,
    "receipt": receipt, "isolatedDestination": {"container": destination} if destination else None,
    "outcomes": outcomes, "verdict": verdict,
}
ev = pathlib.Path(evidence)
(ev / "acceptance.json").write_text(json.dumps(report, indent=2) + "\n")
rows = "\n".join(f"| {o['id']} | {o['result']} | {o['detail']} |" for o in outcomes)
(ev / "acceptance.md").write_text(
    f"# Backup acceptance {stamp}\n\nStarted {started}; verdict **{verdict}**.\n\n"
    f"| outcome | result | detail |\n|---|---|---|\n{rows}\n")
print(f"verdict={verdict}")
PY

if grep -q $'\tFAIL\t' "${OUTCOMES}"; then
  exit 1
fi
if grep -q -v $'\tPASS\t' "${OUTCOMES}"; then
  exit 4
fi
exit 0
