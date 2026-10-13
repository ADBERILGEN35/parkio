#!/usr/bin/env python3
"""U06 heartbeat, part B2: recreate only Alertmanager so that the heartbeat URL written by u06-heartbeat-env.py takes
effect, and roll it back. Run on parkio-civo-prod as civo (Python 3.8+). Prepared 2026-10-13, revised after the
independent review (U06-TOOLS-REVIEW.md, M1, M2, L2, L6, L7); running it needs the owner's authorization for the
activation window.

  python3 -I u06-alertmanager-step.py pre
  python3 -I u06-alertmanager-step.py recreate --yes
  python3 -I u06-alertmanager-step.py rollback --backup <env backup FILE NAME, not a path> --yes
  python3 -I u06-alertmanager-step.py status

pre (read-only):
  - this host; docker usable; no synthetic BackupStale textfile armed;
  - the release checkout is clean, at the PIN f888a81b or at R1 (the PIN plus only a web pin that pins the web image
    alone);
  - the env file holds an https PARKIO_ALERT_HEARTBEAT_URL (classified only, never printed);
  - the generic operator webhook is empty, so integration="webhook" counts only the heartbeat;
  - the newest env backup made by u06-heartbeat-env.py differs from the env file only in that key and its comment line,
    with another URL value. A backup without a URL is a first activation; a backup with one is a rotation;
  - exactly one running, healthy alertmanager:
    - it runs the image the model names: the local id of that tag equals the container's image;
    - with the backup env, the wrapper's model hash equals the running container's hash, so the heartbeat URL is the
      only pending change;
    - with the current env, it differs;
  - Watchdog routes to `null` in the running Alertmanager for a first activation, or to `heartbeat` for a rotation;
  - a baseline of every parkio container (id, image, config hash) and the env file's SHA-256 (private record only).
recreate (--yes), the only live change:
  - requires the latest pre, passing, within 2 hours, with the same env file and the same Alertmanager container;
  - runs exactly what T1 ran at D2a (no --force-recreate; the wrapper adds the Civo Alertmanager overlay):
      cd /opt/parkio-release && PARKIO_ENV_FILE=/opt/parkio/docker/.env.azure-hosted-beta \
        ./scripts/parkio-prod-compose.sh up -d --no-build --no-deps alertmanager
  - post-checks:
    - a new alertmanager is running and healthy, on the image pre recorded;
    - its config hash equals the wrapper's model hash;
    - every other container is unchanged against the baseline;
    - Watchdog routes to `heartbeat`;
    - within HEARTBEAT_WAIT seconds at least two webhook notifications are sent (first delivery and one repeat);
    - no webhook notification failed. The counters restart with the process.
rollback (--backup NAME --yes). Everything below is checked BEFORE the env file is written:
  - the named backup is a regular file beside the env file and differs from it only in the heartbeat key and comment;
  - it is the backup the latest passing pre recorded, or it holds no heartbeat URL (a pre-activation file);
  - with the backup env, the wrapper's model differs from the running Alertmanager (there is something to roll back);
  - for a pre-activation backup after a first activation, it equals the Alertmanager hash that pre recorded;
  - a fresh baseline of the other containers is taken now; a synthetic BackupStale textfile is only a warning here.
  Then the backup is written over the env file atomically (temp file in the same directory, fsync, original mode and
  owner, rename) and the same wrapper command runs. Post-checks:
  - a new, healthy container on the same image;
  - its hash equals the backup's model hash;
  - Watchdog routes to `null` (backup without a URL) or `heartbeat` (backup with a URL);
  - the other containers are unchanged against the fresh baseline.
Every external command gets an allowlisted environment (PATH, HOME, LANG, USER, LOGNAME; PARKIO_ENV_FILE for the
wrapper). URLs are never printed, the rendered model is parsed in memory only (the image reference is the only field
kept), and the Alertmanager log is not read. Records go to ~/parkio-release-20261009/u06/am-<stamp>/ (mode 700, files
600). Exit 0 = PASS, 1 = FAIL, 2 = usage.

Before a rollback, or any stop of Alertmanager, Prometheus or the host longer than about 8 minutes, pause the check at
the heartbeat provider: once armed, it pages when the pings stop (period + grace).
"""
import calendar
import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import time

TEST = os.environ.get("U06A_TEST") == "1"


def cfg(name, default):
    """Overrides are honoured only by the self-test."""
    return os.environ.get(name, default) if TEST else default


PIN = cfg("U06A_PIN", "f888a81b113c0c486ea0949067779443857e644d")
HOST = cfg("U06A_HOST", "parkio-civo-prod")
REL = cfg("U06A_RELEASE", "/opt/parkio-release")
ENV_FILE = cfg("U06A_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
TEXTFILE_DIR = cfg("U06A_TEXTFILE_DIR", "/opt/parkio/docker/prometheus/textfile")
OUT = cfg("U06A_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009", "u06"))
HEARTBEAT_WAIT = int(cfg("U06A_HEARTBEAT_WAIT", "360"))
POLL = float(cfg("U06A_POLL", "15"))
HEALTH_WAIT = int(cfg("U06A_HEALTH_WAIT", "120"))
PROJECT = "parkio"
SERVICE = "alertmanager"
AM_URL = "http://localhost:9093"
KEY = "PARKIO_ALERT_HEARTBEAT_URL"
GENERIC = "PARKIO_ALERT_WEBHOOK_URL"
COMMENT = b"# External heartbeat / dead-man's switch (U06, owner-authorized activation)"   # = u06-heartbeat-env.py
SYNTHETIC = "parkio_backup_synthetic_stale.prom"
WATCHDOG = ["alertname=Watchdog", "severity=heartbeat", "service=observability"]          # docker/prometheus/alerts.yml
WEB_PIN_FILE = "docker/docker-compose.web-release-pin.yml"
WEB_PIN_LINES = (r"name: parkio", r"services:", r"  web:", r"    image: ghcr\.io/adberilgen35/parkio/web@sha256:[0-9a-f]{64}")
PRE_MAX_AGE = 2 * 3600
STAMP = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
CHECKS = []
URL = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://[^\s'\"]+")


def say(line=""):
    print(URL.sub("<url>", line), flush=True)


def check(what, ok, detail=""):
    CHECKS.append({"check": what, "pass": bool(ok), "detail": URL.sub("<url>", str(detail))})
    say(("  PASS " if ok else "  FAIL ") + what + (f" ({detail})" if detail else ""))
    return bool(ok)


def base_env():
    env = {k: os.environ[k] for k in ("PATH", "HOME", "LANG", "USER", "LOGNAME") if k in os.environ}
    if TEST and "U06A_W" in os.environ:
        env["U06A_W"] = os.environ["U06A_W"]
    return env


def run(cmd, cwd=None, env=None, timeout=180):
    try:
        p = subprocess.run(cmd, cwd=cwd, env=env if env is not None else base_env(), capture_output=True, text=True,
                           timeout=timeout, stdin=subprocess.DEVNULL)
    except FileNotFoundError:
        return 127, "", "not found"
    except subprocess.TimeoutExpired:
        return 124, "", "timed out"
    return p.returncode, p.stdout, p.stderr


def git(*args):
    return run(["git", "--no-optional-locks", "-c", f"safe.directory={REL}", "-C", REL] + list(args))


def wrapper(*args, env_file=ENV_FILE, timeout=900):
    env = base_env()
    env["PARKIO_ENV_FILE"] = env_file
    return run(["./scripts/parkio-prod-compose.sh"] + list(args), cwd=REL, env=env, timeout=timeout)


def record_dir():
    d = os.path.join(OUT, f"am-{STAMP}")
    os.makedirs(d, mode=0o700, exist_ok=True)
    return d


def private_write(path, text):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(text)


def finish(name, rec):
    rec["checks"] = CHECKS
    rec["verdict"] = "PASS" if CHECKS and all(c["pass"] for c in CHECKS) else "FAIL"
    path = os.path.join(record_dir(), f"{name}.json")
    private_write(path, json.dumps(rec, indent=1, sort_keys=True) + "\n")
    say(f"U06 alertmanager {name}: {rec['verdict']} | record {path}")
    return 0 if rec["verdict"] == "PASS" else 1


def containers():
    rc, out, _ = run(["docker", "ps", "-a", "--no-trunc", "--filter", f"label=com.docker.compose.project={PROJECT}", "--format", "{{.ID}}"])
    if rc != 0:
        return None
    ids = out.split()
    if not ids:
        return {}
    rc, info, _ = run(["docker", "inspect"] + ids)
    if rc != 0:
        return None
    result = {}
    for d in json.loads(info):
        labels = (d.get("Config") or {}).get("Labels") or {}
        state = d.get("State") or {}
        svc = labels.get("com.docker.compose.service") or d.get("Name", "").lstrip("/")
        result.setdefault(svc, []).append({"id": d.get("Id"), "image": d.get("Image"), "status": state.get("Status"),
                                           "health": (state.get("Health") or {}).get("Status"),
                                           "hash": labels.get("com.docker.compose.config-hash"), "name": d.get("Name", "").lstrip("/")})
    return result


def one(cs, svc):
    entries = (cs or {}).get(svc) or []
    return entries[0] if len(entries) == 1 else None


def baseline_of(cs):
    return {svc: sorted(json.dumps({k: e[k] for k in ("id", "image", "hash")}, sort_keys=True) for e in entries)
            for svc, entries in (cs or {}).items()}


def model_hash(env_file=ENV_FILE):
    rc, out, _ = wrapper("config", "--hash", SERVICE, env_file=env_file, timeout=180)
    for line in out.splitlines():
        parts = line.split()
        if rc == 0 and len(parts) == 2 and parts[0] == SERVICE:
            return parts[1]
    return None


def model_image():
    """(image reference the model gives alertmanager, local image id of that reference). The rendered model carries env
    values (secrets), so it is parsed in memory and only the image field is kept."""
    rc, out, _ = wrapper("config", "--format", "json", timeout=180)
    try:
        ref = json.loads(out)["services"][SERVICE].get("image") if rc == 0 else None
    except (ValueError, KeyError, TypeError):
        ref = None
    if not ref:
        return None, None
    rc, iid, _ = run(["docker", "image", "inspect", "--format", "{{.Id}}", ref])
    return ref, (iid.strip() if rc == 0 and iid.strip() else None)


def key_of(line):
    m = re.match(rb"^([A-Za-z_][A-Za-z0-9_]*)=", line)
    return m.group(1).decode("ascii") if m else None


def env_value(data, key):
    value = None
    for line in data.splitlines():
        if key_of(line) == key:
            value = line.split(b"=", 1)[1].decode("utf-8", "replace").strip().strip('"').strip("'")
    return value


def classify(value):
    if value is None:
        return "absent"
    return "empty" if not value else ("set (https)" if value.startswith("https://") else "set (not https)")


def without_heartbeat(data):
    return [l.rstrip(b"\r\n") for l in data.splitlines(keepends=True) if key_of(l) != KEY and l.rstrip(b"\r\n") != COMMENT]


def read_bytes(path):
    try:
        with open(path, "rb") as fh:
            return fh.read()
    except OSError:
        return None


def backups():
    d, base = os.path.dirname(ENV_FILE), os.path.basename(ENV_FILE) + ".bak-"
    try:
        return sorted(f for f in os.listdir(d) if f.startswith(base) and re.fullmatch(r"\d{8}T\d{6}Z", f[len(base):]))
    except OSError:
        return []


def write_atomically(data, st):
    """The env file holds secrets: write a temp file in its directory (0600 from mkstemp), fsync, give it the original
    mode and owner, rename it over the file, fsync the directory. Never a world-readable copy, never a fixed name."""
    directory = os.path.dirname(ENV_FILE)
    fd, tmp = tempfile.mkstemp(prefix=".u06a-", dir=directory)
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
            fh.flush()
            os.fsync(fh.fileno())
            os.fchmod(fh.fileno(), st.st_mode & 0o7777)
            os.fchown(fh.fileno(), st.st_uid, st.st_gid)
        os.replace(tmp, ENV_FILE)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise
    dfd = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(dfd)
    finally:
        os.close(dfd)


def watchdog_receiver(name):
    rc, out, _ = run(["docker", "exec", name, "amtool", "config", "routes", "test", f"--alertmanager.url={AM_URL}"] + WATCHDOG,
                     timeout=60)
    lines = [l.strip() for l in out.splitlines() if l.strip()]
    return lines[-1] if rc == 0 and lines else None


def webhook_counters(name):
    """Sum of every integration="webhook" series of the two counters; None when a counter has no series."""
    rc, out, _ = run(["docker", "exec", name, "wget", "-qO-", "http://127.0.0.1:9093/metrics"], timeout=60)
    if rc != 0:
        return None, None
    sums = {"": None, "_failed": None}
    for line in out.splitlines():
        m = re.match(r'^alertmanager_notifications(_failed)?_total\{([^}]*)\}\s+(\S+)$', line)
        if m and re.search(r'(^|,)integration="webhook"(,|$)', m.group(2)):
            sums[m.group(1) or ""] = (sums[m.group(1) or ""] or 0.0) + float(m.group(3))
    return sums[""], sums["_failed"]


def release_ok():
    rc, head, _ = git("rev-parse", "HEAD")
    rc2, status, _ = git("status", "--porcelain")
    head = head.strip()
    if rc != 0 or rc2 != 0 or status.strip():
        return False, f"not clean ({head[:12] or '?'})"
    if head == PIN:
        return True, f"at the PIN {PIN[:12]}"
    rc3, _, _ = git("merge-base", "--is-ancestor", PIN, head)
    rc4, names, _ = git("diff", "--no-renames", "-z", "--name-only", PIN, head)
    if rc3 == 0 and rc4 == 0 and [n for n in names.split("\0") if n] == [WEB_PIN_FILE]:
        rc5, text, _ = git("show", f"{head}:{WEB_PIN_FILE}")
        lines = [l for l in text.splitlines() if l.strip() and not l.lstrip().startswith("#")]
        if rc5 == 0 and len(lines) == 4 and all(re.fullmatch(p, l) for p, l in zip(WEB_PIN_LINES, lines)):
            return True, f"{head[:12]}: the PIN plus only the web pin"
    return False, f"at {head[:12]}, neither the PIN nor the PIN plus only the web pin"


def basics(rollback=False):
    check(f"host {HOST}", socket.gethostname() == HOST, socket.gethostname())
    rc, out, _ = run(["docker", "info", "--format", "{{.ServerVersion}}"])
    check("docker usable without sudo", rc == 0, out.strip()[:20])
    armed = os.path.exists(os.path.join(TEXTFILE_DIR, SYNTHETIC))
    if rollback:
        if armed:
            say("  NOTE a synthetic BackupStale textfile is armed: the BackupStale test's counters will include this restart")
    else:
        check("no synthetic BackupStale textfile armed", not armed, "a BackupStale test is running: finish it first" if armed else "")
    ok, detail = release_ok()
    check("the release checkout is clean at the PIN or R1", ok, detail)


def latest_pre(passing_only=False):
    best = None
    try:
        for d in sorted(os.listdir(OUT)):
            p = os.path.join(OUT, d, "pre.json")
            if d.startswith("am-") and os.path.isfile(p):
                r = json.load(open(p, encoding="utf-8"))
                if not passing_only or r.get("verdict") == "PASS":
                    best = r
    except (OSError, ValueError):
        return None
    return best


def cmd_pre():
    say(f"U06 alertmanager pre | {STAMP} | read-only")
    rec = {"tool": "u06-alertmanager-step", "command": "pre", "stamp": STAMP}
    basics()
    current = read_bytes(ENV_FILE)
    state = classify(env_value(current, KEY)) if current is not None else "unreadable"
    check("the env file holds an https heartbeat URL (value not shown)", state == "set (https)", state)
    generic = classify(env_value(current or b"", GENERIC))
    check("the generic operator webhook is empty (webhook counters then count only the heartbeat)", generic in ("absent", "empty"), generic)
    names = backups()
    rec["backup"] = names[-1] if names else None
    backup_path = os.path.join(os.path.dirname(ENV_FILE), names[-1]) if names else None
    backup = read_bytes(backup_path) if backup_path else None
    rotation = bool(backup is not None and env_value(backup, KEY))
    rec["rotation"] = rotation
    check("the newest env backup differs from the env file only in the heartbeat key, with another URL value",
          backup is not None and current is not None and without_heartbeat(backup) == without_heartbeat(current) and
          env_value(backup, KEY) != env_value(current, KEY),
          f"{rec['backup'] or 'no backup'}; {'rotation (the backup already holds a URL)' if rotation else 'first activation'}")
    cs = containers()
    am = one(cs, SERVICE)
    check(f"exactly one {SERVICE}, running and healthy", bool(am) and am["status"] == "running" and am["health"] == "healthy",
          f"{(am or {}).get('status') or '?'}/{(am or {}).get('health') or '?'}")
    ref, iid = model_image()
    check(f"{SERVICE} runs the image the model names", bool(am) and iid is not None and am["image"] == iid,
          f"{ref or '?'} -> {(iid or '?')[:19]}, container {((am or {}).get('image') or '?')[:19]}")
    before = model_hash(backup_path) if backup_path else None
    after = model_hash()
    running = (am or {}).get("hash")
    check("with the backup env the model equals the running Alertmanager (the heartbeat is the only pending change)",
          bool(running) and before == running, f"backup model {(before or '?')[:12]}, running {(running or '?')[:12]}")
    check("with the current env the model differs (the recreate applies the heartbeat)", after is not None and after != running,
          f"current model {(after or '?')[:12]}")
    receiver = watchdog_receiver(am["name"]) if am else None
    want = "heartbeat" if rotation else "null"
    check(f"Watchdog routes to {want} in the running Alertmanager", receiver == want, receiver or "?")
    rec["baseline"] = baseline_of(cs)
    rec["alertmanager"] = {"id": (am or {}).get("id"), "hash": running, "image": (am or {}).get("image")}
    rec["envSha256"] = hashlib.sha256(current).hexdigest() if current is not None else None
    if names:
        say(f"  rollback, if needed later: python3 -I u06-alertmanager-step.py rollback --backup {names[-1]} --yes")
    return finish("pre", rec)


def wait_new(before_id):
    deadline, am = time.time() + HEALTH_WAIT, None
    while time.time() < deadline:
        am = one(containers(), SERVICE)
        if am and am["id"] != before_id and am["status"] == "running" and am["health"] == "healthy":
            break
        time.sleep(min(3.0, POLL))
    check(f"a new {SERVICE} is running and healthy", bool(am) and am["id"] != before_id and am["status"] == "running" and
          am["health"] == "healthy", f"{((am or {}).get('id') or '?')[:12]} {(am or {}).get('status')}/{(am or {}).get('health')}")
    return am


def others_unchanged(baseline, label="the baseline"):
    now = baseline_of(containers())
    changed = sorted(s for s in set(baseline) | set(now) if s != SERVICE and baseline.get(s) != now.get(s))
    return check(f"every other container unchanged against {label}", not changed, ", ".join(changed))


def heartbeat_deliveries(rec, name):
    start, samples = time.time(), []
    while True:
        total, failed = webhook_counters(name)
        samples.append({"t": round(time.time() - start, 1), "total": total, "failed": failed})
        if total is not None and failed is not None and total - failed >= 2:
            break
        if time.time() - start >= HEARTBEAT_WAIT:
            break
        time.sleep(POLL)
    rec["webhookCounters"] = samples
    last = samples[-1]
    sent = None if last["total"] is None or last["failed"] is None else last["total"] - last["failed"]
    check(f"heartbeat delivered at least twice (first send and one repeat) within {HEARTBEAT_WAIT} s",
          sent is not None and sent >= 2, f"successful webhook notifications {sent}, after {last['t']} s")
    check("no webhook notification failed", last["failed"] == 0, f"failed {last['failed']}")


def cmd_recreate(yes):
    if not yes:
        print("usage: recreate needs --yes (the owner's authorization for the activation window)", file=sys.stderr)
        return 2
    say(f"U06 alertmanager recreate | {STAMP} | the only live change: {SERVICE}")
    rec = {"tool": "u06-alertmanager-step", "command": "recreate", "stamp": STAMP}
    basics()
    pre = latest_pre()
    age = (time.time() - calendar.timegm(time.strptime(pre["stamp"], "%Y%m%dT%H%M%SZ"))) if pre else None
    check("the latest pre passed within 2 hours", bool(pre) and pre.get("verdict") == "PASS" and age is not None and 0 <= age <= PRE_MAX_AGE,
          f"{pre['stamp']} {pre.get('verdict')}" if pre else "none")
    current = read_bytes(ENV_FILE)
    check("the env file is unchanged since that pre", bool(pre) and current is not None and
          hashlib.sha256(current).hexdigest() == pre.get("envSha256"))
    am = one(containers(), SERVICE)
    check(f"{SERVICE} is still the pre's container", bool(am) and bool(pre) and am["id"] == (pre.get("alertmanager") or {}).get("id"))
    if not all(c["pass"] for c in CHECKS):
        return finish("recreate", rec)
    say(f"  run: cd {REL} && PARKIO_ENV_FILE={ENV_FILE} ./scripts/parkio-prod-compose.sh up -d --no-build --no-deps {SERVICE}")
    rc, out, err = wrapper("up", "-d", "--no-build", "--no-deps", SERVICE)
    private_write(os.path.join(record_dir(), "wrapper-recreate.log"), URL.sub("<url>", out + err))
    if check("wrapper exit 0", rc == 0, f"exit {rc}"):
        new = wait_new(am["id"])
        check(f"{SERVICE} runs the image pre recorded", bool(new) and new["image"] == (pre.get("alertmanager") or {}).get("image"),
              ((new or {}).get("image") or "?")[:19])
        mh = model_hash()
        check(f"{SERVICE} config hash = the wrapper's model hash", bool(new) and mh is not None and mh == new["hash"],
              f"model {(mh or '?')[:12]}, container {((new or {}).get('hash') or '?')[:12]}")
        others_unchanged(pre["baseline"])
        receiver = watchdog_receiver(new["name"]) if new else None
        check("Watchdog routes to heartbeat", receiver == "heartbeat", receiver or "?")
        if new:
            heartbeat_deliveries(rec, new["name"])
        rec["alertmanager"] = {"previous": am["id"], "now": (new or {}).get("id"), "hash": (new or {}).get("hash")}
    say(f"  rollback, if needed: pause the provider check first, then: python3 -I u06-alertmanager-step.py rollback "
        f"--backup {pre.get('backup')} --yes")
    return finish("recreate", rec)


def cmd_rollback(backup, yes):
    base = os.path.basename(ENV_FILE) + ".bak-"
    if not yes or not backup or not backup.startswith(base) or not re.fullmatch(r"\d{8}T\d{6}Z", backup[len(base):]):
        print(f"usage: rollback --backup {base}<YYYYMMDDTHHMMSSZ> --yes   (the file name only, as pre and recreate print it)",
              file=sys.stderr)
        return 2
    say(f"U06 alertmanager rollback | {STAMP} | env backup {backup}, then {SERVICE} (nothing is written until every check passes)")
    rec = {"tool": "u06-alertmanager-step", "command": "rollback", "stamp": STAMP, "backup": backup}
    basics(rollback=True)
    path = os.path.join(os.path.dirname(ENV_FILE), backup)
    restored = read_bytes(path) if os.path.isfile(path) and not os.path.islink(path) else None
    current = read_bytes(ENV_FILE)
    check("the backup is a regular file and differs from the env file only in the heartbeat key",
          restored is not None and current is not None and without_heartbeat(restored) == without_heartbeat(current))
    restored_url = env_value(restored, KEY) if restored is not None else None
    pre = latest_pre(passing_only=True)
    check("the backup is the one the latest passing pre recorded, or holds no heartbeat URL (a pre-activation file)",
          restored is not None and ((bool(pre) and backup == pre.get("backup")) or not restored_url),
          f"pre recorded {(pre or {}).get('backup') or 'none'}; backup URL {classify(restored_url)}")
    cs = containers()
    am = one(cs, SERVICE)
    check(f"one {SERVICE}, running", bool(am) and am["status"] == "running")
    target = model_hash(path) if restored is not None else None
    check("with the backup env the model differs from the running Alertmanager (there is something to roll back)",
          target is not None and bool(am) and target != am["hash"], f"backup model {(target or '?')[:12]}, running {((am or {}).get('hash') or '?')[:12]}")
    if restored is not None and not restored_url and pre and not pre.get("rotation") and backup == pre.get("backup"):
        want_hash = (pre.get("alertmanager") or {}).get("hash")
        check("with the backup env the model equals the Alertmanager hash pre recorded before the activation",
              bool(want_hash) and target == want_hash, f"{(target or '?')[:12]} vs {(want_hash or '?')[:12]}")
    fresh = baseline_of(cs)
    want_receiver = "heartbeat" if restored_url else "null"
    if not all(c["pass"] for c in CHECKS):
        say("  nothing was written and nothing was recreated")
        return finish("rollback", rec)
    st = os.stat(ENV_FILE)
    write_atomically(restored, st)
    nst = os.stat(ENV_FILE)
    if not check("the env file equals the backup byte for byte, with the same mode, owner and group",
                 read_bytes(ENV_FILE) == restored and (nst.st_mode, nst.st_uid, nst.st_gid) == (st.st_mode, st.st_uid, st.st_gid)):
        return finish("rollback", rec)
    rc, out, err = wrapper("up", "-d", "--no-build", "--no-deps", SERVICE)
    private_write(os.path.join(record_dir(), "wrapper-rollback.log"), URL.sub("<url>", out + err))
    if check("wrapper exit 0", rc == 0, f"exit {rc}"):
        new = wait_new(am["id"])
        check(f"{SERVICE} runs the same image as before", bool(new) and new["image"] == am["image"], ((new or {}).get("image") or "?")[:19])
        check(f"{SERVICE} config hash = the backup's model hash", bool(new) and new["hash"] == target,
              f"{((new or {}).get('hash') or '?')[:12]} vs {(target or '?')[:12]}")
        others_unchanged(fresh, "the rollback's fresh baseline")
        receiver = watchdog_receiver(new["name"]) if new else None
        check(f"Watchdog routes to {want_receiver}", receiver == want_receiver, receiver or "?")
    return finish("rollback", rec)


def cmd_status():
    try:
        for d in sorted(os.listdir(OUT))[-8:]:
            for f in sorted(os.listdir(os.path.join(OUT, d))):
                if f.endswith(".json"):
                    r = json.load(open(os.path.join(OUT, d, f), encoding="utf-8"))
                    say(f"{d} {r.get('command')}: {r.get('verdict')}")
    except (OSError, ValueError):
        say("no records")
    return 0


def main(argv):
    if not argv or argv[0] not in ("pre", "recreate", "rollback", "status"):
        print(__doc__, file=sys.stderr)
        return 2
    cmd, rest = argv[0], argv[1:]
    backup = None
    if "--backup" in rest:
        i = rest.index("--backup")
        backup = rest[i + 1] if i + 1 < len(rest) else None
        rest = rest[:i] + rest[i + 2:]
    yes = rest.count("--yes") == 1
    if [a for a in rest if a != "--yes"] or rest.count("--yes") > 1 or (backup and cmd != "rollback") or (yes and cmd in ("pre", "status")):
        print("usage: unknown or misplaced argument", file=sys.stderr)
        return 2
    if cmd == "status":
        return cmd_status()
    if cmd == "pre":
        return cmd_pre()
    if cmd == "recreate":
        return cmd_recreate(yes)
    return cmd_rollback(backup, yes)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
