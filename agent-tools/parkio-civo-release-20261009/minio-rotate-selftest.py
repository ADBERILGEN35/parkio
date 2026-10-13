#!/usr/bin/env python3
"""Self-test of minio-rotate.py, with fakes only.

The fakes are backed by one JSON state:
- **docker:** info, ps (with filters), inspect (and `--format` for the config_files label), image inspect, logs, and
  two kinds of child:
  - `run --rm -i --network <net> ... <mc image> -c "mc alias set t http://minio:9000 ... && { script; }"`. It reads
    the user and password from stdin and answers as MinIO would: a refused pair gets "The request signature we
    calculated does not match", an unreachable minio gets a connection error. Otherwise it runs the script against
    the stored objects (`mc ls t`, `mc ls --recursive --json`, `mc cat ... | sha256sum`);
  - `exec <media> curl ... /actuator/health`, which answers 200 while media-service's pair is minio's.
  Every argv and child environment is recorded.
- **The release checkout:** a git repository at R2.
  - Its fake wrapper renders minio (with the reviewed hardening unless the emergency override is given),
    minio-setup, media-service, the gateway, web and redis. Leading `-f` overlays are honoured.
  - `up -d` recreates one service; `up --no-start` creates one without starting it. The minio-data volume and its
    objects survive recreates.
  - Its fake preflight passes unless told otherwise.
- **The backup library:** a stand-in for scripts/lib/backup-common.sh whose parkio_backup_load_env sources the env
  file with `set -a`, as the real one does.
- **An HTTP server:** stands in for the public API.
Marker files inject faults; MRT_FAULT simulates interruptions.

Pass criteria:
- every expected result holds;
- no secret value (old, new or a probe's wrong value) appears in any output, record or docker/wrapper argument;
- no child sees a variable from outside the allowlist.

  python3 -I minio-rotate-selftest.py
"""
import calendar
import hashlib
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
TOOL = os.path.join(HERE, "minio-rotate.py")
OVERRIDE_SRC = os.path.join(HERE, "minio-emergency-override.yml")
MC_ID = "sha256:" + "a5" * 32
results = []

FAKE_DOCKER = r'''#!/usr/bin/env python3
import hashlib, json, os, re, sys, time
W = os.environ["MRT_W"]
a = sys.argv[1:]
open(os.path.join(W, "argv.log"), "a").write(json.dumps(a) + "\n")
open(os.path.join(W, "env.log"), "a").write(json.dumps({"argv0": a[0], "env": sorted(os.environ)}) + "\n")
P = os.path.join(W, "state.json")
st = json.load(open(P))
cs = st["containers"]
mark = lambda m: os.path.exists(os.path.join(W, m))
def minio():
    m = [c for c in cs.values() if c["project"] == "parkio" and c["service"] == "minio"]
    return m[0] if len(m) == 1 else None
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
                    "State": {"Status": c.get("status", "running"), "Health": {"Status": c["health"]} if c.get("health") else None,
                              "StartedAt": c.get("started", "2026-10-13T00:00:00.000000000Z")},
                    "HostConfig": c.get("host", {"ReadonlyRootfs": False, "CapDrop": None, "SecurityOpt": None, "Tmpfs": None}),
                    "Mounts": c.get("mounts", []), "NetworkSettings": {"Networks": {n: {} for n in c.get("nets", [])}}})
    print(json.dumps(out)); sys.exit(0)
if a[:2] == ["image", "inspect"]:
    ref = a[-1]
    iid = st["images"].get(ref) or (ref if ref in st["images"].values() else None)
    if iid and mark("tag-moved") and "media" in ref: iid = "sha256:" + "f" * 64
    if not iid: sys.exit(1)
    print(iid); sys.exit(0)
if a[0] == "logs":
    name = a[-1]
    if mark("media-auth-errors") and "media" in name:
        print("io.minio.errors.ErrorResponseException: SignatureDoesNotMatch")
    sys.exit(0)
if a[0] == "exec" and "curl" in a:
    me = [c for c in cs.values() if c["name"] == a[1]][0]
    m = minio()
    ok = m and m.get("status") == "running" and m["health"] == "healthy" and \
         me["env"].get("PARKIO_MEDIA_STORAGE_SECRET_KEY") == m["env"].get("MINIO_ROOT_PASSWORD") and \
         me["env"].get("PARKIO_MEDIA_STORAGE_ACCESS_KEY") == m["env"].get("MINIO_ROOT_USER")
    sys.stdout.write("503" if mark("media-health-503") or not ok else "200"); sys.exit(0)
if a[0] == "run":
    net = a[a.index("--network") + 1]
    script = a[-1]
    lines = sys.stdin.read().split("\n")
    user, pw = (lines + ["", ""])[:2]
    m = minio()
    if not m or net not in m.get("nets", []) or m.get("status") != "running" or m["health"] != "healthy":
        print("mc: <ERROR> Unable to initialize new alias from the provided credentials. dial tcp: lookup minio: no such host"); sys.exit(1)
    n = st.get("not_ready", 0)
    if n > 0:
        st["not_ready"] = n - 1; json.dump(st, open(P + ".t", "w")); os.replace(P + ".t", P)
        print("mc: <ERROR> Unable to initialize new alias from the provided credentials. Server not initialized yet, please try again."); sys.exit(1)
    good = user == m["env"].get("MINIO_ROOT_USER") and pw == m["env"].get("MINIO_ROOT_PASSWORD")
    good = (good or mark("minio-accepts-any")) and not mark("minio-rejects-all")
    if not good:
        print("mc: <ERROR> Unable to initialize new alias from the provided credentials. The request signature we calculated does not match"
              " the signature you provided. Check your key and signing method."); sys.exit(1)
    inner = script.split("&& {", 1)[1].rsplit(";", 1)[0].strip()
    objs = st["objects"]
    if inner.startswith("mc ls t >/dev/null"):
        print("LOGIN-OK"); sys.exit(0)
    if inner.startswith("mc ls --recursive --json"):
        if mark("listing-fails"):
            print("mc: <ERROR> Unable to list folder."); sys.exit(1)
        for k, o in sorted(objs.items()):
            print(json.dumps({"status": "success", "type": "file", "key": k, "size": o["size"], "etag": o["etag"]}))
        sys.exit(0)
    found = re.match(r"mc cat 't/[^/]+/(.+)' \| sha256sum", inner)
    if found and found.group(1) in objs:
        print(hashlib.sha256(objs[found.group(1)]["content"].encode()).hexdigest()); sys.exit(0)
    print("mc: unknown script"); sys.exit(1)
sys.exit(2)
'''

FAKE_WRAPPER = r'''#!/usr/bin/env python3
import hashlib, json, os, sys, time
W = os.environ["MRT_W"]
a = sys.argv[1:]
mark = lambda m: os.path.exists(os.path.join(W, m))
open(os.path.join(W, "argv.log"), "a").write(json.dumps(["wrapper"] + a) + "\n")
open(os.path.join(W, "env.log"), "a").write(json.dumps({"argv0": "wrapper", "env": sorted(os.environ)}) + "\n")
OVERLAYS = []
while a[:1] == ["-f"]:
    OVERLAYS.append(a[1]); a = a[2:]
INBOX = any(o.endswith("docker-compose.waitlist-ops-inbox.yml") for o in OVERLAYS)
EMERGENCY = any(o.endswith("minio-emergency-override.yml") for o in OVERLAYS)
env = {}
for line in open(os.environ["PARKIO_ENV_FILE"], encoding="utf-8"):
    if "=" in line and not line.startswith("#"):
        k, v = line.rstrip("\n").split("=", 1)
        if len(v) >= 2 and v[0] == v[-1] == '"':
            v = v[1:-1]
        env[k] = v
HARD = {"read_only": True, "cap_drop": ["ALL"], "security_opt": ["no-new-privileges:true"]}
def service(svc):
    user, pw = env.get("MINIO_ROOT_USER", "parkio"), env.get("MINIO_ROOT_PASSWORD", "")
    if svc == "minio":
        out = {"image": "quay.io/minio/minio:RELEASE.2024-09-13T20-26-02Z", "environment": {"MINIO_ROOT_USER": user, "MINIO_ROOT_PASSWORD": pw},
               "volumes": [{"type": "volume", "source": "minio-data", "target": "/data"}]}
        if not EMERGENCY:
            out.update(HARD, tmpfs=["/tmp:size=16m"])
        return out
    if svc == "minio-setup":
        return dict(HARD, tmpfs=["/tmp:size=8m"], image="quay.io/minio/mc:RELEASE.2024-09-16T17-43-14Z",
                    environment={"MINIO_ROOT_USER": user, "MINIO_ROOT_PASSWORD": pw, "MINIO_BUCKET": "parkio-media", "MC_CONFIG_DIR": "/tmp/.mc"})
    if svc == "media-service":
        return {"image": "ghcr.io/x/media-service", "environment": {"PARKIO_MEDIA_STORAGE_ACCESS_KEY": user, "PARKIO_MEDIA_STORAGE_SECRET_KEY": pw, "X": "1"}}
    if svc == "gateway-service":
        e = {"X": "1"}
        if INBOX: e["PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR"] = "/var/lib/parkio/waitlist-ops-inbox"
        return {"image": "ghcr.io/x/gateway-service", "environment": e}
    e = {"Y": "2"}
    if svc == "web" and mark("model-leak"): e["LEAK"] = pw
    if svc == "web" and mark("env-affects-web"): e["X"] = hashlib.sha256(pw.encode()).hexdigest()[:8]
    return {"image": "ghcr.io/x/" + svc, "environment": e}
SERVICES = ("minio", "minio-setup", "media-service", "gateway-service", "web", "redis")
def mhash(svc): return hashlib.sha256(json.dumps(service(svc), sort_keys=True).encode()).hexdigest()[:16]
def host(svc):
    s = service(svc)
    if not s.get("read_only") or mark("hardening-missing") or (svc == "minio-setup" and mark("setup-hardening-missing")):
        return {"ReadonlyRootfs": False, "CapDrop": None, "SecurityOpt": None, "Tmpfs": None}
    return {"ReadonlyRootfs": True, "CapDrop": ["ALL"], "SecurityOpt": ["no-new-privileges:true"], "Tmpfs": {t.split(":")[0]: t.split(":", 1)[1] for t in s["tmpfs"]}}
if a[:2] == ["config", "--format"]:
    print(json.dumps({"services": {s: service(s) for s in SERVICES}})); sys.exit(0)
if a[:2] == ["config", "--hash"]:
    print("\n".join(f"{s} {mhash(s)}" for s in SERVICES)); sys.exit(0)
if a[0] == "up":
    svc = a[-1]
    force = "--force-recreate" in a
    nostart = "--no-start" in a
    P = os.path.join(W, "state.json"); st = json.load(open(P))
    mine = [i for i, c in st["containers"].items() if c["project"] == "parkio" and c["service"] == svc]
    old = mine[0]
    if not force and len(mine) == 1 and st["containers"][old]["hash"] == mhash(svc):
        sys.exit(0)
    c = dict(st["containers"].pop(old))
    for extra in mine[1:]:
        st["containers"].pop(extra)
    if not mark("stale-env-" + svc):
        c["env"] = dict(service(svc)["environment"])
    c["hash"] = mhash(svc)
    c["host"] = host(svc)
    c["files"] = "/opt/parkio-release/docker/docker-compose.yml" + (",/opt/parkio-release/docker/docker-compose.waitlist-ops-inbox.yml" if INBOX and svc == "gateway-service" else "")
    c["started"] = time.strftime("%Y-%m-%dT%H:%M:%S.000000000Z", time.gmtime())
    if nostart:
        c["status"], c["health"] = "created", None
    else:
        c["status"] = "running"
        c["health"] = "unhealthy" if mark("unhealthy-" + svc) or (svc == "minio" and mark("hardened-fails") and c["host"]["ReadonlyRootfs"]) else "healthy"
    if svc == "minio":
        if mark("new-volume"):
            c["mounts"] = [{"Type": "volume", "Name": "parkio_minio-data-2", "Destination": "/data"}]
        if mark("objects-lost"):
            st["objects"] = {}
        if mark("minio-not-ready"):
            st["not_ready"] = 2
        if mark("content-changed"):
            for o in st["objects"].values():
                o["content"] = o["content"] + "-changed"
    if mark("collateral-" + svc):
        cad = [i for i, x in st["containers"].items() if x["service"] == "redis"][0]
        st["containers"]["c" * 64] = st["containers"].pop(cad)
    st["containers"][hashlib.sha256(f"{svc}{time.time()}".encode()).hexdigest()] = c
    json.dump(st, open(P + ".t", "w")); os.replace(P + ".t", P)
    sys.exit(0)
sys.exit(2)
'''

FAKE_BACKUP_LIB = r'''# stand-in for scripts/lib/backup-common.sh
parkio_backup_load_env() {
  local env_file="${1:-}"
  [ -e "$MRT_W/backup-loader-stale" ] && env_file="$MRT_W/stale.env"
  set -a
  . "${env_file}"
  set +a
}
'''


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
    W = tempfile.mkdtemp(prefix="mrt-")
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Api)
    Api.W = W
    threading.Thread(target=server.serve_forever, daemon=True).start()
    outputs = []
    try:
        R, L, OUT, BIN, TOOLS = (os.path.join(W, d) for d in ("release", "live", "out", "bin", "tools"))
        for d in (os.path.join(R, "scripts"), os.path.join(L, "docker"), os.path.join(L, "scripts", "lib"), BIN, TOOLS):
            os.makedirs(d)
        open(os.path.join(BIN, "docker"), "w").write(FAKE_DOCKER)
        os.chmod(os.path.join(BIN, "docker"), 0o755)
        for name, body in (("parkio-prod-compose.sh", FAKE_WRAPPER),
                           ("preflight-hosted-beta.sh", '#!/bin/sh\n[ -e "$MRT_W/preflight-fail" ] && exit 1\nexit 0\n')):
            p = os.path.join(R, "scripts", name)
            open(p, "w").write(body)
            os.chmod(p, 0o755)
        LIB = os.path.join(L, "scripts", "lib", "backup-common.sh")
        open(LIB, "w").write(FAKE_BACKUP_LIB)
        OVR = os.path.join(TOOLS, "minio-emergency-override.yml")
        shutil.copy(OVERRIDE_SRC, OVR)
        ovr_sha = hashlib.sha256(open(OVR, "rb").read()).hexdigest()
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
        OLD = secrets.token_urlsafe(33)
        values = [OLD]
        env = {"PATH": BIN + ":" + os.environ["PATH"], "HOME": W, "LANG": "C.UTF-8", "LEAKY_SECRET": "x", "MRT_TEST": "1", "MRT_W": W,
               "MRT_R2": r2, "MRT_HOST": socket.gethostname(), "MRT_RELEASE": R, "MRT_ENV_FILE": ENV, "MRT_OUT": OUT,
               "MRT_PUBLIC_PROBE": f"http://127.0.0.1:{server.server_address[1]}/public",
               "MRT_NOW": str(calendar.timegm(time.strptime("2026-10-13 15:00", "%Y-%m-%d %H:%M"))),
               "MRT_HEALTH_WAIT": "10", "MRT_LOGIN_WAIT": "10", "MRT_POLL": "0.1",
               "MRT_REDACT_GLOB": os.path.join(L, "docker", ".env.azure-hosted-beta.bak-*"),
               "MRT_REDACT_EXTRA": ":".join(EXTRA + [os.path.join(L, "never-there.env")]), "MRT_SCAN_ROOTS": L,
               "MRT_BACKUP_CWD": L, "MRT_BACKUP_LIB": LIB, "MRT_OVERRIDE": OVR, "MRT_OVERRIDE_SHA256": ovr_sha}

        def env_text(pw):
            return f'PARKIO_DOMAIN=parkio.dev\nMINIO_ROOT_USER=parkio\nMINIO_ROOT_PASSWORD="{pw}"\nMINIO_IMAGE=quay.io/minio/minio:RELEASE.2024-09-13T20-26-02Z\n'

        def archive_logs():
            for f in ("argv.log", "env.log"):
                src = os.path.join(W, f)
                if os.path.exists(src):
                    with open(os.path.join(W, f.replace(".log", "-all.log")), "a") as dst:
                        dst.write(open(src).read())
                    os.remove(src)

        def wrap(*args, overlay=True, emergency=False):
            pre = (["-f", "docker/docker-compose.waitlist-ops-inbox.yml"] if overlay else []) + (["-f", OVR] if emergency else [])
            p = subprocess.run([os.path.join(R, "scripts", "parkio-prod-compose.sh")] + pre + list(args), cwd=R, capture_output=True, text=True,
                               env={k: v for k, v in dict(env, PARKIO_ENV_FILE=ENV).items() if k != "LEAKY_SECRET"})
            return p.stdout

        def reset(checkout=r2, short=False, extra_carrier=False, cmd_carrier=False, gateway_inbox=True, drift=False, volume="parkio_minio-data"):
            for f in os.listdir(W):
                if f.startswith(("unhealthy-", "stale-env-", "collateral-")) or f in (
                        "tag-moved", "model-leak", "env-affects-web", "preflight-fail", "daemon-debug", "public-down", "minio-accepts-any",
                        "minio-rejects-all", "backup-loader-stale", "media-health-503", "media-auth-errors", "hardened-fails", "hardening-missing",
                        "new-volume", "objects-lost", "minio-not-ready", "stale.env", "listing-fails", "content-changed", "setup-hardening-missing"):
                    os.remove(os.path.join(W, f))
            archive_logs()
            shutil.rmtree(OUT, ignore_errors=True)
            for f in os.listdir(os.path.join(L, "docker")):
                os.remove(os.path.join(L, "docker", f))
            for f in os.listdir(L):
                if os.path.isfile(os.path.join(L, f)) or os.path.islink(os.path.join(L, f)):
                    os.remove(os.path.join(L, f))
            shutil.copy(OVERRIDE_SRC, OVR)
            git(R, "checkout", "-q", "--detach", checkout)
            pw = OLD[:20] if short else OLD
            for path in [ENV] + BACKUPS + EXTRA:
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                os.write(fd, env_text(pw).encode())
                os.close(fd)
            cs, images = {}, {}
            unhardened = {"ReadonlyRootfs": False, "CapDrop": None, "SecurityOpt": None, "Tmpfs": None}
            for i, (svc, ref) in enumerate((("minio", "quay.io/minio/minio:RELEASE.2024-09-13T20-26-02Z"), ("minio-setup", "quay.io/minio/mc:RELEASE.2024-09-16T17-43-14Z"),
                                            ("media-service", "ghcr.io/x/media-service"), ("gateway-service", "ghcr.io/x/gateway-service"),
                                            ("web", "ghcr.io/x/web"), ("redis", "ghcr.io/x/redis"))):
                iid = MC_ID if svc == "minio-setup" else f"sha256:{i:02d}{'0' * 62}"
                images[ref] = iid
                e = {"Y": "2"}
                if svc == "minio":
                    e = {"MINIO_ROOT_USER": "parkio", "MINIO_ROOT_PASSWORD": pw}
                if svc == "minio-setup":
                    e = {"MINIO_ROOT_USER": "parkio", "MINIO_ROOT_PASSWORD": pw, "MINIO_BUCKET": "parkio-media"}
                if svc == "media-service":
                    e = {"PARKIO_MEDIA_STORAGE_ACCESS_KEY": "parkio", "PARKIO_MEDIA_STORAGE_SECRET_KEY": pw, "X": "1"}
                if svc == "gateway-service":
                    e = {"X": "1"}
                    if gateway_inbox:
                        e["PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR"] = "/var/lib/parkio/waitlist-ops-inbox"
                c = {"name": f"parkio-{svc}" if svc.startswith("minio") else f"parkio-{svc}-1", "project": "parkio", "service": svc, "image": iid,
                     "ref": ref, "hash": "", "health": "healthy", "status": "running", "env": e, "host": dict(unhardened), "nets": ["parkio-backend"],
                     "files": "/opt/parkio-release/docker/docker-compose.yml" +
                              (",/opt/parkio-release/docker/docker-compose.waitlist-ops-inbox.yml" if svc == "gateway-service" and gateway_inbox else "")}
                if svc == "minio":
                    c["mounts"] = [{"Type": "volume", "Name": volume, "Destination": "/data"}]
                    if cmd_carrier:
                        c["cmd"] = ["server", "/data", f"--x={pw}"]
                if svc == "minio-setup":
                    c["status"], c["health"] = "exited", None
                cs[f"{i:02d}" * 32] = c
            if extra_carrier:
                cs["e" * 64] = {"name": "other-app", "project": "other", "service": "app", "image": "x", "ref": "x", "hash": "h", "health": "healthy",
                                "env": {"COPY": pw}}
            objs = {f"media/{i:03d}.jpg": {"size": 1000 + i, "etag": hashlib.md5(str(i).encode()).hexdigest(), "content": f"bytes-{i}"} for i in range(7)}
            st = {"containers": cs, "images": images, "objects": objs}
            json.dump(st, open(os.path.join(W, "state.json"), "w"))
            hashes = dict(l.split() for l in wrap("config", "--hash", "*", overlay=gateway_inbox).splitlines())
            emergency = dict(l.split() for l in wrap("config", "--hash", "*", overlay=gateway_inbox, emergency=True).splitlines())
            for c in st["containers"].values():
                if c["project"] == "parkio":
                    c["hash"] = emergency["minio"] if c["service"] == "minio" else hashes[c["service"]]
                    if c["service"] in ("minio-setup",):
                        c["hash"] = "pre-hardening-setup"
            if drift:
                [c for c in st["containers"].values() if c["service"] == "minio"][0]["hash"] = "drifted"
            json.dump(st, open(os.path.join(W, "state.json"), "w"))
            archive_logs()

        def tool(*args, extra=None):
            p = subprocess.run([sys.executable, "-I", TOOL] + list(args), capture_output=True, text=True, env=dict(env, **(extra or {})), timeout=600)
            outputs.append(p.stdout + p.stderr)
            return p.returncode, p.stdout + p.stderr

        def state():
            return json.load(open(os.path.join(W, "state.json")))

        def mark(m, text="x"):
            open(os.path.join(W, m), "w").write(text)

        def env_val():
            for line in open(ENV):
                if line.startswith("MINIO_ROOT_PASSWORD="):
                    return line.rstrip("\n").split("=", 1)[1].strip('"')

        def svc(name):
            return [c for c in state()["containers"].values() if c["project"] == "parkio" and c["service"] == name][0]

        def ups():
            p = os.path.join(W, "argv.log")
            return [u for u in (json.loads(l) for l in open(p)) if u[0] == "wrapper" and "up" in u] if os.path.exists(p) else []

        def bak():
            return [f for f in os.listdir(os.path.join(L, "docker")) if ".bak-mrt-" in f]

        def carriers_of(value):
            return sorted(c["name"] for c in state()["containers"].values() if value in json.dumps(c["env"]))

        # ---- usage
        reset()
        for args, label in (([], "no command"), (["pre", "--yes"], "pre --yes"), (["rotate"], "rotate without --yes"),
                            (["rollback"], "rollback without --yes"), (["rollback", "--unhardened"], "rollback --unhardened without --yes"),
                            (["redact"], "redact without --yes"), (["rotate", "--yes", "--unhardened"], "rotate --unhardened"),
                            (["rotate", "--yes", "x"], "an extra argument"), (["rollback", "--yes", "--yes"], "a repeated argument")):
            rc, out = tool(*args)
            ok(rc == 2, f"U usage refusal: {label}", out)

        # ---- pre
        reset()
        rc, out = tool("pre")
        ok(rc == 0 and "minio rotation pre: PASS" in out and "include docker/docker-compose.waitlist-ops-inbox.yml" in out, "P1 pre passes", out)
        for label, kw, marker, expect in (
                ("P2 a checkout other than R2", dict(checkout=later), None, "FAIL the release checkout is clean at R2"),
                ("P3 a password shorter than 32 characters", dict(short=True), None, "FAIL the env file's password is at least 32 characters"),
                ("P4 another project's container carrying the value", dict(extra_carrier=True), None, "FAIL the live containers carrying the value"),
                ("P5 another service of the model carrying the value", {}, "model-leak", "FAIL in the model, exactly minio, minio-setup and media-service"),
                ("P6 a carrier holding the value outside Env", dict(cmd_carrier=True), None, "FAIL the live containers carrying the value"),
                ("P8 a moved image tag", {}, "tag-moved", "FAIL media-service: healthy, at the model's hash, running the model's image"),
                ("P9 a minio that accepts any password", {}, "minio-accepts-any", "FAIL the login discriminates"),
                ("P10 a public API that does not answer 200", {}, "public-down", "FAIL the public API answers 200"),
                ("P12 dockerd debug logging", {}, "daemon-debug", "FAIL dockerd debug logging is off"),
                ("P15 a minio that differs from the model by more than the hardening", dict(drift=True), None,
                 "FAIL minio's hash equals the model's plus the emergency override"),
                ("P18 media-service's aggregate health not 200", {}, "media-health-503", "FAIL media-service's aggregate health answers 200"),
                ("P19 minio on another volume", dict(volume="parkio_other"), None, "FAIL minio runs the env file's password, is healthy, runs the model's image, on the minio-data volume")):
            reset(**kw)
            if marker:
                mark(marker)
            rc, out = tool("pre")
            ok(rc == 1 and expect in out, label + " fails", out)
        reset()
        st = state()
        [c for c in st["containers"].values() if c["service"] == "media-service"][0]["hash"] = "pending-other-change"
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL media-service: healthy, at the model's hash" in out, "P7 media-service off the model's hash fails", out)
        reset()
        open(OVR, "a").write("# edited\n")
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL the emergency override is the reviewed file" in out, "P14 an edited emergency override fails", out)
        reset()
        mark("stale.env", env_text(secrets.token_urlsafe(33)))
        mark("backup-loader-stale")
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL the nightly backup's env loader yields minio's user and password" in out, "P16 a backup loader that yields another password fails", out)
        reset()
        st = state()
        [c for c in st["containers"].values() if c["service"] == "media-service"][0]["env"]["PARKIO_MEDIA_STORAGE_SECRET_KEY"] = "x" * 44
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL media-service: healthy, at the model's hash, running the model's image, with minio's user and password" in out,
           "P17 media-service with a pair other than minio's fails", out)
        reset()
        st = state()
        [c for c in st["containers"].values() if c["service"] == "minio-setup"][0]["image"] = "sha256:" + "9" * 64
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL minio-setup runs the model's image" in out, "P21 a minio-setup on another image fails", out)
        reset()
        mark("listing-fails")
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL the media bucket's objects are listed" in out, "P22 a bucket that cannot be listed fails", out)
        reset(gateway_inbox=False)
        rc, out = tool("pre")
        ok(rc == 0 and "use the production file list only" in out and not [u for u in (json.loads(l) for l in open(os.path.join(W, "argv.log")))
                                                                           if u[:2] == ["wrapper", "-f"] and u[2].endswith("inbox.yml")],
           "P20 a gateway created without the inbox overlay: pre passes, and no Compose call adds it", out)
        reset()
        rc, out = tool("pre", extra={"MRT_HOST": "some-other-host"})
        ok(rc == 1 and "FAIL host some-other-host" in out, "P11 another host fails", out)
        reset()
        rc, out = tool("pre", extra={"MRT_NOW": str(calendar.timegm(time.strptime("2026-10-13 03:40", "%Y-%m-%d %H:%M")))})
        ok(rc == 1 and "FAIL outside 03:00-04:30 UTC" in out, "P13 the backup window is refused", out)

        # ---- rotate refusals
        reset()
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the latest pre passed within an hour" in out and env_val() == OLD, "R1 rotate without a pre changes nothing", out)
        reset()
        tool("pre")
        open(ENV, "a").write("NEW=1\n")
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the env file is unchanged since that pre" in out, "R1b an env change after pre blocks rotate", out)
        reset()
        tool("pre")
        rec = [os.path.join(OUT, d, "pre.json") for d in os.listdir(OUT)][0]
        r = json.load(open(rec))
        r["stamp"] = time.strftime("%Y%m%dT%H%M%S000Z", time.gmtime(time.time() - 7200))
        open(rec, "w").write(json.dumps(r))
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the latest pre passed within an hour" in out, "R1c a pre older than an hour blocks rotate", out)
        reset()
        tool("pre")
        st = state()
        red = [i for i, c in st["containers"].items() if c["service"] == "redis"][0]
        st["containers"]["d" * 64] = st["containers"].pop(red)
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the containers are the ones pre recorded" in out, "R1d a container change after pre blocks rotate", out)

        # ---- the full rotation
        reset()
        tool("pre")
        before = {s: svc(s) for s in ("minio", "minio-setup", "media-service")}
        rc, out = tool("rotate", "--yes")
        new = env_val()
        values.append(new)
        ok(rc == 0 and "minio rotation rotate: PASS" in out, "R2 the full rotation passes", out)
        mi, se, me = svc("minio"), svc("minio-setup"), svc("media-service")
        ok(new != OLD and len(new) == 44 and mi["env"]["MINIO_ROOT_PASSWORD"] == new and se["env"]["MINIO_ROOT_PASSWORD"] == new
           and me["env"]["PARKIO_MEDIA_STORAGE_SECRET_KEY"] == new, "R2 minio, minio-setup and media-service carry the new 44-character password")
        ok(mi["host"]["ReadonlyRootfs"] and mi["host"]["CapDrop"] == ["ALL"] and mi["image"] == before["minio"]["image"]
           and mi["mounts"] == before["minio"]["mounts"], "R2 minio runs the hardening on the same image and volume")
        ok(se["status"] == "created" and se["host"]["ReadonlyRootfs"], "R2 minio-setup is recreated with the hardening and not started")
        ok(carriers_of(OLD) == [] and len(state()["objects"]) == 7, "R2 no container carries the old value; the objects are all there")
        seq = [(("--no-start " if "--no-start" in u else "") + u[-1]) for u in ups()]
        ok(seq == ["minio", "media-service", "--no-start minio-setup"] and all(u[1:3] == ["-f", "docker/docker-compose.waitlist-ops-inbox.yml"] for u in ups())
           and not any("minio-emergency-override.yml" in x for u in ups() for x in u),
           "R2 the order is minio, media-service, then minio-setup without starting; the inbox overlay on every call, no emergency override", str(seq))
        b = bak()
        ok(len(b) == 1 and OLD in open(os.path.join(L, "docker", b[0])).read() and oct(os.stat(os.path.join(L, "docker", b[0])).st_mode & 0o777) == "0o600",
           "R2 the backup holds the old value, mode 600")
        runs = [u for u in (json.loads(l) for l in open(os.path.join(W, "argv.log"))) if u[0] == "run"]
        ok(runs and all(u[u.index("--network") + 1] == "parkio-backend" and MC_ID in u and "--read-only" in u for u in runs),
           "R2 every login runs from the mc image on minio's network, read-only")
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL a rotation stopped after its backup" in out and env_val() == new, "R2b rollback after a passing rotation is refused", out)

        # ---- failures and rollbacks
        reset()
        tool("pre")
        mark("preflight-fail")
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the release preflight passes" in out and env_val() == OLD and not ups(), "R3 a failing preflight restores the env file; nothing is recreated", out)
        reset()
        tool("pre")
        mark("env-affects-web")
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL exactly minio, minio-setup and media-service changed in the model" in out and env_val() == OLD,
           "R3b another service's model changing restores the env file", out)

        def stopped(label, marker=None, fault=None, expect="", text=None, untouched=()):
            reset()
            tool("pre")
            if marker:
                mark(marker, text or "x")
            ids = {s: [i for i, c in state()["containers"].items() if c["service"] == s] for s in untouched}
            rc, out = tool("rotate", "--yes", extra={"MRT_FAULT": fault} if fault else None)
            values.append(env_val())
            same = all([i for i, c in state()["containers"].items() if c["service"] == s] == ids[s] for s in untouched)
            ok((rc == 99 if fault else rc == 1) and expect in out and same,
               f"{label}: the rotation stops" + (f" before {', '.join(untouched)}" if untouched else ""), out)

        def rolled_back(label, unhardened=False, clear=True):
            if clear:
                for f in os.listdir(W):
                    if f.startswith(("unhealthy-", "stale-env-", "collateral-")) or f in ("media-auth-errors", "objects-lost", "hardening-missing", "new-volume",
                                                                                        "backup-loader-stale", "media-health-503", "setup-hardening-missing"):
                        os.remove(os.path.join(W, f))
            n_up = len(ups())
            rc, out = tool("rollback", "--yes", *(["--unhardened"] if unhardened else []))
            mi, se, me = svc("minio"), svc("minio-setup"), svc("media-service")
            ok(rc == 0 and env_val() == OLD and mi["env"]["MINIO_ROOT_PASSWORD"] == OLD and se["env"]["MINIO_ROOT_PASSWORD"] == OLD
               and me["env"]["PARKIO_MEDIA_STORAGE_SECRET_KEY"] == OLD and "The EXPOSED password is live again" in out,
               f"{label}: rollback restores the original password in every carrier, verifies it, and says the exposed password is back", out)
            return [u for u in ups()[n_up:]], out

        stopped("R4 a minio that does not come back under the hardening", marker="hardened-fails",
                expect="FAIL minio: a new healthy container, at the model's hash, on the same image and volume", untouched=("media-service", "minio-setup"))
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL minio: recreated and healthy" in out, "R4 a plain rollback keeps the hardening, so minio stays down: rollback fails", out)
        rb, out = rolled_back("R4b rollback --unhardened", unhardened=True, clear=False)
        mi = svc("minio")
        ok(any(u[-1] == "minio" and any(x.endswith("minio-emergency-override.yml") for x in u) for u in rb) and not mi["host"]["ReadonlyRootfs"]
           and len(state()["objects"]) == 7, "R4b --unhardened recreates minio with the emergency override (no hardening); the objects are all there", str(rb))
        stopped("R5 objects missing after minio's recreate", marker="objects-lost",
                expect="FAIL minio: every object listed before is there with the same size and ETag")
        stopped("R6 an interruption after the env write", fault="after-env-write")
        rb, out = rolled_back("R6")
        ok(rb == [], "R6 rollback after the env write recreates nothing (the carriers still run the original)", str(rb))
        stopped("R7 an interruption after minio's recreate", fault="after-up:minio")
        rb, out = rolled_back("R7")
        ok([u[-1] for u in rb] == ["minio"], "R7 rollback recreates only minio", str(rb))
        stopped("R8 an unhealthy media-service", marker="unhealthy-media-service", expect="FAIL media-service: a new healthy container at the model's hash",
                untouched=("minio-setup",))
        rb, out = rolled_back("R8")
        ok([u[-1] for u in rb] == ["minio", "media-service"], "R8 rollback recreates minio, then media-service", str(rb))
        stopped("R9 a media-service without the new value", marker="stale-env-media-service", expect="FAIL media-service: runs the new password and minio's user")
        rolled_back("R9")
        stopped("R10 MinIO authentication errors in media-service's log", marker="media-auth-errors",
                expect="FAIL media-service: no MinIO authentication error in its log since it started")
        rolled_back("R10")
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "rolled-back" in out, "R11 a second rollback is refused (rolled-back)", out)
        reset()
        mark("stale.env", env_text(OLD))
        mark("backup-loader-stale")
        tool("pre")
        rc, out = tool("rotate", "--yes")
        values.append(env_val())
        ok(rc == 1 and "FAIL the nightly backup's env loader yields the new password, and it logs in" in out,
           "R12 a backup loader that does not pick up the new password stops the rotation", out)
        rolled_back("R12", clear=False)
        stopped("R16 a minio recreated without the hardening", marker="hardening-missing", expect="FAIL minio: the reviewed hardening and the new password")
        rolled_back("R16")
        stopped("R17 a minio recreated on another volume", marker="new-volume", expect="FAIL minio: a new healthy container, at the model's hash, on the same image and volume")
        stopped("R19 another container changing at minio's recreate", marker="collateral-minio", expect="FAIL minio: every other container unchanged")
        reset()
        tool("pre")
        mark("minio-not-ready")
        rc, out = tool("rotate", "--yes")
        values.append(env_val())
        ok(rc == 0 and "minio rotation rotate: PASS" in out, "R18 a minio that is not ready for a moment after its recreate: the logins wait, the rotation passes", out)
        reset()
        tool("pre")
        mark("listing-fails")
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the media bucket's objects are listed before the cutover" in out and env_val() == OLD and not ups(),
           "R20 a bucket that cannot be listed before the cutover stops the rotation; nothing changed", out)
        stopped("R21 a minio that still accepts the old password after its recreate", marker="minio-accepts-any",
                expect="FAIL minio: the new password logs in; the old one is refused", untouched=("media-service",))
        stopped("R22 a media-service whose aggregate health is not 200", marker="media-health-503", expect="FAIL media-service: its aggregate health answers 200",
                untouched=("minio-setup",))
        stopped("R23 another container changing at media-service's recreate", marker="collateral-media-service",
                expect="FAIL media-service: every other container unchanged", untouched=("minio-setup",))
        stopped("R24 a minio-setup created without the new value", marker="stale-env-minio-setup",
                expect="FAIL minio-setup: a new container, created and not started, at the model's hash, with the hardening and the new password")
        rolled_back("R24")
        stopped("R32 a minio-setup created without the hardening", marker="setup-hardening-missing",
                expect="FAIL minio-setup: a new container, created and not started, at the model's hash, with the hardening and the new password")
        rolled_back("R32")
        stopped("R25 a public API that fails at the end", marker="public-down", expect="FAIL the public API answers 200")
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "FAIL the latest rotation passed" in out, "R25 a rotation whose end checks failed cannot be redacted", out)
        stopped("R26 a stop before an unhardened rollback with an edited override", fault="after-up:minio")
        open(OVR, "a").write("# edited\n")
        n_up = len(ups())
        rc, out = tool("rollback", "--yes", "--unhardened")
        ok(rc == 1 and "FAIL the emergency override is the reviewed file" in out and len(ups()) == n_up and env_val() != OLD,
           "R26 rollback --unhardened with an edited override is refused and changes nothing", out)
        stopped("R27 a media-service whose health is not 200 after rollback", fault="after-up:media-service")
        mark("media-health-503")
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL minio and media-service are healthy; media-service's aggregate health answers 200" in out,
           "R27 a rollback that leaves media-service's aggregate health off 200 fails", out)
        stopped("R28 objects lost at the rollback's recreate", fault="after-up:minio")
        mark("objects-lost")
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL the media bucket keeps at least the object count pre recorded" in out, "R28 a rollback that loses objects fails", out)
        stopped("R29 a backup loader that does not yield the original after rollback", fault="after-up:minio")
        mark("stale.env", env_text(secrets.token_urlsafe(33)))
        mark("backup-loader-stale")
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL the nightly backup's env loader yields the original password" in out, "R29 a rollback the backup loader does not follow fails", out)
        stopped("R30 a public API down after rollback", fault="after-up:minio")
        mark("public-down")
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL the public API answers 200" in out, "R30 a rollback with the public API down fails", out)
        stopped("R31 object content that changes at minio's recreate", marker="content-changed",
                expect="FAIL minio: every object listed before is there with the same size and ETag; the samples read back the same", untouched=("media-service",))
        stopped("R14 a carrier left on the rotation's value", fault="after-up:media-service")
        mark("stale-env-media-service")
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL every carrier runs the original password" in out, "R14 a rollback that leaves a carrier on the rotation's value fails its end-state check", out)
        stopped("R15 a minio that refuses the original password after rollback", fault="after-up:minio")
        mark("minio-rejects-all")
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL the original password logs in; the rotation's password is refused" in out, "R15 a rollback whose original password does not log in fails", out)
        stopped("R13 a stop before rollback without a backup", fault="after-up:minio")
        os.remove(os.path.join(L, "docker", bak()[0]))
        n_up = len(ups())
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL the rotation's backup exists and is readable" in out and len(ups()) == n_up, "R13 rollback without the backup is refused and changes nothing", out)

        # ---- redact
        reset()
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "FAIL the latest rotation passed" in out and OLD in open(BACKUPS[0]).read(), "X1 redact before a rotation is refused", out)
        reset()
        tool("pre")
        tool("rotate", "--yes")
        cur = env_val()
        values.append(cur)
        rbak = os.path.join(L, "docker", bak()[0])
        mt = {p: os.stat(p).st_mtime_ns for p in BACKUPS + EXTRA}
        rc, out = tool("redact", "--yes")
        ok(rc == 0 and all(OLD not in open(p).read() and MARKER in open(p).read() for p in BACKUPS + EXTRA + [rbak])
           and all(os.stat(p).st_mtime_ns == mt[p] for p in BACKUPS + EXTRA) and env_val() == cur,
           "X2 redact replaces the old value everywhere listed and in the rotation's backup last; mtimes kept; the live env untouched", out)
        rc, out = tool("redact", "--yes")
        ok(rc == 0 and "redaction already complete" in out, "X5 a second redact reports completion", out)
        reset()
        tool("pre")
        tool("rotate", "--yes")
        values.append(env_val())
        rbak = os.path.join(L, "docker", bak()[0])
        open(os.path.join(L, "stray.txt"), "w").write(OLD)
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "stray.txt" in out and OLD in open(rbak).read(), "X3 an unlisted copy fails the scan; the backup keeps the old value", out)
        reset()
        tool("pre")
        tool("rotate", "--yes")
        values.append(env_val())
        rbak = os.path.join(L, "docker", bak()[0])
        open(EXTRA[1], "w").write(env_text(env_val()))
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "holds the new value; left alone" in out and OLD in open(rbak).read(), "X4 a listed file holding the new value is left alone", out)
        reset()
        tool("pre")
        tool("rotate", "--yes")
        values.append(env_val())
        cur = env_val()
        os.symlink(ENV, os.path.join(L, "docker", ".env.azure-hosted-beta.bak-live"))
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "the live env file is never redacted" in out and env_val() == cur, "X6 the live env file is never redacted, even through a link", out)
        reset()
        tool("pre")
        tool("rotate", "--yes")
        values.append(env_val())
        mark("minio-accepts-any")
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "FAIL the old value is known (the rotation's backup) and refused" in out and OLD in open(BACKUPS[0]).read(),
           "X7 redact while the old value still logs in is refused and changes nothing", out)

        # ---- no value anywhere
        archive_logs()
        text = "\n".join(outputs)
        for root, _, files in os.walk(OUT):
            for f in files:
                text += open(os.path.join(root, f), encoding="utf-8").read()
        ok(len(values) >= 10 and not any(v and v in text for v in values), f"no secret value in any output or record ({len(values)} values)")
        argv = open(os.path.join(W, "argv-all.log")).read()
        ok(argv.count("\n") > 100 and not any(v and v in argv for v in values), "no secret value in any docker or wrapper argument")
        envs = [json.loads(l) for l in open(os.path.join(W, "env-all.log"))]
        ok(envs and not any("LEAKY_SECRET" in e["env"] for e in envs), "no child saw a variable outside the allowlist")
    finally:
        server.shutdown()
        shutil.rmtree(W, ignore_errors=True)
    print(f"=== summary: {sum(results)} ok, {len(results) - sum(results)} FAIL ===")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
