#!/usr/bin/env bash
#
# Parkio - dated backup acceptance for ONE stamp (U14). Five outcomes, each recorded on its own:
#
#   1. complete          the local stamp: COMPLETE binds SHA256SUMS, every listed file matches, and
#                        the manifest describes a full, encrypted stamp (restore-stamp-preflight.py).
#   2. remote-presence   the offsite listing holds exactly the SHA256SUMS entries plus SHA256SUMS
#                        and COMPLETE, each at its local byte size: nothing missing, nothing extra.
#                        Names and sizes only; nothing is downloaded.
#   3. remote-integrity  a copy pulled from offsite passes its checksums, holds no file the seal does
#                        not list, and its SHA256SUMS digest and COMPLETE equal the sealed stamp's.
#   4. decrypt           every encrypted artifact of that copy decrypts and decompresses, streamed;
#                        no plaintext is written anywhere (--decrypt).
#   5. isolated-restore  scripts/restore-drill-01.sh on that copy, in a scrubbed environment, into
#                        the disposable container the operator names (--isolated-restore).
#
# The outcomes come from two hosts, as docs/operations/backup-dated-acceptance.md describes:
#
#   backup host (production-class, holds the stamp and offsite read access): outcomes 1-3, and 4
#   if passphrase custody allows it there.
#     scripts/backup-dated-acceptance.sh --stamp-dir DIR --evidence DIR --work DIR [--decrypt]
#
#   drill host (disposable, per restore-drill-01-isolated-database.md, after the stamp was pulled
#   with backup-offsite-pull.sh and egress was denied): outcomes 3-5 on that copy.
#     scripts/backup-dated-acceptance.sh --pulled DIR --expected-sha256sums HEX \
#       --evidence DIR --work DIR [--decrypt] \
#       [--isolated-restore [--drill-env-file FILE] -- --recovery-cutoff TS --container NAME [...]]
#
#   then, anywhere: one record from the two.
#     scripts/backup-dated-acceptance.sh --combine BACKUP_HOST.json DRILL_HOST.json --evidence DIR
#
# Each outcome is PASS, FAIL, BLOCKED (the drill's privacy gate) or NOT_RUN with a reason; a
# failure in one never changes another. Outcomes 4 and 5 use only a copy that passed 3. The drill
# never sees the offsite credentials or the production env file: it runs in a scrubbed environment
# (an allowlist). Values, the passphrase among them, stay in the environment and never appear on any
# argv. Reading real backups, pulling, decrypting and restoring need separate authorization.
#
# --evidence receives only secret-free files. --work receives the pulled copy (backup host) and
# raw tool logs; keep it on the host and delete it afterwards.
# Exit: 0 = all five PASS (only a combined record can); 1 = a FAIL; 4 = no FAIL, but something
#       BLOCKED or NOT_RUN; 2 = usage; 5 = the report itself could not be written.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/backup-common.sh
source "$ROOT/scripts/lib/backup-common.sh"

STAMP_DIR=""
PULLED_INPUT=""
EXPECTED_SUMS=""
EVIDENCE=""
WORK=""
ENV_FILE="${PARKIO_ENV_FILE:-}"
DRILL_ENV_FILE=""
DECRYPT=0
RESTORE=0
RESTORE_ARGS=()
COMBINE=()

usage() {
  sed -n '17,30p' "$0" >&2
  exit 2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --stamp-dir) STAMP_DIR="${2:-}"; shift 2 ;;
    --pulled) PULLED_INPUT="${2:-}"; shift 2 ;;
    --expected-sha256sums) EXPECTED_SUMS="${2:-}"; shift 2 ;;
    --evidence) EVIDENCE="${2:-}"; shift 2 ;;
    --work) WORK="${2:-}"; shift 2 ;;
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --drill-env-file) DRILL_ENV_FILE="${2:-}"; shift 2 ;;
    --decrypt) DECRYPT=1; shift ;;
    --isolated-restore) RESTORE=1; shift ;;
    --combine) COMBINE=("${2:-}" "${3:-}"); shift 3 ;;
    --) shift; RESTORE_ARGS=("$@"); break ;;
    -h|--help) sed -n '2,42p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [ -z "${EVIDENCE}" ]; then usage; fi
if [ -e "${EVIDENCE}" ]; then
  echo "ERROR: ${EVIDENCE} must not exist yet; each record gets a new directory." >&2
  exit 2
fi

# ---- combine two host records into one dated record -----------------------------------------
if [ "${#COMBINE[@]}" -gt 0 ]; then
  if [ -z "${COMBINE[0]}" ] || [ -z "${COMBINE[1]:-}" ] || [ ! -f "${COMBINE[0]}" ] || [ ! -f "${COMBINE[1]}" ]; then
    echo "ERROR: --combine needs the backup-host and the drill-host acceptance.json." >&2
    exit 2
  fi
  umask 077
  mkdir -p "${EVIDENCE}"
  set +e
  python3 - "${COMBINE[0]}" "${COMBINE[1]}" "${EVIDENCE}" <<'PY'
import datetime, hashlib, json, pathlib, sys
backup_path, drill_path, evidence = sys.argv[1:]
def load(path):
    raw = pathlib.Path(path).read_bytes()
    return json.loads(raw), hashlib.sha256(raw).hexdigest()
backup, backup_sha = load(backup_path)
drill, drill_sha = load(drill_path)
if backup.get("mode") != "backup-host" or drill.get("mode") != "drill-host":
    print("ERROR: --combine takes a backup-host record, then a drill-host record", file=sys.stderr)
    sys.exit(2)
byid = lambda record: {o["id"]: o for o in record["outcomes"]}
b, d = byid(backup), byid(drill)
same_seal = (backup.get("stamp") == drill.get("stamp")
             and backup.get("localSha256sums") and backup["localSha256sums"] == drill.get("expectedSha256sums"))
def pick(oid):
    if not same_seal:
        return {"id": oid, "result": "FAIL", "detail": "the two records are not for the same sealed stamp "
                f"(stamps {backup.get('stamp')} and {drill.get('stamp')}; SHA256SUMS digests "
                f"{backup.get('localSha256sums')} and {drill.get('expectedSha256sums')})"}
    if oid in ("complete", "remote-presence"):
        return dict(b[oid], detail="backup host: " + b[oid]["detail"])
    if oid == "remote-integrity":
        results = {b[oid]["result"], d[oid]["result"]}
        result = "FAIL" if "FAIL" in results else ("PASS" if results == {"PASS"} else "NOT_RUN")
        return {"id": oid, "result": result,
                "detail": f"backup host: {b[oid]['result']}; drill host: {d[oid]['result']}"}
    if oid == "decrypt":
        source, host = (d, "drill host") if d[oid]["result"] != "NOT_RUN" else (b, "backup host")
        return dict(source[oid], detail=f"{host}: " + source[oid]["detail"])
    return dict(d[oid], detail="drill host: " + d[oid]["detail"])
outcomes = [pick(oid) for oid in ("complete", "remote-presence", "remote-integrity", "decrypt", "isolated-restore")]
results = [o["result"] for o in outcomes]
verdict = "FAIL" if "FAIL" in results else ("INCOMPLETE" if set(results) - {"PASS"} else "PASS")
report = {"tool": "backup-dated-acceptance", "schemaVersion": 2, "mode": "combined",
          "combined": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
          "stamp": backup.get("stamp"), "localSha256sums": backup.get("localSha256sums"),
          "sources": {"backupHost": {"started": backup.get("started"), "sha256": backup_sha},
                      "drillHost": {"started": drill.get("started"), "sha256": drill_sha}},
          "isolatedDestination": drill.get("isolatedDestination"), "outcomes": outcomes, "verdict": verdict}
ev = pathlib.Path(evidence)
(ev / "acceptance.json").write_text(json.dumps(report, indent=2) + "\n")
rows = "\n".join(f"| {o['id']} | {o['result']} | {o['detail']} |" for o in outcomes)
(ev / "acceptance.md").write_text(f"# Backup acceptance {report['stamp']} (combined)\n\nVerdict **{verdict}**.\n\n"
                                  f"| outcome | result | detail |\n|---|---|---|\n{rows}\n")
print(f"verdict={verdict}")
PY
  rc=$?
  set -e
  if [ "${rc}" -eq 2 ]; then exit 2; fi
  verdict="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["verdict"])' "${EVIDENCE}/acceptance.json" 2>/dev/null || true)"
  if [ "${rc}" -ne 0 ] || [ -z "${verdict}" ]; then
    echo "ERROR: the combined report could not be written." >&2
    exit 5
  fi
  case "${verdict}" in PASS) exit 0 ;; FAIL) exit 1 ;; *) exit 4 ;; esac
fi

# ---- argument checks for the two host modes --------------------------------------------------
if [ -z "${WORK}" ]; then usage; fi
if [ -n "${STAMP_DIR}" ] && [ -n "${PULLED_INPUT}" ]; then
  echo "ERROR: --stamp-dir (backup host) and --pulled (drill host) are separate runs." >&2
  exit 2
fi
if [ -n "${STAMP_DIR}" ]; then
  MODE=backup-host
  if [ ! -d "${STAMP_DIR}" ]; then echo "ERROR: stamp directory not found: ${STAMP_DIR}" >&2; exit 2; fi
  if [ "${RESTORE}" -eq 1 ]; then
    echo "ERROR: outcome 5 runs on a disposable drill host (--pulled), never next to the production stamp." >&2
    exit 2
  fi
elif [ -n "${PULLED_INPUT}" ]; then
  MODE=drill-host
  if [ ! -d "${PULLED_INPUT}" ]; then echo "ERROR: pulled copy not found: ${PULLED_INPUT}" >&2; exit 2; fi
  if ! printf '%s' "${EXPECTED_SUMS}" | grep -Eq '^[0-9a-f]{64}$'; then
    echo "ERROR: --pulled needs --expected-sha256sums: localSha256sums from the backup-host record." >&2
    exit 2
  fi
  if [ -n "${ENV_FILE}" ]; then
    echo "ERROR: the drill host takes no --env-file or PARKIO_ENV_FILE: the copy is already pulled, and the drill gets only --drill-env-file." >&2
    exit 2
  fi
else
  usage
fi
if [ -e "${WORK}" ]; then
  echo "ERROR: ${WORK} must not exist yet; each record gets a new directory." >&2
  exit 2
fi
if [ "${RESTORE}" -eq 1 ] && [ "${#RESTORE_ARGS[@]}" -eq 0 ]; then
  echo "ERROR: --isolated-restore needs the restore drill's arguments after --." >&2
  exit 2
fi
for arg in "${RESTORE_ARGS[@]}"; do
  case "${arg}" in
    --stamp|--work|--evidence|--env-file)
      echo "ERROR: ${arg} is set by this script (the verified copy, its own directories, --drill-env-file)." >&2
      exit 2
      ;;
    --allow-privacy-blocked)
      echo "ERROR: --allow-privacy-blocked would restore despite incomplete erasure evidence; not in an acceptance." >&2
      exit 2
      ;;
  esac
done

umask 077
mkdir -p "${EVIDENCE}" "${WORK}"
OUTCOMES="${WORK}/outcomes.tsv"
: > "${OUTCOMES}"
STARTED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

record() { # id result detail
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "${OUTCOMES}"
  echo "$1: $2 - $3"
}

# Checks a copy of the stamp: its COMPLETE, its checksums, no file outside the seal, and the
# SHA256SUMS digest (and COMPLETE, when a local one is given) against the sealed stamp.
verify_copy() { # dir expected-digest [local COMPLETE]
  python3 - "$1" "$2" "${3:-}" <<'PY'
import hashlib, pathlib, sys
copy, expected, local_complete = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()
problems = []
if not (copy / "COMPLETE").is_file() or not (copy / "SHA256SUMS").is_file():
    print("the copy has no COMPLETE or SHA256SUMS")
    sys.exit(1)
listed = {}
for line in (copy / "SHA256SUMS").read_text().splitlines():
    if line.strip():
        value, name = line.split(None, 1)
        listed[name.strip().lstrip("*").removeprefix("./")] = value
present = {p.relative_to(copy).as_posix() for p in copy.rglob("*") if p.is_file()} - {"SHA256SUMS", "COMPLETE"}
unlisted = sorted(present - set(listed))
missing = sorted(set(listed) - present)
mismatched = sorted(n for n in set(listed) & present if sha(copy / n) != listed[n])
digest = sha(copy / "SHA256SUMS")
complete = dict(l.split("=", 1) for l in (copy / "COMPLETE").read_text().splitlines() if "=" in l)
if unlisted: problems.append(f"files outside the seal {unlisted}")
if missing or mismatched: problems.append(f"checksum failures missing={missing} mismatched={mismatched}")
if complete.get("sha256sums") != digest: problems.append("COMPLETE does not bind this SHA256SUMS")
if digest != expected: problems.append(f"SHA256SUMS digest {digest} is not the sealed stamp's")
if local_complete and pathlib.Path(local_complete).read_bytes() != (copy / "COMPLETE").read_bytes():
    problems.append("COMPLETE differs from the local stamp's")
if problems:
    print("; ".join(problems))
    sys.exit(1)
print(f"{len(listed)} files verified, nothing outside the seal; SHA256SUMS digest {digest} is the sealed stamp's")
PY
}

if [ "${MODE}" = backup-host ]; then
  STAMP="$(basename "${STAMP_DIR%/}")"
  parkio_backup_load_env "${ENV_FILE}"
  LOCAL_SUMS="$(sed -n 's/^sha256sums=//p' "${STAMP_DIR}/COMPLETE" 2>/dev/null | head -1 || true)"

  # ---- 1. complete -------------------------------------------------------------------------
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

  # ---- 2. remote-presence ------------------------------------------------------------------
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
sys.exit(1 if missing or mismatched or extra else 0)
PY
    )"; then
      record remote-presence PASS "${presence}"
    else
      record remote-presence FAIL "${presence}"
    fi
  else
    record remote-presence FAIL "offsite listing failed (raw error in the work directory)"
  fi

  # ---- 3. remote-integrity -----------------------------------------------------------------
  COPY="${WORK}/pulled"
  COPY_OK=0
  if [ "${OFFSITE_KIND}" = none ]; then
    record remote-integrity NOT_RUN "no offsite is configured"
  elif "${ROOT}/scripts/backup-offsite-pull.sh" ${ENV_FILE:+--env-file "${ENV_FILE}"} \
      --stamp "${STAMP}" --dest "${COPY}" > "${WORK}/pull.log" 2>&1; then
    if detail="$(verify_copy "${COPY}" "${LOCAL_SUMS}" "${STAMP_DIR}/COMPLETE")"; then
      COPY_OK=1
      record remote-integrity PASS "pulled copy: ${detail}"
    else
      record remote-integrity FAIL "pulled copy: ${detail}"
    fi
  else
    record remote-integrity FAIL "pull or checksum verification failed (raw log in the work directory)"
  fi
else
  # Drill host: outcomes 1 and 2 belong to the backup-host record; the copy was pulled beforehand.
  # The stamp name comes from the copy's COMPLETE (stamp=), so a copy pulled into a directory of
  # another name still combines; the digest check below is what binds the copy to the seal.
  STAMP="$(sed -n 's/^stamp=//p' "${PULLED_INPUT%/}/COMPLETE" 2>/dev/null | head -n 1)"
  [ -n "${STAMP}" ] || STAMP="$(basename "${PULLED_INPUT%/}")"
  LOCAL_SUMS=""
  OFFSITE_KIND=""
  COPY="${PULLED_INPUT%/}"
  COPY_OK=0
  record complete NOT_RUN "recorded on the backup host"
  record remote-presence NOT_RUN "recorded on the backup host"
  if detail="$(verify_copy "${COPY}" "${EXPECTED_SUMS}")"; then
    COPY_OK=1
    record remote-integrity PASS "drill-host copy: ${detail}"
  else
    record remote-integrity FAIL "drill-host copy: ${detail}"
  fi
fi

# ---- 4. decrypt ------------------------------------------------------------------------------
if [ "${DECRYPT}" -ne 1 ]; then
  record decrypt NOT_RUN "--decrypt was not requested"
elif [ -z "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then
  record decrypt NOT_RUN "BACKUP_ENCRYPT_PASSPHRASE is not set"
elif [ "${COPY_OK}" -ne 1 ]; then
  record decrypt NOT_RUN "no verified copy to decrypt (see remote-integrity)"
else
  decrypted=0
  failed=()
  shopt -s nullglob
  for artifact in "${COPY}"/*.sql.gz.enc; do
    if openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_ENCRYPT_PASSPHRASE -in "${artifact}" 2>/dev/null \
        | gzip -t 2>/dev/null; then
      decrypted=$((decrypted + 1))
    else
      failed+=("$(basename "${artifact}")")
    fi
  done
  shopt -u nullglob
  if [ -f "${COPY}/minio.tar.gz.enc" ]; then
    if openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_ENCRYPT_PASSPHRASE -in "${COPY}/minio.tar.gz.enc" 2>/dev/null \
        | tar -tzf - > /dev/null 2>&1; then
      decrypted=$((decrypted + 1))
    else
      failed+=("minio.tar.gz.enc")
    fi
  fi
  if [ "${#failed[@]}" -eq 0 ] && [ "${decrypted}" -gt 0 ]; then
    record decrypt PASS "${decrypted} encrypted artifacts decrypt and decompress (streamed, nothing written)"
  elif [ "${decrypted}" -eq 0 ] && [ "${#failed[@]}" -eq 0 ]; then
    record decrypt FAIL "the copy holds no encrypted artifact"
  else
    record decrypt FAIL "${#failed[@]} artifacts do not decrypt or decompress: ${failed[*]}"
  fi
fi

# ---- 5. isolated-restore ---------------------------------------------------------------------
DESTINATION=""
for i in "${!RESTORE_ARGS[@]}"; do
  if [ "${RESTORE_ARGS[$i]}" = --container ]; then DESTINATION="${RESTORE_ARGS[$((i + 1))]:-}"; fi
done
if [ "${MODE}" = backup-host ]; then
  record isolated-restore NOT_RUN "outcome 5 runs on a disposable drill host (--pulled)"
elif [ "${RESTORE}" -ne 1 ]; then
  record isolated-restore NOT_RUN "--isolated-restore was not requested"
elif [ "${COPY_OK}" -ne 1 ]; then
  record isolated-restore NOT_RUN "no verified copy to restore (see remote-integrity)"
else
  # The drill gets a scrubbed environment: no offsite or provider credentials, no production
  # env file, only its own drill env file. Its isolation preflight would refuse anything else.
  # The scrub unsets every other variable in a subshell that then execs the drill: passing
  # NAME=VALUE pairs to `env -i` would put the passphrase on env's argv, readable by any local
  # user in /proc/<pid>/cmdline (#220 review D1). Bash adds only PWD, SHLVL, _ and OLDPWD.
  set +e
  (
    for name in $(compgen -e); do
      case "${name}" in
        PATH|HOME|TMPDIR|PARKIO_RESTORE_*|PARKIO_DRILL_*|BACKUP_ENCRYPT_PASSPHRASE|DOCKER_HOST|DOCKER_CONTEXT|DOCKER_CONFIG|DOCKER_CERT_PATH|DOCKER_TLS_VERIFY) ;;
        *) unset "${name}" 2>/dev/null || export -n "${name}" ;;
      esac
    done
    export LANG=C.UTF-8 HOME="${HOME:-/root}" TMPDIR="${TMPDIR:-/tmp}"
    exec "${ROOT}/scripts/restore-drill-01.sh" --stamp "${COPY}" \
      --work "${WORK}/restore-work" --evidence "${EVIDENCE}/isolated-restore" \
      ${DRILL_ENV_FILE:+--env-file "${DRILL_ENV_FILE}"} "${RESTORE_ARGS[@]}"
  ) > "${WORK}/restore-drill.log" 2>&1
  drill_rc=$?
  set -e
  drill_reason="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("reason",""))' \
    "${EVIDENCE}/isolated-restore/summary.json" 2>/dev/null || true)"
  case "${drill_rc}" in
    0) record isolated-restore PASS "restore-drill-01 PASS into container ${DESTINATION}" ;;
    3) record isolated-restore BLOCKED "restore-drill-01 BLOCKED (privacy evidence incomplete); nothing was restored" ;;
    *)
      if [ "${drill_reason}" = "isolation preflight" ]; then
        record isolated-restore NOT_RUN "the drill host failed restore-drill-01's isolation preflight; nothing was restored (see isolated-restore/isolation.json)"
      else
        record isolated-restore FAIL "restore-drill-01 exit ${drill_rc}: ${drill_reason:-see the work directory}"
      fi
      ;;
  esac
fi

# ---- report ---------------------------------------------------------------------------------
set +e
python3 - "${OUTCOMES}" "${EVIDENCE}" "${STAMP}" "${STARTED}" "${MODE}" "${LOCAL_SUMS}" "${EXPECTED_SUMS}" \
    "${OFFSITE_KIND}" "${DESTINATION}" "${STAMP_DIR:+${STAMP_DIR%/}.offsite-receipt.json}" <<'PY'
import datetime, json, pathlib, sys
(outcomes_path, evidence, stamp, started, mode, local_sums, expected_sums, kind, destination,
 receipt_path) = sys.argv[1:]
outcomes = []
for line in open(outcomes_path):
    oid, result, detail = line.rstrip("\n").split("\t", 2)
    outcomes.append({"id": oid, "result": result, "detail": detail})
results = [o["result"] for o in outcomes]
verdict = "FAIL" if "FAIL" in results else ("INCOMPLETE" if set(results) - {"PASS"} else "PASS")
# The post-upload receipt beside the stamp (U14, when present) is recorded, not trusted: the
# outcomes check the offsite copy directly.
receipt = {"present": False}
if receipt_path:
    try:
        data = json.loads(pathlib.Path(receipt_path).read_text())
        receipt = {"present": True, "uploaded": data.get("uploaded"),
                   "target": (data.get("offsite") or {}).get("target"),
                   "bindsLocalComplete": (data.get("sealed") or {}).get("sha256sums") == local_sums}
    except (OSError, ValueError):
        pass
report = {
    "tool": "backup-dated-acceptance", "schemaVersion": 2, "mode": mode,
    "started": started, "finished": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "stamp": stamp, "localSha256sums": local_sums or None, "expectedSha256sums": expected_sums or None,
    "offsiteKind": kind or None, "receipt": receipt if mode == "backup-host" else None,
    "isolatedDestination": {"container": destination} if destination else None,
    "outcomes": outcomes, "verdict": verdict,
}
ev = pathlib.Path(evidence)
(ev / "acceptance.json").write_text(json.dumps(report, indent=2) + "\n")
rows = "\n".join(f"| {o['id']} | {o['result']} | {o['detail']} |" for o in outcomes)
(ev / "acceptance.md").write_text(
    f"# Backup acceptance {stamp} ({mode})\n\nStarted {started}; verdict **{verdict}**.\n\n"
    f"| outcome | result | detail |\n|---|---|---|\n{rows}\n")
print(f"verdict={verdict}")
PY
report_rc=$?
set -e
if [ "${report_rc}" -ne 0 ]; then
  echo "ERROR: the acceptance report could not be written; the outcomes are in ${OUTCOMES}." >&2
  exit 5
fi

if grep -q $'\tFAIL\t' "${OUTCOMES}"; then
  exit 1
fi
if grep -q -v $'\tPASS\t' "${OUTCOMES}"; then
  exit 4
fi
exit 0
