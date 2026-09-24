#!/usr/bin/env bash
# Isolated entrypoint tests for F-03: production restore fail-closes before
# decrypt or destructive apply. Uses the real restore-hosted-beta.sh and
# restore-database.sh with docker/openssl/psql stubs. No live SSH, backup,
# decrypt of production data, or application start.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
chmod +x "${ROOT}/scripts/restore-hosted-beta.sh" \
  "${ROOT}/scripts/restore-database.sh" \
  "${ROOT}/scripts/lib/restore-safe-preflight.sh"
pass=0
fail=0
ok() { echo "PASS $1"; pass=$((pass + 1)); }
bad() { echo "FAIL $1"; fail=$((fail + 1)); }

need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: missing $1" >&2; exit 2; }; }
need bash
need python3
need sha256sum
need openssl
need gzip

WORK="$(mktemp -d "${TMPDIR:-/tmp}/parkio-f03.XXXXXX")"
trap 'rm -rf "${WORK}"' EXIT
BIN="${WORK}/bin"
LOG="${WORK}/destructive.log"
mkdir -p "${BIN}"
: > "${LOG}"

if ! command -v jq >/dev/null 2>&1; then
  cat > "${BIN}/jq" <<'PY'
#!/usr/bin/env python3
import json, sys
args = [a for a in sys.argv[1:] if a != "-r"]
expr = args[0]
src = args[1] if len(args) > 1 else None
data = json.load(open(src, encoding="utf-8") if src else sys.stdin)

def walk(expr, data):
    expr = expr.strip()
    if expr.endswith("// empty"):
        expr = expr[: -len("// empty")].rstrip()
    if expr == ".databases[]":
        for item in data.get("databases") or []:
            print(item)
        return
    cur = data
    for part in expr.lstrip(".").split("."):
        if not part:
            continue
        if " // " in part:
            part = part.split(" // ", 1)[0]
        cur = (cur or {}).get(part) if isinstance(cur, dict) else None
    print("" if cur is None else cur)

walk(expr, data)
PY
  chmod +x "${BIN}/jq"
fi

cat > "${BIN}/docker" <<'SH'
#!/usr/bin/env bash
echo "DOCKER $*" >> "${PARKIO_F03_LOG}"
case "$1" in
  inspect) exit 0 ;;
  exec)
    shift
    while [ "$#" -gt 0 ]; do
      case "$1" in
        -i) shift ;;
        -U|-d|-c|-At|-v|-X|-q) shift 2 ;;
        psql)
          if [ "${2:-}" = "--version" ]; then
            echo "psql (PostgreSQL) ${PARKIO_STUB_PSQL_VERSION:-16.15}"
            exit 0
          fi
          if echo "$*" | grep -q "show server_version"; then
            echo "${PARKIO_STUB_SERVER_VERSION:-16.15}"
            exit 0
          fi
          if echo "$*" | grep -q postgis; then
            echo "${PARKIO_STUB_POSTGIS_VERSION:-3.4.2}"
            exit 0
          fi
          if echo "$*" | grep -q to_regclass; then
            echo "erased_user_tombstones"
            exit 0
          fi
          echo "PSQL_APPLY $*" >> "${PARKIO_F03_LOG}"
          cat >/dev/null || true
          if [ "${PARKIO_STUB_PSQL_FAIL:-0}" = "1" ]; then
            exit 1
          fi
          exit 0
          ;;
        *) shift ;;
      esac
    done
    exit 0
    ;;
  run)
    echo "DOCKER_RUN $*" >> "${PARKIO_F03_LOG}"
    exit 0
    ;;
  *) exit 0 ;;
esac
SH
chmod +x "${BIN}/docker"

REAL_OPENSSL="$(command -v openssl)"
cat > "${BIN}/openssl" <<SH
#!/usr/bin/env bash
echo "OPENSSL \$*" >> "\${PARKIO_F03_LOG}"
if echo "\$*" | grep -q -- "-d"; then
  printf -- '-- Dumped from database version 16.15\\n-- Dumped by pg_dump version 16.15\\n' | gzip -n
  exit 0
fi
exec "${REAL_OPENSSL}" "\$@"
SH
chmod +x "${BIN}/openssl"

write_integrity() {
  local stamp="$1"
  python3 - "$stamp" <<'PY'
import hashlib, os, sys
from pathlib import Path
stamp = Path(sys.argv[1])
files = sorted(p.relative_to(stamp).as_posix() for p in stamp.rglob("*")
               if p.is_file() and p.name not in ("SHA256SUMS", "COMPLETE"))
lines = [f"{hashlib.sha256((stamp / f).read_bytes()).hexdigest()}  ./{f}" for f in files]
(stamp / "SHA256SUMS").write_text("\n".join(lines) + "\n")
digest = hashlib.sha256((stamp / "SHA256SUMS").read_bytes()).hexdigest()
(stamp / "COMPLETE").write_text(f"stamp={stamp.name}\nsha256sums={digest}\n")
PY
}

make_full_stamp() {
  local stamp="$1"
  mkdir -p "$stamp"
  local db
  for db in auth gateway user parking media gamification notification moderation analytics ai-validation; do
    printf 'Salted__%s' "$db" > "${stamp}/${db}.sql.gz.enc"
  done
  printf 'Salted__minio' > "${stamp}/minio.tar.gz.enc"
  python3 - "$stamp" <<'PY'
import json,sys
from pathlib import Path
stamp=Path(sys.argv[1])
dbs=["auth","gateway","user","parking","media","gamification","notification","moderation","analytics","ai-validation"]
(stamp/"erasure-tombstones.json").write_text(json.dumps([
    {"authUserId":"00000000-0000-4000-a000-0000000000b1","erasedAt":"2026-09-01T00:00:00Z"}
]))
(stamp/"backup-manifest.json").write_text(json.dumps({
    "schemaVersion":3,"timestamp":stamp.name,"gitSha":"abc","deploymentProfile":"hosted-beta",
    "destination":str(stamp),"databases":dbs,"databasesOk":10,"databasesFailed":0,"minioOk":1,
    "minio":{"bucket":"parkio-media","objectCount":1,"artifact":"minio.tar.gz.enc"},
    "encryption":{"enabled":True,"algorithm":"aes-256-cbc-pbkdf2"},
    "offsite":{"kind":"azure","uploaded":False}
}))
profile={"pgDumpVersion":"16.15","serverVersion":"16.15","extensions":[],"restrictCommands":True}
(stamp/"auth.dump-profile.json").write_text(json.dumps(profile))
PY
  write_integrity "$stamp"
}

make_db_only_stamp() {
  local stamp="$1"
  make_full_stamp "$stamp"
  rm -f "${stamp}/minio.tar.gz.enc"
  python3 - "$stamp" <<'PY'
import json,sys
from pathlib import Path
p=Path(sys.argv[1])/"backup-manifest.json"
m=json.loads(p.read_text()); m["minioOk"]=0; m["minio"]={"objectCount":0}
p.write_text(json.dumps(m))
PY
  write_integrity "$stamp"
}

ENV_FILE="${WORK}/env"
cat > "${ENV_FILE}" <<'EOF'
PARKIO_DEPLOYMENT_PROFILE=hosted-beta
EOF

export PATH="${BIN}:${PATH}"
export PARKIO_F03_LOG="${LOG}"
export PARKIO_ENV_FILE="${ENV_FILE}"
export PARKIO_STUB_PSQL_VERSION=16.15
export PARKIO_STUB_SERVER_VERSION=16.15
export PARKIO_RESTORE_CLIENT_VERSION="psql (PostgreSQL) 16.15"
export PARKIO_RESTORE_TARGET_SERVER_VERSION=16.15
export PARKIO_RESTORE_DUMP_PROFILE=""
export BACKUP_ENCRYPT_PASSPHRASE="f03-synthetic-not-prod"

STAMP="${WORK}/2026-09-20T03-30-01Z"
make_full_stamp "${STAMP}"
MANIFEST="${STAMP}/backup-manifest.json"

run_hosted() {
  : > "${LOG}"
  PARKIO_ENV_FILE="${ENV_FILE}" \
    "${ROOT}/scripts/restore-hosted-beta.sh" --manifest "${MANIFEST}" --yes "$@"
}

no_destroy() {
  if grep -E 'PSQL_APPLY|DOCKER_RUN' "${LOG}" >/dev/null; then
    return 1
  fi
  return 0
}

# --- missing COMPLETE ---
cp -a "${STAMP}" "${WORK}/no-complete"
rm -f "${WORK}/no-complete/COMPLETE"
MANIFEST="${WORK}/no-complete/backup-manifest.json"
if run_hosted --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "missing COMPLETE must fail"
else
  if no_destroy; then ok "missing COMPLETE fails with zero destructive commands"; else bad "missing COMPLETE leaked destructive commands"; fi
fi

# --- malformed manifest ---
MANIFEST="${STAMP}/backup-manifest.json"
printf '{' > "${STAMP}/backup-manifest.json"
if run_hosted --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "malformed manifest must fail"
else
  if no_destroy; then ok "malformed manifest fails closed"; else bad "malformed manifest leaked commands"; fi
fi
make_full_stamp "${STAMP}"
MANIFEST="${STAMP}/backup-manifest.json"

# --- failed DB in manifest ---
python3 - "${STAMP}" <<'PY'
import json,sys
from pathlib import Path
p=Path(sys.argv[1])/"backup-manifest.json"
m=json.loads(p.read_text()); m["databasesFailed"]=2
p.write_text(json.dumps(m))
PY
write_integrity "${STAMP}"
if run_hosted --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "failed DB manifest must fail"
else
  if no_destroy; then ok "failed DB/MinIO manifest fails closed"; else bad "failed DB leaked commands"; fi
fi
make_full_stamp "${STAMP}"

# --- checksum mismatch ---
printf 'x' >> "${STAMP}/auth.sql.gz.enc"
if run_hosted --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "checksum mismatch must fail"
else
  if no_destroy; then ok "checksum mismatch fails before decrypt"; else bad "checksum mismatch leaked commands"; fi
fi
make_full_stamp "${STAMP}"

# --- missing file ---
rm -f "${STAMP}/user.sql.gz.enc"
write_integrity "${STAMP}"
if run_hosted --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "missing dump must fail"
else
  if no_destroy; then ok "missing dump fails closed"; else bad "missing dump leaked commands"; fi
fi
make_full_stamp "${STAMP}"

# --- path traversal in SHA256SUMS ---
printf 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  ./../../etc/passwd\n' >> "${STAMP}/SHA256SUMS"
if run_hosted --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "path traversal must fail"
else
  if no_destroy; then ok "path traversal in SHA256SUMS fails closed"; else bad "path traversal leaked commands"; fi
fi
make_full_stamp "${STAMP}"

# --- DB-only stamp cannot be a full restore ---
DBONLY="${WORK}/db-only/2026-09-20T03-30-01Z"
make_db_only_stamp "${DBONLY}"
MANIFEST="${DBONLY}/backup-manifest.json"
if PARKIO_ENV_FILE="${ENV_FILE}" "${ROOT}/scripts/restore-hosted-beta.sh" \
    --manifest "${MANIFEST}" --yes --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "DB-only stamp must not qualify as full restore"
else
  if no_destroy; then ok "DB-only stamp refused as full-system backup"; else bad "DB-only full restore leaked commands"; fi
fi

# --- missing ledger ---
MANIFEST="${STAMP}/backup-manifest.json"
rm -f "${STAMP}/erasure-tombstones.json"
write_integrity "${STAMP}"
if run_hosted --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "missing ledger must fail"
else
  if no_destroy; then ok "missing ledger fails closed"; else bad "missing ledger leaked commands"; fi
fi
make_full_stamp "${STAMP}"

# --- malformed ledger ---
printf '{' > "${STAMP}/erasure-tombstones.json"
write_integrity "${STAMP}"
if run_hosted --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "malformed ledger must fail"
else
  if no_destroy; then ok "malformed ledger fails closed"; else bad "malformed ledger leaked commands"; fi
fi
make_full_stamp "${STAMP}"

# --- insufficient coverage: stamp-time ledger vs later cutoff ---
if run_hosted --recovery-cutoff "2026-09-21T15:00:00Z" >/dev/null 2>&1; then
  bad "later cutoff without newer ledger must BLOCK"
else
  if no_destroy; then ok "insufficient recovery coverage blocks before decrypt"; else bad "coverage gap leaked commands"; fi
fi

# --- missing cutoff ---
if run_hosted >/dev/null 2>&1; then
  bad "missing recovery cutoff must fail"
else
  if no_destroy; then ok "missing recovery cutoff fails closed"; else bad "missing cutoff leaked commands"; fi
fi

# --- restore-database standalone path traversal ---
: > "${LOG}"
if PARKIO_ENV_FILE="${ENV_FILE}" "${ROOT}/scripts/restore-database.sh" \
    auth "${STAMP}/../2026-09-20T03-30-01Z/auth.sql.gz.enc" --yes \
    --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  :
fi
# resolved path is still inside stamp; use a dump outside the stamp
printf 'Salted__x' > "${WORK}/outside.sql.gz.enc"
: > "${LOG}"
if PARKIO_ENV_FILE="${ENV_FILE}" "${ROOT}/scripts/restore-database.sh" \
    auth "${WORK}/outside.sql.gz.enc" --yes --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "dump outside COMPLETE stamp must fail"
else
  if no_destroy; then ok "standalone dump outside stamp fails closed"; else bad "outside dump leaked commands"; fi
fi

# --- incompatible restore client (16.4 vs 16.15 restrict dump) ---
: > "${LOG}"
export PARKIO_RESTORE_DUMP_PROFILE="${STAMP}/auth.dump-profile.json"
export PARKIO_RESTORE_CLIENT_VERSION="psql (PostgreSQL) 16.4"
export PARKIO_RESTORE_TARGET_SERVER_VERSION=16.15
if PARKIO_ENV_FILE="${ENV_FILE}" "${ROOT}/scripts/restore-database.sh" \
    auth "${STAMP}/auth.sql.gz.enc" --yes --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "incompatible restore client must fail"
else
  if grep PSQL_APPLY "${LOG}" >/dev/null; then
    bad "incompatible client still applied SQL"
  else
    ok "incompatible client fails before apply"
  fi
fi
export PARKIO_RESTORE_CLIENT_VERSION="psql (PostgreSQL) 16.15"

# --- valid synthetic recovery through the safe path ---
: > "${LOG}"
if PARKIO_ENV_FILE="${ENV_FILE}" \
    PARKIO_RESTORE_DUMP_PROFILE="${STAMP}/auth.dump-profile.json" \
    "${ROOT}/scripts/restore-hosted-beta.sh" \
    --manifest "${MANIFEST}" --yes --only databases \
    --recovery-cutoff "2026-09-20T03:30:01Z" >/tmp/parkio-f03-valid.out 2>&1; then
  if grep -q 'Applications, publishers' /tmp/parkio-f03-valid.out \
     && grep PSQL_APPLY "${LOG}" >/dev/null; then
    ok "valid synthetic databases restore through the safe path"
  else
    bad "valid path did not restore or started apps"
    cat /tmp/parkio-f03-valid.out >&2 || true
  fi
else
  bad "valid synthetic restore should pass"
  cat /tmp/parkio-f03-valid.out >&2 || true
fi

# --- interrupted restore / failure propagation ---
: > "${LOG}"
export PARKIO_STUB_PSQL_FAIL=1
if PARKIO_ENV_FILE="${ENV_FILE}" \
    PARKIO_RESTORE_DUMP_PROFILE="${STAMP}/auth.dump-profile.json" \
    "${ROOT}/scripts/restore-hosted-beta.sh" \
    --manifest "${MANIFEST}" --yes --only databases \
    --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "failed apply must propagate"
else
  applies="$(grep -c PSQL_APPLY "${LOG}" || true)"
  if [ "${applies}" -le 1 ]; then
    ok "failed apply stops further database restores"
  else
    bad "failed apply continued to later databases (${applies})"
  fi
fi
export PARKIO_STUB_PSQL_FAIL=0

# --- newer ledger keeps post-stamp erasure and unrelated account ---
NEWER="${WORK}/2026-09-21T03-30-01Z"
make_full_stamp "${NEWER}"
python3 - "${STAMP}" "${NEWER}" <<'PY'
import json,sys
from pathlib import Path
erased_before="00000000-0000-4000-a000-0000000000b1"
erased_after="00000000-0000-4000-a000-0000000000a1"
unrelated="00000000-0000-4000-a000-000000000099"
Path(sys.argv[1],"erasure-tombstones.json").write_text(json.dumps([
    {"authUserId":erased_before,"erasedAt":"2026-09-01T00:00:00Z"}
]))
Path(sys.argv[2],"erasure-tombstones.json").write_text(json.dumps([
    {"authUserId":erased_before,"erasedAt":"2026-09-01T00:00:00Z"},
    {"authUserId":erased_after,"erasedAt":"2026-09-21T04:00:00Z"}
]))
# rewrite integrity for both
import hashlib
def integ(stamp):
    stamp=Path(stamp)
    files=sorted(p.relative_to(stamp).as_posix() for p in stamp.rglob("*") if p.is_file() and p.name not in ("SHA256SUMS","COMPLETE"))
    lines=[f"{hashlib.sha256((stamp/f).read_bytes()).hexdigest()}  ./{f}" for f in files]
    (stamp/"SHA256SUMS").write_text("\n".join(lines)+"\n")
    digest=hashlib.sha256((stamp/"SHA256SUMS").read_bytes()).hexdigest()
    (stamp/"COMPLETE").write_text(f"stamp={stamp.name}\nsha256sums={digest}\n")
integ(sys.argv[1]); integ(sys.argv[2])
PY
export PARKIO_RESTORE_MERGED_LEDGER="${WORK}/merged.json"
if PARKIO_ENV_FILE="${ENV_FILE}" \
    PARKIO_RESTORE_DUMP_PROFILE="${STAMP}/auth.dump-profile.json" \
    "${ROOT}/scripts/restore-hosted-beta.sh" \
    --manifest "${MANIFEST}" --yes --only databases \
    --recovery-cutoff "2026-09-21T03:30:01Z" \
    --ledger-stamp "${NEWER}" >/dev/null 2>&1; then
  python3 - "${WORK}/merged.json" <<'PY'
import json,sys
ids={e["authUserId"] for e in json.loads(open(sys.argv[1]).read())}
assert "00000000-0000-4000-a000-0000000000a1" in ids, ids
assert "00000000-0000-4000-a000-0000000000b1" in ids, ids
assert "00000000-0000-4000-a000-000000000099" not in ids, ids
PY
  ok "post-stamp erasure is in merged set; unrelated account stays out"
else
  bad "valid restore with newer ledger should pass"
fi

echo
echo "${pass} passed, ${fail} failed"
[ "${fail}" -eq 0 ]
