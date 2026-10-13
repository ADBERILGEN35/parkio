#!/usr/bin/env python3
"""U06 heartbeat, part B2: recreate only Alertmanager so that the heartbeat URL written by u06-heartbeat-env.py takes
effect, and roll it back. Run on parkio-civo-prod as civo (Python 3.8+). Prepared 2026-10-13; running it needs the owner's
authorization for the activation window.

  python3 -I u06-alertmanager-step.py pre
  python3 -I u06-alertmanager-step.py recreate --yes
  python3 -I u06-alertmanager-step.py rollback --backup <env backup file name> --yes
  python3 -I u06-alertmanager-step.py status

pre (read-only):
  - this host; docker usable; no synthetic BackupStale textfile armed;
  - the release checkout is clean, at the PIN f888a81b or at R1 (the PIN plus only a web pin that pins the web image
    alone);
  - the env file holds an https PARKIO_ALERT_HEARTBEAT_URL (classified only, never printed);
  - the generic operator webhook is empty, so integration="webhook" counts only the heartbeat;
  - the newest env backup made by u06-heartbeat-env.py differs from the env file only in that key and its comment line;
  - exactly one running, healthy alertmanager:
    - with the backup env, the wrapper's model hash equals the running container's hash, so the heartbeat URL is the
      only pending change;
    - with the current env, it differs;
  - Watchdog routes to `null` in the running Alertmanager;
  - a baseline of every parkio container (id, image, config hash) and the env file's SHA-256 (private record only).
recreate (--yes), the only live change:
  - requires the latest pre, passing, within 2 hours, with the same env file and the same Alertmanager container;
  - runs exactly what T1 ran at D2a (no --force-recreate; the wrapper adds the Civo Alertmanager overlay):
      cd /opt/parkio-release && PARKIO_ENV_FILE=/opt/parkio/docker/.env.azure-hosted-beta \
        ./scripts/parkio-prod-compose.sh up -d --no-build --no-deps alertmanager
  - post-checks:
    - a new alertmanager is running and healthy, and its config hash equals the wrapper's model hash;
    - every other container is unchanged against the baseline;
    - Watchdog routes to `heartbeat`;
    - within HEARTBEAT_WAIT seconds at least two webhook notifications are sent (first delivery and one repeat);
    - no webhook notification failed. The counters restart with the process.
rollback (--backup NAME --yes):
  - the named backup must differ from the env file only in the heartbeat key and its comment line;
  - it is copied over the env file, byte for byte, with the same mode and owner; then the same wrapper command runs;
  - post-checks:
    - a new, healthy container whose hash equals the hash recorded in the passing pre's baseline;
    - Watchdog back to `null`;
    - the other containers unchanged.
Every external command gets an allowlisted environment (PATH, HOME, LANG, USER, LOGNAME; PARKIO_ENV_FILE for the
wrapper). URLs are never printed and the Alertmanager log is not read. Records go to
~/parkio-release-20261009/u06/am-<stamp>/ (mode 700, files 600). Exit 0 = PASS, 1 = FAIL, 2 = usage.
"""
import calendar
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
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


def basics():
    check(f"host {HOST}", socket.gethostname() == HOST, socket.gethostname())
    rc, out, _ = run(["docker", "info", "--format", "{{.ServerVersion}}"])
    check("docker usable without sudo", rc == 0, out.strip()[:20])
    armed = os.path.exists(os.path.join(TEXTFILE_DIR, SYNTHETIC))
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
    check("the newest env backup differs from the env file only in the heartbeat key",
          backup is not None and current is not None and without_heartbeat(backup) == without_heartbeat(current) and
          env_value(backup, KEY) != env_value(current, KEY), rec["backup"] or "no backup")
    cs = containers()
    am = one(cs, SERVICE)
    check(f"exactly one {SERVICE}, running and healthy", bool(am) and am["status"] == "running" and am["health"] == "healthy",
          f"{(am or {}).get('status') or '?'}/{(am or {}).get('health') or '?'}")
    before = model_hash(backup_path) if backup_path else None
    after = model_hash()
    running = (am or {}).get("hash")
    check("with the backup env the model equals the running Alertmanager (the heartbeat is the only pending change)",
          bool(running) and before == running, f"backup model {(before or '?')[:12]}, running {(running or '?')[:12]}")
    check("with the current env the model differs (the recreate applies the heartbeat)", after is not None and after != running,
          f"current model {(after or '?')[:12]}")
    receiver = watchdog_receiver(am["name"]) if am else None
    check("Watchdog routes to null in the running Alertmanager", receiver == "null", receiver or "?")
    rec["baseline"] = baseline_of(cs)
    rec["alertmanager"] = {"id": (am or {}).get("id"), "hash": running}
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


def others_unchanged(baseline):
    now = baseline_of(containers())
    changed = sorted(s for s in set(baseline) | set(now) if s != SERVICE and baseline.get(s) != now.get(s))
    return check("every other container unchanged against the baseline", not changed, ", ".join(changed))


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
        mh = model_hash()
        check(f"{SERVICE} config hash = the wrapper's model hash", bool(new) and mh is not None and mh == new["hash"],
              f"model {(mh or '?')[:12]}, container {((new or {}).get('hash') or '?')[:12]}")
        others_unchanged(pre["baseline"])
        receiver = watchdog_receiver(new["name"]) if new else None
        check("Watchdog routes to heartbeat", receiver == "heartbeat", receiver or "?")
        if new:
            heartbeat_deliveries(rec, new["name"])
        rec["alertmanager"] = {"previous": am["id"], "now": (new or {}).get("id"), "hash": (new or {}).get("hash")}
    say(f"  rollback, if needed: python3 -I u06-alertmanager-step.py rollback --backup {pre.get('backup')} --yes")
    return finish("recreate", rec)


def cmd_rollback(backup, yes):
    base = os.path.basename(ENV_FILE) + ".bak-"
    if not yes or not backup or not backup.startswith(base) or not re.fullmatch(r"\d{8}T\d{6}Z", backup[len(base):]):
        print(f"usage: rollback --backup {base}<YYYYMMDDTHHMMSSZ> --yes", file=sys.stderr)
        return 2
    say(f"U06 alertmanager rollback | {STAMP} | env backup {backup}, then {SERVICE}")
    rec = {"tool": "u06-alertmanager-step", "command": "rollback", "stamp": STAMP, "backup": backup}
    basics()
    path = os.path.join(os.path.dirname(ENV_FILE), backup)
    restored, current = read_bytes(path), read_bytes(ENV_FILE)
    check("the backup exists and differs from the env file only in the heartbeat key",
          not os.path.islink(path) and restored is not None and current is not None and
          without_heartbeat(restored) == without_heartbeat(current))
    pre = latest_pre(passing_only=True)
    check("a passing pre with a baseline exists", bool(pre) and bool(pre.get("baseline")))
    am = one(containers(), SERVICE)
    check(f"one {SERVICE}", bool(am))
    if not all(c["pass"] for c in CHECKS):
        return finish("rollback", rec)
    st = os.stat(ENV_FILE)
    tmp = ENV_FILE + f".u06-rollback-{STAMP}"
    shutil.copyfile(path, tmp)
    os.chmod(tmp, st.st_mode & 0o7777)
    os.chown(tmp, st.st_uid, st.st_gid)
    os.replace(tmp, ENV_FILE)
    if not check("the env file equals the backup byte for byte", read_bytes(ENV_FILE) == restored):
        return finish("rollback", rec)
    rc, out, err = wrapper("up", "-d", "--no-build", "--no-deps", SERVICE)
    private_write(os.path.join(record_dir(), "wrapper-rollback.log"), URL.sub("<url>", out + err))
    if check("wrapper exit 0", rc == 0, f"exit {rc}"):
        new = wait_new(am["id"])
        want = (pre.get("alertmanager") or {}).get("hash")
        check(f"{SERVICE} config hash = the baseline's (before the heartbeat)", bool(new) and bool(want) and new["hash"] == want,
              f"{((new or {}).get('hash') or '?')[:12]} vs {(want or '?')[:12]}")
        others_unchanged(pre["baseline"])
        receiver = watchdog_receiver(new["name"]) if new else None
        check("Watchdog routes to null again", receiver == "null", receiver or "?")
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
