#!/usr/bin/env python3
"""Self-test of jwt-rotate.py with fakes and a real openssl.

Fakes, backed by one JSON state file:
- docker: ps, inspect, info, image inspect. Every argv and child environment is recorded.
- the release checkout: a git repository at R2, with a fake scripts/parkio-prod-compose.sh and preflight.
  - `config` renders the model from the env file the way Compose does: inside double quotes a doubled backslash
    becomes one, so the container receives the PEM with one backslash and an n per newline.
  - `config --hash` hashes each service's model.
  - `up` recreates one service; for gateway-service it reloads the gateway's JWKS cache.
- an HTTP server standing in for the public API:
  - /jwks serves the key that auth-service runs with;
  - /probe acts as the gateway: kid lookup in the gateway's cached keys, an openssl signature check, then issuer,
    audience, expiry and session_epoch. INVALID_TOKEN on any failure, otherwise 503 SESSION_EPOCH_UNAVAILABLE.
Marker files inject faults.

Pass criteria:
- every expected result holds;
- no key material (the base64 body of any key) appears in any output, record, or docker or wrapper argument;
- no child sees a variable from outside the allowlist.

  python3 -I jwt-rotate-selftest.py
"""
import base64
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
TOOL = os.path.join(HERE, "jwt-rotate.py")
results = []

FAKE_DOCKER = r'''#!/usr/bin/env python3
import json, os, sys
W = os.environ["JRT_W"]
a = sys.argv[1:]
open(os.path.join(W, "argv.log"), "a").write(json.dumps(a) + "\n")
open(os.path.join(W, "env.log"), "a").write(json.dumps({"argv0": a[0], "env": sorted(os.environ)}) + "\n")
st = json.load(open(os.path.join(W, "state.json")))
cs = st["containers"]
mark = lambda m: os.path.exists(os.path.join(W, m))
if a[0] == "info": print("29.8.1"); sys.exit(0)
if a[0] == "ps": print("\n".join(cs)); sys.exit(0)
if a[:2] == ["image", "inspect"]:
    iid = st["images"].get(a[-1])
    if iid and mark("tag-moved") and "auth" in a[-1]: iid = "sha256:" + "f" * 64
    if not iid: sys.exit(1)
    print(iid); sys.exit(0)
if a[0] == "inspect":
    out = []
    for i in a[1:]:
        c = cs[i]
        out.append({"Id": i, "Name": "/" + c["name"], "Image": c["image"],
                    "Config": {"Env": [f"{k}={v}" for k, v in c["env"].items()],
                               "Image": "other:tag" if mark("config-image-differs") and c["service"] == "gateway-service" else c.get("ref"),
                               "Labels": {"com.docker.compose.project": c["project"], "com.docker.compose.service": c["service"],
                                          "com.docker.compose.config-hash": c["hash"]}},
                    "State": {"Status": "running", "Health": {"Status": c["health"]}}})
    print(json.dumps(out)); sys.exit(0)
sys.exit(2)
'''

FAKE_WRAPPER = r'''#!/usr/bin/env python3
import hashlib, json, os, sys, time
W = os.environ["JRT_W"]
a = sys.argv[1:]
mark = lambda m: os.path.exists(os.path.join(W, m))
open(os.path.join(W, "argv.log"), "a").write(json.dumps(["wrapper"] + a) + "\n")
open(os.path.join(W, "env.log"), "a").write(json.dumps({"argv0": "wrapper", "env": sorted(os.environ)}) + "\n")
env = {}
for line in open(os.environ["PARKIO_ENV_FILE"], encoding="utf-8"):
    if "=" in line and not line.startswith("#"):
        k, v = line.rstrip("\n").split("=", 1)
        if len(v) >= 2 and v[0] == v[-1] == '"':
            v = v[1:-1].replace("\\\\", "\\")          # Compose: a doubled backslash inside double quotes becomes one
        env[k] = v
JWT = ("PARKIO_JWT_PRIVATE_KEY_PEM", "PARKIO_JWT_KEY_ID", "PARKIO_JWT_ADDITIONAL_PUBLIC_KEYS_JSON", "PARKIO_JWT_ISSUER", "PARKIO_JWT_AUDIENCE")
def service(svc):
    if svc == "auth-service":
        return {"image": "ghcr.io/x/auth", "environment": {k: env.get(k, "") for k in JWT}}
    if svc == "gateway-service":
        e = {"PARKIO_AUTH_JWKS_URI": "http://auth-service:8081/jwks"}
        return {"environment": e} if mark("gateway-build-only") else {"image": "ghcr.io/x/gateway", "environment": e}
    e = {"Y": "2"}
    if svc == "web" and mark("model-leak"): e["LEAK"] = env.get("PARKIO_JWT_PRIVATE_KEY_PEM", "")
    if svc == "web" and mark("env-affects-web"): e["X"] = hashlib.sha256(env.get("PARKIO_JWT_KEY_ID", "").encode()).hexdigest()[:8]
    return {"image": "ghcr.io/x/" + svc, "environment": e}
SERVICES = ("auth-service", "gateway-service", "web", "caddy")
def mhash(svc): return hashlib.sha256(json.dumps(service(svc), sort_keys=True).encode()).hexdigest()[:16]
if a[:2] == ["config", "--format"]:
    print(json.dumps({"services": {s: service(s) for s in SERVICES}})); sys.exit(0)
if a[:2] == ["config", "--hash"]:
    print("\n".join(f"{s} {mhash(s)}" for s in SERVICES)); sys.exit(0)
if a[:4] == ["up", "-d", "--no-build", "--no-deps"]:
    svc = a[-1]
    force = "--force-recreate" in a
    P = os.path.join(W, "state.json"); st = json.load(open(P))
    old = [i for i, c in st["containers"].items() if c["project"] == "parkio" and c["service"] == svc][0]
    if not force and st["containers"][old]["hash"] == mhash(svc):
        sys.exit(0)                                     # Compose: nothing to do when the hash is unchanged
    c = dict(st["containers"].pop(old))
    if not mark("stale-env-" + svc):
        c["env"] = dict(service(svc)["environment"])
    c["hash"] = mhash(svc)
    c["health"] = "unhealthy" if mark("unhealthy-" + svc) else "healthy"
    if svc == "gateway-service":
        auth = [x for x in st["containers"].values() if x["service"] == "auth-service"][0]
        keys = {auth["env"]["PARKIO_JWT_KEY_ID"]: auth["env"]["PARKIO_JWT_PRIVATE_KEY_PEM"]}
        if mark("gateway-keeps-old") or mark("gateway-rejects-new"):
            keys = dict(st["gateway_keys"], **({} if mark("gateway-rejects-new") else keys))
        st["gateway_keys"] = keys
    if mark("collateral-" + svc):
        cad = [i for i, x in st["containers"].items() if x["service"] == "caddy"][0]
        st["containers"]["c" * 64] = st["containers"].pop(cad)
    st["containers"][hashlib.sha256(f"{svc}{time.time()}".encode()).hexdigest()] = c
    json.dump(st, open(P + ".t", "w")); os.replace(P + ".t", P)
    sys.exit(0)
sys.exit(2)
'''


def ok(cond, label, extra=""):
    results.append(bool(cond))
    print(("ok   " if cond else "FAIL ") + label + ("" if cond else f"\n{extra[-2500:]}"))


def openssl(args, data=b"", fds=()):
    p = subprocess.run(["openssl"] + args, input=data, capture_output=True, pass_fds=fds)
    return p.returncode, p.stdout


def new_key():
    return openssl(["genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"])[1].decode()


def pem_of(container_value):
    return container_value.replace("\\n", "\n")


def modulus_n(pem_text):
    pub = openssl(["pkey", "-pubout"], pem_text.encode())[1]
    hexmod = openssl(["rsa", "-pubin", "-modulus", "-noout"], pub)[1].decode().strip().split("=", 1)[1]
    return base64.urlsafe_b64encode(bytes.fromhex(hexmod)).rstrip(b"=").decode()


def b64d(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def needle(pem_text):
    lines = pem_text.strip().splitlines()
    return lines[len(lines) // 2][:48]


class Api(http.server.BaseHTTPRequestHandler):
    W = None

    def log_message(self, *a):
        pass

    def send(self, status, body):
        raw = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        st = json.load(open(os.path.join(self.W, "state.json")))
        mark = lambda m: os.path.exists(os.path.join(self.W, m))
        auth = [c for c in st["containers"].values() if c["service"] == "auth-service"][0]
        if self.path == "/jwks":
            src = st["jwks_stale"] if mark("jwks-stale") else auth["env"]
            keys = [{"kty": "RSA", "kid": src["PARKIO_JWT_KEY_ID"], "n": modulus_n(pem_of(src["PARKIO_JWT_PRIVATE_KEY_PEM"])), "e": "AQAB"}]
            if mark("jwks-extra"):
                keys.append({"kty": "RSA", "kid": "extra", "n": "AQAB", "e": "AQAB"})
            return self.send(200, {"keys": keys})
        if self.path == "/probe":
            token = (self.headers.get("Authorization") or "").removeprefix("Bearer ").strip()
            try:
                h, pl, sig = token.split(".")
                header, claims = json.loads(b64d(h)), json.loads(b64d(pl))
            except ValueError:
                return self.send(401, {"code": "INVALID_TOKEN"})
            key = st["gateway_keys"].get(header.get("kid"))
            if not key:
                return self.send(401, {"code": "INVALID_TOKEN"})
            pub = openssl(["pkey", "-pubout"], pem_of(key).encode())[1]
            r1, w1 = os.pipe(); os.write(w1, pub); os.close(w1)
            r2, w2 = os.pipe(); os.write(w2, b64d(sig)); os.close(w2)
            rc, _ = openssl(["dgst", "-sha256", "-verify", f"/dev/fd/{r1}", "-signature", f"/dev/fd/{r2}"], f"{h}.{pl}".encode(), (r1, r2))
            os.close(r1); os.close(r2)
            env = auth["env"]
            if (rc != 0 or claims.get("iss") != env["PARKIO_JWT_ISSUER"] or claims.get("aud") != env["PARKIO_JWT_AUDIENCE"]
                    or claims.get("exp", 0) < time.time()):
                return self.send(401, {"code": "INVALID_TOKEN"})
            if mark("probe-forwards") or claims.get("session_epoch") != -1:
                return self.send(200, {"forwarded": True})
            return self.send(503, {"code": "SESSION_EPOCH_UNAVAILABLE"})
        self.send(404, {})


def git(repo, *args):
    p = subprocess.run(["git", "-C", repo, "-c", "user.name=t", "-c", "user.email=t@t"] + list(args), capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit(f"git {args}: {p.stderr}")
    return p.stdout.strip()


def main():
    W = tempfile.mkdtemp(prefix="jrt-")
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Api)
    Api.W = W
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base_url = f"http://127.0.0.1:{server.server_address[1]}"
    try:
        R, L, OUT, BIN = (os.path.join(W, d) for d in ("release", "live", "out", "bin"))
        for d in (os.path.join(R, "scripts"), os.path.join(L, "docker"), BIN):
            os.makedirs(d)
        open(os.path.join(BIN, "docker"), "w").write(FAKE_DOCKER)
        os.chmod(os.path.join(BIN, "docker"), 0o755)
        for name, body in (("parkio-prod-compose.sh", FAKE_WRAPPER),
                           ("preflight-hosted-beta.sh", '#!/bin/sh\n[ -e "$JRT_W/preflight-fail" ] && exit 1\nexit 0\n')):
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
        BACKUPS = [os.path.join(L, "docker", ".env.azure-hosted-beta.bak-old1"), os.path.join(L, "docker", ".env.azure-hosted-beta.bak-rrt-x")]
        EXTRA = [os.path.join(L, "artifact.env"), os.path.join(L, "checkpoint.env")]
        MISSING = os.path.join(L, "never-there.env")
        MARKER = "CHANGE_ME_redacted_after_rotation_20261013"
        OLD = new_key()
        OTHER = new_key()
        keys_seen = [OLD, OTHER]
        OLD_FILE = OLD.replace("\n", "\\\\n")
        env = {"PATH": BIN + ":" + os.environ["PATH"], "HOME": W, "LANG": "C.UTF-8", "LEAKY_SECRET": "x", "JRT_TEST": "1", "JRT_W": W,
               "JRT_R2": r2, "JRT_HOST": socket.gethostname(), "JRT_RELEASE": R, "JRT_ENV_FILE": ENV, "JRT_OUT": OUT,
               "JRT_JWKS_URL": base_url + "/jwks", "JRT_PROBE_URL": base_url + "/probe",
               "JRT_NOW": str(calendar.timegm(time.strptime("2026-10-13 12:00", "%Y-%m-%d %H:%M"))),
               "JRT_HEALTH_WAIT": "3", "JRT_POLL": "0.1", "JRT_REDACT_GLOB": os.path.join(L, "docker", ".env.azure-hosted-beta.bak-*"),
               "JRT_REDACT_EXTRA": ":".join(EXTRA + [MISSING]), "JRT_SCAN_ROOTS": L}
        outputs = []

        def env_text(pem_file_form, kid="parkio-auth-rs256-1", add=""):
            return (f'PARKIO_DOMAIN=parkio.dev\nREDIS_PASSWORD=r3d1s-value-0123456789\nPARKIO_JWT_PRIVATE_KEY_PEM="{pem_file_form}"\n'
                    f'PARKIO_JWT_KEY_ID="{kid}"\nPARKIO_JWT_ISSUER=parkio-auth\nPARKIO_JWT_AUDIENCE=parkio-api\n'
                    f'PARKIO_JWT_ADDITIONAL_PUBLIC_KEYS_JSON={add}\n')

        def archive_logs():
            for f in ("argv.log", "env.log"):
                src = os.path.join(W, f)
                if os.path.exists(src):
                    with open(os.path.join(W, f.replace(".log", "-all.log")), "a") as dst:
                        dst.write(open(src).read())
                    os.remove(src)

        def reset(checkout=r2, env_pem_file=None, add="", auth_pem=None, extra_carrier=False, gateway_build_only=False):
            for f in os.listdir(W):
                if f in ("tag-moved", "model-leak", "env-affects-web", "jwks-stale", "jwks-extra", "gateway-keeps-old", "gateway-rejects-new",
                         "probe-forwards", "preflight-fail", "gateway-build-only", "config-image-differs") or\
                    f.startswith(("unhealthy-", "collateral-", "stale-env-")):
                    os.remove(os.path.join(W, f))
            if gateway_build_only:
                open(os.path.join(W, "gateway-build-only"), "w").write("x")
            archive_logs()
            shutil.rmtree(OUT, ignore_errors=True)
            for f in os.listdir(os.path.join(L, "docker")):
                os.remove(os.path.join(L, "docker", f))
            for f in os.listdir(L):
                if os.path.isfile(os.path.join(L, f)) or os.path.islink(os.path.join(L, f)):
                    os.remove(os.path.join(L, f))
            git(R, "checkout", "-q", "--detach", checkout)
            for path, text in [(ENV, env_text(env_pem_file or OLD_FILE, add=add))] + [(p, env_text(OLD_FILE)) for p in BACKUPS + EXTRA]:
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                os.write(fd, text.encode())
                os.close(fd)
            auth_env = {"PARKIO_JWT_PRIVATE_KEY_PEM": (auth_pem or OLD).replace("\n", "\\n"), "PARKIO_JWT_KEY_ID": "parkio-auth-rs256-1",
                        "PARKIO_JWT_ADDITIONAL_PUBLIC_KEYS_JSON": add, "PARKIO_JWT_ISSUER": "parkio-auth", "PARKIO_JWT_AUDIENCE": "parkio-api"}
            cs = {}
            for i, svc in enumerate(("auth-service", "gateway-service", "web", "caddy")):
                e = auth_env if svc == "auth-service" else ({"PARKIO_AUTH_JWKS_URI": "http://auth-service:8081/jwks"} if svc == "gateway-service" else {"Y": "2"})
                cs[f"{i}" * 64] = {"name": f"parkio-{svc}-1", "project": "parkio", "service": svc, "image": f"sha256:{i}{'0' * 63}",
                                   "ref": ("parkio-gateway-service" if gateway_build_only and svc == "gateway-service" else
                                           "ghcr.io/x/" + ("auth" if svc == "auth-service" else "gateway" if svc == "gateway-service" else svc)),
                                   "hash": "", "health": "healthy", "env": dict(e)}
            if extra_carrier:
                cs["e" * 64] = {"name": "other-app", "project": "other", "service": "app", "image": "x", "hash": "h", "health": "healthy",
                                "env": {"COPY": OLD.replace("\n", "\\n")}}
            images = {c["ref"]: c["image"] for c in cs.values() if c["project"] == "parkio"}
            st = {"containers": cs, "images": images, "gateway_keys": {"parkio-auth-rs256-1": OLD.replace("\n", "\\n")},
                  "jwks_stale": dict(auth_env)}
            json.dump(st, open(os.path.join(W, "state.json"), "w"))
            p = subprocess.run([os.path.join(R, "scripts", "parkio-prod-compose.sh"), "config", "--hash", "*"], cwd=R, capture_output=True, text=True,
                               env={k: v for k, v in dict(env, PARKIO_ENV_FILE=ENV).items() if k != "LEAKY_SECRET"})
            hashes = dict(l.split() for l in p.stdout.splitlines())
            for c in st["containers"].values():
                if c["project"] == "parkio":
                    c["hash"] = hashes[c["service"]]
            json.dump(st, open(os.path.join(W, "state.json"), "w"))
            archive_logs()

        def tool(*args, extra=None):
            p = subprocess.run([sys.executable, "-I", TOOL] + list(args), capture_output=True, text=True, env=dict(env, **(extra or {})), timeout=300)
            outputs.append(p.stdout + p.stderr)
            return p.returncode, p.stdout + p.stderr

        def state():
            return json.load(open(os.path.join(W, "state.json")))

        def mark(m):
            open(os.path.join(W, m), "w").write("x")

        def env_pem_file():
            for line in open(ENV):
                if line.startswith("PARKIO_JWT_PRIVATE_KEY_PEM="):
                    return line.rstrip("\n").split("=", 1)[1].strip('"')

        def env_kid():
            for line in open(ENV):
                if line.startswith("PARKIO_JWT_KEY_ID="):
                    return line.rstrip("\n").split("=", 1)[1].strip('"')

        def auth():
            return [c for c in state()["containers"].values() if c["service"] == "auth-service"][0]

        def ups():
            p = os.path.join(W, "argv.log")
            return [json.loads(l) for l in open(p) if l.startswith('["wrapper", "up"')] if os.path.exists(p) else []

        def jbak():
            return [f for f in os.listdir(os.path.join(L, "docker")) if ".bak-jrt-" in f]

        def rotated():
            reset()
            tool("pre")
            rc, out = tool("rotate", "--yes")
            keys_seen.append(env_pem_file().replace("\\\\n", "\n"))
            return rc, out

        # ---- usage
        for args, label in (([], "no command"), (["pre", "--yes"], "pre --yes"), (["rotate"], "rotate without --yes"), (["rollback"], "rollback without --yes"),
                            (["redact"], "redact without --yes"), (["rotate", "--yes", "x"], "an extra argument")):
            rc, out = tool(*args)
            ok(rc == 2, f"U usage refusal: {label}", out)

        # ---- pre
        reset()
        rc, out = tool("pre")
        ok(rc == 0 and "jwt rotation pre: PASS" in out, "P1 pre passes", out)
        for label, kw, marker, expect in (
                ("P2 a checkout other than R2", dict(checkout=later), None, "FAIL the release checkout is clean at R2"),
                ("P3 an env PEM other than auth-service's", dict(env_pem_file=OTHER.replace("\n", "\\\\n")), None,
                 "FAIL auth-service runs with the env file's PEM and kid"),
                ("P4 additional public keys set", dict(add="[{}]"), None, "FAIL no additional (previous) public keys"),
                ("P5 another container carrying the PEM", dict(extra_carrier=True), None, "FAIL only auth-service carries the PEM"),
                ("P6 another service of the model carrying the PEM", {}, "model-leak", "FAIL in the model, only auth-service's environment"),
                ("P7 a moved image tag", {}, "tag-moved", "FAIL auth-service running, healthy, at the model's hash"),
                ("P8 a JWKS with another key", {}, "jwks-extra", "FAIL the public JWKS lists exactly one key")):
            reset(**kw)
            if marker:
                mark(marker)
            rc, out = tool("pre")
            ok(rc == 1 and expect in out, label + " fails", out)
        reset()
        st = state()
        [c for c in st["containers"].values() if c["service"] == "auth-service"][0]["hash"] = "pending-other-change"
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL auth-service running, healthy, at the model's hash" in out, "P10 auth-service off the model's hash fails", out)
        reset()
        rc, out = tool("pre", extra={"JRT_HOST": "some-other-host"})
        ok(rc == 1 and "FAIL host some-other-host" in out, "P11 another host fails", out)
        reset(gateway_build_only=True)
        rc, out = tool("pre")
        ok(rc == 0, "P12 a build-only gateway (no image in the model) passes under its implicit name", out)
        reset()
        mark("config-image-differs")
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL gateway-service running, healthy, at the model's hash, its image resolving" in out,
           "P13 a container not running the model's image reference fails", out)
        reset()
        rc, out = tool("pre", extra={"JRT_NOW": str(calendar.timegm(time.strptime("2026-10-13 03:40", "%Y-%m-%d %H:%M")))})
        ok(rc == 1 and "FAIL outside 03:00-04:30 UTC" in out, "P9 the backup window is refused", out)

        # ---- rotate refusals
        reset()
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the latest pre passed within an hour" in out and env_pem_file() == OLD_FILE, "R1 rotate without a pre changes nothing", out)
        reset()
        tool("pre")
        open(ENV, "a").write("NEW=1\n")
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the env file is unchanged since that pre" in out and env_pem_file() == OLD_FILE, "R1b an env change after pre blocks rotate", out)
        reset()
        tool("pre")
        st = state()
        cad = [i for i, c in st["containers"].items() if c["service"] == "caddy"][0]
        st["containers"]["d" * 64] = st["containers"].pop(cad)
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the containers are the ones pre recorded" in out and env_pem_file() == OLD_FILE, "R1c a container change after pre blocks rotate", out)

        reset()
        tool("pre")
        rec = [os.path.join(OUT, d, "pre.json") for d in os.listdir(OUT)][0]
        r = json.load(open(rec))
        r["stamp"] = time.strftime("%Y%m%dT%H%M%S000Z", time.gmtime(time.time() - 7200))
        open(rec, "w").write(json.dumps(r))
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the latest pre passed within an hour" in out and env_pem_file() == OLD_FILE, "R1d a pre older than an hour blocks rotate", out)

        # ---- the full rotation
        rc, out = rotated()
        newf = env_pem_file()
        new = newf.replace("\\\\n", "\n")
        ok(rc == 0 and "jwt rotation rotate: PASS" in out, "R2 the full rotation passes", out)
        ok(new.startswith("-----BEGIN PRIVATE KEY-----") and new != OLD and newf.count("\\\\n") == new.count("\n") and "\n" not in newf,
           "R2 the env file holds a new PKCS#8 key in the file form (two backslashes and an n per newline, one line)")
        ok(env_kid().startswith("parkio-auth-rs256-2026") and env_kid() != "parkio-auth-rs256-1", "R2 the env file holds a new kid", env_kid())
        ok(auth()["env"]["PARKIO_JWT_PRIVATE_KEY_PEM"] == new.replace("\n", "\\n") and auth()["env"]["PARKIO_JWT_KEY_ID"] == env_kid(),
           "R2 auth-service runs with the new key (container form) and kid")
        ok(state()["gateway_keys"] == {env_kid(): new.replace("\n", "\\n")}, "R2 the gateway's cache holds only the new key")
        order = [(u[-1], "--force-recreate" in u) for u in ups()]
        ok(order == [("auth-service", False), ("gateway-service", True)], "R2 auth-service first, then gateway-service with --force-recreate", str(order))
        bak = jbak()
        ok(len(bak) == 1 and open(os.path.join(L, "docker", bak[0])).read() == env_text(OLD_FILE) and
           oct(os.stat(os.path.join(L, "docker", bak[0])).st_mode & 0o777) == "0o600", "R2 the backup holds the old env, mode 600")
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL a rotation stopped in phase C, D or E" in out and env_pem_file() == newf, "R2b rollback after a passing rotation is refused", out)

        # ---- failures
        reset()
        tool("pre")
        mark("preflight-fail")
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL the release preflight still passes" in out and env_pem_file() == OLD_FILE and len(ups()) == 0,
           "R3 a failing preflight restores the env file; nothing is recreated", out)
        reset()
        tool("pre")
        mark("env-affects-web")
        rc, out = tool("rotate", "--yes")
        ok(rc == 1 and "FAIL only auth-service's model hash changed" in out and env_pem_file() == OLD_FILE, "R4 another service's model changing restores the env file", out)
        reset()
        tool("pre")
        mark("unhealthy-auth-service")
        rc, out = tool("rotate", "--yes")
        keys_seen.append(env_pem_file().replace("\\\\n", "\n"))
        ok(rc == 1 and "FAIL auth-service: wrapper exit 0, a new healthy container" in out, "R5 an unhealthy auth-service stops the rotation", out)
        os.remove(os.path.join(W, "unhealthy-auth-service"))
        rc, out = tool("rollback", "--yes")
        ok(rc == 0 and env_pem_file() == OLD_FILE and auth()["env"]["PARKIO_JWT_KEY_ID"] == "parkio-auth-rs256-1",
           "R5 rollback restores the previous key and kid and recreates auth-service", out)
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "rolled-back" in out, "R5b a second rollback is refused (the rotation is marked rolled-back)", out)
        reset()
        tool("pre")
        mark("unhealthy-auth-service")
        tool("rotate", "--yes")
        keys_seen.append(env_pem_file().replace("\\\\n", "\n"))
        os.remove(os.path.join(W, "unhealthy-auth-service"))
        mark("stale-env-auth-service")
        rc, out = tool("rollback", "--yes")
        ok(rc == 1 and "FAIL auth-service runs with the previous PEM and kid" in out,
           "R5c a rollback that leaves auth-service on the new key fails its end-state check", out)
        def phase():
            return [json.load(open(os.path.join(OUT, d, "rotate-progress.json"))) for d in sorted(os.listdir(OUT))
                    if os.path.exists(os.path.join(OUT, d, "rotate-progress.json"))][-1]["phase"]
        for label, marker, expect, stops in (
                ("R6 a JWKS still serving the old key", "jwks-stale", "FAIL the public JWKS lists exactly the new kid", "C"),
                ("R7 a gateway that still accepts the old key", "gateway-keeps-old", "FAIL a token signed with the old key under the old kid is refused", "E"),
                ("R8 a gateway that forwards the new-key probe", "probe-forwards", "FAIL a token signed with the new key passes the signature", "E"),
                ("R9 a gateway that rejects the new key", "gateway-rejects-new", "FAIL a token signed with the new key passes the signature", "E"),
                ("R10 another container changing at the auth recreate", "collateral-auth-service", "FAIL auth-service: every other container unchanged", "C"),
                ("R10b another container changing at the gateway recreate", "collateral-gateway-service", "FAIL gateway-service: every other container unchanged", "D"),
                ("R12 auth-service recreated without the new key", "stale-env-auth-service", "FAIL auth-service: the new PEM and kid in its environment", "C"),
                ("R11 an unhealthy gateway", "unhealthy-gateway-service", "FAIL gateway-service: wrapper exit 0", "D")):
            reset()
            tool("pre")
            mark(marker)
            rc, out = tool("rotate", "--yes")
            keys_seen.append(env_pem_file().replace("\\\\n", "\n"))
            recreated = [u[-1] for u in ups()]
            ok(rc == 1 and expect in out and phase() == stops and (recreated == ["auth-service"] if stops == "C" else len(recreated) == 2),
               f"{label} fails and stops in phase {stops}", out + str(recreated))
        os.remove(os.path.join(W, "unhealthy-gateway-service"))
        rc, out = tool("rollback", "--yes")
        ok(rc == 0 and env_pem_file() == OLD_FILE and [u[-1] for u in ups()][-2:] == ["auth-service", "gateway-service"],
           "R11 rollback after the gateway recreate restores the env file and recreates auth-service, then gateway-service", out)

        # ---- redact
        reset()
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "FAIL the latest rotation passed" in out and OLD_FILE in open(BACKUPS[0]).read(), "X1 redact before a rotation is refused", out)
        rotated()
        cur = env_pem_file()
        rbak = os.path.join(L, "docker", jbak()[0])
        mt = {p: os.stat(p).st_mtime_ns for p in BACKUPS + EXTRA}
        rc, out = tool("redact", "--yes")
        ok(rc == 0 and "jwt rotation redact: PASS" in out, "X2 redact after a passing rotation passes", out)
        ok(all(OLD_FILE not in open(p).read() and MARKER in open(p).read() for p in BACKUPS + EXTRA + [rbak]) and env_pem_file() == cur,
           "X2 every backup, the extra files and the rotation's backup hold the marker; the live env keeps the new key")
        ok(all(os.stat(p).st_mtime_ns == mt[p] for p in BACKUPS + EXTRA), "X2 modification times kept")
        rc, out = tool("redact", "--yes")
        ok(rc == 0 and "redaction already complete" in out, "X5 a second redact reports completion", out)
        rotated()
        rbak = os.path.join(L, "docker", jbak()[0])
        open(os.path.join(L, "stray.pem"), "w").write(OLD)
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "stray.pem" in out and OLD_FILE in open(rbak).read(), "X3 a stray copy of the old key (real PEM form) fails the scan; the backup is kept", out)
        rotated()
        rbak = os.path.join(L, "docker", jbak()[0])
        open(EXTRA[1], "w").write(env_text(env_pem_file()))
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "holds the current key; left alone" in out and OLD_FILE in open(rbak).read(), "X4 a listed file holding the current key is left alone", out)

        rotated()
        rbak = os.path.join(L, "docker", jbak()[0])
        mark("jwks-stale")
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "FAIL the old key is known" in out and OLD_FILE in open(BACKUPS[0]).read(), "X6 redact while the JWKS still lists the old kid is refused", out)
        os.remove(os.path.join(W, "jwks-stale"))
        rotated()
        rbak = os.path.join(L, "docker", jbak()[0])
        target = os.path.join(W, "outside.env")
        open(target, "w").write(env_text(OLD_FILE))
        os.symlink(target, os.path.join(L, "docker", ".env.azure-hosted-beta.bak-link"))
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "not a regular file of this user; left alone" in out and OLD_FILE in open(target).read() and OLD_FILE in open(rbak).read(),
           "X7 a listed symlink and its target are left alone; the backup is kept", out)
        os.remove(target)
        rotated()
        os.symlink(ENV, os.path.join(L, "docker", ".env.azure-hosted-beta.bak-live"))
        cur = env_pem_file()
        rc, out = tool("redact", "--yes")
        ok(rc == 1 and "the live env file is never redacted" in out and env_pem_file() == cur, "X8 the live env file is never redacted, even through a link", out)

        rotated()
        rbak = os.path.join(L, "docker", jbak()[0])
        os.chmod(os.path.join(L, "docker"), 0o555)
        rc, out = tool("redact", "--yes")
        os.chmod(os.path.join(L, "docker"), 0o755)
        ok(rc == 1 and "cannot be rewritten (PermissionError)" in out and OLD_FILE in open(rbak).read(),
           "X9 an unwritable directory is a recorded FAIL, not a crash; the backup keeps the old key", out)

        # ---- no key material anywhere
        archive_logs()
        text = "\n".join(outputs)
        for root, _, files in os.walk(OUT):
            for f in files:
                text += open(os.path.join(root, f), encoding="utf-8").read()
        needles = [needle(k) for k in keys_seen if k.startswith("-----BEGIN")]
        ok(len(needles) >= 10 and not any(n in text for n in needles), f"no key material in any output or record ({len(needles)} keys)")
        argv = open(os.path.join(W, "argv-all.log")).read()
        ok(argv.count("\n") > 50 and not any(n in argv for n in needles), "no key material in any docker or wrapper argument")
        envs = [json.loads(l) for l in open(os.path.join(W, "env-all.log"))]
        ok(envs and not any("LEAKY_SECRET" in e["env"] for e in envs), "no child saw a variable outside the allowlist")
    finally:
        server.shutdown()
        shutil.rmtree(W, ignore_errors=True)
    print(f"=== summary: {sum(results)} ok, {len(results) - sum(results)} FAIL ===")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
