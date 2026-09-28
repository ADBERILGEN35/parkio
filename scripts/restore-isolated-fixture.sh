#!/usr/bin/env bash
#
# Disposable restore-fixture orchestrator. This is the only supported issuer of
# isolated-fixture tickets. Restore entrypoints never self-authorize.
#
# Usage:
#   scripts/restore-isolated-fixture.sh up --stamp DIR [--services auth,...] [--with-minio]
#   scripts/restore-isolated-fixture.sh down --ticket FILE
#
# The ticket binds the selected stamp to live docker context/host/engine plus
# container, network and volume identities. Container names, env flags, or a
# marker file alone are not isolation proof.
#
# Threat model: accidental/misrouted use of the supported restore scripts.
# This does not stop a malicious root operator who can edit these scripts.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/restore-safe-preflight.sh
source "${ROOT}/scripts/lib/restore-safe-preflight.sh"

usage() {
  sed -n '2,16p' "$0"
  exit 2
}

CMD="${1:-}"
[ -n "${CMD}" ] || usage
shift

STAMP=""
SERVICES="auth,gateway,user,parking,media,gamification,notification,moderation,analytics,ai-validation"
WITH_MINIO=0
TICKET=""
POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:16.10}"
MINIO_IMAGE="${MINIO_IMAGE:-ghcr.io/adberilgen35/parkio/minio@sha256:efba309ba4dc89e48f37304db52a0b854c0e701ba944ca02205c4e292c1a756c}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --stamp) STAMP="${2:-}"; shift 2 ;;
    --services) SERVICES="${2:-}"; shift 2 ;;
    --with-minio) WITH_MINIO=1; shift ;;
    --ticket) TICKET="${2:-}"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

wait_pg() {
  local name="$1"
  local i
  for i in $(seq 1 60); do
    if docker exec "${name}" pg_isready -U postgres >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "ERROR: fixture postgres ${name} did not become ready" >&2
  return 1
}

if [ "${CMD}" = "down" ]; then
  if [ -z "${TICKET}" ] || [ ! -f "${TICKET}" ]; then
    echo "ERROR: down requires --ticket <file>" >&2
    exit 2
  fi
  python3 - "${TICKET}" <<'PY'
import json, subprocess, sys, time

ticket = json.load(open(sys.argv[1], encoding="utf-8"))
containers = []
volumes = []
seen_c = set()
seen_v = set()
for dest in (ticket.get("postgres") or {}).values():
    cid = dest.get("containerId") or ""
    vol = dest.get("volumeName") or ""
    if cid and cid not in seen_c:
        containers.append(cid)
        seen_c.add(cid)
    if vol and vol not in seen_v:
        volumes.append(vol)
        seen_v.add(vol)
minio = ticket.get("minio") or {}
if minio.get("containerId"):
    containers.append(minio["containerId"])
if minio.get("volumeName") and minio["volumeName"] not in seen_v:
    volumes.append(minio["volumeName"])
network_ids = []
for key in ("id", "name"):
    value = (ticket.get("network") or {}).get(key)
    if value and value not in network_ids:
        network_ids.append(value)
if ticket.get("project") and ticket["project"] not in network_ids:
    network_ids.append(ticket["project"])


def run(args):
    subprocess.run(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)


for cid in containers:
    run(["docker", "rm", "-f", cid])
for vol in volumes:
    run(["docker", "volume", "rm", vol])
# Network last: docker refuses rm while endpoints still exist.
for _ in range(10):
    remaining = False
    for ident in network_ids:
        inspect = subprocess.run(
            ["docker", "network", "inspect", ident],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if inspect.returncode == 0:
            remaining = True
            run(["docker", "network", "rm", ident])
    if not remaining:
        break
    time.sleep(0.2)
PY
  rm -f "${TICKET}"
  exit 0
fi

if [ "${CMD}" != "up" ]; then
  echo "ERROR: command must be up or down" >&2
  exit 2
fi

if [ -z "${STAMP}" ] || [ ! -d "${STAMP}" ]; then
  echo "ERROR: up requires --stamp <existing stamp directory>" >&2
  exit 2
fi
STAMP="$(parkio_restore_realpath "${STAMP}")"

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker CLI is required to establish isolated restore targets" >&2
  exit 2
fi
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: docker daemon is not reachable; refusing ambiguous topology" >&2
  exit 2
fi

PROJECT="parkio-iso-$(python3 -c 'import secrets; print(secrets.token_hex(6))')"
PG_NAME="${PROJECT}-pg"
PG_VOL="${PROJECT}-vol-pg"
MINIO_NAME="${PROJECT}-minio"
MINIO_VOL="${PROJECT}-vol-minio"
PG_PASS="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
MINIO_PASS="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"

cleanup_partial() {
  docker rm -f "${PG_NAME}" >/dev/null 2>&1 || true
  docker rm -f "${MINIO_NAME}" >/dev/null 2>&1 || true
  docker volume rm "${PG_VOL}" >/dev/null 2>&1 || true
  docker volume rm "${MINIO_VOL}" >/dev/null 2>&1 || true
  docker network rm "${PROJECT}" >/dev/null 2>&1 || true
}
trap cleanup_partial EXIT

docker network create --internal \
  --label parkio.isolated.fixture=1 \
  --label "parkio.isolated.project=${PROJECT}" \
  "${PROJECT}" >/dev/null
docker volume create \
  --label parkio.isolated.fixture=1 \
  --label "parkio.isolated.project=${PROJECT}" \
  "${PG_VOL}" >/dev/null

docker pull -q "${POSTGRES_IMAGE}" >/dev/null
docker run -d \
  --name "${PG_NAME}" \
  --network "${PROJECT}" \
  --network-alias "${PG_NAME}" \
  --restart=no \
  --memory 512m \
  -v "${PG_VOL}:/var/lib/postgresql/data" \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD="${PG_PASS}" \
  -e POSTGRES_DB=postgres \
  --label parkio.isolated.fixture=1 \
  --label "parkio.isolated.project=${PROJECT}" \
  "${POSTGRES_IMAGE}" >/dev/null
wait_pg "${PG_NAME}"

BOOTSTRAP="$(mktemp "${TMPDIR:-/tmp}/parkio-iso-boot.XXXXXX.sql")"
python3 - "${SERVICES}" "${ROOT}" "${BOOTSTRAP}" <<'PY'
import json, subprocess, sys
services, root, out = sys.argv[1], sys.argv[2], sys.argv[3]
creds = json.loads(subprocess.check_output(
    [sys.executable, f"{root}/scripts/lib/restore-isolated-ticket.py", "service-creds"],
    text=True,
))
parts = []
create_db = []
for svc in [item.strip() for item in services.split(",") if item.strip()]:
    if svc not in creds:
        raise SystemExit(f"unknown service {svc}")
    user = creds[svc]["user"]
    db = creds[svc]["database"]
    parts.append(
        "DO $$ BEGIN "
        f"IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{user}') THEN "
        f"EXECUTE format('CREATE ROLE %I LOGIN', '{user}'); "
        "END IF; END $$;\n"
    )
    create_db.append((db, user))
open(out, "w", encoding="utf-8").write("".join(parts))
open(out + ".dbs", "w", encoding="utf-8").write(
    "\n".join(f"{db}\t{user}" for db, user in create_db) + "\n"
)
PY
docker exec -i "${PG_NAME}" psql -U postgres -d postgres -v ON_ERROR_STOP=1 < "${BOOTSTRAP}" >/dev/null
while IFS=$'\t' read -r db user; do
  [ -n "${db}" ] || continue
  exists="$(docker exec "${PG_NAME}" psql -U postgres -d postgres -At -c \
    "SELECT 1 FROM pg_database WHERE datname='${db}'")"
  if [ "${exists}" != "1" ]; then
    docker exec "${PG_NAME}" psql -U postgres -d postgres -v ON_ERROR_STOP=1 \
      -c "CREATE DATABASE ${db} OWNER ${user}" >/dev/null
  fi
done < "${BOOTSTRAP}.dbs"
rm -f "${BOOTSTRAP}" "${BOOTSTRAP}.dbs"

if echo ",${SERVICES}," | grep -q ',auth,'; then
  docker exec "${PG_NAME}" psql -U postgres -d parkio_auth -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
CREATE TABLE IF NOT EXISTS erased_user_tombstones (
  auth_user_id uuid PRIMARY KEY,
  erased_at timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS auth_users (
  id uuid PRIMARY KEY,
  status text NOT NULL,
  status_changed_at timestamptz,
  session_epoch integer
);
ALTER TABLE erased_user_tombstones OWNER TO parkio_auth;
ALTER TABLE auth_users OWNER TO parkio_auth;
GRANT ALL ON TABLE erased_user_tombstones, auth_users TO parkio_auth;
SQL
fi

if [ "${WITH_MINIO}" -eq 1 ]; then
  docker pull -q "${MINIO_IMAGE}" >/dev/null
  docker volume create \
    --label parkio.isolated.fixture=1 \
    --label "parkio.isolated.project=${PROJECT}" \
    "${MINIO_VOL}" >/dev/null
  docker run -d \
    --name "${MINIO_NAME}" \
    --network "${PROJECT}" \
    --network-alias "${MINIO_NAME}" \
    --restart=no \
    --memory 256m \
    -v "${MINIO_VOL}:/data" \
    -e MINIO_ROOT_USER=parkioiso \
    -e "MINIO_ROOT_PASSWORD=${MINIO_PASS}" \
    --label parkio.isolated.fixture=1 \
    --label "parkio.isolated.project=${PROJECT}" \
    "${MINIO_IMAGE}" server /data --console-address ":9001" >/dev/null
  for _ in $(seq 1 60); do
    if docker exec "${MINIO_NAME}" sh -c 'true' >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
fi

TICKET_OUT="$(mktemp "${TMPDIR:-/tmp}/parkio-isolated-ticket.XXXXXX.json")"
export PARKIO_RESTORE_FIXTURE_ORCHESTRATOR=1
issue_args=(
  "${ROOT}/scripts/lib/restore-isolated-inspect.py"
  --issue-from-live
  --out "${TICKET_OUT}"
  --stamp "${STAMP}"
  --project "${PROJECT}"
  --network-name "${PROJECT}"
  --postgres-name "${PG_NAME}"
  --services "${SERVICES}"
)
if [ "${WITH_MINIO}" -eq 1 ]; then
  issue_args+=(--minio-name "${MINIO_NAME}")
fi
python3 "${issue_args[@]}" >/dev/null
trap - EXIT
chmod 600 "${TICKET_OUT}"
echo "${TICKET_OUT}"
