#!/usr/bin/env python3
"""N4 - targeted push-keys recovery for notification-service (owner decision 4, 2026-10-10: "push recovery = targeted change
of the push keys (not the whole old env), explain the recreation needed").

Run as civo (the env file's owner) on parkio-civo-prod:
  python3 -I n4-push-recovery.py check                       read-only; before D7 and at any time
  python3 -I n4-push-recovery.py disable --push-incident     only after D7, in a push incident the owner approved

check - nothing is written, created, started or stopped:
  A. The env file: mode and owner; PARKIO_PUSH_DELIVERY_PROVIDER and PARKIO_PUSH_DELIVERY_ENABLED (their values are not
     secret), PARKIO_EXPO_ACCESS_TOKEN (set / empty / absent only) and how many lines each has; the HMAC keys
     PARKIO_LOGIN_THROTTLE_HMAC_KEY and PARKIO_LOGIN_THROTTLE_HMAC_KEY_PREVIOUS (set / absent only), which the change leaves
     untouched.
  B. The backups <env file>.bak-*: name, mode and their push keys (same classification). P3c's backup holds no token.
  C. The running notification-service: container, start time, image, config hash, and its push settings as the container
     has them (from its environment; same classification).
  D. The release model (docker compose config, read-only): notification-service's push settings and config hash, and
     whether the running container is the release image parkio/notification-service:sha-<PIN8> that D7 builds.
  E. The change, simulated without writing anything: the release model rendered with PARKIO_PUSH_DELIVERY_ENABLED=false
     given to Compose for interpolation (the env file is not copied). notification-service must get enabled=false with
     provider and token unchanged, and it must be the only service whose config hash changes.
disable --push-incident - the recovery, for the release's notification-service only:
  1. The running notification-service must be the release image built by D7 (an older image on the migrated V15 schema is
     not covered by this tool: owner decision). The env file must hold PARKIO_PUSH_DELIVERY_ENABLED on at most one
     standard line.
  2. Backup <env file>.bak-n4-<stamp>: identical content, mode, owner, group and timestamps.
  3. Writes only PARKIO_PUSH_DELIVERY_ENABLED=false (in place, or appended if absent; atomic rename, same mode and owner).
     Every other line stays byte-identical, otherwise the backup is restored at once. The token, the provider and the HMAC
     keys stay as they are.
  4. The release model must change notification-service only.
  5. Recreation, because a container reads its environment only when it is created: the production wrapper from the
     release checkout recreates notification-service alone (up -d --no-build --no-deps notification-service; the same
     release image, so Flyway has nothing to apply). Then: healthy within 420 s, its environment has delivery disabled with
     provider and token unchanged, and no other container of the project changed.
  Undo: PARKIO_PUSH_DELIVERY_ENABLED back to true (or the backup copied back), then the same recreation.
Report: ~/parkio-release-20261009/N4-<mode>-<stamp>.txt (mode 600). No secret value is printed.
"""
import json
import os
import pwd
import re
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone

PIN = "f888a81b113c0c486ea0949067779443857e644d"
LIVE = os.environ.get("N4_LIVE", "/opt/parkio")                      # overrides: self-test only
REL = os.environ.get("N4_RELEASE", "/opt/parkio-release")
ENV_FILE = os.environ.get("N4_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
PROJECT = os.environ.get("N4_PROJECT", "parkio")
OUT = os.environ.get("N4_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009"))
HEALTH_TIMEOUT = int(os.environ.get("N4_HEALTH_TIMEOUT", "420"))
SERVICE = "notification-service"
RELEASE_TAG = os.environ.get("N4_RELEASE_TAG", f"parkio/{SERVICE}:sha-{PIN[:8]}")
WRAPPER = "./scripts/parkio-prod-compose.sh"
CIVO_TRIGGER = "docker/docker-compose.azure-hosted-beta.yml"
CIVO_OVERLAY = "docker/docker-compose.civo-alertmanager.yml"
PROVIDER, ENABLED, TOKEN = "PARKIO_PUSH_DELIVERY_PROVIDER", "PARKIO_PUSH_DELIVERY_ENABLED", "PARKIO_EXPO_ACCESS_TOKEN"
PUSH_KEYS = (PROVIDER, ENABLED, TOKEN)
HMAC_KEYS = ("PARKIO_LOGIN_THROTTLE_HMAC_KEY", "PARKIO_LOGIN_THROTTLE_HMAC_KEY_PREVIOUS")
COMMENT = b"# Push delivery disabled by N4 (owner decision 4, push incident)"

REPORT = []
GATES = []
STAMP = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
MODE = "?"
URL = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://[^\s'\"]+")
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")


def say(line=""):
    line = EMAIL.sub("<email>", URL.sub("<url>", line))
    print(line, flush=True)
    REPORT.append(line)


def gate(name, ok, detail=""):
    GATES.append((name, ok))
    say(f"  {'PASS' if ok else 'FAIL'} {name}{': ' + detail if detail else ''}")
    return ok


def save():
    os.makedirs(OUT, mode=0o700, exist_ok=True)
    path = os.path.join(OUT, f"N4-{MODE}-{STAMP}.txt")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write("\n".join(REPORT) + "\n")
    print(f"report saved: {path}")


def stop(message, changed=False):
    say(f"STOP: {message}")
    say(f"N4 {MODE} STOPPED; " + ("see above for the state of the env file and the container" if changed
                                  else "nothing was changed"))
    save()
    sys.exit(1)


def base_env(**extra):
    """The process environment without the push keys (a value exported in the shell would override the env file in
    Compose interpolation), plus the given overrides."""
    env = {k: v for k, v in os.environ.items() if k not in PUSH_KEYS}
    env.update(extra)
    return env


def run(cmd, cwd=None, timeout=300, env=None):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, cwd=cwd, timeout=timeout, stdin=subprocess.DEVNULL,
                           env=env if env is not None else base_env())
    except FileNotFoundError:
        return 127, "", ""
    except subprocess.TimeoutExpired:
        return 124, "", "timed out"
    return p.returncode, p.stdout, p.stderr


def first_error(err):
    return next((ln.strip() for ln in (err or "").splitlines() if ln.strip()), "")[:200]


def key_of(line):
    m = re.match(rb"^([A-Za-z_][A-Za-z0-9_]*)=", line)
    return m.group(1).decode("ascii") if m else None


def value_of(line):
    raw = line.split(b"=", 1)[1].rstrip(b"\r\n").decode("utf-8", "surrogateescape")
    raw = re.sub(r'^"(.*)"$', r"\1", raw)
    return re.sub(r"^'(.*)'$", r"\1", raw)


def ending_of(line):
    return b"\r\n" if line.endswith(b"\r\n") else (b"\n" if line.endswith(b"\n") else b"")


def classify(key, value):
    if value is None:
        return "absent"
    if value == "":
        return "empty"
    if key == PROVIDER:
        return value if value in ("expo", "noop", "fcm-disabled") else "<other value>"
    if key == ENABLED:
        return value.lower() if value.lower() in ("true", "false", "1", "0", "yes", "no", "on", "off") else "<non-boolean>"
    return "set"


def last_values(lines):
    values = {}
    for line in lines:
        k = key_of(line)
        if k:
            values[k] = value_of(line)
    return values


def push_summary(values):
    return "; ".join(f"{k} {classify(k, values.get(k))}" for k in PUSH_KEYS)


def compose_cmd():
    canonical = [ln.strip() for ln in open(os.path.join(REL, "docker", "compose.production.files"), encoding="utf-8")
                 if ln.strip() and not ln.strip().startswith("#")]
    files = [os.path.join(REL, f) for f in canonical]
    if CIVO_TRIGGER in canonical:
        files.append(os.path.join(REL, CIVO_OVERLAY))
    cmd = ["docker", "compose", "--env-file", ENV_FILE]
    for f in files:
        cmd += ["-f", f]
    return cmd


def model_hashes(**override):
    rc, out, err = run(compose_cmd() + ["config", "--hash", "*"], cwd=REL, env=base_env(**override))
    if rc != 0:
        return None, first_error(err)
    result = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 2:
            result[parts[0]] = parts[1]
    return result, None


def model_service_env(**override):
    rc, out, err = run(compose_cmd() + ["config", "--format", "json"], cwd=REL, env=base_env(**override))
    if rc != 0:
        return None, first_error(err)
    env = ((json.loads(out).get("services") or {}).get(SERVICE) or {}).get("environment") or {}
    if isinstance(env, list):
        env = dict(e.split("=", 1) if "=" in e else (e, "") for e in env)
    return {k: ("" if v is None else str(v)) for k, v in env.items()}, None


def project_containers():
    rc, out, _ = run(["docker", "ps", "-q", "--no-trunc", "--filter", f"label=com.docker.compose.project={PROJECT}",
                      "--filter", "status=running"])
    return sorted(out.split())


def running_container():
    rc, out, _ = run(["docker", "ps", "-q", "--no-trunc", "--filter", f"label=com.docker.compose.project={PROJECT}",
                      "--filter", f"label=com.docker.compose.service={SERVICE}", "--filter", "status=running"])
    ids = out.split()
    if len(ids) != 1:
        return None
    rc, out, _ = run(["docker", "inspect", ids[0]])
    if rc != 0:
        return None
    data = json.loads(out)[0]
    labels = (data.get("Config") or {}).get("Labels") or {}
    env = dict(e.split("=", 1) if "=" in e else (e, "") for e in (data.get("Config") or {}).get("Env") or [])
    return {"id": data.get("Id", ""), "started": (data.get("State") or {}).get("StartedAt", ""),
            "health": ((data.get("State") or {}).get("Health") or {}).get("Status"), "image": data.get("Image", ""),
            "hash": labels.get("com.docker.compose.config-hash", ""), "env": env}


def image_id(ref):
    rc, out, _ = run(["docker", "image", "inspect", "--format", "{{.Id}}", ref])
    return out.strip() if rc == 0 else ""


def write_atomically(data, st):
    directory = os.path.dirname(ENV_FILE)
    fd, tmp = tempfile.mkstemp(prefix=".n4-", dir=directory)
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


def read_env():
    if os.path.islink(ENV_FILE) or not os.path.isfile(ENV_FILE):
        stop("the env file is missing or a symbolic link")
    st = os.stat(ENV_FILE)
    with open(ENV_FILE, "rb") as fh:
        original = fh.read()
    return st, original, original.splitlines(keepends=True)


def section_a(st, lines):
    try:
        owner = pwd.getpwuid(st.st_uid).pw_name
    except KeyError:
        owner = str(st.st_uid)
    say(f"  env file: mode {oct(st.st_mode & 0o7777)[2:]}, owner {owner}")
    values = last_values(lines)
    counts = {k: sum(1 for ln in lines if key_of(ln) == k) for k in PUSH_KEYS + HMAC_KEYS}
    nonstandard = sorted({k for ln in lines for k in PUSH_KEYS
                          if re.match(rb"^\s*(export\s+)?" + k.encode() + rb"\s*=", ln) and key_of(ln) != k})
    for k in PUSH_KEYS:
        say(f"  {k}: {classify(k, values.get(k))} ({counts[k]} line{'s' if counts[k] != 1 else ''})")
    for k in HMAC_KEYS:
        say(f"  {k}: {'set' if values.get(k) else ('empty' if k in values else 'absent')} (left untouched)")
    return values, counts, nonstandard


def check():
    say(f"N4 check | stamp {STAMP} | env file {ENV_FILE} | release {REL} | read-only")
    say("")
    say("A. Env file (names, non-secret values and set/absent only)")
    st, original, lines = read_env()
    values, counts, nonstandard = section_a(st, lines)
    gate("PARKIO_PUSH_DELIVERY_ENABLED on at most one standard line (a disable writes exactly that line)",
         counts[ENABLED] <= 1 and not nonstandard, f"{counts[ENABLED]} line(s)" + (f"; non-standard: {', '.join(nonstandard)}"
                                                                                  if nonstandard else ""))
    gate("the HMAC key is set and its _PREVIOUS line is present (empty since 2026-10-08); a disable preserves both",
         bool(values.get(HMAC_KEYS[0])) and HMAC_KEYS[1] in values,
         f"{HMAC_KEYS[1]} {'set' if values.get(HMAC_KEYS[1]) else ('empty' if HMAC_KEYS[1] in values else 'absent')}")

    say("")
    say("B. Backups beside the env file")
    backups = sorted(f for f in os.listdir(os.path.dirname(ENV_FILE)) if f.startswith(os.path.basename(ENV_FILE) + ".bak-"))
    for name in backups:
        path = os.path.join(os.path.dirname(ENV_FILE), name)
        try:
            with open(path, "rb") as fh:
                bvalues = last_values(fh.read().splitlines(keepends=True))
            mode = oct(os.stat(path).st_mode & 0o7777)[2:]
            say(f"  {name}: mode {mode}; {push_summary(bvalues)}")
        except OSError:
            say(f"  {name}: not readable")
    if not backups:
        say("  none")

    say("")
    say(f"C. Running {SERVICE}")
    c = running_container()
    if c:
        say(f"  container {c['id'][:12]} started {c['started']} health {c['health'] or 'none'}; image {c['image'][7:19]}; "
            f"config hash {c['hash'][:12]}")
        say(f"  its push settings: {push_summary(c['env'])}")
    else:
        say("  not exactly one running container")
    gate(f"exactly one running {SERVICE}", bool(c))

    say("")
    say("D. Release model (read-only render)")
    hashes, err = model_hashes()
    env, err2 = model_service_env()
    if hashes is None or env is None:
        gate("release model renders", False, err or err2)
        return finish()
    say(f"  {SERVICE} in the model: {push_summary(env)}; config hash {hashes.get(SERVICE, '-')[:12]}")
    rel_img = image_id(RELEASE_TAG)
    if c:
        say(f"  running container = release model config: {'yes' if c['hash'] == hashes.get(SERVICE) else 'no (D7 recreates it)'}")
        say(f"  running image = {RELEASE_TAG}: " + ("yes" if rel_img and c["image"] == rel_img
                                                    else "no (the release image is built and started by D7)"))

    say("")
    say("E. The disable change, simulated (Compose interpolation; nothing written)")
    sim_hashes, err = model_hashes(**{ENABLED: "false"})
    sim_env, err2 = model_service_env(**{ENABLED: "false"})
    if sim_hashes is None or sim_env is None:
        gate("simulated model renders", False, err or err2)
        return finish()
    gate(f"{SERVICE} would get {ENABLED}=false, provider and token unchanged",
         sim_env.get(ENABLED, "").lower() == "false" and sim_env.get(PROVIDER) == env.get(PROVIDER)
         and sim_env.get(TOKEN) == env.get(TOKEN),
         f"{push_summary(sim_env)}")
    changed = sorted(s for s in set(hashes) | set(sim_hashes) if hashes.get(s) != sim_hashes.get(s))
    gate(f"only {SERVICE}'s config hash would change", changed == [SERVICE], f"changed: {', '.join(changed) or 'none'}")
    return finish()


def wait_healthy(old_id):
    deadline = time.time() + HEALTH_TIMEOUT
    c = None
    while time.time() < deadline:
        c = running_container()
        if c and c["id"] != old_id and c["health"] in ("healthy", None):
            return c
        time.sleep(5)
    return c


def disable():
    say(f"N4 disable | stamp {STAMP} | env file {ENV_FILE} | release {REL} | push incident")
    say("")
    say("A. Preconditions (nothing changes here)")
    if not os.path.isdir(REL):
        stop(f"missing directory {REL}")
    st, original, lines = read_env()
    if os.getuid() not in (0, st.st_uid):
        stop("run N4 as the env file's owner")
    values, counts, nonstandard = section_a(st, lines)
    if nonstandard or counts[ENABLED] > 1:
        stop(f"{ENABLED} must be on at most one standard line (found {counts[ENABLED]}"
             + (f"; non-standard: {', '.join(nonstandard)}" if nonstandard else "") + "); edit it by hand first")
    if (values.get(ENABLED) or "").lower() == "false":
        stop(f"{ENABLED} is already false; nothing to change")
    c = running_container()
    if not c:
        stop(f"not exactly one running {SERVICE}")
    rel_img = image_id(RELEASE_TAG)
    if not rel_img or c["image"] != rel_img:
        stop(f"the running {SERVICE} is not the release image {RELEASE_TAG} (D7 starts it); an older image on the "
             "migrated schema is not covered by this tool - owner decision")
    say(f"  running {SERVICE}: container {c['id'][:12]}, release image, health {c['health'] or 'none'}")
    hashes_before, err = model_hashes()
    if hashes_before is None:
        stop(f"the release model does not render: {err}")
    others_before = [i for i in project_containers() if i != c["id"]]

    say("")
    say("B. Backup and write (only PARKIO_PUSH_DELIVERY_ENABLED)")
    backup = f"{ENV_FILE}.bak-n4-{STAMP}"
    if os.path.exists(backup):
        stop("the backup name exists already")
    fd = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, st.st_mode & 0o7777)
    with os.fdopen(fd, "wb") as fh:
        fh.write(original)
        fh.flush()
        os.fsync(fh.fileno())
        os.fchmod(fh.fileno(), st.st_mode & 0o7777)
        os.fchown(fh.fileno(), st.st_uid, st.st_gid)
    os.utime(backup, ns=(st.st_atime_ns, st.st_mtime_ns))
    bst = os.stat(backup)
    with open(backup, "rb") as fh:
        same = fh.read() == original
    if not same or (bst.st_mode, bst.st_uid, bst.st_gid) != (st.st_mode, st.st_uid, st.st_gid):
        stop("the backup does not equal the original (content, mode, owner or group); nothing else was changed")
    say(f"  backup {os.path.basename(backup)}: identical content, mode {oct(bst.st_mode & 0o7777)[2:]}, same owner/group")
    nl = b"\r\n" if original.count(b"\r\n") * 2 > original.count(b"\n") else b"\n"
    new_lines = [ENABLED.encode() + b"=false" + ending_of(ln) if key_of(ln) == ENABLED else ln for ln in lines]
    if counts[ENABLED] == 0:
        if new_lines and not ending_of(new_lines[-1]):
            new_lines[-1] = new_lines[-1] + nl
        new_lines += [COMMENT + nl, ENABLED.encode() + b"=false" + nl]
    data = b"".join(new_lines)
    write_atomically(data, st)
    with open(ENV_FILE, "rb") as fh:
        written = fh.read()
    nst = os.stat(ENV_FILE)
    after_lines = written.splitlines(keepends=True)
    problems = []
    if written != data:
        problems.append("the file content is not what was written")
    for i, line in enumerate(lines):
        if key_of(line) == ENABLED:
            continue
        if i >= len(after_lines) or (after_lines[i] != line and not (i == len(lines) - 1 and counts[ENABLED] == 0
                                                                      and after_lines[i] == line + nl)):
            problems.append(f"line {i + 1} changed")
            break
    after = last_values(after_lines)
    if (nst.st_mode, nst.st_uid, nst.st_gid) != (st.st_mode, st.st_uid, st.st_gid):
        problems.append("mode, owner or group changed")
    if after.get(ENABLED) != "false" or any(after.get(k) != values.get(k) for k in (PROVIDER, TOKEN) + HMAC_KEYS):
        problems.append("a key other than the intended one differs")
    if problems:
        write_atomically(original, st)
        stop("structural check failed (" + "; ".join(problems) + "); the backup content was restored at once", True)
    gate(f"env file: only {ENABLED} changed (now false); provider, token and HMAC keys unchanged; mode and owner unchanged",
         True)
    hashes_after, err = model_hashes()
    changed = sorted(s for s in set(hashes_before) | set(hashes_after or {})
                     if (hashes_after or {}).get(s) != hashes_before.get(s)) if hashes_after else None
    if not gate(f"only {SERVICE}'s release config changes", changed == [SERVICE],
                f"changed: {', '.join(changed) if changed else 'none'}" if changed is not None else f"render failed: {err}"):
        write_atomically(original, st)
        stop("the model change is not limited to notification-service; the backup content was restored", True)

    say("")
    say(f"C. Recreate {SERVICE} alone (its environment is read only when the container is created)")
    say(f"  Run: cd {REL} && PARKIO_ENV_FILE={ENV_FILE} {WRAPPER} up -d --no-build --no-deps {SERVICE}")
    rc, out, err = run([WRAPPER, "up", "-d", "--no-build", "--no-deps", SERVICE], cwd=REL,
                       env=base_env(PARKIO_ENV_FILE=ENV_FILE), timeout=900)
    gate("wrapper exit 0", rc == 0, f"exit {rc}" + (f"; {first_error(err)}" if rc else ""))
    n = wait_healthy(c["id"])
    gate(f"{SERVICE} recreated and healthy within {HEALTH_TIMEOUT} s", bool(n) and n["id"] != c["id"]
         and n["health"] in ("healthy", None), f"{n['id'][:12]} {n['health'] or 'none'}" if n else "no container")
    if n:
        gate(f"{SERVICE} runs the same release image", n["image"] == rel_img)
        gate(f"its environment: delivery disabled, provider and token unchanged",
             n["env"].get(ENABLED, "").lower() == "false" and n["env"].get(PROVIDER) == c["env"].get(PROVIDER)
             and n["env"].get(TOKEN) == c["env"].get(TOKEN), push_summary(n["env"]))
    others_after = [i for i in project_containers() if not n or i != n["id"]]
    gate("no other container of the project changed", others_after == others_before,
         f"{len(others_before)} before, {len(others_after)} after")
    say(f"  undo: set {ENABLED} back to true (or cp -p {os.path.basename(backup)} back) and recreate {SERVICE} the same way")
    return finish()


def finish():
    failed = [name for name, ok in GATES if not ok]
    say("")
    say(f"gates passed {len(GATES) - len(failed)}/{len(GATES)}" + (f"; failed: {', '.join(failed)}" if failed else ""))
    say(f"N4 {MODE.upper()} {'COMPLETE' if not failed else 'NOT COMPLETE'}")
    save()
    sys.exit(0 if not failed else 1)


def main():
    global MODE
    args = sys.argv[1:]
    if args == ["check"]:
        MODE = "check"
        check()
    elif args == ["disable", "--push-incident"]:
        MODE = "disable"
        disable()
    else:
        print("usage: n4-push-recovery.py check | disable --push-incident")
        sys.exit(2)


if __name__ == "__main__":
    main()
