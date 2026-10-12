#!/usr/bin/env python3
"""U05 PRIV-001A live window on parkio-civo-prod (Asana 1219100261876615, criteria 2 and 3). Version 2.

It runs ONLY after the owner's scoped live-test decision (D-U05-2). Steps W0-W6 follow
agent-tools/parkio-u05-priv001a-civo-20261012/LIVE-TEST-SPEC.md.

  sudo python3 -I u05-priv001a-window.py preflight --harness DIR [--fault none|analytics]   read-only
  sudo python3 -I u05-priv001a-window.py start --harness DIR [--fault none|analytics]       launches the window as a
                                         transient systemd unit and follows its log; an SSH disconnect stops only the
                                         following, never the window
  sudo python3 -I u05-priv001a-window.py status [--run RUN]
  sudo python3 -I u05-priv001a-window.py restore [--run RUN] [--stop-window]               idempotent full restore

--fault none (default): criterion 2 only; analytics-service is never stopped.
--fault analytics: criterion 3 with analytics-service stopped (at most 600 s), then criterion 2. It needs its own
  explicit authorization: it pauses all analytics processing and fires ServiceDown/CoreServiceDown.

Integrity
- The five harness files and the snapshot tool must match the SHA-256 pins below. They are copied, with this file,
  into the root-only run directory and verified there; only the copies run, also from the guards, and each harness
  or snapshot run is preceded by another check.

Timing, from W1 (the first change)
- The runner begins its restore by RESTORE_BY (38 min): every step checks the time left, and every harness or
  compose call is capped by it.
- The window guard fires at GUARD_WINDOW_AT (42 min). It waits up to GUARD_LOCK_WAIT (3 min) for a restore that the
  runner is running, then stops the runner and restores. Every restore step has its own bound (about 15 min in all).
- The analytics guard starts analytics-service ANALYTICS_GUARD_AT (510 s) after the stop; the approved limit is 600 s.

Safety
- Each guard is armed BEFORE the change it protects. Guards are transient systemd timers, independent of the runner
  and of SSH.
- The runner unit has KillMode=mixed and TimeoutStopSec=1200: `systemctl stop` sends SIGTERM to the runner only; it
  then restores, and further SIGTERMs are ignored while it does.
- Every restore step runs on its own and records its result: analytics-service, the synthetic principal, the env file,
  auth-service, the erasure's completion, the guards. A failed step does not skip the next one.
- The env file: only the window keys change, by compare-and-swap writes with the same mode and owner. It is restored
  byte for byte from the W1 backup, and only while it is exactly what W1 wrote. Any other change found there, by
  anyone, stops the automatic restore: nothing is written, auth-service is not recreated, and RECOVERY.md applies.
- auth-service is recreated with `up -d --no-deps --no-build` only. analytics-service is stopped and started, never
  `up`: during the window its rendered config differs, and `up` would recreate it.
- After every outcome a read-only accounting covers the synthetic accounts and priv001a invites created since W1, the
  principal's erasure, and other accounts' erasure requests. Anything left open is an ORPHAN or PENDING note, and FAIL.
- The operator token, when it has to be created, is generated here, written only to the env file, and never printed
  or stored elsewhere; the state keeps the SHA-256 of its line.
Evidence (secret-free): ~civo/parkio-release-20261009/u05-window/<run>/. Private state, the verified copies, the env
backup and the credentials file: /root/u05-priv001a-window/<run>/ (700/600). Secrets are shredded after a verified
restore.
"""
import fcntl, hashlib, json, os, re, secrets, shutil, signal, subprocess, sys, time

TEST = os.environ.get("U05R_TEST") == "1"


def cfg(name, default):
    """Overrides are honoured only by the self-test."""
    return os.environ.get(name, default) if TEST else default


def cfg_int(name, default):
    return int(cfg(name, str(default)))


HERE = os.path.dirname(os.path.abspath(__file__))
RELEASE = cfg("U05R_RELEASE", "/opt/parkio-release")
PIN = cfg("U05R_PIN", "f888a81b113c0c486ea0949067779443857e644d")
ENV_FILE = cfg("U05R_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
PRIVATE = cfg("U05R_PRIVATE", "/root/u05-priv001a-window")
EVIDENCE = cfg("U05R_EVIDENCE", "/home/civo/parkio-release-20261009/u05-window")
OWNER = cfg("U05R_OWNER", "civo")
DOCKER = cfg("U05R_DOCKER", "docker")
SYSTEMD_RUN = cfg("U05R_SYSTEMD_RUN", "systemd-run")
SYSTEMCTL = cfg("U05R_SYSTEMCTL", "systemctl")
SHRED = cfg("U05R_SHRED", "shred")
PROJECT = "parkio"
HOLD = cfg_int("U05R_HOLD", 300)
COMPLETE_TIMEOUT = cfg_int("U05R_COMPLETE_TIMEOUT", 900)
ANALYTICS_LIMIT = 600
ANALYTICS_GUARD_AT = cfg_int("U05R_ANALYTICS_GUARD_AT", 510)
RESTORE_BY = cfg_int("U05R_RESTORE_BY", 2280)
GUARD_WINDOW_AT = cfg_int("U05R_GUARD_WINDOW_AT", 2520)
GUARD_LOCK_WAIT = cfg_int("U05R_GUARD_LOCK_WAIT", 180)
HEALTH_TIMEOUT = cfg_int("U05R_HEALTH_TIMEOUT", 180)
LAG_WAIT = cfg_int("U05R_LAG_WAIT", 180)
LAG_TOLERANCE = 5
POLL = cfg_int("U05R_POLL", 5)
HARNESS_POLL = cfg("U05R_HARNESS_POLL", "15")
HARNESS_TIMEOUT_CAP = cfg_int("U05R_HARNESS_TIMEOUT_CAP", 100000)
STOP_TIMEOUT = 1200
UNIT = "u05-priv001a-window"
GUARD_ANALYTICS, GUARD_WINDOW = "u05-priv001a-guard-analytics", "u05-priv001a-guard-window"
CREATE = "scripts/acceptance/create-priv001-synthetic-principal.sh"
ERASE = "scripts/acceptance/erase-priv001-synthetic-principal.sh"
INSPECT = "scripts/acceptance/inspect-priv001-synthetic-residue.sh"
HARNESS_FILES = (CREATE, ERASE, INSPECT, "scripts/lib/priv001-synthetic.sh", "scripts/lib/dark-gateway-url.sh")
# SHA-256 of each harness file at the PR #338 merge commit 9ffdd377c188ebd28babd54089b5049d71610435 (api).
HARNESS_PINS = {
    "scripts/acceptance/create-priv001-synthetic-principal.sh":
        "5d82417fe723ab9732fb8e4a8f1382ae9cb8885c9c1c6dc3330134c8a87731c6",
    "scripts/acceptance/erase-priv001-synthetic-principal.sh":
        "7b7e93657e2024ab8924b18cce65b934aafc2f82b17fec83af8cf0ec6f7f96ad",
    "scripts/acceptance/inspect-priv001-synthetic-residue.sh":
        "b04e23c5a717b1bd6cdf43344700afa7b5be145620bfea33135f98e5f37fcdae",
    "scripts/lib/priv001-synthetic.sh":
        "3f4fee89cec02c1c45cc2457d56f86b92dbc9d963b5428024da820e77db9896d",
    "scripts/lib/dark-gateway-url.sh":
        "f58eebaa7afd358fd90c681aec3968af66013cbcbf2d75445f7d63739be36b13",
}
SNAPSHOT_PIN = "d6bcc9a2e9201552d0f2f5fcac5bf286c6560822a7fe9792bbecf8445dd9ebd3"
KEY_ERASURE, KEY_MODE = "PARKIO_ACCOUNT_ERASURE_ENABLED", "PARKIO_REGISTRATION_MODE"
KEY_CREATION, KEY_TOKEN = "PARKIO_REGISTRATION_INVITE_CREATION_ENABLED", "PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN"
APP_SERVICES = ("auth-service", "user-service", "parking-service", "media-service", "moderation-service",
                "gamification-service", "notification-service", "analytics-service", "ai-validation-service", "gateway-service")
ANALYTICS_GROUPS = ("parkio.analytics", "parkio.analytics.erasure")
SYNTHETIC_EMAIL = re.compile(r"^priv001a-[a-z0-9]{6,64}@priv001a\.parkio\.invalid$")
UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
STATE_NAME = "state.json"


class Stop(Exception):
    pass


def pins():
    """(harness pins, snapshot pin). The self-test may supply its own through U05R_PINS."""
    if TEST and os.environ.get("U05R_PINS"):
        p = json.load(open(os.environ["U05R_PINS"]))
        return p.get("harness", {}), p.get("snapshot", SNAPSHOT_PIN)
    return HARNESS_PINS, SNAPSHOT_PIN


def now():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def sha(b):
    return hashlib.sha256(b).hexdigest()


def sha_file(path):
    with open(path, "rb") as f:
        return sha(f.read())


def run(args, cwd=None, env=None, timeout=600, inp=None):
    """No stdin unless given. On a timeout, an exception or a signal, the whole child process group is killed."""
    try:
        p = subprocess.Popen(args, cwd=cwd, env=env, text=True, start_new_session=True,
                             stdin=subprocess.PIPE if inp is not None else subprocess.DEVNULL,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except OSError as e:
        return 127, "", str(e)
    try:
        out, err = p.communicate(inp, timeout=max(1, int(timeout)))
        return p.returncode, out, err
    except subprocess.TimeoutExpired:
        kill_group(p)
        out, err = p.communicate()
        return 124, out, (err or "") + f"\ntimed out after {int(timeout)} s"
    except BaseException:
        kill_group(p)
        try:
            p.communicate(timeout=10)
        except Exception:
            pass
        raise


def kill_group(p):
    try:
        os.killpg(p.pid, signal.SIGKILL)
    except OSError:
        pass


def say(msg):
    print(f"{now()} {msg}", flush=True)


def parse_ts(s):
    """Docker's RFC 3339 time with nanoseconds -> epoch seconds, or None."""
    m = re.match(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})$", s or "")
    if not m or m.group(1).startswith("0001-"):
        return None
    import calendar
    t = calendar.timegm(time.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S")) + float("0." + (m.group(2) or "0"))
    if m.group(3) != "Z":
        sign = 1 if m.group(3)[0] == "+" else -1
        t -= sign * (int(m.group(3)[1:3]) * 3600 + int(m.group(3)[4:6]) * 60)
    return t


# --- private state (root only) --------------------------------------------------------------------------------------
def run_dir(run_id):
    return os.path.join(PRIVATE, run_id)


def latest_run():
    try:
        runs = sorted(d for d in os.listdir(PRIVATE) if re.match(r"^\d{8}T\d{6}Z$", d))
    except OSError:
        runs = []
    return runs[-1] if runs else None


def load_state(run_id):
    with open(os.path.join(run_dir(run_id), STATE_NAME)) as f:
        return json.load(f)


def save_state(st):
    d = run_dir(st["run"])
    tmp = os.path.join(d, STATE_NAME + ".tmp")
    with open(tmp, "w") as f:
        json.dump(st, f, indent=2, sort_keys=True)
        f.flush()
        os.fsync(f.fileno())
    os.chmod(tmp, 0o600)
    os.replace(tmp, os.path.join(d, STATE_NAME))


def save_state_quietly(st):
    try:
        save_state(st)
    except OSError as e:
        say(f"  WARNING: the state file could not be written ({type(e).__name__}); continuing")


def acquire(path, wait):
    f = open(path, "a+")
    end = time.time() + wait
    while True:
        try:
            fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return f
        except BlockingIOError:
            if time.time() >= end:
                f.close()
                return None
            time.sleep(1)


# --- integrity ------------------------------------------------------------------------------------------------------
def copy_verified(src_dir, dst_dir):
    """Copies the harness files into dst_dir (same layout) and the snapshot tool next to this file's copy."""
    hpins, spin = pins()
    if set(hpins) != set(HARNESS_FILES):
        raise Stop("the harness pins are not set: no harness may run")
    for rel in HARNESS_FILES:
        src = os.path.join(src_dir, rel)
        if not os.path.isfile(src):
            raise Stop(f"harness file missing: {rel}")
        dst = os.path.join(dst_dir, "harness", rel)
        os.makedirs(os.path.dirname(dst), mode=0o700, exist_ok=True)
        shutil.copyfile(src, dst)
        os.chmod(dst, 0o700)
    if not os.path.isfile(os.path.join(HERE, "u05-window-snapshot.py")):
        raise Stop("snapshot tool missing next to the runner")
    shutil.copyfile(os.path.join(HERE, "u05-window-snapshot.py"), os.path.join(dst_dir, "u05-window-snapshot.py"))
    verify_copies(dst_dir)


def verify_copies(d):
    hpins, spin = pins()
    if set(hpins) != set(HARNESS_FILES):
        raise Stop("the harness pins are not set: no harness may run")
    bad = [rel for rel in HARNESS_FILES if sha_file(os.path.join(d, "harness", rel)) != hpins[rel]]
    if sha_file(os.path.join(d, "u05-window-snapshot.py")) != spin:
        bad.append("u05-window-snapshot.py")
    if bad:
        raise Stop(f"integrity: {', '.join(bad)} do not match the pinned SHA-256")


# --- the env file ---------------------------------------------------------------------------------------------------
LINE = re.compile(rb"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*?)(\r?\n)?$")


def parse(data):
    lines = data.splitlines(keepends=True)
    keyed = []
    for ln in lines:
        m = LINE.match(ln)
        keyed.append((m.group(1).decode() if m else None, m.group(2) if m else None))
    return lines, keyed


def value_of(raw):
    v = raw.strip()
    if len(v) >= 2 and v[:1] == v[-1:] and v[:1] in (b"'", b'"'):
        v = v[1:-1]
    return v.decode("utf-8", "replace")


def env_values(data):
    lines, keyed = parse(data)
    out, dup = {}, set()
    for k, raw in keyed:
        if k is None:
            continue
        if k in out:
            dup.add(k)
        out[k] = value_of(raw)
    return out, dup


def env_stat(path):
    st = os.stat(path)
    return {"mode": oct(st.st_mode & 0o7777), "uid": st.st_uid, "gid": st.st_gid}


def atomic_write(path, data, expect_sha):
    """Compare-and-swap: writes only if the file still has expect_sha right before the replace; keeps mode and owner."""
    st = os.stat(path)
    tmp = os.path.join(os.path.dirname(path), f".{os.path.basename(path)}.u05-{os.getpid()}")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.write(fd, data)
        os.fsync(fd)
    finally:
        os.close(fd)
    try:
        os.chmod(tmp, st.st_mode & 0o7777)
        if not TEST:
            os.chown(tmp, st.st_uid, st.st_gid)
        if sha_file(path) != expect_sha:
            raise Stop("the env file changed between the read and the write")
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            shred([tmp])


def env_leftovers():
    d, base = os.path.dirname(os.path.realpath(ENV_FILE)), os.path.basename(os.path.realpath(ENV_FILE))
    try:
        return [os.path.join(d, f) for f in os.listdir(d) if f.startswith(f".{base}.u05-")]
    except OSError:
        return []


def plan_changes(values):
    """The window keys and target values. KEY_TOKEN only when it is unset (the value is created at apply time)."""
    mode = values.get(KEY_MODE, "closed") or "closed"
    if mode == "open":
        raise Stop("PARKIO_REGISTRATION_MODE is open: an invite would stay unconsumed; re-plan the window")
    if mode not in ("closed", "invite"):
        raise Stop(f"unexpected PARKIO_REGISTRATION_MODE {mode!r}")
    plan = [(KEY_ERASURE, "true"), (KEY_CREATION, "true")]
    if mode == "closed":
        plan.append((KEY_MODE, "invite"))
    if not values.get(KEY_TOKEN):
        plan.append((KEY_TOKEN, None))
    return plan


def apply_changes(st):
    path = os.path.realpath(ENV_FILE)
    if not os.path.isfile(path):
        raise Stop(f"{ENV_FILE} is not a regular file")
    data = open(path, "rb").read()
    if sha(data) != st["envShaW0"]:
        raise Stop("the env file changed after W0; re-run the window")
    values, dup = env_values(data)
    plan = plan_changes(values)
    clash = sorted(dup & {k for k, _ in plan})
    if clash:
        raise Stop(f"the env file defines {clash} more than once; refusing to edit it")
    backup = os.path.join(run_dir(st["run"]), "env.backup")
    with open(backup, "wb") as f:
        f.write(data)
    os.chmod(backup, 0o600)
    lines, keyed = parse(data)
    patches = []
    for key, value in plan:
        if value is None:
            value = secrets.token_urlsafe(36)      # 48 characters, URL-safe, never printed or stored here
        new = f"{key}={value}\n".encode()
        idx = [i for i, (k, _) in enumerate(keyed) if k == key]
        if idx:
            i = idx[0]
            orig = lines[i]
            if key == KEY_TOKEN and value_of(keyed[i][1]):
                raise Stop("the operator token is already set; the window never replaces it")
            eol = b"\r\n" if orig.endswith(b"\r\n") else b"\n"
            new = new[:-1] + eol
            lines[i] = new
            # The token line is only ever replaced when its value is empty, so its original line holds no secret.
            patches.append({"key": key, "original": orig.decode("utf-8", "replace"),
                            "originalSha": sha(orig), "newSha": sha(new), "appended": False})
        else:
            if lines and not lines[-1].endswith(b"\n"):
                lines[-1] = lines[-1] + b"\n"
            lines.append(new)
            keyed.append((key, None))
            patches.append({"key": key, "original": None, "originalSha": None, "newSha": sha(new), "appended": True})
    out = b"".join(lines)
    st.update(envPath=path, envSha0=sha(data), envSha1=sha(out), envBackup=backup, patches=patches,
              changedKeys=[p["key"] for p in patches])
    save_state(st)
    atomic_write(path, out, sha(data))
    if sha_file(path) != st["envSha1"]:
        raise Stop("the env file does not hold what W1 wrote")
    return [p["key"] for p in patches]


def restore_env(st):
    """'not-changed', 'already-restored' or 'byte-identical'; raises Stop on any other content (CONFLICT)."""
    if not st.get("patches"):
        return "not-changed"
    path = st["envPath"]
    cur = sha_file(path)
    if cur == st["envSha0"]:
        return "already-restored"
    if cur != st["envSha1"]:
        lines = open(path, "rb").read().splitlines(keepends=True)
        still = [p["key"] for p in st["patches"] if sum(1 for ln in lines if sha(ln) == p["newSha"]) == 1]
        st["envConflict"] = {"windowKeysStillSet": still,
                             "otherKeysChanged": "yes (the file is neither the W0 nor the W1 content)"}
        raise Stop("CONFLICT: the env file was changed by someone else during the window; nothing was written "
                   f"(window keys still set: {', '.join(still) or 'none'}) - follow RECOVERY.md section 4")
    backup = open(st["envBackup"], "rb").read()
    if sha(backup) != st["envSha0"]:
        raise Stop("the W1 backup does not match the W0 hash; nothing was written - follow RECOVERY.md")
    atomic_write(path, backup, st["envSha1"])
    if sha_file(path) != st["envSha0"]:
        raise Stop("the restored env file does not match W0")
    return "byte-identical"


# --- compose, containers, guards ------------------------------------------------------------------------------------
def compose(*args, timeout=300):
    env = dict(os.environ, PARKIO_ENV_FILE=ENV_FILE)
    return run(["./scripts/parkio-prod-compose.sh", *args], cwd=RELEASE, env=env, timeout=timeout)


def containers():
    rc, out, _ = run([DOCKER, "ps", "-a", "--format", "{{.ID}}"], timeout=60)
    if rc != 0:
        raise Stop("docker ps failed")
    ids = out.split()
    if not ids:
        return {}
    rc, out, _ = run([DOCKER, "inspect", *ids], timeout=60)
    if rc != 0:
        raise Stop("docker inspect failed")
    res = {}
    for c in json.loads(out):
        lab = (c.get("Config") or {}).get("Labels") or {}
        state = c.get("State") or {}
        res[c["Name"].lstrip("/")] = {"id": c["Id"], "service": lab.get("com.docker.compose.service"),
                                      "project": lab.get("com.docker.compose.project"),
                                      "configHash": lab.get("com.docker.compose.config-hash"),
                                      "status": state.get("Status"), "startedAt": state.get("StartedAt"),
                                      "finishedAt": state.get("FinishedAt"),
                                      "health": (state.get("Health") or {}).get("Status"),
                                      "env": (c.get("Config") or {}).get("Env") or []}
    return res


def one(cs, service):
    hits = [n for n, c in cs.items() if c["project"] == PROJECT and c["service"] == service]
    if len(hits) != 1:
        raise Stop(f"expected exactly one {service} container, found {len(hits)}")
    return hits[0]


def container(service):
    cs = containers()
    return cs[one(cs, service)]


def wait_healthy(service, timeout):
    end = time.time() + timeout
    while True:
        c = container(service)
        if c["status"] == "running" and c["health"] in ("healthy", None):
            return
        if time.time() >= end:
            raise Stop(f"{service} did not become healthy within {int(timeout)} s")
        time.sleep(POLL)


def auth_env_matches(expect):
    env = dict(e.split("=", 1) for e in container("auth-service")["env"] if "=" in e)
    for k, v in expect.items():
        got = env.get(k, "")
        ok = bool(got) if v == "<set>" else (not got) if v == "<unset>" else got == v
        if not ok:
            return False
    return True


def recreate_auth(expect, timeout=300):
    before = {n: c for n, c in containers().items() if c["project"] == PROJECT}
    rc, out, err = compose("up", "-d", "--no-deps", "--no-build", "auth-service", timeout=timeout)
    if rc != 0:
        raise Stop(f"recreating auth-service failed (exit {rc})")
    wait_healthy("auth-service", HEALTH_TIMEOUT)
    after = {n: c for n, c in containers().items() if c["project"] == PROJECT}
    others = sorted(n for n in before if before[n]["service"] != "auth-service" and n in after
                    and before[n]["id"] != after[n]["id"])
    gone_or_new = sorted(set(before) ^ set(after))
    if others or gone_or_new:
        raise Stop(f"recreating auth-service changed other project containers: {others + gone_or_new}")
    if not auth_env_matches(expect):
        raise Stop("auth-service does not run with the expected settings")


def unit_active(unit):
    rc, out, _ = run([SYSTEMCTL, "is-active", unit], timeout=30)
    return out.strip() in ("active", "activating", "deactivating", "reloading")


def arm(guard, seconds, log, *cmd):
    rc, out, err = run([SYSTEMD_RUN, "--unit", guard, f"--on-active={int(seconds)}", "--timer-property=AccuracySec=1s",
                        "--collect", f"--property=StandardOutput=append:{log}", f"--property=StandardError=append:{log}",
                        *cmd], timeout=60)
    if rc != 0:
        raise Stop(f"cannot arm {guard}: {err.strip()[:160]}")


def disarm(guard):
    """Stops the guard's timer (never its service, which may be the caller) and checks it."""
    run([SYSTEMCTL, "stop", f"{guard}.timer"], timeout=60)
    if unit_active(f"{guard}.timer"):
        raise Stop(f"{guard}.timer is still active after the stop")


def analytics_stop_seconds():
    c = container("analytics-service")
    stopped, started = parse_ts(c["finishedAt"]), parse_ts(c["startedAt"])
    if stopped is None or started is None or started < stopped:
        return None
    return int(round(started - stopped))


def harness(st, script, *args, timeout):
    verify_copies(run_dir(st["run"]))
    env = dict(os.environ, TMPDIR=os.path.join(run_dir(st["run"]), "tmp"))
    os.makedirs(env["TMPDIR"], mode=0o700, exist_ok=True)
    path = os.path.join(run_dir(st["run"]), "harness", script)
    rc, out, err = run(["bash", path, "--environment", "civo-production", "--confirm-synthetic-only", *args],
                       env=env, timeout=min(timeout, HARNESS_TIMEOUT_CAP))
    text = (out + err).strip()
    say(f"  {os.path.basename(script)} exit {rc}")
    for line in text.splitlines():
        if not re.search(r"(?i)(password|access_token|refresh_token|bearer|eyJ)", line):
            print(f"    {line}", flush=True)
    return rc, text


def psql(sql):
    """Read-only query (read-only session) on the auth database; rows of '|'-separated fields."""
    pg = one(containers(), "postgres-auth")
    rc, out, err = run([DOCKER, "exec", "-e", "PGOPTIONS=-c default_transaction_read_only=on", pg, "sh", "-c",
                        'exec psql -X -q -At -F "|" --set=ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"',
                        "psql", sql], timeout=60)
    if rc != 0:
        raise Stop("a read-only auth database query failed")
    return [ln.split("|") for ln in out.splitlines() if ln.strip()]


def scalar(sql):
    rows = psql(sql)
    return rows[0][0] if rows else None


def analytics_lag():
    kafka = one(containers(), "kafka")
    total = 0
    for group in ANALYTICS_GROUPS:
        rc, out, err = run([DOCKER, "exec", "-e", "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m", kafka, "timeout", "60",
                            "kafka-consumer-groups", "--bootstrap-server", "kafka:9092", "--describe", "--group", group],
                           timeout=90)
        if rc != 0:
            raise Stop(f"cannot read the consumer lag of {group}")
        rows = [ln.split() for ln in out.splitlines() if ln.split()[:1] == [group]]
        if not rows:
            raise Stop(f"no partitions listed for {group}")
        total += sum(int(r[5]) for r in rows if len(r) > 5 and r[5].isdigit())
    return total


def snapshot(label, st):
    d = run_dir(st["run"])
    verify_copies(d)
    env = dict(os.environ, U05W_OUT=st["evidence"], U05W_ENV_FILE=ENV_FILE, U05W_RELEASE=RELEASE, U05W_DOCKER=DOCKER,
               U05W_CHANGED="auth-service,analytics-service" if st.get("fault") == "analytics" else "auth-service")
    args = ["snapshot", label] if label != "compare" else ["compare"]
    rc, out, err = run([sys.executable, "-I", os.path.join(d, "u05-window-snapshot.py"), *args], env=env, timeout=300)
    for line in (out + err).strip().splitlines():
        print(f"    {line}", flush=True)
    return rc


def latest_snapshot(st, label):
    files = sorted(f for f in os.listdir(st["evidence"]) if re.match(rf"^{label}-\d{{8}}T\d{{6}}Z\.json$", f))
    if not files:
        raise Stop(f"no {label} snapshot")
    with open(os.path.join(st["evidence"], files[-1])) as f:
        return json.load(f)


def shred(paths):
    for p in paths:
        if p and os.path.isfile(p):
            if run([SHRED, "-u", p], timeout=60)[0] != 0 and os.path.exists(p):
                with open(p, "r+b") as f:
                    f.write(b"\0" * os.path.getsize(p))
                os.remove(p)


# --- read-only accounting -------------------------------------------------------------------------------------------
def creds_fields(st):
    out = {}
    p = st.get("credentials")
    if p and os.path.isfile(p):
        for line in open(p).read().splitlines():
            k, _, v = line.partition("=")
            if k in ("auth_user_id", "email"):
                out[k] = v
    return out


def principal_row(st):
    """(id, status, email) of the synthetic principal, from the state, the credentials file or the register line."""
    c = creds_fields(st)
    uid = st.get("authUserId") or c.get("auth_user_id")
    if uid and UUID.match(uid):
        rows = psql(f"select id, status, email from auth_users where id = '{uid}'::uuid")
    elif c.get("email") and SYNTHETIC_EMAIL.match(c["email"]):
        rows = psql(f"select id, status, email from auth_users where email = '{c['email']}'")
    else:
        return None
    if not rows:
        return None
    st["authUserId"] = rows[0][0]
    return tuple(rows[0][:3])


def request_row(uid):
    rows = psql("select r.status, (select count(*) from erasure_service_acks a where a.erasure_request_id = r.id "
                f"and a.status = 'SUCCESS') from erasure_requests r where r.auth_user_id = '{uid}'::uuid "
                "order by r.requested_at desc limit 1")
    return (rows[0][0], int(rows[0][1])) if rows else None


def accounting(st):
    w1 = st["w1"]
    acc = {"syntheticAccountsSinceW1": [], "syntheticAccountsBeforeW1": 0, "invitesSinceW1": None,
           "principal": None, "otherErasureRequestsSinceW1": None}
    for r in psql("select id, status from auth_users where email like 'priv001a-%@priv001a.parkio.invalid' "
                  f"and created_at >= '{w1}'::timestamptz order by created_at"):
        acc["syntheticAccountsSinceW1"].append({"id": r[0], "status": r[1]})
    acc["syntheticAccountsBeforeW1"] = int(scalar("select count(*) from auth_users where email like "
                                                  f"'priv001a-%@priv001a.parkio.invalid' and created_at < '{w1}'::timestamptz"))
    rows = psql("select count(*), count(*) filter (where consumed_at is null) from registration_invites "
                f"where created_by = 'priv001a' and created_at >= '{w1}'::timestamptz")
    acc["invitesSinceW1"] = {"created": int(rows[0][0]), "unconsumed": int(rows[0][1])}
    p = principal_row(st)
    uid = p[0] if p else None
    if p:
        req = request_row(uid)
        acc["principal"] = {"id": uid, "status": p[1], "emailErased": p[2] == f"erased-{uid}@invalid.localhost",
                            "request": req[0] if req else None, "successAcks": req[1] if req else None}
    cond = f"auth_user_id <> '{uid}'::uuid and " if uid else ""
    acc["otherErasureRequestsSinceW1"] = int(scalar(f"select count(*) from erasure_requests where {cond}"
                                                    f"requested_at >= '{w1}'::timestamptz"))
    notes = []
    for a in acc["syntheticAccountsSinceW1"]:
        notes.append(f"ORPHAN: synthetic account {a['id']} is {a['status']} (not erased)")
    if acc["invitesSinceW1"]["unconsumed"]:
        notes.append(f"ORPHAN: {acc['invitesSinceW1']['unconsumed']} priv001a invite(s) created since W1 are unconsumed "
                     "(only their hash is stored; they expire after the invite TTL)")
    pr = acc["principal"]
    if pr and pr["request"] and pr["request"] != "COMPLETE":
        notes.append(f"PENDING: the erasure of {pr['id']} is {pr['request']} with {pr['successAcks']}/8 SUCCESS")
    if pr and pr["status"] == "ERASED" and not pr["emailErased"]:
        notes.append(f"ORPHAN: {pr['id']} is ERASED without its tombstone email")
    return acc, notes


# --- the window -----------------------------------------------------------------------------------------------------
def render_drift(services):
    rc, out, _ = compose("config", "--hash", "*", timeout=180)
    if rc != 0:
        raise Stop("the production wrapper's config --hash failed")
    hashes = dict(ln.split() for ln in out.splitlines() if len(ln.split()) == 2)
    cs = containers()
    return sorted(s for s in services if hashes.get(s) != cs[one(cs, s)]["configHash"])


def preflight(harness_dir, fault, copy_to, own_unit=None):
    """Read-only checks; copy_to receives the verified copies (root-only). Returns (problems, shown, values).
    own_unit: the window's own runner unit, when called from inside the window."""
    problems = []
    rc, head, _ = run(["git", "-c", f"safe.directory={RELEASE}", "-C", RELEASE, "rev-parse", "HEAD"], timeout=60)
    rc2, dirty, _ = run(["git", "-c", f"safe.directory={RELEASE}", "-C", RELEASE, "status", "--porcelain"], timeout=60)
    if head.strip() != PIN or dirty.strip():
        problems.append(f"the release checkout is not clean at the PIN ({head.strip()[:12] or '?'})")
    try:
        copy_verified(harness_dir or "/nonexistent", copy_to)
        rc, out, err = run(["bash", os.path.join(copy_to, "harness", CREATE), "--environment", "civo-production",
                            "--confirm-synthetic-only", "--dry-run-guards"], timeout=120)
        if rc != 0 or "PASS: dry-run guards" not in out:
            problems.append("the harness's own guards refuse this host: " + (out + err).strip().splitlines()[-1][:160]
                            if (out + err).strip() else "the harness's dry-run guards failed")
    except Stop as e:
        problems.append(str(e))
    services = APP_SERVICES + ("postgres-auth", "kafka")
    try:
        cs = containers()
        for svc in services:
            try:
                c = cs[one(cs, svc)]
                if c["status"] != "running" or c["health"] not in ("healthy", None):
                    problems.append(f"{svc} is {c['status']}/{c['health']}")
            except Stop as e:
                problems.append(str(e))
    except Stop as e:
        problems.append(str(e))
    try:
        drift = render_drift(["auth-service", "analytics-service"])
        if drift:
            problems.append(f"{', '.join(drift)} already differ from the rendered config; the window would deploy that")
    except Stop as e:
        problems.append(str(e))
    try:
        analytics_lag()
    except Stop as e:
        problems.append(str(e))
    values, plan = {}, []
    try:
        data = open(ENV_FILE, "rb").read()
        values, dup = env_values(data)
        try:
            plan = plan_changes(values)
        except Stop as e:
            problems.append(str(e))
        clash = sorted(dup & {k for k, _ in plan})
        if clash:
            problems.append(f"the env file defines {clash} more than once")
        if values.get(KEY_ERASURE, "false") not in ("false", ""):
            problems.append(f"{KEY_ERASURE} is already {values.get(KEY_ERASURE)!r}")
    except OSError:
        problems.append(f"cannot read {ENV_FILE}")
    if env_leftovers():
        problems.append(f"an interrupted write left {len(env_leftovers())} temporary env file(s) next to the env file "
                        "(they hold secrets): shred them first (RECOVERY.md section 7)")
    rc, out, _ = run([SYSTEMCTL, "list-units", "--all", "--plain", "--no-legend", "u05-priv001a-*"], timeout=60)
    live = [ln.split()[0] for ln in out.splitlines() if ln.strip() and " active " in f" {ln} " and ln.split()[0] != own_unit]
    if live:
        problems.append(f"window or guard units are still active: {live}")
    if fault not in ("analytics", "none"):
        problems.append(f"unknown --fault {fault!r}")
    shown = {k: ("<generated, 48 chars, never shown>" if v is None else v) for k, v in plan}
    return problems, shown, values


class Budget:
    def __init__(self):
        self.t_w1 = None

    def left(self):
        return RESTORE_BY - (time.time() - self.t_w1) if self.t_w1 else RESTORE_BY

    def need(self, seconds, what):
        if self.left() < seconds:
            raise Stop(f"deadline: {what} needs about {int(seconds)} s, {int(self.left())} s left before the restore "
                       "must begin")

    def cap(self, seconds):
        return max(1, min(seconds, int(self.left())))


def window(st, budget):
    me = os.path.join(run_dir(st["run"]), "window.py")
    log = os.path.join(run_dir(st["run"]), "window.log")
    say(f"W0 preflight and snapshot (read-only; fault: {st['fault']})")
    problems, shown, values = preflight(os.path.join(run_dir(st["run"]), "harness"), st["fault"],
                                        os.path.join(run_dir(st["run"]), "w0check"), own_unit=f"{UNIT}-{st['run']}.service")
    shutil.rmtree(os.path.join(run_dir(st["run"]), "w0check"), ignore_errors=True)
    if problems:
        raise Stop("preflight: " + "; ".join(problems))
    st["w0Values"] = {k: values.get(k, "(absent)") for k in (KEY_ERASURE, KEY_MODE, KEY_CREATION)}
    st["w0TokenSet"] = bool(values.get(KEY_TOKEN))
    st["planned"] = shown
    st["envShaW0"] = sha_file(os.path.realpath(ENV_FILE))
    st["envStat"] = env_stat(os.path.realpath(ENV_FILE))
    save_state(st)
    if snapshot("W0", st) != 0:
        raise Stop("the W0 snapshot failed")
    w0 = latest_snapshot(st, "W0")
    if w0.get("rendered") is None:
        raise Stop("the W0 snapshot has no rendered config")
    if {"auth-service", "analytics-service"} & set(w0.get("renderDrift") or []):
        raise Stop("auth-service or analytics-service differs from the rendered config at W0")
    st["erasureRequestsAtW0"] = int(scalar("select count(*) from erasure_requests"))
    st["lagW0"] = analytics_lag()
    save_state(st)

    say("W1 the window guard first, then the env change and auth-service")
    arm(f"{GUARD_WINDOW}-{st['run']}", GUARD_WINDOW_AT, log, sys.executable, "-I", me, "restore", "--guard",
        "--run", st["run"])
    st["guards"] = {"window": True}
    st["w1"] = now()
    budget.t_w1 = time.time()
    st["w1Epoch"] = budget.t_w1
    save_state(st)
    changed = apply_changes(st)
    say(f"  env keys changed: {', '.join(changed)} (values: " + ", ".join(f"{k}={v}" for k, v in shown.items()) + ")")
    recreate_auth({KEY_ERASURE: "true", KEY_CREATION: "true", KEY_MODE: "invite", KEY_TOKEN: "<set>"},
                  timeout=budget.cap(300))
    st["phase"] = "W1-done"
    save_state(st)

    say("W2 synthetic principal")
    budget.need(300, "creating the principal")
    creds = os.path.join(run_dir(st["run"]), "creds")
    st["credentials"] = creds
    save_state(st)
    rc, text = harness(st, CREATE, "--credentials-file", creds, "--evidence-file",
                       os.path.join(st["evidence"], "create.json"), timeout=budget.cap(600))
    m = re.search(r"register=PASS user_id=([0-9a-f-]{36})", text) or re.search(r"^auth_user_id=([0-9a-f-]{36})$", text, re.M)
    if m:
        st["authUserId"] = m.group(1)
        save_state(st)
    if rc != 0 or not st.get("authUserId"):
        raise Stop("creating the synthetic principal failed")
    st["phase"] = "W2-done"
    save_state(st)

    if st["fault"] == "analytics":
        say("W3 failure case: analytics-service stopped, DELETE, IN_PROGRESS must hold")
        budget.need(HOLD + 600, "the failure case and the recovery")
        arm(f"{GUARD_ANALYTICS}-{st['run']}", ANALYTICS_GUARD_AT, log, sys.executable, "-I", me, "restore",
            "--analytics-only", "--run", st["run"])
        st["guards"]["analytics"] = True
        st["analyticsStopped"] = True
        save_state(st)
        rc, _, _ = compose("stop", "analytics-service", timeout=120)
        if rc != 0:
            raise Stop(f"stop analytics-service failed (exit {rc})")
        ev = os.path.join(st["evidence"], "erase-in-progress.json")
        rc, text = harness(st, ERASE, "--credentials-file", creds, "--delete", "--expect", "IN_PROGRESS",
                           "--hold-seconds", str(HOLD), "--poll-seconds", HARNESS_POLL, "--window-start", st["w1"],
                           "--evidence-file", ev, timeout=budget.cap(HOLD + 240))
        st["deleteSent"] = "delete=ACCEPTED" in text
        save_state(st)
        if rc != 0:
            raise Stop("criterion 3 (IN_PROGRESS while analytics is down) did not hold")
        last = json.load(open(ev))["observations"][-1]
        if last.get("successCount") != 7 or last.get("missing") != ["analytics"] or last.get("request_status") != "IN_PROGRESS":
            raise Stop(f"criterion 3: expected 7/8 SUCCESS with only analytics missing, got {last.get('successCount')}/8 "
                       f"missing {last.get('missing')}")

        say("W4 recovery: analytics-service started, COMPLETE with 8/8 SUCCESS")
        if container("analytics-service")["status"] != "running":
            rc, _, _ = compose("start", "analytics-service", timeout=120)
            if rc != 0:
                raise Stop(f"start analytics-service failed (exit {rc})")
        else:
            say("  analytics-service was already started by its guard")
        disarm(f"{GUARD_ANALYTICS}-{st['run']}")
        st["guards"]["analytics"] = False
        st["analyticsStopSeconds"] = analytics_stop_seconds()
        save_state(st)
        say(f"  analytics-service was stopped for {st['analyticsStopSeconds']} s (limit {ANALYTICS_LIMIT} s; Docker State times)")
        if st["analyticsStopSeconds"] is None or st["analyticsStopSeconds"] > ANALYTICS_LIMIT:
            raise Stop("analytics-service was stopped longer than the approved limit, or the stop cannot be measured")
        wait_healthy("analytics-service", min(HEALTH_TIMEOUT, budget.cap(HEALTH_TIMEOUT)))
        expect_args = ("--expect", "COMPLETE")
    else:
        say("W3 DELETE with every participant running (criterion 2 only; analytics-service is never stopped)")
        budget.need(300, "the erasure")
        expect_args = ("--delete", "--expect", "COMPLETE")
    ev = os.path.join(st["evidence"], "erase-complete.json")
    rc, text = harness(st, ERASE, "--credentials-file", creds, *expect_args,
                       "--timeout-seconds", str(max(1, min(COMPLETE_TIMEOUT, budget.cap(COMPLETE_TIMEOUT) - 60))),
                       "--poll-seconds", HARNESS_POLL, "--window-start", st["w1"], "--evidence-file", ev,
                       timeout=budget.cap(COMPLETE_TIMEOUT + 120))
    st["deleteSent"] = st.get("deleteSent") or "delete=ACCEPTED" in text
    save_state(st)
    if rc != 0:
        raise Stop("criterion 2 (COMPLETE with 8/8 SUCCESS, account ERASED) did not hold")
    last = json.load(open(ev))["observations"][-1]
    if last.get("successCount") != 8 or last.get("auth_state") != "ERASED":
        raise Stop("criterion 2: the evidence does not show 8/8 SUCCESS and ERASED")
    st["phase"] = "W4-done"
    save_state(st)

    say("W5 inspection (read-only)")
    rc, text = harness(st, INSPECT, "--auth-user-id", st["authUserId"], timeout=budget.cap(180))
    m = re.search(r"\{.*\}", text, re.S)
    try:
        ins = json.loads(m.group(0)) if m else {}
    except ValueError:
        ins = {}
    st["inspect"] = {k: ins.get(k) for k in ("auth_state", "erasure_request_status", "participant_ack_count",
                                             "tombstone_count", "hard_delete_residue_count", "forbidden_user_id_hits")}
    save_state(st)
    if (rc != 0 or ins.get("auth_state") != "ERASED" or ins.get("erasure_request_status") != "COMPLETE"
            or ins.get("participant_ack_count") != 8 or ins.get("tombstone_count") != 1
            or ins.get("hard_delete_residue_count") != 0 or ins.get("forbidden_user_id_hits") != 0):
        raise Stop(f"the residue inspection failed: {st['inspect']}")
    st["phase"] = "W5-done"
    save_state(st)


# --- restore: every step on its own ---------------------------------------------------------------------------------
def restore(st):
    """Idempotent; never raises. Returns (ok, steps)."""
    signal.signal(signal.SIGTERM, signal.SIG_IGN)       # a second SIGTERM must not cut the restore short
    steps = []
    expect_w0 = None

    def step(name, fn):
        try:
            res, ok = fn(), True
        except Exception as e:      # recorded, and the next step still runs
            res, ok = (str(e) if isinstance(e, Stop) else f"unexpected {type(e).__name__}"), False
        steps.append({"step": name, "ok": ok, "result": res})
        say(f"  restore {name}: {'ok' if ok else 'FAILED'} - {res}")
        st["restoreSteps"] = steps
        save_state_quietly(st)

    def analytics_step():
        c = container("analytics-service")
        started = False
        if c["status"] != "running":
            rc, _, _ = compose("start", "analytics-service", timeout=120)
            if rc != 0:
                raise Stop(f"start analytics-service failed (exit {rc})")
            started = True
        wait_healthy("analytics-service", HEALTH_TIMEOUT)
        if st.get("analyticsStopped"):
            st["analyticsStopSeconds"] = analytics_stop_seconds()
        return ("started" if started else "running") + (f"; stopped for {st.get('analyticsStopSeconds')} s"
                                                        if st.get("analyticsStopped") else "")

    def principal_step():
        p = principal_row(st)
        if p is None:
            return "no synthetic principal exists"
        uid, status, _ = p
        req = request_row(uid)
        if req:
            st["deleteSent"] = True
            return f"erasure already requested ({req[0]}, {req[1]}/8 SUCCESS)"
        if status not in ("PENDING_VERIFICATION", "ACTIVE"):
            return f"principal {uid} is {status}; nothing to send"
        if not os.path.isfile(st.get("credentials") or ""):
            raise Stop(f"principal {uid} is {status} and the credentials file is gone: ORPHAN")
        rc, text = harness(st, ERASE, "--credentials-file", st["credentials"], "--delete", "--expect", "COMPLETE",
                           "--timeout-seconds", "240", "--poll-seconds", "10", timeout=420)
        st["deleteSent"] = st.get("deleteSent") or "delete=ACCEPTED" in text or request_row(uid) is not None
        if rc == 0:
            return f"principal {uid} erased canonically (COMPLETE)"
        if st["deleteSent"]:
            return f"DELETE sent for {uid}; not COMPLETE yet (observed again below)"
        raise Stop(f"the synthetic principal {uid} could not be erased (harness exit {rc})")

    def env_step():
        res = restore_env(st)
        st["envRestore"] = res
        if res != "not-changed" and env_stat(st["envPath"]) != st["envStat"]:
            raise Stop(f"the env file's mode or owner differ from W0: {env_stat(st['envPath'])}")
        return res

    def auth_step():
        nonlocal expect_w0
        if not st.get("patches"):
            return "not needed (the env file was never changed)"
        if st.get("envRestore") not in ("byte-identical", "already-restored"):
            raise Stop("skipped: the env file is not restored")
        w0 = st.get("w0Values", {})
        expect_w0 = {k: ("" if w0.get(k, "(absent)") == "(absent)" else w0[k]) for k in (KEY_ERASURE, KEY_MODE, KEY_CREATION)}
        expect_w0 = {k: v for k, v in expect_w0.items() if v}
        expect_w0[KEY_TOKEN] = "<set>" if st.get("w0TokenSet") else "<unset>"
        if auth_env_matches(expect_w0) and container("auth-service")["status"] == "running":
            wait_healthy("auth-service", HEALTH_TIMEOUT)
            return "already running the W0 settings"
        recreate_auth(expect_w0, timeout=300)
        return "recreated with the W0 settings"

    def completion_step():
        p = principal_row(st)
        if p is None:
            return "no principal"
        req = request_row(p[0])
        if not req or req[0] == "COMPLETE":
            return f"request {req[0] if req else 'none'}"
        if container("analytics-service")["status"] != "running":
            return f"request {req[0]}, {req[1]}/8 SUCCESS (analytics-service is not running)"
        rc, _ = harness(st, ERASE, "--credentials-file", st["credentials"], "--expect", "COMPLETE",
                        "--timeout-seconds", "180", "--poll-seconds", "10", timeout=300) \
            if os.path.isfile(st.get("credentials") or "") else (1, "")
        req = request_row(p[0])
        return f"request {req[0]}, {req[1]}/8 SUCCESS"

    def guards_step():
        for g in (GUARD_ANALYTICS, GUARD_WINDOW):
            disarm(f"{g}-{st['run']}")
        st["guards"] = {"window": False, "analytics": False}
        return "both guard timers inactive"

    step("analytics-service", analytics_step)
    step("synthetic principal", principal_step)
    step("env file", env_step)
    step("auth-service", auth_step)
    step("erasure completion", completion_step)
    step("guards", guards_step)
    return all(s["ok"] for s in steps), steps


def finish(st, ok, steps, window_ok, report_name="window-report.json"):
    say("W6 checks, snapshot, comparison and accounting")
    notes = []
    cmp_rc = None
    restored = {s["step"]: s["ok"] for s in steps}
    has_w0 = any(re.match(r"^W0-\d{8}T\d{6}Z\.json$", f) for f in os.listdir(st["evidence"]))
    if restored.get("env file") and restored.get("auth-service") and has_w0:
        if snapshot("W6", st) == 0:
            cmp_rc = snapshot("compare", st)
    elif not has_w0:
        notes.append("W6 comparison skipped: the window stopped before its W0 snapshot")
    else:
        notes.append("W6 comparison skipped: the env file or auth-service is not restored (RECOVERY.md)")
    lag = None
    if st.get("lagW0") is not None and not restored.get("analytics-service"):
        notes.append("analytics consumer lag not measured: analytics-service is not restored")
    if st.get("lagW0") is not None and restored.get("analytics-service"):
        end = time.time() + LAG_WAIT
        while True:
            try:
                lag = analytics_lag()
            except Stop:
                lag = None
            if lag is not None and lag <= st["lagW0"] + LAG_TOLERANCE or time.time() >= end:
                break
            time.sleep(POLL)
    st["lagW6"] = lag
    lag_ok = st.get("lagW0") is None or (lag is not None and lag <= st["lagW0"] + LAG_TOLERANCE)
    if not lag_ok and restored.get("analytics-service"):
        notes.append(f"analytics consumer lag {lag} did not return to the W0 level {st.get('lagW0')} within {LAG_WAIT} s")
    acc, acc_notes = None, []
    if st.get("w1"):
        try:
            acc, acc_notes = accounting(st)
        except Exception as e:
            acc_notes = [f"ACCOUNTING FAILED ({e if isinstance(e, Stop) else type(e).__name__}): RECOVERY.md section 6"]
    st["accounting"] = acc
    notes += acc_notes
    secrets_removed = False
    if st.get("envRestore") in ("byte-identical", "already-restored", "not-changed") and restored.get("env file"):
        creds_exist = os.path.isfile(st.get("credentials") or "")
        keep_creds = creds_exist and (acc is None or any(n.startswith(("ORPHAN: synthetic", "PENDING")) for n in acc_notes))
        shred([st.get("envBackup")] + ([] if keep_creds else [st.get("credentials")]))
        tmp = os.path.join(run_dir(st["run"]), "tmp")
        for root, _, files in os.walk(tmp):
            shred([os.path.join(root, f) for f in files])
        shutil.rmtree(tmp, ignore_errors=True)
        shred(env_leftovers())
        secrets_removed = not keep_creds
        if keep_creds:
            notes.append("the credentials file is kept for the open erasure (RECOVERY.md section 3)")
    st["secretsRemoved"] = secrets_removed
    passed = bool(window_ok and ok and cmp_rc == 0 and lag_ok and acc is not None and not acc_notes)
    st["verdict"] = "PASS" if passed else "FAIL"
    st["finishedAt"] = now()
    save_state_quietly(st)
    report = {k: st.get(k) for k in ("run", "fault", "w1", "phase", "verdict", "failure", "planned", "changedKeys",
                                     "envRestore", "envConflict", "restoreSteps", "accounting", "inspect", "deleteSent",
                                     "erasureRequestsAtW0", "analyticsStopSeconds", "lagW0", "lagW6", "secretsRemoved",
                                     "finishedAt")}
    report["compare"] = {0: "PASS", None: "NOT RUN"}.get(cmp_rc, "FAIL")
    report["notes"] = notes
    report["minutesFromW1"] = round((time.time() - st["w1Epoch"]) / 60, 1) if st.get("w1Epoch") else None
    path = os.path.join(st["evidence"], report_name)
    with open(path, "w") as f:
        json.dump(report, f, indent=2, sort_keys=True)
    os.chmod(path, 0o600)
    if not TEST:
        subprocess.run(["chown", "-R", f"{OWNER}:{OWNER}", st["evidence"]], check=False, stdin=subprocess.DEVNULL)
    for n in notes:
        say(f"  {n}")
    say(f"report: {path} sha256 {sha_file(path)}")
    return passed


def cmd_run(args):
    run_id = args["--run"]
    st = load_state(run_id)
    with open(os.path.join(run_dir(run_id), "runner.pid"), "w") as f:
        f.write(str(os.getpid()))
    budget = Budget()

    def on_term(sig, frame):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        raise Stop("SIGTERM received")
    signal.signal(signal.SIGTERM, on_term)
    window_ok = False
    try:
        window(st, budget)
        window_ok = True
    except BaseException as e:      # any failure, exception or signal restores
        st["failure"] = str(e) if isinstance(e, Stop) else f"unexpected {type(e).__name__}"
        say(f"STOP: {st['failure']}")
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    save_state_quietly(st)
    lock = acquire(os.path.join(run_dir(run_id), "run.lock"), 3 * 3600)
    say("restore (the runner holds the run lock)")
    ok, steps = restore(st)
    try:
        passed = finish(st, ok, steps, window_ok)
    except Exception as e:
        say(f"W6 FAILED ({e if isinstance(e, Stop) else type(e).__name__}): RECOVERY.md section 6")
        passed = False
    lock.close()
    say("U05 PRIV-001A WINDOW " + ("PASS" if passed else "FAIL"))
    return 0 if passed else 1


def guard_analytics(run_id):
    """The analytics guard: starts analytics-service if it is not running. No lock, no state write."""
    marker = os.path.join(run_dir(run_id), "analytics-guard.json")
    try:
        c = container("analytics-service")
        if c["status"] == "running":
            res = "analytics-service already running"
        else:
            rc, _, _ = compose("start", "analytics-service", timeout=120)
            res = "analytics-service started by its guard" if rc == 0 else f"start analytics-service failed (exit {rc})"
    except Stop as e:
        res = f"FAILED: {e}"
    with open(marker, "a") as f:
        f.write(json.dumps({"at": now(), "result": res}) + "\n")
    say(f"analytics guard: {res}")
    return 0 if not res.startswith(("FAILED", "start analytics-service failed")) else 1


def cmd_restore(args):
    run_id = args.get("--run") or latest_run()
    if not run_id:
        print("no window run to restore")
        return 0
    if args.get("--analytics-only"):
        return guard_analytics(run_id)
    unit = f"{UNIT}-{run_id}.service"
    guard = bool(args.get("--guard"))
    if not guard and unit_active(unit) and not args.get("--stop-window"):
        print("STOP: the window runner is still active and restores by itself. To stop it and restore now, add "
              "--stop-window.")
        return 1
    lockpath = os.path.join(run_dir(run_id), "run.lock")
    lock = acquire(lockpath, GUARD_LOCK_WAIT)
    if lock is None and unit_active(unit):
        say(f"{'guard' if guard else 'restore'}: the runner's restore did not finish within {GUARD_LOCK_WAIT} s; stopping it")
        run([SYSTEMCTL, "kill", "--signal=SIGKILL", unit], timeout=60)
        lock = acquire(lockpath, 60)
    if lock is None:
        print("STOP: another restore holds the run lock; nothing was done")
        return 1
    st = load_state(run_id)
    if not st.get("finishedAt") and unit_active(unit):
        run([SYSTEMCTL, "kill", "--signal=SIGKILL", unit], timeout=60)
        end = time.time() + 30
        while unit_active(unit) and time.time() < end:
            time.sleep(1)
        say(f"{'guard' if guard else 'restore'}: the window runner was stopped")
        st = load_state(run_id)                  # read again: the runner can no longer write it
    if st.get("finishedAt"):
        # The window already finished (a timer that fired late): check that everything is still restored, and keep
        # the window's own verdict and report.
        say(f"the window already finished at {st['finishedAt']} ({st.get('verdict')}); an idempotent check follows")
        ok, steps = restore(st)
        path = os.path.join(st["evidence"], f"restore-check-{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}.json")
        with open(path, "w") as f:
            json.dump({"run": run_id, "at": now(), "by": "guard" if guard else "manual", "steps": steps}, f, indent=2)
        os.chmod(path, 0o600)
        lock.close()
        say("U05 PRIV-001A RESTORE CHECK " + ("COMPLETE" if ok else "INCOMPLETE - follow RECOVERY.md"))
        return 0 if ok else 1
    st["failure"] = st.get("failure") or ("stopped by the window guard" if guard else "stopped by a manual restore")
    ok, steps = restore(st)
    passed = finish(st, ok, steps, window_ok=False)
    lock.close()
    say("U05 PRIV-001A RESTORE " + ("COMPLETE" if ok else "INCOMPLETE - follow RECOVERY.md"))
    return 0 if ok else 1


def cmd_preflight(args):
    fault = args.get("--fault") or "none"
    os.makedirs(PRIVATE, mode=0o700, exist_ok=True)
    tmpdir = os.path.join(PRIVATE, f"preflight-{os.getpid()}")
    try:
        problems, shown, values = preflight(args.get("--harness"), fault, tmpdir)
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)
    print(f"U05 PRIV-001A window preflight | {now()} | read-only | fault: {fault}")
    print("  planned env changes (restored at W6): " + ", ".join(f"{k}={v}" for k, v in shown.items()))
    print(f"  W0 values: {KEY_ERASURE}={values.get(KEY_ERASURE, '(absent)')}, {KEY_MODE}={values.get(KEY_MODE, '(absent)')}, "
          f"{KEY_CREATION}={values.get(KEY_CREATION, '(absent)')}, {KEY_TOKEN}={'set' if values.get(KEY_TOKEN) else 'unset'}")
    for p in problems:
        print(f"  PROBLEM {p}")
    print("PREFLIGHT " + ("PASS" if not problems else "FAIL"))
    return 0 if not problems else 1


def cmd_start(args):
    fault = args.get("--fault") or "none"
    run_id = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    d = run_dir(run_id)
    os.makedirs(PRIVATE, mode=0o700, exist_ok=True)
    os.chmod(PRIVATE, 0o700)
    os.makedirs(d, mode=0o700, exist_ok=False)
    problems, shown, values = preflight(os.path.abspath(args.get("--harness") or "/nonexistent"), fault, d)
    if problems:
        for p in problems:
            print(f"PROBLEM {p}")
        shutil.rmtree(d, ignore_errors=True)
        print("NOT STARTED")
        return 1
    shutil.copyfile(os.path.abspath(__file__), os.path.join(d, "window.py"))
    if sha_file(os.path.join(d, "window.py")) != sha_file(os.path.abspath(__file__)):
        raise Stop("the runner copy does not match")
    ev = os.path.join(EVIDENCE, run_id)
    os.makedirs(ev, exist_ok=True)
    os.chmod(EVIDENCE, 0o700)
    os.chmod(ev, 0o700)
    st = {"run": run_id, "fault": fault, "evidence": ev, "phase": "W0", "guards": {},
          "runnerSha": sha_file(os.path.join(d, "window.py"))}
    save_state(st)
    log = os.path.join(d, "window.log")
    rc, out, err = run([SYSTEMD_RUN, "--unit", f"{UNIT}-{run_id}", "--collect", "--property=KillMode=mixed",
                        f"--property=TimeoutStopSec={STOP_TIMEOUT}", f"--property=StandardOutput=append:{log}",
                        f"--property=StandardError=append:{log}", sys.executable, "-I", os.path.join(d, "window.py"),
                        "run", "--run", run_id], timeout=60)
    if rc != 0:
        print(f"could not start the window unit: {err.strip()[:200]}")
        return 1
    print(f"window {run_id} (fault: {fault}) started as {UNIT}-{run_id}.service; following {log}")
    print("(Ctrl-C or an SSH disconnect stops only this following; the window and its guards keep running)")
    if TEST:
        return 0
    return follow(log, f"{UNIT}-{run_id}.service")


def follow(log, unit):
    pos, final, started = 0, None, time.time()
    try:
        while final is None:
            time.sleep(2)
            try:
                with open(log, "rb") as f:
                    f.seek(pos)
                    chunk = f.read()
                    pos = f.tell()
            except OSError:
                chunk = b""
            text = chunk.decode("utf-8", "replace")
            if text:
                sys.stdout.write(text)
                sys.stdout.flush()
            m = re.search(r"U05 PRIV-001A WINDOW (PASS|FAIL)", text)
            if m:
                final = m.group(1)
            elif time.time() - started > 15 and not unit_active(unit):
                print("\nthe window unit ended without a final line (stopped or killed). The window guard restores if "
                      "needed; check: sudo python3 -I u05-priv001a-window.py status")
                return 1
    except KeyboardInterrupt:
        print("\nstopped following; check later: sudo python3 -I u05-priv001a-window.py status")
        return 0
    return 0 if final == "PASS" else 1


def cmd_status(args):
    run_id = args.get("--run") or latest_run()
    if not run_id:
        print("no window run")
        return 0
    st = load_state(run_id)
    show = {k: st.get(k) for k in ("run", "fault", "phase", "verdict", "failure", "w1", "changedKeys", "envRestore",
                                   "guards", "authUserId", "deleteSent", "accounting", "restoreSteps", "finishedAt")}
    show["units"] = {u: unit_active(u) for u in (f"{UNIT}-{run_id}.service", f"{GUARD_WINDOW}-{run_id}.timer",
                                                 f"{GUARD_ANALYTICS}-{run_id}.timer")}
    print(json.dumps(show, indent=2, sort_keys=True))
    return 0


def main():
    if not TEST and os.geteuid() != 0:
        print("STOP: run as root (sudo python3 -I ...)")
        return 1
    argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        return 1
    cmd, rest = argv[0], argv[1:]
    args, i = {}, 0
    while i < len(rest):
        if rest[i] in ("--harness", "--run", "--fault"):
            args[rest[i]] = rest[i + 1] if i + 1 < len(rest) else ""
            i += 2
        else:
            args[rest[i]] = True
            i += 1
    try:
        return {"preflight": cmd_preflight, "start": cmd_start, "run": cmd_run, "restore": cmd_restore,
                "status": cmd_status}.get(cmd, lambda a: (print(__doc__), 1)[1])(args)
    except Stop as e:
        print(f"STOP: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
