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

cat > "${BIN}/docker" <<'PY'
#!/usr/bin/env python3
import json, os, sys
log = os.environ["PARKIO_F03_LOG"]
with open(log, "a", encoding="utf-8") as handle:
    handle.write("DOCKER " + " ".join(sys.argv[1:]) + "\n")
args = sys.argv[1:]
inspect_dir = os.environ.get("PARKIO_F03_INSPECT_DIR", "")

def load(name):
    if not inspect_dir:
        return None
    base = name.lstrip("/")
    for candidate in (name, base, name.split(":")[-1]):
        path = os.path.join(inspect_dir, f"{candidate}.json")
        if os.path.isfile(path):
            return json.loads(open(path, encoding="utf-8").read())
    return None

if not args:
    sys.exit(0)
cmd = args[0]
if cmd == "context":
    if len(args) > 1 and args[1] == "show":
        print("default")
        sys.exit(0)
    if len(args) > 1 and args[1] == "inspect":
        data = load("context") or {
            "Name": "default",
            "Endpoints": {"docker": {"Host": "unix:///var/run/docker.sock"}},
        }
        json.dump([data], sys.stdout)
        sys.exit(0)
if cmd == "info":
    data = load("info") or {"ID": "stub-engine", "Name": "stub", "Swarm": {"LocalNodeState": "inactive"}}
    json.dump(data, sys.stdout)
    sys.exit(0)
if cmd == "network" and len(args) > 1 and args[1] == "inspect":
    args = ["inspect", args[-1]]
    cmd = "inspect"
if cmd == "volume" and len(args) > 1 and args[1] == "inspect":
    args = ["inspect", args[-1]]
    cmd = "inspect"
if cmd == "inspect":
    ref = args[-1]
    data = load(ref)
    if data is None:
        # Old bypass: production names "existed" for a dummy inspect.
        if os.environ.get("PARKIO_STUB_INSPECT_ALWAYS", "0") == "1":
            sys.exit(0)
        print(f"Error: No such object: {ref}", file=sys.stderr)
        sys.exit(1)
    json.dump([data], sys.stdout)
    sys.exit(0)
if cmd == "ps":
    vol = None
    i = 1
    while i < len(args):
        tok = args[i]
        if tok == "--filter" and i + 1 < len(args):
            spec = args[i + 1]
            i += 2
            if spec.startswith("volume="):
                vol = spec.split("=", 1)[1]
            continue
        i += 1
    if vol:
        data = load(f"volume-consumers-{vol}")
        if isinstance(data, list):
            sys.stdout.write("\n".join(data) + ("\n" if data else ""))
    sys.exit(0)
if cmd == "exec":
    rest = args[1:]
    i = 0
    while i < len(rest):
        tok = rest[i]
        if tok == "-i":
            i += 1
            continue
        if tok in ("-U", "-d", "-c", "-At", "-v", "-X", "-q"):
            i += 2
            continue
        if tok == "psql":
            joined = " ".join(rest[i:])
            if len(rest) > i + 1 and rest[i + 1] == "--version":
                print(f"psql (PostgreSQL) {os.environ.get('PARKIO_STUB_PSQL_VERSION', '16.15')}")
                sys.exit(0)
            if "show server_version" in joined:
                print(os.environ.get("PARKIO_STUB_SERVER_VERSION", "16.15"))
                sys.exit(0)
            if "postgis" in joined:
                print(os.environ.get("PARKIO_STUB_POSTGIS_VERSION", "3.4.2"))
                sys.exit(0)
            if "to_regclass" in joined:
                print("erased_user_tombstones")
                sys.exit(0)
            with open(log, "a", encoding="utf-8") as handle:
                handle.write("PSQL_APPLY " + joined + "\n")
            try:
                sys.stdin.read()
            except Exception:
                pass
            sys.exit(1 if os.environ.get("PARKIO_STUB_PSQL_FAIL", "0") == "1" else 0)
        i += 1
    sys.exit(0)
if cmd == "run":
    with open(log, "a", encoding="utf-8") as handle:
        handle.write("DOCKER_RUN " + " ".join(args[1:]) + "\n")
    sys.exit(0)
sys.exit(0)
PY
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
export PARKIO_F03_INSPECT_DIR="${WORK}/inspect"
export PARKIO_RESTORE_DOCKER="${BIN}/docker"
mkdir -p "${PARKIO_F03_INSPECT_DIR}"
export PARKIO_F03_DOCKER_STUB="${BIN}/docker"
docker() { python3 "${PARKIO_F03_DOCKER_STUB}" "$@"; }
export -f docker
export PARKIO_F03_DOCKER_STUB
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
  local extra=()
  if [ -n "${PARKIO_RESTORE_ISOLATED_TICKET:-}" ]; then
    extra+=(--isolated-ticket "${PARKIO_RESTORE_ISOLATED_TICKET}")
  fi
  PARKIO_ENV_FILE="${ENV_FILE}" \
    "${ROOT}/scripts/restore-hosted-beta.sh" --manifest "${MANIFEST}" --yes \
    --isolated-fixture "${extra[@]}" "$@"
}

mint_ticket() {
  local mode="${1:-ok}"
  local ticket="${WORK}/ticket-${mode}.json"
  python3 "${ROOT}/scripts/test-restore-isolated-topology.py" \
    --inspect-dir "${PARKIO_F03_INSPECT_DIR}" \
    --stamp "${STAMP}" \
    --out-ticket "${ticket}" \
    --mode "${mode}"
  PARKIO_RESTORE_ISOLATED_TICKET="${ticket}"
  export PARKIO_RESTORE_ISOLATED_TICKET
}

no_destroy() {
  if grep -E 'PSQL_APPLY|DOCKER_RUN|OPENSSL .* -d' "${LOG}" >/dev/null; then
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

# --- incomplete supplemental with an apparently sufficient timestamp ---
printf '[]\n' > "${WORK}/incomplete-supplement.json"
: > "${LOG}"
if PARKIO_ENV_FILE="${ENV_FILE}" "${ROOT}/scripts/restore-hosted-beta.sh" \
    --manifest "${MANIFEST}" --yes --recovery-cutoff "2026-09-21T15:00:00Z" \
    --supplemental-ledger "${WORK}/incomplete-supplement.json" \
    --supplemental-covered-through "2026-09-21T15:00:00Z" >/dev/null 2>&1; then
  bad "incomplete supplemental must not certify coverage"
else
  if no_destroy; then ok "incomplete supplemental timestamp does not certify coverage"; else bad "uncertified supplemental leaked commands"; fi
fi

# --- incomplete ledger with a sufficiently new manifest timestamp ---
python3 - "${STAMP}" <<'PY'
import json,sys
from pathlib import Path
p=Path(sys.argv[1])/"erasure-tombstones.json"
p.write_text("[]")
PY
write_integrity "${STAMP}"
: > "${LOG}"
if run_hosted --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "empty ledger plus stamp-clock cutoff must not authorize production"
else
  if no_destroy; then ok "incomplete ledger with new manifest timestamp stays BLOCKED"; else bad "incomplete ledger leaked commands"; fi
fi
make_full_stamp "${STAMP}"

# --- equal/earlier cutoff without certified coverage ---
: > "${LOG}"
if run_hosted --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "equal cutoff must not authorize production without verified coverage"
else
  if no_destroy; then ok "equal cutoff without verified coverage stays BLOCKED"; else bad "equal cutoff leaked commands"; fi
fi

# --- standalone database production apply ---
: > "${LOG}"
if PARKIO_ENV_FILE="${ENV_FILE}" "${ROOT}/scripts/restore-database.sh" \
    auth "${STAMP}/auth.sql.gz.enc" --yes --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "standalone restore-database must refuse production apply"
else
  if no_destroy; then ok "standalone database production apply refused"; else bad "standalone database leaked commands"; fi
fi

# --- MinIO-only unsupported production scope ---
: > "${LOG}"
if run_hosted --only minio --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "MinIO-only production restore must be refused"
else
  if no_destroy; then ok "MinIO-only production scope refused"; else bad "MinIO-only leaked commands"; fi
fi

# --- env bypass flags without isolated-fixture ticket ---
: > "${LOG}"
if PARKIO_RESTORE_ISOLATED_DRILL=1 PARKIO_RESTORE_PREFLIGHT_DONE=1 \
    PARKIO_ENV_FILE="${ENV_FILE}" "${ROOT}/scripts/restore-hosted-beta.sh" \
    --manifest "${MANIFEST}" --yes --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "env bypass flags must not authorize production restore"
else
  if no_destroy; then ok "preflight/drill env flags do not bypass production refusal"; else bad "env bypass leaked commands"; fi
fi

# --- merged CI_EPHEMERAL / WP-06.2B flags must not authorize production restore ---
: > "${LOG}"
if PARKIO_ENVIRONMENT_TYPE=CI_EPHEMERAL \
    PARKIO_CI_HAS_STAGING_ENV=yes \
    PARKIO_STAGING_ALLOW_DESTRUCTIVE=yes \
    PARKIO_WP062B_EXECUTION_CLASS=CI_EPHEMERAL \
    PARKIO_WP062B_BUILD_IMAGES=yes \
    PARKIO_ENV_FILE="${ENV_FILE}" "${ROOT}/scripts/restore-hosted-beta.sh" \
    --manifest "${MANIFEST}" --yes --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "CI_EPHEMERAL restored-stack flags must not authorize production restore"
else
  if no_destroy; then ok "CI_EPHEMERAL restored-stack flags do not bypass production refusal"; else bad "CI_EPHEMERAL flags leaked commands"; fi
fi
if grep -E 'restore-hosted-beta\.sh|restore-database\.sh' \
    "${ROOT}/scripts/staging/run-wp062b-restored-stack-verification.sh" >/dev/null; then
  bad "WP-06.2B must not invoke production restore entrypoints"
else
  ok "WP-06.2B restored-stack uses its own drill DBs, not production entrypoints"
fi
: > "${LOG}"
if PARKIO_RESTORE_ISOLATED_DRILL=1 PARKIO_RESTORE_PREFLIGHT_DONE=1 \
    PARKIO_ENV_FILE="${ENV_FILE}" "${ROOT}/scripts/restore-database.sh" \
    auth "${STAMP}/auth.sql.gz.enc" --yes --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "env bypass flags must not authorize standalone apply"
else
  if no_destroy; then ok "standalone env flags do not bypass production refusal"; else bad "standalone env bypass leaked commands"; fi
fi

# --- N-01 / U01: CLI flag must not self-authorize (old bypass) ---
: > "${LOG}"
unset PARKIO_RESTORE_ISOLATED_TICKET || true
if run_isolated --only databases --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "CLI --isolated-fixture without orchestrator ticket must not apply"
else
  applies="$(grep -c PSQL_APPLY "${LOG}" || true)"
  if [ "${applies}" = "0" ] && no_destroy; then
    ok "CLI flag without destination-bound ticket is zero apply (old 11-apply bypass closed)"
  else
    bad "CLI flag leaked decrypt/apply (${applies})"
  fi
fi

: > "${LOG}"
if PARKIO_RESTORE_ISOLATED_FIXTURE=1 PARKIO_ENV_FILE="${ENV_FILE}" \
    "${ROOT}/scripts/restore-hosted-beta.sh" --manifest "${MANIFEST}" --yes \
    --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "env ISOLATED_FIXTURE=1 without ticket must not apply"
else
  if no_destroy; then ok "environment flag alone does not authorize isolation"; else bad "env isolated flag leaked commands"; fi
fi

refuse_ticket_mode() {
  local mode="$1"
  local label="$2"
  mint_ticket "${mode}"
  : > "${LOG}"
  if PARKIO_ENV_FILE="${ENV_FILE}" \
      "${ROOT}/scripts/restore-hosted-beta.sh" --manifest "${MANIFEST}" --yes \
      --only databases --isolated-fixture --isolated-ticket "${PARKIO_RESTORE_ISOLATED_TICKET}" \
      --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
    bad "${label} must fail closed"
  else
    if no_destroy; then ok "${label} produces zero decrypt/apply"; else bad "${label} leaked commands"; fi
  fi
}

refuse_ticket_mode old-marker "legacy stamp-only marker ticket"
refuse_ticket_mode forged "forged/mismatched ticket digest"
refuse_ticket_mode wrong-stamp "ticket bound to a different stamp"
refuse_ticket_mode wrong-target "ticket missing manifest postgres targets"
refuse_ticket_mode production-name "production/default container names"
refuse_ticket_mode misleading-name "misleading production DNS aliases"
refuse_ticket_mode published-port "published-port fixture topology"
refuse_ticket_mode extra-network "unsafe extra network attachment"
refuse_ticket_mode prod-volume "production-like volume mapping"
refuse_ticket_mode drift "live destination identity drift"
refuse_ticket_mode bind-volume "local-driver bind volume options"
refuse_ticket_mode device-volume "local-driver device volume options"
refuse_ticket_mode nfs-volume "local-driver NFS volume options"
refuse_ticket_mode cifs-volume "local-driver CIFS volume options"
refuse_ticket_mode unlabeled-volume "unlabeled fixture-shaped volume"
refuse_ticket_mode extra-mount "extra bind mount on fixture container"
refuse_ticket_mode extra-volume "extra volume mount on fixture container"
refuse_ticket_mode unrelated-consumer "volume attached to an unrelated container"
refuse_ticket_mode stopped "stopped fixture is not an apply destination"
refuse_ticket_mode wrong-daemon "ticket bound to a different docker engine"

OTHER_STAMP="${WORK}/other-stamp"
mkdir -p "${OTHER_STAMP}"
mint_ticket ok
: > "${LOG}"
if PARKIO_ENV_FILE="${ENV_FILE}" \
    "${ROOT}/scripts/restore-hosted-beta.sh" --manifest "${MANIFEST}" --yes \
    --only databases --isolated-fixture --isolated-ticket "${PARKIO_RESTORE_ISOLATED_TICKET}" \
    --stamp-dir "${OTHER_STAMP}" --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
  bad "selected stamp override must not use a ticket for another stamp"
else
  if no_destroy; then ok "wrong selected stamp vs ticket is zero apply"; else bad "stamp override leaked commands"; fi
fi
unset PARKIO_RESTORE_ISOLATED_TICKET || true

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
mint_ticket ok
: > "${LOG}"
export PARKIO_RESTORE_DUMP_PROFILE="${STAMP}/auth.dump-profile.json"
export PARKIO_RESTORE_CLIENT_VERSION="psql (PostgreSQL) 16.4"
export PARKIO_RESTORE_TARGET_SERVER_VERSION=16.15
if PARKIO_ENV_FILE="${ENV_FILE}" "${ROOT}/scripts/restore-database.sh" \
    auth "${STAMP}/auth.sql.gz.enc" --yes --isolated-fixture \
    --isolated-ticket "${PARKIO_RESTORE_ISOLATED_TICKET}" \
    --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1; then
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
mint_ticket ok
: > "${LOG}"
if PARKIO_ENV_FILE="${ENV_FILE}" \
    PARKIO_RESTORE_DUMP_PROFILE="${STAMP}/auth.dump-profile.json" \
    "${ROOT}/scripts/restore-hosted-beta.sh" \
    --manifest "${MANIFEST}" --yes --only databases --isolated-fixture \
    --isolated-ticket "${PARKIO_RESTORE_ISOLATED_TICKET}" \
    --recovery-cutoff "2026-09-20T03:30:01Z" >/tmp/parkio-f03-valid.out 2>&1; then
  if grep -q 'Applications, publishers' /tmp/parkio-f03-valid.out \
     && grep PSQL_APPLY "${LOG}" >/dev/null \
     && ! grep -E 'parkio-postgres-|parkio-minio' "${LOG}" >/dev/null; then
    ok "valid synthetic databases restore through destination-bound ticket"
  else
    bad "valid path did not restore, used production names, or started apps"
    cat /tmp/parkio-f03-valid.out >&2 || true
  fi
else
  bad "valid synthetic restore should pass"
  cat /tmp/parkio-f03-valid.out >&2 || true
fi

# --- interrupted restore / failure propagation ---
mint_ticket ok
: > "${LOG}"
export PARKIO_STUB_PSQL_FAIL=1
if PARKIO_ENV_FILE="${ENV_FILE}" \
    PARKIO_RESTORE_DUMP_PROFILE="${STAMP}/auth.dump-profile.json" \
    "${ROOT}/scripts/restore-hosted-beta.sh" \
    --manifest "${MANIFEST}" --yes --only databases --isolated-fixture \
    --isolated-ticket "${PARKIO_RESTORE_ISOLATED_TICKET}" \
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
mint_ticket ok
if PARKIO_ENV_FILE="${ENV_FILE}" \
    PARKIO_RESTORE_DUMP_PROFILE="${STAMP}/auth.dump-profile.json" \
    "${ROOT}/scripts/restore-hosted-beta.sh" \
    --manifest "${MANIFEST}" --yes --only databases --isolated-fixture \
    --isolated-ticket "${PARKIO_RESTORE_ISOLATED_TICKET}" \
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
