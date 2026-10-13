#!/usr/bin/env python3
"""Redis credential rotation on parkio-civo-prod (credential incident 2026-10-13). Run as civo (Python 3.8+); running it
needs the independent review of the procedure (agent-tools/parkio-redis-credential-incident-20261013/ROTATION-
PROCEDURE.md) and its gates:

  python3 -I redis-rotate.py checkout --r2 <sha>   (moves /opt/parkio-release from R1 to R2)
  python3 -I redis-rotate.py pre
  python3 -I redis-rotate.py rotate --yes
  python3 -I redis-rotate.py rollback --yes        (only before redis itself was recreated)
  python3 -I redis-rotate.py redact --yes          (after a passing rotation)
  python3 -I redis-rotate.py status

No password value is ever printed, logged, passed as a process argument or written outside the env file:
- the new value is generated inside this process (secrets.token_urlsafe(32));
- redis-cli gets passwords through REDISCLI_AUTH in its environment;
- ACL commands go on stdin;
- comparisons happen in memory;
- records hold only names, ids, hashes of the Compose model and yes/no results.

checkout --r2 <sha> (after the W1 web deploy, which leaves the checkout at R1):
- the release checkout is clean at R1 5f181690 (the PIN plus the reviewed web pin);
- <sha> is the tip of origin/release/civo-redis-rotation-20261013, its only parent is R1, and it changes only
  docker/docker-compose.yml, to exactly the reviewed fix's blob (PR #344: the redis command and healthcheck keep the
  password out of the model and of process arguments);
- then `git checkout --detach <sha>`. This changes no container. The checkout stays at R2 afterwards, whatever the
  rotation's outcome: at R1 the rendered model would carry the password in the redis healthcheck again.
pre (read-only):
- the release checkout is clean at R2, as `checkout` verifies it;
- the model's redis service carries the env value nowhere outside `environment`, and its command writes
  /tmp/redis-auth.conf;
- the credential's consumers are exactly the expected four, both in the model and in the live containers of every
  project: gateway-service, auth-service, parking-service and gamification-service (SPRING_DATA_REDIS_PASSWORD), plus
  redis (REDIS_PASSWORD). Any other carrier fails;
- each consumer is running and healthy at the model's hash, so the rotation changes nothing else in them;
- for redis and each consumer, the model's image reference resolves on the host to the running container's image, so
  a recreate cannot change an image through a moved tag;
- redis is running and healthy and accepts the current value;
- not between 03:00 and 04:30 UTC (the backup).
rotate (--yes), within an hour of a passing pre, with the same env file and containers:
  A. adds the new value as a second password of the running redis (`ACL SETUSER default >…` on stdin); both work;
  B. backs the env file up (same mode and owner) and writes the new REDIS_PASSWORD atomically. It checks that only
     that key changed, that the preflight passes, and that exactly the consumers' and redis's model hashes changed;
  C. recreates the consumers one at a time: gamification, parking, auth, then gateway. For each: the wrapper
     `up -d --no-build --no-deps <svc>`, healthy, the new value in its environment, the model hash, and every other
     container unchanged;
  D. writes a probe key, then recreates redis with the wrapper. The fixed definition and the model's other redis
     settings take effect (the procedure lists them). Then: healthy; the new value works; the old one is refused; the
     probe key survives; no value in docker inspect outside Env or in process arguments; the consumers stay healthy;
     no authentication error in their logs since the redis recreate.
rollback (--yes), before phase D only, read from the rotation's progress file:
- restores the backup;
- recreates the consumers already moved, which use the old value again (still valid);
- resets the running redis to the old password only (`ACL SETUSER default resetpass >old`, on stdin). This needs
  no knowledge of the new value.
After phase D the old value is invalid by design, and a rollback would bring the exposed value back. The tool
refuses; the procedure's emergency section covers that case.
redact (--yes), after a passing rotation, while redis refuses the old value and accepts the env file's:
- in each file of a fixed list, every occurrence of the old value is replaced with
  CHANGE_ME_redacted_after_rotation_20261013. The list is the old env backups, the recovery checkpoint's env copy and
  rendered model (the 2026-10-13 scans). The preflight refuses that marker, so a restored backup cannot start with it.
  Each file is written atomically, keeping its mode, owner and modification time. A missing file is reported and
  skipped. The live env file is never touched, and neither is a file that holds the new value or is not a regular
  file of this user;
- then /opt, /home, /usr/local, /tmp and /var/tmp are scanned in memory, paths only: apart from the rotation's backup,
  no readable file may still hold the old value;
- only when all of that passed is the rotation's own backup redacted last. It is where the old value is read from, so
  a failed run can be repeated once the reported file is dealt with. A run after that finds the backup redacted and
  reports the redaction complete.
Records: ~/parkio-release-20261009/redis-rotation/rr-<stamp>/ (mode 700, files 600). Exit 0 = PASS, 1 = FAIL, 2 = usage.
"""
import calendar
import hashlib
import json
import os
import re
import stat
import secrets
import socket
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone

TEST = os.environ.get("RRT_TEST") == "1"


def cfg(name, default):
    """Overrides are honoured only by the self-test."""
    return os.environ.get(name, default) if TEST else default


R1 = cfg("RRT_R1", "5f181690b305eba3a494cf0b18c8412dbed1be57")          # the PIN f888a81b plus the reviewed web pin
BRANCH = cfg("RRT_BRANCH", "release/civo-redis-rotation-20261013")
FIX_COMPOSE_BLOB = cfg("RRT_FIX_BLOB", "c3f70587529169a421a8e61f569e3e5da2399c8a")     # docker/docker-compose.yml, PR #344
HOST = cfg("RRT_HOST", "parkio-civo-prod")
REL = cfg("RRT_RELEASE", "/opt/parkio-release")
ENV_FILE = cfg("RRT_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
OUT = cfg("RRT_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009", "redis-rotation"))
NOW = cfg("RRT_NOW", "")
HEALTH_WAIT = int(cfg("RRT_HEALTH_WAIT", "300"))
SETTLE = float(cfg("RRT_SETTLE", "60"))
POLL = float(cfg("RRT_POLL", "5"))
PROJECT = "parkio"
KEY = "REDIS_PASSWORD"
CONSUMER_KEY = "SPRING_DATA_REDIS_PASSWORD"
REDIS = "redis"
CONSUMERS = ("gamification-service", "parking-service", "auth-service", "gateway-service")   # least user-facing first
COMPOSE_FILE = "docker/docker-compose.yml"
REDACT_FILES = cfg("RRT_REDACT_FILES", ":".join((
    "/opt/parkio/docker/.env.azure-hosted-beta.bak-sas-quote-20260917T192536Z",
    "/opt/parkio/docker/.env.azure-hosted-beta.bak-pre-cron-quote-20260921T073414Z",
    "/opt/parkio/docker/.env.azure-hosted-beta.bak-20261008T180235Z",
    "/opt/parkio/docker/.env.azure-hosted-beta.bak-20261010T174445Z",
    "/opt/parkio/backup-artifacts/pr68-auth-email-20260922T114212Z/env.azure-hosted-beta.pre",
    "/opt/parkio-recovery/checkpoint-550848-postrestore/.env.azure-hosted-beta",
    "/opt/parkio-recovery/checkpoint-550848-postrestore/compose-550848-rendered.yml"))).split(":")
SCAN_ROOTS = cfg("RRT_SCAN_ROOTS", "/opt:/home:/usr/local:/tmp:/var/tmp").split(":")
MARKER = "CHANGE_ME_redacted_after_rotation_20261013"   # the preflight's is_placeholder refuses CHANGE_ME
NEW_VALUE = re.compile(r"[A-Za-z0-9_-]{43}")   # secrets.token_urlsafe(32): no quote, backslash or newline (#344 review R1)
PRE_MAX_AGE = 3600
_T = time.time()
STAMP = time.strftime("%Y%m%dT%H%M%S", time.gmtime(_T)) + "%03dZ" % (int(_T * 1000) % 1000)
CHECKS = []
SECRETS = []                       # values to scrub from anything printed; never printed themselves
AUTH_ERRORS = re.compile(rb"WRONGPASS|NOAUTH|invalid username-password|Authentication required")


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
    if TEST and "RRT_W" in os.environ:
        env["RRT_W"] = os.environ["RRT_W"]
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
    rc, out, err = run(["git", "--no-optional-locks", "-c", f"safe.directory={REL}", "-C", REL] + list(args))
    return rc, out.decode("utf-8", "replace")


def wrapper(*args, timeout=900):
    return run(["./scripts/parkio-prod-compose.sh"] + list(args), cwd=REL, extra_env={"PARKIO_ENV_FILE": ENV_FILE}, timeout=timeout)


def private_write(path, text):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(text)


def record_dir():
    d = os.path.join(OUT, f"rr-{STAMP}")
    os.makedirs(d, mode=0o700, exist_ok=True)
    return d


def save_progress(rec):
    """The rotation's phase and moved consumers, for a rollback after an interruption (replaced atomically)."""
    text = json.dumps({k: rec.get(k) for k in ("stamp", "phase", "backup", "moved")}, sort_keys=True)
    path = os.path.join(record_dir(), "rotate-progress.json")
    fd, tmp = tempfile.mkstemp(dir=record_dir())
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(text)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


def finish(name, rec):
    rec["checks"] = CHECKS
    rec["verdict"] = "PASS" if CHECKS and all(c["pass"] for c in CHECKS) else "FAIL"
    path = os.path.join(record_dir(), f"{name}.json")
    text = json.dumps(rec, indent=1, sort_keys=True) + "\n"
    if any(v and v in text for v in SECRETS):
        raise SystemExit("internal error: a record would contain a secret value; nothing written")
    private_write(path, text)
    say(f"redis rotation {name}: {rec['verdict']} | record {path}")
    return 0 if rec["verdict"] == "PASS" else 1


def now():
    return datetime.fromtimestamp(float(NOW), timezone.utc) if NOW else datetime.now(timezone.utc)


# ------------------------------------------------------------------ state readers (values stay in memory)
def env_file_value():
    try:
        data = open(ENV_FILE, "rb").read()
    except OSError:
        return None, None
    value = None
    for line in data.splitlines():
        if line.startswith(KEY.encode() + b"="):
            value = line.split(b"=", 1)[1].decode("utf-8", "replace").strip().strip('"').strip("'")
    return value, data


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


def carriers(value, ds):
    """{container name: [where]} for every container of any project that carries `value` anywhere."""
    out = {}
    for d in ds:
        where = sorted(k for k, v in env_of(d).items() if v == value)
        rest = dict(d)
        rest["Config"] = {k: v for k, v in (d.get("Config") or {}).items() if k != "Env"}
        if value in json.dumps(rest):
            where.append("OUTSIDE Env (inspect)")
        if where:
            out[d.get("Name", "").lstrip("/")] = where
    return out


def health(d):
    return ((d or {}).get("State") or {}).get("Health", {}).get("Status")


def model():
    rc, out, err = wrapper("config", "--format", "json", timeout=300)
    if rc != 0:
        return None
    try:
        return json.loads(out)
    except ValueError:
        return None


def model_hashes():
    rc, out, _ = wrapper("config", "--hash", "*", timeout=300)
    if rc != 0:
        return None
    return dict(l.split() for l in out.decode().splitlines() if len(l.split()) == 2)


def model_consumers(m, value):
    found = {}
    for svc, s in (m.get("services") or {}).items():
        env = s.get("environment") or {}
        if isinstance(env, list):
            env = dict(e.split("=", 1) if "=" in e else (e, "") for e in env)
        keys = sorted(k for k, v in env.items() if v is not None and str(v) == value)
        if keys:
            found[svc] = keys
    return found


def image_id(ref):
    rc, out, _ = run(["docker", "image", "inspect", "--format", "{{.Id}}", ref])
    return out.decode().strip() if rc == 0 else None


def redis_cli(name, password, commands):
    """Send `commands` on stdin to redis-cli in the redis container, authenticated through REDISCLI_AUTH."""
    rc, out, err = run(["docker", "exec", "-i", "-e", "REDISCLI_AUTH", name, "redis-cli"], extra_env={"REDISCLI_AUTH": password},
                       stdin=commands.encode(), timeout=60)
    return rc, (out + err).decode("utf-8", "replace")


def ping(name, password):
    rc, out = redis_cli(name, password, "PING\n")
    return any(l.strip() == "PONG" for l in out.splitlines())


def head_state():
    rc, head = git("rev-parse", "HEAD")
    rc2, status = git("status", "--porcelain")
    return (head.strip() if rc == 0 else ""), rc2 == 0 and not status.strip()


def is_r2(sha):
    """(ok, detail): sha's only parent is R1 and it changes only the compose file, to the reviewed fix's blob."""
    rc, parents = git("rev-list", "--parents", "-n", "1", sha)
    if rc != 0 or parents.split()[1:] != [R1]:
        return False, f"{sha[:12]}: its parent is not R1 {R1[:12]}"
    rc, names = git("diff", "--no-renames", "-z", "--name-only", R1, sha)
    changed = sorted(n for n in names.split("\0") if n)
    if rc != 0 or changed != [COMPOSE_FILE]:
        return False, f"{sha[:12]}: changes {', '.join(changed) or 'nothing'}, not only {COMPOSE_FILE}"
    rc, blob = git("rev-parse", f"{sha}:{COMPOSE_FILE}")
    if rc != 0 or blob.strip() != FIX_COMPOSE_BLOB:
        return False, f"{COMPOSE_FILE} is blob {blob.strip()[:12]}, not the reviewed fix {FIX_COMPOSE_BLOB[:12]}"
    return True, f"{sha[:12]}: R1 plus the reviewed redis fix"


def release_ok():
    head, clean = head_state()
    if not head or not clean:
        return False, f"not clean ({head[:12] or '?'})"
    return is_r2(head)


def write_atomically(data, st):
    directory = os.path.dirname(ENV_FILE)
    fd, tmp = tempfile.mkstemp(prefix=".rrt-", dir=directory)
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


def with_value(data, value):
    out = []
    for line in data.splitlines(keepends=True):
        if line.startswith(KEY.encode() + b"="):
            end = b"\r\n" if line.endswith(b"\r\n") else (b"\n" if line.endswith(b"\n") else b"")
            line = KEY.encode() + b"=" + value.encode("ascii") + end
        out.append(line)
    return b"".join(out)


def only_key_changed(before, after):
    strip = lambda d: [l for l in d.splitlines() if not l.startswith(KEY.encode() + b"=")]
    return strip(before) == strip(after)


def preflight_ok():
    rc, out, err = run(["sh", os.path.join(REL, "scripts", "preflight-hosted-beta.sh"), "--env-file", ENV_FILE,
                        "--deployment-profile", "azure-hosted-beta", "--skip-compose"], cwd=REL, timeout=300)
    return rc == 0


def wait_container(svc, before_id):
    deadline, d = time.time() + HEALTH_WAIT, None
    while time.time() < deadline:
        d = parkio(svc)
        if d and d.get("Id") != before_id and (d.get("State") or {}).get("Status") == "running" and health(d) == "healthy":
            return d
        time.sleep(POLL)
    return d


def baseline(ds):
    return {f"{p}/{s}": d.get("Id") for d in ds for p, s in [service_of(d)] if p}


def changed_since(base, ds, allowed):
    now_ = baseline(ds)
    return sorted(k for k in set(base) | set(now_) if k not in allowed and base.get(k) != now_.get(k))


# ------------------------------------------------------------------ commands
def basics():
    check(f"host {HOST}", socket.gethostname() == HOST, socket.gethostname())
    rc, out, _ = run(["docker", "info", "--format", "{{.ServerVersion}}"])
    check("docker usable without sudo", rc == 0)
    ok, detail = release_ok()
    check("the release checkout is clean at R2 (R1 plus the reviewed redis fix)", ok, detail)


def cmd_pre():
    say(f"redis rotation pre | {STAMP} | read-only")
    rec = {"tool": "redis-rotate", "command": "pre", "stamp": STAMP}
    basics()
    t = now()
    check("outside 03:00-04:30 UTC (the backup)", not (t.hour == 3 or (t.hour == 4 and t.minute < 30)), t.strftime("%H:%M UTC"))
    value, data = env_file_value()
    SECRETS.append(value or "")
    check("the env file holds a REDIS_PASSWORD of at least 16 characters (value not shown)", bool(value) and len(value) >= 16)
    ds = inspect_all()
    red = parkio(REDIS, ds)
    check("redis is running and healthy", bool(red) and health(red) == "healthy", health(red) or "?")
    check("the env file's value is the one redis runs with", bool(red) and env_of(red).get(KEY) == value)
    m = model()
    if check("the release model renders", m is not None):
        rs = (m.get("services") or {}).get(REDIS) or {}
        outside = {k: v for k, v in rs.items() if k != "environment"}
        check("the model's redis carries the value nowhere outside `environment`", bool(value) and value not in json.dumps(outside))
        check("the model's redis command writes /tmp/redis-auth.conf (the reviewed fix)", "/tmp/redis-auth.conf" in json.dumps(rs.get("command")))
        mc = model_consumers(m, value or "\0")
        want = {s: [CONSUMER_KEY] for s in CONSUMERS}
        want[REDIS] = [KEY]
        check("the model's consumers are exactly the expected ones", mc == want, ", ".join(f"{k}:{'/'.join(v)}" for k, v in sorted(mc.items())))
        for svc in (REDIS,) + CONSUMERS:
            d = parkio(svc, ds)
            ref = ((m.get("services") or {}).get(svc) or {}).get("image") or ""
            check(f"{svc}: the model's image resolves to the running container's image (no moved tag)",
                  bool(d) and bool(ref) and image_id(ref) == d.get("Image"), ref)
    live = carriers(value or "\0", ds)
    want_live = {red.get("Name", "").lstrip("/"): [KEY]} if red else {}
    for svc in CONSUMERS:
        d = parkio(svc, ds)
        if d:
            want_live[d.get("Name", "").lstrip("/")] = [CONSUMER_KEY]
    check("the live containers carrying the value are exactly the expected ones (any project, inside Env only)",
          live == want_live and len(want_live) == len(CONSUMERS) + 1, ", ".join(f"{k}:{'/'.join(v)}" for k, v in sorted(live.items())))
    hashes = model_hashes() or {}
    for svc in CONSUMERS:
        d = parkio(svc, ds)
        lab = ((d or {}).get("Config") or {}).get("Labels") or {}
        check(f"{svc} running, healthy and at the model's hash (the rotation changes only its credential)",
              bool(d) and health(d) == "healthy" and lab.get("com.docker.compose.config-hash") == hashes.get(svc),
              f"{health(d) or '?'}; hash {'= model' if lab.get('com.docker.compose.config-hash') == hashes.get(svc) else 'differs'}")
    red_hash = (((red or {}).get("Config") or {}).get("Labels") or {}).get("com.docker.compose.config-hash")
    rec["redisPendingDefinition"] = red_hash != hashes.get(REDIS)
    say(f"  NOTE redis will be recreated from the model: its definition {'differs' if rec['redisPendingDefinition'] else 'does not differ'} "
        "from the running container (the disclosure fix, plus the model's other redis settings; see the procedure)")
    check("redis accepts the current value", bool(red) and ping(red.get("Name", "").lstrip("/"), value or ""))
    rec["baseline"] = baseline(ds)
    rec["envSha256"] = hashlib.sha256(data).hexdigest() if data else None
    return finish("pre", rec)


def latest(name, passing_only=False):
    best = None
    try:
        for d in sorted(os.listdir(OUT)):
            p = os.path.join(OUT, d, f"{name}.json")
            if d.startswith("rr-") and os.path.isfile(p):
                r = json.load(open(p, encoding="utf-8"))
                if not passing_only or r.get("verdict") == "PASS":
                    best = r
    except (OSError, ValueError):
        return None
    return best


def cmd_rotate(yes):
    if not yes:
        print("usage: rotate needs --yes (after the procedure's review and gates)", file=sys.stderr)
        return 2
    say(f"redis rotation rotate | {STAMP}")
    rec = {"tool": "redis-rotate", "command": "rotate", "stamp": STAMP, "phase": "checks"}
    basics()
    pre = latest("pre")
    age = (time.time() - calendar.timegm(time.strptime(pre["stamp"][:15], "%Y%m%dT%H%M%S"))) if pre else None
    check("the latest pre passed within an hour", bool(pre) and pre.get("verdict") == "PASS" and age is not None and 0 <= age <= PRE_MAX_AGE,
          f"{pre['stamp']} {pre.get('verdict')}" if pre else "none")
    old, data = env_file_value()
    SECRETS.append(old or "")
    check("the env file is unchanged since that pre", bool(pre) and data is not None and hashlib.sha256(data).hexdigest() == pre.get("envSha256"))
    ds = inspect_all()
    check("the containers are the ones pre recorded", bool(pre) and baseline(ds) == pre.get("baseline"))
    if not all(c["pass"] for c in CHECKS):
        return finish("rotate", rec)
    red = parkio(REDIS, ds)
    rname = red.get("Name", "").lstrip("/")
    new = secrets.token_urlsafe(32)
    SECRETS.append(new)
    if not check("the new value is 43 URL-safe base64 characters (valid in redis's quoted config string)", NEW_VALUE.fullmatch(new)):
        return finish("rotate", rec)
    st = os.stat(ENV_FILE)

    say("A. add the new value as a second password of the running redis (stdin; never an argument)")
    rc, out = redis_cli(rname, old, f"ACL SETUSER default >{new}\n")
    rec["phase"] = "A"
    save_progress(rec)
    if not check("ACL SETUSER default >new: OK", rc == 0 and "OK" in out) or not check("old and new both work", ping(rname, old) and ping(rname, new)):
        say("  run `rollback --yes`: it resets redis to the old password only")
        return finish("rotate", rec)

    say("B. write the new value into the env file")
    backup = f"{ENV_FILE}.bak-rrt-{STAMP}"
    fd = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, st.st_mode & 0o7777)
    with os.fdopen(fd, "wb") as fh:
        fh.write(data)
        fh.flush()
        os.fsync(fh.fileno())
        os.fchown(fh.fileno(), st.st_uid, st.st_gid)
    rec["backup"] = os.path.basename(backup)
    save_progress(rec)
    if not check("backup identical to the original", open(backup, "rb").read() == data):
        return finish("rotate", rec)
    hashes_before = model_hashes() or {}
    newdata = with_value(data, new)
    rec["phase"] = "B"
    save_progress(rec)
    write_atomically(newdata, st)
    after = open(ENV_FILE, "rb").read()
    ok = check("only REDIS_PASSWORD changed, mode and owner kept", after == newdata and only_key_changed(data, after) and
               (os.stat(ENV_FILE).st_mode, os.stat(ENV_FILE).st_uid) == (st.st_mode, st.st_uid))
    ok &= check("the release preflight still passes", preflight_ok())
    hashes_after = model_hashes() or {}
    changed = sorted(s for s in set(hashes_before) | set(hashes_after) if hashes_before.get(s) != hashes_after.get(s))
    ok &= check("exactly the consumers' and redis's model hashes changed", changed == sorted(CONSUMERS + (REDIS,)), ", ".join(changed))
    m = model()
    rs = ((m or {}).get("services") or {}).get(REDIS) or {}
    ok &= check("the model's redis carries the new value nowhere outside `environment`",
                m is not None and new not in json.dumps({k: v for k, v in rs.items() if k != "environment"}))
    if not ok:
        write_atomically(data, st)
        rec["phase"] = "A"
        save_progress(rec)
        say("  the env file was restored; run `rollback --yes` to reset redis to the old password only")
        return finish("rotate", rec)

    say("C. recreate the consumers one at a time")
    base = baseline(inspect_all())
    moved = []
    for svc in CONSUMERS:
        before = parkio(svc)
        rc, out, err = wrapper("up", "-d", "--no-build", "--no-deps", svc)
        d = wait_container(svc, (before or {}).get("Id"))
        lab = ((d or {}).get("Config") or {}).get("Labels") or {}
        good = check(f"{svc}: wrapper exit 0, a new healthy container", rc == 0 and bool(d) and d.get("Id") != (before or {}).get("Id") and health(d) == "healthy",
                     f"exit {rc}, {health(d) or '?'}")
        good &= check(f"{svc}: the new value in its environment, at the model's hash",
                      bool(d) and env_of(d).get(CONSUMER_KEY) == new and lab.get("com.docker.compose.config-hash") == (model_hashes() or {}).get(svc))
        moved.append(svc)
        rec["moved"] = list(moved)
        rec["phase"] = "C"
        save_progress(rec)
        others = changed_since(base, inspect_all(), {f"{PROJECT}/{s}" for s in moved})
        good &= check(f"{svc}: every other container unchanged", not others, ", ".join(others))
        if not good:
            say("  STOP before redis was recreated: the old value still works. Run `rollback --yes`.")
            return finish("rotate", rec)

    say("D. recreate redis with the fixed definition")
    probe = "rotation:probe:" + secrets.token_hex(6)
    marker = secrets.token_hex(8)
    rc, out = redis_cli(rname, new, f"SET {probe} {marker} EX 3600\n")
    check("probe key written", rc == 0 and "OK" in out)
    before = parkio(REDIS)
    t_switch = time.time()
    rec["phase"] = "D"
    save_progress(rec)
    rc, out, err = wrapper("up", "-d", "--no-build", "--no-deps", REDIS)
    d = wait_container(REDIS, (before or {}).get("Id"))
    if not check("redis: wrapper exit 0, a new healthy container", rc == 0 and bool(d) and d.get("Id") != (before or {}).get("Id") and health(d) == "healthy",
                 f"exit {rc}, {health(d) or '?'}"):
        say("  redis did not come back healthy: follow the procedure's emergency section")
        return finish("rotate", rec)
    rname = d.get("Name", "").lstrip("/")
    lab = (d.get("Config") or {}).get("Labels") or {}
    check("redis: at the model's hash, on the same image", lab.get("com.docker.compose.config-hash") == (model_hashes() or {}).get(REDIS) and
          d.get("Image") == (before or {}).get("Image"))
    check("redis: the new value works", ping(rname, new))
    check("redis: the old value is refused", not ping(rname, old))
    rc, out = redis_cli(rname, new, f"GET {probe}\n")
    check("redis: the probe key survived (AOF)", marker in out)
    redis_cli(rname, new, f"DEL {probe}\n")
    check("redis: no value in docker inspect outside Env", carriers(new, [d]) == {rname: [KEY]} and not carriers(old, [d]))
    rc, top, _ = run(["docker", "top", rname, "-eo", "pid,args"])
    check("redis: no value in process arguments", rc == 0 and new.encode() not in top and old.encode() not in top)
    time.sleep(SETTLE)
    ds = inspect_all()
    for svc in CONSUMERS:
        c = parkio(svc, ds)
        rc, logs, lerr = run(["docker", "logs", "--since", str(int(t_switch)), c.get("Id")]) if c else (1, b"", b"")
        check(f"{svc}: healthy after the redis recreate, no authentication error in its logs since",
              bool(c) and health(c) == "healthy" and not AUTH_ERRORS.search(logs + lerr), health(c) or "?")
    others = changed_since(base, ds, {f"{PROJECT}/{s}" for s in CONSUMERS + (REDIS,)})
    check("every other container unchanged", not others, ", ".join(others))
    rec["phase"] = "done"
    save_progress(rec)
    say(f"  the env backup {rec['backup']} holds the old, now invalid value; remove it after acceptance")
    return finish("rotate", rec)


def cmd_rollback(yes):
    if not yes:
        print("usage: rollback needs --yes", file=sys.stderr)
        return 2
    say(f"redis rotation rollback | {STAMP}")
    rec = {"tool": "redis-rotate", "command": "rollback", "stamp": STAMP}
    rot = latest("rotate-progress")
    phase = (rot or {}).get("phase")
    if not check("a rotate record exists, stopped before redis was recreated (phase A, B or C)", phase in ("A", "B", "C"), phase or "none"):
        say("  after phase D the old value is invalid by design; see the procedure's emergency section")
        return finish("rollback", rec)
    current, data = env_file_value()
    backup = os.path.join(os.path.dirname(ENV_FILE), rot.get("backup") or "")
    old_data = open(backup, "rb").read() if rot.get("backup") and os.path.isfile(backup) else data
    old = None
    for line in (old_data or b"").splitlines():
        if line.startswith(KEY.encode() + b"="):
            old = line.split(b"=", 1)[1].decode().strip().strip('"').strip("'")
    SECRETS.extend([current or "", old or ""])
    st = os.stat(ENV_FILE)
    if phase in ("B", "C") and data != old_data:
        if not check("the backup differs from the env file only in REDIS_PASSWORD", only_key_changed(old_data, data)):
            return finish("rollback", rec)
        write_atomically(old_data, st)
        check("the env file holds the previous value again", open(ENV_FILE, "rb").read() == old_data)
    for svc in rot.get("moved") or []:
        before = parkio(svc)
        rc, _, _ = wrapper("up", "-d", "--no-build", "--no-deps", svc)
        d = wait_container(svc, (before or {}).get("Id"))
        check(f"{svc}: recreated with the previous value, healthy", rc == 0 and bool(d) and env_of(d).get(CONSUMER_KEY) == old and health(d) == "healthy")
    red = parkio(REDIS)
    rname = (red or {}).get("Name", "").lstrip("/")
    # resetpass drops every password, the unknown new one included, and >old adds the previous one back
    rc, out = redis_cli(rname, old, f"ACL SETUSER default resetpass >{old}\n")
    check("redis reset to the previous password only", rc == 0 and "OK" in out and ping(rname, old) and
          (current == old or not ping(rname, current)))
    return finish("rollback", rec)


def redact_file(path, old, new):
    """(ok, detail): replace every occurrence of old in path with MARKER, atomically, keeping mode, owner and times."""
    if os.path.realpath(path) == os.path.realpath(ENV_FILE):
        return False, "the live env file is never redacted"
    try:
        st = os.lstat(path)
    except FileNotFoundError:
        return True, "missing; nothing to redact"
    except OSError:
        return False, "cannot be read"
    if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid():
        return False, "not a regular file of this user; left alone"
    data = open(path, "rb").read()
    if new.encode() in data:
        return False, "holds the new value; left alone"
    n = data.count(old.encode())
    if n == 0:
        return True, "no occurrence (already redacted)"
    out = data.replace(old.encode(), MARKER.encode())
    fd, tmp = tempfile.mkstemp(prefix=".rrt-redact-", dir=os.path.dirname(path))
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(out)
            fh.flush()
            os.fsync(fh.fileno())
            os.fchmod(fh.fileno(), st.st_mode & 0o7777)
            os.fchown(fh.fileno(), st.st_uid, st.st_gid)
        os.utime(tmp, ns=(st.st_atime_ns, st.st_mtime_ns))
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise
    after = os.lstat(path)
    ok = open(path, "rb").read() == out and (after.st_mode, after.st_uid, after.st_gid, after.st_mtime_ns) == \
        (st.st_mode, st.st_uid, st.st_gid, st.st_mtime_ns)
    return ok, f"{n} occurrence(s) replaced; mode, owner and mtime kept" if ok else "written, but the read-back differs"


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
    say(f"redis rotation redact | {STAMP}")
    rec = {"tool": "redis-rotate", "command": "redact", "stamp": STAMP}
    check(f"host {HOST}", socket.gethostname() == HOST, socket.gethostname())
    rot, prog = latest("rotate"), latest("rotate-progress")
    if not check("the latest rotation passed (phase done)", bool(rot) and rot.get("verdict") == "PASS" and (prog or {}).get("phase") == "done" and
                 rot.get("stamp") == (prog or {}).get("stamp")):
        return finish("redact", rec)
    backup = os.path.join(os.path.dirname(ENV_FILE), rot.get("backup") or "")
    old = None
    for line in (open(backup, "rb").read() if os.path.isfile(backup) else b"").splitlines():
        if line.startswith(KEY.encode() + b"="):
            old = line.split(b"=", 1)[1].decode().strip().strip('"').strip("'")
    if old == MARKER:
        check("redaction already complete: the rotation's backup no longer holds the old value", True)
        return finish("redact", rec)
    new, _ = env_file_value()
    SECRETS.extend([old or "", new or ""])
    red = parkio(REDIS)
    rname = (red or {}).get("Name", "").lstrip("/")
    if not check("the old value is known (the rotation's backup), redis refuses it and accepts the env file's value",
                 bool(old) and bool(new) and old != new and bool(red) and not ping(rname, old) and ping(rname, new)):
        return finish("redact", rec)
    rec["files"] = {}
    for path in REDACT_FILES:
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


def cmd_checkout(r2):
    say(f"redis rotation checkout | {STAMP} | {r2[:12]}")
    rec = {"tool": "redis-rotate", "command": "checkout", "stamp": STAMP, "r2": r2}
    check(f"host {HOST}", socket.gethostname() == HOST, socket.gethostname())
    head, clean = head_state()
    if head == r2 and clean:
        check("the release checkout is already at R2 (clean)", is_r2(r2)[0], is_r2(r2)[1])
        return finish("checkout", rec)
    if not check("the release checkout is clean at R1 (the W1 web deploy's state)", head == R1 and clean, head[:12] or "?"):
        return finish("checkout", rec)
    rc, _ = git("fetch", "--quiet", "origin", f"+refs/heads/{BRANCH}:refs/remotes/origin/{BRANCH}")
    if not check(f"fetched {BRANCH}", rc == 0):
        return finish("checkout", rec)
    rc, tip = git("rev-parse", f"refs/remotes/origin/{BRANCH}")
    ok = check("R2 is the release branch tip", rc == 0 and tip.strip() == r2, tip.strip()[:12])
    good, detail = is_r2(r2)
    ok &= check("R2 is R1 plus only the reviewed redis fix", good, detail)
    if not ok:
        return finish("checkout", rec)
    rc, _ = git("checkout", "--quiet", "--detach", r2)
    head, clean = head_state()
    check("checked out R2 (clean)", rc == 0 and head == r2 and clean, head[:12])
    return finish("checkout", rec)


def cmd_status():
    try:
        for d in sorted(os.listdir(OUT))[-8:]:
            for f in sorted(os.listdir(os.path.join(OUT, d))):
                if f.endswith(".json"):
                    r = json.load(open(os.path.join(OUT, d, f), encoding="utf-8"))
                    say(f"{d} {r.get('command')}: {r.get('verdict')}" + (f" (phase {r.get('phase')})" if r.get("phase") else ""))
    except (OSError, ValueError):
        say("no records")
    return 0


def main(argv):
    if not argv or argv[0] not in ("checkout", "pre", "rotate", "rollback", "redact", "status"):
        print(__doc__, file=sys.stderr)
        return 2
    cmd, rest = argv[0], argv[1:]
    if cmd == "checkout":
        if len(rest) != 2 or rest[0] != "--r2" or not re.fullmatch(r"[0-9a-f]{40}", rest[1]):
            print("usage: checkout --r2 <40-hex sha>", file=sys.stderr)
            return 2
        return cmd_checkout(rest[1])
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
