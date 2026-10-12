#!/usr/bin/env python3
"""T1 - release steps for the scoped Civo hosted-beta release (plan v2.1 sections 10-13; owner decisions 2026-10-10 and
2026-10-11). Run as civo on parkio-civo-prod (Python 3.8+), one command per operator step, in this order:

  python3 -I /tmp/t1-release-step.py plan          P6: planned record (baseline); changes nothing
  python3 -I /tmp/t1-release-step.py step D2a      alertmanager      (within 2 h of the verified P5 backup, not 03:00-04:30 UTC)
  python3 -I /tmp/t1-release-step.py step D2b      prometheus
  python3 -I /tmp/t1-release-step.py step D2c      caddy
  python3 -I /tmp/t1-release-step.py nr-pause      stop only parkio-nr-log-continuous-guard.timer (N1)
  python3 -I /tmp/t1-release-step.py step D3       gateway-service with the waitlist overlay
  python3 -I /tmp/t1-release-step.py step D4       web (the wrapper's web guards run)
  python3 -I /tmp/t1-release-step.py step D5       auth-service
  python3 -I /tmp/t1-release-step.py step D6a      media-service
  python3 -I /tmp/t1-release-step.py step D6b      parking-service (V41 gate)
  python3 -I /tmp/t1-release-step.py nr-resume     three sources attached -> restart the guard timer and one guard run
  python3 -I /tmp/t1-release-step.py step D7       build the six, check them (pebble absent, Security CI scan), then start them
  python3 -I /tmp/t1-release-step.py final         E: smoke gate, CSRF/refresh/logout checks, U12 B/C, Flyway, final record
  python3 -I /tmp/t1-release-step.py rollback <service>   schema gate first (no override); previous inputs from /opt/parkio
  python3 -I /tmp/t1-release-step.py status

Every recreating command is the production wrapper from the release checkout with named services and --no-deps:
  cd /opt/parkio-release && PARKIO_ENV_FILE=/opt/parkio/docker/.env.azure-hosted-beta ./scripts/parkio-prod-compose.sh ...
Nothing else is recreated; no whole-project up, no --force-recreate, no --allow-dirty or guard bypass, no offset change, no
restore. Smoke steps need the session prepared once (plan section 12): `read -r -s PARKIO_REAL_USER_PASSWORD; export ...` and
`./scripts/smoke-hosted-beta.sh --check-credentials`; T1 only checks that the variables are present and never prints them.
Records (plan section 10) go to ~/parkio-release-20261009: planned/planned-<stamp>.json, journal.tsv (append-only),
state.json (atomic), steps/<step>-<stamp>.{log,txt,json}, smoke-<step>-<stamp>.txt; the final deployed-manifest.json goes to
/var/lib/parkio/azure-hosted-beta (created by the owner first) and R/final/current.json only after every E gate passes.
A failed check stops with state "partial"; the step can be re-run after the cause is fixed; later steps refuse to start.
"""
import hashlib
from http import cookiejar
import io
import json
import os
import re
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

PIN = "f888a81b113c0c486ea0949067779443857e644d"
OLD_PIN = "22f9699038cdae15ffff5dd7433c4a64aed0a951"
ROLLBACK_TAG = "rollback-pre-22f96990"
REL = os.environ.get("T1_RELEASE", "/opt/parkio-release")
LIVE = os.environ.get("T1_LIVE", "/opt/parkio")
ENV_FILE = os.environ.get("T1_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
PROJECT = os.environ.get("T1_PROJECT", "parkio")
OUT = os.environ.get("T1_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009"))
FINAL_DIR = os.environ.get("T1_FINAL_DIR", "/var/lib/parkio/azure-hosted-beta")
PG_PREFIX = os.environ.get("T1_PG_PREFIX", "parkio-postgres-")
KAFKA = os.environ.get("T1_KAFKA", "parkio-kafka")
API = os.environ.get("T1_API", "https://api.parkio.dev")
APP = os.environ.get("T1_APP", "https://app.parkio.dev")
SITE = os.environ.get("T1_SITE", "https://parkio.dev")
WRAPPER = "./scripts/parkio-prod-compose.sh"
WAITLIST_OVERLAY = "docker/docker-compose.waitlist-ops-inbox.yml"
TRIVY = os.environ.get("T1_TRIVY", "aquasec/trivy@sha256:a8ca29078522f30393bdb34225e4c0994d38f37083be81a42da3a2a7e1488e9e")
MIN_FREE = 12 * 1024 ** 3
BACKUP_MAX_AGE = 2 * 3600
BUILT = ["user-service", "gamification-service", "notification-service", "moderation-service", "ai-validation-service",
         "analytics-service"]
PINNED = ["gateway-service", "auth-service", "media-service", "parking-service", "web"]
INFRA = ["alertmanager", "prometheus", "caddy"]
SERVICES = INFRA + PINNED + BUILT
ORDER = ["plan", "D2a", "D2b", "D2c", "nr-pause", "D3", "D4", "D5", "D6a", "D6b", "nr-resume", "D7", "final"]
STEP_SERVICES = {"D2a": ["alertmanager"], "D2b": ["prometheus"], "D2c": ["caddy"], "D3": ["gateway-service"], "D4": ["web"],
                 "D5": ["auth-service"], "D6a": ["media-service"], "D6b": ["parking-service"], "D7": BUILT}
EXTRA_FILES = {"gateway-service": ["-f", WAITLIST_OVERLAY]}
SMOKE_STEPS = {"D3", "D5", "D6a", "D6b", "D7"}
NR_STEPS = {"D3", "D5", "D6b"}
DB_OF = {"gateway-service": "gateway", "auth-service": "auth", "media-service": "media", "parking-service": "parking",
         "user-service": "user", "gamification-service": "gamification", "notification-service": "notification",
         "moderation-service": "moderation", "ai-validation-service": "ai-validation", "analytics-service": "analytics"}
HEALTH_TIMEOUT = {"D7": 420, "D3": 240, "D5": 300}
NR_UNITS = ["parkio-nr-log-source.service", "parkio-nr-log-continuous.service", "parkio-nr-log-continuous-guard.timer"]
NR_GUARD_TIMER, NR_GUARD_SERVICE = "parkio-nr-log-continuous-guard.timer", "parkio-nr-log-continuous-guard.service"
NR_CHECK = ["sudo", "env", "PARKIO_NR_COMPOSE_PROJECT=parkio",
            "PARKIO_NR_SOURCE_ROOT=/var/lib/parkio-nr-log-continuous/source/logs",
            "PARKIO_NR_SOURCE_STATE_ROOT=/var/lib/parkio-nr-log-continuous/source/state",
            "/opt/parkio-nr-log-continuous/current/scripts/newrelic_log_pilot/resolve_production_sources.sh",
            "--check-live-helper"]
U12_GROUP, U12_TOPIC, DLT_USER = "parkio.user", "parkio.gamification.score", "parkio.dlt.user"
KAFKA_EXEC = ["docker", "exec", "-e", "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m"]
GO_MAGIC = b"\xff Go buildinf:"
URL = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://[^\s'\"]+")
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
STAMP = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
REPORT, GATES = [], []


# ---------------------------------------------------------------- output and records
def now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def say(line=""):
    line = EMAIL.sub("<email>", URL.sub(lambda m: m.group(0) if m.group(0).startswith((API, APP, SITE)) else "<url>", line))
    print(line, flush=True)
    REPORT.append(line)


def gate(name, ok, detail=""):
    GATES.append((name, ok))
    say(f"  {'PASS' if ok else 'FAIL'} {name}{': ' + detail if detail else ''}")
    return ok


def private_write(path, text):
    os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(text)
        fh.flush()
        os.fsync(fh.fileno())
    os.replace(tmp, path)


def load_state():
    try:
        return json.load(open(os.path.join(OUT, "state.json"), encoding="utf-8"))
    except (OSError, ValueError):
        return {"pin": PIN, "status": "not-started", "steps": {}, "services": {}, "flyway": {}}


def save_state(state):
    state["updatedAt"] = now()
    private_write(os.path.join(OUT, "state.json"), json.dumps(state, indent=2, sort_keys=True) + "\n")


def journal(step, service, event, prev="", new="", image="", chash="", health="", flyway="", smoke="", log_sha=""):
    path = os.path.join(OUT, "journal.tsv")
    os.makedirs(OUT, mode=0o700, exist_ok=True)
    new_file = not os.path.exists(path)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    with os.fdopen(fd, "a", encoding="utf-8") as fh:
        if new_file:
            fh.write("utc\tstep\tservice\tevent\tprevious_container\tnew_container\timage_id\tconfig_hash\thealth\t"
                     "flyway_applied\tsmoke\tlog_sha256\n")
        fh.write("\t".join(str(x) for x in (now(), step, service, event, prev[:12], new[:12], short(image), chash[:12],
                                             health, flyway, smoke, log_sha[:16])) + "\n")


def save_report(name, record):
    record.update(stamp=STAMP, gates={n: ok for n, ok in GATES})
    base = os.path.join(OUT, "steps", f"{name}-{STAMP}")
    private_write(base + ".txt", "\n".join(REPORT) + "\n")
    private_write(base + ".json", json.dumps(record, indent=2, sort_keys=True) + "\n")
    print(f"saved: {base}.txt / .json")


def finish(name, record, state, ok_status="ok"):
    failed = [n for n, ok in GATES if not ok]
    record["result"] = "PASS" if not failed else "FAIL"
    step = state["steps"].setdefault(name, {})
    step.update(status=ok_status if not failed else "fail", at=now(), stamp=STAMP, failed=failed)
    if failed and state.get("status") not in ("complete",):
        state["status"] = "partial"
    save_state(state)
    say("")
    say(f"gates passed {len(GATES) - len(failed)}/{len(GATES)}" + (f"; failed: {', '.join(failed)}" if failed else ""))
    say(f"T1 {name}: {'OK' if not failed else 'FAILED - state partial; fix the cause, then re-run this step or roll back'}")
    save_report(name, record)
    sys.exit(0 if not failed else 1)


def stop(name, record, state, message):
    say(f"STOP: {message}")
    GATES.append((message, False))
    finish(name, record, state)


# ---------------------------------------------------------------- host helpers
def run(cmd, cwd=None, env=None, timeout=120, binary=False, stdin=None):
    try:
        p = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=not binary, timeout=timeout, input=stdin)
    except FileNotFoundError:
        return 127, (b"" if binary else ""), "not found"
    except subprocess.TimeoutExpired:
        return 124, (b"" if binary else ""), "timed out"
    err = p.stderr.decode("utf-8", "replace") if binary else p.stderr
    return p.returncode, p.stdout, err


def first_error(err):
    return EMAIL.sub("<email>", URL.sub("<url>", next((ln.strip() for ln in (err or "").splitlines() if ln.strip()), "")))[:200]


def short(image_id):
    return (image_id or "").replace("sha256:", "")[:12]


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def git(repo, *args):
    rc, out, _ = run(["git", "--no-optional-locks", "-C", repo] + list(args))
    return rc, out.strip()


def free_bytes(path):
    try:
        st = os.statvfs(path)
        return st.f_bavail * st.f_frsize
    except OSError:
        return None


def env_value(key):
    value = None
    try:
        with open(ENV_FILE, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if line.startswith(key + "="):
                    value = line.rstrip("\n").split("=", 1)[1].strip().strip("'\"")
    except OSError:
        return None
    return value


def wrapper_env():
    return dict(os.environ, PARKIO_ENV_FILE=ENV_FILE)


def compose(args, cwd=REL, timeout=600):
    return run([WRAPPER] + args, cwd=cwd, env=wrapper_env(), timeout=timeout)


def model_hash(service, cwd=REL):
    rc, out, err = compose(EXTRA_FILES.get(service, []) + ["config", "--hash", service], cwd=cwd, timeout=180)
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[0] == service:
            return parts[1]
    return None


def model(cwd=REL, extra=()):
    rc, out, err = compose(list(extra) + ["config", "--format", "json"], cwd=cwd, timeout=180)
    if rc != 0:
        return None
    return json.loads(out)


def container(service):
    rc, out, _ = run(["docker", "ps", "-a", "--no-trunc", "--filter", f"label=com.docker.compose.project={PROJECT}",
                      "--filter", f"label=com.docker.compose.service={service}", "--format", "{{.ID}}"])
    ids = out.split()
    if len(ids) != 1:
        return None
    rc, info, _ = run(["docker", "inspect", ids[0]])
    data = json.loads(info)[0] if rc == 0 and info.strip().startswith("[") else {}
    state = data.get("State") or {}
    labels = (data.get("Config") or {}).get("Labels") or {}
    return {"id": data.get("Id", ids[0]), "image": data.get("Image"), "status": state.get("Status"),
            "health": (state.get("Health") or {}).get("Status"), "started": state.get("StartedAt"),
            "hash": labels.get("com.docker.compose.config-hash"), "env": (data.get("Config") or {}).get("Env") or [],
            "mounts": [m.get("Destination") for m in data.get("Mounts") or []],
            "groupAdd": (data.get("HostConfig") or {}).get("GroupAdd") or [], "labels": labels}


def recorded_inputs(c, svc):
    """The Compose inputs recorded on the running container (paths only), as D0.2.2/D0.2.3 reproduced them; a temporary
    web binding file that no longer exists is rebuilt from the running image (pull_policy never)."""
    lab = c.get("labels") or {}
    files = [f for f in (lab.get("com.docker.compose.project.config_files") or "").split(",") if f]
    env = lab.get("com.docker.compose.project.environment_file") or ""
    inputs = {"workingDir": lab.get("com.docker.compose.project.working_dir") or "", "files": files,
              "envFile": env if env and "," not in env and os.path.isfile(env) else ENV_FILE}
    missing = [f for f in files if not os.path.isfile(f)]
    if svc == "web" and len(missing) == 1 and os.path.basename(missing[0]) == "web-binding.yml":
        rebuilt = os.path.join(OUT, "planned", "web-binding-rebuilt.yml")
        private_write(rebuilt, json.dumps({"services": {"web": {"image": c["image"], "pull_policy": "never"}}}) + "\n")
        inputs["files"] = [rebuilt if f == missing[0] else f for f in files]
        inputs["rebuiltWebBinding"] = True
    elif missing:
        inputs["missing"] = [os.path.basename(f) for f in missing]
    return inputs


def raw_compose(inputs, args, timeout=600):
    cmd = ["docker", "compose", "--env-file", inputs["envFile"]]
    if inputs.get("workingDir"):
        cmd += ["--project-directory", inputs["workingDir"]]
    for f in inputs["files"]:
        cmd += ["-f", f]
    return run(cmd + list(args), timeout=timeout)


def recorded_hash(inputs, svc):
    if inputs.get("missing") or not inputs.get("files"):
        return None
    rc, out, err = raw_compose(inputs, ["config", "--hash", svc], timeout=180)
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[0] == svc:
            return parts[1]
    return None


def image_id(ref):
    rc, out, _ = run(["docker", "image", "inspect", "--format", "{{.Id}}", ref])
    return out.strip() if rc == 0 else None


def wait_healthy(service, timeout):
    deadline = time.time() + timeout
    c = None
    while time.time() < deadline:
        c = container(service)
        if c and c["status"] == "running" and c["health"] in ("healthy", None):
            return c
        if c and c["status"] in ("exited", "dead"):
            return c
        time.sleep(5)
    return c


def psql(db, sql, timeout=90):
    script = ("SET default_transaction_read_only = on;\nSET statement_timeout = '60s';\nSET lock_timeout = '2s';\n" + sql + "\n")
    cmd = ["docker", "exec", "-i", PG_PREFIX + db, "sh", "-c",
           'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F "|" -f -']
    rc, out, err = run(cmd, stdin=script, timeout=timeout)
    return rc, [ln.split("|") for ln in out.splitlines() if ln.strip()], err


def flyway(db):
    """{'applied': [script], 'failed': n, 'baselineVersion': v or None} read from flyway_schema_history."""
    rc, rows, err = psql(db, "select coalesce(version, ''), coalesce(script, ''), type, success from flyway_schema_history "
                              "order by installed_rank;")
    if rc != 0:
        return None
    applied, failed, baseline = [], 0, None
    for r in rows:
        if len(r) < 4:
            continue
        version, script, kind, success = r[0], r[1], r[2], r[3] == "t"
        if kind == "BASELINE":
            baseline = version
            continue
        if not success:
            failed += 1
        elif kind == "SQL":
            applied.append(script)
    return {"applied": applied, "failed": failed, "baselineVersion": baseline}


def version_of(script):
    m = re.match(r"^V(\d+(?:[._]\d+)*)__", script)
    return tuple(int(x) for x in re.split(r"[._]", m.group(1))) if m else None


def release_scripts(service):
    d = os.path.join(REL, "services", service, "src", "main", "resources", "db", "migration")
    try:
        return sorted((f for f in os.listdir(d) if re.match(r"^V\d.*__.*\.sql$", f)), key=version_of)
    except OSError:
        return []


def expected_scripts(service, fly):
    base = fly.get("baselineVersion")
    scripts = release_scripts(service)
    if base:
        bv = tuple(int(x) for x in re.split(r"[._]", base))
        scripts = [s for s in scripts if version_of(s) > bv]
    return scripts


def kafka(*args):
    return run(KAFKA_EXEC + [KAFKA, "timeout", "60"] + list(args), timeout=90)


def end_offsets(topic, when="-1"):
    rc, out, err = kafka("kafka-get-offsets", "--bootstrap-server", "localhost:9092", "--topic", topic, "--time", when)
    if rc != 0:
        return None
    result = {}
    for line in out.splitlines():
        m = re.match(r"^(\S+):(\d+):(-?\d+)$", line.strip())
        if m and m.group(1) == topic:
            result[int(m.group(2))] = int(m.group(3))
    return result


def committed(group, topic):
    rc, out, err = kafka("kafka-consumer-groups", "--bootstrap-server", "localhost:9092", "--describe", "--group", group)
    if rc != 0 and not out.strip():
        return None
    result, header = {}, None
    for line in out.splitlines():
        cols = line.split()
        if "GROUP" in cols and "PARTITION" in cols:
            header = cols
            continue
        if header and len(cols) >= len(header) - 1 and len(cols) >= 4:
            row = dict(zip(header, cols))
            if row.get("TOPIC") == topic and row.get("CURRENT-OFFSET", "-").isdigit():
                result[int(row["PARTITION"])] = int(row["CURRENT-OFFSET"])
    return result


def http(method, url, headers=None, body=None, opener=None, timeout=30):
    req = urllib.request.Request(url, data=body, method=method, headers=dict({"User-Agent": "parkio-release-t1/1"},
                                                                                  **(headers or {})))
    try:
        with (opener or urllib.request.build_opener()).open(req, timeout=timeout) as r:
            return r.status, {k.lower(): v for k, v in r.headers.items()}, r.read(1_000_000)
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read(200_000)
    except (urllib.error.URLError, OSError):
        return None, {}, b""


def nr_state():
    states = {}
    for unit in NR_UNITS + [NR_GUARD_SERVICE]:
        rc, out, _ = run(["systemctl", "is-active", unit])
        states[unit] = out.strip() or "unknown"
    return states


def nr_attached():
    rc, out, err = run(NR_CHECK, timeout=120)
    # resolve_production_sources.sh --check-live-helper prints its PASS/FAIL lines on stderr and leaves stdout empty
    # (2026-10-12: D3 read stdout only and counted 0/0 while the check had passed); count both streams.
    lines = [ln for ln in (out + "\n" + err).splitlines() if "live-helper=" in ln]
    attached = [ln for ln in lines if "live-helper=attached" in ln]
    return rc, len(attached), len(lines)


# ---------------------------------------------------------------- pebble / scan for the D7 outputs
def uvarint_string(data):
    n = shift = 0
    for idx, b in enumerate(data[:10]):
        n |= (b & 0x7F) << shift
        if b < 0x80:
            start = idx + 1
            return (data[start:start + n], data[start + n:]) if start + n <= len(data) else (None, b"")
        shift += 7
    return None, b""


def go_version(binary):
    i = binary.find(GO_MAGIC)
    while i != -1:
        h = binary[i:i + 32]
        if len(h) == 32 and h[14] in (4, 8) and h[15] & 0x2:
            v, _ = uvarint_string(binary[i + 32:])
            if v and v.startswith(b"go"):
                return v.decode("ascii", "replace")
        i = binary.find(GO_MAGIC, i + 1)
    return None


def pebble_in(ref, role):
    """'absent' / 'present goX' / 'error ...' for /usr/bin/pebble in the image's final filesystem (never executed)."""
    name = f"t1-probe-{role}-{STAMP.lower()}"
    rc, cid, err = run(["docker", "create", "--pull", "never", "--network", "none", "--name", name, "--label",
                        "parkio.release.probe=t1", "--entrypoint", "/t1-probe-noop", ref])
    cid = cid.strip()
    if rc != 0 or not cid:
        return f"error: docker create failed: {first_error(err)}"
    try:
        rc, data, err = run(["docker", "cp", "-L", f"{cid}:/usr/bin/pebble", "-"], binary=True, timeout=300)
        if rc != 0:
            text = (err or "").lower()
            if "could not find the file" in text or "no such container:path" in text or "no such file or directory" in text:
                return "absent"
            return f"error: {first_error(err)}"
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as tar:
            m = tar.next()
            body = tar.extractfile(m).read() if m is not None and m.isfile() else b""
        return f"present {go_version(body) or 'unknown Go'}"
    finally:
        run(["docker", "rm", "-v", cid])


def trivy_scan(ref, cache):
    """Security CI's container policy: CRITICAL fixed (all packages) and HIGH/CRITICAL fixed (libraries), with the release's
    ignore file. Returns (critical_ok, library_ok, targets)."""
    try:
        sock_gid = os.stat("/var/run/docker.sock").st_gid
    except OSError:
        sock_gid = os.getgid()
    base = ["docker", "run", "--rm", "--network", "host", "--user", f"{os.getuid()}:{os.getgid()}", "--group-add", str(sock_gid),
            "-e", "HOME=/tmp", "-v", "/var/run/docker.sock:/var/run/docker.sock:ro",
            "-v", f"{cache}:/root/.cache/trivy", "-v", f"{os.path.join(REL, '.trivyignore.yaml')}:/work/.trivyignore.yaml:ro",
            "-w", "/work", TRIVY, "image", "--scanners", "vuln", "--ignorefile", ".trivyignore.yaml",
            "--cache-dir", "/root/.cache/trivy", "--quiet"]
    rc_c, out_c, err_c = run(base + ["--severity", "CRITICAL", "--ignore-unfixed", "--exit-code", "1", "--format", "json",
                                     ref], timeout=3600)
    rc_l, out_l, err_l = run(base + ["--pkg-types", "library", "--severity", "HIGH,CRITICAL", "--ignore-unfixed",
                                     "--exit-code", "1", "--format", "json", ref], timeout=3600)
    targets = []
    try:
        targets = [f"{t.get('Target')} ({len(t.get('Vulnerabilities') or [])})" for t in json.loads(out_l).get("Results") or []]
    except ValueError:
        pass
    return (rc_c == 0, rc_l == 0, targets, first_error(err_c if rc_c not in (0, 1) else err_l if rc_l not in (0, 1) else ""))


# ---------------------------------------------------------------- common pre-checks
def precheck(name, state, record, services):
    say(f"T1 {name} | stamp {STAMP} | release {REL} @ {PIN[:12]} | project {PROJECT}")
    say("")
    say("Pre-checks")
    if "__" in PIN:
        stop(name, record, state, "this copy has an unfilled PIN")
    rc, head = git(REL, "rev-parse", "HEAD")
    rc2, porcelain = git(REL, "status", "--porcelain")
    if head != PIN or porcelain:
        stop(name, record, state, f"release checkout is not clean at {PIN[:12]} (HEAD {head[:12]}, {len(porcelain.splitlines())} changes)")
    gate("release checkout clean at the PIN", True)
    idx = ORDER.index(name)
    steps = state.get("steps", {})
    prev = ORDER[idx - 1]
    if (steps.get(prev) or {}).get("status") != "ok":
        stop(name, record, state, f"the previous step {prev} is not ok ({(steps.get(prev) or {}).get('status', 'not run')})")
    later = [s for s in ORDER[idx + 1:] if (steps.get(s) or {}).get("status") in ("ok", "running")]
    if later:
        stop(name, record, state, f"later step(s) already ran: {', '.join(later)}")
    gate(f"order: {prev} ok", True)
    n = free_bytes("/")
    gate("free space on / >= 12 GiB", n is not None and n >= MIN_FREE, f"{(n or 0) / 1024 ** 3:.1f} GiB")
    rc, out, _ = run(["pgrep", "-f", "pg_dump|run-production-backup|backup-hosted-beta"])
    gate("no backup or pg_dump process on the host", not out.strip(), f"{len(out.split())} process(es)" if out.strip() else "")
    if services:
        planned = load_plan()
        missing = [s for s in services if s not in (planned.get("previous") or {})]
        gate("each service's previous container is in the planned record", not missing, ", ".join(missing))
    return load_plan() if services else None


def load_plan():
    d = os.path.join(OUT, "planned")
    try:
        files = sorted(f for f in os.listdir(d) if f.startswith("planned-") and f.endswith(".json"))
    except OSError:
        return {}
    return json.load(open(os.path.join(d, files[-1]), encoding="utf-8")) if files else {}


def session_ready():
    names = ["PARKIO_REAL_USER_PASSWORD", "PARKIO_GATEWAY_URL", "PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED", "PARKIO_DEPLOYMENT_PROFILE",
             "PARKIO_ENV_FILE"]
    missing = [n for n in names if not os.environ.get(n)]
    return missing


def run_smoke(step):
    log = os.path.join(OUT, f"smoke-{step}-{STAMP}.txt")
    rc, out, err = run(["./scripts/smoke-hosted-beta.sh"], cwd=REL, env=dict(os.environ, PARKIO_ENV_FILE=ENV_FILE), timeout=900)
    text = EMAIL.sub("<email>", out + err)
    private_write(log, text)
    m = re.search(r"=== smoke summary: pass=(\d+) fail=(\d+) ===", text)
    passed, failed = (int(m.group(1)), int(m.group(2))) if m else (0, -1)
    return rc, passed, failed, sha256_file(log), log


# ---------------------------------------------------------------- plan (P6)
def cmd_plan():
    name, state, record = "plan", load_state(), {"tool": "t1-release-step", "command": "plan", "pin": PIN}
    say(f"T1 plan | stamp {STAMP} | release {REL} @ {PIN[:12]} | project {PROJECT}")
    if "__" in PIN:
        stop(name, record, state, "this copy has an unfilled PIN")
    rc, head = git(REL, "rev-parse", "HEAD")
    rc2, porcelain = git(REL, "status", "--porcelain")
    if head != PIN or porcelain:
        stop(name, record, state, f"release checkout is not clean at {PIN[:12]}")
    done = [s for s in ORDER[1:] if (state.get("steps", {}).get(s) or {}).get("status") in ("ok", "running")]
    if done:
        stop(name, record, state, f"the deployment already started ({', '.join(done)}); the plan is not rewritten")
    gate("release checkout clean at the PIN", True)
    say("")
    say("A. Baseline Flyway per database (read from flyway_schema_history)")
    baseline = {}
    for svc, db in DB_OF.items():
        fly = flyway(db)
        if fly is None:
            gate(f"{db} Flyway readable", False)
            continue
        baseline[db] = fly
        target = expected_scripts(svc, fly)
        pending = [s for s in target if s not in fly["applied"]]
        extra = [s for s in fly["applied"] if s not in release_scripts(svc)]
        gate(f"{db}: {len(fly['applied'])} applied, failed {fly['failed']}", fly["failed"] == 0 and not extra,
             f"pending with this release {', '.join(p.split('__')[0] for p in pending) or 'none'}"
             + (f"; NOT IN THE RELEASE: {', '.join(extra)}" if extra else ""))
    say("")
    say("B. Previous containers and rollback method (live wrapper from /opt/parkio reproduces the running config hash?)")
    previous = {}
    for svc in SERVICES:
        c = container(svc)
        if not c:
            gate(f"{svc} container", False, "not exactly one container")
            continue
        live_hash = model_hash(svc, cwd=LIVE)
        target_hash = model_hash(svc, cwd=REL)
        inputs = recorded_inputs(c, svc)
        rec_hash = None if live_hash == c["hash"] else recorded_hash(inputs, svc)
        method = ("live wrapper" if live_hash == c["hash"] else "recorded inputs" if rec_hash == c["hash"]
                  else "decision needed (neither the live wrapper nor the recorded inputs reproduce its config)")
        previous[svc] = {"id": c["id"], "image": c["image"], "hash": c["hash"], "status": c["status"], "health": c["health"],
                         "liveWrapperHash": live_hash, "recordedHash": rec_hash, "inputs": inputs, "targetHash": target_hash,
                         "rollback": method}
        if svc in BUILT:
            previous[svc]["rollbackTag"] = f"{PROJECT}-{svc}:{ROLLBACK_TAG}"
            previous[svc]["rollbackTagPresent"] = image_id(previous[svc]["rollbackTag"]) == c["image"]
        say(f"  {svc}: {c['status']}/{c['health'] or '-'} image {short(c['image'])} hash {c['hash'][:12] if c['hash'] else '-'};"
            f" rollback: {method}" + (f"; rollback tag {'on the running image' if previous[svc].get('rollbackTagPresent') else 'MISSING'}"
                                      if svc in BUILT else "") + ("; already at the target" if target_hash == c["hash"] else ""))
    gate("all fourteen previous containers recorded", len(previous) == len(SERVICES), f"{len(previous)}/{len(SERVICES)}")
    gate("rollback tags of the six on their running images", all(previous.get(s, {}).get("rollbackTagPresent") for s in BUILT))
    say("")
    say("C. Targets: pinned images present locally (by the release model) and the six host-built services")
    m = model()
    targets = {}
    for svc in PINNED:
        ref = ((m or {}).get("services") or {}).get(svc, {}).get("image")
        iid = image_id(ref) if ref else None
        targets[svc] = {"ref": ref, "imageId": iid}
        gate(f"{svc} target image present", bool(iid), f"{(ref or '?').rsplit('@', 1)[-1][:19]} -> {short(iid)}")
    say("")
    say("D. U12-A, V41 and NR refresh (read-only)")
    rc, rows, err = psql("gamification", "select count(*) filter (where dead_lettered and not acknowledged_deadletter), "
                                         "count(*) filter (where not published and not dead_lettered) from outbox_events;")
    u12 = {"deadLettered": rows[0][0] if rows else "?", "pending": rows[0][1] if rows else "?"}
    dlt = end_offsets(DLT_USER) or {}
    dlt_start = end_offsets(DLT_USER, "-2") or {}
    u12["dltUserRetained"] = sum(max(dlt[p] - dlt_start.get(p, 0), 0) for p in dlt)
    ends, starts, com = end_offsets(U12_TOPIC) or {}, end_offsets(U12_TOPIC, "-2") or {}, committed(U12_GROUP, U12_TOPIC) or {}
    u12["userRestartReads"] = sum(max(ends[p] - max(com.get(p, starts.get(p, 0)), starts.get(p, 0)), 0) for p in ends)
    gate("U12-A: dead-lettered 0, DLT 0, user-service would re-read 0 score records", u12["deadLettered"] == "0" and
         u12["dltUserRetained"] == 0 and u12["userRestartReads"] == 0, json.dumps(u12))
    rc, rows, err = psql("parking", "select count(*) from parking_sessions;")
    v41_rows = rows[0][0] if rows else "?"
    gate("V41: parking_sessions has 0 rows", v41_rows == "0", f"{v41_rows} rows")
    nr = nr_state()
    say("  NR units: " + "; ".join(f"{u} {s}" for u, s in nr.items()))
    record.update(status="planned", baseline=baseline, previous=previous, targets=targets, u12a=u12, v41Rows=v41_rows, nr=nr,
                  composeFiles=compose_files(), plannedAt=now())
    path = os.path.join(OUT, "planned", f"planned-{STAMP}.json")
    failed = [n for n, ok in GATES if not ok]
    if not failed:
        private_write(path, json.dumps(record, indent=2, sort_keys=True) + "\n")
        say(f"planned record: {path}")
        state.update(pin=PIN, status="planned", flyway={db: b["applied"] for db, b in baseline.items()})
    finish(name, record, state)


def compose_files():
    try:
        files = [ln.strip() for ln in open(os.path.join(REL, "docker", "compose.production.files"), encoding="utf-8")
                 if ln.strip() and not ln.strip().startswith("#")]
    except OSError:
        return []
    return files + ["docker/docker-compose.civo-alertmanager.yml"]


# ---------------------------------------------------------------- deployment steps
def post_service(step, svc, prev, expected_image, record, state, before_id=None):
    timeout = HEALTH_TIMEOUT.get(step, 240)
    c = wait_healthy(svc, timeout)
    ok_health = bool(c) and c["status"] == "running" and c["health"] in ("healthy", None)
    gate(f"{svc} running and healthy within {timeout} s", ok_health,
         f"{c['status']}/{c['health'] or 'no healthcheck'}" if c else "no container")
    if not c:
        return False
    recreated = c["id"] != (before_id or prev["id"])
    target = model_hash(svc)
    gate(f"{svc} config hash = the release model's", c["hash"] == target and target is not None,
         f"{(c['hash'] or '-')[:12]}" + ("" if recreated else " (not recreated: already at the target)"))
    gate(f"{svc} image = {short(expected_image)}", c["image"] == expected_image, short(c["image"]))
    fly_note = ""
    if svc in DB_OF:
        fly = flyway(DB_OF[svc])
        base = (load_plan().get("baseline") or {}).get(DB_OF[svc]) or {}
        if fly is None:
            gate(f"{svc} Flyway readable", False)
        else:
            expected = expected_scripts(svc, fly)
            missing = [s for s in expected if s not in fly["applied"]]
            extra = [s for s in fly["applied"] if s not in release_scripts(svc)]
            new = [s for s in fly["applied"] if s not in (base.get("applied") or [])]
            fly_note = f"{len(fly['applied'])} (+{len(new)})"
            gate(f"{svc} Flyway = the release's scripts, 0 failed", not missing and not extra and fly["failed"] == 0,
                 f"applied now: {', '.join(s.split('__')[0] for s in new) or 'none'}"
                 + (f"; MISSING {', '.join(missing)}" if missing else "") + (f"; EXTRA {', '.join(extra)}" if extra else "")
                 + (f"; failed {fly['failed']}" if fly["failed"] else ""))
            state.setdefault("flyway", {})[DB_OF[svc]] = fly["applied"]
    state.setdefault("services", {})[svc] = {"release": PIN[:12], "container": c["id"], "image": c["image"], "hash": c["hash"],
                                             "previousContainer": prev["id"], "at": now()}
    journal(step, svc, "ok" if ok_health else "fail", prev["id"], c["id"], c["image"], c["hash"] or "", c["health"] or "",
            fly_note)
    record.setdefault("services", {})[svc] = state["services"][svc]
    return ok_health


def extra_checks(step, record):
    if step == "D2a":
        rc, out, err = run(["docker", "exec", "parkio-alertmanager", "amtool", "config", "routes", "show",
                            "--alertmanager.url=http://localhost:9093"], timeout=60)
        lines = [ln for ln in out.splitlines() if ln.strip()]
        wd = next((i for i, ln in enumerate(lines) if 'alertname="Watchdog"' in ln and "receiver: null" in ln), None)
        sev = [i for i, ln in enumerate(lines) if "severity=" in ln]
        gate("Watchdog -> null ahead of the severity routes", wd is not None and (not sev or wd < min(sev)),
             f"{len(lines)} route lines")
        receivers = sorted({m.group(1) for ln in lines for m in [re.search(r"receiver: (\S+)", ln)] if m})
        gate("live receivers present (critical, warning)", {"critical", "warning"} <= set(receivers), ", ".join(receivers))
    elif step == "D2b":
        rc, out, err = run(["docker", "exec", "parkio-prometheus", "wget", "-qO-", "http://localhost:9090/api/v1/rules"],
                           timeout=60)
        try:
            groups = json.loads(out)["data"]["groups"]
        except (ValueError, KeyError, TypeError):
            groups = []
        names = {r.get("name") for g in groups for r in g.get("rules") or []}
        bad = [r.get("name") for g in groups for r in g.get("rules") or [] if r.get("health") not in ("ok", "unknown", None)]
        want = {"Watchdog", "AlertmanagerNotificationsFailing", "PrometheusNotificationsFailing"}
        gate("rules loaded (Watchdog and the two notification-failure rules), all healthy",
             want <= names and not bad, f"{len(groups)} groups, {len(names)} rules" + (f"; unhealthy {bad[:5]}" if bad else ""))
        ev = os.path.join(OUT, "d2-delivery-rules")
        os.makedirs(ev, mode=0o700, exist_ok=True)
        rc, out, err = run(["scripts/alerting-live-backup-stale-acceptance.sh", "delivery-rules"], cwd=REL,
                           env=dict(wrapper_env(), PARKIO_LIVE_EVIDENCE_DIR=ev), timeout=300)
        private_write(os.path.join(ev, f"delivery-rules-{STAMP}.txt"), out + err)
        gate("delivery-rules acceptance", rc == 0, f"exit {rc}")
    elif step == "D2c":
        st, h, _ = http("GET", API + "/actuator/health")
        gate("api /actuator/health 200", st == 200, str(st))
        st, h, _ = http("GET", API + "/actuator/prometheus")
        gate("api /actuator/prometheus 404 at the edge", st == 404, str(st))
        st, h, _ = http("GET", APP + "/")
        gate("app 200 with a Content-Security-Policy", st == 200 and bool(h.get("content-security-policy")), str(st))
    elif step == "D3":
        c = container("gateway-service") or {}
        keys = {e.split("=", 1)[0] for e in c.get("env", [])}
        gate("waitlist overlay applied: export-dir key, inbox bind, group_add",
             "PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR" in keys and "/var/lib/parkio/waitlist-ops-inbox" in c.get("mounts", [])
             and bool(c.get("groupAdd")), f"group_add {','.join(c.get('groupAdd', [])) or 'none'}")
    elif step == "D4":
        st, h, _ = http("GET", APP + "/")
        gate("app 200", st == 200, str(st))
    elif step == "D6b":
        rc, rows, err = psql("parking", "select data_type from information_schema.columns where table_schema = 'public' and "
                                        "table_name = 'parking_sessions' and column_name = 'last_confirmed_at';\n"
                                        "select count(*) from parking_sessions;")
        coltype = rows[0][0] if rows else "?"
        count = rows[1][0] if len(rows) > 1 else "?"
        gate("parking last_confirmed_at is timestamptz", coltype == "timestamp with time zone", coltype)
        gate("parking_sessions rows unchanged (0)", count == record.get("v41RowsBefore") == "0", f"{count} rows")


def step_nr_check(step):
    states = nr_state()
    rc, attached, total = nr_attached()
    ok = states.get("parkio-nr-log-source.service") == "active" and states.get("parkio-nr-log-continuous.service") == "active"
    gate("NR source and transport active; guard timer paused", ok and states.get(NR_GUARD_TIMER) != "active",
         "; ".join(f"{u.replace('parkio-nr-log-', '')} {s}" for u, s in states.items()))
    gate("NR three sources attached (live helper)", rc == 0 and attached == 3, f"{attached}/{total} attached (exit {rc})")


def cmd_step(step):
    state, record = load_state(), {"tool": "t1-release-step", "command": f"step {step}", "pin": PIN}
    services = STEP_SERVICES[step]
    planned = precheck(step, state, record, services)
    if step == "D2a":
        receipt = latest_receipt()
        age = time.time() - receipt["epoch"] if receipt else None
        gate("verified P5 backup within 2 hours", bool(receipt) and receipt["result"] == "PASS" and age is not None
             and age <= BACKUP_MAX_AGE, f"stamp {receipt['stamp']} age {age / 60:.0f} min" if receipt else "no P5b receipt")
        hm = datetime.now(timezone.utc)
        gate("outside 03:00-04:30 UTC", not (3 <= hm.hour < 4 or (hm.hour == 4 and hm.minute < 30)), hm.strftime("%H:%M"))
    if step in SMOKE_STEPS:
        missing = session_ready()
        gate("smoke session prepared (variables present, values not shown)", not missing, ", ".join(missing))
    if step == "D3":
        gate("NR guard timer paused (nr-pause ok)", (state["steps"].get("nr-pause") or {}).get("status") == "ok")
    if step == "D6b":
        rc, rows, err = psql("parking", "select count(*) from parking_sessions;")
        record["v41RowsBefore"] = rows[0][0] if rows else "?"
        gate("V41 gate: parking_sessions has 0 rows before the migration", record["v41RowsBefore"] == "0",
             f"{record['v41RowsBefore']} rows (rows present -> stop for a zone decision)")
    if any(not ok for _, ok in GATES):
        stop(step, record, state, "a pre-check failed; nothing was changed")
    state["steps"][step] = {"status": "running", "at": now(), "stamp": STAMP}
    state["status"] = "in-progress"
    save_state(state)
    if step == "D7":
        return cmd_d7(state, record, planned)
    prev = {svc: planned["previous"][svc] for svc in services}
    expected = {}
    for svc in services:
        if svc in INFRA:
            expected[svc] = prev[svc]["image"]
        else:
            ref = ((model() or {}).get("services") or {}).get(svc, {}).get("image")
            expected[svc] = image_id(ref) if ref else None
            if not expected[svc]:
                stop(step, record, state, f"{svc} target image is not present locally; nothing was changed")
    args = EXTRA_FILES.get(services[0], []) + ["up", "-d", "--no-build", "--no-deps"] + services
    say("")
    say(f"Run: cd {REL} && PARKIO_ENV_FILE={ENV_FILE} {WRAPPER} {' '.join(args)}")
    for svc in services:
        journal(step, svc, "start", prev[svc]["id"], image=expected[svc], chash=prev[svc]["hash"] or "")
    before = {svc: (container(svc) or {}).get("id") for svc in services}
    rc, out, err = compose(args, timeout=900)
    log = os.path.join(OUT, "steps", f"{step}-{STAMP}.log")
    private_write(log, EMAIL.sub("<email>", out + err))
    gate("wrapper command exit 0", rc == 0, f"exit {rc}; log sha256 {sha256_file(log)[:16]}")
    say("")
    say("Post-checks")
    for svc in services:
        post_service(step, svc, prev[svc], expected[svc], record, state, before[svc])
    extra_checks(step, record)
    if step in NR_STEPS:
        step_nr_check(step)
    if step in SMOKE_STEPS:
        rc, passed, failed, sha, log = run_smoke(step)
        gate("smoke exit 0 and fail=0", rc == 0 and failed == 0, f"pass={passed} fail={failed}; log sha256 {sha[:16]}")
        journal(step, services[0], "smoke", smoke=f"{passed}/{failed}", log_sha=sha)
    finish(step, record, state)


def latest_receipt():
    d = os.path.join(OUT, "backup")
    try:
        files = sorted(f for f in os.listdir(d) if f.startswith("P5b-receipt-") and f.endswith(".json"))
    except OSError:
        return None
    if not files:
        return None
    r = json.load(open(os.path.join(d, files[-1]), encoding="utf-8"))
    try:
        r["epoch"] = datetime.strptime(r["stamp"], "%Y-%m-%dT%H-%M-%SZ").replace(tzinfo=timezone.utc).timestamp()
    except (KeyError, ValueError):
        return None
    return r


def cmd_d7(state, record, planned):
    step = "D7"
    prev = {svc: planned["previous"][svc] for svc in BUILT}
    say("")
    say("D7.1 U12 record before the six start (no offset change)")
    dlt_before = end_offsets(DLT_USER)
    score_before = end_offsets(U12_TOPIC)
    gate("Kafka offsets readable", dlt_before is not None and score_before is not None)
    record["u12"] = {"dltUserEndBefore": dlt_before, "scoreEndBefore": score_before}
    say("")
    say("D7.2 Build the six, one at a time, from the release checkout")
    created = now()
    moved = []
    for svc in BUILT:
        args = ["build", "--build-arg", f"IMAGE_VERSION={PIN[:8]}", "--build-arg", f"IMAGE_REVISION={PIN}",
                "--build-arg", f"IMAGE_CREATED={created}", svc]
        say(f"  Run: {WRAPPER} {' '.join(args)}")
        rc, out, err = compose(args, timeout=3600)
        log = os.path.join(OUT, "steps", f"D7-build-{svc}-{STAMP}.log")
        private_write(log, out + err)
        moved.append(svc)
        if not gate(f"{svc} build", rc == 0, f"exit {rc}; log sha256 {sha256_file(log)[:16]}"):
            return d7_abort(state, record, moved, "build failed")
    say("")
    say("D7.3 The six final images, before anything starts")
    built = {}
    for svc in BUILT:
        local = f"{PROJECT}-{svc}:latest"
        iid = image_id(local)
        rc, labels, _ = run(["docker", "image", "inspect", "--format",
                             '{{index .Config.Labels "org.opencontainers.image.revision"}}', local])
        tag = f"parkio/{svc}:sha-{PIN[:8]}"
        run(["docker", "tag", local, tag])
        built[svc] = iid
        gate(f"{svc} image {short(iid)}: OCI revision = PIN, tagged {tag}", labels.strip() == PIN and image_id(tag) == iid,
             labels.strip()[:12])
        peb = pebble_in(local, svc)
        gate(f"{svc} final filesystem without /usr/bin/pebble", peb == "absent", peb)
    say("  remaining exposure (recorded, not cleaned): the build stage ran on eclipse-temurin:21-jdk and the runtime base "
        "eclipse-temurin:21-jre carries /usr/bin/pebble in its layer; the six final images hide it with a whiteout")
    record["remainingExposure"] = {"buildStageBase": "eclipse-temurin:21-jdk (pebble present; only the jar is copied out)",
                                   "runtimeBaseLayer": "eclipse-temurin:21-jre layer keeps the bytes under a whiteout"}
    if any(not ok for _, ok in GATES):
        return d7_abort(state, record, moved, "a final image failed its checks")
    cache = os.path.join(OUT, "trivy-cache")
    os.makedirs(cache, mode=0o700, exist_ok=True)
    say(f"  Security CI container policy with {TRIVY.split('@')[0]} (pinned digest) and the release's .trivyignore.yaml")
    pulled = image_id(TRIVY) is None
    for svc in BUILT:
        crit, lib, targets, note = trivy_scan(f"parkio/{svc}:sha-{PIN[:8]}", cache)
        gate(f"{svc} scan: CRITICAL fixed gate and HIGH/CRITICAL library gate", crit and lib,
             f"critical {'pass' if crit else 'FAIL'}, library {'pass' if lib else 'FAIL'}; targets {', '.join(targets) or '-'}"
             + (f"; {note}" if note else ""))
    run(["rm", "-rf", cache])
    if pulled:
        run(["docker", "image", "rm", TRIVY])
    say(f"  scanner cache removed{'; scanner image removed (T1 pulled it)' if pulled else ''}")
    if any(not ok for _, ok in GATES):
        return d7_abort(state, record, moved, "a final image failed the scan")
    record["builtImages"] = built
    say("")
    args = ["up", "-d", "--no-build", "--no-deps"] + BUILT
    say(f"D7.4 Run: cd {REL} && PARKIO_ENV_FILE={ENV_FILE} {WRAPPER} {' '.join(args)}")
    for svc in BUILT:
        journal("D7", svc, "start", prev[svc]["id"], image=built[svc], chash=prev[svc]["hash"] or "")
    before = {svc: (container(svc) or {}).get("id") for svc in BUILT}
    rc, out, err = compose(args, timeout=1200)
    log = os.path.join(OUT, "steps", f"D7-{STAMP}.log")
    private_write(log, out + err)
    gate("wrapper up exit 0", rc == 0, f"exit {rc}; log sha256 {sha256_file(log)[:16]}")
    say("")
    say("Post-checks")
    for svc in BUILT:
        post_service("D7", svc, prev[svc], built[svc], record, state, before[svc])
    g = container("gamification-service") or {}
    record["u12"]["Tg"] = g.get("started")
    record["u12"]["scoreEndAtTg"] = end_offsets(U12_TOPIC)
    say(f"  T_g (new gamification container start) {g.get('started', '?')[:19]}")
    rc, passed, failed, sha, log = run_smoke("D7")
    gate("smoke exit 0 and fail=0", rc == 0 and failed == 0, f"pass={passed} fail={failed}; log sha256 {sha[:16]}")
    journal("D7", "six", "smoke", smoke=f"{passed}/{failed}", log_sha=sha)
    state["u12"] = record["u12"]
    finish("D7", record, state)


def d7_abort(state, record, moved, why):
    say(f"  D7 stops before any service starts ({why}); the six keep running their previous images")
    for svc in moved:
        rc, _, err = run(["docker", "tag", f"{PROJECT}-{svc}:{ROLLBACK_TAG}", f"{PROJECT}-{svc}:latest"])
        gate(f"{PROJECT}-{svc}:latest pointed back at its rollback tag", rc == 0, first_error(err) if rc else "")
        journal("D7", svc, "fail")
    GATES.append((why, False))
    finish("D7", record, state)


# ---------------------------------------------------------------- NR pause / resume (N1)
def cmd_nr(which):
    state, record = load_state(), {"tool": "t1-release-step", "command": which, "pin": PIN}
    precheck(which, state, record, [])
    if which == "nr-pause":
        say("")
        say(f"Run: sudo systemctl stop {NR_GUARD_TIMER}")
        rc, out, err = run(["sudo", "systemctl", "stop", NR_GUARD_TIMER], timeout=120)
        for _ in range(12):
            if nr_state().get(NR_GUARD_SERVICE) != "active":
                break
            time.sleep(5)
        states = nr_state()
        gate("guard timer stopped", rc == 0 and states.get(NR_GUARD_TIMER) != "active", states.get(NR_GUARD_TIMER, "?"))
        gate("guard service not running", states.get(NR_GUARD_SERVICE) != "active", states.get(NR_GUARD_SERVICE, "?"))
        gate("source and transport still active", states.get("parkio-nr-log-source.service") == "active"
             and states.get("parkio-nr-log-continuous.service") == "active")
        state["nrPausedAt"] = now()
        say("  RECOVERY if the session ends before nr-resume: the timer stays stopped. Check the three sources with")
        say("  `sudo env ... resolve_production_sources.sh --check-live-helper`, then `sudo systemctl start "
            f"{NR_GUARD_TIMER}` and `sudo systemctl start {NR_GUARD_SERVICE}`; journalctl must end with continuous-guard PASS.")
        say("  If a source is not attached, do not restart the timer or re-activate the wider NR stack: report to the owner.")
    else:
        say("")
        say("Check before restarting the guard")
        rc, attached, total = nr_attached()
        states = nr_state()
        ok = gate("three sources attached", rc == 0 and attached == 3, f"{attached}/{total}") and gate(
            "source and transport active", states.get("parkio-nr-log-source.service") == "active"
            and states.get("parkio-nr-log-continuous.service") == "active")
        if not ok:
            say("  The timer stays stopped. Owner decision needed; D7 does not start (approved decision 3).")
            return finish(which, record, state)
        say(f"Run: sudo systemctl start {NR_GUARD_TIMER} && sudo systemctl start {NR_GUARD_SERVICE}")
        rc1, _, _ = run(["sudo", "systemctl", "start", NR_GUARD_TIMER], timeout=120)
        rc2, _, _ = run(["sudo", "systemctl", "start", NR_GUARD_SERVICE], timeout=300)
        rc, out, _ = run(["journalctl", "-u", NR_GUARD_SERVICE, "-n", "3", "--no-pager", "-o", "cat"])
        last = (out.strip().splitlines() or [""])[-1]
        gate("guard timer active", nr_state().get(NR_GUARD_TIMER) == "active")
        gate("guard run ends with continuous-guard PASS", rc2 == 0 and "continuous-guard PASS" in last, last[:80])
        paused = state.get("nrPausedAt")
        say(f"  guard paused from {paused or '?'} to {now()}")
    finish(which, record, state)


# ---------------------------------------------------------------- E (final)
def csrf_checks():
    email, password = env_value("PARKIO_REAL_USER_EMAIL"), os.environ.get("PARKIO_REAL_USER_PASSWORD", "")
    if not email or not password:
        gate("CSRF checks: seeded account available (values not shown)", False)
        return
    jar = cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    origin = {"Origin": APP, "Content-Type": "application/json"}
    base = API + "/api/v1/auth"
    st, h, body = http("POST", base + "/login", origin, json.dumps({"email": email, "password": password}).encode(), opener)
    password = None
    try:
        csrf = json.loads(body or b"{}").get("csrfToken", "")
    except ValueError:
        csrf = ""
    gate("login 200 with csrfToken in the body", st == 200 and bool(csrf), str(st))
    st, _, _ = http("POST", base + "/refresh-token", origin, b"{}", opener)
    gate("refresh without X-XSRF-TOKEN -> 403", st == 403, str(st))
    st, _, _ = http("POST", base + "/refresh-token", dict(origin, **{"X-XSRF-TOKEN": csrf}), b"{}", opener)
    gate("refresh with X-XSRF-TOKEN -> 200", st == 200, str(st))
    st, _, _ = http("GET", base + "/csrf", {"Origin": APP}, None, opener)
    gate("csrf read without bearer -> 200", st == 200, str(st))
    st, _, _ = http("POST", base + "/logout", dict(origin, **{"X-XSRF-TOKEN": csrf}), b"{}", opener)
    gate("logout with X-XSRF-TOKEN -> 204", st == 204, str(st))
    st, _, _ = http("POST", base + "/refresh-token", {"Origin": "https://evil.example", "Content-Type": "application/json"}, b"{}")
    gate("cross-origin refresh -> 403", st == 403, str(st))
    csrf = None
    jar.clear()


def cmd_final():
    state, record = load_state(), {"tool": "t1-release-step", "command": "final", "pin": PIN}
    planned = precheck("final", state, record, [])
    missing = session_ready()
    gate("smoke session prepared (variables present, values not shown)", not missing, ", ".join(missing))
    say("")
    say("E1. Smoke gate")
    rc, passed, failed, sha, log = run_smoke("E")
    gate("smoke exit 0 and fail=0", rc == 0 and failed == 0, f"pass={passed} fail={failed}; log sha256 {sha[:16]}")
    say("")
    say("E2. Web transport: CSRF, refresh, logout (values never printed)")
    csrf_checks()
    st, _, _ = http("GET", API + "/actuator/prometheus")
    gate("api /actuator/prometheus -> 404", st == 404, str(st))
    st, h, _ = http("GET", SITE + "/")
    gate("parkio.dev CSP and HSTS present", bool(h.get("content-security-policy")) and bool(h.get("strict-transport-security")))
    say("")
    say("E3. U12 B/C (no offset change)")
    u12 = state.get("u12") or {}
    at_tg = {int(k): v for k, v in (u12.get("scoreEndAtTg") or {}).items()}
    com = committed(U12_GROUP, U12_TOPIC) or {}
    starts = end_offsets(U12_TOPIC, "-2") or {}
    behind = [p for p, end in at_tg.items() if end > starts.get(p, 0) and com.get(p, -1) < end]
    gate("B: parkio.user committed every score partition up to its end at T_g", bool(at_tg) and not behind,
         f"{len(at_tg)} partitions" + (f"; behind: {behind}" if behind else ""))
    dlt_now = end_offsets(DLT_USER)
    before = {int(k): v for k, v in (u12.get("dltUserEndBefore") or {}).items()}
    gate("C: parkio.dlt.user end offsets unchanged since before D7", dlt_now is not None and dlt_now == before,
         f"{sum((dlt_now or {}).values())} vs {sum(before.values())}")
    tg = u12.get("Tg") or ""
    rc, rows, err = psql("gamification", "select count(*) filter (where dead_lettered and not acknowledged_deadletter), "
                                         "count(*) filter (where not published and not dead_lettered and created_at < "
                                         f"'{tg[:26]}'::timestamptz) from outbox_events;" if re.match(r"^\d{4}-\d\d-\d\dT", tg)
                                         else "select -1, -1;")
    vals = rows[0] if rows else ["?", "?"]
    gate("C: gamification outbox 0 dead-lettered and 0 unpublished before T_g", vals == ["0", "0"], "/".join(vals))
    say("")
    say("E4. Flyway (read from each database)")
    migrations = {}
    for svc, db in DB_OF.items():
        fly = flyway(db)
        ok = fly is not None and fly["failed"] == 0 and not [s for s in expected_scripts(svc, fly) if s not in fly["applied"]]
        gate(f"{db}: release scripts applied, 0 failed", ok, f"{len(fly['applied']) if fly else '?'} applied")
        migrations[svc] = fly["applied"] if fly else None
    if any(not ok for _, ok in GATES):
        return finish("final", record, state)
    say("")
    say("E5. Final record")
    if not os.access(FINAL_DIR, os.W_OK):
        return stop("final", record, state, f"{FINAL_DIR} is not writable: the owner creates it first with "
                    f"`sudo install -d -m 0755 -o civo -g civo {FINAL_DIR}`")
    m = model() or {}
    pinned = {s: ((m.get("services") or {}).get(s) or {}).get("image") for s in PINNED}
    images, digests = {}, {}
    for svc in BUILT:
        images[svc] = f"parkio/{svc}:sha-{PIN[:8]}"
        digests[svc] = image_id(images[svc])
    journal_path = os.path.join(OUT, "journal.tsv")
    manifest = {
        "schemaVersion": 1, "action": "deploy", "gitSha": PIN, "branch": "api", "buildTime": state.get("steps", {}).get("D7", {}).get("at"),
        "imageTag": f"sha-{PIN[:8]}", "imageVersion": PIN[:8], "composeFiles": compose_files(),
        "composeOverlays": {"gateway-service": WAITLIST_OVERLAY}, "envProfile": ENV_FILE, "deploymentProfile": "azure-hosted-beta",
        "operator": os.environ.get("PARKIO_OPERATOR") or os.environ.get("USER") or "civo",
        "previousManifest": None, "images": images, "imageDigests": digests, "pinnedImages": pinned,
        "migrationVersions": migrations,
        "migrationNote": "migrationVersions lists the successful Flyway scripts read from each database after E.",
        "runtimeServices": SERVICES, "disabledServices": [],
        "rollbackCommand": "python3 -I t1-release-step.py rollback <service> (schema gate; forward fix after a committed migration)",
        "evidence": {"smokeLogSha256": sha, "journalSha256": sha256_file(journal_path), "plannedRecordStamp": load_plan().get("stamp")},
        "remainingExposure": (state.get("steps", {}).get("D7") or {}).get("remainingExposure"),
    }
    text = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    tmp = os.path.join(FINAL_DIR, ".deployed-manifest.json.tmp")
    with open(tmp, "w", encoding="utf-8") as fh:
        fh.write(text)
        fh.flush()
        os.fsync(fh.fileno())
    os.chmod(tmp, 0o644)
    os.replace(tmp, os.path.join(FINAL_DIR, "deployed-manifest.json"))
    private_write(os.path.join(OUT, "final", "current.json"), text)
    gate("deployed-manifest.json written atomically and copied to R/final/current.json", True,
         hashlib.sha256(text.encode()).hexdigest()[:16])
    state["status"] = "complete"
    finish("final", record, state)


# ---------------------------------------------------------------- rollback
def cmd_rollback(svc):
    state, record = load_state(), {"tool": "t1-release-step", "command": f"rollback {svc}", "pin": PIN}
    say(f"T1 rollback {svc} | stamp {STAMP}")
    planned = load_plan()
    prev = (planned.get("previous") or {}).get(svc)
    if svc not in SERVICES or not prev:
        stop("rollback", record, state, f"{svc} is not a release service with a planned previous container")
    if svc == "gamification-service":
        stop("rollback", record, state, "gamification rolls back only together with user-service (U12): forward fix")
    if svc in DB_OF:
        fly = flyway(DB_OF[svc])
        base = set(((planned.get("baseline") or {}).get(DB_OF[svc]) or {}).get("applied") or [])
        outside = [s for s in (fly or {}).get("applied", []) if s not in base]
        if fly is None or outside:
            say(f"  REFUSED (F-INV-3, no override): {DB_OF[svc]} holds {', '.join(outside) or 'unreadable history'} beyond the "
                "P6 baseline. Forward fix: keep the release if it serves, fix configuration and re-run the step, or ship a "
                "fixed image through PR, review, CI, publication and a pin change. Containment: "
                f"`{WRAPPER} stop {svc}` (gateway answers 503 for its routes).")
            journal("rollback", svc, "refused")
            GATES.append(("schema gate: committed migration beyond the baseline", False))
            return finish("rollback", record, state, ok_status="refused")
        gate("schema gate: no script beyond the P6 baseline", True)
    if prev.get("rollback") not in ("live wrapper", "recorded inputs"):
        stop("rollback", record, state, f"{svc}'s previous config is not reproducible ({prev.get('rollback')}); "
             "owner decision needed")
    if svc in BUILT:
        rc, _, err = run(["docker", "tag", prev["rollbackTag"], f"{PROJECT}-{svc}:latest"])
        gate(f"{PROJECT}-{svc}:latest pointed back at {ROLLBACK_TAG}", rc == 0, first_error(err) if rc else "")
    cur = container(svc) or {}
    if prev["rollback"] == "live wrapper":
        args = EXTRA_FILES.get(svc, []) + ["up", "-d", "--no-build", "--no-deps", svc]
        say(f"Run: cd {LIVE} && PARKIO_ENV_FILE={ENV_FILE} {WRAPPER} {' '.join(args)}")
        rc, out, err = compose(args, cwd=LIVE, timeout=900)
    else:
        inputs = prev["inputs"]
        say(f"Run: docker compose with the previous container's recorded inputs ({len(inputs['files'])} files"
            f"{', web binding rebuilt from its image' if inputs.get('rebuiltWebBinding') else ''}) up -d --no-build --no-deps {svc}")
        rc, out, err = raw_compose(inputs, ["up", "-d", "--no-build", "--no-deps", svc], timeout=900)
    private_write(os.path.join(OUT, "steps", f"rollback-{svc}-{STAMP}.log"), out + err)
    gate(f"{prev['rollback']} exit 0", rc == 0, f"exit {rc}")
    c = wait_healthy(svc, 300) or {}
    gate(f"{svc} healthy", c.get("status") == "running" and c.get("health") in ("healthy", None), f"{c.get('status')}/{c.get('health')}")
    gate(f"{svc} config hash = the previous container's", c.get("hash") == prev["hash"], (c.get("hash") or "-")[:12])
    gate(f"{svc} image = the previous image", c.get("image") == prev["image"], short(c.get("image")))
    journal("rollback", svc, "rollback", cur.get("id", ""), c.get("id", ""), c.get("image", ""), c.get("hash") or "")
    state.setdefault("services", {})[svc] = {"release": "previous", "container": c.get("id"), "at": now()}
    state["status"] = "partial"
    finish("rollback", record, state, ok_status="rolled-back")


def cmd_status():
    state = load_state()
    print(json.dumps({k: state.get(k) for k in ("pin", "status", "steps", "updatedAt")}, indent=2, sort_keys=True))


def main():
    args = sys.argv[1:]
    if args == ["plan"]:
        cmd_plan()
    elif len(args) == 2 and args[0] == "step" and args[1] in STEP_SERVICES:
        cmd_step(args[1])
    elif args in (["nr-pause"], ["nr-resume"]):
        cmd_nr(args[0])
    elif args == ["final"]:
        cmd_final()
    elif len(args) == 2 and args[0] == "rollback":
        cmd_rollback(args[1])
    elif args == ["status"]:
        cmd_status()
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
