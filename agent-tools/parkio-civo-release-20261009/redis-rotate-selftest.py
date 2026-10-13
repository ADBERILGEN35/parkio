#!/usr/bin/env python3
"""Self-test of redis-rotate.py, using fakes only.

Fakes:
- docker, backed by a JSON state. It covers containers (any project) with environments, a redis with a password set and
  keys, and per-container logs. It answers ps, inspect, info, top, logs, and `exec -i -e REDISCLI_AUTH … redis-cli`:
  stdin commands PING, ACL SETUSER (> and resetpass), SET, GET and DEL, authenticated by REDISCLI_AUTH. It records
  every argv and every environment.
- the release checkout is a real git repository:
  - PIN: an unfixed docker-compose.yml and the old web pin;
  - R1: the reviewed four-line web pin;
  - R2: the fixed docker-compose.yml.
  Its scripts/parkio-prod-compose.sh is a fake wrapper: `config --format json` and `config --hash *` derive from the
  env file and the checkout's compose file, and `up` recreates one service. Its scripts/preflight-hosted-beta.sh is
  a fake. Marker files inject faults.

Pass criteria:
- every case expected below holds;
- no old or new value appears in any output, record or process argument;
- REDISCLI_AUTH reaches only the docker exec calls;
- no other variable from outside the allowlist reaches a child.

  python3 -I redis-rotate-selftest.py
"""
import calendar
import hashlib
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "redis-rotate.py")
WEB_DIGEST = "sha256:08811c39cd5a0a483415b136d1f6bcfd7120e97a94b71ec77d20b63ac15cdea6"
CONSUMERS = ("gamification-service", "parking-service", "auth-service", "gateway-service")
results = []

FAKE_DOCKER = r'''#!/usr/bin/env python3
import json, os, sys, time, hashlib
W = os.environ["RRT_W"]
a = sys.argv[1:]
open(os.path.join(W, "argv.log"), "a").write(json.dumps(a) + "\n")
open(os.path.join(W, "env.log"), "a").write(json.dumps({"argv0": a[0], "env": sorted(os.environ)}) + "\n")
P = os.path.join(W, "state.json")
st = json.load(open(P))
cs = st["containers"]
def save(): json.dump(st, open(P + ".t", "w")); os.replace(P + ".t", P)
def by_name(n): return next((i for i, c in cs.items() if c["name"] == n or i == n), None)
mark = lambda m: os.path.exists(os.path.join(W, m))
def seen(v): open(os.path.join(W, "seen.txt"), "a").write(v + "\n")
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
                    "Config": {"Env": [f"{k}={v}" for k, v in c["env"].items()], "Cmd": c.get("cmd", []),
                               "Image": "other:tag" if mark("config-image-differs") and c["service"] == "gateway-service" else c.get("ref"),
                               "Healthcheck": {"Test": c.get("hc", [])},
                               "Labels": {"com.docker.compose.project": c["project"], "com.docker.compose.service": c["service"],
                                          "com.docker.compose.config-hash": c["hash"]}},
                    "State": {"Status": "running", "Health": {"Status": c["health"]}}})
    print(json.dumps(out)); sys.exit(0)
if a[:2] == ["image", "inspect"]:
    iid = st.get("images", {}).get(a[-1])
    if iid and mark("tag-moved") and a[-1].startswith("redis"):
        iid = "sha256:" + "f" * 64
    if iid and mark("tag-moved-gamification") and a[-1].startswith("parkio-gamification"):
        iid = "sha256:" + "f" * 64
    print(iid or "") if iid else sys.exit(1)
    sys.exit(0)
if a[0] == "top":
    c = cs[by_name(a[1])]
    print("PID ARGS\n1 " + " ".join(c.get("args", ["redis-server", "*:6379"]))); sys.exit(0)
if a[0] == "logs":
    c = cs[by_name(a[-1])]
    since = float(a[a.index("--since") + 1]) if "--since" in a else 0
    print("\n".join(l for t, l in c.get("logs", []) if t >= since)); sys.exit(0)
if a[0] == "exec" and a[2] == "curl":
    c = cs[by_name(a[1])]
    down = mark("agg-503-" + c["service"]) or (mark("agg-503-after-redis-" + c["service"]) and st.get("redis_recreated"))
    print("503" if down else "200"); sys.exit(0)
if a[0] == "exec":
    c = cs[by_name(a[4])]
    assert a[1:4] == ["-i", "-e", "REDISCLI_AUTH"] and a[5] == "redis-cli", a
    auth = os.environ.get("REDISCLI_AUTH", "")
    seen(auth)
    if auth not in st["redis"]["passwords"]:
        print("AUTH failed: WRONGPASS invalid username-password pair", file=sys.stderr); print("NOAUTH Authentication required."); sys.exit(0)
    for line in sys.stdin.read().splitlines():
        w = line.split()
        if not w: continue
        if w[0] == "PING": print("PONG")
        elif w[:3] == ["ACL", "SETUSER", "default"]:
            for op in w[3:]:
                if op[:1] in "<>": seen(op[1:])
            if mark("acl-fail"):
                print("(error) ERR Error in ACL SETUSER modifier"); continue
            for op in w[3:]:
                if op == "resetpass" and not mark("resetpass-noop"): st["redis"]["passwords"] = []
                elif op.startswith(">"): st["redis"]["passwords"].append(op[1:])
                elif op.startswith("<") and op[1:] in st["redis"]["passwords"]: st["redis"]["passwords"].remove(op[1:])
            print("OK")
        elif w[0] == "SET": st["redis"]["keys"][w[1]] = w[2]; print("OK")
        elif w[0] == "GET": print(st["redis"]["keys"].get(w[1], ""))
        elif w[0] == "DEL": st["redis"]["keys"].pop(w[1], None); print("(integer) 1")
    save(); sys.exit(0)
sys.exit(2)
'''

FAKE_WRAPPER = r'''#!/usr/bin/env python3
import hashlib, json, os, sys, time
W = os.environ["RRT_W"]
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
        k, v = line.rstrip("\n").split("=", 1); env[k] = v
pw = env.get("REDIS_PASSWORD", "")
fixed = "redis-auth.conf" in open("docker/docker-compose.yml").read() and not mark("unfixed")
def service(svc):
    if svc == "redis":
        return {"image": "redis:7-alpine", "environment": {"REDIS_PASSWORD": pw},
                "command": ["/bin/sh", "-c", "printf 'requirepass' > /tmp/redis-auth.conf; exec redis-server /tmp/redis-auth.conf" if fixed and not mark("argv-command") else "exec redis-server --requirepass \"$REDIS_PASSWORD\""],
                "healthcheck": {"test": ["CMD-SHELL", "REDISCLI_AUTH=\"$REDIS_PASSWORD\" redis-cli ping" if fixed else f"redis-cli -a {pw} ping"]}}
    if svc in ("gamification-service", "parking-service", "auth-service", "gateway-service"):
        e = {"SPRING_DATA_REDIS_PASSWORD": pw, "X": "1"}
        if svc == "gateway-service" and INBOX: e["PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR"] = "/var/lib/parkio/waitlist-ops-inbox"
        if mark("model-drops-" + svc): e.pop("SPRING_DATA_REDIS_PASSWORD")
        if svc == "gamification-service":
            return {"environment": e}                     # build-only, as in production: no image key (review T1)
        return {"image": "ghcr.io/x/" + svc, "environment": e}
    if svc == "redis-exporter":
        return {"image": "x/redis-exporter", "environment": {"REDIS_PASSWORD": pw}}
    if svc == "web" and mark("env-affects-web"):
        return {"image": "ghcr.io/x/web", "environment": {"Y": "2", "X_REDIS": hashlib.sha256(pw.encode()).hexdigest()[:8]}}
    return {"image": "ghcr.io/x/" + svc, "environment": {"Y": "2"}}
SERVICES = ("redis", "gamification-service", "parking-service", "auth-service", "gateway-service", "web", "caddy") + \
    (("redis-exporter",) if mark("model-adds-consumer") else ())
def mhash(svc): return hashlib.sha256(json.dumps(service(svc), sort_keys=True).encode()).hexdigest()[:16]
if a[:2] == ["config", "--format"]:
    print(json.dumps({"services": {s: service(s) for s in SERVICES}})); sys.exit(0)
if a[:2] == ["config", "--hash"]:
    print("\n".join(f"{s} {mhash(s)}" for s in SERVICES)); sys.exit(0)
if a[:4] == ["up", "-d", "--no-build", "--no-deps"]:
    svc = a[-1]
    P = os.path.join(W, "state.json"); st = json.load(open(P))
    if svc == "parking-service" and mark("redis-restart-during-c"):
        st["redis"]["passwords"] = st["redis"]["passwords"][:1]       # the old container restarted with --requirepass OLD
    if svc == "gateway-service" and mark("redis-restart-during-c-gateway"):
        st["redis"]["passwords"] = st["redis"]["passwords"][:1]
    old = [i for i, c in st["containers"].items() if c["project"] == "parkio" and c["service"] == svc][0]
    c = dict(st["containers"].pop(old))
    if not mark("stale-env-" + svc):
        c["env"] = {k: v for k, v in service(svc)["environment"].items()}
    c["hash"] = mhash(svc)
    c["files"] = "/opt/parkio-release/docker/docker-compose.yml,/opt/parkio-release/docker/docker-compose.apps.yml" + (",/opt/parkio-release/docker/docker-compose.waitlist-ops-inbox.yml" if INBOX and svc == "gateway-service" else "")
    c["health"] = "unhealthy" if mark("unhealthy-" + svc) else "healthy"
    now = time.time()
    if svc == "redis":
        st["redis_recreated"] = True
        prev = st["redis"]["passwords"]
        st["redis"]["passwords"] = [pw] + ([x for x in prev if x != pw] if mark("keeps-old") else [])
        if mark("keys-lost"): st["redis"]["keys"] = {}
        if mark("redis-wrong-pw"): st["redis"]["passwords"] = ["not-the-value"]
        c["hc"] = service(svc)["healthcheck"]["test"] if not mark("hc-leak") else ["CMD", "redis-cli", "-a", pw, "ping"]
        if mark("argv-leak"): c["args"] = ["redis-server", "--requirepass", pw]
        if mark("image-changed"): c["image"] = "sha256:" + "e" * 64
        if mark("hash-off"): c["hash"] = "not-the-model-hash"
        if mark("auth-error-log"):
            for i2, c2 in st["containers"].items():
                if c2["service"] == "auth-service": c2.setdefault("logs", []).append([now + 0.1, "ERROR WRONGPASS invalid username-password pair"])
    if mark("collateral-" + svc):
        cad = [i for i, x in st["containers"].items() if x["service"] == "caddy"][0]
        st["containers"]["c" * 64] = st["containers"].pop(cad)
    st["containers"][hashlib.sha256(f"{svc}{now}".encode()).hexdigest()] = c
    json.dump(st, open(P + ".t", "w")); os.replace(P + ".t", P)
    sys.exit(0)
sys.exit(2)
'''


def ok(cond, label, extra=""):
    results.append(bool(cond))
    print(("ok   " if cond else "FAIL ") + label + ("" if cond else f"\n{extra[-2500:]}"))


def git(repo, *args):
    p = subprocess.run(["git", "-C", repo, "-c", "user.name=t", "-c", "user.email=t@t"] + list(args), capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit(f"git {args}: {p.stderr}")
    return p.stdout.strip()


def main():
    W = tempfile.mkdtemp(prefix="rrt-")
    try:
        R, L, OUT, BIN = (os.path.join(W, d) for d in ("release", "live", "out", "bin"))
        for d in (os.path.join(R, "scripts"), os.path.join(R, "docker"), os.path.join(L, "docker"), BIN):
            os.makedirs(d)
        open(os.path.join(BIN, "docker"), "w").write(FAKE_DOCKER)
        os.chmod(os.path.join(BIN, "docker"), 0o755)
        for name, body in (("parkio-prod-compose.sh", FAKE_WRAPPER),
                           ("preflight-hosted-beta.sh", '#!/bin/sh\n[ -e "$RRT_W/preflight-fail" ] && exit 1\nexit 0\n')):
            p = os.path.join(R, "scripts", name)
            open(p, "w").write(body)
            os.chmod(p, 0o755)
        open(os.path.join(R, "docker", "docker-compose.yml"), "w").write("services:\n  redis:\n    healthcheck: redis-cli -a ...\n")
        open(os.path.join(R, "docker", "docker-compose.web-release-pin.yml"), "w").write(
            "# old\nname: parkio\nservices:\n  web:\n    image: ghcr.io/adberilgen35/parkio/web@sha256:" + "4" * 64 + "\n")
        git(R, "init", "-q")
        git(R, "add", "-A")
        git(R, "commit", "-qm", "PIN")
        pin = git(R, "rev-parse", "HEAD")
        open(os.path.join(R, "docker", "docker-compose.web-release-pin.yml"), "w").write(
            "# R1\nname: parkio\n\nservices:\n  web:\n    image: ghcr.io/adberilgen35/parkio/web@" + WEB_DIGEST + "\n")
        git(R, "commit", "-qam", "R1")
        r1 = git(R, "rev-parse", "HEAD")
        open(os.path.join(R, "docker", "docker-compose.yml"), "w").write("services:\n  redis:\n    command: write /tmp/redis-auth.conf\n")
        git(R, "commit", "-qam", "R2: the redis fix")
        r2 = git(R, "rev-parse", "HEAD")
        fix_blob = git(R, "rev-parse", "HEAD:docker/docker-compose.yml")
        FIXED = "services:\n  redis:\n    command: write /tmp/redis-auth.conf\n"

        def variant(base, name, files):
            git(R, "checkout", "-q", "--detach", base)
            for path, text in files.items():
                open(os.path.join(R, path), "w").write(text)
            git(R, "add", "-A")
            git(R, "commit", "-qm", name)
            return git(R, "rev-parse", "HEAD")
        r2_extra = variant(r1, "R2 plus another change", {"docker/docker-compose.yml": FIXED, "scripts/extra.txt": "x\n"})
        r2_blob = variant(r1, "R2 with another compose blob", {"docker/docker-compose.yml": FIXED + "# drift\n"})
        r2_pin = variant(pin, "the fix on the PIN", {"docker/docker-compose.yml": FIXED})
        ORIGIN = os.path.join(W, "origin.git")
        subprocess.run(["git", "init", "-q", "--bare", ORIGIN], check=True)
        git(R, "remote", "add", "origin", ORIGIN)
        for i, c in enumerate((r2, r2_extra, r2_blob, r2_pin)):
            git(R, "push", "-q", "origin", f"{c}:refs/heads/t{i}")
        BRANCH = "release/test-rotation"

        def branch_tip(c):
            subprocess.run(["git", "-C", ORIGIN, "update-ref", f"refs/heads/{BRANCH}", c], check=True)
        branch_tip(r2)
        ENV = os.path.join(L, "docker", ".env.azure-hosted-beta")
        REC = os.path.join(L, "recovery")
        REDACT = [os.path.join(L, "docker", ".env.azure-hosted-beta.bak-old1"), os.path.join(REC, "env-copy"), os.path.join(REC, "rendered.yml"),
                  os.path.join(REC, "already.yml"), os.path.join(REC, "holds-new.env"), os.path.join(REC, "link.env")]
        MARKER = "CHANGE_ME_redacted_after_rotation_20261013"
        OLD_TIME = calendar.timegm(time.strptime("2026-09-17 12:00", "%Y-%m-%d %H:%M"))
        SEED_MODES = {REDACT[0]: 0o600, REDACT[1]: 0o640, REDACT[2]: 0o664, REDACT[3]: 0o644}
        OLD = secrets.token_urlsafe(24)
        allvalues = [OLD]
        env = {"PATH": BIN + ":" + os.environ["PATH"], "HOME": W, "LANG": "C.UTF-8", "LEAKY_SECRET": "x", "RRT_TEST": "1", "RRT_W": W,
               "RRT_R1": r1, "RRT_BRANCH": BRANCH, "RRT_FIX_BLOB": fix_blob, "RRT_HOST": socket.gethostname(), "RRT_RELEASE": R,
               "RRT_ENV_FILE": ENV, "RRT_OUT": OUT, "RRT_NOW": str(calendar.timegm(time.strptime("2026-10-13 10:00", "%Y-%m-%d %H:%M"))),
               "RRT_HEALTH_WAIT": "3", "RRT_SETTLE": "0.2", "RRT_POLL": "0.1", "RRT_REDACT_FILES": ":".join(REDACT), "RRT_SCAN_ROOTS": L}
        outputs = []
        OTHER = secrets.token_urlsafe(24)
        allvalues.append(OTHER)

        def reset(checkout=r2, extra_carrier=False, env_value=None, embedded_carrier=False, gateway_inbox=True, cmd_carrier=False):
            if os.path.isdir(REC):
                os.chmod(REC, 0o755)
            for f in os.listdir(os.path.join(L, "docker")):
                os.remove(os.path.join(L, "docker", f))
            shutil.rmtree(OUT, ignore_errors=True)
            for f in os.listdir(W):
                if f.startswith(("unhealthy-", "model-drops-", "collateral-", "stale-env-", "agg-503-")) or f in (
                        "daemon-debug", "tag-moved-gamification", "redis-restart-during-c", "redis-restart-during-c-gateway", "resetpass-noop",
                        "config-image-differs",
                        "unfixed", "keys-lost", "auth-error-log", "keeps-old", "preflight-fail", "argv-command", "model-adds-consumer",
                        "env-affects-web", "redis-wrong-pw", "hc-leak", "argv-leak", "acl-fail", "tag-moved", "image-changed", "hash-off"):
                    os.remove(os.path.join(W, f))
            archive_logs()
            git(R, "checkout", "-q", "--detach", checkout)
            fd = os.open(ENV, os.O_WRONLY | os.O_CREAT, 0o600)
            os.write(fd, f"PARKIO_DOMAIN=parkio.dev\nREDIS_PASSWORD={env_value or OLD}\nOTHER_SECRET=s3cr3tvalue123456\n".encode())
            os.close(fd)
            shutil.rmtree(REC, ignore_errors=True)
            os.makedirs(REC)
            seed = {REDACT[0]: f"A=1\nREDIS_PASSWORD={OLD}\nB=2\n", REDACT[1]: f'REDIS_PASSWORD="{OLD}"\n',
                    REDACT[2]: f"x: {OLD}\ny: \"{OLD}\"\nz: [CMD, redis-cli, -a, {OLD}]\n", REDACT[3]: "nothing here\n"}
            for path, text in seed.items():
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                os.write(fd, text.encode())
                os.close(fd)
                os.chmod(path, SEED_MODES[path])
                os.utime(path, (OLD_TIME, OLD_TIME))
            cs = {}
            for i, svc in enumerate(("redis",) + CONSUMERS + ("web", "caddy")):
                e = {"REDIS_PASSWORD": OLD} if svc == "redis" else ({"SPRING_DATA_REDIS_PASSWORD": OLD, "X": "1"} if svc in CONSUMERS else {"Y": "2"})
                ref = "redis:7-alpine" if svc == "redis" else ("parkio-gamification-service" if svc == "gamification-service" else "ghcr.io/x/" + svc)
                if svc == "gateway-service" and gateway_inbox:
                    e = dict(e, **{"PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR": "/var/lib/parkio/waitlist-ops-inbox"})
                cs[f"{i}" * 64] = {"name": "parkio-redis" if svc == "redis" else f"parkio-{svc}-1", "project": "parkio", "service": svc,
                                   "image": f"sha256:{i}{'0' * 63}", "ref": ref, "hash": "", "health": "healthy", "env": e, "logs": [],
                                   "files": "/opt/parkio-release/docker/docker-compose.yml,/opt/parkio-release/docker/docker-compose.apps.yml" + (",/opt/parkio-release/docker/docker-compose.waitlist-ops-inbox.yml" if gateway_inbox and svc == "gateway-service" else ""),
                                   # the running redis predates the fix: its healthcheck carries the value (first live pre)
                                   "hc": ["CMD-SHELL", f"redis-cli -a {OLD} ping"] if svc == "redis" else []}
            if extra_carrier:
                cs["e" * 64] = {"name": "other-app", "project": "other", "service": "app", "image": "x", "hash": "h", "health": "healthy",
                                "env": {"CACHE_PW": OLD}, "logs": []}
            if cmd_carrier:
                cs["a" * 64] = {"name": "cmd-app", "project": "other", "service": "app", "image": "x", "hash": "h", "health": "healthy",
                                "env": {}, "cmd": ["sh", "-c", f"use {OLD}"], "logs": []}
            if embedded_carrier:
                cs["f" * 64] = {"name": "url-app", "project": "other", "service": "app", "image": "x", "hash": "h", "health": "healthy",
                                "env": {"REDIS_URL": f"redis://:{OLD}@redis:6379"}, "logs": []}
            images = {c["ref"]: c["image"] for c in cs.values() if c["project"] == "parkio"}
            json.dump({"containers": cs, "redis": {"passwords": [OLD], "keys": {"k1": "v1"}}, "images": images}, open(os.path.join(W, "state.json"), "w"))
            # align the consumers' and redis's hashes with the model, as after a normal deploy
            overlay = ["-f", "docker/docker-compose.waitlist-ops-inbox.yml"] if gateway_inbox else []
            p = subprocess.run([os.path.join(R, "scripts", "parkio-prod-compose.sh")] + overlay + ["config", "--hash", "*"], cwd=R, capture_output=True,
                               text=True, env={k: v for k, v in dict(env, PARKIO_ENV_FILE=ENV).items() if k != "LEAKY_SECRET"})
            hashes = dict(l.split() for l in p.stdout.splitlines())
            st = json.load(open(os.path.join(W, "state.json")))
            for c in st["containers"].values():
                if c["project"] == "parkio":
                    c["hash"] = hashes.get(c["service"], "") if c["service"] != "redis" else "old-redis-definition"
            json.dump(st, open(os.path.join(W, "state.json"), "w"))
            archive_logs()

        def archive_logs():
            """Move this case's argv and env logs into the run's logs, which the final checks read."""
            for f in ("argv.log", "env.log"):
                src = os.path.join(W, f)
                if os.path.exists(src):
                    with open(os.path.join(W, f.replace(".log", "-all.log")), "a") as dst:
                        dst.write(open(src).read())
                    os.remove(src)

        def tool(*args, extra=None):
            p = subprocess.run([sys.executable, "-I", TOOL] + list(args), capture_output=True, text=True, env=dict(env, **(extra or {})), timeout=300)
            outputs.append(p.stdout + p.stderr)
            return p.returncode, p.stdout + p.stderr

        def state():
            return json.load(open(os.path.join(W, "state.json")))

        def mark(m):
            open(os.path.join(W, m), "w").write("x")

        def env_value():
            for line in open(ENV):
                if line.startswith("REDIS_PASSWORD="):
                    return line.split("=", 1)[1].strip()

        def consumer_values():
            return {c["service"]: c["env"].get("SPRING_DATA_REDIS_PASSWORD") for c in state()["containers"].values() if c["service"] in CONSUMERS}

        def bak_files():
            return [f for f in os.listdir(os.path.join(L, "docker")) if ".bak-rrt-" in f]

        def ups():
            p = os.path.join(W, "argv.log")
            return [json.loads(l) for l in open(p)] if os.path.exists(p) else []

        # ---- usage
        reset()
        for args, label in (([], "no command"), (["pre", "--yes"], "pre --yes"), (["rotate"], "rotate without --yes"),
                            (["rollback"], "rollback without --yes"), (["rotate", "--yes", "--x"], "an extra argument")):
            rc, out = tool(*args)
            ok(rc == 2, f"U usage refusal: {label}", out)

        # ---- checkout
        for args, label in ((["checkout"], "checkout without --r2"), (["checkout", "--r2", "abc"], "checkout with a short sha")):
            rc, out = tool(*args)
            ok(rc == 2, f"U usage refusal: {label}", out)
        reset(checkout=pin)
        rc, out = tool("checkout", "--r2", r2)
        ok(rc == 1 and "FAIL the release checkout is clean at R1" in out and git(R, "rev-parse", "HEAD") == pin,
           "K1 a checkout that is not at R1 is refused and left unchanged", out)
        reset(checkout=r1)
        open(os.path.join(R, "stray.txt"), "w").write("x")
        rc, out = tool("checkout", "--r2", r2)
        os.remove(os.path.join(R, "stray.txt"))
        ok(rc == 1 and "FAIL the release checkout is clean at R1" in out and git(R, "rev-parse", "HEAD") == r1, "K2 a dirty checkout is refused", out)
        for tip, sha, label, expect in ((r2, r2_extra, "K3 an R2 that is not the branch tip", "FAIL R2 is the release branch tip"),
                                        (r2_extra, r2_extra, "K4 an R2 with another changed file", "not only docker/docker-compose.yml"),
                                        (r2_blob, r2_blob, "K5 an R2 with another compose blob", "not the reviewed fix"),
                                        (r2_pin, r2_pin, "K6 an R2 whose parent is not R1", "its parent is not R1")):
            reset(checkout=r1)
            branch_tip(tip)
            rc, out = tool("checkout", "--r2", sha)
            ok(rc == 1 and expect in out and git(R, "rev-parse", "HEAD") == r1, label + " is refused; the checkout stays at R1", out)
        branch_tip(r2)
        reset(checkout=r1)
        rc, out = tool("checkout", "--r2", r2)
        ok(rc == 0 and "redis rotation checkout: PASS" in out and git(R, "rev-parse", "HEAD") == r2, "K7 R1 -> R2 passes", out)
        rc, out = tool("checkout", "--r2", r2)
        ok(rc == 0 and "already at R2" in out and git(R, "rev-parse", "HEAD") == r2, "K8 checkout at R2 again passes, unchanged", out)

        # ---- pre
        reset()
        rc, out = tool("pre")
        ok(rc == 0 and "redis rotation pre: PASS" in out and "R1 plus the reviewed redis fix" in out and "its old healthcheck" in out
           and "include docker/docker-compose.waitlist-ops-inbox.yml" in out, "P1 pre passes at R2 (old redis healthcheck noted; inbox overlay included)", out)
        ok("NOTE redis will be recreated from the model: its definition differs" in out, "P1 pre notes the redis definition change", out)
        cases = [
            ("P2 the checkout at R1 (no fix) fails", dict(checkout=r1), None, "FAIL the release checkout is clean at R2"),
            ("P4 an unfixed model (value in the healthcheck) fails", {}, "unfixed", "FAIL the model's redis carries the value nowhere outside"),
            ("P5 another container of another project carrying the value fails", dict(extra_carrier=True), None,
             "FAIL the live containers carrying the value are exactly the expected ones"),
            ("P6 a consumer missing from the model fails", {}, "model-drops-parking-service", "FAIL the model's consumers are exactly the expected ones"),
            ("P6b another service of the model carrying the value fails", {}, "model-adds-consumer", "FAIL the model's consumers are exactly the expected ones"),
            ("P11 a moved redis tag fails", {}, "tag-moved", "FAIL redis: the container runs the model's image reference"),
            ("P4b a redis command that passes the password as an argument fails", {}, "argv-command",
             "FAIL the model's redis command writes /tmp/redis-auth.conf"),
            ("P9 an env file value that redis does not run with fails", dict(env_value=OTHER), None,
             "FAIL the env file's value is the one redis runs with"),
            ("P12 another project's container embedding the value in a URL fails", dict(embedded_carrier=True), None,
             "FAIL the live containers carrying the value are exactly the expected ones"),
            ("P13 a consumer whose aggregate health is not 200 fails", {}, "agg-503-auth-service", "FAIL auth-service: aggregate health 200"),
            ("P14 dockerd debug logging fails", {}, "daemon-debug", "FAIL dockerd debug logging is off"),
            ("P16 a moved implicit tag of the build-only consumer fails", {}, "tag-moved-gamification",
             "FAIL gamification-service: the container runs the model's image reference"),
            ("P17 a container not running the model's image reference fails", {}, "config-image-differs",
             "FAIL gateway-service: the container runs the model's image reference"),
        ]
        reset(gateway_inbox=False)
        rc, out = tool("pre")
        ok(rc == 0 and "use the production file list only" in out and not [u for u in ups() if u[:2] == ["wrapper", "-f"]],
           "P18 a gateway created without the inbox overlay: pre passes, and no Compose call adds it", out)
        reset(cmd_carrier=True)
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL the live containers carrying the value are exactly the expected ones" in out,
           "P5b another container carrying the value outside Env (its command) fails", out)
        reset()
        rc, out = tool("pre", extra={"RRT_HOST": "some-other-host"})
        ok(rc == 1 and "FAIL host some-other-host" in out, "P15 another host fails", out)
        for label, kw, marker, expect in cases:
            reset(**kw)
            if marker:
                mark(marker)
            rc, out = tool("pre")
            ok(rc == 1 and expect in out, label, out)
        reset(checkout=r2_blob)
        rc, out = tool("pre")
        ok(rc == 1 and "is blob" in out and "not the reviewed fix" in out, "P3 a compose file other than the reviewed fix fails", out)
        reset()
        st = state()
        for c in st["containers"].values():
            if c["service"] == "auth-service":
                c["hash"] = "pending-other-change"
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL auth-service running, healthy and at the model's hash" in out, "P7 a consumer with another pending change fails", out)
        reset()
        rc, out = tool("pre", extra={"RRT_NOW": str(calendar.timegm(time.strptime("2026-10-13 03:40", "%Y-%m-%d %H:%M")))})
        ok(rc == 1 and "FAIL outside 03:00-04:30 UTC" in out, "P8 the backup window is refused", out)
        reset()
        st = state()
        st["redis"]["passwords"] = ["changed-at-run-time"]
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL redis accepts the current value" in out, "P10 a redis that does not accept the env file's value fails", out)

        # ---- rotate refusals
        reset()
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the latest pre passed within an hour" in out and redis_unchanged(state(), OLD), "R1 rotate without a pre changes nothing", out)
        reset()
        tool("pre")
        with open(ENV, "a") as fh:
            fh.write("NEW_LINE=1\n")
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the env file is unchanged since that pre" in out and redis_unchanged(state(), OLD), "R1b an env change after pre blocks rotate", out)
        reset()
        tool("pre")
        rec = [os.path.join(OUT, d, "pre.json") for d in os.listdir(OUT)][0]
        r = json.load(open(rec))
        r["stamp"] = time.strftime("%Y%m%dT%H%M%S000Z", time.gmtime(time.time() - 7200))
        open(rec, "w").write(json.dumps(r))
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the latest pre passed within an hour" in out and redis_unchanged(state(), OLD), "R1c a pre older than an hour blocks rotate", out)
        reset()
        tool("pre")
        st = state()
        cad = [i for i, c in st["containers"].items() if c["service"] == "caddy"][0]
        st["containers"]["d" * 64] = st["containers"].pop(cad)
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the containers are the ones pre recorded" in out and redis_unchanged(state(), OLD),
           "R1d a container changed after pre blocks rotate", out)
        reset()
        tool("pre")
        mark("acl-fail")
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL ACL SETUSER default >new: OK" in out and env_value() == OLD and not bak_files(),
           "R8 a failed ACL SETUSER stops before the env file is touched", out)
        reset()
        tool("pre")
        mark("env-affects-web")
        rc, out = tool("rotate", "--yes")
        allvalues.append(state()["redis"]["passwords"][-1])
        ok(rc == 1 and "FAIL exactly the consumers' and redis's model hashes changed" in out and env_value() == OLD,
           "R9 another service's model changing with the value restores the env file and stops", out)

        # ---- the full rotation
        reset()
        tool("pre")
        rc, out = tool("rotate", "--yes")
        NEW = env_value()
        allvalues.append(NEW)
        ok(rc == 0 and "redis rotation rotate: PASS" in out, "R2 the full rotation passes", out)
        ok(NEW and NEW != OLD and len(NEW) >= 32, "R2 the env file holds a new value")
        ok(set(consumer_values().values()) == {NEW}, "R2 every consumer runs with the new value")
        ok(state()["redis"]["passwords"] == [NEW], "R2 redis accepts only the new value")
        ok(state()["redis"]["keys"].get("k1") == "v1" and not [k for k in state()["redis"]["keys"] if k.startswith("rotation:probe")],
           "R2 existing data kept; the probe key removed")
        order = [u[-1] for u in ups() if u[0] == "wrapper" and "up" in u and "--force-recreate" not in u]
        ok(order == list(CONSUMERS) + ["redis"], "R2 the order: gamification, parking, auth, gateway, then redis", str(order))
        gw = [c for c in state()["containers"].values() if c["service"] == "gateway-service"][0]
        ok(gw["env"].get("PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR") and gw["files"].endswith("docker-compose.waitlist-ops-inbox.yml")
           and all(u[1:3] == ["-f", "docker/docker-compose.waitlist-ops-inbox.yml"] for u in ups() if u[0] == "wrapper"),
           "R2 every Compose call carried the inbox overlay; the recreated gateway kept the relay inbox")
        bak = [f for f in os.listdir(os.path.join(L, "docker")) if ".bak-rrt-" in f]
        ok(len(bak) == 1 and f"REDIS_PASSWORD={OLD}".encode() in open(os.path.join(L, "docker", bak[0]), "rb").read() and
           oct(os.stat(os.path.join(L, "docker", bak[0])).st_mode & 0o777) == "0o600", "R2 the backup holds the old value, mode 600")
        n_up = len(ups())
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL a rotate record exists, stopped before redis was recreated" in out and env_value() == NEW and
           len(ups()) == n_up and state()["redis"]["passwords"] == [NEW], "R7 rollback after the redis recreate is refused; nothing changes", out)

        # ---- redact
        def mtime(p):
            return os.stat(p).st_mtime_ns

        def rotated():
            reset()
            tool("pre")
            rc, _ = tool("rotate", "--yes")
            v = env_value()
            allvalues.append(v)
            return rc, v, os.path.join(L, "docker", bak_files()[0])
        reset()
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "FAIL the latest rotation passed" in out and OLD in open(REDACT[0]).read(), "X1 redact before a rotation is refused; nothing changes", out)
        rc0, newx, rbak = rotated()
        t0 = {q: mtime(q) for q in REDACT[:4]}
        rc, out = tool("redact", "--yes")
        texts = {q: open(q).read() for q in REDACT[:4]}
        ok(rc0 == 0 and rc == 0 and "redis rotation redact: PASS" in out, "X2 redact after a passing rotation passes", out)
        ok(all(OLD not in t for t in texts.values()) and texts[REDACT[0]] == f"A=1\nREDIS_PASSWORD={MARKER}\nB=2\n" and
           texts[REDACT[1]] == f'REDIS_PASSWORD="{MARKER}"\n' and texts[REDACT[2]].count(MARKER) == 3 and texts[REDACT[3]] == "nothing here\n",
           "X2 the listed files hold the marker instead of the old value; everything else kept", str(texts))
        ok(all(mtime(q) == t0[q] and os.stat(q).st_mode & 0o777 == SEED_MODES[q] for q in REDACT[:4]), "X2 each file's mode (600, 640, 664, 644) and modification time kept")
        ok(OLD not in open(rbak).read() and MARKER in open(rbak).read() and env_value() == newx,
           "X2 the rotation's backup is redacted last; the live env file is untouched")
        rc, out = tool("redact", "--yes")
        ok(rc == 0 and "redaction already complete" in out, "X6 a second redact reports completion", out)
        _, newx, rbak = rotated()
        open(REDACT[4], "w").write(f"REDIS_PASSWORD={newx}\n")
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "holds the new value; left alone" in out and open(REDACT[4]).read() == f"REDIS_PASSWORD={newx}\n" and OLD in open(rbak).read(),
           "X3 a listed file holding the new value fails and is untouched; the backup keeps the old value", out)
        _, newx, rbak = rotated()
        open(os.path.join(REC, "stray.txt"), "w").write(OLD)
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "stray.txt" in out and "FAIL apart from the rotation's backup" in out and OLD in open(rbak).read(),
           "X4 an unlisted file still holding the old value fails; the backup keeps the old value", out)
        _, newx, rbak = rotated()
        target = os.path.join(W, "outside-target.txt")
        open(target, "w").write(OLD)
        os.symlink(target, REDACT[5])
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "not a regular file of this user; left alone" in out and open(target).read() == OLD,
           "X5 a listed symlink is left alone, and so is its target", out)
        os.remove(target)
        _, newx, rbak = rotated()
        st = state()
        st["redis"]["passwords"].append(OLD)
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "FAIL the old value is known" in out and OLD in open(REDACT[0]).read() and OLD in open(rbak).read(),
           "X7 redact while redis still accepts the old value is refused; nothing changes", out)
        _, newx, rbak = rotated()
        os.symlink(ENV, REDACT[5])
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "the live env file is never redacted" in out and env_value() == newx, "X5b the live env file is never redacted, even through a link", out)

        reset()
        tool("pre")
        mark("unhealthy-auth-service")
        tool("rotate", "--yes")
        allvalues.append(env_value())
        first = os.path.join(L, "docker", bak_files()[0])
        os.remove(os.path.join(W, "unhealthy-auth-service"))
        tool("rollback", "--yes")
        tool("pre")
        rc0, out = tool("rotate", "--yes")
        allvalues.append(env_value())
        rc, out = tool("redact", "--yes")
        ok(rc0 == 0 and rc == 0 and OLD not in open(first).read() and "CHANGE_ME_redacted_after_rotation_20261013" in open(first).read(),
           "X9 after a rolled-back attempt and a passing one, redact also clears the first attempt's backup", out)
        _, newx, rbak = rotated()
        os.chmod(REC, 0o555)
        rc, out = tool("redact", "--yes")
        os.chmod(REC, 0o755)
        recs = [os.path.join(OUT, d, "redact.json") for d in os.listdir(OUT) if os.path.exists(os.path.join(OUT, d, "redact.json"))]
        ok(rc == 1 and "cannot be rewritten (PermissionError)" in out and recs and OLD in open(rbak).read(),
           "X10 an unwritable directory is a recorded FAIL, not a crash; the backup keeps the old value", out)

        # ---- interrupted rotations and their rollbacks
        reset()
        tool("pre")
        mark("unhealthy-auth-service")
        rc, out = tool("rotate", "--yes")
        new3 = env_value()
        allvalues.append(new3)
        ok(rc == 1 and "FAIL auth-service: wrapper exit 0, a new healthy container" in out and "STOP before redis was recreated" in out,
           "R3 a consumer that does not come back stops the rotation before redis", out)
        os.remove(os.path.join(W, "unhealthy-auth-service"))
        rc, out = tool("rollback", "--yes")
        ok(rc == 0 and env_value() == OLD and set(consumer_values().values()) == {OLD} and state()["redis"]["passwords"] == [OLD],
           "R3 rollback: the env file, the moved consumers and redis are back on the old value only", out)
        reset()
        tool("pre")
        mark("preflight-fail")
        rc, out = tool("rotate", "--yes")
        allvalues.append(state()["redis"]["passwords"][-1])
        ok(rc == 1 and "FAIL the release preflight still passes" in out and env_value() == OLD, "R4 a failing preflight restores the env file at once", out)
        os.remove(os.path.join(W, "preflight-fail"))
        rc, out = tool("rollback", "--yes")
        ok(rc == 0 and state()["redis"]["passwords"] == [OLD], "R4 rollback resets redis to the old password only (resetpass)", out)

        def interrupted(fault):
            reset()
            tool("pre")
            rc, out = tool("rotate", "--yes", extra={"RRT_FAULT": fault})
            allvalues.append(env_value())
            allvalues.extend(state()["redis"]["passwords"])
            return rc, out
        rc, out = interrupted("after-up:parking-service")
        ok(rc == 99 and consumer_values()["parking-service"] != OLD, "R15 setup: interrupted right after recreating parking-service (it holds the new value)", out)
        rc, out = tool("rollback", "--yes")
        ok(rc == 0 and env_value() == OLD and set(consumer_values().values()) == {OLD} and state()["redis"]["passwords"] == [OLD],
           "R15 rollback after an interruption recreates the moving consumer too and verifies the end state", out)
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL a rotate record exists, stopped before redis was recreated" in out and "rolled-back" in out,
           "R17 a second rollback is refused (the rotation is marked rolled-back)", out)
        rc, out = interrupted("after-up:parking-service")
        os.remove(os.path.join(L, "docker", bak_files()[0]))
        n_up = len(ups())
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL the rotation's backup exists and is readable" in out and env_value() != OLD and len(ups()) == n_up,
           "R16 rollback without the backup is refused and changes nothing", out)
        rc, out = interrupted("after-acl")
        ok(rc == 99 and len(state()["redis"]["passwords"]) == 2, "R18 setup: interrupted right after ACL SETUSER (two passwords)", out)
        rc, out = tool("rollback", "--yes")
        ok(rc == 0 and state()["redis"]["passwords"] == [OLD] and set(consumer_values().values()) == {OLD},
           "R18 rollback after an interruption in phase A resets redis to the old password only", out)
        rc, out = interrupted("after-env-write")
        subprocess.run([os.path.join(R, "scripts", "parkio-prod-compose.sh"), "up", "-d", "--no-build", "--no-deps", "gateway-service"], cwd=R,
                       capture_output=True, env={k: v for k, v in dict(env, PARKIO_ENV_FILE=ENV).items() if k != "LEAKY_SECRET"})
        archive_logs()
        rc, out = tool("rollback", "--yes")
        ok(rc == 0 and set(consumer_values().values()) == {OLD} and state()["redis"]["passwords"] == [OLD],
           "R24 rollback also recreates a consumer that moved outside the tool's lists (live state)", out)
        rc, out = interrupted("after-up:parking-service")
        mark("stale-env-parking-service")
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL parking-service: carries the previous value" in out, "R22 rollback that leaves a consumer on the new value fails its end-state check", out)
        rc, out = interrupted("after-up:parking-service")
        mark("resetpass-noop")
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL redis refuses the rotation's value" in out, "R23 rollback that leaves redis accepting the new value fails", out)
        reset()
        tool("pre")
        mark("redis-restart-during-c")
        rc, out = tool("rotate", "--yes")
        allvalues.append(env_value())
        ok(rc == 1 and "FAIL redis still accepts the new value before auth-service" in out and "STOP before redis was recreated" in out,
           "R20 a redis restart during phase C stops the rotation at the next consumer", out)
        os.remove(os.path.join(W, "redis-restart-during-c"))
        rc, out = tool("rollback", "--yes")
        ok(rc == 0 and set(consumer_values().values()) == {OLD} and state()["redis"]["passwords"] == [OLD], "R20 rollback restores the old value everywhere", out)
        reset()
        tool("pre")
        mark("redis-restart-during-c-gateway")
        rc, out = tool("rotate", "--yes")
        allvalues.append(env_value())
        ok(rc == 1 and "FAIL probe key written with the new value" in out and not state().get("redis_recreated"),
           "R21 a redis restart during the last consumer stops before the redis recreate (the probe write)", out)
        reset()
        tool("pre")
        mark("unhealthy-redis")
        rc, out = tool("rotate", "--yes")
        allvalues.append(env_value())
        ok(rc == 1 and "FAIL redis: wrapper exit 0, a new healthy container" in out and "emergency section" in out,
           "R5 redis not healthy after its recreate fails and points to the emergency section", out)
        n_up, pw = len(ups()), state()["redis"]["passwords"]
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL a rotate record exists, stopped before redis was recreated" in out and len(ups()) == n_up and
           state()["redis"]["passwords"] == pw, "R5b rollback after phase D is refused; nothing changes", out)
        for label, marker, expect in (("R6a an authentication error in a consumer's logs", "auth-error-log", "FAIL auth-service: healthy after the redis recreate"),
                                      ("R6b the probe key lost", "keys-lost", "FAIL redis: the probe key survived"),
                                      ("R6c another container changed at the redis recreate", "collateral-redis", "FAIL every other container unchanged"),
                                      ("R6d the old value still accepted after the recreate", "keeps-old", "FAIL redis: the old value is refused"),
                                      ("R6e another container changed at a consumer's recreate", "collateral-gamification-service",
                                       "FAIL gamification-service: every other container unchanged"),
                                      ("R10 a recreated consumer without the new value", "stale-env-auth-service",
                                       "FAIL auth-service: the new value in its environment, at the model's hash"),
                                      ("R11 a recreated redis that does not accept the new value", "redis-wrong-pw", "FAIL redis: the new value works"),
                                      ("R12 a recreated redis with the value in its healthcheck", "hc-leak", "FAIL redis: no value in docker inspect outside Env"),
                                      ("R13 a recreated redis with the value in its arguments", "argv-leak", "FAIL redis: no value in process arguments"),
                                      ("R14a a recreated redis on another image", "image-changed", "FAIL redis: at the model's hash, on the same image"),
                                      ("R14b a recreated redis off the model's hash", "hash-off", "FAIL redis: at the model's hash, on the same image"),
                                      ("R19 a consumer whose aggregate health fails after the redis recreate", "agg-503-after-redis-parking-service",
                                       "FAIL parking-service: reconnected with the new value")):
            reset()
            tool("pre")
            mark(marker)
            rc, out = tool("rotate", "--yes")
            allvalues.append(env_value())
            ok(rc == 1 and expect in out, label + " fails", out)
        rc, out = tool("status")
        ok(rc == 0 and "pre: PASS" in out, "S status lists the records", out)

        # ---- no value anywhere
        allvalues.extend(l.strip() for l in open(os.path.join(W, "seen.txt")) if l.strip())
        text = "\n".join(outputs)
        for root, _, files in os.walk(OUT):
            for f in files:
                text += open(os.path.join(root, f), encoding="utf-8").read()
        ok(not any(v and v in text for v in allvalues), "no old or new value in any output or record")
        archive_logs()
        argv = open(os.path.join(W, "argv-all.log")).read()
        ok(argv.count("\n") > 100 and not any(v and v in argv for v in allvalues), "no value in any process argument (docker, wrapper)")
        envs = [json.loads(l) for l in open(os.path.join(W, "env-all.log"))]
        ok(envs and not any("LEAKY_SECRET" in e["env"] for e in envs), "no child saw a variable outside the allowlist")
        auth = {e["argv0"] for e in envs if "REDISCLI_AUTH" in e["env"]}
        ok(auth == {"exec"}, "REDISCLI_AUTH reached only `docker exec` (redis-cli)", str(sorted(auth)))
    finally:
        shutil.rmtree(W, ignore_errors=True)
    print(f"=== summary: {sum(results)} ok, {len(results) - sum(results)} FAIL ===")
    return 0 if all(results) else 1


def redis_unchanged(st, old):
    return st["redis"]["passwords"] == [old]


if __name__ == "__main__":
    sys.exit(main())
