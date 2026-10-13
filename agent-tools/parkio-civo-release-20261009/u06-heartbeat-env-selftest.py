#!/usr/bin/env python3
"""Self-test of u06-heartbeat-env.py.

Fixtures:
- The env file is built from the release's docker/.env.azure-hosted-beta.example at f888a81b, with synthetic secrets,
  Expo push set as on the host, and no PARKIO_ALERT_HEARTBEAT_URL line, as on the host. So the REAL
  preflight-hosted-beta.sh from f888a81b passes except for its heartbeat warning.
- docker is a fake. Its config renders give alertmanager the env file's URL, and its hash depends on that URL, so the
  "only alertmanager changes" gate is exercised.
- The live and release checkouts are real git repositories.
- The tool runs under a pseudo-terminal and answers its hidden prompts like a person pasting a URL.

Scenarios that stop with the file unchanged and no backup: no terminal, entries differ, the entry equals another value in
the file, a placeholder, plain http, an unknown argument, a dirty release checkout, a release checkout at another commit.
Scenarios that pass: the normal run (the key is appended); the release checkout at a web-pin-only descendant;
--replace-url (rewritten in place). A plain re-run stops because the URL is set.
In every transcript and report, no URL value appears.

  python3 -I u06-heartbeat-env-selftest.py      (from a git checkout that contains f888a81b)
"""
import json
import os
import pty
import re
import secrets
import select
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "u06-heartbeat-env.py")
PIN = "f888a81b113c0c486ea0949067779443857e644d"
SLACK = "https://hooks.slack.com/services/T000SELF/B000TEST/synthetic0webhook0value"   # fixed, synthetic
results = []


def ok(cond, label, extra=""):
    results.append(bool(cond))
    print(("ok   " if cond else "FAIL ") + label + ("" if cond else f"\n{extra}"))


def git(repo, *args):
    p = subprocess.run(["git", "-C", repo, "-c", "user.name=t", "-c", "user.email=t@t"] + list(args), capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit(f"git {args}: {p.stderr}")
    return p.stdout


FAKE_DOCKER = r'''#!/usr/bin/env python3
import hashlib, json, os, re, sys
a = sys.argv[1:]
W = os.environ["U06H_W"]
if a[:1] == ["ps"]:
    print("a" * 64); sys.exit(0)
if a[:1] == ["inspect"]:
    print("a" * 64 + " " + open(os.path.join(W, "am-start")).read().strip()); sys.exit(0)
if a[:1] == ["compose"]:
    env_file = a[a.index("--env-file") + 1]
    url = None
    for line in open(env_file, encoding="utf-8", errors="replace"):
        m = re.match(r"^PARKIO_ALERT_HEARTBEAT_URL=(.*)$", line.rstrip("\r\n"))
        if m:
            url = m.group(1)
    if "--hash" in a:
        for svc in ("alertmanager", "prometheus", "caddy", "web", "auth-service"):
            seed = svc + ("|" + (url or "") if svc == "alertmanager" else "")
            print(svc + " " + hashlib.sha256(seed.encode()).hexdigest()[:16])
        sys.exit(0)
    if "--format" in a:
        env = {"PARKIO_ALERT_SLACK_CHANNEL": "#parkio-alert"}
        if url is not None:
            env["PARKIO_ALERT_HEARTBEAT_URL"] = url
        print(json.dumps({"services": {"alertmanager": {"environment": env}}})); sys.exit(0)
sys.exit(2)
'''


def build_env(example, dest, extra_lines=()):
    pat = re.compile(r"CHANGE_ME|CHANGEME|CHANGE-ME|PLACEHOLDER|REPLACE_ME|REPLACEME|YOUR_|<[A-Za-z_-]+>|DUMMY|SAMPLE_|TODO|FIXME|"
                     r"00000000-0000-0000-0000-000000000000", re.I)
    out = []
    for line in example.splitlines():
        m = re.match(r"^([A-Z0-9_]+)=(.*)$", line)
        key = m.group(1) if m else None
        if key == "PARKIO_ALERT_HEARTBEAT_URL":
            continue                                   # absent, as on the host
        if key == "PARKIO_PUSH_DELIVERY_PROVIDER":
            line = "PARKIO_PUSH_DELIVERY_PROVIDER=expo"
        elif key == "PARKIO_EXPO_ACCESS_TOKEN":
            line = "PARKIO_EXPO_ACCESS_TOKEN=Synth" + secrets.token_hex(20)
        elif key == "PARKIO_ALERT_SLACK_WEBHOOK_URL":
            line = "PARKIO_ALERT_SLACK_WEBHOOK_URL=" + SLACK
        elif key == "KAFKA_CLUSTER_ID":
            import base64
            line = "KAFKA_CLUSTER_ID=" + base64.urlsafe_b64encode(secrets.token_bytes(16)).decode().rstrip("=")
        elif key == "PARKIO_JWT_PRIVATE_KEY_PEM":
            import base64
            body = base64.b64encode(secrets.token_bytes(48)).decode()
            line = 'PARKIO_JWT_PRIVATE_KEY_PEM="-----BEGIN PRIVATE KEY-----\\n' + body + '\\n-----END PRIVATE KEY-----"'
        elif key == "PARKIO_ACME_EMAIL":
            line = "PARKIO_ACME_EMAIL=ops@selftest.parkio.invalid"
        elif m and pat.search(m.group(2)):
            line = f"{key}={'re_' if key == 'PARKIO_RESEND_API_KEY' else ''}Synth{secrets.token_hex(24)}"
        out.append(line)
    if not any(l.startswith("PARKIO_PUSH_DELIVERY_ENABLED=") for l in out):
        out.append("PARKIO_PUSH_DELIVERY_ENABLED=true")
    out += list(extra_lines)
    fd = os.open(dest, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out))                       # no final newline on purpose


def run_pty(args, answers, env, timeout=120):
    """Run the tool under a pseudo-terminal; answer each hidden prompt with the next entry of `answers`."""
    pid, fd = pty.fork()
    if pid == 0:
        os.execve(sys.executable, [sys.executable, "-I", TOOL] + list(args), env)
    out, pending, deadline = b"", list(answers), time.time() + timeout
    while time.time() < deadline:
        r, _, _ = select.select([fd], [], [], 0.5)
        if fd in r:
            try:
                chunk = os.read(fd, 4096)
            except OSError:
                break
            if not chunk:
                break
            out += chunk
            if pending and out.rstrip().endswith(b"(hidden):"):
                os.write(fd, pending.pop(0).encode() + b"\n")
    _, status = os.waitpid(pid, 0)
    return os.waitstatus_to_exitcode(status), out.decode("utf-8", "replace")


def main():
    W = tempfile.mkdtemp(prefix="u06h-")
    try:
        repo = subprocess.run(["git", "-C", HERE, "rev-parse", "--show-toplevel"], capture_output=True, text=True).stdout.strip()
        example = subprocess.run(["git", "-C", repo, "show", f"{PIN}:docker/.env.azure-hosted-beta.example"], capture_output=True, text=True).stdout
        preflight = subprocess.run(["git", "-C", repo, "show", f"{PIN}:scripts/preflight-hosted-beta.sh"], capture_output=True, text=True).stdout
        if not example or not preflight:
            raise SystemExit("SELFTEST FAIL: needs a git checkout containing " + PIN)
        L, R, out_dir, bindir = (os.path.join(W, d) for d in ("live", "release", "out", "bin"))
        os.makedirs(os.path.join(L, "docker"))
        os.makedirs(os.path.join(R, "docker"))
        os.makedirs(os.path.join(R, "scripts"))
        os.makedirs(bindir)
        open(os.path.join(bindir, "docker"), "w").write(FAKE_DOCKER)
        os.chmod(os.path.join(bindir, "docker"), 0o755)
        open(os.path.join(W, "am-start"), "w").write("2026-10-12T07:15:58Z")
        ENV = os.path.join(L, "docker", ".env.azure-hosted-beta")
        # live checkout: dirty, ignores .env.*
        open(os.path.join(L, ".gitignore"), "w").write(".env\n.env.*\n")
        open(os.path.join(L, "README"), "w").write("tracked\n")
        git(L, "init", "-q")
        git(L, "add", "-A")
        git(L, "commit", "-qm", "live")
        open(os.path.join(L, "README"), "a").write("dirty\n")
        # release checkout at the PIN (a local commit whose id is used as the PIN)
        open(os.path.join(R, "scripts", "preflight-hosted-beta.sh"), "w").write(preflight)
        open(os.path.join(R, "docker", "compose.production.files"), "w").write("docker/docker-compose.yml\ndocker/docker-compose.azure-hosted-beta.yml\n")
        for f in ("docker-compose.yml", "docker-compose.azure-hosted-beta.yml", "docker-compose.civo-alertmanager.yml"):
            open(os.path.join(R, "docker", f), "w").write("services: {}\n")
        open(os.path.join(R, "docker", "docker-compose.web-release-pin.yml"), "w").write(
            "# pin\nname: parkio\n\nservices:\n  web:\n    image: ghcr.io/adberilgen35/parkio/web@sha256:" + "a" * 64 + "\n")
        git(R, "init", "-q")
        git(R, "add", "-A")
        git(R, "commit", "-qm", "pin")
        pin = git(R, "rev-parse", "HEAD").strip()

        env = {"PATH": bindir + ":" + os.environ["PATH"], "HOME": W, "LANG": "C.UTF-8", "U06H_TEST": "1", "U06H_W": W,
               "U06H_PIN": pin, "U06H_LIVE": L, "U06H_RELEASE": R, "U06H_ENV_FILE": ENV, "U06H_OUT": out_dir, "TERM": "dumb"}
        good = "https://hc-ping.com/" + "1f2e3d4c-5b6a-4789-a0b1-" + secrets.token_hex(6)
        other = "https://hc-ping.com/" + "9a8b7c6d-5e4f-4321-b0a9-" + secrets.token_hex(6)
        urls = [good, other, SLACK]

        def fresh():
            build_env(example, ENV)
            for f in os.listdir(os.path.join(L, "docker")):
                if ".bak-" in f:
                    os.remove(os.path.join(L, "docker", f))
            git(R, "checkout", "-q", "--detach", pin)
            return open(ENV, "rb").read()

        def no_secret(text, label):
            ok(not any(u in text for u in urls), f"  {label}: no URL value in the transcript")

        def reports_clean():
            texts = "".join(open(os.path.join(out_dir, f), encoding="utf-8").read() for f in os.listdir(out_dir)) if os.path.isdir(out_dir) else ""
            return not any(u in texts for u in urls)

        def stops(label, args, answers, expect, setup=None, use_pty=True):
            original = fresh()
            if setup:
                setup()
            if use_pty:
                rc, out = run_pty(args, answers, env)
            else:
                p = subprocess.run([sys.executable, "-I", TOOL] + args, capture_output=True, text=True, env=env, stdin=subprocess.DEVNULL)
                rc, out = p.returncode, p.stdout + p.stderr
            backups = [f for f in os.listdir(os.path.join(L, "docker")) if ".bak-" in f]
            ok(rc == 1 and expect in out and open(ENV, "rb").read() == original and not backups, label, out)
            no_secret(out, label)

        stops("S0 no terminal stops", [], [], "standard input is not a terminal", use_pty=False)
        stops("S1 entries that differ stop", [], [good, other], "the two entries differ")
        slack = None

        def slack_value():
            nonlocal slack
            for line in open(ENV, encoding="utf-8"):
                if line.startswith("PARKIO_ALERT_SLACK_WEBHOOK_URL="):
                    slack = line.split("=", 1)[1].strip()
        fresh()
        slack_value()
        stops("S2 the Slack webhook URL is refused", [], [slack, slack], "equals the value of PARKIO_ALERT_SLACK_WEBHOOK_URL")
        stops("S3 a placeholder is refused", [], ["https://hc-ping.com/CHANGE_ME_please"] * 2, "looks like a placeholder")
        stops("S3b plain http is refused", [], ["http://hc-ping.com/" + "x" * 30] * 2, "not an https URL")
        stops("S4 an unknown argument stops before any prompt", ["--url=https://x"], [], "unknown argument(s)")
        stops("S8 a dirty release checkout stops", [], [], "is not clean",
              setup=lambda: open(os.path.join(R, "stray.txt"), "w").write("x"))
        os.remove(os.path.join(R, "stray.txt"))

        def other_commit():
            open(os.path.join(R, "docker", "docker-compose.yml"), "w").write("services: {x: {image: y}}\n")
            git(R, "commit", "-qam", "another change")
        stops("S9 a release checkout at another commit stops", [], [], "neither the PIN nor the PIN plus only the web pin", setup=other_commit)
        git(R, "checkout", "-q", "--detach", pin)

        original = fresh()
        rc, out = run_pty([], [good, good], env)
        after = open(ENV, "rb").read()
        backups = sorted(f for f in os.listdir(os.path.join(L, "docker")) if ".bak-" in f)
        ok(rc == 0 and "U06 heartbeat env COMPLETE" in out, "S5 the normal run passes", out)
        ok(len(backups) == 1 and open(os.path.join(L, "docker", backups[0]), "rb").read() == original, "S5 one backup, identical to the original")
        ok(after.startswith(original) and after[len(original):] ==
           b"\n# External heartbeat / dead-man's switch (U06, owner-authorized activation)\nPARKIO_ALERT_HEARTBEAT_URL=" + good.encode() + b"\n",
           "S5 the key is appended under its comment; every other byte unchanged")
        for g in ("PASS preflight (azure-hosted-beta, env validation)", "PASS preflight reports nothing about the heartbeat",
                  "PASS release model gives alertmanager the entered URL", "PASS only alertmanager's release config changes",
                  "PASS running alertmanager untouched", "PASS live checkout git status unchanged"):
            ok(g in out, f"S5 {g}", out)
        no_secret(out, "S5")
        rc, out = run_pty([], [good, good], env)
        ok(rc == 1 and "is already set" in out, "S6 a re-run stops because the URL is set", out)
        no_secret(out, "S6")
        time.sleep(1.2)                 # the backup name has one-second resolution; a same-second rerun is refused by design
        rc, out = run_pty(["--replace-url"], [other, other], env)
        backups = sorted(f for f in os.listdir(os.path.join(L, "docker")) if ".bak-" in f)
        ok(rc == 0 and len(backups) == 2 and ("PARKIO_ALERT_HEARTBEAT_URL=" + other).encode() in open(ENV, "rb").read()
           and good.encode() not in open(ENV, "rb").read(), "S7 --replace-url rewrites in place with a second backup", out)
        no_secret(out, "S7")

        # S10: the release checkout at a web-pin-only descendant (the MapTiler deploy commit R1) is accepted
        fresh()
        p = os.path.join(R, "docker", "docker-compose.web-release-pin.yml")
        open(p, "w").write("# pin R1\nname: parkio\n\nservices:\n  web:\n    image: ghcr.io/adberilgen35/parkio/web@sha256:" + "b" * 64 + "\n")
        git(R, "commit", "-qam", "R1: web pin only")
        rc, out = run_pty([], [good, good], env)
        ok(rc == 0 and "the PIN plus only the web pin (web image alone)" in out, "S10 a web-pin-only descendant (R1) is accepted", out)
        no_secret(out, "S10")
        git(R, "checkout", "-q", "--detach", pin)
        ok(reports_clean(), "no URL value in any saved report")
    finally:
        shutil.rmtree(W, ignore_errors=True)
    print(f"=== summary: {sum(results)} ok, {len(results) - sum(results)} FAIL ===")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
