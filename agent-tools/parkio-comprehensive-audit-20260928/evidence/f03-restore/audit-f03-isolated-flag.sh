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

run_isolated() {
  : > "${LOG}"
  PARKIO_ENV_FILE="${ENV_FILE}" \
    "${ROOT}/scripts/restore-hosted-beta.sh" --manifest "${MANIFEST}" --yes \
    --isolated-fixture "$@"
}

# ---- AUDIT REPRO (not product code) ----
set +e
# Production-shaped env: production deployment profile, default container names.
cat > "${ENV_FILE}" <<'EOT'
PARKIO_DEPLOYMENT_PROFILE=hosted-beta
EOT
unset PARKIO_RESTORE_ISOLATED_TICKET
echo "--- A: production path without flag ---"
: > "${LOG}"
"${ROOT}/scripts/restore-hosted-beta.sh" --manifest "${MANIFEST}" --yes --only databases \
  --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; echo "exit=$?"
echo "destructive lines: $(grep -cE 'PSQL_APPLY' "${LOG}")"
echo "--- B: same command + --isolated-fixture (no ticket supplied) ---"
: > "${LOG}"
"${ROOT}/scripts/restore-hosted-beta.sh" --manifest "${MANIFEST}" --yes --only databases \
  --recovery-cutoff "2026-09-20T03:30:01Z" --isolated-fixture >/dev/null 2>&1; echo "exit=$?"
echo "destructive lines: $(grep -cE 'PSQL_APPLY' "${LOG}")"
grep -E 'PSQL_APPLY|DOCKER_EXEC|docker exec' "${LOG}" | sed -E 's/[[:space:]]+/ /g' | cut -c1-160 | head -4
