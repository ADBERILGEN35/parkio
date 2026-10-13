#!/usr/bin/env python3
"""Self-test of u06-alertmanager-step.py, using fakes only.

Fakes:
- docker: a JSON state of three parkio containers (alertmanager, prometheus, caddy) answers ps, inspect, info and exec.
  - `amtool config routes test` prints the running receiver;
  - wget /metrics prints webhook counters that grow every TICK seconds while the receiver is `heartbeat`. It prints two
    failed series per integration and slack series, to exercise the summing and the integration filter.
- the release checkout is a real git repository whose scripts/parkio-prod-compose.sh is a fake wrapper:
  - `config --hash alertmanager` hashes the heartbeat URL of PARKIO_ENV_FILE;
  - `up -d --no-build --no-deps alertmanager` replaces the alertmanager container accordingly. Marker files can make it
    fail, or also replace prometheus;
  - it records its arguments and whether it saw a variable outside the allowlist.

Scenarios: usage refusals; pre failures (no URL, generic webhook set, another key changed, another pending
alertmanager difference, BackupStale armed, dirty release checkout); pre PASS; recreate refusals (env changed after pre, a
later failing pre, a stale pre); recreate PASS; recreate failures (failing webhook, collateral change, wrapper error);
rollback refusal (backup differs elsewhere) and PASS; status. No URL value may appear in any output or record.

  python3 -I u06-alertmanager-step-selftest.py
"""
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
HB_FAIL = "https://hc-ping.com/failing0-4a5b-4c6d-8e7f-0a1b2c3d4e5f"
SLACK = "https://hooks.slack.com/services/T000SELF/B000TEST/synthetic0webhook0value"
SECRETS = (HB, HB_FAIL, SLACK)
BACKUP = ".env.azure-hosted-beta.bak-20261013T080000Z"
results = []


def ok(cond, label, extra=""):
    results.append(bool(cond))
    print(("ok   " if cond else "FAIL ") + label + ("" if cond else f"\n{extra[-3000:]}"))


FAKE_DOCKER = r'''#!/usr/bin/env python3
import json, os, sys, time
W = os.environ["U06A_W"]
TICK = 0.5
a = sys.argv[1:]
state = json.load(open(os.path.join(W, "state.json")))
cs = state["containers"]
if a[:1] == ["info"]:
    print("27.3.1"); sys.exit(0)
if a[:1] == ["ps"]:
    print("\n".join(cs)); sys.exit(0)
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
        failed = n if c.get("fail") else 0
        print("# HELP alertmanager_notifications_total The total number of attempted notifications.")
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
with open(os.path.join(W, "wrapper-env.log"), "a") as fh:
    fh.write(json.dumps({"args": a, "envFile": env_file, "keys": sorted(os.environ)}) + "\n")
url = ""
for line in open(env_file, encoding="utf-8"):
    m = re.match(r"^PARKIO_ALERT_HEARTBEAT_URL=(.*)$", line.rstrip("\r\n"))
    if m:
        url = m.group(1)
model = hashlib.sha256(("am|" + url).encode()).hexdigest()[:16]
if a == ["config", "--hash", "alertmanager"]:
    print("alertmanager " + model); sys.exit(0)
if a == ["up", "-d", "--no-build", "--no-deps", "alertmanager"]:
    if os.path.exists(os.path.join(W, "wrapper-fail")):
        sys.exit(3)
    p = os.path.join(W, "state.json")
    state = json.load(open(p))
    cs = state["containers"]
    for i in [i for i, c in cs.items() if c["service"] == "alertmanager"]:
        del cs[i]
    new = hashlib.sha256(("new|" + str(time.time())).encode()).hexdigest()
    cs[new] = {"service": "alertmanager", "name": "parkio-alertmanager", "image": "sha256:" + "a" * 64, "hash": model,
               "status": "running", "health": "healthy", "started": time.time(), "receiver": "heartbeat" if url else "null",
               "fail": "failing" in url}
    if os.path.exists(os.path.join(W, "touch-prometheus")):
        old = [i for i, c in cs.items() if c["service"] == "prometheus"][0]
        cs["f" * 64] = dict(cs.pop(old))
    json.dump(state, open(p + ".tmp", "w")); os.replace(p + ".tmp", p)
    sys.exit(0)
sys.exit(2)
'''


def git(repo, *args):
    p = subprocess.run(["git", "-C", repo, "-c", "user.name=t", "-c", "user.email=t@t"] + list(args), capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit(f"git {args}: {p.stderr}")
    return p.stdout


def main():
    import hashlib
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

        def reset(url=HB, generic="", extra_change=False, am_hash=None):
            for f in os.listdir(os.path.join(L, "docker")):
                os.remove(os.path.join(L, "docker", f))
            for f in os.listdir(T):
                os.remove(os.path.join(T, f))
            shutil.rmtree(OUT, ignore_errors=True)
            for f in ("wrapper-env.log", "wrapper-fail", "touch-prometheus"):
                if os.path.exists(os.path.join(W, f)):
                    os.remove(os.path.join(W, f))
            git(R, "checkout", "-q", "--detach", pin)
            git(R, "clean", "-qfd")
            base = original.replace("PARKIO_ALERT_WEBHOOK_URL=", "PARKIO_ALERT_WEBHOOK_URL=" + generic)
            fd = os.open(os.path.join(L, "docker", BACKUP), os.O_WRONLY | os.O_CREAT, 0o600)
            os.write(fd, base.encode())
            os.close(fd)
            current = base + ("\n" + COMMENT + "\nPARKIO_ALERT_HEARTBEAT_URL=" + url + "\n" if url else "")
            if extra_change:
                current = current.replace("PARKIO_PUSH_DELIVERY_PROVIDER=expo", "PARKIO_PUSH_DELIVERY_PROVIDER=noop")
            fd = os.open(ENV, os.O_WRONLY | os.O_CREAT, 0o600)
            os.write(fd, current.encode())
            os.close(fd)
            state = {"containers": {
                "1" * 64: {"service": "alertmanager", "name": "parkio-alertmanager", "image": "sha256:" + "a" * 64,
                           "hash": am_hash or model(""), "status": "running", "health": "healthy", "started": time.time(),
                           "receiver": "null"},
                "2" * 64: {"service": "prometheus", "name": "parkio-prometheus", "image": "sha256:" + "b" * 64, "hash": "p" * 16,
                           "status": "running", "health": "healthy", "started": 0, "receiver": ""},
                "3" * 64: {"service": "caddy", "name": "parkio-caddy", "image": "sha256:" + "d" * 64, "hash": "c" * 16,
                           "status": "running", "health": "healthy", "started": 0, "receiver": ""}}}
            json.dump(state, open(os.path.join(W, "state.json"), "w"))

        def tool(*args):
            p = subprocess.run([sys.executable, "-I", TOOL] + list(args), capture_output=True, text=True, env=env, timeout=120)
            outputs.append(p.stdout + p.stderr)
            return p.returncode, p.stdout + p.stderr

        def wrapper_calls():
            p = os.path.join(W, "wrapper-env.log")
            return [json.loads(l) for l in open(p)] if os.path.exists(p) else []

        def ups():
            return [c for c in wrapper_calls() if c["args"][:1] == ["up"]]

        def has(out, *lines):
            return all(l in out for l in lines)

        def state():
            return json.load(open(os.path.join(W, "state.json")))["containers"]

        # usage
        reset()
        for args, label in (([], "no command"), (["pre", "--yes"], "pre --yes"), (["recreate"], "recreate without --yes"),
                            (["recreate", "--yes", "--bogus"], "an unknown argument"),
                            (["rollback", "--backup", "x.bak", "--yes"], "a malformed backup name"),
                            (["rollback", "--backup", BACKUP], "rollback without --yes"),
                            (["recreate", "--yes", "--yes"], "a repeated --yes")):
            rc, out = tool(*args)
            ok(rc == 2 and not ups(), f"U usage refusal: {label}", out)

        # pre failures
        reset(url="")
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL the env file holds an https heartbeat URL (value not shown) (absent)" in out, "P1 pre fails without a heartbeat URL", out)
        reset(generic="https://ops.example.test/hook")
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL the generic operator webhook is empty" in out, "P2 pre fails when the generic webhook is set", out)
        reset(extra_change=True)
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL the newest env backup differs from the env file only in the heartbeat key" in out,
           "P3 pre fails when another key changed too", out)
        reset(am_hash="9" * 16)
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL with the backup env the model equals the running Alertmanager" in out,
           "P4 pre fails when alertmanager has another pending difference", out)
        reset()
        open(os.path.join(T, "parkio_backup_synthetic_stale.prom"), "w").write("# marker\n")
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL no synthetic BackupStale textfile armed" in out, "P5 pre fails while a BackupStale test is armed", out)
        reset()
        open(os.path.join(R, "stray"), "w").write("x")
        rc, out = tool("pre")
        ok(rc == 1 and "FAIL the release checkout is clean at the PIN or R1" in out, "P6 pre fails on a dirty release checkout", out)
        reset()
        rc, out = tool("pre")
        ok(rc == 0 and "U06 alertmanager pre: PASS" in out and "Watchdog routes to null" in out and
           f"rollback --backup {BACKUP} --yes" in out and not ups(), "P7 pre passes, names the backup and changes nothing", out)
        calls = wrapper_calls()
        ok(calls and all(c["envFile"] in (ENV, os.path.join(L, "docker", BACKUP)) for c in calls) and
           not any("LEAKY_SECRET" in c["keys"] for c in calls), "P7 the wrapper gets PARKIO_ENV_FILE and no other variable", str(calls))

        # recreate refusals
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
        tool("pre")                                                 # fails: another key differs from the backup
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

        # recreate PASS, then rollback
        reset()
        tool("pre")
        before = dict(state())
        rc, out = tool("recreate", "--yes")
        after = state()
        am = [c for c in after.values() if c["service"] == "alertmanager"]
        ok(rc == 0 and "U06 alertmanager recreate: PASS" in out, "R4 recreate passes", out)
        ok([c["args"] for c in ups()] == [["up", "-d", "--no-build", "--no-deps", "alertmanager"]],
           "R4 the wrapper ran exactly once: up -d --no-build --no-deps alertmanager", str(ups()))
        ok(len(am) == 1 and am[0]["receiver"] == "heartbeat" and am[0]["hash"] == model(HB) and
           {k: v for k, v in after.items() if v["service"] != "alertmanager"} == {k: v for k, v in before.items() if v["service"] != "alertmanager"},
           "R4 only alertmanager was replaced; its hash is the heartbeat model's")
        ok(has(out, "PASS Watchdog routes to heartbeat", "PASS heartbeat delivered at least twice", "PASS no webhook notification failed (failed 0.0)",
               "PASS every other container unchanged against the baseline", "PASS alertmanager config hash = the wrapper's model hash"),
           "R4 post-checks: route, two deliveries, no failure, others unchanged, hash", out)
        ok(not any("LEAKY_SECRET" in c["keys"] for c in wrapper_calls()), "R4 the wrapper saw no variable outside the allowlist")
        rc, out = tool("rollback", "--backup", BACKUP, "--yes")
        am = [c for c in state().values() if c["service"] == "alertmanager"]
        ok(rc == 0 and "U06 alertmanager rollback: PASS" in out and open(ENV, "rb").read() == open(os.path.join(L, "docker", BACKUP), "rb").read()
           and len(am) == 1 and am[0]["receiver"] == "null" and am[0]["hash"] == model(""), "B2 rollback restores the backup and Watchdog -> null", out)
        ok(oct(os.stat(ENV).st_mode & 0o777) == "0o600", "B2 the env file keeps mode 600")
        rc, out = tool("status")
        ok(rc == 0 and "pre: PASS" in out and "recreate: PASS" in out and "rollback: PASS" in out, "S status lists the records", out)

        # recreate failures
        reset(url=HB_FAIL)
        tool("pre")
        rc, out = tool("recreate", "--yes")
        ok(rc == 1 and "FAIL no webhook notification failed" in out and "FAIL heartbeat delivered at least twice" in out,
           "R5 a failing webhook fails the recreate", out)
        reset()
        tool("pre")
        open(os.path.join(W, "touch-prometheus"), "w").write("x")
        rc, out = tool("recreate", "--yes")
        ok(rc == 1 and "FAIL every other container unchanged against the baseline (prometheus)" in out, "R6 a collateral change fails", out)
        reset()
        tool("pre")
        open(os.path.join(W, "wrapper-fail"), "w").write("x")
        rc, out = tool("recreate", "--yes")
        ok(rc == 1 and "FAIL wrapper exit 0 (exit 3)" in out and "Watchdog routes to heartbeat" not in out, "R7 a wrapper error stops the post-checks", out)

        # rollback refusal
        reset()
        tool("pre")
        tool("recreate", "--yes")
        with open(ENV, "a") as fh:
            fh.write("PARKIO_Y=2\n")
        n = len(ups())
        rc, out = tool("rollback", "--backup", BACKUP, "--yes")
        ok(rc == 1 and "FAIL the backup exists and differs from the env file only in the heartbeat key" in out and len(ups()) == n
           and b"PARKIO_Y=2" in open(ENV, "rb").read(), "B1 rollback refuses a backup that differs elsewhere", out)

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
