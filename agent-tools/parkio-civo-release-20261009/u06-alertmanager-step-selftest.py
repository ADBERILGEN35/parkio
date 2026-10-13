#!/usr/bin/env python3
"""Self-test of u06-alertmanager-step.py, using fakes only. Revised after the independent review (U06-TOOLS-REVIEW.md
M1, M2, M6, L6, L7).

Fakes:
- docker: a JSON state of three parkio containers (alertmanager, prometheus, caddy) answers ps, inspect, info, image
  inspect and exec.
  - `amtool config routes test` prints the running receiver;
  - wget /metrics prints webhook counters that grow every TICK seconds while the receiver is `heartbeat`. It prints two
    failed series per integration and slack series, to exercise the summing and the integration filter;
  - it records the environment of every call (docker.env).
- the release checkout is a real git repository whose scripts/parkio-prod-compose.sh is a fake wrapper:
  - `config --hash alertmanager` hashes the heartbeat URL of PARKIO_ENV_FILE;
  - `config --format json` gives the image reference;
  - `up -d --no-build --no-deps alertmanager` replaces the alertmanager container accordingly;
  - marker files inject faults: wrapper-fail, touch-prometheus, hash-ignores-url, drift-hash, stuck-receiver,
    single-delivery;
  - it records its arguments and environment.

Each gate of the tool has a case that fails it. The scenarios are listed in the code. No URL value may appear in any
output or record, and no child may see a variable outside the allowlist.

  python3 -I u06-alertmanager-step-selftest.py
"""
import hashlib
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "u06-alertmanager-step.py")
COMMENT = "# External heartbeat / dead-man's switch (U06, owner-authorized activation)"
HB = "https://hc-ping.com/5c1d2e3f-4a5b-4c6d-8e7f-0a1b2c3d4e5f"
HB2 = "https://hc-ping.com/6d2e3f4a-5b6c-4d7e-9f80-1b2c3d4e5f60"
HB0 = "https://hc-ping.com/7e3f4a5b-6c7d-4e8f-a091-2c3d4e5f6071"
HB_FAIL = "https://hc-ping.com/failing0-4a5b-4c6d-8e7f-0a1b2c3d4e5f"
SLACK = "https://hooks.slack.com/services/T000SELF/B000TEST/synthetic0webhook0value"
SECRETS = (HB, HB2, HB0, HB_FAIL, SLACK)
B0 = ".env.azure-hosted-beta.bak-20261013T080000Z"      # before the first activation (no URL)
B1 = ".env.azure-hosted-beta.bak-20261013T090000Z"      # before a rotation (holds HB)
BX = ".env.azure-hosted-beta.bak-20261013T075000Z"      # an older file holding another URL, recorded by no pre
REF = "prom/alertmanager:v0.27.0"
IMG = "sha256:" + "a" * 64
results = []


def ok(cond, label, extra=""):
    results.append(bool(cond))
    print(("ok   " if cond else "FAIL ") + label + ("" if cond else f"\n{extra[-3000:]}"))


FAKE_DOCKER = r'''#!/usr/bin/env python3
import json, os, sys, time
W = os.environ["U06A_W"]
TICK = 0.5
a = sys.argv[1:]
open(os.path.join(W, "docker.env"), "a").write(json.dumps(sorted(os.environ)) + "\n")
state = json.load(open(os.path.join(W, "state.json")))
cs = state["containers"]
if a[:1] == ["info"]:
    print("27.3.1"); sys.exit(0)
if a[:1] == ["ps"]:
    print("\n".join(cs)); sys.exit(0)
if a[:2] == ["image", "inspect"]:
    iid = state.get("imageIds", {}).get(a[-1])
    if not iid:
        sys.exit(1)
    print(iid); sys.exit(0)
if a[:1] == ["inspect"]:
    out = []
    for i in a[1:]:
        c = cs[i]
        out.append({"Id": i, "Name": "/" + c["name"], "Image": c["image"],
                    "Config": {"Labels": {"com.docker.compose.project": "parkio", "com.docker.compose.service": c["service"],
                                          "com.docker.compose.config-hash": c["hash"]}},
                    "State": {"Status": c["status"], "Health": {"Status": c["health"]}}})
    print(json.dumps(out)); sys.exit(0)
if a[:1] == ["exec"]:
    c = next((c for c in cs.values() if c["name"] == a[1]), None)
    if c is None:
        sys.exit(1)
    if a[2:6] == ["amtool", "config", "routes", "test"]:
        assert "--alertmanager.url=http://localhost:9093" in a and "alertname=Watchdog" in a
        print(c["receiver"]); sys.exit(0)
    if a[2:4] == ["wget", "-qO-"]:
        n = int((time.time() - c["started"]) / TICK) + 1 if c["receiver"] == "heartbeat" else 0
        if c.get("single"):
            n = min(n, 1)
        failed = n if c.get("fail") else 0
        print('alertmanager_notifications_total{integration="slack"} 7')
        print('alertmanager_notifications_total{integration="webhook"} %d' % n)
        print('alertmanager_notifications_failed_total{integration="slack",reason="other"} 1')
        print('alertmanager_notifications_failed_total{integration="webhook",reason="clientError"} %d' % failed)
        print('alertmanager_notifications_failed_total{integration="webhook",reason="other"} 0')
        sys.exit(0)
sys.exit(2)
'''

FAKE_WRAPPER = r'''#!/usr/bin/env python3
import hashlib, json, os, re, sys, time
W = os.environ["U06A_W"]
a = sys.argv[1:]
env_file = os.environ.get("PARKIO_ENV_FILE", "")
mark = lambda m: os.path.exists(os.path.join(W, m))
with open(os.path.join(W, "wrapper-env.log"), "a") as fh:
    fh.write(json.dumps({"args": a, "envFile": env_file, "keys": sorted(os.environ)}) + "\n")
url = ""
for line in open(env_file, encoding="utf-8"):
    m = re.match(r"^PARKIO_ALERT_HEARTBEAT_URL=(.*)$", line.rstrip("\r\n"))
    if m:
        url = m.group(1)
model = hashlib.sha256(("am|" + ("" if mark("hash-ignores-url") else url)).encode()).hexdigest()[:16]
if a == ["config", "--hash", "alertmanager"]:
    print("alertmanager " + model); sys.exit(0)
if a == ["config", "--format", "json"]:
    print(json.dumps({"services": {"alertmanager": {"image": "prom/alertmanager:v0.27.0", "environment": {"X": "y"}}}})); sys.exit(0)
if a == ["up", "-d", "--no-build", "--no-deps", "alertmanager"]:
    if mark("wrapper-fail"):
        sys.exit(3)
    p = os.path.join(W, "state.json")
    state = json.load(open(p))
    cs = state["containers"]
    old = [c for c in cs.values() if c["service"] == "alertmanager"][0]
    for i in [i for i, c in cs.items() if c["service"] == "alertmanager"]:
        del cs[i]
    new = hashlib.sha256(("new|" + str(time.time())).encode()).hexdigest()
    cs[new] = {"service": "alertmanager", "name": "parkio-alertmanager", "image": "sha256:" + ("9" if mark("wrong-image") else "a") * 64,
               "hash": "drifted0000000000" if mark("drift-hash") else model, "status": "running", "health": "healthy",
               "started": time.time(), "receiver": old["receiver"] if mark("stuck-receiver") else ("heartbeat" if url else "null"),
               "fail": "failing" in url, "single": mark("single-delivery")}
    if mark("touch-prometheus"):
        oldp = [i for i, c in cs.items() if c["service"] == "prometheus"][0]
        cs["f" * 64] = dict(cs.pop(oldp))
    json.dump(state, open(p + ".tmp", "w")); os.replace(p + ".tmp", p)
    sys.exit(0)
sys.exit(2)
'''

MARKERS = ("wrapper-fail", "touch-prometheus", "hash-ignores-url", "drift-hash", "stuck-receiver", "single-delivery", "wrong-image")


def git(repo, *args):
    p = subprocess.run(["git", "-C", repo, "-c", "user.name=t", "-c", "user.email=t@t"] + list(args), capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit(f"git {args}: {p.stderr}")
    return p.stdout


def main():
    W = tempfile.mkdtemp(prefix="u06a-")
    try:
        L, R, T, OUT, BIN = (os.path.join(W, d) for d in ("live", "release", "textfile", "out", "bin"))
        for d in (os.path.join(L, "docker"), os.path.join(R, "scripts"), os.path.join(R, "docker"), T, BIN):
            os.makedirs(d)
        open(os.path.join(BIN, "docker"), "w").write(FAKE_DOCKER)
        os.chmod(os.path.join(BIN, "docker"), 0o755)
        wrapper = os.path.join(R, "scripts", "parkio-prod-compose.sh")
        open(wrapper, "w").write(FAKE_WRAPPER)
        os.chmod(wrapper, 0o755)
        open(os.path.join(R, "docker", "docker-compose.web-release-pin.yml"), "w").write(
            "# pin\nname: parkio\nservices:\n  web:\n    image: ghcr.io/adberilgen35/parkio/web@sha256:" + "c" * 64 + "\n")
        git(R, "init", "-q")
        git(R, "add", "-A")
        git(R, "commit", "-qm", "pin")
        pin = git(R, "rev-parse", "HEAD").strip()
        ENV = os.path.join(L, "docker", ".env.azure-hosted-beta")
        original = ("PARKIO_DOMAIN=parkio.dev\nPARKIO_ALERT_SLACK_WEBHOOK_URL=" + SLACK + "\nPARKIO_ALERT_SLACK_CHANNEL=#parkio-alert\n"
                    "PARKIO_ALERT_WEBHOOK_URL=\nPARKIO_PUSH_DELIVERY_PROVIDER=expo")          # no final newline, like the host
        env = {"PATH": BIN + ":" + os.environ["PATH"], "HOME": W, "LANG": "C.UTF-8", "LEAKY_SECRET": "must-not-reach-children",
               "U06A_TEST": "1", "U06A_W": W, "U06A_PIN": pin, "U06A_HOST": socket.gethostname(), "U06A_RELEASE": R,
               "U06A_ENV_FILE": ENV, "U06A_TEXTFILE_DIR": T, "U06A_OUT": OUT, "U06A_HEARTBEAT_WAIT": "4", "U06A_POLL": "0.2",
               "U06A_HEALTH_WAIT": "5"}
        outputs = []

        def model(url):
            return hashlib.sha256(("am|" + url).encode()).hexdigest()[:16]

        def with_url(base, url):
            return base + ("\n" + COMMENT + "\nPARKIO_ALERT_HEARTBEAT_URL=" + url + "\n" if url else "")

        def write_file(path, text):
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            os.write(fd, text.encode())
            os.close(fd)

        def reset(url=HB, generic="", extra_change=False, am_hash=None, receiver="null", image=IMG):
            for f in os.listdir(os.path.join(L, "docker")):
                os.remove(os.path.join(L, "docker", f))
            for f in os.listdir(T):
                os.remove(os.path.join(T, f))
            shutil.rmtree(OUT, ignore_errors=True)
            for f in ("wrapper-env.log", "docker.env") + MARKERS:
                if os.path.exists(os.path.join(W, f)):
                    os.remove(os.path.join(W, f))
            git(R, "checkout", "-q", "--detach", pin)
            git(R, "clean", "-qfd")
            base = original.replace("PARKIO_ALERT_WEBHOOK_URL=", "PARKIO_ALERT_WEBHOOK_URL=" + generic)
            write_file(os.path.join(L, "docker", B0), base)
            current = with_url(base, url)
            if extra_change:
                current = current.replace("PARKIO_PUSH_DELIVERY_PROVIDER=expo", "PARKIO_PUSH_DELIVERY_PROVIDER=noop")
            write_file(ENV, current)
            state = {"imageIds": {REF: IMG}, "containers": {
                "1" * 64: {"service": "alertmanager", "name": "parkio-alertmanager", "image": image,
                           "hash": am_hash or model(""), "status": "running", "health": "healthy", "started": time.time(),
                           "receiver": receiver},
                "2" * 64: {"service": "prometheus", "name": "parkio-prometheus", "image": "sha256:" + "b" * 64, "hash": "p" * 16,
                           "status": "running", "health": "healthy", "started": 0, "receiver": ""},
                "3" * 64: {"service": "caddy", "name": "parkio-caddy", "image": "sha256:" + "d" * 64, "hash": "c" * 16,
                           "status": "running", "health": "healthy", "started": 0, "receiver": ""}}}
            json.dump(state, open(os.path.join(W, "state.json"), "w"))
            return base

        def tool(*args):
            p = subprocess.run([sys.executable, "-I", TOOL] + list(args), capture_output=True, text=True, env=env, timeout=120)
            outputs.append(p.stdout + p.stderr)
            return p.returncode, p.stdout + p.stderr

        def marker(name):
            open(os.path.join(W, name), "w").write("x")

        def unmark():
            for m in MARKERS:
                if os.path.exists(os.path.join(W, m)):
                    os.remove(os.path.join(W, m))

        def calls():
            p = os.path.join(W, "wrapper-env.log")
            return [json.loads(l) for l in open(p)] if os.path.exists(p) else []

        def ups():
            return [c for c in calls() if c["args"][:1] == ["up"]]

        def state():
            return json.load(open(os.path.join(W, "state.json")))["containers"]

        def mutate(fn):
            p = os.path.join(W, "state.json")
            st = json.load(open(p))
            fn(st)
            json.dump(st, open(p, "w"))

        def am():
            return [c for c in state().values() if c["service"] == "alertmanager"][0]

        def env_keys(name):
            p = os.path.join(W, name)
            return [set(json.loads(l)) for l in open(p)] if os.path.exists(p) else []

        # ---- usage
        reset()
        for args, label in (([], "no command"), (["pre", "--yes"], "pre --yes"), (["recreate"], "recreate without --yes"),
                            (["recreate", "--yes", "--bogus"], "an unknown argument"),
                            (["rollback", "--backup", "x.bak", "--yes"], "a malformed backup name"),
                            (["rollback", "--backup", os.path.join(L, "docker", B0), "--yes"], "a path instead of a file name"),
                            (["rollback", "--backup", B0], "rollback without --yes"),
                            (["recreate", "--yes", "--yes"], "a repeated --yes")):
            rc, out = tool(*args)
            ok(rc == 2 and not ups(), f"U usage refusal: {label}", out)

        # ---- pre failures, one per gate
        cases = [
            ("P1 pre fails without a heartbeat URL", dict(url=""), "FAIL the env file holds an https heartbeat URL"),
            ("P2 pre fails when the generic webhook is set", dict(generic="https://ops.example.test/hook"), "FAIL the generic operator webhook is empty"),
            ("P3 pre fails when another key changed too", dict(extra_change=True), "FAIL the newest env backup differs from the env file only"),
            ("P4 pre fails when alertmanager has another pending difference", dict(am_hash="9" * 16), "FAIL with the backup env the model equals the running"),
            ("P8 pre fails when Watchdog already routes to heartbeat on a first activation", dict(receiver="heartbeat"), "FAIL Watchdog routes to null"),
            ("P10 pre fails when alertmanager runs another image than the model's", dict(image="sha256:" + "e" * 64), "FAIL alertmanager runs the image the model names"),
        ]
        for label, kw, expect in cases:
            reset(**kw)
            rc, out = tool("pre")
            ok(rc == 1 and expect in out and not ups(), label, out)
        reset()
        open(os.path.join(T, "parkio_backup_synthetic_stale.prom"), "w").write("# marker\n")
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL no synthetic BackupStale textfile armed" in out, "P5 pre fails while a BackupStale test is armed", out)
        reset()
        open(os.path.join(R, "stray"), "w").write("x")
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL the release checkout is clean at the PIN or R1" in out, "P6 pre fails on a dirty release checkout", out)
        reset()
        marker("hash-ignores-url")
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL with the current env the model differs" in out, "P9 pre fails when the current model equals the running one", out)
        reset()
        rc, out = tool("pre")
        ok(rc == 0 and "U06 alertmanager pre: PASS" in out and "first activation" in out and "Watchdog routes to null" in out and
           f"rollback --backup {B0} --yes" in out and not ups(), "P7 pre passes, names the backup and changes nothing", out)
        ok(calls() and not any("LEAKY_SECRET" in c["keys"] for c in calls()), "P7 the wrapper gets no variable outside the allowlist")
        ok(env_keys("docker.env") and not any("LEAKY_SECRET" in k for k in env_keys("docker.env")), "P7 docker gets no variable outside the allowlist")

        # ---- recreate refusals
        reset()
        tool("pre")
        with open(ENV, "a") as fh:
            fh.write("PARKIO_ALERT_REPEAT_WARNING=4h\n")
        rc, out = tool("recreate", "--yes")
        ok(rc == 1 and "FAIL the env file is unchanged since that pre" in out and not ups(), "R1 recreate refuses an env changed after pre", out)
        reset()
        tool("pre")
        time.sleep(1.1)
        with open(ENV, "a") as fh:
            fh.write("PARKIO_X=1\n")
        tool("pre")
        rc, out = tool("recreate", "--yes")
        ok(rc == 1 and "FAIL the latest pre passed within 2 hours" in out and not ups(), "R2 a later failing pre blocks recreate", out)
        reset()
        tool("pre")
        p = [os.path.join(OUT, d, "pre.json") for d in os.listdir(OUT)][0]
        rec = json.load(open(p))
        rec["stamp"] = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(time.time() - 3 * 3600))
        json.dump(rec, open(p, "w"))
        rc, out = tool("recreate", "--yes")
        ok(rc == 1 and "FAIL the latest pre passed within 2 hours" in out and not ups(), "R3 a pre older than 2 hours blocks recreate", out)
        reset()
        tool("pre")
        mutate(lambda st: st["containers"].__setitem__("9" * 64, dict(st["containers"].pop("1" * 64))))
        rc, out = tool("recreate", "--yes")
        ok(rc == 1 and "FAIL alertmanager is still the pre's container" in out and not ups(), "R3b another alertmanager container since pre blocks recreate", out)

        # ---- recreate PASS, then a rotation, then rollbacks
        base = reset()
        tool("pre")
        before = dict(state())
        rc, out = tool("recreate", "--yes")
        after = state()
        ok(rc == 0 and "U06 alertmanager recreate: PASS" in out, "R4 recreate passes", out)
        ok([c["args"] for c in ups()] == [["up", "-d", "--no-build", "--no-deps", "alertmanager"]],
           "R4 the wrapper ran exactly once: up -d --no-build --no-deps alertmanager", str(ups()))
        ok(am()["receiver"] == "heartbeat" and am()["hash"] == model(HB) and
           {k: v for k, v in after.items() if v["service"] != "alertmanager"} == {k: v for k, v in before.items() if v["service"] != "alertmanager"},
           "R4 only alertmanager was replaced; its hash is the heartbeat model's")
        ok(all(s in out for s in ("PASS alertmanager runs the image pre recorded", "PASS Watchdog routes to heartbeat",
                                   "PASS heartbeat delivered at least twice", "PASS no webhook notification failed (failed 0.0)",
                                   "PASS every other container unchanged against the baseline", "PASS alertmanager config hash = the wrapper's model hash")),
           "R4 post-checks: image, route, two deliveries, no failure, others unchanged, hash", out)
        ok(not any("LEAKY_SECRET" in c["keys"] for c in calls()) and not any("LEAKY_SECRET" in k for k in env_keys("docker.env")),
           "R4 no child saw a variable outside the allowlist")
        # rotation (M1): the env tool's --replace-url makes a backup holding HB and writes HB2
        write_file(os.path.join(L, "docker", B1), open(ENV).read())
        write_file(ENV, with_url(base, HB2))
        time.sleep(1.1)
        rc, out = tool("pre")
        ok(rc == 0 and "rotation (the backup already holds a URL)" in out and "PASS Watchdog routes to heartbeat in the running" in out,
           "ROT1 pre passes for a rotation (Watchdog expected on heartbeat; review M1)", out)
        rc, out = tool("recreate", "--yes")
        ok(rc == 0 and am()["hash"] == model(HB2) and am()["receiver"] == "heartbeat", "ROT2 recreate applies the rotated URL", out)
        # wrong backup (M2): an older file with another URL, recorded by no pre, is refused before anything is written
        write_file(os.path.join(L, "docker", BX), with_url(base, HB0))
        n, env_before = len(ups()), open(ENV, "rb").read()
        rc, out = tool("rollback", "--backup", BX, "--yes")
        ok(rc == 1 and "FAIL the backup is the one the latest passing pre recorded" in out and len(ups()) == n and
           open(ENV, "rb").read() == env_before and "nothing was written" in out, "B2 a URL backup that no pre recorded is refused before any write (M2)", out)
        # fresh baseline (M2): caddy changes between pre and the rollback (e.g. W1's web deploy); rollback to B1 still passes
        mutate(lambda st: st["containers"].__setitem__("8" * 64, dict(st["containers"].pop("3" * 64))))
        open(os.path.join(T, "parkio_backup_synthetic_stale.prom"), "w").write("# marker\n")
        rc, out = tool("rollback", "--backup", B1, "--yes")
        ok(rc == 0 and "U06 alertmanager rollback: PASS" in out and am()["hash"] == model(HB) and am()["receiver"] == "heartbeat" and
           "PASS every other container unchanged against the rollback's fresh baseline" in out, "B4 rollback to the rotation backup passes with a fresh baseline", out)
        ok("NOTE a synthetic BackupStale textfile is armed" in out, "B6 an armed BackupStale file is only a NOTE for a rollback (L7)", out)
        os.remove(os.path.join(T, "parkio_backup_synthetic_stale.prom"))
        # nothing to roll back: the env already equals B1's model
        n = len(ups())
        rc, out = tool("rollback", "--backup", B1, "--yes")
        ok(rc == 1 and "FAIL with the backup env the model differs from the running" in out and len(ups()) == n,
           "B3 a rollback with nothing to roll back is refused before any write", out)
        # full back-out to the pre-activation file B0 after the rotation: Watchdog back to null
        rc, out = tool("rollback", "--backup", B0, "--yes")
        ok(rc == 0 and am()["receiver"] == "null" and am()["hash"] == model("") and open(ENV, "rb").read() == open(os.path.join(L, "docker", B0), "rb").read(),
           "B7 a full back-out to the pre-activation file passes (Watchdog -> null)", out)
        ok(oct(os.stat(ENV).st_mode & 0o777) == "0o600" and not [f for f in os.listdir(os.path.join(L, "docker")) if f.startswith(".u06a-")],
           "B7 the env file keeps mode 600 and no temp file is left (atomic write, L2)")

        # ---- recreate failures
        reset(url=HB_FAIL)
        tool("pre")
        rc, out = tool("recreate", "--yes")
        ok(rc == 1 and "FAIL no webhook notification failed" in out and "FAIL heartbeat delivered at least twice" in out,
           "R5 a failing webhook fails the recreate", out)
        reset()
        tool("pre")
        marker("touch-prometheus")
        rc, out = tool("recreate", "--yes")
        ok(rc == 1 and "FAIL every other container unchanged against the baseline (prometheus)" in out, "R6 a collateral change fails", out)
        reset()
        tool("pre")
        marker("wrapper-fail")
        rc, out = tool("recreate", "--yes")
        ok(rc == 1 and "FAIL wrapper exit 0 (exit 3)" in out and "Watchdog routes to heartbeat" not in out, "R7 a wrapper error stops the post-checks", out)
        reset()
        tool("pre")
        marker("wrong-image")
        rc, out = tool("recreate", "--yes")
        ok(rc == 1 and "FAIL alertmanager runs the image pre recorded" in out, "R7b a recreate onto another image fails (review round 2)", out)
        reset()
        tool("pre")
        marker("single-delivery")
        rc, out = tool("recreate", "--yes")
        ok(rc == 1 and "FAIL heartbeat delivered at least twice" in out, "R8 a single delivery is not enough", out)

        # ---- rollback refusals and post-check failures
        reset()
        tool("pre")
        tool("recreate", "--yes")
        with open(ENV, "a") as fh:
            fh.write("PARKIO_Y=2\n")
        n = len(ups())
        rc, out = tool("rollback", "--backup", B0, "--yes")
        ok(rc == 1 and "FAIL the backup is a regular file and differs from the env file only in the heartbeat key" in out and
           len(ups()) == n and b"PARKIO_Y=2" in open(ENV, "rb").read(), "B1 rollback refuses a backup that differs elsewhere", out)
        for mark_, expect, label in (("drift-hash", "FAIL alertmanager config hash = the backup's model hash", "B5a a wrong hash after the rollback fails"),
                                     ("stuck-receiver", "FAIL Watchdog routes to null", "B5b a receiver still on heartbeat after the rollback fails"),
                                     ("touch-prometheus", "FAIL every other container unchanged against the rollback's fresh baseline (prometheus)",
                                      "B5c a collateral change during the rollback fails"),
                                     ("wrong-image", "FAIL alertmanager runs the same image as before", "B5d a rollback onto another image fails (review round 2)")):
            reset()
            tool("pre")
            tool("recreate", "--yes")
            marker(mark_)
            rc, out = tool("rollback", "--backup", B0, "--yes")
            ok(rc == 1 and expect in out, label, out)
            unmark()
        rc, out = tool("status")
        ok(rc == 0 and "pre: PASS" in out and "recreate: PASS" in out and "rollback: FAIL" in out, "S status lists the records", out)

        text = "\n".join(outputs)
        for root, _, files in os.walk(OUT):
            for f in files:
                text += open(os.path.join(root, f), encoding="utf-8").read()
        ok(not any(s in text for s in SECRETS), "no URL value in any output or record")
    finally:
        shutil.rmtree(W, ignore_errors=True)
    print(f"=== summary: {sum(results)} ok, {len(results) - sum(results)} FAIL ===")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
