#!/usr/bin/env python3
"""Postgres password rotation on parkio-civo-prod, one database per run (incident 2026-10-13, priority 4: the ten
POSTGRES_<DB>_PASSWORD values were in the world-readable rendered model). Run as civo (Python 3.8+); running it needs
the independent review of agent-tools/parkio-redis-credential-incident-20261013/POSTGRES-ROTATION-PROCEDURE.md:

  python3 -I pg-rotate.py pre --db <db>
  python3 -I pg-rotate.py rotate --yes --db <db>
  python3 -I pg-rotate.py rollback --yes --db <db>   (back to the EXPOSED password: only when that rotation cannot complete)
  python3 -I pg-rotate.py redact --yes               (after all ten rotations passed)
  python3 -I pg-rotate.py status
  <db>: analytics, ai-validation, moderation, notification, gamification, media, user, parking, auth, gateway (the
  procedure's order: least user-facing first)

How a password is used (code and host at R2, POSTGRES-ROTATION-PROCEDURE.md):
- each service has its own postgres container. POSTGRES_<DB>_PASSWORD is that container's POSTGRES_PASSWORD, read only
  when its data directory is initialised, and the service's SPRING_DATASOURCE_PASSWORD;
- POSTGRES_USER is the only login role (a superuser). pg_hba trusts the local socket and 127.0.0.1 and asks for
  scram-sha-256 from any other address, so every network login needs the password;
- the nightly backup (PARKIO_PG_MODE unset = docker) dumps with `docker exec <container> pg_dump` over the local socket,
  without a password.
Postgres keeps one password per role, so a database is cut over on its own:
  1. ALTER ROLE ... PASSWORD with a SCRAM-SHA-256 verifier computed here, over the container's local socket. The
     plaintext never reaches the server, a log or an argument. Existing connections stay open;
  2. the env file gets the new value;
  3. the service is recreated. It must come up healthy, and fresh sessions from its new address must appear in
     pg_stat_activity: those can only have authenticated with the new password.
The postgres container is not recreated. Its POSTGRES_PASSWORD keeps the old, now refused value as initialisation
metadata; the role's password in the data directory, and the env file, hold the authoritative new value.
Network logins are checked the way the service connects: from a short-lived container of the database's own image on
the database's network, to <service>:5432, with the password on stdin.

pre --db (read-only):
- host, docker, dockerd debug off, the release checkout clean at R2; outside 03:00-04:30 UTC (the backup);
- the env file's value is at least 32 characters; only the database's container and its service carry it, in the model
  and live (any project, inside Env only);
- the postgres container is healthy and runs the value; its role, database and network are what the tool expects;
  log_statement is none;
- the service is healthy, at the model's hash, runs the model's image and the value, and has fresh sessions;
- the network login discriminates: the value logs in, a random wrong one is refused;
- the backup's env loader yields the value; the public API answers 200.
rotate --yes --db, within an hour of a passing pre for the same database, with the same env file and containers:
the three steps above.
- After step 1: the new password logs in over the network and the old one is refused.
- After step 2: only that line changed, the preflight passes, and exactly the database's container and its service
  changed in the model.
- After step 3:
  - a new healthy service at the model's hash with the new value;
  - fresh sessions of the role from its new address, started after it;
  - every other container unchanged, the postgres container included;
  - the backup's env loader yields the new value;
  - the old password is still refused.
rollback --yes --db, after that database's rotation failed or was interrupted, judged by live state:
- sets the role back to the original password when that no longer logs in;
- restores the env file from the run's backup;
- recreates the service when its live value is not the original, or when it is unhealthy;
- verifies the original logs in, the rotation's value is refused, and the service is healthy with fresh sessions;
- reports that the EXPOSED password is back for that database.
redact --yes, after all ten rotations passed and while each old password is refused:
- replaces each old value with CHANGE_ME_redacted_after_rotation_20261013 in every
  /opt/parkio/docker/.env.azure-hosted-beta.bak-* file, the pr68 artifact and the checkpoint env copy;
- scans /opt, /home, /usr/local, /tmp and /var/tmp in memory, paths only;
- redacts the runs' own backups last.
Records: ~/parkio-release-20261009/pg-rotation/pr-<stamp>-<db>/ (mode 700, files 600). Exit 0 = PASS, 1 = FAIL,
2 = usage.
"""
import base64
import calendar
import glob
import hashlib
import hmac
import json
import os
import re
import secrets
import socket
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

TEST = os.environ.get("PGR_TEST") == "1"


def cfg(name, default):
    """Overrides are honoured only by the self-test."""
    return os.environ.get(name, default) if TEST else default


R2 = cfg("PGR_R2", "c30b9ddbc960cac7e45566c940403dfed1739c7c")
HOST = cfg("PGR_HOST", "parkio-civo-prod")
REL = cfg("PGR_RELEASE", "/opt/parkio-release")
ENV_FILE = cfg("PGR_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
OUT = cfg("PGR_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009", "pg-rotation"))
PUBLIC_PROBE = cfg("PGR_PUBLIC_PROBE", "https://api.parkio.dev/api/v1/auth/registration-mode")
NOW = cfg("PGR_NOW", "")
HEALTH_WAIT = int(cfg("PGR_HEALTH_WAIT", "300"))
SESSION_WAIT = int(cfg("PGR_SESSION_WAIT", "120"))
POLL = float(cfg("PGR_POLL", "5"))
REDACT_GLOB = cfg("PGR_REDACT_GLOB", "/opt/parkio/docker/.env.azure-hosted-beta.bak-*")
REDACT_EXTRA = cfg("PGR_REDACT_EXTRA", ":".join((
    "/opt/parkio/backup-artifacts/pr68-auth-email-20260922T114212Z/env.azure-hosted-beta.pre",
    "/opt/parkio-recovery/checkpoint-550848-postrestore/.env.azure-hosted-beta"))).split(":")
SCAN_ROOTS = cfg("PGR_SCAN_ROOTS", "/opt:/home:/usr/local:/tmp:/var/tmp").split(":")
BACKUP_CWD = cfg("PGR_BACKUP_CWD", "/opt/parkio")
BACKUP_LIB = cfg("PGR_BACKUP_LIB", "/opt/parkio/scripts/lib/backup-common.sh")
BACKUP_ENV = "docker/.env.azure-hosted-beta"
FAULT = cfg("PGR_FAULT", "")                    # self-test only: simulate an interruption at a named point
PROJECT = "parkio"
GATEWAY = "gateway-service"
APP_KEY = "SPRING_DATASOURCE_PASSWORD"
PG_KEY = "POSTGRES_PASSWORD"
# database: (env key, service, aggregate health port); the procedure's order, least user-facing first
_ALL_DBS = {"analytics": ("POSTGRES_ANALYTICS_PASSWORD", "analytics-service", 8089),
       "ai-validation": ("POSTGRES_AIVALIDATION_PASSWORD", "ai-validation-service", 8088),
       "moderation": ("POSTGRES_MODERATION_PASSWORD", "moderation-service", 8087),
       "notification": ("POSTGRES_NOTIFICATION_PASSWORD", "notification-service", 8086),
       "gamification": ("POSTGRES_GAMIFICATION_PASSWORD", "gamification-service", 8085),
       "media": ("POSTGRES_MEDIA_PASSWORD", "media-service", 8084),
       "user": ("POSTGRES_USER_PASSWORD", "user-service", 8082),
       "parking": ("POSTGRES_PARKING_PASSWORD", "parking-service", 8083),
       "auth": ("POSTGRES_AUTH_PASSWORD", "auth-service", 8081),
       "gateway": ("POSTGRES_GATEWAY_PASSWORD", "gateway-service", 8080)}
DBS = {k: v for k, v in _ALL_DBS.items() if k in cfg("PGR_DBS", ",".join(_ALL_DBS)).split(",")}   # the self-test may use fewer
IDENT = re.compile(r"[a-z_][a-z0-9_]{0,62}")
REFUSED = re.compile(rb"password authentication failed")
PLACEHOLDER = re.compile(r"CHANGE_ME|CHANGEME|CHANGE-ME|PLACEHOLDER|REPLACE_ME|REPLACEME|YOUR_|<[A-Za-z_-]+>|DUMMY|SAMPLE_|TODO|FIXME", re.I)
MARKER = "CHANGE_ME_redacted_after_rotation_20261013"
INBOX_OVERLAY = "docker/docker-compose.waitlist-ops-inbox.yml"
PRE_MAX_AGE = 3600
_T = time.time()
STAMP = time.strftime("%Y%m%dT%H%M%S", time.gmtime(_T)) + "%03dZ" % (int(_T * 1000) % 1000)
CHECKS = []
SECRETS = []
DB = None                                        # the database of this run


def scrub(text):
    for v in SECRETS:
        if v:
            text = text.replace(v, "<redacted>")
    return text


def say(line=""):
    print(scrub(line), flush=True)


def check(what, ok, detail=""):
    CHECKS.append({"check": what, "pass": bool(ok), "detail": scrub(str(detail))})
    say(("  PASS " if ok else "  FAIL ") + what + (f" ({detail})" if detail else ""))
    return bool(ok)


def base_env(extra=None):
    env = {k: os.environ[k] for k in ("PATH", "HOME", "LANG", "USER", "LOGNAME") if k in os.environ}
    if TEST and "PGR_W" in os.environ:
        env["PGR_W"] = os.environ["PGR_W"]
    env.update(extra or {})
    return env


def run(cmd, cwd=None, extra_env=None, timeout=300, stdin=None):
    """Every child gets the allowlisted environment; `stdin` (bytes) is its only input, empty when None."""
    try:
        p = subprocess.run(cmd, cwd=cwd, env=base_env(extra_env), capture_output=True, timeout=timeout,
                           input=stdin if stdin is not None else b"")
    except FileNotFoundError:
        return 127, b"", b"not found"
    except subprocess.TimeoutExpired:
        return 124, b"", b"timed out"
    return p.returncode, p.stdout, p.stderr


def git(*args):
    rc, out, _ = run(["git", "--no-optional-locks", "-c", f"safe.directory={REL}", "-C", REL] + list(args))
    return rc, out.decode("utf-8", "replace")


_OVERLAYS = None


def overlays():
    """`-f INBOX_OVERLAY` when the live gateway was created with that activation-only overlay, else nothing (first live
    pre of the Redis rotation, 2026-10-13)."""
    global _OVERLAYS
    if _OVERLAYS is None:
        rc, out, _ = run(["docker", "ps", "-q", "--no-trunc", "--filter", f"label=com.docker.compose.project={PROJECT}",
                          "--filter", f"label=com.docker.compose.service={GATEWAY}"])
        files = ""
        if rc == 0 and out.split():
            rc, out, _ = run(["docker", "inspect", "--format", '{{index .Config.Labels "com.docker.compose.project.config_files"}}',
                              out.split()[0].decode()])
            files = out.decode() if rc == 0 else ""
        _OVERLAYS = ["-f", INBOX_OVERLAY] if any(f.endswith("/" + os.path.basename(INBOX_OVERLAY)) for f in files.strip().split(",")) else []
    return _OVERLAYS


def wrapper(*args, timeout=900):
    return run(["./scripts/parkio-prod-compose.sh"] + overlays() + list(args), cwd=REL, extra_env={"PARKIO_ENV_FILE": ENV_FILE}, timeout=timeout)


def private_write(path, text):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(text)


def record_dir():
    d = os.path.join(OUT, f"pr-{STAMP}-{DB or 'all'}")
    os.makedirs(d, mode=0o700, exist_ok=True)
    return d


def save_progress(rec, path=None):
    text = json.dumps({k: rec.get(k) for k in ("stamp", "db", "phase", "backup")}, sort_keys=True)
    path = path or os.path.join(record_dir(), "rotate-progress.json")
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path))
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(text)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


def finish(name, rec):
    rec["checks"] = CHECKS
    rec["verdict"] = "PASS" if CHECKS and all(c["pass"] for c in CHECKS) else "FAIL"
    text = json.dumps(rec, indent=1, sort_keys=True) + "\n"
    if any(v and v in text for v in SECRETS):
        raise SystemExit("internal error: a record would contain a secret value; nothing written")
    path = os.path.join(record_dir(), f"{name}.json")
    private_write(path, text)
    say(f"postgres rotation {name}{' ' + DB if DB else ''}: {rec['verdict']} | record {path}")
    return 0 if rec["verdict"] == "PASS" else 1


def records(name, db=None):
    """Paths of the `name` records, oldest first; of one database's runs when db is given."""
    try:
        dirs = sorted(d for d in os.listdir(OUT) if d.startswith("pr-") and (db is None or d.endswith(f"-{db}")))
    except OSError:
        return []
    return [p for p in (os.path.join(OUT, d, f"{name}.json") for d in dirs) if os.path.isfile(p)]


def load(path):
    try:
        return json.load(open(path, encoding="utf-8")) if path else None
    except (OSError, ValueError):
        return None


def now():
    return datetime.fromtimestamp(float(NOW), timezone.utc) if NOW else datetime.now(timezone.utc)


# ------------------------------------------------------------------ env file (values stay in memory)
def env_value(data, key):
    for line in data.splitlines():
        if line.startswith(key.encode() + b"="):
            v = line.split(b"=", 1)[1].decode("utf-8", "replace").strip()
            if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
                v = v[1:-1]
            return v
    return ""


def with_value(data, key, value):
    """data with the key's line set to KEY="value" (the live file's form)."""
    out = []
    for line in data.splitlines(keepends=True):
        if line.startswith(key.encode() + b"="):
            end = b"\r\n" if line.endswith(b"\r\n") else (b"\n" if line.endswith(b"\n") else b"")
            line = key.encode() + b'="' + value.encode("ascii") + b'"' + end
        out.append(line)
    return b"".join(out)


def only_key_changed(before, after, key):
    strip = lambda d: [l for l in d.splitlines() if not l.startswith(key.encode() + b"=")]
    return strip(before) == strip(after)


def write_atomically(path, data, st):
    fd, tmp = tempfile.mkstemp(prefix=".pgr-", dir=os.path.dirname(path))
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
            fh.flush()
            os.fsync(fh.fileno())
            os.fchmod(fh.fileno(), st.st_mode & 0o7777)
            os.fchown(fh.fileno(), st.st_uid, st.st_gid)
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise
    dfd = os.open(os.path.dirname(path), os.O_RDONLY)
    try:
        os.fsync(dfd)
    finally:
        os.close(dfd)


def preflight_ok():
    rc, _, _ = run(["sh", os.path.join(REL, "scripts", "preflight-hosted-beta.sh"), "--env-file", ENV_FILE,
                    "--deployment-profile", "azure-hosted-beta", "--skip-compose"], cwd=REL, timeout=300)
    return rc == 0


# ------------------------------------------------------------------ containers and the model
def inspect_all():
    rc, out, _ = run(["docker", "ps", "-aq", "--no-trunc"])
    ids = out.decode().split()
    if rc != 0 or not ids:
        return []
    rc, out, _ = run(["docker", "inspect"] + ids)
    return json.loads(out) if rc == 0 else []


def service_of(d):
    labels = (d.get("Config") or {}).get("Labels") or {}
    return labels.get("com.docker.compose.project"), labels.get("com.docker.compose.service")


def env_of(d):
    return dict(e.split("=", 1) for e in ((d or {}).get("Config") or {}).get("Env") or [] if "=" in e)


def parkio(svc, ds=None):
    found = [d for d in (ds if ds is not None else inspect_all()) if service_of(d) == (PROJECT, svc)]
    return found[0] if len(found) == 1 else None


def health(d):
    return ((d or {}).get("State") or {}).get("Health", {}).get("Status")


def name_of(d):
    return (d or {}).get("Name", "").lstrip("/")


def label(d, key):
    return (((d or {}).get("Config") or {}).get("Labels") or {}).get(key)


def model():
    rc, out, _ = wrapper("config", "--format", "json", timeout=300)
    try:
        return json.loads(out) if rc == 0 else None
    except ValueError:
        return None


def model_hashes():
    rc, out, _ = wrapper("config", "--hash", "*", timeout=300)
    if rc != 0:
        return None
    return dict(l.split() for l in out.decode().splitlines() if len(l.split()) == 2)


def image_id(ref):
    rc, out, _ = run(["docker", "image", "inspect", "--format", "{{.Id}}", ref])
    return out.decode().strip() if rc == 0 else None


def image_ok(m, d, svc):
    ref = (((m or {}).get("services") or {}).get(svc) or {}).get("image") or f"{PROJECT}-{svc}"
    return bool(d) and (d.get("Config") or {}).get("Image") == ref and image_id(ref) == d.get("Image")


def baseline(ds):
    return {f"{p}/{s}": d.get("Id") for d in ds for p, s in [service_of(d)] if p}


def changed_since(base, ds, allowed):
    cur = baseline(ds)
    return sorted(k for k in set(base) | set(cur) if k not in allowed and base.get(k) != cur.get(k))


def wait_container(svc, before_id):
    deadline, d = time.time() + HEALTH_WAIT, None
    while time.time() < deadline:
        d = parkio(svc)
        if d and d.get("Id") != before_id and (d.get("State") or {}).get("Status") == "running" and health(d) == "healthy":
            return d
        time.sleep(POLL)
    return d


def carriers(value, ds):
    live = sorted(name_of(d) for d in ds if value and value in json.dumps(d.get("Config") or {}))
    outside = sorted(name_of(d) for d in ds if value and value in json.dumps({k: v for k, v in (d.get("Config") or {}).items() if k != "Env"}))
    return live, outside


# ------------------------------------------------------------------ Postgres
def scram_verifier(password, salt=None, iterations=4096):
    """The SCRAM-SHA-256 verifier Postgres stores for `password` (RFC 5802 and 7677, the form libpq's
    PQencryptPasswordConn builds). ALTER ROLE ... PASSWORD with it never sends the plaintext. SASLprep leaves the ASCII
    passwords used here unchanged."""
    salt = salt or secrets.token_bytes(16)
    salted = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
    client_key = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
    stored_key = hashlib.sha256(client_key).digest()
    server_key = hmac.new(salted, b"Server Key", hashlib.sha256).digest()
    b64 = lambda b: base64.b64encode(b).decode("ascii")
    return f"SCRAM-SHA-256${iterations}:{b64(salt)}${b64(stored_key)}:{b64(server_key)}"


def role_of(pg):
    """(role, database) from the postgres container's env, when both are plain identifiers."""
    e = env_of(pg)
    user, db = e.get("POSTGRES_USER", ""), e.get("POSTGRES_DB", "")
    return (user, db) if IDENT.fullmatch(user) and IDENT.fullmatch(db) else (None, None)


def network_of(d):
    nets = sorted((((d or {}).get("NetworkSettings") or {}).get("Networks") or {}).keys())
    return nets[0] if len(nets) == 1 else None


def address_of(d, net):
    return ((((d or {}).get("NetworkSettings") or {}).get("Networks") or {}).get(net) or {}).get("IPAddress")


def local_sql(pg, sql):
    """psql over the container's local socket (trusted), the SQL on stdin. (exit code, stdout)."""
    user, db = role_of(pg)
    if not user:
        return 125, ""
    rc, out, _ = run(["docker", "exec", "-i", name_of(pg), "psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", db],
                     stdin=sql.encode(), timeout=60)
    return rc, out.decode("utf-8", "replace").strip()


def set_role_password(pg, password):
    user, _ = role_of(pg)
    rc, _ = local_sql(pg, f"ALTER ROLE \"{user}\" PASSWORD '{scram_verifier(password)}';\n") if user else (125, "")
    return rc == 0


LOGIN_SH = 'IFS= read -r PGPASSWORD; export PGPASSWORD; exec psql -X -q -At -h "$PGR_HOST" -p 5432 -U "$PGR_ROLE" -d "$PGR_DB" -c "select 1"'


def login(db, pg, password):
    """A fresh network login as the service makes it: a short-lived container of the database's own image on its
    network, to <postgres service>:5432, the password on stdin. True: logged in; False: "password authentication
    failed"; None: anything else."""
    user, name = role_of(pg)
    net = network_of(pg)
    if not user or not net:
        return None
    rc, out, err = run(["docker", "run", "--rm", "-i", "--network", net, "--read-only", "--cap-drop", "ALL",
                        "--security-opt", "no-new-privileges:true", "-e", f"PGR_HOST=postgres-{db}", "-e", f"PGR_ROLE={user}",
                        "-e", f"PGR_DB={name}", "--entrypoint", "/bin/sh", pg.get("Image"), "-c", LOGIN_SH],
                       stdin=(password + "\n").encode(), timeout=120)
    if rc == 0 and out.decode().strip() == "1":
        return True
    return False if REFUSED.search(out + err) else None


def fresh_sessions(pg, app):
    """Sessions of the role from the service's current address that started after the service's container did."""
    user, _ = role_of(pg)
    ip = address_of(app, network_of(pg))
    started = ((app or {}).get("State") or {}).get("StartedAt", "")
    found = re.match(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(\.\d{1,6})?\d*Z$", started)
    if not user or not ip or not re.fullmatch(r"[0-9.]+", ip) or not found:
        return None
    since = found.group(1) + (found.group(2) or "") + "Z"
    rc, out = local_sql(pg, f"select count(*) from pg_stat_activity where usename = current_user and client_addr = '{ip}'::inet"
                            f" and backend_start >= '{since}'::timestamptz;\n")
    return int(out) if rc == 0 and out.isdigit() else None


def wait_sessions(pg, app):
    deadline = time.time() + SESSION_WAIT
    while True:
        n = fresh_sessions(pg, app)
        if n or time.time() >= deadline:
            return n
        time.sleep(POLL)


def setting(pg, name):
    rc, out = local_sql(pg, f"show {name};\n")
    return out if rc == 0 else None


def app_health(app, port):
    rc, out, _ = run(["docker", "exec", name_of(app), "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                      f"http://localhost:{port}/actuator/health"], timeout=60)
    return out.decode().strip() if rc == 0 else f"exit {rc}"


def backup_loader(key):
    """The value of `key` as the nightly backup loads the env file (its own library and path, from /opt/parkio), and
    PARKIO_PG_MODE. Read from the child's stdout into memory only."""
    script = (f'. "{BACKUP_LIB}" && parkio_backup_load_env "{BACKUP_ENV}" && '
              f'printf "PGR-MODE:%s\\nPGR-VALUE:%s\\n" "${{PARKIO_PG_MODE:-docker}}" "${{{key}:-}}"')
    rc, out, _ = run(["bash", "-c", script], cwd=BACKUP_CWD, timeout=60)
    got = dict(l.split(":", 1) for l in out.decode("utf-8", "replace").splitlines() if l.startswith(("PGR-MODE:", "PGR-VALUE:")))
    return (got.get("PGR-MODE"), got.get("PGR-VALUE")) if rc == 0 else (None, None)


def public_ok():
    try:
        with urllib.request.urlopen(urllib.request.Request(PUBLIC_PROBE), timeout=20) as resp:
            return resp.status == 200
    except (urllib.error.URLError, OSError):
        return False


def release_ok():
    rc, head = git("rev-parse", "HEAD")
    rc2, st = git("status", "--porcelain")
    head = head.strip()
    return rc == 0 and rc2 == 0 and head == R2 and not st.strip(), head[:12] or "?"


def basics():
    check(f"host {HOST}", socket.gethostname() == HOST, socket.gethostname())
    rc, _, _ = run(["docker", "info", "--format", "{{.ServerVersion}}"])
    check("docker usable without sudo", rc == 0)
    rc, out, _ = run(["docker", "info", "--format", "{{.Debug}}"])
    check("dockerd debug logging is off (it would log exec and run requests)", rc == 0 and out.decode().strip() == "false", out.decode().strip())
    ok, head = release_ok()
    check("the release checkout is clean at R2", ok, head)


# ------------------------------------------------------------------ commands
def cmd_pre():
    key, app_svc, port = DBS[DB]
    pg_svc = f"postgres-{DB}"
    say(f"postgres rotation pre {DB} | {STAMP} | read-only")
    rec = {"tool": "pg-rotate", "command": "pre", "db": DB, "stamp": STAMP}
    basics()
    t = now()
    check("outside 03:00-04:30 UTC (the backup)", not (t.hour == 3 or (t.hour == 4 and t.minute < 30)), t.strftime("%H:%M UTC"))
    say(f"  NOTE Compose calls {'include ' + INBOX_OVERLAY + ' (the live gateway was created with it)' if overlays() else 'use the production file list only'}")
    data = open(ENV_FILE, "rb").read()
    cur = env_value(data, key)
    SECRETS.append(cur)
    check(f"the env file's {key} is at least 32 characters", len(cur) >= 32)
    ds = inspect_all()
    m = model()
    if not check("the release model renders", m is not None):
        return finish("pre", rec)
    in_model = sorted(s for s, sv in (m.get("services") or {}).items() if cur and cur in json.dumps(sv))
    check(f"in the model, exactly {pg_svc} and {app_svc} carry the value", in_model == sorted((pg_svc, app_svc)), ", ".join(in_model))
    live, outside = carriers(cur, ds)
    pg, app = parkio(pg_svc, ds), parkio(app_svc, ds)
    want = sorted(name_of(x) for x in (pg, app) if x)
    check(f"the live containers carrying the value are exactly {pg_svc} and {app_svc}, inside Env only (any project)",
          len(want) == 2 and live == want and not outside, ", ".join(live))
    user, name = role_of(pg)
    net = network_of(pg)
    check(f"{pg_svc}: healthy, runs the value, with a plain role, database and one network",
          bool(pg) and health(pg) == "healthy" and env_of(pg).get(PG_KEY) == cur and bool(user) and bool(net), f"{health(pg)}, {net}")
    check(f"{pg_svc}: log_statement is none (no statement text reaches its log)", bool(user) and setting(pg, "log_statement") == "none")
    hashes = model_hashes() or {}
    check(f"{app_svc}: healthy, at the model's hash, running the model's image, with the value",
          bool(app) and health(app) == "healthy" and label(app, "com.docker.compose.config-hash") == hashes.get(app_svc) and image_ok(m, app, app_svc)
          and env_of(app).get(APP_KEY) == cur, health(app) or "?")
    check(f"{app_svc}: its aggregate health answers 200", bool(app) and app_health(app, port) == "200")
    check(f"{app_svc}: sessions of the role from its address", bool(app) and bool(user) and (fresh_sessions(pg, app) or 0) > 0)
    wrong = secrets.token_urlsafe(33)
    check("the network login discriminates: the value logs in, a random wrong one is refused",
          bool(pg) and login(DB, pg, cur) is True and login(DB, pg, wrong) is False)
    mode, value = backup_loader(key)
    check("the nightly backup's env loader yields the value (its dumps use the local socket: PARKIO_PG_MODE docker)",
          value == cur and mode == "docker", f"mode {mode}")
    check("the public API answers 200 through the gateway", public_ok())
    rec["baseline"] = baseline(ds)
    rec["envSha256"] = hashlib.sha256(data).hexdigest()
    return finish("pre", rec)


def cmd_rotate(yes):
    if not yes:
        print("usage: rotate needs --yes (after the procedure's review and gates)", file=sys.stderr)
        return 2
    key, app_svc, port = DBS[DB]
    pg_svc = f"postgres-{DB}"
    say(f"postgres rotation rotate {DB} | {STAMP}")
    rec = {"tool": "pg-rotate", "command": "rotate", "db": DB, "stamp": STAMP, "phase": "checks"}
    basics()
    paths = records("pre", DB)
    pre = load(paths[-1]) if paths else None
    age = (time.time() - calendar.timegm(time.strptime(pre["stamp"][:15], "%Y%m%dT%H%M%S"))) if pre else None
    # -60: a clock stepped back a little (NTP, or WSL in the self-test) must not refuse a pre taken moments ago
    check(f"the latest pre for {DB} passed within an hour", bool(pre) and pre.get("verdict") == "PASS" and age is not None and -60 <= age <= PRE_MAX_AGE,
          f"{pre['stamp']} {pre.get('verdict')}" if pre else "none")
    data = open(ENV_FILE, "rb").read()
    check("the env file is unchanged since that pre", bool(pre) and hashlib.sha256(data).hexdigest() == pre.get("envSha256"))
    check("the containers are the ones pre recorded", bool(pre) and baseline(inspect_all()) == pre.get("baseline"))
    if not all(c["pass"] for c in CHECKS):
        return finish("rotate", rec)
    old = env_value(data, key)
    new = secrets.token_urlsafe(33)
    while PLACEHOLDER.search(new):
        new = secrets.token_urlsafe(33)
    SECRETS.extend([old, new])
    st = os.stat(ENV_FILE)
    backup = f"{ENV_FILE}.bak-pgr-{DB}-{STAMP}"
    fd = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, st.st_mode & 0o7777)
    with os.fdopen(fd, "wb") as fh:
        fh.write(data)
        fh.flush()
        os.fsync(fh.fileno())
        os.fchown(fh.fileno(), st.st_uid, st.st_gid)
    rec["backup"] = os.path.basename(backup)
    if not check("backup identical to the original", open(backup, "rb").read() == data):
        return finish("rotate", rec)
    base = baseline(inspect_all())
    pg = parkio(pg_svc)

    say(f"1. the role's password (SCRAM verifier over {pg_svc}'s local socket)")
    rec["phase"] = "role"
    save_progress(rec)
    good = check(f"{pg_svc}: ALTER ROLE with the new password's verifier", bool(pg) and set_role_password(pg, new))
    if FAULT == "after-role":
        raise SystemExit(99)
    good = good and check("a fresh network login: the new password logs in; the old one is refused",
                          login(DB, pg, new) is True and login(DB, pg, old) is False)
    if not good:
        say(f"  STOP: run `rollback --yes --db {DB}`")
        return finish("rotate", rec)

    say(f"2. the env file gets the new {key}")
    rec["phase"] = "env"
    save_progress(rec)
    hb = model_hashes() or {}
    write_atomically(ENV_FILE, with_value(data, key, new), st)
    if FAULT == "after-env-write":
        raise SystemExit(99)
    after = open(ENV_FILE, "rb").read()
    good = check(f"only {key} changed in the env file, mode and owner kept",
                 after == with_value(data, key, new) and only_key_changed(data, after, key)
                 and (os.stat(ENV_FILE).st_mode, os.stat(ENV_FILE).st_uid) == (st.st_mode, st.st_uid))
    good &= check("the release preflight passes", preflight_ok())
    ha = model_hashes() or {}
    changed = sorted(s for s in set(hb) | set(ha) if hb.get(s) != ha.get(s))
    good &= check(f"exactly {pg_svc} and {app_svc} changed in the model", changed == sorted((pg_svc, app_svc)), ", ".join(changed))
    if not good:
        say(f"  STOP: run `rollback --yes --db {DB}` (it sets the role back and restores the env file)")
        return finish("rotate", rec)

    say(f"3. {app_svc} is recreated with the new value")
    rec["phase"] = "app"
    save_progress(rec)
    before = parkio(app_svc)
    rc, _, _ = wrapper("up", "-d", "--no-build", "--no-deps", app_svc)
    if FAULT == "after-up":
        raise SystemExit(99)
    d = wait_container(app_svc, (before or {}).get("Id"))
    good = check(f"{app_svc}: a new healthy container at the model's hash",
                 rc == 0 and bool(d) and d.get("Id") != (before or {}).get("Id") and health(d) == "healthy"
                 and label(d, "com.docker.compose.config-hash") == (model_hashes() or {}).get(app_svc), f"exit {rc}, {health(d) or '?'}")
    good &= check(f"{app_svc}: runs the new value", env_of(d).get(APP_KEY) == new)
    good &= check(f"{app_svc}: its aggregate health answers 200", bool(d) and app_health(d, port) == "200")
    n = wait_sessions(pg, d) if d else None
    good &= check(f"{app_svc}: fresh sessions of the role from its new address, started after it (they authenticated with the new password)",
                  bool(n), f"{n} session(s)")
    others = changed_since(base, inspect_all(), {f"{PROJECT}/{app_svc}"})
    good &= check(f"every other container unchanged ({pg_svc} included)", not others, ", ".join(others))
    mode, value = backup_loader(key)
    good &= check("the nightly backup's env loader yields the new value", value == new and mode == "docker", f"mode {mode}")
    good &= check("the old password is still refused over the network", login(DB, pg, old) is False)
    if not good:
        say(f"  STOP: run `rollback --yes --db {DB}`")
        return finish("rotate", rec)
    check("the public API answers 200 through the gateway", public_ok())
    rec["postgresContainer"] = (f"{pg_svc} unchanged; its POSTGRES_PASSWORD is initialisation metadata and keeps the old, refused value;"
                                " the role's password and the env file hold the new one")
    rec["phase"] = "done" if all(c["pass"] for c in CHECKS) else "app"
    save_progress(rec)
    say(f"  the env backup {rec['backup']} holds the old, now refused value; `redact --yes` replaces it once all ten passed")
    return finish("rotate", rec)


def cmd_rollback(yes):
    if not yes:
        print("usage: rollback needs --yes", file=sys.stderr)
        return 2
    key, app_svc, port = DBS[DB]
    pg_svc = f"postgres-{DB}"
    say(f"postgres rotation rollback {DB} | {STAMP} | brings the EXPOSED password back")
    rec = {"tool": "pg-rotate", "command": "rollback", "db": DB, "stamp": STAMP}
    paths = records("rotate-progress", DB)
    path = paths[-1] if paths else None
    prog = load(path)
    phase = (prog or {}).get("phase")
    if not check(f"a rotation of {DB} stopped after its backup (phase role, env or app)", phase in ("role", "env", "app"), phase or "none"):
        return finish("rollback", rec)
    try:
        old_data = open(os.path.join(os.path.dirname(ENV_FILE), prog.get("backup") or ""), "rb").read() if prog.get("backup") else None
    except OSError:
        old_data = None
    if not check("the run's backup exists and is readable", old_data is not None):
        return finish("rollback", rec)
    data = open(ENV_FILE, "rb").read()
    if not check(f"the backup differs from the env file only in the {key} line", only_key_changed(old_data, data, key)):
        return finish("rollback", rec)
    old = env_value(old_data, key)
    ds = inspect_all()
    pg, app = parkio(pg_svc, ds), parkio(app_svc, ds)
    found = [env_value(data, key), env_of(app).get(APP_KEY, "")]
    new = next((v for v in found if v and v != old), "")
    SECRETS.extend([old, new])
    if login(DB, pg, old) is not True:
        check(f"{pg_svc}: the role is set back to the original password", bool(pg) and set_role_password(pg, old))
    if data != old_data:
        write_atomically(ENV_FILE, old_data, os.stat(ENV_FILE))
    check("the env file is byte-identical to the run's backup", open(ENV_FILE, "rb").read() == old_data)
    # an unhealthy service (it could not log in while the role held the other password) is recreated too
    if not app or env_of(app).get(APP_KEY) != old or health(app) != "healthy":
        rc, _, _ = wrapper("up", "-d", "--no-build", "--no-deps", "--force-recreate", app_svc)
        d = wait_container(app_svc, (app or {}).get("Id"))
        check(f"{app_svc}: recreated and healthy", rc == 0 and bool(d) and d.get("Id") != (app or {}).get("Id") and health(d) == "healthy")
    app = parkio(app_svc)
    check(f"{app_svc}: runs the original value, healthy, aggregate health 200",
          bool(app) and env_of(app).get(APP_KEY) == old and health(app) == "healthy" and app_health(app, port) == "200")
    check("a fresh network login: the original password logs in; the rotation's is refused",
          login(DB, pg, old) is True and (not new or login(DB, pg, new) is False))
    n = wait_sessions(pg, app) if app else None
    check(f"{app_svc}: fresh sessions of the role from its address, started after it", bool(n), f"{n} session(s)")
    mode, value = backup_loader(key)
    check("the nightly backup's env loader yields the original value", value == old)
    check("the public API answers 200 through the gateway", public_ok())
    if all(c["pass"] for c in CHECKS):
        prog["phase"] = "rolled-back"
        save_progress(prog, path)
        say(f"  {DB}'s rotation is marked rolled-back. The EXPOSED password is live again for {DB}: fix the cause, run pre, rotate again."
            " Do not start the next database first.")
    return finish("rollback", rec)


def redact_file(path, olds):
    """Replace every old value in path with MARKER. Later backups hold current values of databases rotated earlier, so a
    current value is no reason to leave a file alone; the live env file is never touched."""
    try:
        st = os.lstat(path)
    except FileNotFoundError:
        return True, "missing; nothing to redact"
    except OSError:
        return False, "cannot be read"
    if os.path.realpath(path) == os.path.realpath(ENV_FILE):
        return False, "the live env file is never redacted"
    if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid():
        return False, "not a regular file of this user; left alone"
    try:
        data = open(path, "rb").read()
    except OSError as e:
        return False, f"cannot be read ({e.__class__.__name__}); left alone"
    n = sum(data.count(o.encode()) for o in olds)
    if n == 0:
        return True, "no occurrence"
    out = data
    for o in olds:
        out = out.replace(o.encode(), MARKER.encode())
    try:
        fd, tmp = tempfile.mkstemp(prefix=".pgr-redact-", dir=os.path.dirname(path))
    except OSError as e:
        return False, f"cannot be rewritten ({e.__class__.__name__}); left alone"
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(out)
            fh.flush()
            os.fsync(fh.fileno())
            os.fchmod(fh.fileno(), st.st_mode & 0o7777)
            os.fchown(fh.fileno(), st.st_uid, st.st_gid)
        os.utime(tmp, ns=(st.st_atime_ns, st.st_mtime_ns))
        os.replace(tmp, path)
    except OSError as e:
        if os.path.exists(tmp):
            os.unlink(tmp)
        return False, f"cannot be rewritten ({e.__class__.__name__}); left alone"
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise
    after = os.lstat(path)
    ok = open(path, "rb").read() == out and (after.st_mode, after.st_uid, after.st_mtime_ns) == (st.st_mode, st.st_uid, st.st_mtime_ns)
    return ok, (f"{n} occurrence(s) replaced; mode, owner and mtime kept" if ok else "written, but the read-back differs")


def scan_for(values):
    found = []
    for root in SCAN_ROOTS:
        for dp, dns, fns in os.walk(root):
            dns[:] = [d for d in dns if d not in (".git", "node_modules", "journal")]
            for fn in fns:
                p = os.path.join(dp, fn)
                try:
                    if os.path.islink(p) or not os.path.isfile(p) or os.path.getsize(p) > 200_000_000:
                        continue
                    with open(p, "rb") as fh:
                        data = fh.read()
                    if any(v.encode() in data for v in values):
                        found.append(p)
                except OSError:
                    continue
    return found


def cmd_redact(yes):
    if not yes:
        print("usage: redact needs --yes (after all ten rotations passed)", file=sys.stderr)
        return 2
    say(f"postgres rotation redact | {STAMP}")
    rec = {"tool": "pg-rotate", "command": "redact", "stamp": STAMP}
    data = open(ENV_FILE, "rb").read()
    olds, backups, done = {}, {}, True
    for db, (key, _, _) in DBS.items():
        rot = [r for r in (load(p) for p in records("rotate", db)) if r and r.get("verdict") == "PASS"]
        prog = [r for r in (load(p) for p in records("rotate-progress", db)) if r and r.get("phase") == "done"]
        run_ = rot[-1] if rot else None
        if not run_ or not prog or prog[-1].get("stamp") != run_.get("stamp"):
            done = False
            continue
        backups[db] = os.path.join(os.path.dirname(ENV_FILE), run_.get("backup") or "")
        olds[db] = env_value(open(backups[db], "rb").read(), key) if os.path.isfile(backups[db]) else ""
    if not check(f"all {len(DBS)} databases have a passing rotation", done and len(olds) == len(DBS)):
        return finish("redact", rec)
    if all(o == MARKER for o in olds.values()):
        check("redaction already complete: the runs' backups no longer hold the old values", True)
        return finish("redact", rec)
    news = {db: env_value(data, DBS[db][0]) for db in DBS}
    SECRETS.extend(list(olds.values()) + list(news.values()))
    ds = inspect_all()
    for db in DBS:
        pg = parkio(f"postgres-{db}", ds)
        if olds[db] != MARKER:
            check(f"{db}: the old password is known (its run's backup) and refused over the network; the current one logs in",
                  len(olds[db]) >= 32 and olds[db] != news[db] and login(db, pg, olds[db]) is False and login(db, pg, news[db]) is True)
    if not all(c["pass"] for c in CHECKS):
        return finish("redact", rec)
    old_values = [o for o in olds.values() if o != MARKER]
    run_backups = {os.path.realpath(b) for b in backups.values()}
    rec["files"] = {}
    paths = sorted(p for p in glob.glob(REDACT_GLOB) if os.path.realpath(p) not in run_backups) + REDACT_EXTRA
    for path in paths:
        ok, detail = redact_file(path, old_values)
        rec["files"][path] = detail
        check(f"{path}: redacted", ok, detail)
    left = [p for p in scan_for(old_values) if os.path.realpath(p) not in run_backups]
    check(f"apart from the runs' backups, no readable file under {', '.join(SCAN_ROOTS)} holds an old value", not left, ", ".join(left))
    if not all(c["pass"] for c in CHECKS):
        say("  the runs' backups keep the old values, so this can run again once the failures above are dealt with")
        return finish("redact", rec)
    for db, b in sorted(backups.items()):
        ok, detail = redact_file(b, old_values)
        rec["files"][b] = detail
        check(f"{b}: redacted last", ok, detail)
    return finish("redact", rec)


def cmd_status():
    try:
        for d in sorted(os.listdir(OUT))[-12:]:
            for f in sorted(os.listdir(os.path.join(OUT, d))):
                if f.endswith(".json"):
                    r = json.load(open(os.path.join(OUT, d, f), encoding="utf-8"))
                    say(f"{d} {f[:-5]}: {r.get('verdict') or r.get('phase')}")
    except (OSError, ValueError):
        say("no records")
    return 0


def main(argv):
    global DB
    if not argv or argv[0] not in ("pre", "rotate", "rollback", "redact", "status"):
        print(__doc__, file=sys.stderr)
        return 2
    cmd, rest = argv[0], list(argv[1:])
    if "--db" in rest:
        i = rest.index("--db")
        DB = rest[i + 1] if i + 1 < len(rest) else ""
        del rest[i:i + 2]
    yes = rest == ["--yes"]
    needs_db = cmd in ("pre", "rotate", "rollback")
    if (rest and not yes) or (yes and cmd in ("pre", "status")) or (needs_db and DB not in DBS) or (not needs_db and DB is not None):
        print("usage: unknown or misplaced argument (pre, rotate and rollback need --db <db>; redact and status take none)", file=sys.stderr)
        return 2
    if cmd == "pre":
        return cmd_pre()
    if cmd == "status":
        return cmd_status()
    if cmd == "rotate":
        return cmd_rotate(yes)
    if cmd == "redact":
        return cmd_redact(yes)
    return cmd_rollback(yes)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
