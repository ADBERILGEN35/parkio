#!/usr/bin/env python3
"""Self-test of w1-web-deploy.py with fakes.

Fakes:
- docker, backed by a JSON state file. The new image's inspect .Id is a manifest-like digest; its config digest is known
  only to the guard, as on the host's containerd store.
- the production wrapper. `config --hash web` is derived from the active checkout's pin file, and `up` sets the
  container's label the same way unless drift is injected.
- the F-05 guard, which writes evidence from the state.
- systemctl, sudo, and an HTTP server standing in for app.parkio.dev.

The release checkout is a real git clone of a local bare origin that carries the api branch and the release branch. The
bundle comparison runs the real web-bundle-compare.py with the real pinned esbuild (fetch-esbuild-0.27.4.sh). docker and
git record the environment they receive, and the guard records its arguments.

  python3 -I w1-web-deploy-selftest.py --esbuild ESBUILD   (from the directory holding w1-web-deploy.py and
                                                            web-bundle-compare.py; ESBUILD = the pinned binary)
"""
import hashlib
import http.server
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading

HERE = os.path.dirname(os.path.abspath(__file__))
OLD_KEY, NEW_KEY = "OldSyntheticKey00001", "NewSyntheticKey00002"
OLD_ID = "sha256:" + "a" * 64          # running image as the daemon reports it
NEW_ID = "sha256:" + "b" * 64          # the new image's daemon id (manifest digest on containerd)
NEW_CONFIG = "sha256:" + "8" * 64      # the accepted image id = config digest, established only by the guard
DIGEST = "sha256:" + "d" * 64
OTHER_DIGEST = "sha256:" + "e" * 64
OLD_DIGEST = "sha256:" + "c" * 64
REPO = "ghcr.io/adberilgen35/parkio/web"
KFP = "0e1413b0e161"                   # the --key-fp under test (only a fingerprint; the fakes never see a key)
FORBIDDEN_ENV = ("COMPOSE_PROJECT_NAME", "COMPOSE_PROFILES", "DOCKER_HOST", "DOCKER_CONTEXT", "PARKIO_COMPOSE_FILES_LIST",
                 "PARKIO_GMP_RECOVERY", "PARKIO_SKIP_WEB_MAP_GUARD")
results = []


def ok(cond, label, extra=""):
    results.append(bool(cond))
    print(("ok   " if cond else "FAIL ") + label + ("" if cond else f"\n{extra}"))


FAKE_DOCKER = r'''#!/usr/bin/env python3
import json, os, shutil, sys
W = os.environ["W1ST_W"]
SP = os.path.join(W, "state.json")
st = json.load(open(SP))
def save(): json.dump(st, open(SP, "w"), indent=1)
a = sys.argv[1:]
open(os.path.join(W, "docker.log"), "a").write(" ".join(a) + "\n")
open(os.path.join(W, "docker.env"), "a").write(json.dumps(sorted(os.environ)) + "\n")
def resolve(ref):
    return st["images"].get(ref) or (ref if ref in st["imageInfo"] else None)
if a[0] == "info":
    print("29.8.1"); sys.exit(0)
if a[0] == "ps":
    for c in st["containers"]: print(c["Id"])
    sys.exit(0)
if a[0] == "inspect":
    print(json.dumps([c for c in st["containers"] if c["Id"] in a[1:]])); sys.exit(0)
if a[0] == "image" and a[1] == "inspect":
    if a[2] == "--format":
        iid = resolve(a[4])
        if not iid: sys.exit(1)
        print(iid); sys.exit(0)
    iid = resolve(a[2])
    if not iid: sys.exit(1)
    print(json.dumps([st["imageInfo"][iid]])); sys.exit(0)
if a[0] == "tag":
    st["images"][a[2]] = resolve(a[1]); save(); sys.exit(0)
if a[0] == "pull":
    ref = a[-1]
    if ref not in st["registry"]: sys.exit(1)
    st["images"][ref] = st["registry"][ref]; save(); sys.exit(0)
if a[0] == "create":
    st.setdefault("created", {})[a[2]] = resolve(a[3]); save(); print("c" * 64); sys.exit(0)
if a[0] == "cp":
    name, path = a[1].split(":", 1)
    src = os.path.join(W, "fs", st["created"][name][7:19], path.lstrip("/"))
    if os.path.isdir(src): shutil.copytree(src, a[2], symlinks=True)       # docker cp keeps links as links
    elif os.path.isfile(src): shutil.copy2(src, a[2], follow_symlinks=False)
    else: sys.exit(1)
    sys.exit(0)
if a[0] == "rm":
    st["created"].pop(a[-1], None); save(); sys.exit(0)
sys.exit(2)
'''

FAKE_WRAPPER = r'''#!/usr/bin/env python3
import hashlib, json, os, sys
W = os.environ["W1ST_W"]
SP = os.path.join(W, "state.json")
st = json.load(open(SP))
pin_text = open(os.path.join(os.getcwd(), "docker", "docker-compose.web-release-pin.yml")).read()
model_hash = "h-" + hashlib.sha256(pin_text.encode()).hexdigest()[:16]
if sys.argv[1:] == ["config", "--hash", "web"]:
    print("web " + model_hash); sys.exit(0)
open(os.path.join(W, "wrapper.calls"), "a").write(json.dumps({"args": sys.argv[1:], "env": sorted(os.environ),
    "envFile": os.environ.get("PARKIO_ENV_FILE"), "fp": os.environ.get("PARKIO_WEB_EXPECTED_MAP_KEY_FINGERPRINT")}) + "\n")
if st.get("wrapperRc"): sys.exit(st["wrapperRc"])
digest = [l.split("@", 1)[1].strip() for l in pin_text.splitlines() if "image:" in l][0]
ref = "ghcr.io/adberilgen35/parkio/web@" + digest
iid = st["images"].get(ref) or st["registry"].get(ref)
web = [c for c in st["containers"] if c["Config"]["Labels"]["com.docker.compose.service"] == "web"][0]
web["Id"] = ("f" if web["Id"].startswith("1") else "1") * 64
web["Image"] = st.get("wrongImage") or iid
web["Config"]["Image"] = ref
web["Config"]["Labels"]["com.docker.compose.config-hash"] = "drifted" if st.get("labelDrift") else model_hash
web["State"] = {"Status": "running", "Health": {"Status": "healthy"}}
if st.get("touchOther"):
    st["containers"][1]["Id"] = "9" * 64
json.dump(st, open(SP, "w"), indent=1)
'''

FAKE_GUARD = r'''#!/usr/bin/env bash
set -euo pipefail
W="$W1ST_W"; EV=""
python3 -c 'import json, sys; open(sys.argv[1], "a").write(json.dumps(sys.argv[2:]) + "\n")' "$W/guard.args" "$@"
while [ $# -gt 0 ]; do case "$1" in --evidence-out) EV="$2"; shift 2 ;; *) shift ;; esac; done
python3 - "$W/state.json" "$EV" "$W/guard.env" <<'PY'
import json, os, sys
st = json.load(open(sys.argv[1]))
g = st["guard"]
open(sys.argv[2], "w").write(json.dumps({"configDigest": g["configDigest"], "configDigestStatus": "established",
                                          "fingerprintCheck": g["fingerprintCheck"], "platform": "linux/amd64"}))
open(sys.argv[3], "a").write(json.dumps(sorted(os.environ)) + "\n")
print("web-map-deploy-guard: " + ("PASS" if g["rc"] == 0 else "BLOCKED") + " fingerprintCheck=" + g["fingerprintCheck"])
sys.exit(g["rc"])
PY
'''


ENV_BODIES = {   # the key-bearing chunk; "renamed" differs only in local names, "semantic" also in behaviour
    "base": 'const e={{VITE_MAPTILER_KEY:"{key}"}};function g(a,b){{return a*b+a}}export{{e,g}};',
    "renamed": 'const e={{VITE_MAPTILER_KEY:"{key}"}};function g(b,a){{return b*a+b}}export{{e,g}};',
    "semantic": 'const e={{VITE_MAPTILER_KEY:"{key}"}};function g(a,b){{return a*b+b}}export{{e,g}};',
}


def bundle(root, key, env_hash, cache, apk_expat="2.8.5-r0", extra_js=False, nginx_conf="worker_processes auto;\n", apk_other=False,
           nginx_mode=0o644, env_variant="base", entrypoint="#!/bin/sh\nexec \"$@\"\n", extra_entrypoint_d=False,
           modules_target="/usr/lib/nginx/modules"):
    html = os.path.join(root, "usr/share/nginx/html")
    os.makedirs(os.path.join(html, "assets"))
    open(os.path.join(html, "index.html"), "w").write('<div id="root"></div><script src="/assets/app-AAAAAAAA.js"></script>')
    open(os.path.join(html, f"assets/env-{env_hash}.js"), "w").write(ENV_BODIES[env_variant].format(key=key))
    open(os.path.join(html, "assets/app-AAAAAAAA.js"), "w").write(f'import "./env-{env_hash}.js";console.log(1);' + ("x();" if extra_js else ""))
    open(os.path.join(html, "sw.js"), "w").write(f"const CACHE_VERSION = '{cache}';\n")
    files = [("etc/nginx/templates/default.conf.template", "server {}\n"), ("docker-entrypoint.d/15-parkio-web-csp.envsh", "#!/bin/sh\n"),
             ("etc/nginx/nginx.conf", nginx_conf), ("etc/nginx/mime.types", "types {}\n"), ("docker-entrypoint.sh", entrypoint)]
    if extra_entrypoint_d:
        files.append(("docker-entrypoint.d/99-extra.sh", "#!/bin/sh\n"))
    os.makedirs(os.path.join(root, "etc/nginx"), exist_ok=True)
    os.symlink(modules_target, os.path.join(root, "etc/nginx/modules"))      # as in the nginx image: a link, never followed
    for f, body in files:
        os.makedirs(os.path.dirname(os.path.join(root, f)), exist_ok=True)
        open(os.path.join(root, f), "w").write(body)
        os.chmod(os.path.join(root, f), nginx_mode if f == "etc/nginx/nginx.conf" else (0o755 if f == "docker-entrypoint.sh" else 0o644))
    os.makedirs(os.path.join(root, "lib/apk/db"))
    open(os.path.join(root, "lib/apk/db/installed"), "w").write(
        f"P:libexpat\nV:{apk_expat}\n\nP:nginx\nV:1.30.5-r1\n\nP:musl\nV:{'1.2.6-r3' if apk_other else '1.2.6-r2'}\n\n")


def container(cid, svc, image, health="healthy"):
    return {"Id": cid, "Image": image, "Name": "/parkio-" + svc, "State": {"Status": "running", "Health": {"Status": health}},
            "Config": {"Image": image, "Labels": {"com.docker.compose.project": "parkio", "com.docker.compose.service": svc,
                                                  "com.docker.compose.config-hash": "h-" + svc}}}


def git(repo, *args):
    p = subprocess.run(["git", "-C", repo, "-c", "user.name=t", "-c", "user.email=t@t"] + list(args), capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit(f"git {args}: {p.stderr}")
    return p.stdout.strip()


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = b'<!doctype html><div id="root"></div>'
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


PIN_FILE = "# header comment\nname: parkio\n\nservices:\n  web:\n    image: " + REPO + "@{}\n"


def setup_repos(W, variant="good"):
    """origin.git with api and release/civo-web-maptiler-20261013; the release checkout cloned at the PIN.
    variant: good | api-blob-differs | r1-not-on-pin"""
    for d in ("seed", "origin.git", "release"):
        shutil.rmtree(os.path.join(W, d), ignore_errors=True)
    seed, origin, rel = os.path.join(W, "seed"), os.path.join(W, "origin.git"), os.path.join(W, "release")
    os.makedirs(os.path.join(seed, "docker"))
    os.makedirs(os.path.join(seed, "scripts"))
    git(seed, "init", "-q", "-b", "api")
    open(os.path.join(seed, "docker/docker-compose.web-release-pin.yml"), "w").write(PIN_FILE.format(OLD_DIGEST))
    for name, body in (("parkio-prod-compose.sh", FAKE_WRAPPER), ("guard-web-synthetic-map-deploy.sh", FAKE_GUARD)):
        p = os.path.join(seed, "scripts", name)
        open(p, "w").write(body)
        os.chmod(p, 0o755)
    git(seed, "add", "-A")
    git(seed, "commit", "-q", "-m", "pin")
    pin = git(seed, "rev-parse", "HEAD")
    api_text = PIN_FILE.format(DIGEST) + ("# a different blob\n" if variant == "api-blob-differs" else "")
    open(os.path.join(seed, "docker/docker-compose.web-release-pin.yml"), "w").write(api_text)
    git(seed, "commit", "-q", "-am", "web pin (api)")
    api_head = git(seed, "rev-parse", "HEAD")
    git(seed, "checkout", "-q", "-b", "release/civo-web-maptiler-20261013", api_head if variant == "r1-not-on-pin" else pin)
    if variant == "r1-not-on-pin":
        git(seed, "commit", "-q", "--allow-empty", "-m", "R1 as an empty commit on api: same tree and blob, parent is not the PIN")
    else:
        open(os.path.join(seed, "docker/docker-compose.web-release-pin.yml"), "w").write(PIN_FILE.format(DIGEST))
        git(seed, "commit", "-q", "-am", "web pin (R1)")
    r1 = git(seed, "rev-parse", "HEAD")
    subprocess.run(["git", "clone", "-q", "--bare", seed, origin], check=True)
    subprocess.run(["git", "clone", "-q", origin, rel], check=True)
    git(rel, "checkout", "-q", "--detach", pin)
    return pin, r1


def main():
    if sys.argv[1:2] != ["--esbuild"] or len(sys.argv) != 3 or not os.path.isfile(sys.argv[2]):
        print("usage: w1-web-deploy-selftest.py --esbuild ESBUILD (the binary from fetch-esbuild-0.27.4.sh)", file=sys.stderr)
        return 2
    esbuild = os.path.abspath(sys.argv[2])
    real_git = shutil.which("git")
    W = tempfile.mkdtemp(prefix="w1st-")
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        tool = os.path.join(W, "tool")
        os.makedirs(tool)
        for f in ("w1-web-deploy.py", "web-bundle-compare.py"):
            shutil.copy(os.path.join(HERE, f), tool)
        staged = os.path.join(tool, "esbuild-0.27.4-linux-x64")
        shutil.copy(esbuild, staged)
        os.chmod(staged, 0o755)
        bindir = os.path.join(W, "bin")
        os.makedirs(bindir)
        shim = os.path.join(bindir, "git")
        open(shim, "w").write("#!/bin/sh\npython3 -c 'import json, os, sys; open(sys.argv[1], \"a\").write(json.dumps(sorted(os.environ)) + \"\\n\")' "
                              f"\"$W1ST_W/git.env\"\nexec {real_git} \"$@\"\n")
        os.chmod(shim, 0o755)
        for name, body in (("docker", FAKE_DOCKER), ("sudo", "#!/bin/sh\nexit 0\n"),
                           ("systemctl", "#!/bin/sh\ncat \"$W1ST_W/units\" 2>/dev/null\nexit 0\n")):
            p = os.path.join(bindir, name)
            open(p, "w").write(body)
            os.chmod(p, 0o755)
        bundle(os.path.join(W, "fs", OLD_ID[7:19]), OLD_KEY, "OLDHASH1", "1111111111111111")
        bundle(os.path.join(W, "fs", NEW_ID[7:19]), NEW_KEY, "NEWHASH2", "2222222222222222", apk_expat="2.8.6-r0")

        def info(iid, ref, rev):
            return {"Id": iid, "RepoDigests": [ref], "Os": "linux", "Architecture": "amd64",
                    "Config": {"Labels": {"org.opencontainers.image.revision": rev}, "Entrypoint": ["/docker-entrypoint.sh"],
                               "Cmd": ["nginx", "-g", "daemon off;"], "Env": ["PATH=/usr/sbin:/usr/bin:/sbin:/bin", "NGINX_VERSION=1.30.5"],
                               "User": "", "WorkingDir": "", "ExposedPorts": {"8080/tcp": {}}, "StopSignal": "SIGQUIT",
                               "Healthcheck": {"Test": ["CMD", "wget", "-q", "--spider", "http://127.0.0.1:8080/"]}}}

        rel = os.path.join(W, "release")
        state = {"pin": None, "r1": None}

        def base_state():
            return {"images": {}, "registry": {f"{REPO}@{DIGEST}": NEW_ID},
                    "imageInfo": {OLD_ID: info(OLD_ID, f"{REPO}@{OLD_DIGEST}", "843ae7cb"), NEW_ID: info(NEW_ID, f"{REPO}@{DIGEST}", state["pin"])},
                    "containers": [container("0" * 64, "web", OLD_ID), container("2" * 64, "auth-service", "sha256:" + "4" * 64),
                                   container("3" * 64, "gateway-service", "sha256:" + "5" * 64)],
                    "guard": {"rc": 0, "configDigest": NEW_CONFIG, "fingerprintCheck": "matched"}}

        def env():
            e = dict(os.environ, PATH=bindir + ":" + os.environ["PATH"], W1ST_W=W, W1_TEST="1", W1_PIN=state["pin"], W1_OLD_IMAGE=OLD_ID,
                     W1_HOST=socket.gethostname(), W1_RELEASE=rel, W1_ENV_FILE=os.path.join(W, "env"), W1_OUT=os.path.join(W, "out"),
                     W1_APP=f"http://127.0.0.1:{srv.server_address[1]}", W1_HEALTH_WAIT="5")
            for k in FORBIDDEN_ENV:
                e[k] = "set-by-the-operator-shell"
            return e

        def reset(variant="good", **changes):
            state["pin"], state["r1"] = setup_repos(W, variant)
            st = base_state()
            st.update(changes)
            json.dump(st, open(os.path.join(W, "state.json"), "w"), indent=1)
            for f in ("wrapper.calls", "units", "docker.log", "guard.env", "docker.env", "git.env", "guard.args"):
                try:
                    os.remove(os.path.join(W, f))
                except FileNotFoundError:
                    pass
            shutil.rmtree(os.path.join(W, "out"), ignore_errors=True)
            shutil.copy(os.path.join(HERE, "web-bundle-compare.py"), tool)

        def mutate_state(fn):
            st = json.load(open(os.path.join(W, "state.json")))
            fn(st)
            json.dump(st, open(os.path.join(W, "state.json"), "w"), indent=1)

        def tool_run(*args, extra_env=None):
            p = subprocess.run([sys.executable, "-I", os.path.join(tool, "w1-web-deploy.py")] + list(args), capture_output=True, text=True,
                               env=dict(env(), **(extra_env or {})), timeout=300)
            out = p.stdout + p.stderr
            ok(OLD_KEY not in out and NEW_KEY not in out, f"  (no key value in the output of {args[0]})")
            return p.returncode, out

        def targs(digest=DIGEST, image_id=NEW_CONFIG, r1=None, kfp=KFP):
            return ["--r1", r1 or state["r1"], "--digest", digest, "--image-id", image_id, "--key-fp", kfp]

        def docker_log():
            try:
                return open(os.path.join(W, "docker.log")).read()
            except FileNotFoundError:
                return ""

        # --- pre ---------------------------------------------------------------------------------------------------------------
        reset()
        rc, out = tool_run("pre", *targs())
        ok(rc == 0 and "W1 pre: PASS" in out, "P1 pre passes on a clean PIN checkout with every check", out)
        st = json.load(open(os.path.join(W, "state.json")))
        ok(st["images"].get(REPO + ":rollback-pre-maptiler-20261013") == OLD_ID, "P1 the rollback tag points to the running web image")
        ok("IDENTICAL" in out and "libexpat" in out, "P1 bundle identical after normalization; the libexpat change is recorded", out)
        ok("PASS guard-established config digest = --image-id" in out, "P1 identity comes from the guard's config digest (containerd)", out)
        ok(not os.path.exists(os.path.join(W, "wrapper.calls")) and git(rel, "rev-parse", "HEAD") == state["pin"],
           "P1 pre never calls the wrapper and leaves the checkout at the PIN")
        genv = json.loads(open(os.path.join(W, "guard.env")).read().splitlines()[0])
        ok(not set(FORBIDDEN_ENV) & set(genv) and "PARKIO_ENV_FILE" in genv and "PARKIO_WEB_EXPECTED_MAP_KEY_FINGERPRINT" not in genv,
           "P1 the guard gets the allowlisted environment only", json.dumps(genv))

        def leaked(name):
            path = os.path.join(W, name)
            lines = [set(json.loads(l)) for l in open(path)] if os.path.exists(path) else []
            return len(lines), sorted(set().union(*lines) & set(FORBIDDEN_ENV)) if lines else []
        for name in ("docker.env", "git.env"):
            n, bad = leaked(name)
            ok(n > 0 and not bad, f"P1 {name.split('.')[0]} gets the allowlisted environment only ({n} calls)", json.dumps(bad))

        def guard_fp():
            args = [json.loads(l) for l in open(os.path.join(W, "guard.args"))]
            return [a[a.index("--expected-map-key-fingerprint") + 1] for a in args if "--expected-map-key-fingerprint" in a], args
        fps, gargs = guard_fp()
        ok(fps == [KFP], "P1 the guard is asked for the --key-fp fingerprint", json.dumps(gargs))
        ok("PASS the staged esbuild 0.27.4 matches its pinned SHA-256 and is executable" in out, "P1 the staged esbuild is checked against its pin", out)
        reset()
        rc, out = tool_run("pre", *targs(kfp="123456789abc"))
        fps, gargs = guard_fp()
        ok(fps == ["123456789abc"], "P1c another --key-fp reaches the guard unchanged", json.dumps(gargs))

        def pre_fails(label, expect, setup=None, args=None, variant="good", extra_env=None, no_side_effects=False):
            reset(variant)
            if setup:
                setup()
            rc, out = tool_run("pre", *(args or targs()), extra_env=extra_env)
            ok(rc == 1 and "W1 pre: FAIL" in out and ("  FAIL " + expect) in out, label, out)
            if no_side_effects:
                log = docker_log()
                ok(" tag " not in f" {log} " and "pull" not in log, label + ": no tag and no pull happened", log)

        pre_fails("P2 a dirty checkout is refused", "the release checkout is clean at the PIN",
                  lambda: open(os.path.join(rel, "untracked.txt"), "w").write("x"))
        pre_fails("P3 web on another image is refused", "one web container, on the current pin",
                  lambda: mutate_state(lambda s: s["containers"][0].__setitem__("Image", "sha256:" + "9" * 64)))
        pre_fails("P4 an --r1 that is not the release branch tip is refused", "R1 is the release branch tip",
                  args=["--r1", "0" * 40, "--digest", DIGEST, "--image-id", NEW_CONFIG, "--key-fp", KFP], no_side_effects=True)
        reset()
        rc, out = tool_run("pre", *targs(digest=OTHER_DIGEST))
        ok(rc == 1 and "  FAIL the R1 pin file pins only the web image, at --digest" in out and "pull" not in docker_log(),
           "P5 a pin file digest other than --digest is refused before any tag or pull", out)
        pre_fails("P6 the api blob differing from R1's is refused", "the pin file blob equals origin/api's",
                  variant="api-blob-differs", no_side_effects=True)
        pre_fails("P7 an R1 whose parent is not the PIN is refused", "R1's only parent is the PIN", variant="r1-not-on-pin",
                  no_side_effects=True)
        pre_fails("P8 a guard config digest other than --image-id is refused", "guard-established config digest = --image-id",
                  lambda: mutate_state(lambda s: s["guard"].__setitem__("configDigest", "sha256:" + "7" * 64)))
        pre_fails("P9 a guard fingerprint mismatch is refused", "F-05 guard PASS offline",
                  lambda: mutate_state(lambda s: s["guard"].update(rc=1, fingerprintCheck="mismatch")))
        pre_fails("P9b a guard PASS without a requested fingerprint check is refused", "F-05 guard PASS offline",
                  lambda: mutate_state(lambda s: s["guard"].update(rc=0, fingerprintCheck="not-requested")))
        pre_fails("P9c a guard exit 1 with a matched fingerprint is refused", "F-05 guard PASS offline",
                  lambda: mutate_state(lambda s: s["guard"].update(rc=1, fingerprintCheck="matched")))
        pre_fails("P10 a wrong OCI revision is refused", "OCI revision is the PIN",
                  lambda: mutate_state(lambda s: s["imageInfo"][NEW_ID]["Config"]["Labels"].__setitem__("org.opencontainers.image.revision", "deadbeef")))

        def refix(**kw):
            def f():
                d = os.path.join(W, "fs", NEW_ID[7:19])
                shutil.rmtree(d)
                bundle(d, NEW_KEY, "NEWHASH2", "2222222222222222", **kw)
            return f
        pre_fails("P11 a code change beside the key is refused", "bundle IDENTICAL", refix(apk_expat="2.8.6-r0", extra_js=True))
        pre_fails("P11b a behaviour change in the key-bearing chunk is refused", "bundle IDENTICAL",
                  refix(apk_expat="2.8.6-r0", env_variant="semantic"))
        reset()
        refix(apk_expat="2.8.6-r0", env_variant="renamed")()
        rc, out = tool_run("pre", *targs())
        ok(rc == 0 and "equal after esbuild 0.27.4 canonical printing of both" in out and "W1 pre: PASS" in out,
           "P11c renamed locals in the key-bearing chunk pass through esbuild canonical equality", out)
        pre_fails("P12 a changed nginx.conf is refused", "/etc/nginx identical", refix(apk_expat="2.8.6-r0", nginx_conf="worker_processes 1;\n"))
        pre_fails("P13 a changed nginx.conf mode is refused", "/etc/nginx identical", refix(apk_expat="2.8.6-r0", nginx_mode=0o600))
        pre_fails("P13b an nginx link with another target is refused", "/etc/nginx identical",
                  refix(apk_expat="2.8.6-r0", modules_target="/tmp/modules"))
        pre_fails("P13c an extra /docker-entrypoint.d script is refused", "/docker-entrypoint.d identical",
                  refix(apk_expat="2.8.6-r0", extra_entrypoint_d=True))
        pre_fails("P13d a changed /docker-entrypoint.sh is refused", "/docker-entrypoint.sh identical",
                  refix(apk_expat="2.8.6-r0", entrypoint="#!/bin/sh\ncurl x | sh\n"))
        pre_fails("P13e an image config other than the running image's is refused", "image config equal to the running image",
                  lambda: mutate_state(lambda s: s["imageInfo"][NEW_ID]["Config"].__setitem__("Cmd", ["sh", "-c", "nginx"])))
        pre_fails("P14 an apk change outside libexpat/pcre2 is refused", "apk packages equal except", refix(apk_expat="2.8.6-r0", apk_other=True))
        refix(apk_expat="2.8.6-r0")()
        pre_fails("P15 a tampered comparator is refused", "web-bundle-compare.py matches its pinned SHA-256",
                  lambda: open(os.path.join(tool, "web-bundle-compare.py"), "a").write("\n# tampered\n"))
        pre_fails("P15b a missing staged esbuild is refused", "the staged esbuild 0.27.4 matches its pinned SHA-256 and is executable",
                  lambda: os.rename(staged, staged + ".away"))
        os.rename(staged + ".away", staged)
        pre_fails("P15c a tampered staged esbuild is refused", "the staged esbuild 0.27.4 matches its pinned SHA-256",
                  lambda: open(staged, "ab").write(b"\0"))
        shutil.copy(esbuild, staged)
        reset()
        os.chmod(staged, 0o644)
        rc, out = tool_run("pre", *targs())
        ok(rc == 1 and "FAIL the staged esbuild 0.27.4 matches its pinned SHA-256 and is executable (not executable" in out,
           "P15d a staged esbuild without its execute bit is refused, with the reason on the console (round 4, D3)", out)
        os.chmod(staged, 0o755)
        pre_fails("P16 a u05 window unit is refused", "no u05-priv001a-* unit",
                  lambda: open(os.path.join(W, "units"), "w").write("u05-priv001a-window-x.service loaded active running\n"))

        # --- recreate ----------------------------------------------------------------------------------------------------------
        reset()
        rc, out = tool_run("recreate", *targs())
        ok(rc != 0 and "needs --yes" in out, "R1 recreate without --yes is a usage error", out)
        rc, out = tool_run("recreate", *targs(), "--yes")
        ok(rc == 1 and "a passing pre for these arguments" in out and not os.path.exists(os.path.join(W, "wrapper.calls")),
           "R2 recreate without a pre changes nothing", out)

        reset()
        tool_run("pre", *targs())
        rc, out = tool_run("recreate", *targs(image_id="sha256:" + "6" * 64), "--yes")
        ok(rc == 1 and "a passing pre for these arguments" in out and not os.path.exists(os.path.join(W, "wrapper.calls"))
           and git(rel, "rev-parse", "HEAD") == state["pin"], "R3 recreate with arguments other than pre's changes nothing", out)
        rc, out = tool_run("recreate", *targs(kfp="b9cf3e673752"), "--yes")
        ok(rc == 1 and "a passing pre for these arguments" in out and not os.path.exists(os.path.join(W, "wrapper.calls")),
           "R3b recreate with a --key-fp other than pre's changes nothing", out)

        reset()
        tool_run("pre", *targs())
        rc, out = tool_run("recreate", *targs(), "--yes")
        ok(rc == 0 and "W1 recreate: PASS" in out, "R4 recreate after a passing pre: PASS", out)
        calls = [json.loads(l) for l in open(os.path.join(W, "wrapper.calls"))]
        ok(len(calls) == 1 and calls[0]["args"] == ["up", "-d", "--no-build", "--no-deps", "--force-recreate", "web"],
           "R4 the wrapper ran once: up -d --no-build --no-deps --force-recreate web", json.dumps(calls))
        ok(calls[0]["fp"] == KFP and calls[0]["envFile"] == os.path.join(W, "env"), "R4 with PARKIO_ENV_FILE and the --key-fp fingerprint")
        ok(not set(FORBIDDEN_ENV) & set(calls[0]["env"]), "R4 the wrapper gets the allowlisted environment only (COMPOSE_*, DOCKER_*, "
           "PARKIO_SKIP_*, PARKIO_COMPOSE_FILES_LIST, PARKIO_GMP_RECOVERY dropped)", json.dumps(calls[0]["env"]))
        ok(git(rel, "rev-parse", "HEAD") == state["r1"], "R4 the checkout is at R1")
        for name in ("docker.env", "git.env"):
            n, bad = leaked(name)
            ok(n > 0 and not bad, f"R4 {name.split('.')[0]} gets the allowlisted environment only in pre and recreate ({n} calls)", json.dumps(bad))
        for s in ("web is healthy", "web is a new container", "web runs the expected image",
                  "web config hash = the wrapper's model hash", "every other container unchanged"):
            ok(f"PASS {s}" in out, f"R4 post-check: {s}", out)
        for path in ("/", "/login", "/explore"):
            ok(re.search(r"(?m)^  PASS http://127\.0\.0\.1:\d+" + re.escape(path) + r" answers 200 with the app root", out) is not None,
               f"R4 post-check: {path} answers 200 with the app root", out)

        reset()
        tool_run("pre", *targs())
        mutate_state(lambda s: s.__setitem__("labelDrift", True))
        rc, out = tool_run("recreate", *targs(), "--yes")
        ok(rc == 1 and "FAIL web config hash = the wrapper's model hash" in out, "R5 a container hash other than the model's is a FAIL", out)

        reset()
        tool_run("pre", *targs())
        mutate_state(lambda s: s.__setitem__("wrongImage", OLD_ID))
        rc, out = tool_run("recreate", *targs(), "--yes")
        ok(rc == 1 and "FAIL web runs the expected image" in out, "R5b a web container started on another image is a FAIL", out)

        reset()
        tool_run("pre", *targs())
        mutate_state(lambda s: s.__setitem__("wrapperRc", 1))
        rc, out = tool_run("recreate", *targs(), "--yes")
        ok(rc == 1 and "FAIL wrapper exit 0" in out and "web is healthy" not in out, "R6 a failing wrapper stops before the post-checks", out)
        ok(git(rel, "rev-parse", "HEAD") == state["pin"] and "the checkout went back to the PIN: True" in out,
           "R6 web unchanged after the failure: the checkout went back to the PIN", out)

        reset()
        tool_run("pre", *targs())
        mutate_state(lambda s: s.__setitem__("touchOther", True))
        rc, out = tool_run("recreate", *targs(), "--yes")
        ok(rc == 1 and "FAIL every other container unchanged" in out, "R7 another container changed during the step is a FAIL", out)

        reset()
        tool_run("pre", *targs())
        mutate_state(lambda s: s["containers"][0].__setitem__("Id", "5" * 64))
        rc, out = tool_run("recreate", *targs(), "--yes")
        ok(rc == 1 and "FAIL web is still the baseline container" in out and not os.path.exists(os.path.join(W, "wrapper.calls")),
           "R8 a web container other than the baseline's changes nothing", out)

        reset()
        tool_run("pre", *targs())
        prej = [os.path.join(dp, f) for dp, _, fs in os.walk(os.path.join(W, "out")) for f in fs if f == "pre.json"][0]
        r = json.load(open(prej))
        r["stamp"] = "20261001T000000Z"
        json.dump(r, open(prej, "w"))
        rc, out = tool_run("recreate", *targs(), "--yes")
        ok(rc == 1 and "within 6 hours" in out and not os.path.exists(os.path.join(W, "wrapper.calls")), "R9 a pre older than 6 hours changes nothing", out)

        reset()
        tool_run("pre", *targs())
        open(os.path.join(rel, "untracked.txt"), "w").write("x")
        tool_run("pre", *targs())          # a later failing pre for the same arguments
        os.remove(os.path.join(rel, "untracked.txt"))
        rc, out = tool_run("recreate", *targs(), "--yes")
        ok(rc == 1 and "(the latest one)" in out and "FAIL" in out and not os.path.exists(os.path.join(W, "wrapper.calls")),
           "R10 a later failing pre overrides an earlier PASS", out)

        # --- rollback ----------------------------------------------------------------------------------------------------------
        reset()
        tool_run("pre", *targs())
        tool_run("recreate", *targs(), "--yes")
        mutate_state(lambda s: (s["registry"].__setitem__(f"{REPO}@{OLD_DIGEST}", OLD_ID), s["images"].__setitem__(f"{REPO}@{OLD_DIGEST}", OLD_ID)))
        os.remove(os.path.join(W, "wrapper.calls"))
        rc, out = tool_run("rollback", "--yes", extra_env={"PARKIO_WEB_EXPECTED_MAP_KEY_FINGERPRINT": "b9cf3e673752"})
        calls = [json.loads(l) for l in open(os.path.join(W, "wrapper.calls"))]
        ok(rc == 0 and "W1 rollback: PASS" in out, "RB1 rollback: PASS (web back on the old image, others unchanged)", out)
        ok(calls and calls[0]["fp"] is None and not set(FORBIDDEN_ENV) & set(calls[0]["env"]),
           "RB1 an operator-set fingerprint variable does not reach the rollback wrapper", json.dumps(calls))
        ok(git(rel, "rev-parse", "HEAD") == state["pin"], "RB1 the checkout is back at the PIN")
        rc, out = tool_run("status")
        ok(rc == 0 and "pre: PASS" in out and "recreate: PASS" in out and "rollback: PASS" in out, "S1 status lists the records", out)
        rc, out = tool_run("pre", "--r1", "x", "--digest", DIGEST, "--image-id", NEW_CONFIG, "--key-fp", KFP)
        ok(rc != 0 and "usage" in out, "U1 malformed arguments are a usage error", out)
        rc, out = tool_run("pre", *targs(kfp="NOTHEX"))
        ok(rc != 0 and "usage" in out, "U2 a malformed --key-fp is a usage error", out)
    finally:
        srv.shutdown()
        shutil.rmtree(W, ignore_errors=True)
    print(f"=== summary: {sum(results)} ok, {len(results) - sum(results)} FAIL ===")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
