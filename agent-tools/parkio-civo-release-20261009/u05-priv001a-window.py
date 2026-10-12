#!/usr/bin/env python3
"""U05 PRIV-001A live window on parkio-civo-prod (Asana 1219100261876615, criteria 2 and 3).

It runs ONLY after the owner's scoped live-test decision (D-U05-2). Steps W0-W6 follow
agent-tools/parkio-u05-priv001a-civo-20261012/LIVE-TEST-SPEC.md.

  sudo python3 -I u05-priv001a-window.py preflight --harness DIR   read-only: checks and the exact planned changes
  sudo python3 -I u05-priv001a-window.py start --harness DIR       launches the window as a transient systemd unit, then
                                                                   follows its log; an SSH disconnect stops only the
                                                                   following, never the window
  sudo python3 -I u05-priv001a-window.py status                    the state file, without secrets
  sudo python3 -I u05-priv001a-window.py restore                   idempotent full restore (used by the guard and by hand)

Safety:
- Guards are armed BEFORE the first change. They are transient systemd timers, independent of the runner and of SSH.
  - The analytics guard starts analytics-service 600 s after it was stopped.
  - The window guard SIGKILLs the runner and runs `restore` 3600 s after W1.
  - The runner disarms both when it has restored everything.
- Any failed check, exception or SIGTERM restores at once: analytics started, env restored, auth-service recreated.
  - If the synthetic principal exists but was never deleted, it is first erased canonically while the flag is still on.
- The env file: only the window keys are changed, with an atomic write and the same mode and owner.
  - It restores byte-identically from the W1 backup when the file is still exactly what W1 wrote.
  - If anything else changed it meanwhile, it reverts ONLY the window keys and keeps the other changes.
  - If a window key itself was changed by someone else, it writes nothing, reports CONFLICT and leaves the file to the
    owner (RECOVERY.md).
- auth-service is recreated with --no-deps only. analytics-service is stopped and started (`docker compose start`, never
  `up`: during the window its rendered config differs, and `up` would recreate it).
- The operator token, when it has to be created, is generated here, written only to the env file, and never printed or
  stored elsewhere. The state file keeps the SHA-256 of the line, not the line.
Evidence (secret-free): ~civo/parkio-release-20261009/u05-window/<run>/. Private state, the env backup and the credentials
file live in /root/u05-priv001a-window/<run>/ (700/600). The backup and credentials are removed after a verified
restore.
"""
import fcntl, hashlib, json, os, re, secrets, shutil, signal, subprocess, sys, time

RELEASE = os.environ.get("U05R_RELEASE", "/opt/parkio-release")
PIN = os.environ.get("U05R_PIN", "f888a81b113c0c486ea0949067779443857e644d")
ENV_FILE = os.environ.get("U05R_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
PRIVATE = os.environ.get("U05R_PRIVATE", "/root/u05-priv001a-window")
EVIDENCE = os.environ.get("U05R_EVIDENCE", "/home/civo/parkio-release-20261009/u05-window")
OWNER = os.environ.get("U05R_OWNER", "civo")
SNAPSHOT = os.environ.get("U05R_SNAPSHOT", "/home/civo/parkio-release-20261009/tools/u05-window-snapshot.py")
DOCKER = os.environ.get("U05R_DOCKER", "docker")
SYSTEMD_RUN = os.environ.get("U05R_SYSTEMD_RUN", "systemd-run")
SYSTEMCTL = os.environ.get("U05R_SYSTEMCTL", "systemctl")
PROJECT = "parkio"
TEST = bool(os.environ.get("U05R_TEST"))
HOLD = int(os.environ.get("U05R_HOLD", "300"))
COMPLETE_TIMEOUT = int(os.environ.get("U05R_COMPLETE_TIMEOUT", "900"))
ANALYTICS_LIMIT = int(os.environ.get("U05R_ANALYTICS_LIMIT", "600"))
WINDOW_LIMIT = int(os.environ.get("U05R_WINDOW_LIMIT", "3600"))
HEALTH_TIMEOUT = int(os.environ.get("U05R_HEALTH_TIMEOUT", "180"))
POLL = int(os.environ.get("U05R_POLL", "5"))
UNIT = "u05-priv001a-window"
GUARD_ANALYTICS, GUARD_WINDOW = "u05-priv001a-guard-analytics", "u05-priv001a-guard-window"
HARNESS_FILES = ("scripts/acceptance/create-priv001-synthetic-principal.sh", "scripts/acceptance/erase-priv001-synthetic-principal.sh",
                 "scripts/acceptance/inspect-priv001-synthetic-residue.sh", "scripts/lib/priv001-synthetic.sh",
                 "scripts/lib/dark-gateway-url.sh")
KEY_ERASURE, KEY_MODE = "PARKIO_ACCOUNT_ERASURE_ENABLED", "PARKIO_REGISTRATION_MODE"
KEY_CREATION, KEY_TOKEN = "PARKIO_REGISTRATION_INVITE_CREATION_ENABLED", "PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN"
PARTICIPANTS = ("auth-service", "user-service", "parking-service", "media-service", "moderation-service",
                "gamification-service", "notification-service", "analytics-service", "ai-validation-service", "gateway-service")
STATE_NAME = "state.json"


class Stop(Exception):
    pass


def now():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def sha(b):
    return hashlib.sha256(b).hexdigest()


def run(args, cwd=None, env=None, timeout=1200, inp=None):
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=timeout, cwd=cwd, env=env, input=inp)
        return p.returncode, p.stdout, p.stderr
    except (OSError, subprocess.TimeoutExpired) as e:
        return 127, "", str(e)


def say(msg):
    print(f"{now()} {msg}", flush=True)


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


# --- the env file ---------------------------------------------------------------------------------------------------
LINE = re.compile(rb"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*?)(\r?\n)?$")


def parse(data):
    lines = data.splitlines(keepends=True)
    keyed = []
    for i, ln in enumerate(lines):
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


def atomic_write(path, data, like):
    st = os.stat(like)
    d = os.path.dirname(path)
    tmp = os.path.join(d, f".{os.path.basename(path)}.u05-{os.getpid()}")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.write(fd, data)
        os.fsync(fd)
    finally:
        os.close(fd)
    os.chmod(tmp, st.st_mode & 0o7777)
    if not TEST:
        os.chown(tmp, st.st_uid, st.st_gid)
    os.replace(tmp, path)


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
    values, dup = env_values(data)
    plan = plan_changes(values)
    clash = sorted(dup & {k for k, _ in plan})
    if clash:
        raise Stop(f"the env file defines {clash} more than once; refusing to edit it")
    backup = os.path.join(run_dir(st["run"]), "env.backup")
    shutil.copy2(path, backup)
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
            eol = b"\r\n" if orig.endswith(b"\r\n") else b"\n"
            new = new[:-1] + eol
            lines[i] = new
            patches.append({"key": key, "original": orig.decode("utf-8", "replace") if key != KEY_TOKEN else None,
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
    atomic_write(path, out, path)
    return [p["key"] for p in patches]


def restore_env(st):
    """Returns 'byte-identical', 'targeted', 'unchanged' or 'conflict'."""
    if not st.get("patches"):
        return "unchanged"
    path = st["envPath"]
    cur = open(path, "rb").read()
    if sha(cur) == st["envSha0"]:
        return "byte-identical"
    if sha(cur) == st["envSha1"]:
        atomic_write(path, open(st["envBackup"], "rb").read(), path)
        if sha(open(path, "rb").read()) != st["envSha0"]:
            raise Stop("the restored env file does not match the W1 backup")
        return "byte-identical"
    lines = cur.splitlines(keepends=True)
    conflicts = []
    for p in st["patches"]:
        hits = [i for i, ln in enumerate(lines) if sha(ln) == p["newSha"]]
        if len(hits) != 1:
            conflicts.append(p["key"])
    if conflicts:
        return "conflict:" + ",".join(conflicts)
    for p in st["patches"]:
        i = next(i for i, ln in enumerate(lines) if sha(ln) == p["newSha"])
        if p["appended"]:
            lines.pop(i)
        else:
            orig = open(st["envBackup"], "rb").read().splitlines(keepends=True)
            match = [ln for ln in orig if sha(ln) == p["originalSha"]]
            if len(match) != 1:
                return "conflict:" + p["key"]
            lines[i] = match[0]
    atomic_write(path, b"".join(lines), path)
    return "targeted"


# --- compose, containers, guards ------------------------------------------------------------------------------------
def compose(*args):
    env = dict(os.environ, PARKIO_ENV_FILE=ENV_FILE)
    return run(["./scripts/parkio-prod-compose.sh", *args], cwd=RELEASE, env=env)


def containers():
    rc, out, _ = run([DOCKER, "ps", "-a", "--format", "{{.ID}}"])
    if rc != 0:
        raise Stop("docker ps failed")
    ids = out.split()
    if not ids:
        return {}
    rc, out, _ = run([DOCKER, "inspect", *ids])
    if rc != 0:
        raise Stop("docker inspect failed")
    res = {}
    for c in json.loads(out):
        lab = (c.get("Config") or {}).get("Labels") or {}
        res[c["Name"].lstrip("/")] = {"id": c["Id"], "service": lab.get("com.docker.compose.service"),
                                      "project": lab.get("com.docker.compose.project"),
                                      "status": (c.get("State") or {}).get("Status"),
                                      "health": ((c.get("State") or {}).get("Health") or {}).get("Status"),
                                      "env": (c.get("Config") or {}).get("Env") or []}
    return res


def one(cs, service):
    hits = [n for n, c in cs.items() if c["project"] == PROJECT and c["service"] == service]
    if len(hits) != 1:
        raise Stop(f"expected exactly one {service} container, found {len(hits)}")
    return hits[0]


def wait_healthy(service, deadline_s=HEALTH_TIMEOUT):
    end = time.time() + deadline_s
    while time.time() < end:
        cs = containers()
        c = cs[one(cs, service)]
        if c["status"] == "running" and c["health"] in ("healthy", None):
            return
        time.sleep(POLL)
    raise Stop(f"{service} did not become healthy within {deadline_s} s")


def recreate_auth(expect):
    before = containers()
    rc, out, err = compose("up", "-d", "--no-deps", "auth-service")
    if rc != 0:
        raise Stop(f"recreating auth-service failed (exit {rc})")
    wait_healthy("auth-service")
    after = containers()
    others = [n for n in before if before[n]["service"] != "auth-service" and n in after and before[n]["id"] != after[n]["id"]]
    if others or set(before) != set(after):
        raise Stop(f"recreating auth-service changed other containers: {others or sorted(set(before) ^ set(after))}")
    env = dict(e.split("=", 1) for e in after[one(after, "auth-service")]["env"] if "=" in e)
    for k, v in expect.items():
        got = env.get(k, "")
        if v == "<set>":
            ok = bool(got)
        elif v == "<unset>":
            ok = not got
        else:
            ok = got == v
        if not ok:
            raise Stop(f"auth-service does not run with the expected {k}")


def arm(guard, seconds, *cmd):
    rc, out, err = run([SYSTEMD_RUN, "--unit", guard, f"--on-active={seconds}", "--timer-property=AccuracySec=1s",
                        "--collect", *cmd])
    if rc != 0:
        raise Stop(f"cannot arm {guard}: {err.strip()[:160]}")


def disarm(guard):
    run([SYSTEMCTL, "stop", f"{guard}.timer"])


def analytics(action):
    rc, out, err = compose(action, "analytics-service")
    if rc != 0:
        raise Stop(f"{action} analytics-service failed (exit {rc})")


def analytics_running():
    cs = containers()
    return cs[one(cs, "analytics-service")]["status"] == "running"


def harness(st, script, *args):
    env = dict(os.environ, TMPDIR=run_dir(st["run"]))
    path = os.path.join(st["harness"], script)
    rc, out, err = run(["bash", path, "--environment", "civo-production", "--confirm-synthetic-only", *args],
                       env=env, timeout=COMPLETE_TIMEOUT + 300)
    text = (out + err).strip()
    say(f"  {os.path.basename(script)} exit {rc}")
    for line in text.splitlines():
        if not re.search(r"(?i)(password|access_token|refresh_token|bearer|eyJ)", line):
            print(f"    {line}", flush=True)
    return rc, text


def psql(sql):
    cs = containers()
    pg = one(cs, "postgres-auth")
    rc, out, err = run([DOCKER, "exec", "-i", pg, "sh", "-c",
                        'exec psql -X -q -At --set=ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"', "psql", sql])
    if rc != 0:
        raise Stop("a read-only auth database query failed")
    return out.strip()


def snapshot(label, st):
    env = dict(os.environ, U05W_OUT=st["evidence"], U05W_ENV_FILE=ENV_FILE, U05W_RELEASE=RELEASE, U05W_DOCKER=DOCKER)
    rc, out, err = run([sys.executable, "-I", SNAPSHOT, *(["snapshot", label] if label != "compare" else ["compare"])], env=env)
    for line in (out + err).strip().splitlines():
        print(f"    {line}", flush=True)
    return rc


# --- the window -----------------------------------------------------------------------------------------------------
def preflight(harness_dir):
    problems = []
    rc, head, _ = run(["git", "-c", f"safe.directory={RELEASE}", "-C", RELEASE, "rev-parse", "HEAD"])
    rc2, dirty, _ = run(["git", "-c", f"safe.directory={RELEASE}", "-C", RELEASE, "status", "--porcelain"])
    if head.strip() != PIN or dirty.strip():
        problems.append(f"the release checkout is not clean at the PIN ({head.strip()[:12] or '?'})")
    for f in HARNESS_FILES:
        if not os.path.isfile(os.path.join(harness_dir or "", f)):
            problems.append(f"harness file missing: {f}")
    if not os.path.isfile(SNAPSHOT):
        problems.append(f"snapshot tool missing: {SNAPSHOT}")
    cs = containers()
    for svc in PARTICIPANTS + ("postgres-auth",):
        try:
            c = cs[one(cs, svc)]
            if c["status"] != "running" or c["health"] not in ("healthy", None):
                problems.append(f"{svc} is {c['status']}/{c['health']}")
        except Stop as e:
            problems.append(str(e))
    data = open(ENV_FILE, "rb").read()
    values, dup = env_values(data)
    try:
        plan = plan_changes(values)
    except Stop as e:
        problems.append(str(e))
        plan = []
    clash = sorted(dup & {k for k, _ in plan})
    if clash:
        problems.append(f"the env file defines {clash} more than once")
    if values.get(KEY_ERASURE, "false") not in ("false", ""):
        problems.append(f"{KEY_ERASURE} is already {values.get(KEY_ERASURE)!r}")
    rc, out, _ = run([SYSTEMCTL, "list-units", "--all", "--plain", "--no-legend", "u05-priv001a-*"])
    live = [ln.split()[0] for ln in out.splitlines() if ln.strip() and " active " in f" {ln} "]
    if live:
        problems.append(f"window or guard units are still active: {live}")
    shown = {k: ("<generated, 48 chars, never shown>" if v is None else v) for k, v in plan}
    return problems, shown, values


def window(st):
    t_w1 = None

    def check_time():
        if t_w1 and time.time() - t_w1 > WINDOW_LIMIT - 300:
            raise Stop("the window is within 5 minutes of its 60-minute limit; restoring")

    say("W0 preflight and snapshot (read-only)")
    problems, shown, values = preflight(st["harness"])
    if problems:
        raise Stop("preflight: " + "; ".join(problems))
    st["w0Values"] = {k: values.get(k, "(absent)") for k in (KEY_ERASURE, KEY_MODE, KEY_CREATION)}
    st["w0TokenSet"] = bool(values.get(KEY_TOKEN))
    st["planned"] = shown
    save_state(st)
    if snapshot("W0", st) != 0:
        raise Stop("the W0 snapshot failed")
    other0 = psql("select count(*) from erasure_requests")
    st["erasureRequestsAtW0"] = int(other0)
    save_state(st)

    say("W1 guards first, then the env change and auth-service")
    me = os.path.abspath(__file__)
    arm(GUARD_WINDOW, WINDOW_LIMIT, sys.executable, "-I", me, "restore", "--guard", "--run", st["run"])
    st["guards"] = {"window": True}
    save_state(st)
    t_w1 = time.time()
    st["w1"] = now()
    save_state(st)
    changed = apply_changes(st)
    say(f"  env keys changed: {', '.join(changed)} (values: " +
        ", ".join(f"{k}={v}" for k, v in shown.items()) + ")")
    expect = {KEY_ERASURE: "true", KEY_CREATION: "true", KEY_MODE: "invite", KEY_TOKEN: "<set>"}
    recreate_auth(expect)
    st["phase"] = "W1-done"
    save_state(st)

    say("W2 synthetic principal")
    check_time()
    creds = os.path.join(run_dir(st["run"]), "creds")
    st["credentials"] = creds
    save_state(st)
    rc, text = harness(st, HARNESS_FILES[0], "--credentials-file", creds,
                       "--evidence-file", os.path.join(st["evidence"], "create.json"))
    m = re.search(r"auth_user_id=([0-9a-f-]{36})", text)
    if m:
        st["authUserId"] = m.group(1)
        save_state(st)
    if rc != 0 or not m:
        raise Stop("creating the synthetic principal failed")
    st["phase"] = "W2-done"
    save_state(st)

    say("W3 failure case: analytics-service stopped, DELETE, IN_PROGRESS must hold")
    check_time()
    arm(GUARD_ANALYTICS, ANALYTICS_LIMIT, sys.executable, "-I", me, "restore", "--analytics-only", "--run", st["run"])
    st["guards"]["analytics"] = True
    st["analyticsStoppedAt"] = time.time()
    save_state(st)
    analytics("stop")
    rc, text = harness(st, HARNESS_FILES[1], "--credentials-file", creds, "--delete", "--expect", "IN_PROGRESS",
                       "--hold-seconds", str(HOLD), "--poll-seconds", "15", "--window-start", st["w1"],
                       "--evidence-file", os.path.join(st["evidence"], "erase-in-progress.json"))
    st["deleteSent"] = "delete=ACCEPTED" in text
    save_state(st)
    if rc != 0:
        raise Stop("criterion 3 (IN_PROGRESS while analytics is down) did not hold")

    say("W4 recovery: analytics-service started, COMPLETE with 8/8 SUCCESS")
    analytics("start")
    st["analyticsStartedAt"] = time.time()
    disarm(GUARD_ANALYTICS)
    st["guards"]["analytics"] = False
    save_state(st)
    stopped_for = st["analyticsStartedAt"] - st["analyticsStoppedAt"]
    say(f"  analytics-service was stopped for {int(stopped_for)} s (limit {ANALYTICS_LIMIT} s)")
    if stopped_for > ANALYTICS_LIMIT:
        raise Stop("analytics-service was stopped longer than the approved limit")
    wait_healthy("analytics-service")
    rc, text = harness(st, HARNESS_FILES[1], "--credentials-file", creds, "--expect", "COMPLETE",
                       "--timeout-seconds", str(COMPLETE_TIMEOUT), "--poll-seconds", "15", "--window-start", st["w1"],
                       "--evidence-file", os.path.join(st["evidence"], "erase-complete.json"))
    if rc != 0:
        raise Stop("criterion 2 (COMPLETE with 8/8 SUCCESS, account ERASED) did not hold")
    st["phase"] = "W4-done"
    save_state(st)

    say("W5 inspection and cleanup proof (read-only)")
    rc, text = harness(st, HARNESS_FILES[2], "--auth-user-id", st["authUserId"])
    uid = st["authUserId"]
    proof = {
        "syntheticEmailsLeft": int(psql("select count(*) from auth_users where email like 'priv001a-%@priv001a.parkio.invalid'")),
        "principalState": psql(f"select status from auth_users where id = '{uid}'::uuid"),
        "principalEmailErased": psql(f"select (email like 'erased-%@invalid.localhost')::text from auth_users where id = '{uid}'::uuid"),
        "invitesCreatedInWindow": int(psql(f"select count(*) from registration_invites where created_by = 'priv001a' and created_at >= '{st['w1']}'::timestamptz")),
        "invitesUnconsumed": int(psql(f"select count(*) from registration_invites where created_by = 'priv001a' and created_at >= '{st['w1']}'::timestamptz and consumed_at is null")),
        "otherErasureRequestsSinceW1": int(psql(f"select count(*) from erasure_requests where auth_user_id <> '{uid}'::uuid and requested_at >= '{st['w1']}'::timestamptz")),
    }
    st["cleanupProof"] = proof
    save_state(st)
    say("  cleanup proof: " + ", ".join(f"{k}={v}" for k, v in proof.items()))
    if (rc != 0 or proof["syntheticEmailsLeft"] != 0 or proof["principalState"] != "ERASED" or proof["principalEmailErased"] != "true"
            or proof["invitesCreatedInWindow"] != 1 or proof["invitesUnconsumed"] != 0):
        raise Stop("the cleanup proof failed")
    st["phase"] = "W5-done"
    save_state(st)


def restore(st, analytics_only=False):
    """Idempotent. Returns (ok, notes)."""
    notes = []
    if not analytics_running():
        analytics("start")
        notes.append("analytics-service started")
        st["analyticsStartedAt"] = st.get("analyticsStartedAt") or time.time()
    disarm(GUARD_ANALYTICS)
    st.setdefault("guards", {})["analytics"] = False
    save_state(st)
    if analytics_only:
        return True, notes
    if st.get("authUserId") and not st.get("deleteSent") and st.get("credentials") and os.path.isfile(st["credentials"]):
        notes.append("the synthetic principal was never deleted: erasing it canonically while the flag is still on")
        rc, _ = harness(st, HARNESS_FILES[1], "--credentials-file", st["credentials"], "--delete", "--expect", "COMPLETE",
                        "--timeout-seconds", str(COMPLETE_TIMEOUT), "--poll-seconds", "15")
        st["deleteSent"] = rc == 0
        save_state(st)
        if rc != 0:
            notes.append("ORPHAN: the synthetic principal could not be erased; record it for the next window")
    mode = restore_env(st)
    st["envRestore"] = mode
    save_state(st)
    if mode.startswith("conflict"):
        notes.append(f"CONFLICT: a window key was changed by someone else ({mode.split(':', 1)[1]}); the env file is "
                     "left as it is and auth-service is not recreated - follow RECOVERY.md")
        return False, notes
    notes.append(f"env file restore: {mode}")
    if mode != "unchanged" or st.get("phase") not in (None, "W0"):
        w0 = st.get("w0Values", {})
        expect = {KEY_ERASURE: w0.get(KEY_ERASURE, "(absent)"), KEY_MODE: w0.get(KEY_MODE, "(absent)"),
                  KEY_CREATION: w0.get(KEY_CREATION, "(absent)"), KEY_TOKEN: "<set>" if st.get("w0TokenSet") else "<unset>"}
        expect = {k: ("" if v == "(absent)" else v) for k, v in expect.items()}
        if st.get("patches"):
            recreate_auth({k: v for k, v in expect.items() if v != ""} if expect else {})
            notes.append("auth-service recreated with the restored settings")
    disarm(GUARD_WINDOW)
    st["guards"]["window"] = False
    save_state(st)
    return True, notes


def finish(st, ok, notes):
    say("W6 restore checks, snapshot and comparison")
    for n in notes:
        say(f"  {n}")
    cmp_rc = 1
    if ok:
        if snapshot("W6", st) == 0:
            cmp_rc = snapshot("compare", st)
    if ok and cmp_rc == 0 and st.get("envRestore") in ("byte-identical", "unchanged"):
        for f in (st.get("envBackup"), st.get("credentials")):
            if f and os.path.isfile(f):
                os.remove(f)
        st["secretsRemoved"] = True
    elif ok and st.get("envRestore") == "targeted":
        notes.append("the env file kept another change made during the window: the comparison reports it; review RECOVERY.md")
    save_state(st)
    report = {k: st.get(k) for k in ("run", "w1", "phase", "verdict", "planned", "changedKeys", "envRestore", "cleanupProof",
                                     "deleteSent", "erasureRequestsAtW0", "secretsRemoved")}
    report["analyticsStoppedSeconds"] = (int(st["analyticsStartedAt"] - st["analyticsStoppedAt"])
                                         if st.get("analyticsStoppedAt") and st.get("analyticsStartedAt") else None)
    report["compare"] = "PASS" if cmp_rc == 0 else "FAIL"
    report["notes"] = notes
    path = os.path.join(st["evidence"], "window-report.json")
    with open(path, "w") as f:
        json.dump(report, f, indent=2, sort_keys=True)
    os.chmod(path, 0o600)
    if not TEST:
        subprocess.run(["chown", "-R", f"{OWNER}:{OWNER}", st["evidence"]], check=False)
    say(f"report: {path} sha256 {sha(open(path, 'rb').read())}")
    return cmp_rc == 0


def cmd_run(args):
    run_id = args["--run"]
    st = load_state(run_id)
    stop_flag = {"term": False}

    def on_term(sig, frame):
        stop_flag["term"] = True
        raise Stop("SIGTERM received")
    signal.signal(signal.SIGTERM, on_term)
    ok_window = False
    try:
        window(st)
        ok_window = True
        st["verdict"] = "PASS"
    except Exception as e:      # any failure restores
        st["verdict"] = "FAIL"
        st["failure"] = f"{type(e).__name__}: {e}" if isinstance(e, Stop) else type(e).__name__
        say(f"STOP: {st['failure']}")
    save_state(st)
    try:
        ok, notes = restore(st)
    except Exception as e:
        ok, notes = False, [f"RESTORE FAILED ({type(e).__name__}: {e}); the guard and RECOVERY.md remain"]
    passed = finish(st, ok, notes) and ok and ok_window
    say("U05 PRIV-001A WINDOW " + ("PASS" if passed else "FAIL"))
    return 0 if passed else 1


def cmd_restore(args):
    run_id = args.get("--run") or latest_run()
    if not run_id:
        print("no window run to restore")
        return 0
    st = load_state(run_id)
    lock = open(os.path.join(run_dir(run_id), "restore.lock"), "w")
    fcntl.flock(lock, fcntl.LOCK_EX)
    if "--guard" in args and not args.get("--analytics-only"):
        run([SYSTEMCTL, "kill", "--signal=SIGKILL", f"{UNIT}-{run_id}.service"])
        say("guard: the window runner was stopped")
    ok, notes = restore(st, analytics_only=bool(args.get("--analytics-only")))
    for n in notes:
        say(n)
    if args.get("--analytics-only"):
        return 0 if ok else 1
    passed = finish(st, ok, notes)
    say("U05 PRIV-001A RESTORE " + ("PASS" if ok and passed else "INCOMPLETE - follow RECOVERY.md"))
    return 0 if ok and passed else 1


def cmd_preflight(args):
    problems, shown, values = preflight(args.get("--harness"))
    print(f"U05 PRIV-001A window preflight | {now()} | read-only")
    print("  planned env changes (restored at W6): " + ", ".join(f"{k}={v}" for k, v in shown.items()))
    print(f"  W0 values: {KEY_ERASURE}={values.get(KEY_ERASURE, '(absent)')}, {KEY_MODE}={values.get(KEY_MODE, '(absent)')}, "
          f"{KEY_CREATION}={values.get(KEY_CREATION, '(absent)')}, {KEY_TOKEN}={'set' if values.get(KEY_TOKEN) else 'unset'}")
    for p in problems:
        print(f"  PROBLEM {p}")
    print("PREFLIGHT " + ("PASS" if not problems else "FAIL"))
    return 0 if not problems else 1


def cmd_start(args):
    harness_dir = os.path.abspath(args.get("--harness") or "")
    problems, shown, values = preflight(harness_dir)
    if problems:
        for p in problems:
            print(f"PROBLEM {p}")
        print("NOT STARTED")
        return 1
    run_id = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    os.makedirs(run_dir(run_id), mode=0o700, exist_ok=False)
    os.chmod(PRIVATE, 0o700)
    ev = os.path.join(EVIDENCE, run_id)
    os.makedirs(ev, exist_ok=True)
    os.chmod(EVIDENCE, 0o700)
    os.chmod(ev, 0o700)
    st = {"run": run_id, "harness": harness_dir, "evidence": ev, "phase": "W0", "guards": {}}
    save_state(st)
    log = os.path.join(run_dir(run_id), "window.log")
    me = os.path.abspath(__file__)
    rc, out, err = run([SYSTEMD_RUN, "--unit", f"{UNIT}-{run_id}", "--collect", f"--property=StandardOutput=append:{log}",
                        f"--property=StandardError=append:{log}", sys.executable, "-I", me, "run", "--run", run_id])
    if rc != 0:
        print(f"could not start the window unit: {err.strip()[:200]}")
        return 1
    print(f"window {run_id} started as {UNIT}-{run_id}.service; following {log}")
    print("(Ctrl-C or an SSH disconnect stops only this following; the window and its guards keep running)")
    if TEST:
        return 0
    pos, final = 0, None
    try:
        while final is None:
            time.sleep(2)
            try:
                with open(log, "rb") as f:
                    f.seek(pos)
                    chunk = f.read()
                    pos = f.tell()
            except OSError:
                continue
            text = chunk.decode("utf-8", "replace")
            if text:
                sys.stdout.write(text)
                sys.stdout.flush()
            m = re.search(r"U05 PRIV-001A WINDOW (PASS|FAIL)", text)
            if m:
                final = m.group(1)
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
    show = {k: st.get(k) for k in ("run", "phase", "verdict", "failure", "w1", "changedKeys", "envRestore", "guards",
                                   "authUserId", "deleteSent", "cleanupProof")}
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
        if rest[i] in ("--harness", "--run"):
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
