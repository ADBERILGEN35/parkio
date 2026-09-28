#!/usr/bin/env bash
# Stub-only teardown/volume trust-boundary tests. No live docker daemon and
# no production resources. Reproduces the pre-fix down path, then the current
# fail-closed down/apply policy.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
chmod +x "${ROOT}/scripts/restore-isolated-fixture.sh"

pass=0
fail=0
ok() { echo "PASS $1"; pass=$((pass + 1)); }
bad() { echo "FAIL $1"; fail=$((fail + 1)); }

need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: missing $1" >&2; exit 2; }; }
need bash
need python3

WORK="$(mktemp -d "${TMPDIR:-/tmp}/parkio-iso-teardown.XXXXXX")"
trap 'rm -rf "${WORK}"' EXIT
BIN="${WORK}/bin"
LOG="${WORK}/commands.log"
STATE="${WORK}/deleted.json"
mkdir -p "${BIN}" "${WORK}/inspect" "${WORK}/stamp"
: > "${LOG}"
printf '%s\n' '{"containers":[],"volumes":[],"networks":[]}' > "${STATE}"

STAMP="${WORK}/stamp"
# Stamp directory must exist for topology helper realpath in tickets.
mkdir -p "${STAMP}"

cat > "${BIN}/docker" <<'PY'
#!/usr/bin/env python3
import json, os, sys
log = os.environ["PARKIO_F03_LOG"]
state_path = os.environ["PARKIO_STUB_STATE"]
inspect_dir = os.environ.get("PARKIO_F03_INSPECT_DIR", "")
fail_mode = os.environ.get("PARKIO_STUB_FAIL", "")
with open(log, "a", encoding="utf-8") as handle:
    handle.write("DOCKER " + " ".join(sys.argv[1:]) + "\n")
args = sys.argv[1:]


def load_json(name):
    if not inspect_dir or not name:
        return None
    base = str(name).lstrip("/")
    for candidate in (name, base, str(name).split(":")[-1]):
        path = os.path.join(inspect_dir, f"{candidate}.json")
        if os.path.isfile(path):
            return json.loads(open(path, encoding="utf-8").read())
    return None


def state():
    return json.loads(open(state_path, encoding="utf-8").read())


def save_state(data):
    open(state_path, "w", encoding="utf-8").write(json.dumps(data))


def deleted(kind, ref):
    data = state()
    refs = set(data.get(kind) or [])
    return ref in refs or str(ref).lstrip("/") in refs


def mark_deleted(kind, *refs):
    data = state()
    bucket = list(data.get(kind) or [])
    for ref in refs:
        if not ref:
            continue
        if ref not in bucket:
            bucket.append(ref)
        bare = str(ref).lstrip("/")
        if bare not in bucket:
            bucket.append(bare)
    data[kind] = bucket
    save_state(data)


def absent(ref):
    print(f"Error: No such object: {ref}", file=sys.stderr)
    sys.exit(1)


def permission(ref):
    print(f"Error: permission denied: {ref}", file=sys.stderr)
    sys.exit(1)


if not args:
    sys.exit(0)
cmd = args[0]
if cmd == "context":
    if len(args) > 1 and args[1] == "show":
        print("default")
        sys.exit(0)
    if len(args) > 1 and args[1] == "inspect":
        data = load_json("context") or {
            "Name": "default",
            "Endpoints": {"docker": {"Host": "unix:///var/run/docker.sock"}},
        }
        json.dump([data], sys.stdout)
        sys.exit(0)
if cmd == "info":
    data = load_json("info") or {"ID": "stub-engine", "Name": "stub", "Swarm": {"LocalNodeState": "inactive"}}
    json.dump(data, sys.stdout)
    sys.exit(0)
if cmd == "inspect":
    ref = args[-1]
    kind = "container"
    if "--type=volume" in args or (len(args) > 2 and args[1] == "--type" and args[2] == "volume"):
        kind = "volume"
    elif "--type=network" in args or (len(args) > 2 and args[1] == "--type" and args[2] == "network"):
        kind = "network"
    buckets = {"container": "containers", "volume": "volumes", "network": "networks"}
    if fail_mode == f"inspect-{kind}":
        permission(ref)
    if deleted(buckets[kind], ref):
        absent(ref)
    data = load_json(ref)
    if data is None:
        absent(ref)
    json.dump([data], sys.stdout)
    sys.exit(0)
if cmd == "ps":
    vol = None
    i = 1
    while i < len(args):
        if args[i] == "--filter" and i + 1 < len(args) and args[i + 1].startswith("volume="):
            vol = args[i + 1].split("=", 1)[1]
            i += 2
            continue
        i += 1
    if vol:
        data = load_json(f"volume-consumers-{vol}")
        if isinstance(data, list):
            alive = [cid for cid in data if not deleted("containers", cid)]
            sys.stdout.write("\n".join(alive) + ("\n" if alive else ""))
    sys.exit(0)
if cmd == "rm":
    ref = args[-1]
    if fail_mode in ("rm", "rm-container"):
        permission(ref)
    obj = load_json(ref)
    mark_deleted("containers", ref, (obj or {}).get("Id"), (obj or {}).get("Name"))
    sys.exit(0)
if cmd == "volume" and len(args) > 1 and args[1] == "inspect":
    ref = args[-1]
    if fail_mode == "inspect-volume":
        permission(ref)
    if deleted("volumes", ref):
        absent(ref)
    data = load_json(ref)
    if data is None:
        absent(ref)
    json.dump([data], sys.stdout)
    sys.exit(0)
if cmd == "volume" and len(args) > 1 and args[1] == "rm":
    ref = args[-1]
    if fail_mode in ("rm", "rm-volume"):
        permission(ref)
    mark_deleted("volumes", ref)
    sys.exit(0)
if cmd == "network" and len(args) > 1 and args[1] == "inspect":
    ref = args[-1]
    if deleted("networks", ref):
        absent(ref)
    data = load_json(ref)
    if data is None:
        absent(ref)
    json.dump([data], sys.stdout)
    sys.exit(0)
if cmd == "network" and len(args) > 1 and args[1] == "rm":
    ref = args[-1]
    if fail_mode in ("rm", "rm-network"):
        permission(ref)
    obj = load_json(ref)
    mark_deleted("networks", ref, (obj or {}).get("Id"), (obj or {}).get("Name"))
    sys.exit(0)
print(f"unexpected docker args: {args}", file=sys.stderr)
sys.exit(2)
PY
chmod +x "${BIN}/docker"

export PATH="${BIN}:${PATH}"
export PARKIO_F03_LOG="${LOG}"
export PARKIO_F03_INSPECT_DIR="${WORK}/inspect"
export PARKIO_STUB_STATE="${STATE}"
export PARKIO_RESTORE_DOCKER="${BIN}/docker"
export PARKIO_STUB_FAIL=""
docker() { python3 "${PARKIO_RESTORE_DOCKER}" "$@"; }
export -f docker

mint() {
  local mode="$1"
  local ticket="${WORK}/ticket-${mode}.json"
  rm -rf "${PARKIO_F03_INSPECT_DIR}"
  mkdir -p "${PARKIO_F03_INSPECT_DIR}"
  printf '%s\n' '{"containers":[],"volumes":[],"networks":[]}' > "${STATE}"
  python3 "${ROOT}/scripts/test-restore-isolated-topology.py" \
    --inspect-dir "${PARKIO_F03_INSPECT_DIR}" \
    --stamp "${STAMP}" \
    --out-ticket "${ticket}" \
    --mode "${mode}"
  echo "${ticket}"
}

reset_log() { : > "${LOG}"; }

no_destroy() {
  if grep -E 'DOCKER rm |DOCKER volume rm|DOCKER network rm' "${LOG}" >/dev/null; then
    return 1
  fi
  return 0
}

run_down() {
  "${ROOT}/scripts/restore-isolated-fixture.sh" down --ticket "$1"
}

# --- A: historical unsafe down vs current fail-closed down ---
REVIEWER_TICKET="${WORK}/reviewer-production-names.json"
python3 - "${REVIEWER_TICKET}" <<'PY'
import json, sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({
    "postgres": {
        "auth": {
            "containerId": "parkio-postgres-auth",
            "volumeName": "parkio_postgres_auth",
        }
    }
}) + "\n", encoding="utf-8")
PY
reset_log
python3 - "${REVIEWER_TICKET}" <<'PY'
import json, os, subprocess, sys, time
# Replica of restore-isolated-fixture.sh down at 9f10d37b. PARKIO_RESTORE_DOCKER
# only routes the same argv through the stub; it does not add validation.
docker_cli = os.environ.get("PARKIO_RESTORE_DOCKER")

def docker_argv(args):
    if docker_cli:
        return [sys.executable, docker_cli, *args[1:]]
    return args

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
    subprocess.run(docker_argv(args), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)


for cid in containers:
    run(["docker", "rm", "-f", cid])
for vol in volumes:
    run(["docker", "volume", "rm", vol])
for _ in range(10):
    remaining = False
    for ident in network_ids:
        inspect = subprocess.run(
            docker_argv(["docker", "network", "inspect", ident]),
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
LEGACY_RC=0
rm -f "${REVIEWER_TICKET}"
if [ ! -f "${REVIEWER_TICKET}" ] \
   && grep -q 'DOCKER rm -f parkio-postgres-auth' "${LOG}" \
   && grep -q 'DOCKER volume rm parkio_postgres_auth' "${LOG}"; then
  ok "A before: unvalidated down issued production rm/volume rm and deleted the ticket (rc=${LEGACY_RC})"
else
  bad "A before: historical down did not reproduce production destructive commands"
  cat "${LOG}" >&2 || true
fi

python3 - "${REVIEWER_TICKET}" <<'PY'
import json, sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({
    "postgres": {
        "auth": {
            "containerId": "parkio-postgres-auth",
            "volumeName": "parkio_postgres_auth",
        }
    }
}) + "\n", encoding="utf-8")
PY
reset_log
set +e
run_down "${REVIEWER_TICKET}" >/dev/null 2>&1
AFTER_RC=$?
set -e
if [ "${AFTER_RC}" -ne 0 ] && [ -f "${REVIEWER_TICKET}" ] && no_destroy; then
  ok "A after: current down refuses reviewer production-name ticket, keeps it, zero destructive commands"
else
  bad "A after: current down rc=${AFTER_RC} ticket=$( [ -f "${REVIEWER_TICKET}" ] && echo kept || echo deleted ) destroy=$(no_destroy && echo none || echo yes)"
  cat "${LOG}" >&2 || true
fi

# --- B: invalid/tampered/wrong-daemon/production/mixed ---
refuse_down() {
  local mode="$1"
  local label="$2"
  local ticket
  ticket="$(mint "${mode}")"
  reset_log
  set +e
  run_down "${ticket}" >/dev/null 2>&1
  local rc=$?
  set -e
  if [ "${rc}" -ne 0 ] && [ -f "${ticket}" ] && no_destroy; then
    ok "B ${label}: nonzero, ticket retained, zero destructive commands"
  else
    bad "B ${label}: rc=${rc} ticket=$( [ -f "${ticket}" ] && echo kept || echo deleted ) destroy=$(no_destroy && echo none || echo yes)"
    cat "${LOG}" >&2 || true
  fi
}

refuse_down old-marker "invalid ticket"
refuse_down forged "tampered ticket digest"
refuse_down wrong-daemon "wrong docker engine"
refuse_down production-name "production container names"
refuse_down mixed-unrelated "mixed valid fixture plus unrelated targets"

# --- C: bind/device/NFS volumes refused before decrypt/apply (covered in
# preflight) and before destructive cleanup ---
refuse_down bind-volume "bind-option volume teardown"
refuse_down device-volume "device-option volume teardown"
refuse_down nfs-volume "NFS-option volume teardown"
refuse_down cifs-volume "CIFS-option volume teardown"

# --- D: valid fixture cleanup succeeds ---
TICKET="$(mint ok)"
reset_log
if run_down "${TICKET}" >/dev/null 2>&1 && [ ! -f "${TICKET}" ]; then
  if grep -q 'DOCKER rm -f aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "${LOG}" \
     && grep -q 'DOCKER volume rm parkio-iso-abcdef012345-vol-pg' "${LOG}" \
     && grep -q 'DOCKER network rm dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd' "${LOG}" \
     && ! grep -E 'parkio-postgres-auth|parkio_postgres_auth|parkio-minio($| )' "${LOG}" >/dev/null; then
    ok "D valid fixture cleanup deleted ticket-bound ids/names only and removed the ticket"
  else
    bad "D valid cleanup used unexpected docker targets"
    cat "${LOG}" >&2 || true
  fi
else
  bad "D valid fixture cleanup should succeed and remove the ticket"
  cat "${LOG}" >&2 || true
fi

# --- E: repeated cleanup and partial prior cleanup ---
TICKET="$(mint ok)"
TICKET_COPY="${WORK}/ticket-ok-copy.json"
cp -a "${TICKET}" "${TICKET_COPY}"
reset_log
run_down "${TICKET}" >/dev/null 2>&1
reset_log
set +e
run_down "${TICKET_COPY}" >/dev/null 2>&1
REPEAT_RC=$?
set -e
if [ "${REPEAT_RC}" -eq 0 ] && [ ! -f "${TICKET_COPY}" ] && no_destroy; then
  ok "E repeated cleanup of an already-removed fixture is idempotent and issues no deletes"
else
  bad "E repeated cleanup rc=${REPEAT_RC} ticket=$( [ -f "${TICKET_COPY}" ] && echo kept || echo deleted ) destroy=$(no_destroy && echo none || echo yes)"
  cat "${LOG}" >&2 || true
fi

TICKET="$(mint ok)"
python3 - "${STATE}" <<'PY'
import json, sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({
    "containers": ["a"*64, "b"*64],
    "volumes": [],
    "networks": [],
}) + "\n")
PY
reset_log
if run_down "${TICKET}" >/dev/null 2>&1 && [ ! -f "${TICKET}" ]; then
  if ! grep -q 'DOCKER rm -f' "${LOG}" \
     && grep -q 'DOCKER volume rm parkio-iso-abcdef012345-vol-pg' "${LOG}" \
     && grep -q 'DOCKER network rm' "${LOG}"; then
    ok "E partial prior cleanup removes remaining volumes/network without requiring running containers"
  else
    bad "E partial prior cleanup docker plan was wrong"
    cat "${LOG}" >&2 || true
  fi
else
  bad "E partial prior cleanup should succeed"
  cat "${LOG}" >&2 || true
fi

TICKET="$(mint stopped)"
reset_log
if run_down "${TICKET}" >/dev/null 2>&1 && [ ! -f "${TICKET}" ]; then
  ok "E stopped-container fixture teardown succeeds"
else
  bad "E stopped-container fixture teardown failed"
  cat "${LOG}" >&2 || true
fi

# --- F: injected deletion/inspection failure retains the ticket ---
TICKET="$(mint ok)"
reset_log
export PARKIO_STUB_FAIL=inspect-volume
set +e
run_down "${TICKET}" >/dev/null 2>&1
INSPECT_RC=$?
set -e
export PARKIO_STUB_FAIL=""
if [ "${INSPECT_RC}" -ne 0 ] && [ -f "${TICKET}" ] && no_destroy; then
  ok "F injected inspect failure returns nonzero, retains ticket, zero deletes"
else
  bad "F inspect failure rc=${INSPECT_RC} ticket=$( [ -f "${TICKET}" ] && echo kept || echo deleted ) destroy=$(no_destroy && echo none || echo yes)"
  cat "${LOG}" >&2 || true
fi

TICKET="$(mint ok)"
reset_log
export PARKIO_STUB_FAIL=rm-container
set +e
run_down "${TICKET}" >/dev/null 2>&1
RM_RC=$?
set -e
export PARKIO_STUB_FAIL=""
if [ "${RM_RC}" -ne 0 ] && [ -f "${TICKET}" ]; then
  ok "F injected deletion failure returns nonzero and retains the ticket"
else
  bad "F deletion failure rc=${RM_RC} ticket=$( [ -f "${TICKET}" ] && echo kept || echo deleted )"
  cat "${LOG}" >&2 || true
fi

echo
echo "${pass} passed, ${fail} failed"
[ "${fail}" -eq 0 ]
