#!/usr/bin/env python3
"""Self-test of pg-rotate.py, with fakes only (pg-local-rotation-test.py drives its Postgres functions against real
Postgres on the production images).

The fakes are backed by one JSON state:
- **docker:** info, ps (with filters), inspect (and `--format` for the config_files label), image inspect, and:
  - `exec -i <postgres> psql ...` reading SQL on stdin. ALTER ROLE stores the SCRAM verifier; `show log_statement`
    answers; the pg_stat_activity count counts the stored sessions by address and start;
  - `run --rm -i --network <net> ... -e PGR_HOST=postgres-<db> ... -c <login>` reading the password on stdin. It logs in
    when the password matches the role's verifier, else answers "password authentication failed";
  - `exec <app> curl ... /actuator/health`, which answers 200 while the service is healthy.
  Every argv and child environment is recorded.
- **The release checkout:** a git repository at R2.
  - Its fake wrapper renders the ten postgres containers and their services (each service's
    SPRING_DATASOURCE_PASSWORD and its database's POSTGRES_PASSWORD from the same env key), the gateway's inbox overlay,
    web and redis.
  - `up` recreates one service at a new address. The service is healthy, with a fresh session, only when its password
    matches its role's verifier, as Flyway at start would require.
  - Its fake preflight passes unless told otherwise.
- **The backup library:** a stand-in for scripts/lib/backup-common.sh whose parkio_backup_load_env sources the env
  file with `set -a`.
- **An HTTP server:** stands in for the public API.
Marker files inject faults; PGR_FAULT simulates interruptions.

Pass criteria:
- every expected result holds;
- no secret value (old, new or a probe's wrong value) appears in any output, record or docker/wrapper argument;
- no child sees a variable from outside the allowlist.

  python3 -I pg-rotate-selftest.py
"""
import base64
import calendar
import hashlib
import hmac
import http.server
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "pg-rotate.py")
ORDER = ("analytics", "ai-validation", "moderation", "notification", "gamification", "media", "user", "parking", "auth", "gateway")
KEYS = {db: f"POSTGRES_{db.replace('-', '').upper()}_PASSWORD" for db in ORDER}
APPS = {db: f"{db}-service" for db in ORDER}
results = []

FAKE_COMMON = r'''
import base64, hashlib, hmac, json, os, re, sys, time
W = os.environ["PGR_W"]
P = os.path.join(W, "state.json")
mark = lambda m: os.path.exists(os.path.join(W, m))
def load(): return json.load(open(P))
def save(st): json.dump(st, open(P + ".t", "w")); os.replace(P + ".t", P)
def verifies(verifier, password):
    """True when password matches a SCRAM-SHA-256 verifier (an independent reading of the stored form)."""
    try:
        head, keys = verifier.split("$", 1)[1].split("$", 1)
        iters, salt = head.split(":", 1)
        stored, _ = keys.split(":", 1)
        salted = hashlib.pbkdf2_hmac("sha256", password.encode(), base64.b64decode(salt), int(iters))
        return hashlib.sha256(hmac.new(salted, b"Client Key", hashlib.sha256).digest()).digest() == base64.b64decode(stored)
    except (ValueError, IndexError):
        return False
'''

FAKE_DOCKER = r'''#!/usr/bin/env python3''' + FAKE_COMMON + r'''
a = sys.argv[1:]
open(os.path.join(W, "argv.log"), "a").write(json.dumps(a) + "\n")
open(os.path.join(W, "env.log"), "a").write(json.dumps({"argv0": a[0], "env": sorted(os.environ)}) + "\n")
st = load()
cs = st["containers"]
if a[0] == "info":
    print(("true" if mark("daemon-debug") else "false") if "{{.Debug}}" in a else "29.8.1"); sys.exit(0)
if a[0] == "ps":
    flt = [a[i + 1].split("=", 2) for i in range(len(a) - 1) if a[i] == "--filter"]
    keep = lambda c: all((k == "com.docker.compose.service" and c["service"] == v) or (k == "com.docker.compose.project" and c["project"] == v)
                         for _, k, v in flt)
    print("\n".join(i for i, c in cs.items() if keep(c))); sys.exit(0)
if a[0] == "inspect" and "--format" in a:
    print(cs[a[-1]].get("files", "")); sys.exit(0)
if a[0] == "inspect":
    out = []
    for i in a[1:]:
        c = cs[i]
        out.append({"Id": i, "Name": "/" + c["name"], "Image": c["image"],
                    "Config": {"Env": [f"{k}={v}" for k, v in c["env"].items()], "Image": c.get("ref"), "Cmd": c.get("cmd", []),
                               "Labels": {"com.docker.compose.project": c["project"], "com.docker.compose.service": c["service"],
                                          "com.docker.compose.config-hash": c["hash"]}},
                    "State": {"Status": "running", "Health": {"Status": c["health"]}, "StartedAt": c.get("started", "2026-10-13T00:00:00.000000000Z")},
                    "NetworkSettings": {"Networks": {"parkio-backend": {"IPAddress": c.get("ip", "172.18.0.250")}}}})
    print(json.dumps(out)); sys.exit(0)
if a[:2] == ["image", "inspect"]:
    ref = a[-1]
    iid = st["images"].get(ref) or (ref if ref in st["images"].values() else None)
    if iid and mark("tag-moved") and "media" in ref: iid = "sha256:" + "f" * 64
    if not iid: sys.exit(1)
    print(iid); sys.exit(0)
def pg_of(db):
    found = [c for c in cs.values() if c["project"] == "parkio" and c["service"] == "postgres-" + db]
    return found[0] if found else None
if a[0] == "exec" and "psql" in a:
    c = [x for x in cs.values() if x["name"] == a[2]][0]
    db = c["service"][len("postgres-"):]
    sql = sys.stdin.read().strip()
    if sql.startswith("ALTER ROLE"):
        if mark("alter-fails"):
            print("ERROR:  permission denied", file=sys.stderr); sys.exit(3)
        found = re.match(r"""ALTER ROLE "([a-z_0-9]+)" PASSWORD '(SCRAM-SHA-256\$[^']+)';$""", sql)
        if not found or found.group(1) != c["env"]["POSTGRES_USER"]:
            print("ERROR:  syntax error", file=sys.stderr); sys.exit(3)
        st["roles"][db] = found.group(2); save(st); sys.exit(0)
    if sql == "show log_statement;":
        print("all" if mark("log-statement-all") else "none"); sys.exit(0)
    found = re.match(r"select count\(\*\) from pg_stat_activity where usename = current_user and client_addr = '([0-9.]+)'::inet"
                     r" and backend_start >= '([0-9T:.\-]+)Z'::timestamptz;$", sql)
    if found:
        n = sum(1 for s in st["sessions"].get(db, []) if s["addr"] == found.group(1) and s["start"] >= found.group(2))
        print(n); sys.exit(0)
    print("ERROR:  unknown statement", file=sys.stderr); sys.exit(3)
if a[0] == "exec" and "curl" in a:
    c = [x for x in cs.values() if x["name"] == a[1]][0]
    sys.stdout.write("503" if mark("app-health-503") or c["health"] != "healthy" else "200"); sys.exit(0)
if a[0] == "run":
    envs = dict(a[i + 1].split("=", 1) for i in range(len(a) - 1) if a[i] == "-e")
    db = envs.get("PGR_HOST", "")[len("postgres-"):]
    pg = pg_of(db)
    password = sys.stdin.read().split("\n")[0]
    if not pg or a[a.index("--network") + 1] != "parkio-backend" or envs.get("PGR_ROLE") != pg["env"]["POSTGRES_USER"]:
        print("psql: error: could not translate host name", file=sys.stderr); sys.exit(2)
    if verifies(st["roles"].get(db, ""), password) or mark("trust-all-" + db):
        print("1"); sys.exit(0)
    print(f'psql: error: connection to server at "postgres-{db}", port 5432 failed: FATAL:  password authentication failed for user "{envs.get("PGR_ROLE")}"',
          file=sys.stderr)
    sys.exit(2)
sys.exit(2)
'''

FAKE_WRAPPER = r'''#!/usr/bin/env python3''' + FAKE_COMMON + r'''
a = sys.argv[1:]
open(os.path.join(W, "argv.log"), "a").write(json.dumps(["wrapper"] + a) + "\n")
open(os.path.join(W, "env.log"), "a").write(json.dumps({"argv0": "wrapper", "env": sorted(os.environ)}) + "\n")
OVERLAYS = []
while a[:1] == ["-f"]:
    OVERLAYS.append(a[1]); a = a[2:]
INBOX = any(o.endswith("docker-compose.waitlist-ops-inbox.yml") for o in OVERLAYS)
env = {}
for line in open(os.environ["PARKIO_ENV_FILE"], encoding="utf-8"):
    if "=" in line and not line.startswith("#"):
        k, v = line.rstrip("\n").split("=", 1)
        if len(v) >= 2 and v[0] == v[-1] == '"':
            v = v[1:-1]
        env[k] = v
ORDER = ("analytics", "ai-validation", "moderation", "notification", "gamification", "media", "user", "parking", "auth", "gateway")
KEY = lambda db: f"POSTGRES_{db.replace('-', '').upper()}_PASSWORD"
ROLE = lambda db: "parkio_" + db.replace("-", "")
def service(svc):
    for db in ORDER:
        if svc == "postgres-" + db:
            return {"image": "postgis/postgis:16-3.4" if db == "parking" else "postgres:16-alpine",
                    "environment": {"POSTGRES_DB": ROLE(db), "POSTGRES_USER": ROLE(db), "POSTGRES_PASSWORD": env.get(KEY(db), "")}}
        if svc == db + "-service":
            e = {"SPRING_DATASOURCE_PASSWORD": env.get(KEY(db), ""), "SPRING_DATASOURCE_USERNAME": ROLE(db), "X": "1"}
            if db == "gateway" and INBOX: e["PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR"] = "/var/lib/parkio/waitlist-ops-inbox"
            return {"image": "ghcr.io/x/" + svc, "environment": e}
    e = {"Y": "2"}
    if svc == "web" and mark("model-leak"): e["LEAK"] = env.get(KEY("media"), "")
    if svc == "web" and mark("env-affects-web"): e["X"] = hashlib.sha256(env.get(KEY("media"), "").encode()).hexdigest()[:8]
    return {"image": "ghcr.io/x/" + svc, "environment": e}
SERVICES = tuple("postgres-" + db for db in ORDER) + tuple(db + "-service" for db in ORDER) + ("web", "redis")
def mhash(svc): return hashlib.sha256(json.dumps(service(svc), sort_keys=True).encode()).hexdigest()[:16]
if a[:2] == ["config", "--format"]:
    print(json.dumps({"services": {s: service(s) for s in SERVICES}})); sys.exit(0)
if a[:2] == ["config", "--hash"]:
    print("\n".join(f"{s} {mhash(s)}" for s in SERVICES)); sys.exit(0)
if a[0] == "up":
    svc = a[-1]
    force = "--force-recreate" in a
    st = load()
    mine = [i for i, c in st["containers"].items() if c["project"] == "parkio" and c["service"] == svc]
    old = mine[0]
    if not force and st["containers"][old]["hash"] == mhash(svc):
        sys.exit(0)
    c = dict(st["containers"].pop(old))
    if not mark("stale-env-" + svc):
        c["env"] = dict(service(svc)["environment"])
    c["hash"] = mhash(svc)
    c["files"] = "/opt/parkio-release/docker/docker-compose.yml" + (",/opt/parkio-release/docker/docker-compose.waitlist-ops-inbox.yml" if INBOX and svc == "gateway-service" else "")
    st["next_ip"] = st.get("next_ip", 100) + 1
    if not mark("ip-reuse"):                            # Docker may give a recreated container its predecessor's address
        c["ip"] = f"172.18.1.{st['next_ip']}"
    c["started"] = time.strftime("%Y-%m-%dT%H:%M:%S.123456789Z", time.gmtime())
    db = svc[:-len("-service")]
    ok = verifies(st["roles"].get(db, ""), c["env"].get("SPRING_DATASOURCE_PASSWORD", ""))
    c["health"] = "healthy" if ok and not mark("unhealthy-" + svc) else "unhealthy"
    if ok and not mark("no-sessions-" + db):
        st["sessions"].setdefault(db, []).append({"addr": c["ip"], "start": time.strftime("%Y-%m-%dT%H:%M:%S.5", time.gmtime(time.time() + 1))})
    if mark("collateral-" + svc):
        red = [i for i, x in st["containers"].items() if x["service"] == "redis"][0]
        st["containers"]["c" * 64] = st["containers"].pop(red)
    st["containers"][hashlib.sha256(f"{svc}{time.time()}".encode()).hexdigest()] = c
    save(st)
    sys.exit(0)
sys.exit(2)
'''

FAKE_BACKUP_LIB = r'''# stand-in for scripts/lib/backup-common.sh
parkio_backup_load_env() {
  local env_file="${1:-}"
  [ -e "$PGR_W/backup-loader-stale" ] && env_file="$PGR_W/stale.env"
  set -a
  . "${env_file}"
  set +a
}
'''


def verifier(password):
    salt = secrets.token_bytes(16)
    salted = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 4096)
    ck = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
    sk = hmac.new(salted, b"Server Key", hashlib.sha256).digest()
    b = lambda x: base64.b64encode(x).decode()
    return f"SCRAM-SHA-256$4096:{b(salt)}${b(hashlib.sha256(ck).digest())}:{b(sk)}"


def ok(cond, label, extra=""):
    results.append(bool(cond))
    print(("ok   " if cond else "FAIL ") + label + ("" if cond else f"\n{extra[-2500:]}"))


class Api(http.server.BaseHTTPRequestHandler):
    W = None

    def log_message(self, *a):
        pass

    def do_GET(self):
        status = 503 if os.path.exists(os.path.join(self.W, "public-down")) else 200
        self.send_response(status)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"{}")


def git(repo, *args):
    p = subprocess.run(["git", "-C", repo, "-c", "user.name=t", "-c", "user.email=t@t"] + list(args), capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit(f"git {args}: {p.stderr}")
    return p.stdout.strip()


def main():
    W = tempfile.mkdtemp(prefix="pgr-")
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Api)
    Api.W = W
    threading.Thread(target=server.serve_forever, daemon=True).start()
    outputs = []
    try:
        R, L, OUT, BIN = (os.path.join(W, d) for d in ("release", "live", "out", "bin"))
        for d in (os.path.join(R, "scripts"), os.path.join(L, "docker"), os.path.join(L, "scripts", "lib"), BIN):
            os.makedirs(d)
        open(os.path.join(BIN, "docker"), "w").write(FAKE_DOCKER)
        os.chmod(os.path.join(BIN, "docker"), 0o755)
        for name, body in (("parkio-prod-compose.sh", FAKE_WRAPPER),
                           ("preflight-hosted-beta.sh", '#!/bin/sh\n[ -e "$PGR_W/preflight-fail" ] && exit 1\nexit 0\n')):
            p = os.path.join(R, "scripts", name)
            open(p, "w").write(body)
            os.chmod(p, 0o755)
        LIB = os.path.join(L, "scripts", "lib", "backup-common.sh")
        open(LIB, "w").write(FAKE_BACKUP_LIB)
        git(R, "init", "-q")
        git(R, "add", "-A")
        git(R, "commit", "-qm", "R2")
        r2 = git(R, "rev-parse", "HEAD")
        open(os.path.join(R, "later.txt"), "w").write("x")
        git(R, "add", "-A")
        git(R, "commit", "-qm", "later")
        later = git(R, "rev-parse", "HEAD")
        ENV = os.path.join(L, "docker", ".env.azure-hosted-beta")
        BACKUPS = [os.path.join(L, "docker", ".env.azure-hosted-beta.bak-old1")]
        EXTRA = [os.path.join(L, "artifact.env"), os.path.join(L, "checkpoint.env")]
        MARKER = "CHANGE_ME_redacted_after_rotation_20261013"
        OLD = {db: secrets.token_urlsafe(33) for db in ORDER}
        values = list(OLD.values())
        env = {"PATH": BIN + ":" + os.environ["PATH"], "HOME": W, "LANG": "C.UTF-8", "LEAKY_SECRET": "x", "PGR_TEST": "1", "PGR_W": W,
               "PGR_R2": r2, "PGR_HOST": socket.gethostname(), "PGR_RELEASE": R, "PGR_ENV_FILE": ENV, "PGR_OUT": OUT,
               "PGR_PUBLIC_PROBE": f"http://127.0.0.1:{server.server_address[1]}/public",
               "PGR_NOW": str(calendar.timegm(time.strptime("2026-10-13 15:00", "%Y-%m-%d %H:%M"))),
               "PGR_HEALTH_WAIT": "10", "PGR_SESSION_WAIT": "3", "PGR_POLL": "0.1",
               "PGR_REDACT_GLOB": os.path.join(L, "docker", ".env.azure-hosted-beta.bak-*"),
               "PGR_REDACT_EXTRA": ":".join(EXTRA + [os.path.join(L, "never-there.env")]), "PGR_SCAN_ROOTS": L,
               "PGR_BACKUP_CWD": L, "PGR_BACKUP_LIB": LIB}
        TWO = {"PGR_DBS": "media,parking"}

        def env_text(vals, mode=None):
            return ("PARKIO_DOMAIN=parkio.dev\n" + "".join(f'{KEYS[db]}="{vals[db]}"\n' for db in ORDER) +
                    (f"PARKIO_PG_MODE={mode}\n" if mode else ""))

        def archive_logs():
            for f in ("argv.log", "env.log"):
                src = os.path.join(W, f)
                if os.path.exists(src):
                    with open(os.path.join(W, f.replace(".log", "-all.log")), "a") as dst:
                        dst.write(open(src).read())
                    os.remove(src)

        def wrap(*args, overlay=True):
            p = subprocess.run([os.path.join(R, "scripts", "parkio-prod-compose.sh")] + (["-f", "docker/docker-compose.waitlist-ops-inbox.yml"] if overlay else [])
                               + list(args), cwd=R, capture_output=True, text=True, env={k: v for k, v in dict(env, PARKIO_ENV_FILE=ENV).items() if k != "LEAKY_SECRET"})
            return p.stdout

        def reset(checkout=r2, short=None, extra_carrier=False, cmd_carrier=None, gateway_inbox=True, mode=None):
            for f in os.listdir(W):
                if f.startswith(("unhealthy-", "stale-env-", "collateral-", "trust-all-", "no-sessions-")) or f in (
                        "tag-moved", "model-leak", "env-affects-web", "preflight-fail", "daemon-debug", "public-down", "alter-fails",
                        "log-statement-all", "app-health-503", "backup-loader-stale", "stale.env", "ip-reuse"):
                    os.remove(os.path.join(W, f))
            archive_logs()
            shutil.rmtree(OUT, ignore_errors=True)
            for f in os.listdir(os.path.join(L, "docker")):
                os.remove(os.path.join(L, "docker", f))
            for f in os.listdir(L):
                if os.path.isfile(os.path.join(L, f)) or os.path.islink(os.path.join(L, f)):
                    os.remove(os.path.join(L, f))
            git(R, "checkout", "-q", "--detach", checkout)
            vals = dict(OLD)
            if short:
                vals[short] = OLD[short][:20]
            for path in [ENV] + BACKUPS + EXTRA:
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                os.write(fd, env_text(vals, mode).encode())
                os.close(fd)
            cs, images, roles, sessions = {}, {}, {}, {}
            for i, db in enumerate(ORDER):
                role = "parkio_" + db.replace("-", "")
                pg_ref = "postgis/postgis:16-3.4" if db == "parking" else "postgres:16-alpine"
                images[pg_ref] = "sha256:" + ("4" if db == "parking" else "c") * 64
                cs[f"{i:02d}" * 32] = {"name": f"parkio-postgres-{db}", "project": "parkio", "service": f"postgres-{db}", "image": images[pg_ref],
                                       "ref": pg_ref, "hash": "pre-hardening", "health": "healthy", "ip": f"172.18.0.{10 + i}",
                                       "env": {"POSTGRES_DB": role, "POSTGRES_USER": role, "POSTGRES_PASSWORD": vals[db]}}
                app_ref = f"ghcr.io/x/{db}-service"
                images[app_ref] = f"sha256:{i:02d}{'1' * 62}"
                e = {"SPRING_DATASOURCE_PASSWORD": vals[db], "SPRING_DATASOURCE_USERNAME": role, "X": "1"}
                if db == "gateway" and gateway_inbox:
                    e["PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR"] = "/var/lib/parkio/waitlist-ops-inbox"
                started = "2026-10-13T09:00:00.000000000Z"
                cs[f"a{i:02d}" * 21 + "a"] = {"name": f"parkio-{db}-service-1", "project": "parkio", "service": f"{db}-service", "image": images[app_ref],
                                              "ref": app_ref, "hash": "", "health": "healthy", "ip": f"172.18.0.{40 + i}", "started": started, "env": e,
                                              "cmd": ["x", f"--p={vals[db]}"] if cmd_carrier == db else [],
                                              "files": "/opt/parkio-release/docker/docker-compose.yml" +
                                                       (",/opt/parkio-release/docker/docker-compose.waitlist-ops-inbox.yml" if db == "gateway" and gateway_inbox else "")}
                roles[db] = verifier(vals[db])
                sessions[db] = [{"addr": f"172.18.0.{40 + i}", "start": "2026-10-13T09:00:05.0"}]
            for i, svc in enumerate(("web", "redis")):
                images[f"ghcr.io/x/{svc}"] = f"sha256:{i:02d}{'2' * 62}"
                cs[f"b{i:02d}" * 21 + "b"] = {"name": f"parkio-{svc}-1", "project": "parkio", "service": svc, "image": images[f"ghcr.io/x/{svc}"],
                                              "ref": f"ghcr.io/x/{svc}", "hash": "", "health": "healthy", "env": {"Y": "2"}}
            if extra_carrier:
                cs["e" * 64] = {"name": "other-app", "project": "other", "service": "app", "image": "x", "ref": "x", "hash": "h", "health": "healthy",
                                "env": {"COPY": vals["media"]}}
            st = {"containers": cs, "images": images, "roles": roles, "sessions": sessions}
            json.dump(st, open(os.path.join(W, "state.json"), "w"))
            hashes = dict(l.split() for l in wrap("config", "--hash", "*", overlay=gateway_inbox).splitlines())
            for c in st["containers"].values():
                if c["project"] == "parkio" and not c["service"].startswith("postgres-"):
                    c["hash"] = hashes[c["service"]]
            json.dump(st, open(os.path.join(W, "state.json"), "w"))
            archive_logs()

        def tool(*args, extra=None):
            p = subprocess.run([sys.executable, "-I", TOOL] + list(args), capture_output=True, text=True, env=dict(env, **(extra or {})), timeout=900)
            outputs.append(p.stdout + p.stderr)
            return p.returncode, p.stdout + p.stderr

        def state():
            return json.load(open(os.path.join(W, "state.json")))

        def mark(m, text="x"):
            open(os.path.join(W, m), "w").write(text)

        def env_val(db):
            for line in open(ENV):
                if line.startswith(KEYS[db] + "="):
                    return line.rstrip("\n").split("=", 1)[1].strip('"')

        def ctr(svc):
            return [c for c in state()["containers"].values() if c["project"] == "parkio" and c["service"] == svc][0]

        def ctr_id(svc):
            return [i for i, c in state()["containers"].items() if c["project"] == "parkio" and c["service"] == svc][0]

        def role_accepts(db, password):
            v = state()["roles"][db]
            head, keys = v.split("$", 1)[1].split("$", 1)
            iters, salt = head.split(":", 1)
            salted = hashlib.pbkdf2_hmac("sha256", password.encode(), base64.b64decode(salt), int(iters))
            return hashlib.sha256(hmac.new(salted, b"Client Key", hashlib.sha256).digest()).digest() == base64.b64decode(keys.split(":", 1)[0])

        def ups():
            p = os.path.join(W, "argv.log")
            return [u for u in (json.loads(l) for l in open(p)) if u[0] == "wrapper" and "up" in u] if os.path.exists(p) else []

        def bak(db=None):
            return sorted(f for f in os.listdir(os.path.join(L, "docker")) if f".bak-pgr-{db + '-' if db else ''}" in f)

        def pre_rotate(db, extra=None):
            rc1, out1 = tool("pre", "--db", db, extra=extra)
            rc2, out2 = tool("rotate", "--yes", "--db", db, extra=extra)
            values.append(env_val(db))
            return rc1, rc2, out1 + out2

        # ---- usage
        reset()
        for args, label in (([], "no command"), (["pre"], "pre without --db"), (["pre", "--db", "bogus"], "an unknown database"),
                            (["pre", "--yes", "--db", "media"], "pre --yes"), (["rotate", "--db", "media"], "rotate without --yes"),
                            (["rotate", "--yes"], "rotate without --db"), (["rollback", "--db", "media"], "rollback without --yes"),
                            (["redact", "--yes", "--db", "media"], "redact with --db"), (["redact"], "redact without --yes"),
                            (["status", "--db", "media"], "status with --db"), (["rotate", "--yes", "--db", "media", "x"], "an extra argument"),
                            (["rotate", "--yes", "--db"], "--db without a value")):
            rc, out = tool(*args)
            ok(rc == 2, f"U usage refusal: {label}", out)

        # ---- pre
        reset()
        rc, out = tool("pre", "--db", "media")
        ok(rc == 0 and "postgres rotation pre media: PASS" in out and "include docker/docker-compose.waitlist-ops-inbox.yml" in out, "P1 pre passes (media)", out)
        rc, out = tool("pre", "--db", "parking")
        ok(rc == 0 and "pre parking: PASS" in out, "P1b pre passes for the postgis database (parking)", out)
        for label, kw, marker, db, expect in (
                ("P2 a checkout other than R2", dict(checkout=later), None, "media", "FAIL the release checkout is clean at R2"),
                ("P3 a value shorter than 32 characters", dict(short="media"), None, "media", "FAIL the env file's POSTGRES_MEDIA_PASSWORD is at least 32 characters"),
                ("P4 another project's container carrying the value", dict(extra_carrier=True), None, "media", "FAIL the live containers carrying the value"),
                ("P5 another service of the model carrying the value", {}, "model-leak", "media", "FAIL in the model, exactly postgres-media and media-service"),
                ("P6 a service holding the value outside Env", dict(cmd_carrier="media"), None, "media", "FAIL the live containers carrying the value"),
                ("P8 a moved image tag", {}, "tag-moved", "media", "FAIL media-service: healthy, at the model's hash, running the model's image"),
                ("P9 a database that accepts any password", {}, "trust-all-media", "media", "FAIL the network login discriminates"),
                ("P10 a public API that does not answer 200", {}, "public-down", "media", "FAIL the public API answers 200"),
                ("P12 dockerd debug logging", {}, "daemon-debug", "media", "FAIL dockerd debug logging is off"),
                ("P14 a database logging statements", {}, "log-statement-all", "media", "FAIL postgres-media: log_statement is none"),
                ("P15 a service whose aggregate health is not 200", {}, "app-health-503", "media", "FAIL media-service: its aggregate health answers 200"),
                ("P17 a backup in managed mode", dict(mode="managed"), None, "media", "FAIL the nightly backup's env loader yields the value"),
                ("P18 a service without sessions", {}, None, "media", "FAIL media-service: sessions of the role from its address")):
            reset(**kw)
            if marker:
                mark(marker)
            if label.startswith("P18"):
                st = state()
                st["sessions"]["media"] = []
                json.dump(st, open(os.path.join(W, "state.json"), "w"))
            rc, out = tool("pre", "--db", db)
            ok(rc == 1 and expect in out, label + " fails", out)
        reset()
        st = state()
        [c for c in st["containers"].values() if c["service"] == "media-service"][0]["hash"] = "pending-other-change"
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("pre", "--db", "media")
        ok(rc == 1 and "FAIL media-service: healthy, at the model's hash" in out, "P7 a service off the model's hash fails", out)
        reset()
        mark("stale.env", env_text({db: secrets.token_urlsafe(33) for db in ORDER}))
        mark("backup-loader-stale")
        rc, out = tool("pre", "--db", "media")
        ok(rc == 1 and "FAIL the nightly backup's env loader yields the value" in out, "P16 a backup loader that yields another value fails", out)
        reset()
        st = state()
        [c for c in st["containers"].values() if c["service"] == "postgres-media"][0]["env"]["POSTGRES_PASSWORD"] = "x" * 44
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("pre", "--db", "media")
        ok(rc == 1 and "FAIL postgres-media: healthy, runs the value" in out, "P19 a postgres container without the env file's value fails", out)
        reset(gateway_inbox=False)
        rc, out = tool("pre", "--db", "gateway")
        ok(rc == 0 and "use the production file list only" in out and not [u for u in (json.loads(l) for l in open(os.path.join(W, "argv.log"))) if u[:2] == ["wrapper", "-f"]],
           "P20 a gateway created without the inbox overlay: pre passes, and no Compose call adds it", out)
        reset()
        rc, out = tool("pre", "--db", "media", extra={"PGR_HOST": "some-other-host"})
        ok(rc == 1 and "FAIL host some-other-host" in out, "P11 another host fails", out)
        reset()
        rc, out = tool("pre", "--db", "media", extra={"PGR_NOW": str(calendar.timegm(time.strptime("2026-10-13 03:40", "%Y-%m-%d %H:%M")))})
        ok(rc == 1 and "FAIL outside 03:00-04:30 UTC" in out, "P13 the backup window is refused", out)

        # ---- rotate refusals
        reset()
        rc, out = tool("rotate", "--yes", "--db", "media")
        ok(rc == 1 and "FAIL the latest pre for media passed within an hour" in out and env_val("media") == OLD["media"], "R1 rotate without a pre changes nothing", out)
        reset()
        tool("pre", "--db", "auth")
        rc, out = tool("rotate", "--yes", "--db", "media")
        ok(rc == 1 and "FAIL the latest pre for media passed within an hour" in out, "R1e a pre for another database does not count", out)
        reset()
        tool("pre", "--db", "media")
        open(ENV, "a").write("NEW=1\n")
        rc, out = tool("rotate", "--yes", "--db", "media")
        ok(rc == 1 and "FAIL the env file is unchanged since that pre" in out, "R1b an env change after pre blocks rotate", out)
        reset()
        tool("pre", "--db", "media")
        rec = [os.path.join(OUT, d, "pre.json") for d in os.listdir(OUT)][0]
        r = json.load(open(rec))
        r["stamp"] = time.strftime("%Y%m%dT%H%M%S000Z", time.gmtime(time.time() - 7200))
        open(rec, "w").write(json.dumps(r))
        rc, out = tool("rotate", "--yes", "--db", "media")
        ok(rc == 1 and "FAIL the latest pre for media passed within an hour" in out, "R1c a pre older than an hour blocks rotate", out)
        reset()
        tool("pre", "--db", "media")
        st = state()
        red = [i for i, c in st["containers"].items() if c["service"] == "redis"][0]
        st["containers"]["d" * 64] = st["containers"].pop(red)
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("rotate", "--yes", "--db", "media")
        ok(rc == 1 and "FAIL the containers are the ones pre recorded" in out, "R1d a container change after pre blocks rotate", out)

        # ---- one database
        reset()
        pg_before = ctr_id("postgres-media")
        rc1, rc2, out = pre_rotate("media")
        new = env_val("media")
        ok(rc1 == 0 and rc2 == 0 and "postgres rotation rotate media: PASS" in out, "R2 the rotation of one database passes", out)
        ok(new != OLD["media"] and len(new) == 44 and role_accepts("media", new) and not role_accepts("media", OLD["media"])
           and all(env_val(db) == OLD[db] for db in ORDER if db != "media"), "R2 the role and the env file hold a new 44-character value; no other database changed")
        app = ctr("media-service")
        ok(app["env"]["SPRING_DATASOURCE_PASSWORD"] == new and app["health"] == "healthy"
           and any(s["addr"] == app["ip"] for s in state()["sessions"]["media"]), "R2 media-service runs the new value with sessions from its new address")
        ok(ctr_id("postgres-media") == pg_before and ctr("postgres-media")["env"]["POSTGRES_PASSWORD"] == OLD["media"],
           "R2 postgres-media is unchanged and keeps the old value as initialisation metadata")
        ok([u[-1] for u in ups()] == ["media-service"] and all(u[1:3] == ["-f", "docker/docker-compose.waitlist-ops-inbox.yml"] for u in ups()),
           "R2 only media-service is recreated, with the inbox overlay", str(ups()))
        b = bak("media")
        ok(len(b) == 1 and OLD["media"] in open(os.path.join(L, "docker", b[0])).read() and oct(os.stat(os.path.join(L, "docker", b[0])).st_mode & 0o777) == "0o600",
           "R2 the run's backup holds the old value, mode 600")
        runs = [u for u in (json.loads(l) for l in open(os.path.join(W, "argv.log"))) if u[0] == "run"]
        ok(runs and all("--read-only" in u and u[u.index("--network") + 1] == "parkio-backend" and "PGR_HOST=postgres-media" in u for u in runs),
           "R2 every login is a fresh network login from a read-only container on the database's network")
        rec = json.load(open([os.path.join(OUT, d, "rotate.json") for d in os.listdir(OUT) if os.path.exists(os.path.join(OUT, d, "rotate.json"))][0]))
        ok("initialisation metadata" in rec.get("postgresContainer", ""), "R2 the record states the postgres container's stale POSTGRES_PASSWORD")
        rc, out = tool("rollback", "--yes", "--db", "media")
        ok(rc == 1 and "FAIL a rotation of media stopped after its backup" in out and env_val("media") == new, "R2b rollback after a passing rotation is refused", out)
        rc, out = tool("rollback", "--yes", "--db", "auth")
        ok(rc == 1 and "FAIL a rotation of auth stopped after its backup" in out, "R19 rollback of a database without a stopped rotation is refused", out)

        # ---- failures and rollbacks
        def stopped(label, marker=None, fault=None, expect="", db="media", extra=None):
            reset()
            tool("pre", "--db", db)
            if marker:
                mark(marker)
            rc, out = tool("rotate", "--yes", "--db", db, extra=dict(extra or {}, **({"PGR_FAULT": fault} if fault else {})))
            values.append(env_val(db))
            ok((rc == 99 if fault else rc == 1) and expect in out, f"{label}: the rotation stops", out)

        def rolled_back(label, db="media", clear=True):
            if clear:
                for f in os.listdir(W):
                    if f.startswith(("unhealthy-", "stale-env-", "collateral-", "trust-all-", "no-sessions-")) or f in (
                            "alter-fails", "preflight-fail", "backup-loader-stale", "app-health-503"):
                        os.remove(os.path.join(W, f))
            n_up = len(ups())
            rc, out = tool("rollback", "--yes", "--db", db)
            app = ctr(APPS[db])
            ok(rc == 0 and env_val(db) == OLD[db] and role_accepts(db, OLD[db]) and app["env"]["SPRING_DATASOURCE_PASSWORD"] == OLD[db]
               and app["health"] == "healthy" and f"The EXPOSED password is live again for {db}" in out,
               f"{label}: rollback restores the original password (role, env file, service), verifies it, and says the exposed password is back", out)
            return [u[-1] for u in ups()[n_up:]]

        stopped("R3 a failing preflight after the env write", marker="preflight-fail", expect="FAIL the release preflight passes")
        ok(not ups(), "R3 nothing was recreated")
        ok(rolled_back("R3") == [], "R3 rollback sets the role back and restores the env file; the service never moved")
        stopped("R4 an ALTER ROLE that fails", marker="alter-fails", expect="FAIL postgres-media: ALTER ROLE with the new password's verifier")
        ok(rolled_back("R4") == [] and env_val("media") == OLD["media"], "R4 nothing changed, so rollback recreates nothing")
        stopped("R5 an interruption after the role change", fault="after-role")
        ok(rolled_back("R5") == [], "R5 rollback sets the role back")
        stopped("R6 an interruption after the env write", fault="after-env-write")
        ok(rolled_back("R6") == [], "R6 rollback sets the role back and restores the env file")
        stopped("R7 an interruption after the service's recreate", fault="after-up")
        ok(rolled_back("R7") == ["media-service"], "R7 rollback also recreates the service")
        stopped("R8 an unhealthy service", marker="unhealthy-media-service", expect="FAIL media-service: a new healthy container at the model's hash")
        rolled_back("R8")
        stopped("R9 a service without the new value", marker="stale-env-media-service", expect="FAIL media-service: runs the new value")
        rolled_back("R9")
        stopped("R10 a service without fresh sessions", marker="no-sessions-media", expect="FAIL media-service: fresh sessions of the role from its new address")
        rolled_back("R10")
        reset()
        tool("pre", "--db", "media")
        mark("no-sessions-media")
        mark("ip-reuse")
        rc, out = tool("rotate", "--yes", "--db", "media")
        values.append(env_val("media"))
        ok(rc == 1 and "FAIL media-service: fresh sessions of the role from its new address" in out,
           "R10b a recreated service on its predecessor's address: the predecessor's older sessions do not count", out)
        rolled_back("R10b")
        stopped("R3b another service's model changing with the env write", marker="env-affects-web",
                expect="FAIL exactly postgres-media and media-service changed in the model")
        rolled_back("R3b")
        stopped("R24 a public API down at the end", marker="public-down", expect="FAIL the public API answers 200")
        stopped("R19b a stopped rotation of media", fault="after-env-write")
        env_before = open(ENV).read()
        rc, out = tool("rollback", "--yes", "--db", "auth")
        ok(rc == 1 and "FAIL a rotation of auth stopped after its backup" in out and open(ENV).read() == env_before,
           "R19b rollback of another database ignores media's stopped rotation and changes nothing", out)
        rolled_back("R19b")
        stopped("R25 a stopped rotation, then another env line changes", fault="after-env-write")
        open(ENV, "a").write("OTHER_CHANGE=1\n")
        rc, out = tool("rollback", "--yes", "--db", "media")
        ok(rc == 1 and "FAIL the backup differs from the env file only in the POSTGRES_MEDIA_PASSWORD line" in out and "OTHER_CHANGE=1" in open(ENV).read(),
           "R25 rollback refuses a backup that would undo another change", out)
        stopped("R11 another container changing at the service's recreate", marker="collateral-media-service",
                expect="FAIL every other container unchanged (postgres-media included)")
        rolled_back("R11")
        reset()
        mark("stale.env", env_text(OLD))
        mark("backup-loader-stale")
        tool("pre", "--db", "media")
        rc, out = tool("rotate", "--yes", "--db", "media")
        values.append(env_val("media"))
        ok(rc == 1 and "FAIL the nightly backup's env loader yields the new value" in out, "R12 a backup loader that does not pick up the new value stops the rotation", out)
        rolled_back("R12", clear=False)
        rc, out = tool("rollback", "--yes", "--db", "media")
        ok(rc == 1 and "rolled-back" in out, "R14 a second rollback is refused (rolled-back)", out)
        stopped("R18 a database that still accepts the old password after the role change", marker="trust-all-media",
                expect="FAIL a fresh network login: the new password logs in; the old one is refused")
        rolled_back("R18")
        stopped("R20 a service whose aggregate health is not 200", marker="app-health-503", expect="FAIL media-service: its aggregate health answers 200")
        rolled_back("R20")
        stopped("R15 a service left on the rotation's value", fault="after-up")
        mark("stale-env-media-service")
        rc, out = tool("rollback", "--yes", "--db", "media")
        ok(rc == 1 and "FAIL media-service: runs the original value, healthy, aggregate health 200" in out,
           "R15 a rollback that leaves the service on the rotation's value fails its end-state check", out)
        stopped("R16 a role that cannot be set back", fault="after-role")
        mark("alter-fails")
        rc, out = tool("rollback", "--yes", "--db", "media")
        ok(rc == 1 and "FAIL postgres-media: the role is set back to the original password" in out and "FAIL a fresh network login: the original password logs in" in out,
           "R16 a rollback whose role cannot be set back fails", out)
        stopped("R21 a backup loader that does not yield the original after rollback", fault="after-up")
        mark("stale.env", env_text({db: secrets.token_urlsafe(33) for db in ORDER}))
        mark("backup-loader-stale")
        rc, out = tool("rollback", "--yes", "--db", "media")
        ok(rc == 1 and "FAIL the nightly backup's env loader yields the original value" in out, "R21 a rollback the backup loader does not follow fails", out)
        stopped("R22 no fresh sessions after rollback", fault="after-up")
        mark("no-sessions-media")
        rc, out = tool("rollback", "--yes", "--db", "media")
        ok(rc == 1 and "FAIL media-service: fresh sessions of the role from its address, started after it" in out, "R22 a rollback without fresh sessions fails", out)
        stopped("R23 a public API down after rollback", fault="after-up")
        mark("public-down")
        rc, out = tool("rollback", "--yes", "--db", "media")
        ok(rc == 1 and "FAIL the public API answers 200" in out, "R23 a rollback with the public API down fails", out)
        stopped("R13 a stop before rollback without a backup", fault="after-up")
        os.remove(os.path.join(L, "docker", bak("media")[0]))
        n_up = len(ups())
        rc, out = tool("rollback", "--yes", "--db", "media")
        ok(rc == 1 and "FAIL the run's backup exists and is readable" in out and len(ups()) == n_up, "R13 rollback without the backup is refused and changes nothing", out)

        # ---- all ten, in order, then redact
        reset()
        rcs = [pre_rotate(db) for db in ORDER]
        ok(all(r1 == 0 and r2 == 0 for r1, r2, _ in rcs), "A1 all ten databases rotate in the procedure's order, one run each",
           "\n".join(o for _, _, o in rcs if "FAIL" in o))
        ok(all(role_accepts(db, env_val(db)) and not role_accepts(db, OLD[db]) and ctr(APPS[db])["env"]["SPRING_DATASOURCE_PASSWORD"] == env_val(db)
               and ctr(f"postgres-{db}")["env"]["POSTGRES_PASSWORD"] == OLD[db] for db in ORDER),
           "A1 every role, service and env line holds its new value; every postgres container keeps its old one as metadata")
        ok([u[-1] for u in ups()] == [APPS[db] for db in ORDER], "A1 exactly the ten services were recreated, in order", str([u[-1] for u in ups()]))
        cur = {db: env_val(db) for db in ORDER}
        mt = {p: os.stat(p).st_mtime_ns for p in BACKUPS + EXTRA}
        rc, out = tool("redact", "--yes")
        runb = [os.path.join(L, "docker", f) for f in bak()]
        ok(rc == 0 and len(runb) == 10 and all(not any(OLD[db] in open(p).read() for db in ORDER) for p in BACKUPS + EXTRA + runb)
           and all(MARKER in open(p).read() for p in BACKUPS + EXTRA + runb) and all(os.stat(p).st_mtime_ns == mt[p] for p in BACKUPS + EXTRA)
           and all(env_val(db) == cur[db] for db in ORDER),
           "X2 redact replaces all ten old values everywhere listed and in the runs' backups last; mtimes kept; the live env untouched", out)
        rc, out = tool("redact", "--yes")
        ok(rc == 0 and "redaction already complete" in out, "X5 a second redact reports completion", out)

        # ---- redact refusals, with two databases
        reset()
        tool("pre", "--db", "media", extra=TWO)
        tool("rotate", "--yes", "--db", "media", extra=TWO)
        values.append(env_val("media"))
        rc, out = tool("redact", "--yes", extra=TWO)
        ok(rc == 1 and "FAIL all 2 databases have a passing rotation" in out and OLD["media"] in open(BACKUPS[0]).read(), "X1 redact before every database rotated is refused", out)

        def two_rotated():
            reset()
            for db in ("media", "parking"):
                tool("pre", "--db", db, extra=TWO)
                tool("rotate", "--yes", "--db", db, extra=TWO)
                values.append(env_val(db))

        two_rotated()
        open(os.path.join(L, "stray.txt"), "w").write(OLD["parking"])
        rc, out = tool("redact", "--yes", extra=TWO)
        ok(rc == 1 and "stray.txt" in out and OLD["parking"] in open(os.path.join(L, "docker", bak("parking")[0])).read(),
           "X3 an unlisted copy fails the scan; the runs' backups keep the old values", out)
        two_rotated()
        cur = env_val("media")
        os.symlink(ENV, os.path.join(L, "docker", ".env.azure-hosted-beta.bak-live"))
        rc, out = tool("redact", "--yes", extra=TWO)
        ok(rc == 1 and "the live env file is never redacted" in out and env_val("media") == cur, "X6 the live env file is never redacted, even through a link", out)
        two_rotated()
        mark("trust-all-parking")
        rc, out = tool("redact", "--yes", extra=TWO)
        ok(rc == 1 and "FAIL parking: the old password is known (its run's backup) and refused" in out and OLD["parking"] in open(BACKUPS[0]).read(),
           "X7 redact while an old password still logs in is refused and changes nothing", out)

        # ---- no value anywhere
        archive_logs()
        text = "\n".join(outputs)
        for root, _, files in os.walk(OUT):
            for f in files:
                text += open(os.path.join(root, f), encoding="utf-8").read()
        ok(len(values) >= 20 and not any(v and v in text for v in values), f"no secret value in any output or record ({len(values)} values)")
        argv = open(os.path.join(W, "argv-all.log")).read()
        ok(argv.count("\n") > 200 and not any(v and v in argv for v in values), "no secret value in any docker or wrapper argument")
        envs = [json.loads(l) for l in open(os.path.join(W, "env-all.log"))]
        ok(envs and not any("LEAKY_SECRET" in e["env"] for e in envs), "no child saw a variable outside the allowlist")
    finally:
        server.shutdown()
        shutil.rmtree(W, ignore_errors=True)
    print(f"=== summary: {sum(results)} ok, {len(results) - sum(results)} FAIL ===")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
