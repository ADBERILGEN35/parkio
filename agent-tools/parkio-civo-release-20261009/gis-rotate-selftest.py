#!/usr/bin/env python3
"""Self-test of gis-rotate.py, with fakes only.

The fakes are backed by one JSON state:
- **docker:** ps (with filters), inspect (and `--format` for the config_files label), image inspect, info, and
  `exec -i <gateway> curl ... -H @- ... http://<service>:<port><path>`.
  - The exec reads the X-Gateway-Auth header from stdin and answers as the target's GatewayAuthFilter would: its own
    secret and its ACCEPTED list are accepted, giving 200 for auth's public path, 404 for parking and 500 for the other
    seven (their catch-all handler, as in production); anything else gets 401 GATEWAY_AUTH_REQUIRED.
  - Every argv and child environment is recorded.
- **The release checkout:** a git repository at R2.
  - Its fake wrapper renders ten app services that share the secret and ACCEPTED, the way the env anchor does, plus
    web, caddy and redis without them. Compose-style unquoting is applied, leading `-f` overlays are stripped, and
    `up` recreates one service.
  - Its fake preflight passes unless told otherwise.
- **An HTTP server:** stands in for the public API; it returns 200 when auth accepts the gateway's secret.
Marker files inject faults; GIS_FAULT simulates interruptions. After every recreate the fake wrapper checks every
ordered pair of app services, as if each sent its secret to every downstream service, and logs each pair whose
receiver would refuse it. That is a superset of the real calls, so the rotation and every rollback must log nothing.

Pass criteria:
- every expected result holds;
- no secret value (old, new or a probe's wrong value) appears in any output, record or docker/wrapper argument;
- no child sees a variable from outside the allowlist.

  python3 -I gis-rotate-selftest.py
"""
import calendar
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
TOOL = os.path.join(HERE, "gis-rotate.py")
DOWNSTREAM = ("analytics-service", "ai-validation-service", "moderation-service", "notification-service", "gamification-service",
              "media-service", "user-service", "parking-service", "auth-service")
APPS = DOWNSTREAM + ("gateway-service",)
PORTS = {"gateway-service": 8080, "auth-service": 8081, "user-service": 8082, "parking-service": 8083, "media-service": 8084,
         "gamification-service": 8085, "notification-service": 8086, "moderation-service": 8087, "ai-validation-service": 8088,
         "analytics-service": 8089}
results = []

FAKE_DOCKER = r'''#!/usr/bin/env python3
import json, os, sys
W = os.environ["GIS_W"]
a = sys.argv[1:]
open(os.path.join(W, "argv.log"), "a").write(json.dumps(a) + "\n")
open(os.path.join(W, "env.log"), "a").write(json.dumps({"argv0": a[0], "env": sorted(os.environ)}) + "\n")
st = json.load(open(os.path.join(W, "state.json")))
cs = st["containers"]
mark = lambda m: os.path.exists(os.path.join(W, m))
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
                    "State": {"Status": "running", "Health": {"Status": c["health"]}}})
    print(json.dumps(out)); sys.exit(0)
if a[:2] == ["image", "inspect"]:
    iid = st["images"].get(a[-1])
    if iid and mark("tag-moved") and "user" in a[-1]: iid = "sha256:" + "f" * 64
    if not iid: sys.exit(1)
    print(iid); sys.exit(0)
if a[0] == "exec" and "curl" in a:
    url = a[-1]
    host, _, rest = url.split("//", 1)[1].partition(":")
    port, _, path = rest.partition("/")
    target = [c for c in cs.values() if c["project"] == "parkio" and c["service"] == host]
    hdr = sys.stdin.read()
    value = hdr.split("X-Gateway-Auth:", 1)[1].strip() if "X-Gateway-Auth:" in hdr else ""
    if not target or mark("service-down-" + host):
        sys.stdout.write("\n000"); sys.exit(7)                 # curl -w writes no trailing newline
    env = target[0]["env"]
    ok = value and (value == env.get("PARKIO_GATEWAY_INTERNAL_SECRET") or value in [x for x in env.get("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS", "").split(",") if x])
    ok = ok or mark("filter-open-" + host) or (mark("keeps-old-" + host) and value == st.get("first_secret"))
    ok = ok and not mark("rejects-all-" + host)
    if not ok:
        sys.stdout.write('{"code":"GATEWAY_AUTH_REQUIRED"}\n401')
    elif host == "auth-service":
        sys.stdout.write('{"mode":"invite"}\n200')
    else:
        sys.stdout.write('{"code":"NOT_FOUND"}\n404' if host == "parking-service" else '{"code":"INTERNAL_ERROR"}\n500')
    sys.exit(0)
sys.exit(2)
'''

FAKE_WRAPPER = r'''#!/usr/bin/env python3
import hashlib, json, os, sys, time
W = os.environ["GIS_W"]
a = sys.argv[1:]
mark = lambda m: os.path.exists(os.path.join(W, m))
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
            v = v[1:-1].replace("\\\\", "\\")
        env[k] = v
APPS = ("analytics-service", "ai-validation-service", "moderation-service", "notification-service", "gamification-service",
        "media-service", "user-service", "parking-service", "auth-service", "gateway-service")
PORTS = {"gateway-service": 8080, "auth-service": 8081, "user-service": 8082, "parking-service": 8083, "media-service": 8084,
         "gamification-service": 8085, "notification-service": 8086, "moderation-service": 8087, "ai-validation-service": 8088,
         "analytics-service": 8089}
def service(svc):
    if svc in APPS:
        e = {"PARKIO_GATEWAY_INTERNAL_SECRET": env.get("PARKIO_GATEWAY_INTERNAL_SECRET", ""),
             "PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS": env.get("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS", ""), "X": "1"}
        if svc == "gateway-service" and INBOX: e["PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR"] = "/var/lib/parkio/waitlist-ops-inbox"
        out = {"environment": e, "healthcheck": {"test": ["CMD", "curl", "-fsS", f"http://localhost:{PORTS[svc]}/actuator/health/readiness"]}}
        if svc != "gamification-service":
            out["image"] = "ghcr.io/x/" + svc
        return out
    e = {"Y": "2"}
    if svc == "web" and mark("model-leak"): e["LEAK"] = env.get("PARKIO_GATEWAY_INTERNAL_SECRET", "")
    if svc == "web" and mark("env-affects-web"): e["X"] = hashlib.sha256(env.get("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS", "").encode()).hexdigest()[:8]
    return {"image": "ghcr.io/x/" + svc, "environment": e}
SERVICES = APPS + ("web", "caddy", "redis")
def mhash(svc): return hashlib.sha256(json.dumps(service(svc), sort_keys=True).encode()).hexdigest()[:16]
if a[:2] == ["config", "--format"]:
    print(json.dumps({"services": {s: service(s) for s in SERVICES}})); sys.exit(0)
if a[:2] == ["config", "--hash"]:
    print("\n".join(f"{s} {mhash(s)}" for s in SERVICES)); sys.exit(0)
if a[:4] == ["up", "-d", "--no-build", "--no-deps"]:
    svc = a[-1]
    force = "--force-recreate" in a
    P = os.path.join(W, "state.json"); st = json.load(open(P))
    mine = [i for i, c in st["containers"].items() if c["project"] == "parkio" and c["service"] == svc]
    old = mine[0]
    if not force and len(mine) == 1 and st["containers"][old]["hash"] == mhash(svc):
        sys.exit(0)
    c = dict(st["containers"].pop(old))
    for extra in mine[1:]:
        st["containers"].pop(extra)                         # Compose converges to one container
    if not mark("stale-env-" + svc):
        c["env"] = dict(service(svc)["environment"])
    c["hash"] = mhash(svc)
    c["files"] = "/opt/parkio-release/docker/docker-compose.yml" + (",/opt/parkio-release/docker/docker-compose.waitlist-ops-inbox.yml" if INBOX and svc == "gateway-service" else "")
    c["health"] = "unhealthy" if mark("unhealthy-" + svc) else "healthy"
    if mark("collateral-" + svc):
        cad = [i for i, x in st["containers"].items() if x["service"] == "caddy"][0]
        st["containers"]["c" * 64] = st["containers"].pop(cad)
    if svc == "gateway-service" and mark("public-down-after-gateway"):
        open(os.path.join(W, "public-down"), "w").write("x")
    st["containers"][hashlib.sha256(f"{svc}{time.time()}".encode()).hexdigest()] = c
    apps = {x["service"]: x["env"] for x in st["containers"].values() if x["project"] == "parkio" and x["service"] in APPS}
    takes = lambda e: {e.get("PARKIO_GATEWAY_INTERNAL_SECRET")} | {v for v in e.get("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS", "").split(",") if v}
    bad = [[f, t] for f in apps for t in apps if t not in (f, "gateway-service") and apps[f].get("PARKIO_GATEWAY_INTERNAL_SECRET") not in takes(apps[t])]
    if bad:
        open(os.path.join(W, "refused-edges.log"), "a").write(json.dumps({"after": svc, "refused": bad}) + "\n")
    json.dump(st, open(P + ".t", "w")); os.replace(P + ".t", P)
    sys.exit(0)
sys.exit(2)
'''


def ok(cond, label, extra=""):
    results.append(bool(cond))
    print(("ok   " if cond else "FAIL ") + label + ("" if cond else f"\n{extra[-2500:]}"))


class Api(http.server.BaseHTTPRequestHandler):
    W = None

    def log_message(self, *a):
        pass

    def do_GET(self):
        st = json.load(open(os.path.join(self.W, "state.json")))
        cs = st["containers"].values()
        gw = [c for c in cs if c["service"] == "gateway-service"][0]["env"]
        auth = [c for c in cs if c["service"] == "auth-service"][0]["env"]
        sent = gw["PARKIO_GATEWAY_INTERNAL_SECRET"]
        accepted = sent == auth["PARKIO_GATEWAY_INTERNAL_SECRET"] or sent in [x for x in auth["PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS"].split(",") if x]
        status = 200 if accepted and not os.path.exists(os.path.join(self.W, "public-down")) else 401
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
    W = tempfile.mkdtemp(prefix="gis-")
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Api)
    Api.W = W
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        R, L, OUT, BIN = (os.path.join(W, d) for d in ("release", "live", "out", "bin"))
        for d in (os.path.join(R, "scripts"), os.path.join(L, "docker"), BIN):
            os.makedirs(d)
        open(os.path.join(BIN, "docker"), "w").write(FAKE_DOCKER)
        os.chmod(os.path.join(BIN, "docker"), 0o755)
        for name, body in (("parkio-prod-compose.sh", FAKE_WRAPPER),
                           ("preflight-hosted-beta.sh", '#!/bin/sh\n[ -e "$GIS_W/preflight-fail" ] && exit 1\nexit 0\n')):
            p = os.path.join(R, "scripts", name)
            open(p, "w").write(body)
            os.chmod(p, 0o755)
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
        OLD = secrets.token_urlsafe(48)
        values = [OLD]
        env = {"PATH": BIN + ":" + os.environ["PATH"], "HOME": W, "LANG": "C.UTF-8", "LEAKY_SECRET": "x", "GIS_TEST": "1", "GIS_W": W,
               "GIS_R2": r2, "GIS_HOST": socket.gethostname(), "GIS_RELEASE": R, "GIS_ENV_FILE": ENV, "GIS_OUT": OUT,
               "GIS_PUBLIC_PROBE": f"http://127.0.0.1:{server.server_address[1]}/public",
               "GIS_NOW": str(calendar.timegm(time.strptime("2026-10-13 15:00", "%Y-%m-%d %H:%M"))),
               "GIS_HEALTH_WAIT": "10", "GIS_POLL": "0.1", "GIS_REDACT_GLOB": os.path.join(L, "docker", ".env.azure-hosted-beta.bak-*"),
               "GIS_REDACT_EXTRA": ":".join(EXTRA + [os.path.join(L, "never-there.env")]), "GIS_SCAN_ROOTS": L}
        outputs = []

        def env_text(secret, accepted=""):
            return (f'PARKIO_DOMAIN=parkio.dev\nPARKIO_GATEWAY_INTERNAL_SECRET="{secret}"\nPARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS={accepted}\n'
                    'PARKIO_WAITLIST_HASH_SECRET="w4itlist-hash-secret-0123456789-0123456789"\n')

        def archive_logs():
            for f in ("argv.log", "env.log"):
                src = os.path.join(W, f)
                if os.path.exists(src):
                    with open(os.path.join(W, f.replace(".log", "-all.log")), "a") as dst:
                        dst.write(open(src).read())
                    os.remove(src)

        def reset(checkout=r2, accepted="", extra_carrier=False, cmd_carrier=False, gateway_inbox=True, app_cmd_carrier=False):
            for f in os.listdir(W):
                if f.startswith(("unhealthy-", "stale-env-", "collateral-", "filter-open-", "service-down-", "keeps-old-", "rejects-all-")) or f in (
                        "tag-moved", "model-leak", "env-affects-web", "preflight-fail", "daemon-debug", "public-down", "public-down-after-gateway",
                        "refused-edges.log"):
                    os.remove(os.path.join(W, f))
            archive_logs()
            shutil.rmtree(OUT, ignore_errors=True)
            for f in os.listdir(os.path.join(L, "docker")):
                os.remove(os.path.join(L, "docker", f))
            for f in os.listdir(L):
                if os.path.isfile(os.path.join(L, f)):
                    os.remove(os.path.join(L, f))
            git(R, "checkout", "-q", "--detach", checkout)
            for path, text in [(ENV, env_text(OLD, accepted))] + [(p, env_text(OLD)) for p in BACKUPS + EXTRA]:
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                os.write(fd, text.encode())
                os.close(fd)
            cs = {}
            for i, svc in enumerate(APPS + ("web", "caddy", "redis")):
                e = ({"PARKIO_GATEWAY_INTERNAL_SECRET": OLD, "PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS": accepted, "X": "1"} if svc in APPS else {"Y": "2"})
                if svc == "gateway-service" and gateway_inbox:
                    e["PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR"] = "/var/lib/parkio/waitlist-ops-inbox"
                ref = "parkio-gamification-service" if svc == "gamification-service" else "ghcr.io/x/" + svc
                cs[f"{i:02d}" * 32] = {"name": f"parkio-{svc}-1", "project": "parkio", "service": svc, "image": f"sha256:{i:02d}{'0' * 62}",
                                       "ref": ref, "hash": "", "health": "healthy", "env": e,
                                       "files": "/opt/parkio-release/docker/docker-compose.yml" +
                                                (",/opt/parkio-release/docker/docker-compose.waitlist-ops-inbox.yml" if svc == "gateway-service" and gateway_inbox else "")}
            if extra_carrier:
                cs["e" * 64] = {"name": "other-app", "project": "other", "service": "app", "image": "x", "ref": "x", "hash": "h", "health": "healthy",
                                "env": {"COPY": OLD}}
            if cmd_carrier:
                cs["a" * 64] = {"name": "cmd-app", "project": "other", "service": "app", "image": "x", "ref": "x", "hash": "h", "health": "healthy",
                                "env": {}, "cmd": ["sh", "-c", f"use {OLD}"]}
            if app_cmd_carrier:
                [c for c in cs.values() if c["service"] == "user-service"][0]["cmd"] = ["sh", "-c", f"use {OLD}"]
            images = {c["ref"]: c["image"] for c in cs.values() if c["project"] == "parkio"}
            st = {"containers": cs, "images": images, "first_secret": OLD}
            json.dump(st, open(os.path.join(W, "state.json"), "w"))
            overlay = ["-f", "docker/docker-compose.waitlist-ops-inbox.yml"] if gateway_inbox else []
            p = subprocess.run([os.path.join(R, "scripts", "parkio-prod-compose.sh")] + overlay + ["config", "--hash", "*"],
                               cwd=R, capture_output=True, text=True, env={k: v for k, v in dict(env, PARKIO_ENV_FILE=ENV).items() if k != "LEAKY_SECRET"})
            hashes = dict(l.split() for l in p.stdout.splitlines())
            for c in st["containers"].values():
                if c["project"] == "parkio":
                    c["hash"] = hashes[c["service"]]
            json.dump(st, open(os.path.join(W, "state.json"), "w"))
            archive_logs()

        def tool(*args, extra=None):
            p = subprocess.run([sys.executable, "-I", TOOL] + list(args), capture_output=True, text=True, env=dict(env, **(extra or {})), timeout=600)
            outputs.append(p.stdout + p.stderr)
            return p.returncode, p.stdout + p.stderr

        def state():
            return json.load(open(os.path.join(W, "state.json")))

        def mark(m):
            open(os.path.join(W, m), "w").write("x")

        def env_val(key):
            for line in open(ENV):
                if line.startswith(key + "="):
                    return line.rstrip("\n").split("=", 1)[1].strip('"')

        def app_env():
            return {c["service"]: c["env"] for c in state()["containers"].values() if c["service"] in APPS}

        def ups():
            p = os.path.join(W, "argv.log")
            return [u for u in (json.loads(l) for l in open(p)) if u[0] == "wrapper" and "up" in u] if os.path.exists(p) else []

        def bak():
            return [f for f in os.listdir(os.path.join(L, "docker")) if ".bak-gis-" in f]

        def refused_edges():
            p = os.path.join(W, "refused-edges.log")
            return open(p).read() if os.path.exists(p) else ""

        def operator_up(svc):
            subprocess.run([os.path.join(R, "scripts", "parkio-prod-compose.sh"), "-f", "docker/docker-compose.waitlist-ops-inbox.yml",
                            "up", "-d", "--no-build", "--no-deps", svc], cwd=R, capture_output=True, text=True,
                           env={k: v for k, v in dict(env, PARKIO_ENV_FILE=ENV).items() if k != "LEAKY_SECRET"})

        # ---- usage
        reset()
        for args, label in (([], "no command"), (["pre", "--yes"], "pre --yes"), (["rotate"], "rotate without --yes"),
                            (["rollback"], "rollback without --yes"), (["redact"], "redact without --yes"), (["rotate", "--yes", "x"], "an extra argument")):
            rc, out = tool(*args)
            ok(rc == 2, f"U usage refusal: {label}", out)

        # ---- pre
        reset()
        rc, out = tool("pre")
        ok(rc == 0 and "gateway secret rotation pre: PASS" in out and "include docker/docker-compose.waitlist-ops-inbox.yml" in out, "P1 pre passes", out)
        for label, kw, marker, expect in (
                ("P2 a checkout other than R2", dict(checkout=later), None, "FAIL the release checkout is clean at R2"),
                ("P3 a non-empty ACCEPTED list", dict(accepted="leftover-0123456789-0123456789-0123456789"), None, "FAIL the env file holds the secret"),
                ("P4 another project's container carrying the value", dict(extra_carrier=True), None, "FAIL the live containers carrying the value"),
                ("P5 another service of the model carrying the value", {}, "model-leak", "FAIL in the model, exactly the ten app services carry"),
                ("P6 a container carrying the value outside Env", dict(cmd_carrier=True), None, "FAIL the live containers carrying the value"),
                ("P15 an app service's own container carrying the value outside Env", dict(app_cmd_carrier=True), None,
                 "FAIL the live containers carrying the value"),
                ("P8 a moved image tag", {}, "tag-moved", "FAIL user-service: running, healthy, at the model's hash"),
                ("P9 a service whose filter accepts anything", {}, "filter-open-media-service", "FAIL media-service: the probe discriminates"),
                ("P10 a public API that does not answer 200", {}, "public-down", "FAIL the public API answers 200"),
                ("P12 dockerd debug logging", {}, "daemon-debug", "FAIL dockerd debug logging is off")):
            reset(**kw)
            if marker:
                mark(marker)
            rc, out = tool("pre")
            ok(rc == 1 and expect in out, label + " fails", out)
        reset()
        st = state()
        [c for c in st["containers"].values() if c["service"] == "notification-service"][0]["hash"] = "pending-other-change"
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL notification-service: running, healthy, at the model's hash" in out, "P7 a service off the model's hash fails", out)
        reset(gateway_inbox=False)
        rc, out = tool("pre")
        ok(rc == 0 and "use the production file list only" in out and not [u for u in (json.loads(l) for l in open(os.path.join(W, "argv.log"))) if u[:2] == ["wrapper", "-f"]],
           "P14 a gateway created without the inbox overlay: pre passes, and no Compose call adds it", out)
        reset()
        rc, out = tool("pre", extra={"GIS_HOST": "some-other-host"})
        ok(rc == 1 and "FAIL host some-other-host" in out, "P11 another host fails", out)
        reset()
        rc, out = tool("pre", extra={"GIS_NOW": str(calendar.timegm(time.strptime("2026-10-13 03:40", "%Y-%m-%d %H:%M")))})
        ok(rc == 1 and "FAIL outside 03:00-04:30 UTC" in out, "P13 the backup window is refused", out)

        # ---- rotate refusals
        reset()
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the latest pre passed within an hour" in out and env_val("PARKIO_GATEWAY_INTERNAL_SECRET") == OLD, "R1 rotate without a pre changes nothing", out)
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
        cad = [i for i, c in st["containers"].items() if c["service"] == "caddy"][0]
        st["containers"]["d" * 64] = st["containers"].pop(cad)
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the containers are the ones pre recorded" in out, "R1d a container change after pre blocks rotate", out)

        # ---- the full rotation
        reset()
        tool("pre")
        rc, out = tool("rotate", "--yes")
        new = env_val("PARKIO_GATEWAY_INTERNAL_SECRET")
        values.append(new)
        ok(rc == 0 and "gateway secret rotation rotate: PASS" in out, "R2 the full rotation passes", out)
        ok(new != OLD and len(new) == 64 and env_val("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS") == "", "R2 the env file holds a new 64-character secret and an empty ACCEPTED list")
        ok(all(e["PARKIO_GATEWAY_INTERNAL_SECRET"] == new and e["PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS"] == "" for e in app_env().values()),
           "R2 all ten app services run the new secret with an empty ACCEPTED list")
        seq = [(u[-1]) for u in ups()]
        want = list(DOWNSTREAM) + list(DOWNSTREAM) + ["gateway-service"] + list(DOWNSTREAM) + ["gateway-service"]
        ok(seq == want and all(u[1:3] == ["-f", "docker/docker-compose.waitlist-ops-inbox.yml"] for u in ups()),
           "R2 three rolling steps (A downstream; B downstream then gateway; C downstream then gateway), each with the inbox overlay", str(seq))
        ok(not refused_edges(), "R2 after every recreate, every app service accepts every other's secret", refused_edges())
        gw = [c for c in state()["containers"].values() if c["service"] == "gateway-service"][0]
        ok(gw["env"].get("PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR") and gw["files"].endswith("docker-compose.waitlist-ops-inbox.yml"),
           "R2 the recreated gateway kept the relay inbox")
        b = bak()
        ok(len(b) == 1 and OLD in open(os.path.join(L, "docker", b[0])).read() and oct(os.stat(os.path.join(L, "docker", b[0])).st_mode & 0o777) == "0o600",
           "R2 the backup holds the old value, mode 600")
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL a rotation stopped in phase A, B or C" in out and env_val("PARKIO_GATEWAY_INTERNAL_SECRET") == new,
           "R2b rollback after a passing rotation is refused", out)

        # ---- failures and rollbacks
        reset()
        tool("pre")
        mark("preflight-fail")
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL A: the release preflight passes" in out and env_val("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS") == "" and not ups(),
           "R3 a failing preflight in A restores the env file; nothing is recreated", out)
        reset()
        tool("pre")
        mark("env-affects-web")
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL A: exactly the ten app services' model hashes changed" in out and env_val("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS") == "",
           "R3b another service's model changing restores the env file", out)

        def phase():
            return [json.load(open(os.path.join(OUT, d, "rotate-progress.json"))) for d in sorted(os.listdir(OUT))
                    if os.path.exists(os.path.join(OUT, d, "rotate-progress.json"))][-1]["phase"]

        def stopped_then_rollback(label, marker=None, fault=None, expect="", stops=None):
            reset()
            tool("pre")
            if marker:
                mark(marker)
            rc, out = tool("rotate", "--yes", extra={"GIS_FAULT": fault} if fault else None)
            values.extend(v for v in (env_val("PARKIO_GATEWAY_INTERNAL_SECRET"), env_val("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS")) if v)
            ok((rc == 99 if fault else rc == 1) and expect in out and (stops is None or phase() == stops),
               f"{label}: the rotation stops" + (f" in phase {stops}" if stops else ""), out)
            for f in os.listdir(W):
                if f.startswith(("unhealthy-", "stale-env-", "collateral-", "keeps-old-")) or f in ("public-down", "public-down-after-gateway"):
                    os.remove(os.path.join(W, f))
            n_up = len(ups())
            rc, out = tool("rollback", "--yes")
            e = app_env()
            ok(rc == 0 and env_val("PARKIO_GATEWAY_INTERNAL_SECRET") == OLD and env_val("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS") == ""
               and all(x["PARKIO_GATEWAY_INTERNAL_SECRET"] == OLD and x["PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS"] == "" for x in e.values())
               and not refused_edges() and all("--force-recreate" in u for u in ups()[n_up:]),
               f"{label}: rollback restores the original secret everywhere and verifies it; no app service refuses another at any step",
               out + refused_edges())
            return [u[-1] for u in ups()[n_up:]]

        stopped_then_rollback("R4 an unhealthy service in A", marker="unhealthy-media-service", expect="FAIL A media-service: a new healthy container", stops="A")
        rb = stopped_then_rollback("R5 an interruption after a recreate in B", fault="after-up:B:parking-service")
        ok(rb == list(DOWNSTREAM[:8]) + list(DOWNSTREAM), "R5 rollback from part-way through B: the eight on B back to A, then all nine"
           " to the original; the gateway, never moved, is not restarted", str(rb))
        stopped_then_rollback("R6 an interruption after the env write in B", fault="after-env-write:B")
        stopped_then_rollback("R7 a recreated service without the expected values", marker="stale-env-user-service",
                              expect="FAIL A user-service: the expected secret and ACCEPTED values", stops="A")
        stopped_then_rollback("R8 another container changing at the gateway recreate in B", marker="collateral-gateway-service",
                              expect="FAIL B gateway-service: every other container unchanged", stops="B")
        stopped_then_rollback("R9 a service that keeps accepting the old value after C", marker="keeps-old-moderation-service",
                              expect="FAIL C moderation-service: accepts new; refuses old", stops="C")
        rb = stopped_then_rollback("R10 a public API that fails after the gateway sends the new value", marker="public-down-after-gateway",
                                   expect="FAIL B: the public API answers 200")
        ok(rb == (list(DOWNSTREAM) + ["gateway-service"]) * 2, "R10 rollback from the end of B: all ten back to A, then all ten to the original", str(rb))
        rb = stopped_then_rollback("R12 an interruption after a recreate in C", fault="after-up:C:auth-service")
        ok(rb == list(DOWNSTREAM) + (list(DOWNSTREAM) + ["gateway-service"]) * 2,
           "R12 rollback from C: the nine on C back to B (the gateway is on B), then all ten to A, then all ten to the original", str(rb))
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "rolled-back" in out, "R11 a second rollback is refused (rolled-back)", out)
        reset()
        tool("pre")
        tool("rotate", "--yes", extra={"GIS_FAULT": "after-up:B:parking-service"})
        values.extend(v for v in (env_val("PARKIO_GATEWAY_INTERNAL_SECRET"), env_val("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS")) if v)
        r14_new = env_val("PARKIO_GATEWAY_INTERNAL_SECRET")
        mark("stale-env-parking-service")
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL to A: every consumer runs its values or an earlier state's (parking-service)" in out
           and env_val("PARKIO_GATEWAY_INTERNAL_SECRET") == OLD and env_val("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS") == r14_new and not refused_edges(),
           "R14 a step that leaves a consumer behind stops before the next one; the env file keeps that step's values", out + refused_edges())
        os.remove(os.path.join(W, "stale-env-parking-service"))
        rc, out = tool("rollback", "--yes")
        ok(rc == 0 and env_val("PARKIO_GATEWAY_INTERNAL_SECRET") == OLD and env_val("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS") == ""
           and all(x["PARKIO_GATEWAY_INTERNAL_SECRET"] == OLD and x["PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS"] == "" for x in app_env().values())
           and not refused_edges(), "R14b a second rollback continues from the live state and passes", out + refused_edges())
        reset()
        tool("pre")
        tool("rotate", "--yes", extra={"GIS_FAULT": "after-up:B:parking-service"})
        values.extend(v for v in (env_val("PARKIO_GATEWAY_INTERNAL_SECRET"), env_val("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS")) if v)
        mark("rejects-all-user-service")
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL user-service: accepts the original value again" in out, "R15 a rollback after which a service refuses the original value fails", out)
        reset()
        tool("pre")
        tool("rotate", "--yes", extra={"GIS_FAULT": "after-up:A:media-service"})
        values.append(env_val("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS"))
        operator_up("user-service")                       # recreated by hand with the A values, outside the tool's records
        st = state()
        auth = [c for c in st["containers"].values() if c["service"] == "auth-service"][0]
        st["containers"]["b" * 64] = dict(auth, name="parkio-auth-service-2")       # and a second auth container
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        n_up = len(ups())
        rc, out = tool("rollback", "--yes")
        rb = [u[-1] for u in ups()[n_up:]]
        ok(rc == 0 and rb == list(DOWNSTREAM[:7]) + ["auth-service"] and len([c for c in state()["containers"].values() if c["service"] == "auth-service"]) == 1
           and all(x["PARKIO_GATEWAY_INTERNAL_SECRET"] == OLD and x["PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS"] == "" for x in app_env().values())
           and not refused_edges(),
           "R16 rollback goes by the live state: a consumer recreated outside the tool's records and one with two containers are rolled back",
           out + str(rb) + refused_edges())
        reset()
        tool("pre")
        tool("rotate", "--yes", extra={"GIS_FAULT": "after-up:B:parking-service"})
        values.extend(v for v in (env_val("PARKIO_GATEWAY_INTERNAL_SECRET"), env_val("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS")) if v)
        st = state()
        [c for c in st["containers"].values() if c["service"] == "gateway-service"][0]["health"] = "unhealthy"
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL gateway-service: runs the original values, healthy" in out, "R17 a consumer left unhealthy fails the rollback's end-state check", out)
        reset()
        tool("pre")
        tool("rotate", "--yes", extra={"GIS_FAULT": "after-up:A:media-service"})
        values.append(env_val("PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS"))
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
        cur = env_val("PARKIO_GATEWAY_INTERNAL_SECRET")
        values.append(cur)
        rbak = os.path.join(L, "docker", bak()[0])
        mt = {p: os.stat(p).st_mtime_ns for p in BACKUPS + EXTRA}
        rc, out = tool("redact", "--yes")
        ok(rc == 0 and all(OLD not in open(p).read() and MARKER in open(p).read() for p in BACKUPS + EXTRA + [rbak])
           and all(os.stat(p).st_mtime_ns == mt[p] for p in BACKUPS + EXTRA) and env_val("PARKIO_GATEWAY_INTERNAL_SECRET") == cur,
           "X2 redact replaces the old value everywhere listed and in the rotation's backup last; mtimes kept; the live env untouched", out)
        rc, out = tool("redact", "--yes")
        ok(rc == 0 and "redaction already complete" in out, "X5 a second redact reports completion", out)
        reset()
        tool("pre")
        tool("rotate", "--yes")
        values.append(env_val("PARKIO_GATEWAY_INTERNAL_SECRET"))
        rbak = os.path.join(L, "docker", bak()[0])
        open(os.path.join(L, "stray.txt"), "w").write(OLD)
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "stray.txt" in out and OLD in open(rbak).read(), "X3 an unlisted copy fails the scan; the backup keeps the old value", out)
        reset()
        tool("pre")
        tool("rotate", "--yes")
        values.append(env_val("PARKIO_GATEWAY_INTERNAL_SECRET"))
        rbak = os.path.join(L, "docker", bak()[0])
        open(EXTRA[1], "w").write(env_text(env_val("PARKIO_GATEWAY_INTERNAL_SECRET")))
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "holds the new value; left alone" in out and OLD in open(rbak).read(), "X4 a listed file holding the new value is left alone", out)

        reset()
        tool("pre")
        tool("rotate", "--yes")
        values.append(env_val("PARKIO_GATEWAY_INTERNAL_SECRET"))
        cur = env_val("PARKIO_GATEWAY_INTERNAL_SECRET")
        os.symlink(ENV, os.path.join(L, "docker", ".env.azure-hosted-beta.bak-live"))
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "the live env file is never redacted" in out and env_val("PARKIO_GATEWAY_INTERNAL_SECRET") == cur,
           "X6 the live env file is never redacted, even through a link", out)

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
