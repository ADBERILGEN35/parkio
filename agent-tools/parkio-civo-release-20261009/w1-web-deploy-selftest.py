#!/usr/bin/env python3
"""Self-test of w1-web-deploy.py with fakes: docker (a JSON state file), the production wrapper, systemctl, sudo and an
HTTP server for app.parkio.dev. The release checkout is a real git repository whose origin is a local bare repository with
the api branch and the release branch. The bundle comparison runs the real web-bundle-compare.py on fixture trees.

  python3 -I w1-web-deploy-selftest.py      (from the directory holding w1-web-deploy.py and web-bundle-compare.py)
"""
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
OLD_ID = "sha256:" + "a" * 64
NEW_ID = "sha256:" + "b" * 64
DIGEST = "sha256:" + "d" * 64
OTHER_DIGEST = "sha256:" + "e" * 64
OLD_DIGEST = "sha256:" + "c" * 64
REPO = "ghcr.io/adberilgen35/parkio/web"
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
    if os.path.isdir(src): shutil.copytree(src, a[2])
    elif os.path.isfile(src): shutil.copy2(src, a[2])
    else: sys.exit(1)
    sys.exit(0)
if a[0] == "rm":
    st["created"].pop(a[-1], None); save(); sys.exit(0)
if a[0] == "compose" and "--hash" in a:
    print("web " + st["expectedHash"]); sys.exit(0)
sys.exit(2)
'''

FAKE_WRAPPER = r'''#!/usr/bin/env python3
import json, os, sys
W = os.environ["W1ST_W"]
SP = os.path.join(W, "state.json")
st = json.load(open(SP))
open(os.path.join(W, "wrapper.calls"), "a").write(json.dumps({"args": sys.argv[1:], "envFile": os.environ.get("PARKIO_ENV_FILE"),
    "fp": os.environ.get("PARKIO_WEB_EXPECTED_MAP_KEY_FINGERPRINT"), "skip": sorted(k for k in os.environ if k.startswith("PARKIO_SKIP_"))}) + "\n")
if st.get("wrapperRc"): sys.exit(st["wrapperRc"])
text = open(os.path.join(os.getcwd(), "docker", "docker-compose.web-release-pin.yml")).read()
digest = [l.split("@", 1)[1].strip() for l in text.splitlines() if "image:" in l][0]
iid = st["registry"].get("ghcr.io/adberilgen35/parkio/web@" + digest) or st["images"].get("ghcr.io/adberilgen35/parkio/web@" + digest)
web = [c for c in st["containers"] if c["Config"]["Labels"]["com.docker.compose.service"] == "web"][0]
web["Id"] = ("f" if web["Id"].startswith("1") else "1") * 64
web["Image"] = iid
web["Config"]["Labels"]["com.docker.compose.config-hash"] = st["expectedHash"]
web["Config"]["Labels"]["com.docker.compose.project.config_files"] = os.path.join(os.getcwd(), "docker", "docker-compose.web-release-pin.yml") + "," + os.path.join(W, "gone", "web-binding.yml")
web["State"] = {"Status": "running", "Health": {"Status": "healthy"}}
if st.get("touchOther"):
    st["containers"][1]["Id"] = "9" * 64
json.dump(st, open(SP, "w"), indent=1)
'''


def bundle(root, key, env_hash, cache, apk_expat="2.8.5-r0", extra_js=False, nginx_conf="worker_processes auto;\n", apk_other=False):
    html = os.path.join(root, "usr/share/nginx/html")
    os.makedirs(os.path.join(html, "assets"))
    open(os.path.join(html, "index.html"), "w").write(f'<div id="root"></div><script src="/assets/app-AAAAAAAA.js"></script>')
    open(os.path.join(html, f"assets/env-{env_hash}.js"), "w").write(f'export const e={{VITE_MAPTILER_KEY:"{key}"}};')
    open(os.path.join(html, "assets/app-AAAAAAAA.js"), "w").write(f'import "./env-{env_hash}.js";console.log(1);' + ("x();" if extra_js else ""))
    open(os.path.join(html, "sw.js"), "w").write(f"const CACHE_VERSION = '{cache}';\n")
    for f, body in (("etc/nginx/templates/default.conf.template", "server {}\n"), ("docker-entrypoint.d/15-parkio-web-csp.envsh", "#!/bin/sh\n"),
                    ("etc/nginx/nginx.conf", nginx_conf)):
        os.makedirs(os.path.dirname(os.path.join(root, f)), exist_ok=True)
        open(os.path.join(root, f), "w").write(body)
    os.makedirs(os.path.join(root, "lib/apk/db"))
    open(os.path.join(root, "lib/apk/db/installed"), "w").write(
        f"P:libexpat\nV:{apk_expat}\n\nP:nginx\nV:1.30.5-r1\n\nP:musl\nV:{'1.2.6-r3' if apk_other else '1.2.6-r2'}\n\n")


def container(cid, svc, image, health="healthy"):
    return {"Id": cid, "Image": image, "Name": "/parkio-" + svc, "State": {"Status": "running", "Health": {"Status": health}},
            "Config": {"Labels": {"com.docker.compose.project": "parkio", "com.docker.compose.service": svc,
                                  "com.docker.compose.config-hash": "h-" + svc}}}


def git(repo, *args, check=True):
    p = subprocess.run(["git", "-C", repo, "-c", "user.name=t", "-c", "user.email=t@t"] + list(args), capture_output=True, text=True)
    if check and p.returncode != 0:
        raise SystemExit(f"git {args}: {p.stderr}")
    return p.stdout.strip()


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = b'<!doctype html><div id="root"></div>'
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def log_message(self, *a):
        pass


def main():
    W = tempfile.mkdtemp(prefix="w1st-")
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        tool = os.path.join(W, "tool")
        os.makedirs(tool)
        for f in ("w1-web-deploy.py", "web-bundle-compare.py"):
            shutil.copy(os.path.join(HERE, f), tool)
        bindir = os.path.join(W, "bin")
        os.makedirs(bindir)
        for name, body in (("docker", FAKE_DOCKER), ("sudo", "#!/bin/sh\nexit 0\n"),
                           ("systemctl", "#!/bin/sh\ncat \"$W1ST_W/units\" 2>/dev/null\nexit 0\n")):
            p = os.path.join(bindir, name); open(p, "w").write(body); os.chmod(p, 0o755)
        # the release repository: PIN, api (PIN + web pin), release branch R1 (PIN + the same blob)
        origin, rel = os.path.join(W, "origin.git"), os.path.join(W, "release")
        seed = os.path.join(W, "seed")
        os.makedirs(os.path.join(seed, "docker")); os.makedirs(os.path.join(seed, "scripts"))
        git(seed, "init", "-q", "-b", "api")
        pin_file = "# header comment\nname: parkio\n\nservices:\n  web:\n    image: " + REPO + "@{}\n"
        open(os.path.join(seed, "docker/docker-compose.web-release-pin.yml"), "w").write(pin_file.format(OLD_DIGEST))
        wp = os.path.join(seed, "scripts/parkio-prod-compose.sh"); open(wp, "w").write(FAKE_WRAPPER); os.chmod(wp, 0o755)
        git(seed, "add", "-A"); git(seed, "commit", "-q", "-m", "pin")
        PIN = git(seed, "rev-parse", "HEAD")
        open(os.path.join(seed, "docker/docker-compose.web-release-pin.yml"), "w").write(pin_file.format(DIGEST))
        git(seed, "commit", "-q", "-am", "web pin (api)")
        git(seed, "checkout", "-q", "-b", "release/civo-web-maptiler-20261013", PIN)
        open(os.path.join(seed, "docker/docker-compose.web-release-pin.yml"), "w").write(pin_file.format(DIGEST))
        git(seed, "commit", "-q", "-am", "web pin (R1)")
        R1 = git(seed, "rev-parse", "HEAD")
        subprocess.run(["git", "clone", "-q", "--bare", seed, origin], check=True)
        subprocess.run(["git", "clone", "-q", origin, rel], check=True)
        git(rel, "checkout", "-q", "--detach", PIN)
        # images and their file systems
        bundle(os.path.join(W, "fs", OLD_ID[7:19]), OLD_KEY, "OLDHASH1", "1111111111111111")
        bundle(os.path.join(W, "fs", NEW_ID[7:19]), NEW_KEY, "NEWHASH2", "2222222222222222", apk_expat="2.8.6-r0")
        info = lambda iid, ref, rev: {"Id": iid, "RepoDigests": [ref], "Os": "linux", "Architecture": "amd64",
                                      "Config": {"Labels": {"org.opencontainers.image.revision": rev}}}
        base_state = {"images": {}, "registry": {f"{REPO}@{DIGEST}": NEW_ID},
                      "imageInfo": {OLD_ID: info(OLD_ID, f"{REPO}@{OLD_DIGEST}", "843ae7cb"), NEW_ID: info(NEW_ID, f"{REPO}@{DIGEST}", PIN)},
                      "containers": [container("0" * 64, "web", OLD_ID), container("2" * 64, "auth-service", "sha256:" + "4" * 64),
                                     container("3" * 64, "gateway-service", "sha256:" + "5" * 64)],
                      "expectedHash": "h-web-new"}

        env = dict(os.environ, PATH=bindir + ":" + os.environ["PATH"], W1ST_W=W, W1_TEST="1", W1_PIN=PIN, W1_OLD_IMAGE=OLD_ID,
                   W1_HOST=socket.gethostname(), W1_RELEASE=rel, W1_ENV_FILE=os.path.join(W, "env"), W1_OUT=os.path.join(W, "out"),
                   W1_APP=f"http://127.0.0.1:{srv.server_address[1]}", W1_HEALTH_WAIT="5", PARKIO_SKIP_WEB_MAP_GUARD="I_ACCEPT_UNVERIFIED_WEB_IMAGE")

        def reset(**changes):
            st = json.loads(json.dumps(base_state))
            for k, v in changes.items():
                st[k] = v
            json.dump(st, open(os.path.join(W, "state.json"), "w"), indent=1)
            for f in ("wrapper.calls", "units", "docker.log"):
                try: os.remove(os.path.join(W, f))
                except FileNotFoundError: pass
            shutil.rmtree(os.path.join(W, "out"), ignore_errors=True)
            git(rel, "checkout", "-q", "--detach", PIN)

        def tool_run(*args, extra_env=None):
            p = subprocess.run([sys.executable, "-I", os.path.join(tool, "w1-web-deploy.py")] + list(args), capture_output=True, text=True,
                               env=dict(env, **(extra_env or {})), timeout=300)
            out = p.stdout + p.stderr
            ok(OLD_KEY not in out and NEW_KEY not in out, f"  (no key value in the output of {args[0]})")
            return p.returncode, out

        T = ["--r1", R1, "--digest", DIGEST, "--image-id", NEW_ID]

        reset()
        rc, out = tool_run("pre", *T)
        ok(rc == 0 and "W1 pre: PASS" in out, "P1 pre passes on a clean PIN checkout with every check", out)
        st = json.load(open(os.path.join(W, "state.json")))
        ok(st["images"].get(REPO + ":rollback-pre-maptiler-20261013") == OLD_ID, "P1 the rollback tag points to the running web image")
        ok("IDENTICAL" in out and "libexpat" in out, "P1 bundle identical after normalization; the libexpat change is recorded", out)
        ok(not os.path.exists(os.path.join(W, "wrapper.calls")), "P1 pre never calls the wrapper")
        ok(git(rel, "rev-parse", "HEAD") == PIN, "P1 pre leaves the checkout at the PIN")

        def pre_fails(label, expect, setup=None, args=T, extra_env=None):
            reset()
            if setup:
                setup()
            rc, out = tool_run("pre", *args, extra_env=extra_env)
            ok(rc == 1 and "W1 pre: FAIL" in out and expect in out, label, out)

        pre_fails("P2 a dirty checkout is refused", "the release checkout is clean at the PIN",
                  lambda: open(os.path.join(rel, "untracked.txt"), "w").write("x"))
        os.remove(os.path.join(rel, "untracked.txt"))
        pre_fails("P3 web on another image is refused", "one web container, on the current pin",
                  lambda: reset(containers=[container("0" * 64, "web", "sha256:" + "9" * 64)] + base_state["containers"][1:]))
        pre_fails("P4 an --r1 that is not the release branch tip is refused", "R1 is the release branch tip",
                  args=["--r1", PIN, "--digest", DIGEST, "--image-id", NEW_ID])
        pre_fails("P5 a pin file digest other than --digest is refused", "pins only the web image, at --digest",
                  args=["--r1", R1, "--digest", OTHER_DIGEST, "--image-id", NEW_ID])
        pre_fails("P6 a pulled image id other than --image-id is refused", "image id = --image-id",
                  args=["--r1", R1, "--digest", DIGEST, "--image-id", "sha256:" + "7" * 64])

        def wrong_rev():
            st = json.load(open(os.path.join(W, "state.json")))
            st["imageInfo"][NEW_ID]["Config"]["Labels"]["org.opencontainers.image.revision"] = "deadbeef"
            json.dump(st, open(os.path.join(W, "state.json"), "w"))
        pre_fails("P7 a wrong OCI revision is refused", "OCI revision is the PIN", wrong_rev)

        def refix(**kw):
            def f():
                d = os.path.join(W, "fs", NEW_ID[7:19]); shutil.rmtree(d)
                bundle(d, NEW_KEY, "NEWHASH2", "2222222222222222", **kw)
            return f
        pre_fails("P8 a code change beside the key is refused", "bundle IDENTICAL", refix(extra_js=True))
        pre_fails("P9 a changed nginx.conf is refused", "nginx.conf byte-identical", refix(nginx_conf="worker_processes 1;\n"))
        pre_fails("P10 an apk change outside libexpat/pcre2 is refused", "apk packages equal except", refix(apk_other=True))
        refix(apk_expat="2.8.6-r0")()
        pre_fails("P11 a u05 window unit is refused", "no u05-priv001a-* unit",
                  lambda: open(os.path.join(W, "units"), "w").write("u05-priv001a-window-x.service loaded active running\n"))

        # recreate
        reset()
        rc, out = tool_run("recreate", *T)
        ok(rc != 0 and "needs --yes" in out, "R1 recreate without --yes is a usage error", out)
        rc, out = tool_run("recreate", *T, "--yes")
        ok(rc == 1 and "a passing pre for these arguments" in out and not os.path.exists(os.path.join(W, "wrapper.calls")),
           "R2 recreate without a passing pre changes nothing", out)
        ok(git(rel, "rev-parse", "HEAD") == PIN, "R2 the checkout is still at the PIN")

        reset()
        tool_run("pre", *T)
        rc, out = tool_run("recreate", *T, "--yes")
        ok(rc == 0 and "W1 recreate: PASS" in out, "R3 recreate after a passing pre: PASS", out)
        calls = [json.loads(l) for l in open(os.path.join(W, "wrapper.calls"))]
        ok(len(calls) == 1 and calls[0]["args"] == ["up", "-d", "--no-build", "--no-deps", "--force-recreate", "web"],
           "R3 the wrapper ran once: up -d --no-build --no-deps --force-recreate web", json.dumps(calls))
        ok(calls[0]["fp"] == "b9cf3e673752" and calls[0]["envFile"] == os.path.join(W, "env") and calls[0]["skip"] == [],
           "R3 with PARKIO_ENV_FILE and the fingerprint, and every PARKIO_SKIP_* removed", json.dumps(calls))
        ok(git(rel, "rev-parse", "HEAD") == R1, "R3 the checkout is at R1")
        for s in ("web is healthy", "web is a new container", "web runs the expected image", "web config hash = its recorded",
                  "every other container unchanged"):
            ok(f"PASS {s}" in out, f"R3 post-check: {s}", out)
        for path in ("/", "/login", "/explore"):
            ok(re.search(r"(?m)^  PASS http://127\.0\.0\.1:\d+" + re.escape(path) + r" answers 200 with the app root", out) is not None,
               f"R3 post-check: {path} answers 200 with the app root", out)

        reset()
        tool_run("pre", *T)
        st = json.load(open(os.path.join(W, "state.json"))); st["wrapperRc"] = 1; json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool_run("recreate", *T, "--yes")
        ok(rc == 1 and "FAIL wrapper exit 0" in out and "web is healthy" not in out, "R4 a failing wrapper stops before the post-checks", out)

        reset()
        tool_run("pre", *T)
        st = json.load(open(os.path.join(W, "state.json"))); st["touchOther"] = True; json.dump(st, open(os.path.join(W, "state.json"), "w"))
        rc, out = tool_run("recreate", *T, "--yes")
        ok(rc == 1 and "FAIL every other container unchanged" in out, "R5 another container changed during the step is a FAIL", out)

        reset()
        tool_run("pre", *T)
        prej = [os.path.join(dp, f) for dp, _, fs in os.walk(os.path.join(W, "out")) for f in fs if f == "pre.json"][0]
        r = json.load(open(prej)); r["stamp"] = "20261001T000000Z"; json.dump(r, open(prej, "w"))
        rc, out = tool_run("recreate", *T, "--yes")
        ok(rc == 1 and "a passing pre for these arguments within 6 hours" in out and not os.path.exists(os.path.join(W, "wrapper.calls")),
           "R6 a pre older than 6 hours changes nothing", out)

        # rollback after a successful recreate
        reset()
        tool_run("pre", *T)
        tool_run("recreate", *T, "--yes")
        st = json.load(open(os.path.join(W, "state.json")))
        st["registry"][f"{REPO}@{OLD_DIGEST}"] = OLD_ID; st["images"][f"{REPO}@{OLD_DIGEST}"] = OLD_ID
        json.dump(st, open(os.path.join(W, "state.json"), "w"))
        os.remove(os.path.join(W, "wrapper.calls"))
        rc, out = tool_run("rollback", "--yes")
        calls = [json.loads(l) for l in open(os.path.join(W, "wrapper.calls"))]
        ok(rc == 0 and "W1 rollback: PASS" in out, "RB1 rollback: PASS (web back on the old image, others unchanged)", out)
        ok(calls and calls[0]["fp"] is None and calls[0]["skip"] == [], "RB1 the wrapper ran without the fingerprint variable", json.dumps(calls))
        ok(git(rel, "rev-parse", "HEAD") == PIN, "RB1 the checkout is back at the PIN")
        rc, out = tool_run("status")
        ok(rc == 0 and "pre: PASS" in out and "recreate: PASS" in out and "rollback: PASS" in out, "S1 status lists the records", out)
        rc, out = tool_run("pre", "--r1", "x", "--digest", DIGEST, "--image-id", NEW_ID)
        ok(rc != 0 and "usage" in out, "U1 malformed arguments are a usage error", out)
    finally:
        srv.shutdown()
        shutil.rmtree(W, ignore_errors=True)
    print(f"=== summary: {sum(results)} ok, {len(results) - sum(results)} FAIL ===")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
