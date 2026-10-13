#!/usr/bin/env python3
"""Gateway internal secret rotation on parkio-civo-prod (incident 2026-10-13: PARKIO_GATEWAY_INTERNAL_SECRET was in the
world-readable rendered model). Run as civo (Python 3.8+); running it needs the independent review of
agent-tools/parkio-redis-credential-incident-20261013/GATEWAY-SECRET-ROTATION-PROCEDURE.md:

  python3 -I gis-rotate.py pre
  python3 -I gis-rotate.py rotate --yes
  python3 -I gis-rotate.py rollback --yes     (back to the EXPOSED secret: only when the rotation cannot complete)
  python3 -I gis-rotate.py redact --yes       (after a passing rotation)
  python3 -I gis-rotate.py status

How the secret is used (code at the PIN):
- the gateway stamps PARKIO_GATEWAY_INTERNAL_SECRET onto every routed request as X-Gateway-Auth;
- the services' own service-to-service clients send it the same way;
- every downstream GatewayAuthFilter (ordered before Spring Security) accepts its own secret plus the comma-separated
  PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS, and refuses anything else with 401 GATEWAY_AUTH_REQUIRED. /actuator/** is
  exempt;
- both variables reach all ten app services through the shared env anchor of docker-compose.apps.yml.
So the rotation overlaps in three steps, each a rolling recreate. At every moment each receiver accepts what every
sender sends:
  A. accept the new value: ACCEPTED=new, the secret stays old. Every downstream service is recreated.
  B. send the new value: secret=new, ACCEPTED=old. Every downstream service, then the gateway, is recreated.
  C. drop the old value: ACCEPTED empty. Every downstream service, then the gateway, is recreated.
Probes run inside the gateway container (it has curl and the backend network): `curl -s -H @-`, with the
X-Gateway-Auth header on stdin, to http://<service>:<port><path>. auth answers 200 on its public registration-mode
path; the others answer 404 behind the filter. A value is refused exactly when the answer is 401
GATEWAY_AUTH_REQUIRED; it is accepted when the answer is anything else. No value reaches a process argument, the
output or a record.

pre (read-only):
- host, docker, dockerd debug off, and the release checkout clean at R2; outside 03:00-04:30 UTC;
- the env file holds the secret (at least 32 characters) and an empty ACCEPTED list;
- the model's carriers of the value are exactly the ten app services, and so are the live containers (any project,
  inside Env only);
- each is running, healthy, at the model's hash, with its image reference resolving to the running image;
- every downstream service accepts the current value and refuses a random wrong one (the probe discriminates);
- the public API answers 200 through the gateway.
rotate (--yes), within an hour of a passing pre, with the same env file and containers: phases A, B and C as above.
After each recreate it checks: healthy; the expected env values; the model's hash; every other container unchanged
except those already moved; the expected probe results. At the end:
- the public API answers 200;
- no container carries the old value anywhere;
- every consumer is at the model's hash.
rollback (--yes), after a failed or interrupted A, B or C:
- restores the original env file (backup);
- recreates, downstream first, then the gateway, every consumer that does not run the original values (live state);
- verifies the old value is accepted again everywhere.
This brings the EXPOSED secret back; while it runs, calls between rolled-back and not-yet-rolled-back services can
fail for seconds.
redact (--yes), after a passing rotation:
- replaces the old value with CHANGE_ME_redacted_after_rotation_20261013 in every
  /opt/parkio/docker/.env.azure-hosted-beta.bak-* file, the pr68 artifact and the checkpoint env copy;
- scans /opt, /home, /usr/local, /tmp and /var/tmp in memory, paths only;
- redacts its own backup last.
Records: ~/parkio-release-20261009/gis-rotation/gr-<stamp>/ (mode 700, files 600). Exit 0 = PASS, 1 = FAIL, 2 = usage.
"""
import calendar
import glob
import hashlib
import json
import os
import re
import secrets
import socket
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

TEST = os.environ.get("GIS_TEST") == "1"


def cfg(name, default):
    """Overrides are honoured only by the self-test."""
    return os.environ.get(name, default) if TEST else default


R2 = cfg("GIS_R2", "c30b9ddbc960cac7e45566c940403dfed1739c7c")
HOST = cfg("GIS_HOST", "parkio-civo-prod")
REL = cfg("GIS_RELEASE", "/opt/parkio-release")
ENV_FILE = cfg("GIS_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
OUT = cfg("GIS_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009", "gis-rotation"))
PUBLIC_PROBE = cfg("GIS_PUBLIC_PROBE", "https://api.parkio.dev/api/v1/auth/registration-mode")
NOW = cfg("GIS_NOW", "")
HEALTH_WAIT = int(cfg("GIS_HEALTH_WAIT", "300"))
POLL = float(cfg("GIS_POLL", "5"))
REDACT_GLOB = cfg("GIS_REDACT_GLOB", "/opt/parkio/docker/.env.azure-hosted-beta.bak-*")
REDACT_EXTRA = cfg("GIS_REDACT_EXTRA", ":".join((
    "/opt/parkio/backup-artifacts/pr68-auth-email-20260922T114212Z/env.azure-hosted-beta.pre",
    "/opt/parkio-recovery/checkpoint-550848-postrestore/.env.azure-hosted-beta"))).split(":")
SCAN_ROOTS = cfg("GIS_SCAN_ROOTS", "/opt:/home:/usr/local:/tmp:/var/tmp").split(":")
FAULT = cfg("GIS_FAULT", "")                    # self-test only: simulate an interruption at a named point
PROJECT = "parkio"
SECRET, ACCEPTED = "PARKIO_GATEWAY_INTERNAL_SECRET", "PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS"
GATEWAY = "gateway-service"
# least user-facing first; auth last among the downstream services
DOWNSTREAM = ("analytics-service", "ai-validation-service", "moderation-service", "notification-service", "gamification-service",
              "media-service", "user-service", "parking-service", "auth-service")
CONSUMERS = DOWNSTREAM + (GATEWAY,)
PROBE_PATH = {"auth-service": "/api/v1/auth/registration-mode"}        # behind auth's permitAll; others 404 behind the filter
DEFAULT_PROBE_PATH = "/api/v1/__gis_probe"
REFUSED_CODE = "GATEWAY_AUTH_REQUIRED"
PLACEHOLDER = re.compile(r"CHANGE_ME|CHANGEME|CHANGE-ME|PLACEHOLDER|REPLACE_ME|REPLACEME|YOUR_|<[A-Za-z_-]+>|DUMMY|SAMPLE_|TODO|FIXME", re.I)
MARKER = "CHANGE_ME_redacted_after_rotation_20261013"
INBOX_OVERLAY = "docker/docker-compose.waitlist-ops-inbox.yml"
PRE_MAX_AGE = 3600
_T = time.time()
STAMP = time.strftime("%Y%m%dT%H%M%S", time.gmtime(_T)) + "%03dZ" % (int(_T * 1000) % 1000)
CHECKS = []
SECRETS = []


def scrub(text):
    for v in SECRETS:
        if v:
            text = text.replace(v, "<redacted>")
    return text


def say(line=""):
    print(scrub(line), flush=True)


def check(what, ok, detail=""):
    CHECKS.append({"check": what, "pass": bool(ok), "detail": scrub(str(detail))})
    say(("  PASS " if ok else "  FAIL ") + what + (f" ({detail})" if detail else ""))
    return bool(ok)


def base_env(extra=None):
    env = {k: os.environ[k] for k in ("PATH", "HOME", "LANG", "USER", "LOGNAME") if k in os.environ}
    if TEST and "GIS_W" in os.environ:
        env["GIS_W"] = os.environ["GIS_W"]
    env.update(extra or {})
    return env


def run(cmd, cwd=None, extra_env=None, timeout=300, stdin=None):
    """Every child gets the allowlisted environment; `stdin` (bytes) is its only input, empty when None."""
    try:
        p = subprocess.run(cmd, cwd=cwd, env=base_env(extra_env), capture_output=True, timeout=timeout,
                           input=stdin if stdin is not None else b"")
    except FileNotFoundError:
        return 127, b"", b"not found"
    except subprocess.TimeoutExpired:
        return 124, b"", b"timed out"
    return p.returncode, p.stdout, p.stderr


def git(*args):
    rc, out, _ = run(["git", "--no-optional-locks", "-c", f"safe.directory={REL}", "-C", REL] + list(args))
    return rc, out.decode("utf-8", "replace")


_OVERLAYS = None


def overlays():
    """`-f INBOX_OVERLAY` when the live gateway was created with that activation-only overlay, else nothing (first live
    pre of the Redis rotation, 2026-10-13)."""
    global _OVERLAYS
    if _OVERLAYS is None:
        rc, out, _ = run(["docker", "ps", "-q", "--no-trunc", "--filter", f"label=com.docker.compose.project={PROJECT}",
                          "--filter", f"label=com.docker.compose.service={GATEWAY}"])
        files = ""
        if rc == 0 and out.split():
            rc, out, _ = run(["docker", "inspect", "--format", '{{index .Config.Labels "com.docker.compose.project.config_files"}}',
                              out.split()[0].decode()])
            files = out.decode() if rc == 0 else ""
        _OVERLAYS = ["-f", INBOX_OVERLAY] if any(f.endswith("/" + os.path.basename(INBOX_OVERLAY)) for f in files.strip().split(",")) else []
    return _OVERLAYS


def wrapper(*args, timeout=900):
    return run(["./scripts/parkio-prod-compose.sh"] + overlays() + list(args), cwd=REL, extra_env={"PARKIO_ENV_FILE": ENV_FILE}, timeout=timeout)


def private_write(path, text):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(text)


def record_dir():
    d = os.path.join(OUT, f"gr-{STAMP}")
    os.makedirs(d, mode=0o700, exist_ok=True)
    return d


def save_progress(rec, path=None):
    text = json.dumps({k: rec.get(k) for k in ("stamp", "phase", "backup", "moved", "moving")}, sort_keys=True)
    path = path or os.path.join(record_dir(), "rotate-progress.json")
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path))
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(text)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


def finish(name, rec):
    rec["checks"] = CHECKS
    rec["verdict"] = "PASS" if CHECKS and all(c["pass"] for c in CHECKS) else "FAIL"
    text = json.dumps(rec, indent=1, sort_keys=True) + "\n"
    if any(v and v in text for v in SECRETS):
        raise SystemExit("internal error: a record would contain a secret value; nothing written")
    path = os.path.join(record_dir(), f"{name}.json")
    private_write(path, text)
    say(f"gateway secret rotation {name}: {rec['verdict']} | record {path}")
    return 0 if rec["verdict"] == "PASS" else 1


def latest_path(name):
    try:
        found = [os.path.join(OUT, d, f"{name}.json") for d in sorted(os.listdir(OUT)) if d.startswith("gr-")]
    except OSError:
        return None
    found = [p for p in found if os.path.isfile(p)]
    return found[-1] if found else None


def latest(name):
    p = latest_path(name)
    try:
        return json.load(open(p, encoding="utf-8")) if p else None
    except (OSError, ValueError):
        return None


def now():
    return datetime.fromtimestamp(float(NOW), timezone.utc) if NOW else datetime.now(timezone.utc)


# ------------------------------------------------------------------ env file (values stay in memory)
def env_values(data):
    out = {}
    for line in data.splitlines():
        for key in (SECRET, ACCEPTED):
            if line.startswith(key.encode() + b"="):
                v = line.split(b"=", 1)[1].decode("utf-8", "replace").strip()
                if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
                    v = v[1:-1]
                out[key] = v
    return out


def with_values(data, values):
    """data with each key's line set to KEY="value" (the live file's form); a missing key is appended."""
    out, seen = [], set()
    for line in data.splitlines(keepends=True):
        for key, value in values.items():
            if line.startswith(key.encode() + b"="):
                end = b"\r\n" if line.endswith(b"\r\n") else (b"\n" if line.endswith(b"\n") else b"")
                line = key.encode() + b'="' + value.encode("ascii") + b'"' + end
                seen.add(key)
        out.append(line)
    text = b"".join(out)
    for key, value in values.items():
        if key not in seen:
            text += (b"" if text.endswith(b"\n") or not text else b"\n") + key.encode() + b'="' + value.encode("ascii") + b'"\n'
    return text


def only_keys_changed(before, after, keys):
    strip = lambda d: [l for l in d.splitlines() if not any(l.startswith(k.encode() + b"=") for k in keys)]
    return strip(before) == strip(after)


def write_atomically(path, data, st):
    fd, tmp = tempfile.mkstemp(prefix=".gis-", dir=os.path.dirname(path))
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
            fh.flush()
            os.fsync(fh.fileno())
            os.fchmod(fh.fileno(), st.st_mode & 0o7777)
            os.fchown(fh.fileno(), st.st_uid, st.st_gid)
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise
    dfd = os.open(os.path.dirname(path), os.O_RDONLY)
    try:
        os.fsync(dfd)
    finally:
        os.close(dfd)


def preflight_ok():
    rc, _, _ = run(["sh", os.path.join(REL, "scripts", "preflight-hosted-beta.sh"), "--env-file", ENV_FILE,
                    "--deployment-profile", "azure-hosted-beta", "--skip-compose"], cwd=REL, timeout=300)
    return rc == 0


# ------------------------------------------------------------------ containers, the model and the probes
def inspect_all():
    rc, out, _ = run(["docker", "ps", "-aq", "--no-trunc"])
    ids = out.decode().split()
    if rc != 0 or not ids:
        return []
    rc, out, _ = run(["docker", "inspect"] + ids)
    return json.loads(out) if rc == 0 else []


def service_of(d):
    labels = (d.get("Config") or {}).get("Labels") or {}
    return labels.get("com.docker.compose.project"), labels.get("com.docker.compose.service")


def env_of(d):
    return dict(e.split("=", 1) for e in (d.get("Config") or {}).get("Env") or [] if "=" in e)


def parkio(svc, ds=None):
    found = [d for d in (ds if ds is not None else inspect_all()) if service_of(d) == (PROJECT, svc)]
    return found[0] if len(found) == 1 else None


def health(d):
    return ((d or {}).get("State") or {}).get("Health", {}).get("Status")


def name_of(d):
    return (d or {}).get("Name", "").lstrip("/")


def model():
    rc, out, _ = wrapper("config", "--format", "json", timeout=300)
    try:
        return json.loads(out) if rc == 0 else None
    except ValueError:
        return None


def model_hashes():
    rc, out, _ = wrapper("config", "--hash", "*", timeout=300)
    if rc != 0:
        return None
    return dict(l.split() for l in out.decode().splitlines() if len(l.split()) == 2)


def model_env(m, svc):
    env = (((m or {}).get("services") or {}).get(svc) or {}).get("environment") or {}
    if isinstance(env, list):
        env = dict(e.split("=", 1) if "=" in e else (e, "") for e in env)
    return env


def port_of(m, svc):
    test = json.dumps((((m or {}).get("services") or {}).get(svc) or {}).get("healthcheck", {}).get("test"))
    found = re.search(r"localhost:(\d+)/actuator", test)
    return int(found.group(1)) if found else None


def image_id(ref):
    rc, out, _ = run(["docker", "image", "inspect", "--format", "{{.Id}}", ref])
    return out.decode().strip() if rc == 0 else None


def baseline(ds):
    return {f"{p}/{s}": d.get("Id") for d in ds for p, s in [service_of(d)] if p}


def changed_since(base, ds, allowed):
    cur = baseline(ds)
    return sorted(k for k in set(base) | set(cur) if k not in allowed and base.get(k) != cur.get(k))


def wait_container(svc, before_id):
    deadline, d = time.time() + HEALTH_WAIT, None
    while time.time() < deadline:
        d = parkio(svc)
        if d and d.get("Id") != before_id and (d.get("State") or {}).get("Status") == "running" and health(d) == "healthy":
            return d
        time.sleep(POLL)
    return d


_GW = None


def gateway_name():
    """The gateway container's name; Compose keeps it across recreates."""
    global _GW
    if _GW is None:
        _GW = name_of(parkio(GATEWAY))
    return _GW


def probe(svc, port, value):
    """(status, code) of an X-Gateway-Auth request from inside the gateway container; the header goes on stdin."""
    gw = gateway_name()
    url = f"http://{svc}:{port}{PROBE_PATH.get(svc, DEFAULT_PROBE_PATH)}"
    rc, out, _ = run(["docker", "exec", "-i", gw, "curl", "-s", "-m", "10", "-H", "@-", "-w", "\n%{http_code}", url],
                     stdin=f"X-Gateway-Auth: {value}\n".encode(), timeout=60)
    if rc != 0:
        return None, None
    body, _, status = out.decode("utf-8", "replace").rpartition("\n")
    try:
        code = (json.loads(body) or {}).get("code") if body.strip().startswith("{") else None
    except ValueError:
        code = None
    return (int(status) if status.strip().isdigit() else None), code


def refused(svc, port, value):
    status, code = probe(svc, port, value)
    return status == 401 and code == REFUSED_CODE


def accepted(svc, port, value):
    status, code = probe(svc, port, value)
    return status is not None and status != 0 and not (status == 401 and code == REFUSED_CODE)


def public_ok():
    try:
        with urllib.request.urlopen(urllib.request.Request(PUBLIC_PROBE), timeout=20) as resp:
            return resp.status == 200
    except (urllib.error.URLError, OSError):
        return False


def release_ok():
    rc, head = git("rev-parse", "HEAD")
    rc2, status = git("status", "--porcelain")
    head = head.strip()
    return rc == 0 and rc2 == 0 and head == R2 and not status.strip(), head[:12] or "?"


def basics():
    check(f"host {HOST}", socket.gethostname() == HOST, socket.gethostname())
    rc, _, _ = run(["docker", "info", "--format", "{{.ServerVersion}}"])
    check("docker usable without sudo", rc == 0)
    rc, out, _ = run(["docker", "info", "--format", "{{.Debug}}"])
    check("dockerd debug logging is off (it would log exec requests)", rc == 0 and out.decode().strip() == "false", out.decode().strip())
    ok, head = release_ok()
    check("the release checkout is clean at R2", ok, head)


# ------------------------------------------------------------------ commands
def cmd_pre():
    say(f"gateway secret rotation pre | {STAMP} | read-only")
    rec = {"tool": "gis-rotate", "command": "pre", "stamp": STAMP}
    basics()
    t = now()
    check("outside 03:00-04:30 UTC (the backup)", not (t.hour == 3 or (t.hour == 4 and t.minute < 30)), t.strftime("%H:%M UTC"))
    say(f"  NOTE Compose calls {'include ' + INBOX_OVERLAY + ' (the live gateway was created with it)' if overlays() else 'use the production file list only'}")
    data = open(ENV_FILE, "rb").read()
    ev = env_values(data)
    cur = ev.get(SECRET, "")
    SECRETS.append(cur)
    check("the env file holds the secret (at least 32 characters) and an empty ACCEPTED list", len(cur) >= 32 and not ev.get(ACCEPTED))
    ds = inspect_all()
    m = model()
    if not check("the release model renders", m is not None):
        return finish("pre", rec)
    carriers_model = sorted(s for s, sv in (m.get("services") or {}).items() if cur and cur in json.dumps(sv))
    check("in the model, exactly the ten app services carry the value (in `environment`)",
          carriers_model == sorted(CONSUMERS) and all(model_env(m, s).get(SECRET) == cur for s in CONSUMERS), ", ".join(carriers_model))
    live = sorted(name_of(d) for d in ds if cur and cur in json.dumps(d.get("Config") or {}))
    want = sorted(name_of(parkio(s, ds)) for s in CONSUMERS if parkio(s, ds))
    outside = sorted(name_of(d) for d in ds if cur and cur in json.dumps({k: v for k, v in (d.get("Config") or {}).items() if k != "Env"}))
    check("the live containers carrying the value are exactly the ten app services, inside Env only (any project)",
          live == want and len(want) == len(CONSUMERS) and not outside, ", ".join(live))
    hashes = model_hashes() or {}
    for svc in CONSUMERS:
        d = parkio(svc, ds)
        lab = ((d or {}).get("Config") or {}).get("Labels") or {}
        ref = (((m.get("services") or {}).get(svc) or {}).get("image")) or f"{PROJECT}-{svc}"
        check(f"{svc}: running, healthy, at the model's hash, running the model's image",
              bool(d) and health(d) == "healthy" and lab.get("com.docker.compose.config-hash") == hashes.get(svc)
              and (d.get("Config") or {}).get("Image") == ref and image_id(ref) == d.get("Image"), health(d) or "?")
    wrong = secrets.token_urlsafe(48)
    for svc in DOWNSTREAM:
        port = port_of(m, svc)
        check(f"{svc}: the probe discriminates (the current value accepted, a random wrong one refused GATEWAY_AUTH_REQUIRED)",
              bool(port) and accepted(svc, port, cur) and refused(svc, port, wrong), f"port {port}")
    check("the public API answers 200 through the gateway", public_ok())
    rec["baseline"] = baseline(ds)
    rec["envSha256"] = hashlib.sha256(data).hexdigest()
    return finish("pre", rec)


def recreate(svc, base, moved, expect, accept, refuse, m, rec):
    """Recreate one consumer and check it. expect: {key: value} of its env; accept/refuse: values to probe."""
    rec["moving"] = svc
    save_progress(rec)
    before = parkio(svc)
    rc, _, _ = wrapper("up", "-d", "--no-build", "--no-deps", svc)
    if FAULT == f"after-up:{rec['phase']}:{svc}":
        raise SystemExit(99)
    d = wait_container(svc, (before or {}).get("Id"))
    lab = ((d or {}).get("Config") or {}).get("Labels") or {}
    env = env_of(d) if d else {}
    good = check(f"{rec['phase']} {svc}: a new healthy container, at the model's hash",
                 rc == 0 and bool(d) and d.get("Id") != (before or {}).get("Id") and health(d) == "healthy"
                 and lab.get("com.docker.compose.config-hash") == (model_hashes() or {}).get(svc), f"exit {rc}, {health(d) or '?'}")
    good &= check(f"{rec['phase']} {svc}: the expected secret and ACCEPTED values", all(env.get(k, "") == v for k, v in expect.items()))
    if svc != GATEWAY:
        port = port_of(m, svc)
        good &= check(f"{rec['phase']} {svc}: accepts {', '.join(n for n, _ in accept)}" + (f"; refuses {', '.join(n for n, _ in refuse)}" if refuse else ""),
                      bool(port) and all(accepted(svc, port, v) for _, v in accept) and all(refused(svc, port, v) for _, v in refuse))
    moved.append(svc)
    rec["moved"], rec["moving"] = list(dict.fromkeys(moved)), None
    save_progress(rec)
    others = changed_since(base, inspect_all(), {f"{PROJECT}/{s}" for s in moved})
    good &= check(f"{rec['phase']} {svc}: every other container unchanged", not others, ", ".join(others))
    return good


def set_env(data, values, st, rec, phase):
    """Write values into the env file; checks: only those keys changed, the preflight passes, every consumer's model
    hash changed and nothing else. On a failure the env file is restored to `data`."""
    hb = model_hashes() or {}
    new = with_values(data, values)
    rec["phase"] = phase
    save_progress(rec)
    write_atomically(ENV_FILE, new, st)
    if FAULT == f"after-env-write:{phase}":
        raise SystemExit(99)
    after = open(ENV_FILE, "rb").read()
    ok = check(f"{phase}: only {', '.join(values)} changed in the env file, mode and owner kept",
               after == new and only_keys_changed(data, after, tuple(values)) and (os.stat(ENV_FILE).st_mode, os.stat(ENV_FILE).st_uid) == (st.st_mode, st.st_uid))
    ok &= check(f"{phase}: the release preflight passes", preflight_ok())
    ha = model_hashes() or {}
    changed = sorted(s for s in set(hb) | set(ha) if hb.get(s) != ha.get(s))
    ok &= check(f"{phase}: exactly the ten app services' model hashes changed", changed == sorted(CONSUMERS), ", ".join(changed))
    if not ok:
        write_atomically(ENV_FILE, data, st)
        say(f"  {phase}: the env file was restored; run `rollback --yes` if any consumer was already recreated")
    return ok, after if ok else data


def cmd_rotate(yes):
    if not yes:
        print("usage: rotate needs --yes (after the procedure's review and gates)", file=sys.stderr)
        return 2
    say(f"gateway secret rotation rotate | {STAMP}")
    rec = {"tool": "gis-rotate", "command": "rotate", "stamp": STAMP, "phase": "checks", "moved": []}
    basics()
    pre = latest("pre")
    age = (time.time() - calendar.timegm(time.strptime(pre["stamp"][:15], "%Y%m%dT%H%M%S"))) if pre else None
    # -60: a clock stepped back a little (NTP, or WSL in the self-test) must not refuse a pre taken moments ago
    check("the latest pre passed within an hour", bool(pre) and pre.get("verdict") == "PASS" and age is not None and -60 <= age <= PRE_MAX_AGE,
          f"{pre['stamp']} {pre.get('verdict')}" if pre else "none")
    data = open(ENV_FILE, "rb").read()
    check("the env file is unchanged since that pre", bool(pre) and hashlib.sha256(data).hexdigest() == pre.get("envSha256"))
    check("the containers are the ones pre recorded", bool(pre) and baseline(inspect_all()) == pre.get("baseline"))
    if not all(c["pass"] for c in CHECKS):
        return finish("rotate", rec)
    old = env_values(data).get(SECRET, "")
    new = secrets.token_urlsafe(48)
    while PLACEHOLDER.search(new):
        new = secrets.token_urlsafe(48)
    SECRETS.extend([old, new])
    st = os.stat(ENV_FILE)
    m = model()
    backup = f"{ENV_FILE}.bak-gis-{STAMP}"
    fd = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, st.st_mode & 0o7777)
    with os.fdopen(fd, "wb") as fh:
        fh.write(data)
        fh.flush()
        os.fsync(fh.fileno())
        os.fchown(fh.fileno(), st.st_uid, st.st_gid)
    rec["backup"] = os.path.basename(backup)
    if not check("backup identical to the original", open(backup, "rb").read() == data):
        return finish("rotate", rec)
    base = baseline(inspect_all())

    say("A. accept the new value everywhere (the secret stays old)")
    ok, data = set_env(data, {ACCEPTED: new}, st, rec, "A")
    if not ok:
        return finish("rotate", rec)
    moved = []
    for svc in DOWNSTREAM:
        if not recreate(svc, base, moved, {SECRET: old, ACCEPTED: new}, [("old", old), ("new", new)], [], m, rec):
            say("  STOP: run `rollback --yes`")
            return finish("rotate", rec)

    say("B. send the new value (the old one stays accepted)")
    ok, data = set_env(data, {SECRET: new, ACCEPTED: old}, st, rec, "B")
    if not ok:
        return finish("rotate", rec)
    for svc in DOWNSTREAM + (GATEWAY,):
        if not recreate(svc, base, moved, {SECRET: new, ACCEPTED: old}, [("old", old), ("new", new)], [], m, rec):
            say("  STOP: run `rollback --yes`")
            return finish("rotate", rec)
    if not check("B: the public API answers 200 through the gateway (it sends the new value)", public_ok()):
        say("  STOP: run `rollback --yes`")
        return finish("rotate", rec)

    say("C. drop the old value")
    ok, data = set_env(data, {ACCEPTED: ""}, st, rec, "C")
    if not ok:
        return finish("rotate", rec)
    for svc in DOWNSTREAM + (GATEWAY,):
        if not recreate(svc, base, moved, {SECRET: new, ACCEPTED: ""}, [("new", new)], [("old", old)], m, rec):
            say("  STOP: see the procedure's recovery section")
            return finish("rotate", rec)
    check("C: the public API answers 200 through the gateway", public_ok())
    ds = inspect_all()
    holders = sorted(name_of(d) for d in ds if old in json.dumps(d.get("Config") or {}))
    check("no container carries the old value anywhere (any project)", not holders, ", ".join(holders))
    hashes = model_hashes() or {}
    off = [s for s in CONSUMERS if (((parkio(s, ds) or {}).get("Config") or {}).get("Labels") or {}).get("com.docker.compose.config-hash") != hashes.get(s)]
    check("every consumer at the model's hash", not off, ", ".join(off))
    rec["phase"] = "done" if all(c["pass"] for c in CHECKS) else "C"
    save_progress(rec)
    say(f"  the env backup {rec['backup']} holds the old, now refused value; `redact --yes` replaces it after acceptance")
    return finish("rotate", rec)


def cmd_rollback(yes):
    if not yes:
        print("usage: rollback needs --yes", file=sys.stderr)
        return 2
    say(f"gateway secret rotation rollback | {STAMP} | brings the EXPOSED secret back")
    rec = {"tool": "gis-rotate", "command": "rollback", "stamp": STAMP}
    path = latest_path("rotate-progress")
    prog = json.load(open(path, encoding="utf-8")) if path else None
    phase = (prog or {}).get("phase")
    if not check("a rotation stopped in phase A, B or C (after the backup)", phase in ("A", "B", "C"), phase or "none"):
        return finish("rollback", rec)
    try:
        old_data = open(os.path.join(os.path.dirname(ENV_FILE), prog.get("backup") or ""), "rb").read() if prog.get("backup") else None
    except OSError:
        old_data = None
    if not check("the rotation's backup exists and is readable", old_data is not None):
        return finish("rollback", rec)
    data = open(ENV_FILE, "rb").read()
    if not check("the backup differs from the env file only in the secret and ACCEPTED lines", only_keys_changed(old_data, data, (SECRET, ACCEPTED))):
        return finish("rollback", rec)
    orig = env_values(old_data)
    SECRETS.extend([orig.get(SECRET, ""), env_values(data).get(SECRET, ""), env_values(data).get(ACCEPTED, "")])
    if data != old_data:
        write_atomically(ENV_FILE, old_data, os.stat(ENV_FILE))
    check("the env file is byte-identical to the backup", open(ENV_FILE, "rb").read() == old_data)
    ds = inspect_all()
    want = {SECRET: orig.get(SECRET, ""), ACCEPTED: orig.get(ACCEPTED, "")}
    todo = [s for s in DOWNSTREAM + (GATEWAY,) if s in (prog.get("moved") or []) or s == prog.get("moving")
            or any(env_of(parkio(s, ds) or {}).get(k, "") != v for k, v in want.items())]
    rec["recreated"] = todo
    for svc in todo:
        before = parkio(svc)
        rc, _, _ = wrapper("up", "-d", "--no-build", "--no-deps", "--force-recreate", svc)
        d = wait_container(svc, (before or {}).get("Id"))
        check(f"{svc}: recreated and healthy", rc == 0 and bool(d) and d.get("Id") != (before or {}).get("Id") and health(d) == "healthy")
    m = model()
    ds = inspect_all()
    for svc in CONSUMERS:
        d = parkio(svc, ds)
        check(f"{svc}: runs the original values, healthy", bool(d) and health(d) == "healthy" and all(env_of(d).get(k, "") == v for k, v in want.items()))
    for svc in DOWNSTREAM:
        port = port_of(m, svc)
        check(f"{svc}: accepts the original value again", bool(port) and accepted(svc, port, want[SECRET]))
    check("the public API answers 200 through the gateway", public_ok())
    if all(c["pass"] for c in CHECKS):
        prog["phase"] = "rolled-back"
        save_progress(prog, path)
        say("  the rotation is marked rolled-back; run pre before another attempt")
    return finish("rollback", rec)


def redact_file(path, old, new):
    try:
        st = os.lstat(path)
    except FileNotFoundError:
        return True, "missing; nothing to redact"
    except OSError:
        return False, "cannot be read"
    if os.path.realpath(path) == os.path.realpath(ENV_FILE):
        return False, "the live env file is never redacted"
    if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid():
        return False, "not a regular file of this user; left alone"
    try:
        data = open(path, "rb").read()
    except OSError as e:
        return False, f"cannot be read ({e.__class__.__name__}); left alone"
    if new.encode() in data:
        return False, "holds the new value; left alone"
    n = data.count(old.encode())
    if n == 0:
        return True, "no occurrence"
    out = data.replace(old.encode(), MARKER.encode())
    try:
        fd, tmp = tempfile.mkstemp(prefix=".gis-redact-", dir=os.path.dirname(path))
    except OSError as e:
        return False, f"cannot be rewritten ({e.__class__.__name__}); left alone"
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(out)
            fh.flush()
            os.fsync(fh.fileno())
            os.fchmod(fh.fileno(), st.st_mode & 0o7777)
            os.fchown(fh.fileno(), st.st_uid, st.st_gid)
        os.utime(tmp, ns=(st.st_atime_ns, st.st_mtime_ns))
        os.replace(tmp, path)
    except OSError as e:
        if os.path.exists(tmp):
            os.unlink(tmp)
        return False, f"cannot be rewritten ({e.__class__.__name__}); left alone"
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise
    after = os.lstat(path)
    ok = open(path, "rb").read() == out and (after.st_mode, after.st_uid, after.st_mtime_ns) == (st.st_mode, st.st_uid, st.st_mtime_ns)
    return ok, (f"{n} occurrence(s) replaced; mode, owner and mtime kept" if ok else "written, but the read-back differs")


def scan_for(value):
    found = []
    for root in SCAN_ROOTS:
        for dp, dns, fns in os.walk(root):
            dns[:] = [d for d in dns if d not in (".git", "node_modules", "journal")]
            for fn in fns:
                p = os.path.join(dp, fn)
                try:
                    if os.path.islink(p) or not os.path.isfile(p) or os.path.getsize(p) > 200_000_000:
                        continue
                    with open(p, "rb") as fh:
                        if value.encode() in fh.read():
                            found.append(p)
                except OSError:
                    continue
    return found


def cmd_redact(yes):
    if not yes:
        print("usage: redact needs --yes (after a passing rotation)", file=sys.stderr)
        return 2
    say(f"gateway secret rotation redact | {STAMP}")
    rec = {"tool": "gis-rotate", "command": "redact", "stamp": STAMP}
    rot, prog = latest("rotate"), latest("rotate-progress")
    if not check("the latest rotation passed (phase done)", bool(rot) and rot.get("verdict") == "PASS" and (prog or {}).get("phase") == "done"
                 and rot.get("stamp") == (prog or {}).get("stamp")):
        return finish("redact", rec)
    backup = os.path.join(os.path.dirname(ENV_FILE), rot.get("backup") or "")
    old = env_values(open(backup, "rb").read()).get(SECRET, "") if os.path.isfile(backup) else ""
    if old == MARKER:
        check("redaction already complete: the rotation's backup no longer holds the old value", True)
        return finish("redact", rec)
    new = env_values(open(ENV_FILE, "rb").read()).get(SECRET, "")
    SECRETS.extend([old, new])
    m = model()
    if not check("the old value is known (the rotation's backup) and every downstream service refuses it",
                 len(old) >= 32 and old != new and all(refused(s, port_of(m, s), old) for s in DOWNSTREAM)):
        return finish("redact", rec)
    rec["files"] = {}
    paths = sorted(p for p in glob.glob(REDACT_GLOB) if os.path.realpath(p) != os.path.realpath(backup)) + REDACT_EXTRA
    for path in paths:
        ok, detail = redact_file(path, old, new)
        rec["files"][path] = detail
        check(f"{path}: redacted", ok, detail)
    left = [p for p in scan_for(old) if os.path.realpath(p) != os.path.realpath(backup)]
    check(f"apart from the rotation's backup, no readable file under {', '.join(SCAN_ROOTS)} holds the old value", not left, ", ".join(left))
    if not all(c["pass"] for c in CHECKS):
        say("  the rotation's backup keeps the old value, so this can run again once the failures above are dealt with")
        return finish("redact", rec)
    ok, detail = redact_file(backup, old, new)
    rec["files"][backup] = detail
    check(f"{backup}: redacted last", ok, detail)
    return finish("redact", rec)


def cmd_status():
    try:
        for d in sorted(os.listdir(OUT))[-8:]:
            for f in sorted(os.listdir(os.path.join(OUT, d))):
                if f.endswith(".json"):
                    r = json.load(open(os.path.join(OUT, d, f), encoding="utf-8"))
                    say(f"{d} {f[:-5]}: {r.get('verdict') or r.get('phase')}")
    except (OSError, ValueError):
        say("no records")
    return 0


def main(argv):
    if not argv or argv[0] not in ("pre", "rotate", "rollback", "redact", "status"):
        print(__doc__, file=sys.stderr)
        return 2
    cmd, rest = argv[0], argv[1:]
    yes = rest == ["--yes"]
    if rest and not yes or (yes and cmd in ("pre", "status")):
        print("usage: unknown or misplaced argument", file=sys.stderr)
        return 2
    if cmd == "pre":
        return cmd_pre()
    if cmd == "status":
        return cmd_status()
    if cmd == "rotate":
        return cmd_rotate(yes)
    if cmd == "redact":
        return cmd_redact(yes)
    return cmd_rollback(yes)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
