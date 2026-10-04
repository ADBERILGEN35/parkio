#!/usr/bin/env bash
# Tests for scripts/backup-dated-acceptance.sh (U14). A synthetic sealed stamp, encrypted with a
# synthetic passphrase, is "uploaded" to a filesystem offsite that a fake mc serves (ls --json,
# mirror). The restore drill is a stub in a copied tree that runs the REAL phase-3 isolation
# preflight (restore-drill-isolation-preflight.py) in the environment it inherits, so a leak of
# offsite or production settings into the drill fails here as it would on a drill host. Every
# outcome is checked on its own. No real backup, database, MinIO, Azure or production path is
# touched. Requires jq, openssl, gzip, tar, python3 and sha256sum.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
pass=0
fail=0
ok() { echo "PASS $1"; pass=$((pass + 1)); }
bad() { echo "FAIL $1"; fail=$((fail + 1)); }
for tool in jq openssl gzip tar python3 sha256sum; do
  command -v "${tool}" >/dev/null 2>&1 || { echo "ERROR: required command '${tool}' is missing" >&2; exit 2; }
done

WORKROOT="$(mktemp -d "${TMPDIR:-/tmp}/parkio-dated-acceptance.XXXXXX")"
trap 'rm -rf "${WORKROOT}"' EXIT
PASSPHRASE="u14-acceptance-synthetic-passphrase"
DATABASES="auth gateway user parking media gamification notification moderation analytics ai-validation"

# A tree with the real scripts, except restore-drill-01.sh: a stub that does what the drill's
# phase 3 does (the real isolation preflight on the inherited environment and --env-file, with
# PARKIO_RESTORE_REQUIRE_ERASURE_LEDGER=1 as the drill exports it), records its arguments and the
# names of its environment, then exits with PARKIO_DRILL_FAKE_RC. The acceptance runs the drill
# under env -i with an allowlist, so the test hook has to use an allowlisted PARKIO_DRILL_ name.
TREE="${WORKROOT}/tree"
mkdir -p "${TREE}/scripts/lib"
cp "${ROOT}/scripts/backup-dated-acceptance.sh" "${ROOT}/scripts/backup-offsite-pull.sh" "${TREE}/scripts/"
cp "${ROOT}/scripts/lib/backup-common.sh" "${ROOT}/scripts/lib/restore-stamp-preflight.py" \
  "${ROOT}/scripts/lib/restore-drill-isolation-preflight.py" "${TREE}/scripts/lib/"
cat > "${TREE}/scripts/restore-drill-01.sh" <<'SH'
#!/usr/bin/env bash
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EVIDENCE="" ENV_FILE=""
args=("$@")
while [ "$#" -gt 0 ]; do
  case "$1" in
    --evidence) EVIDENCE="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    *) shift ;;
  esac
done
mkdir -p "${EVIDENCE}"
printf '%s\n' "${args[@]}" > "${EVIDENCE}/../../drill-args"
compgen -e | sort > "${EVIDENCE}/../../drill-env-names"
export PARKIO_RESTORE_REQUIRE_ERASURE_LEDGER=1
: > "${EVIDENCE}/containers.txt"
iso=(--containers-file "${EVIDENCE}/containers.txt")
[ -n "${ENV_FILE}" ] && iso+=(--env-file "${ENV_FILE}")
if ! python3 "${ROOT}/scripts/lib/restore-drill-isolation-preflight.py" "${iso[@]}" > "${EVIDENCE}/isolation.json"; then
  printf '{"tool":"restore-drill-01","verdict":"FAIL","reason":"isolation preflight"}\n' > "${EVIDENCE}/summary.json"
  exit 1
fi
case "${PARKIO_DRILL_FAKE_RC:-0}" in
  0) printf '{"tool":"restore-drill-01","verdict":"PASS","reason":""}\n' > "${EVIDENCE}/summary.json" ;;
  3) printf '{"tool":"restore-drill-01","verdict":"BLOCKED","reason":"erasure set incomplete"}\n' > "${EVIDENCE}/summary.json" ;;
  *) printf '{"tool":"restore-drill-01","verdict":"FAIL","reason":"row-count parity auth"}\n' > "${EVIDENCE}/summary.json" ;;
esac
exit "${PARKIO_DRILL_FAKE_RC:-0}"
SH
chmod +x "${TREE}/scripts/"*.sh

BIN="${WORKROOT}/bin"
mkdir -p "${BIN}"
# Fake mc over a filesystem "bucket": ls --recursive --json prints one JSON line per file with
# its key relative to the listed prefix; mirror copies a tree.
cat > "${BIN}/mc" <<'PY'
#!/usr/bin/env python3
import json, os, shutil, sys
args = sys.argv[1:]
if args[:1] == ["ls"]:
    prefix = args[-1].rstrip("/")
    if not os.path.isdir(prefix):
        print(json.dumps({"status": "error", "error": {"message": "Object does not exist."}}))
        sys.exit(1)
    for base, _, files in os.walk(prefix):
        for name in sorted(files):
            path = os.path.join(base, name)
            print(json.dumps({"status": "success", "type": "file", "size": os.path.getsize(path),
                              "key": os.path.relpath(path, prefix)}))
elif args[:1] == ["mirror"]:
    src, dst = args[-2], args[-1]
    if not os.path.isdir(src):
        sys.stderr.write("mc: source does not exist\n")
        sys.exit(1)
    shutil.copytree(src, dst, dirs_exist_ok=True)
else:
    sys.stderr.write(f"unexpected mc call: {args}\n")
    sys.exit(2)
PY
chmod +x "${BIN}/mc"
# Fake az: only the blob listing, as TSV (name, contentLength), with one object of another stamp.
cat > "${BIN}/az" <<'SH'
#!/usr/bin/env bash
if [ "$1 $2 $3" = "storage blob list" ]; then
  printf '%s\t%s\n' "${FAKE_AZ_STAMP}/COMPLETE" 103 "${FAKE_AZ_STAMP}/auth.sql.gz.enc" 128 \
    "${FAKE_AZ_STAMP}/nested/object.bin" 7 "2020-01-01T00-00-00Z/COMPLETE" 99
  exit 0
fi
echo "unexpected az call: $*" >&2
exit 2
SH
chmod +x "${BIN}/az"
export PATH="${BIN}:${PATH}"

# shellcheck source=lib/backup-common.sh
source "${ROOT}/scripts/lib/backup-common.sh"

STAMP="2026-10-04T03-30-01Z"
make_stamp() { # dir
  local dir="$1" db src
  mkdir -p "${dir}"
  for db in ${DATABASES}; do
    printf -- '-- synthetic %s dump\nSELECT 1;\n' "${db}" | gzip -c \
      | BACKUP_ENCRYPT_PASSPHRASE="${PASSPHRASE}" openssl enc -aes-256-cbc -pbkdf2 -salt \
        -pass env:BACKUP_ENCRYPT_PASSPHRASE > "${dir}/${db}.sql.gz.enc"
  done
  src="$(mktemp -d "${WORKROOT}/minio.XXXXXX")"
  mkdir -p "${src}/minio/parkio-media"
  printf 'synthetic-object\n' > "${src}/minio/parkio-media/object.bin"
  tar -C "${src}" -czf - minio | BACKUP_ENCRYPT_PASSPHRASE="${PASSPHRASE}" \
    openssl enc -aes-256-cbc -pbkdf2 -salt -pass env:BACKUP_ENCRYPT_PASSPHRASE > "${dir}/minio.tar.gz.enc"
  printf '[]\n' > "${dir}/erasure-tombstones.json"
  python3 - "${dir}" "${DATABASES}" <<'PY'
import json, sys
dest, databases = sys.argv[1], sys.argv[2].split()
json.dump({"schemaVersion": 3, "action": "backup", "timestamp": dest.rsplit("/", 1)[-1],
           "databases": databases, "databasesOk": len(databases), "databasesFailed": 0, "minioOk": 1,
           "minio": {"objectCount": 1, "artifact": "minio.tar.gz.enc"},
           "encryption": {"enabled": True, "algorithm": "aes-256-cbc-pbkdf2"},
           "offsite": {"kind": "s3", "uploaded": False}, "checksums": {"sha256sums": "SHA256SUMS"}},
          open(f"{dest}/backup-manifest.json", "w"))
PY
  parkio_backup_write_stamp_integrity "${dir}" "$(basename "${dir}")"
}

flip_last_byte() {
  python3 - "$1" <<'PY'
import sys
path = sys.argv[1]
data = bytearray(open(path, "rb").read())
data[-1] ^= 0x01
open(path, "wb").write(bytes(data))
PY
}

# One case directory per scenario: backups/<stamp>, an offsite copy, a drill-host copy (as
# backup-offsite-pull.sh would leave it on the drill host) and an inert drill env file.
new_case() { # name
  CASE="${WORKROOT}/$1"
  mkdir -p "${CASE}/backups" "${CASE}/offsite/parkio-backups" "${CASE}/drill/stamps"
  make_stamp "${CASE}/backups/${STAMP}"
  cp -a "${CASE}/backups/${STAMP}" "${CASE}/offsite/parkio-backups/${STAMP}"
  cp -a "${CASE}/backups/${STAMP}" "${CASE}/drill/stamps/${STAMP}"
  printf '%s\n' PARKIO_EMAIL_PROVIDER=logging PARKIO_WAITLIST_EMAIL_PROVIDER=logging \
    PARKIO_PUSH_DELIVERY_PROVIDER=noop > "${CASE}/drill/drill.env"
  SEAL="$(sed -n 's/^sha256sums=//p' "${CASE}/backups/${STAMP}/COMPLETE")"
}

# The environment of both hosts deliberately carries offsite and production settings; the
# drill must never see them.
run_acceptance() { # extra args...; sets rc, out and REPORT
  local rc_=0 evidence="${EVIDENCE_DIR:-${CASE}/evidence}"
  out="$(env BACKUP_OFFSITE_KIND="${KIND:-s3}" BACKUP_MC_DEST="${CASE}/offsite/parkio-backups" \
    BACKUP_ENCRYPT_PASSPHRASE="${PHRASE-${PASSPHRASE}}" BACKUP_PRODUCTION_MODE=1 \
    BACKUP_AZURE_STORAGE_KEY=live-looking-storage-key PARKIO_RESEND_API_KEY=re_ABCDEFGHIJKLMNOPQRSTUV \
    PARKIO_DRILL_ID=rd-test-01 PARKIO_DRILL_FAKE_RC="${DRILL_RC:-0}" PARKIO_ENV_FILE="" BACKUP_MC_URL="" \
    bash "${TREE}/scripts/backup-dated-acceptance.sh" --evidence "${evidence}" "$@" 2>&1)" || rc_=$?
  rc="${rc_}"
  REPORT="${evidence}/acceptance.json"
}
backup_host() { run_acceptance --stamp-dir "${CASE}/backups/${STAMP}" --work "${CASE}/work" "$@"; }
drill_host() { # expected digest, extra args...
  local expected="$1"; shift
  EVIDENCE_DIR="${CASE}/drill-evidence" run_acceptance --pulled "${CASE}/drill/stamps/${STAMP}" \
    --expected-sha256sums "${expected}" --work "${CASE}/drill-work" "$@"
}

results() { # -> "complete=PASS ... verdict=..."
  python3 - "${REPORT}" <<'PY'
import json, sys
report = json.load(open(sys.argv[1]))
pairs = ["%s=%s" % (o["id"], o["result"]) for o in report["outcomes"]]
print(" ".join(pairs) + " verdict=" + report["verdict"])
PY
}
RESTORE=(--isolated-restore --drill-env-file "DRILL_ENV" -- --recovery-cutoff 2026-10-04T03:30:01Z --container parkio-acceptance-db)
restore_args() { printf '%s\n' "${RESTORE[@]}" | sed "s#DRILL_ENV#${CASE}/drill/drill.env#"; }

# ---- 1. backup host, healthy --------------------------------------------------------------------
new_case backup-host
backup_host --decrypt
expected="complete=PASS remote-presence=PASS remote-integrity=PASS decrypt=PASS isolated-restore=NOT_RUN verdict=INCOMPLETE"
[ "${rc}" -eq 4 ] && [ "$(results)" = "${expected}" ] && grep -q "runs on a disposable drill host" "${REPORT}" \
  && ok "the backup host records outcomes 1-4 and leaves outcome 5 to a drill host (exit 4)" \
  || bad "backup host (rc=${rc}): $(results 2>/dev/null) ${out}"
BACKUP_RECORD="${REPORT}"
if grep -rq -- "${PASSPHRASE}" "${CASE}/evidence" || grep -rq -- "${WORKROOT}" "${CASE}/evidence"; then
  bad "backup-host evidence must hold neither the passphrase nor absolute paths"
else
  ok "backup-host evidence holds neither the passphrase nor an absolute path"
fi

# ---- 2. drill host, healthy, in an environment full of offsite and production settings --------
mapfile -t args < <(restore_args)
drill_host "${SEAL}" --decrypt "${args[@]}"
expected="complete=NOT_RUN remote-presence=NOT_RUN remote-integrity=PASS decrypt=PASS isolated-restore=PASS verdict=INCOMPLETE"
[ "${rc}" -eq 4 ] && [ "$(results)" = "${expected}" ] \
  && ok "the drill host passes outcomes 3-5 through the real isolation preflight" \
  || bad "drill host (rc=${rc}): $(results 2>/dev/null) $(head -c 400 "${CASE}/drill-evidence/isolated-restore/isolation.json" 2>/dev/null) ${out}"
DRILL_RECORD="${REPORT}"
if ! grep -q -E '^(BACKUP_MC_DEST|BACKUP_AZURE_STORAGE_KEY|BACKUP_PRODUCTION_MODE|PARKIO_RESEND_API_KEY|BACKUP_OFFSITE_KIND)$' "${CASE}/drill-env-names" \
  && grep -qx "PARKIO_DRILL_ID" "${CASE}/drill-env-names" && grep -qx "BACKUP_ENCRYPT_PASSPHRASE" "${CASE}/drill-env-names" \
  && grep -qx -- "--env-file" "${CASE}/drill-args" && grep -qx "${CASE}/drill/drill.env" "${CASE}/drill-args" \
  && grep -qx "${CASE}/drill/stamps/${STAMP}" "${CASE}/drill-args"; then
  ok "the drill sees no offsite or production variables, only its drill env file and the verified copy"
else
  bad "drill environment or arguments: $(tr '\n' ' ' < "${CASE}/drill-env-names")"
fi

# ---- 3. one record from the two ------------------------------------------------------------------
EVIDENCE_DIR="${CASE}/combined" run_acceptance --combine "${BACKUP_RECORD}" "${DRILL_RECORD}"
REPORT="${CASE}/combined/acceptance.json"
expected="complete=PASS remote-presence=PASS remote-integrity=PASS decrypt=PASS isolated-restore=PASS verdict=PASS"
[ "${rc}" -eq 0 ] && [ "$(results)" = "${expected}" ] \
  && python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); assert r["isolatedDestination"]=={"container":"parkio-acceptance-db"} and len(r["sources"]["drillHost"]["sha256"])==64' "${REPORT}" \
  && ok "the combined record passes all five outcomes (exit 0) and names the destination and its sources" \
  || bad "combined (rc=${rc}): $(results 2>/dev/null)"
python3 - "${DRILL_RECORD}" "${CASE}/other-seal.json" <<'PY'
import json, sys
record = json.load(open(sys.argv[1]))
record["expectedSha256sums"] = "0" * 64
json.dump(record, open(sys.argv[2], "w"))
PY
EVIDENCE_DIR="${CASE}/combined-mismatch" run_acceptance --combine "${BACKUP_RECORD}" "${CASE}/other-seal.json"
REPORT="${CASE}/combined-mismatch/acceptance.json"
[ "${rc}" -eq 1 ] && grep -q "not for the same sealed stamp" "${REPORT}" \
  && ok "records for different seals do not combine into a PASS" || bad "combine mismatch (rc=${rc})"

# ---- 4. drill host: the wrong seal, or a file outside it --------------------------------------
new_case drill-wrong-seal
drill_host "$(printf '%064d' 7)" --decrypt
[ "${rc}" -eq 1 ] && [ "$(results)" = "complete=NOT_RUN remote-presence=NOT_RUN remote-integrity=FAIL decrypt=NOT_RUN isolated-restore=NOT_RUN verdict=FAIL" ] \
  && ok "a drill-host copy that is not the recorded seal fails integrity; nothing is decrypted" \
  || bad "drill wrong seal (rc=${rc}): $(results)"
new_case drill-extra-file
printf 'plaintext\n' | gzip -c > "${CASE}/drill/stamps/${STAMP}/auth.sql.gz"
drill_host "${SEAL}" --decrypt
[ "${rc}" -eq 1 ] && grep -q "files outside the seal" "${REPORT}" \
  && ok "a drill-host copy with a file outside the seal fails integrity" || bad "drill extra file (rc=${rc}): $(results)"

# ---- 5. the drill: not isolated, blocked, failed ------------------------------------------------
new_case drill-not-isolated
printf '%s\n' PARKIO_RESEND_API_KEY=re_LIVELOOKINGKEY1234567890 >> "${CASE}/drill/drill.env"
mapfile -t args < <(restore_args)
drill_host "${SEAL}" "${args[@]}"
[ "${rc}" -eq 4 ] && [ "$(results)" = "complete=NOT_RUN remote-presence=NOT_RUN remote-integrity=PASS decrypt=NOT_RUN isolated-restore=NOT_RUN verdict=INCOMPLETE" ] \
  && grep -q "failed restore-drill-01's isolation preflight" "${REPORT}" \
  && ok "a drill host that fails the isolation preflight leaves outcome 5 NOT_RUN, not FAIL" \
  || bad "not isolated (rc=${rc}): $(results)"
new_case drill-blocked
mapfile -t args < <(restore_args)
DRILL_RC=3 drill_host "${SEAL}" "${args[@]}"
[ "${rc}" -eq 4 ] && [ "$(results)" = "complete=NOT_RUN remote-presence=NOT_RUN remote-integrity=PASS decrypt=NOT_RUN isolated-restore=BLOCKED verdict=INCOMPLETE" ] \
  && ok "a BLOCKED drill is recorded as BLOCKED, not PASS" || bad "drill blocked (rc=${rc}): $(results)"
new_case drill-failed
mapfile -t args < <(restore_args)
DRILL_RC=1 drill_host "${SEAL}" "${args[@]}"
[ "${rc}" -eq 1 ] && grep -q "row-count parity auth" "${REPORT}" \
  && [ "$(results)" = "complete=NOT_RUN remote-presence=NOT_RUN remote-integrity=PASS decrypt=NOT_RUN isolated-restore=FAIL verdict=FAIL" ] \
  && ok "a failed restore is FAIL with the drill's reason" || bad "drill failed (rc=${rc}): $(results)"

# ---- 6. backup host: offsite defects ------------------------------------------------------------
new_case missing-object
rm "${CASE}/offsite/parkio-backups/${STAMP}/parking.sql.gz.enc"
backup_host --decrypt
[ "${rc}" -eq 1 ] && [ "$(results)" = "complete=PASS remote-presence=FAIL remote-integrity=FAIL decrypt=NOT_RUN isolated-restore=NOT_RUN verdict=FAIL" ] \
  && ok "a missing offsite object fails presence and integrity; complete stays PASS" || bad "missing object (rc=${rc}): $(results)"
new_case extra-object
printf 'plaintext\n' | gzip -c > "${CASE}/offsite/parkio-backups/${STAMP}/auth.sql.gz"
backup_host --decrypt
[ "${rc}" -eq 1 ] && [ "$(results)" = "complete=PASS remote-presence=FAIL remote-integrity=FAIL decrypt=NOT_RUN isolated-restore=NOT_RUN verdict=FAIL" ] \
  && grep -q "unexpected=\['auth.sql.gz'\]" "${REPORT}" && grep -q "files outside the seal" "${REPORT}" \
  && ok "an unlisted offsite object (a plaintext dump) fails presence and integrity" || bad "extra object (rc=${rc}): $(results)"
new_case changed-byte
flip_last_byte "${CASE}/offsite/parkio-backups/${STAMP}/user.sql.gz.enc"
backup_host --decrypt
[ "${rc}" -eq 1 ] && [ "$(results)" = "complete=PASS remote-presence=PASS remote-integrity=FAIL decrypt=NOT_RUN isolated-restore=NOT_RUN verdict=FAIL" ] \
  && ok "a changed offsite byte passes presence (same size) and fails integrity" || bad "changed byte (rc=${rc}): $(results)"
new_case resealed
remote="${CASE}/offsite/parkio-backups/${STAMP}"
flip_last_byte "${remote}/user.sql.gz.enc"
rm -f "${remote}/COMPLETE" "${remote}/SHA256SUMS"
parkio_backup_write_stamp_integrity "${remote}" "${STAMP}"
backup_host
[ "${rc}" -eq 1 ] && grep -q "is not the sealed stamp's" "${REPORT}" \
  && ok "an offsite copy that passes its own checksums but belongs to another seal fails integrity" \
  || bad "resealed (rc=${rc}): $(results)"

# ---- 7. backup host: passphrase, local stamp, no offsite ---------------------------------------
new_case wrong-passphrase
PHRASE="not-the-passphrase" backup_host --decrypt
[ "${rc}" -eq 1 ] && grep -q "11 artifacts do not decrypt" "${REPORT}" \
  && [ "$(results)" = "complete=PASS remote-presence=PASS remote-integrity=PASS decrypt=FAIL isolated-restore=NOT_RUN verdict=FAIL" ] \
  && ok "a wrong passphrase fails decrypt only" || bad "wrong passphrase (rc=${rc}): $(results)"
new_case no-passphrase
PHRASE="" backup_host --decrypt
[ "${rc}" -eq 4 ] && [ "$(results)" = "complete=PASS remote-presence=PASS remote-integrity=PASS decrypt=NOT_RUN isolated-restore=NOT_RUN verdict=INCOMPLETE" ] \
  && ok "without a passphrase decrypt is NOT_RUN, not PASS" || bad "no passphrase (rc=${rc}): $(results)"
new_case local-broken
rm "${CASE}/backups/${STAMP}/erasure-tombstones.json"
backup_host
[ "${rc}" -eq 1 ] && [ "$(results)" = "complete=FAIL remote-presence=PASS remote-integrity=PASS decrypt=NOT_RUN isolated-restore=NOT_RUN verdict=FAIL" ] \
  && ok "a broken local stamp fails complete while the offsite outcomes are judged on their own" \
  || bad "local broken (rc=${rc}): $(results)"
new_case no-offsite
KIND=none backup_host --decrypt
[ "${rc}" -eq 4 ] && [ "$(results)" = "complete=PASS remote-presence=NOT_RUN remote-integrity=NOT_RUN decrypt=NOT_RUN isolated-restore=NOT_RUN verdict=INCOMPLETE" ] \
  && ok "without offsite the remote outcomes are NOT_RUN" || bad "no offsite (rc=${rc}): $(results)"

# ---- 8. usage refusals ---------------------------------------------------------------------------
new_case usage
mkdir -p "${CASE}/evidence"
backup_host
[ "${rc}" -eq 2 ] && ok "an existing evidence directory is refused" || bad "existing evidence: rc=${rc}"
rm -rf "${CASE}/evidence"
mapfile -t args < <(restore_args)
backup_host "${args[@]}"
[ "${rc}" -eq 2 ] && ok "outcome 5 is refused on the backup host" || bad "restore on backup host: rc=${rc}"
run_acceptance --pulled "${CASE}/drill/stamps/${STAMP}" --work "${CASE}/drill-work"
[ "${rc}" -eq 2 ] && ok "--pulled without the recorded seal is refused" || bad "pulled without seal: rc=${rc}"
for refused in --allow-privacy-blocked --env-file --stamp; do
  rm -rf "${CASE}/drill-evidence" "${CASE}/drill-work"
  drill_host "${SEAL}" --isolated-restore -- --recovery-cutoff 2026-10-04T03:30:01Z --container x "${refused}" y
  [ "${rc}" -eq 2 ] && ok "drill argument ${refused} is refused" || bad "drill ${refused}: rc=${rc}"
done
run_acceptance --stamp-dir "${CASE}/backups/${STAMP}" --pulled "${CASE}/drill/stamps/${STAMP}" \
  --expected-sha256sums "${SEAL}" --work "${CASE}/work"
[ "${rc}" -eq 2 ] && ok "a run is either the backup host or the drill host" || bad "both modes: rc=${rc}"

# ---- 9. the Azure listing --------------------------------------------------------------------------
listing="$(BACKUP_OFFSITE_KIND=azure BACKUP_AZURE_STORAGE_ACCOUNT=synthacct BACKUP_AZURE_CONTAINER=parkio-backups \
  BACKUP_AZURE_STORAGE_KEY=synthetic-key FAKE_AZ_STAMP="${STAMP}" parkio_backup_offsite_list "${STAMP}")"
if [ "${listing}" = "$(printf 'COMPLETE\t103\nauth.sql.gz.enc\t128\nnested/object.bin\t7')" ]; then
  ok "the Azure listing keeps this stamp's objects, relative to the stamp prefix"
else
  bad "Azure listing: ${listing}"
fi

echo
echo "=== backup dated acceptance: pass=${pass} fail=${fail} ==="
[ "${fail}" -eq 0 ]
