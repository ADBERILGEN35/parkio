#!/usr/bin/env bash
# Tests for scripts/backup-dated-acceptance.sh (U14). A synthetic sealed stamp, encrypted with a
# synthetic passphrase, is "uploaded" to a filesystem offsite that a fake mc serves (ls --json,
# mirror). The restore drill is a stub in a copied tree. Each scenario checks that every outcome
# is recorded on its own: a failure in one never changes another. No real backup, database,
# MinIO, Azure or production path is touched. Requires jq (the listing uses it), openssl, gzip,
# tar, python3 and sha256sum.
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

# A tree with the real scripts, except restore-drill-01.sh, which is a stub that records its
# arguments and exits with FAKE_DRILL_RC.
TREE="${WORKROOT}/tree"
mkdir -p "${TREE}/scripts/lib"
cp "${ROOT}/scripts/backup-dated-acceptance.sh" "${ROOT}/scripts/backup-offsite-pull.sh" "${TREE}/scripts/"
cp "${ROOT}/scripts/lib/backup-common.sh" "${ROOT}/scripts/lib/restore-stamp-preflight.py" "${TREE}/scripts/lib/"
cat > "${TREE}/scripts/restore-drill-01.sh" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$@" > "${FAKE_DRILL_ARGS:?}"
[ -n "${BACKUP_ENCRYPT_PASSPHRASE:-}" ] && echo "passphrase-present" >> "${FAKE_DRILL_ARGS}"
exit "${FAKE_DRILL_RC:-0}"
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

# One case directory per scenario: backups/<stamp>, an offsite copy and fresh output paths.
new_case() { # name
  CASE="${WORKROOT}/$1"
  mkdir -p "${CASE}/backups" "${CASE}/offsite/parkio-backups"
  make_stamp "${CASE}/backups/${STAMP}"
  cp -a "${CASE}/backups/${STAMP}" "${CASE}/offsite/parkio-backups/${STAMP}"
}

run_acceptance() { # extra args...; sets rc, out and REPORT
  local rc_=0
  out="$(env BACKUP_OFFSITE_KIND="${KIND:-s3}" BACKUP_MC_DEST="${CASE}/offsite/parkio-backups" \
    BACKUP_ENCRYPT_PASSPHRASE="${PHRASE-${PASSPHRASE}}" FAKE_DRILL_ARGS="${CASE}/drill-args" \
    FAKE_DRILL_RC="${DRILL_RC:-0}" PARKIO_ENV_FILE="" BACKUP_MC_URL="" \
    bash "${TREE}/scripts/backup-dated-acceptance.sh" --stamp-dir "${CASE}/backups/${STAMP}" \
    --evidence "${CASE}/evidence" --work "${CASE}/work" "$@" 2>&1)" || rc_=$?
  rc="${rc_}"
  REPORT="${CASE}/evidence/acceptance.json"
}

results() { # -> "complete=PASS remote-presence=... verdict=..."
  python3 - "${REPORT}" <<'PY'
import json, sys
report = json.load(open(sys.argv[1]))
pairs = ["%s=%s" % (o["id"], o["result"]) for o in report["outcomes"]]
print(" ".join(pairs) + " verdict=" + report["verdict"])
PY
}

# ---- 1. everything requested and healthy ----------------------------------------------------
new_case all-pass
run_acceptance --decrypt --isolated-restore -- --recovery-cutoff 2026-10-04T03:30:01Z --container parkio-acceptance-db
expected="complete=PASS remote-presence=PASS remote-integrity=PASS decrypt=PASS isolated-restore=PASS verdict=PASS"
if [ "${rc}" -eq 0 ] && [ "$(results)" = "${expected}" ]; then
  ok "a healthy stamp passes all five outcomes"
else
  bad "all five must pass (rc=${rc}): $(results 2>/dev/null) ${out}"
fi
if grep -qx -- "--stamp" "${CASE}/drill-args" && grep -qx "${CASE}/work/pulled" "${CASE}/drill-args" \
  && grep -qx -- "--container" "${CASE}/drill-args" && grep -qx "parkio-acceptance-db" "${CASE}/drill-args" \
  && grep -qx "passphrase-present" "${CASE}/drill-args" \
  && python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); assert r["isolatedDestination"]=={"container":"parkio-acceptance-db"}' "${REPORT}"; then
  ok "the restore drill gets the pulled copy and the named container, which the report records"
else
  bad "restore drill arguments or destination: $(tr '\n' ' ' < "${CASE}/drill-args" 2>/dev/null)"
fi
if grep -rq -- "${PASSPHRASE}" "${CASE}/evidence" || grep -rq -- "${WORKROOT}" "${CASE}/evidence"; then
  bad "evidence must hold neither the passphrase nor absolute paths"
else
  ok "evidence holds neither the passphrase nor an absolute path"
fi
if [ "$(wc -l < "${CASE}/evidence/remote-listing.tsv")" -eq "$(( $(wc -l < "${CASE}/backups/${STAMP}/SHA256SUMS") + 2 ))" ]; then
  ok "the remote listing holds every SHA256SUMS entry plus SHA256SUMS and COMPLETE"
else
  bad "remote listing size"
fi

# ---- 2. decrypt and restore not requested ----------------------------------------------------
new_case not-requested
run_acceptance
expected="complete=PASS remote-presence=PASS remote-integrity=PASS decrypt=NOT_RUN isolated-restore=NOT_RUN verdict=INCOMPLETE"
[ "${rc}" -eq 4 ] && [ "$(results)" = "${expected}" ] \
  && ok "unrequested decrypt and restore are NOT_RUN and the run is incomplete (exit 4)" \
  || bad "not requested (rc=${rc}): $(results)"

# ---- 3. an object missing offsite -------------------------------------------------------------
new_case missing-object
rm "${CASE}/offsite/parkio-backups/${STAMP}/parking.sql.gz.enc"
run_acceptance --decrypt
expected="complete=PASS remote-presence=FAIL remote-integrity=FAIL decrypt=NOT_RUN isolated-restore=NOT_RUN verdict=FAIL"
[ "${rc}" -eq 1 ] && [ "$(results)" = "${expected}" ] && grep -q "parking.sql.gz.enc" "${REPORT}" \
  && ok "a missing offsite object fails presence and integrity, leaves the local outcome PASS" \
  || bad "missing object (rc=${rc}): $(results)"

# ---- 4. a changed byte offsite (same size) ------------------------------------------------------
new_case changed-byte
python3 - "${CASE}/offsite/parkio-backups/${STAMP}/user.sql.gz.enc" <<'PY'
import sys
path = sys.argv[1]
data = bytearray(open(path, "rb").read())
data[-1] ^= 0x01
open(path, "wb").write(bytes(data))
PY
run_acceptance --decrypt
expected="complete=PASS remote-presence=PASS remote-integrity=FAIL decrypt=NOT_RUN isolated-restore=NOT_RUN verdict=FAIL"
[ "${rc}" -eq 1 ] && [ "$(results)" = "${expected}" ] \
  && ok "a changed offsite byte passes presence (same size) and fails integrity" \
  || bad "changed byte (rc=${rc}): $(results)"

# ---- 5. a self-consistent offsite copy that is not the local sealed stamp -----------------------
new_case resealed
remote="${CASE}/offsite/parkio-backups/${STAMP}"
python3 - "${remote}/user.sql.gz.enc" <<'PY'
import sys
path = sys.argv[1]
data = bytearray(open(path, "rb").read())
data[-1] ^= 0x01
open(path, "wb").write(bytes(data))
PY
rm -f "${remote}/COMPLETE" "${remote}/SHA256SUMS"
parkio_backup_write_stamp_integrity "${remote}" "${STAMP}"
run_acceptance
if [ "${rc}" -eq 1 ] && [ "$(results)" = "complete=PASS remote-presence=PASS remote-integrity=FAIL decrypt=NOT_RUN isolated-restore=NOT_RUN verdict=FAIL" ] \
  && grep -q "is not the local sealed stamp" "${REPORT}"; then
  ok "an offsite copy that passes its own checksums but differs from the local seal fails integrity"
else
  bad "resealed (rc=${rc}): $(results)"
fi

# ---- 6. the wrong passphrase --------------------------------------------------------------------
new_case wrong-passphrase
PHRASE="not-the-passphrase" run_acceptance --decrypt
[ "${rc}" -eq 1 ] && [ "$(results)" = "complete=PASS remote-presence=PASS remote-integrity=PASS decrypt=FAIL isolated-restore=NOT_RUN verdict=FAIL" ] \
  && grep -q "11 artifacts do not decrypt" "${REPORT}" \
  && ok "a wrong passphrase fails decrypt only" || bad "wrong passphrase (rc=${rc}): $(results)"

# ---- 7. no passphrase (custody) ------------------------------------------------------------------
new_case no-passphrase
PHRASE="" run_acceptance --decrypt
[ "${rc}" -eq 4 ] && grep -q '"id": "decrypt",' "${REPORT}" \
  && [ "$(results)" = "complete=PASS remote-presence=PASS remote-integrity=PASS decrypt=NOT_RUN isolated-restore=NOT_RUN verdict=INCOMPLETE" ] \
  && ok "without a passphrase decrypt is NOT_RUN, not PASS" || bad "no passphrase (rc=${rc}): $(results)"

# ---- 8. the restore drill blocks or fails ----------------------------------------------------------
new_case drill-blocked
DRILL_RC=3 run_acceptance --isolated-restore -- --recovery-cutoff 2026-10-04T03:30:01Z --container parkio-acceptance-db
[ "${rc}" -eq 4 ] && [ "$(results)" = "complete=PASS remote-presence=PASS remote-integrity=PASS decrypt=NOT_RUN isolated-restore=BLOCKED verdict=INCOMPLETE" ] \
  && ok "a BLOCKED restore drill is recorded as BLOCKED, not PASS" || bad "drill blocked (rc=${rc}): $(results)"
new_case drill-failed
DRILL_RC=1 run_acceptance --isolated-restore -- --recovery-cutoff 2026-10-04T03:30:01Z --container parkio-acceptance-db
[ "${rc}" -eq 1 ] && [ "$(results)" = "complete=PASS remote-presence=PASS remote-integrity=PASS decrypt=NOT_RUN isolated-restore=FAIL verdict=FAIL" ] \
  && ok "a failed restore drill fails isolated-restore only" || bad "drill failed (rc=${rc}): $(results)"

# ---- 9. a broken local stamp -----------------------------------------------------------------------
new_case local-broken
rm "${CASE}/backups/${STAMP}/erasure-tombstones.json"
run_acceptance
[ "${rc}" -eq 1 ] && [ "$(results)" = "complete=FAIL remote-presence=PASS remote-integrity=PASS decrypt=NOT_RUN isolated-restore=NOT_RUN verdict=FAIL" ] \
  && ok "a broken local stamp fails complete while the offsite outcomes are judged on their own" \
  || bad "local broken (rc=${rc}): $(results)"

# ---- 10. no offsite configured -----------------------------------------------------------------------
new_case no-offsite
KIND=none run_acceptance --decrypt
[ "${rc}" -eq 4 ] && [ "$(results)" = "complete=PASS remote-presence=NOT_RUN remote-integrity=NOT_RUN decrypt=NOT_RUN isolated-restore=NOT_RUN verdict=INCOMPLETE" ] \
  && ok "without offsite the remote outcomes are NOT_RUN" || bad "no offsite (rc=${rc}): $(results)"

# ---- 11. usage refusals ------------------------------------------------------------------------------
new_case usage
mkdir -p "${CASE}/evidence"
run_acceptance
[ "${rc}" -eq 2 ] && ok "an existing evidence directory is refused" || bad "existing evidence: rc=${rc}"
rm -rf "${CASE}/evidence"
run_acceptance --isolated-restore
[ "${rc}" -eq 2 ] && ok "--isolated-restore without drill arguments is refused" || bad "no drill args: rc=${rc}"
run_acceptance --isolated-restore -- --stamp /elsewhere --container x
[ "${rc}" -eq 2 ] && ok "a drill --stamp of its own is refused (the pulled copy is used)" || bad "drill --stamp: rc=${rc}"

# ---- 12. the Azure listing --------------------------------------------------------------------------
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
