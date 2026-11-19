#!/usr/bin/env bash
# Live disposable PostgreSQL/MinIO proof that the changed restore entrypoints
# apply only to orchestrator-established identities. Independent of
# restore-drill-01.sh. No production backup download/decrypt.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
chmod +x "${ROOT}/scripts/restore-isolated-fixture.sh" \
  "${ROOT}/scripts/restore-hosted-beta.sh" \
  "${ROOT}/scripts/restore-database.sh"

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "ERROR: docker daemon required in CI for isolated-fixture acceptance" >&2
    exit 1
  fi
  echo "SKIP live isolated-fixture docker tests (daemon not available)"
  exit 0
fi

need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: missing $1" >&2; exit 2; }; }
need python3
need openssl
need gzip

WORK="$(mktemp -d "${TMPDIR:-/tmp}/parkio-iso-live.XXXXXX")"
TICKET=""
SENTINEL="parkio-iso-sentinel-$$"

if ! command -v jq >/dev/null 2>&1; then
  mkdir -p "${WORK}/bin"
  cat > "${WORK}/bin/jq" <<'PY'
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
  chmod +x "${WORK}/bin/jq"
  PARKIO_F03_JQ_STUB="${WORK}/bin/jq"
  jq() { python3 "${PARKIO_F03_JQ_STUB}" "$@"; }
  export -f jq
  export PARKIO_F03_JQ_STUB
  export PATH="${WORK}/bin:${PATH}"
fi
cleanup() {
  if [ -n "${TICKET}" ] && [ -f "${TICKET}" ]; then
    "${ROOT}/scripts/restore-isolated-fixture.sh" down --ticket "${TICKET}" >/dev/null 2>&1 || true
  fi
  docker rm -f "${SENTINEL}" >/dev/null 2>&1 || true
  rm -rf "${WORK}"
}
trap cleanup EXIT

snapshot() {
  python3 - <<'PY'
import json, subprocess
def lines(args):
    out = subprocess.check_output(args, text=True)
    return sorted(x for x in out.splitlines() if x.strip())
print(json.dumps({
    "containers": lines(["docker", "ps", "-aq"]),
    "volumes": lines(["docker", "volume", "ls", "-q"]),
    "networks": lines(["docker", "network", "ls", "-q"]),
}))
PY
}

BEFORE="$(snapshot)"
docker pull -q "${POSTGRES_IMAGE:-postgres:16.10}" >/dev/null
docker create --name "${SENTINEL}" --restart=no "${POSTGRES_IMAGE:-postgres:16.10}" true >/dev/null
SENTINEL_ID="$(docker inspect -f '{{.Id}}' "${SENTINEL}")"

STAMP="${WORK}/2026-09-20T03-30-01Z"
mkdir -p "${STAMP}/minio/parkio-media/synthetic"
printf 'iso-canary\n' > "${STAMP}/minio/parkio-media/synthetic/obj.txt"
export BACKUP_ENCRYPT_PASSPHRASE="${BACKUP_ENCRYPT_PASSPHRASE:-parkio-iso-live-not-prod}"
SQL=$'-- Dumped from database version 16.10\n-- Dumped by pg_dump version 16.10\nBEGIN;\nCREATE TABLE IF NOT EXISTS parkio_restore_probe(id integer primary key);\nINSERT INTO parkio_restore_probe VALUES (1) ON CONFLICT DO NOTHING;\nCOMMIT;\n'
AUTH_SQL=$'-- Dumped from database version 16.10\n-- Dumped by pg_dump version 16.10\nBEGIN;\nCREATE TABLE IF NOT EXISTS parkio_restore_probe(id integer primary key);\nINSERT INTO parkio_restore_probe VALUES (1) ON CONFLICT DO NOTHING;\nCREATE TABLE IF NOT EXISTS erased_user_tombstones (auth_user_id uuid PRIMARY KEY, erased_at timestamptz NOT NULL);\nCREATE TABLE IF NOT EXISTS auth_users (id uuid PRIMARY KEY, status text NOT NULL, status_changed_at timestamptz, session_epoch integer);\nCOMMIT;\n'
for db in auth gateway user parking media gamification notification moderation analytics ai-validation; do
  payload="${SQL}"
  if [ "${db}" = auth ]; then payload="${AUTH_SQL}"; fi
  printf '%s' "${payload}" | gzip -n | openssl enc -aes-256-cbc -pbkdf2 -salt \
    -pass env:BACKUP_ENCRYPT_PASSPHRASE > "${STAMP}/${db}.sql.gz.enc"
  printf '{"pgDumpVersion":"16.10","serverVersion":"16.10","extensions":[],"restrictCommands":false}\n' \
    > "${STAMP}/${db}.dump-profile.json"
done
python3 - "${STAMP}" <<'PY'
import json, tarfile, io, os, subprocess, sys
from pathlib import Path
stamp = Path(sys.argv[1])
dbs = ["auth","gateway","user","parking","media","gamification","notification","moderation","analytics","ai-validation"]
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
PY
# shellcheck source=lib/backup-common.sh
source "${ROOT}/scripts/lib/backup-common.sh"
parkio_backup_seal_minio "${STAMP}"
python3 - "${STAMP}" <<'PY'
import hashlib, sys
from pathlib import Path
stamp = Path(sys.argv[1])
files = sorted(p.relative_to(stamp).as_posix() for p in stamp.rglob("*")
               if p.is_file() and p.name not in ("SHA256SUMS", "COMPLETE"))
lines = [f"{hashlib.sha256((stamp / f).read_bytes()).hexdigest()}  ./{f}" for f in files]
(stamp / "SHA256SUMS").write_text("\n".join(lines) + "\n")
digest = hashlib.sha256((stamp / "SHA256SUMS").read_bytes()).hexdigest()
(stamp / "COMPLETE").write_text(f"stamp={stamp.name}\nsha256sums={digest}\n")
PY

ENV_FILE="${WORK}/env"
cat > "${ENV_FILE}" <<'EOF'
PARKIO_DEPLOYMENT_PROFILE=hosted-beta
EOF

WITH_MINIO=()
if docker pull -q "${MINIO_IMAGE:-ghcr.io/adberilgen35/parkio/minio@sha256:efba309ba4dc89e48f37304db52a0b854c0e701ba944ca02205c4e292c1a756c}" >/dev/null \
   && docker pull -q "${MINIO_MC_IMAGE:-ghcr.io/adberilgen35/parkio/mc@sha256:456b1e641897329fc9491f9bc8b31df351d728af9a328bf5653707af62d0d6bf}" >/dev/null; then
  WITH_MINIO=(--with-minio)
elif [ -n "${GITHUB_ACTIONS:-}" ]; then
  echo "ERROR: CI must pull the pinned MinIO images for isolated-fixture acceptance" >&2
  exit 1
else
  echo "WARN: MinIO image unavailable locally; postgres-only live path"
fi

TICKET="$("${ROOT}/scripts/restore-isolated-fixture.sh" up --stamp "${STAMP}" --services \
  "auth,gateway,user,parking,media,gamification,notification,moderation,analytics,ai-validation" \
  "${WITH_MINIO[@]}")"
AFTER_UP="$(snapshot)"
PG_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["postgres"]["auth"]["containerId"])' "${TICKET}")"

PARKIO_ENV_FILE="${ENV_FILE}" \
  "${ROOT}/scripts/restore-hosted-beta.sh" \
  --manifest "${STAMP}/backup-manifest.json" --yes --only databases \
  --isolated-fixture --isolated-ticket "${TICKET}" \
  --recovery-cutoff "2026-09-20T03:30:01Z"

COUNT="$(docker exec "${PG_ID}" psql -U parkio_auth -d parkio_auth -At \
  -c 'select count(*) from parkio_restore_probe')"
if [ "${COUNT}" != "1" ]; then
  echo "ERROR: hosted-beta did not apply to the ticket postgres identity (count=${COUNT})" >&2
  exit 1
fi

PARKIO_ENV_FILE="${ENV_FILE}" \
  "${ROOT}/scripts/restore-database.sh" \
  gateway "${STAMP}/gateway.sql.gz.enc" --yes \
  --isolated-fixture --isolated-ticket "${TICKET}" \
  --recovery-cutoff "2026-09-20T03:30:01Z"

if [ "${#WITH_MINIO[@]}" -gt 0 ]; then
  PARKIO_ENV_FILE="${ENV_FILE}" \
    "${ROOT}/scripts/restore-hosted-beta.sh" \
    --manifest "${STAMP}/backup-manifest.json" --yes --only minio \
    --isolated-fixture --isolated-ticket "${TICKET}" \
    --recovery-cutoff "2026-09-20T03:30:01Z"
fi

if [ "$(docker inspect -f '{{.Id}}' "${SENTINEL}")" != "${SENTINEL_ID}" ]; then
  echo "ERROR: unrelated sentinel container identity changed" >&2
  exit 1
fi

"${ROOT}/scripts/restore-isolated-fixture.sh" down --ticket "${TICKET}"
TICKET=""
docker rm -f "${SENTINEL}" >/dev/null
python3 - <<'PY'
import subprocess, sys
def lines(args):
    return [x.strip() for x in subprocess.check_output(args, text=True).splitlines() if x.strip()]
leftover_c = lines(["docker", "ps", "-a", "--filter", "name=parkio-iso-", "--format", "{{.Names}}"])
leftover_v = [n for n in lines(["docker", "volume", "ls", "-q"]) if n.startswith("parkio-iso-")]
leftover_n = [n for n in lines(["docker", "network", "ls", "--format", "{{.Name}}"]) if n.startswith("parkio-iso-")]
if leftover_c or leftover_v or leftover_n:
    print("ERROR: fixture resources remain after down", leftover_c, leftover_v, leftover_n, file=sys.stderr)
    sys.exit(1)
print("fixture containers/volumes/networks removed; sentinel identity was unchanged during restore")
PY
echo "PASS live isolated-fixture entrypoints against disposable PostgreSQL/MinIO"
