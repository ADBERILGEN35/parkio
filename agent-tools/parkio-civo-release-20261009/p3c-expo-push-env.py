#!/usr/bin/env python3
"""P3c - configure Expo push delivery in the live env file on parkio-civo-prod (owner decision 2026-10-10).

Run as civo (the env file's owner) in an interactive terminal on the host, after creating the parkio-civo-push token:
  python3 -I /tmp/p3c-expo-push-env.py

What it changes, and nothing else:
  /opt/parkio/docker/.env.azure-hosted-beta gets PARKIO_PUSH_DELIVERY_PROVIDER=expo, PARKIO_PUSH_DELIVERY_ENABLED=true and
  PARKIO_EXPO_ACCESS_TOKEN=<the value entered at the hidden prompt>. Lines of these keys are rewritten in place; a key the
  file lacks is appended at its end under one comment line. Every other line stays byte-identical and in place.
  Before that, the original is copied beside it to <env file>.bak-<UTC stamp> with the same mode, owner, group and
  timestamps (git-ignored by .env.*, like the 2026-10-08 backup). The new content is written to a temporary file in the
  same directory with the original mode, owner and group, then renamed over the original.
The token is read twice through a hidden terminal prompt (getpass), never from arguments, environment variables or a
pipe. It is never printed, logged or handed to another process; preflight and Compose read it from the file.
No container is created, started, restarted or stopped.

Checks after the write: only those three keys differ; mode, owner and group unchanged; the live checkout's git status
unchanged; the release's preflight (azure-hosted-beta, --skip-compose as in P3) passes; the release model gives
notification-service provider expo, delivery enabled and exactly the entered token, and notification-service is the only
service whose config hash changes; the running notification-service container is the same one. A structural mismatch
restores the backup at once.

Option: --replace-token  allow replacing an existing non-empty token (a later rotation). Without it, a set token stops
the run before any change.
Report: ~/parkio-release-20261009/P3c-expo-push-env-<stamp>.txt (mode 600).
"""
import getpass
import json
import os
import pwd
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone

PIN = "22f9699038cdae15ffff5dd7433c4a64aed0a951"
LIVE = os.environ.get("P3C_LIVE", "/opt/parkio")
REL = os.environ.get("P3C_RELEASE", "/opt/parkio-release")
ENV_FILE = os.environ.get("P3C_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
PROJECT = os.environ.get("P3C_PROJECT", "parkio")
OUT = os.environ.get("P3C_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009"))
CIVO_TRIGGER = "docker/docker-compose.azure-hosted-beta.yml"
CIVO_OVERLAY = "docker/docker-compose.civo-alertmanager.yml"
PROVIDER, ENABLED, TOKEN = "PARKIO_PUSH_DELIVERY_PROVIDER", "PARKIO_PUSH_DELIVERY_ENABLED", "PARKIO_EXPO_ACCESS_TOKEN"
TARGET_ORDER = [PROVIDER, ENABLED, TOKEN]
SERVICE = "notification-service"
COMMENT = b"# Expo push delivery (owner decision 2026-10-10, P3c)"
# scripts/preflight-hosted-beta.sh is_placeholder
PLACEHOLDER = re.compile(r"CHANGE_ME|CHANGEME|CHANGE-ME|PLACEHOLDER|REPLACE_ME|REPLACEME|YOUR_|<[A-Za-z_-]+>|DUMMY|"
                         r"SAMPLE_|TODO|FIXME|00000000-0000-0000-0000-000000000000", re.I)
# No whitespace, quotes, '#', '$' or backslash: the value must read the same for compose's env-file parser and preflight.
TOKEN_SHAPE = re.compile(r"^[A-Za-z0-9._~+/=:-]{20,512}$")
SUBJECT = re.compile(r"^[A-Za-z0-9_.:/-]{1,80}$")

REPORT = []
GATES = []
SECRET = None
STAMP = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
URL = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://[^\s'\"]+")
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")


def say(line=""):
    if SECRET:
        line = line.replace(SECRET, "<redacted>")
    line = EMAIL.sub("<email>", URL.sub("<url>", line))
    print(line, flush=True)
    REPORT.append(line)


def gate(name, ok, detail=""):
    GATES.append((name, ok))
    say(f"  {'PASS' if ok else 'FAIL'} {name}{': ' + detail if detail else ''}")


def save():
    os.makedirs(OUT, mode=0o700, exist_ok=True)
    path = os.path.join(OUT, f"P3c-expo-push-env-{STAMP}.txt")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write("\n".join(REPORT) + "\n")
    print(f"report saved: {path}")


def stop(message, changed=False):
    say(f"STOP: {message}")
    say("P3c STOPPED; " + ("see above for the state of the env file" if changed else "the env file was not changed"))
    save()
    sys.exit(1)


def run(cmd, cwd=None, timeout=300):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, cwd=cwd, timeout=timeout, stdin=subprocess.DEVNULL)
    except FileNotFoundError:
        return 127, "", ""
    except subprocess.TimeoutExpired:
        return 124, "", "timed out"
    return p.returncode, p.stdout, p.stderr


def first_error(err):
    return next((ln.strip() for ln in (err or "").splitlines() if ln.strip()), "")[:200]


def git(*args, repo):
    rc, out, _ = run(["git", "--no-optional-locks", "-C", repo] + list(args))
    return rc, out


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
    return "placeholder-like" if PLACEHOLDER.search(value) else "set"


def last_values(lines):
    values = {}
    for line in lines:
        k = key_of(line)
        if k:
            values[k] = value_of(line)
    return values


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


def model_hashes():
    rc, out, err = run(compose_cmd() + ["config", "--hash", "*"], cwd=REL)
    if rc != 0:
        return None, first_error(err)
    result = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 2:
            result[parts[0]] = parts[1]
    return result, None


def model_service_env():
    rc, out, err = run(compose_cmd() + ["config", "--format", "json"], cwd=REL)
    if rc != 0:
        return None, first_error(err)
    env = ((json.loads(out).get("services") or {}).get(SERVICE) or {}).get("environment") or {}
    if isinstance(env, list):
        env = dict(e.split("=", 1) if "=" in e else (e, "") for e in env)
    return {k: ("" if v is None else str(v)) for k, v in env.items()}, None


def running_container():
    rc, out, _ = run(["docker", "ps", "-q", "--no-trunc", "--filter", f"label=com.docker.compose.project={PROJECT}",
                      "--filter", f"label=com.docker.compose.service={SERVICE}", "--filter", "status=running"])
    ids = out.split()
    if len(ids) != 1:
        return None
    rc, out, _ = run(["docker", "inspect", "--format", "{{.Id}} {{.State.StartedAt}}", ids[0]])
    return out.strip() if rc == 0 else None


def write_atomically(data, st):
    directory = os.path.dirname(ENV_FILE)
    fd, tmp = tempfile.mkstemp(prefix=".p3c-", dir=directory)
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


def preflight():
    script = os.path.join(REL, "scripts", "preflight-hosted-beta.sh")
    rc, out, err = run(["sh", script, "--env-file", ENV_FILE, "--deployment-profile", "azure-hosted-beta",
                        "--skip-compose"], cwd=REL)
    summary = ""
    for line in (out + "\n" + err).splitlines():
        s = line.strip()
        m = re.match(r"^\[(.+)\]$", s)
        if m:
            say(f"    [{m.group(1)[:60]}]")
            continue
        m = re.match(r"^(FAIL|WARN)\s+(\S+)", s)
        if m:
            subject = m.group(2).rstrip(":")
            say(f"      {m.group(1)} {subject if SUBJECT.match(subject) else '<subject>'}")
            continue
        if s.startswith("=== PREFLIGHT:"):
            summary = re.sub(r"[^\w\s:()=,.-]", "-", s)[:120]
    gate("preflight (azure-hosted-beta, env validation)", rc == 0, f"exit {rc}; {summary or 'no summary line'}")


def main():
    global SECRET
    replace = "--replace-token" in sys.argv[1:]
    unknown = [a for a in sys.argv[1:] if a != "--replace-token"]
    if unknown:
        stop(f"{len(unknown)} unknown argument(s), not shown; the only option is --replace-token and the token is never "
             "an argument (if one was typed on the command line, remove it from the shell history)")
    say(f"P3c Expo push env | stamp {STAMP} | env file {ENV_FILE} | release {REL}")
    say("")
    say("A. Preconditions (nothing changes here)")
    if not os.isatty(0):
        stop("standard input is not a terminal: run P3c interactively (the token is read only from a hidden prompt)")
    for path in (LIVE, REL):
        if not os.path.isdir(path):
            stop(f"missing directory {path}")
    if os.path.islink(ENV_FILE) or not os.path.isfile(ENV_FILE):
        stop("the env file is missing or a symbolic link")
    st = os.stat(ENV_FILE)
    try:
        owner = pwd.getpwuid(st.st_uid).pw_name
    except KeyError:
        owner = str(st.st_uid)
    say(f"  env file: mode {oct(st.st_mode & 0o7777)[2:]}, owner {owner}, group id {st.st_gid}")
    if os.getuid() not in (0, st.st_uid):
        stop(f"run P3c as the env file's owner ({owner})")
    if not os.access(os.path.dirname(ENV_FILE), os.W_OK | os.X_OK):
        stop("the env file's directory is not writable (needed for the backup and the atomic rename)")
    rc, head = git("rev-parse", "HEAD", repo=REL)
    if head.strip() != PIN and not os.environ.get("P3C_PIN_ANY"):
        stop(f"{REL} is not at {PIN}")
    rc, rel_status = git("status", "--porcelain", repo=REL)
    if rel_status.strip():
        stop(f"{REL} is not clean")
    rc, live_status = git("status", "--porcelain", repo=LIVE)
    say(f"  live checkout {LIVE}: {len(live_status.splitlines())} changes (must stay the same)")
    with open(ENV_FILE, "rb") as fh:
        original = fh.read()
    lines = original.splitlines(keepends=True)
    for line in lines:
        for key in TARGET_ORDER:
            if re.match(rb"^\s*(export\s+)?" + key.encode() + rb"\s*=", line) and key_of(line) != key:
                stop(f"a non-standard line for {key} (leading space, export or space before '='); edit it by hand first")
    counts = {k: sum(1 for ln in lines if key_of(ln) == k) for k in TARGET_ORDER}
    before = last_values(lines)
    for key in TARGET_ORDER:
        say(f"  {key}: {classify(key, before.get(key))}"
            + (f" ({counts[key]} lines; every one is rewritten)" if counts[key] > 1 else ""))
    if before.get(TOKEN) and not replace:
        stop(f"{TOKEN} is already set; to replace it (rotation) run P3c with --replace-token")
    container_before = running_container()
    say(f"  running {SERVICE}: {'one container, recorded' if container_before else 'not exactly one running'}")
    hashes_before, err = model_hashes()
    if hashes_before is None:
        stop(f"the release model does not render: {err}")
    say(f"  release model renders: {len(hashes_before)} services")

    say("")
    say("B. Token entry (hidden; paste the parkio-civo-push token twice)")
    first = getpass.getpass("  token (hidden): ").strip()
    second = getpass.getpass("  same token again (hidden): ").strip()
    if first != second:
        stop("the two entries differ")
    if not first:
        stop("the entry is empty")
    if not TOKEN_SHAPE.match(first):
        stop("the entry has whitespace, quotes, '#', '$', a backslash or another unexpected character, or a length "
             "outside 20-512")
    if PLACEHOLDER.search(first):
        stop("the entry looks like a placeholder")
    same_as = sorted(k for k, v in last_values(lines).items() if v == first and k != TOKEN)
    if same_as:
        stop(f"the entry equals the value of {', '.join(same_as)}; paste the parkio-civo-push token")
    if before.get(TOKEN) == first:
        stop("the entry equals the token already in the file; nothing to change")
    SECRET = first
    say("  entries match; shape accepted")

    say("")
    say("C. Backup and write")
    backup = f"{ENV_FILE}.bak-{STAMP}"
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
    wanted = {PROVIDER: b"expo", ENABLED: b"true", TOKEN: first.encode("ascii")}
    new_lines = []
    for line in lines:
        key = key_of(line)
        if key in wanted:
            new_lines.append(key.encode() + b"=" + wanted[key] + ending_of(line))
        else:
            new_lines.append(line)
    missing = [k for k in TARGET_ORDER if counts[k] == 0]
    if missing:
        if new_lines and not ending_of(new_lines[-1]):
            new_lines[-1] = new_lines[-1] + nl
        new_lines.append(COMMENT + nl)
        for key in missing:
            new_lines.append(key.encode() + b"=" + wanted[key] + nl)
    data = b"".join(new_lines)
    write_atomically(data, st)
    say(f"  rewritten in place: {', '.join(k for k in TARGET_ORDER if counts[k]) or 'none'}; appended: "
        f"{', '.join(missing) or 'none'}")

    say("")
    say("D. Verification (no value is printed)")
    with open(ENV_FILE, "rb") as fh:
        written = fh.read()
    nst = os.stat(ENV_FILE)
    problems = []
    if written != data:
        problems.append("the file content is not what was written")
    after_lines = written.splitlines(keepends=True)
    unchanged = 0
    for i, line in enumerate(lines):
        if key_of(line) in wanted:
            continue
        if i >= len(after_lines) or (after_lines[i] != line and not (i == len(lines) - 1 and missing
                                                                      and after_lines[i] == line + nl)):
            problems.append(f"line {i + 1} changed")
            break
        unchanged += 1
    if (nst.st_mode, nst.st_uid, nst.st_gid) != (st.st_mode, st.st_uid, st.st_gid):
        problems.append("mode, owner or group changed")
    after = last_values(after_lines)
    if (after.get(PROVIDER), after.get(ENABLED), after.get(TOKEN)) != ("expo", "true", first):
        problems.append("a target key does not hold the intended value")
    if problems:
        write_atomically(original, st)
        stop("structural check failed (" + "; ".join(problems) + "); the backup content was restored at once", True)
    say(f"  other lines byte-identical and in place: {unchanged}; mode {oct(nst.st_mode & 0o7777)[2:]}, owner and group "
        f"unchanged")
    say(f"  {PROVIDER}: expo; {ENABLED}: true; {TOKEN}: set, equals the entered value")
    gate("env file holds exactly the intended change", True)
    rc, live_after = git("status", "--porcelain", repo=LIVE)
    gate("live checkout git status unchanged", live_after == live_status, f"{len(live_after.splitlines())} changes")
    rc, rel_after = git("status", "--porcelain", repo=REL)
    gate("release checkout still clean", not rel_after.strip())

    say("")
    say("E. Preflight and release model (nothing started)")
    preflight()
    env, err = model_service_env()
    if env is None:
        gate(f"release model gives {SERVICE} the intended settings", False, f"render failed: {err}")
    else:
        ok = (env.get(PROVIDER), env.get(ENABLED), env.get(TOKEN)) == ("expo", "true", first)
        gate(f"release model gives {SERVICE} the intended settings", ok,
             f"{PROVIDER} {classify(PROVIDER, env.get(PROVIDER))}; {ENABLED} {classify(ENABLED, env.get(ENABLED))}; "
             f"{TOKEN} {'equals the entered value' if env.get(TOKEN) == first else classify(TOKEN, env.get(TOKEN))}")
    hashes_after, err = model_hashes()
    changed = sorted(s for s in set(hashes_before) | set(hashes_after or {})
                     if (hashes_after or {}).get(s) != hashes_before.get(s)) if hashes_after else None
    gate(f"only {SERVICE}'s release config changes", changed == [SERVICE],
         f"changed: {', '.join(changed) if changed else 'none'}" if changed is not None else f"render failed: {err}")
    container_after = running_container()
    gate(f"running {SERVICE} untouched", container_after == container_before,
         "same container, same start time" if container_after == container_before else "container differs")

    failed = [name for name, ok in GATES if not ok]
    say("")
    say("SUMMARY")
    say(f"gates passed {len(GATES) - len(failed)}/{len(GATES)}" + (f"; failed: {', '.join(failed)}" if failed else ""))
    say(f"to undo the env change (the backup holds the previous state, without this token): cp -p {backup} {ENV_FILE}")
    say(f"{SERVICE} keeps running with provider noop until it is recreated (D7); nothing was restarted")
    say("P3c COMPLETE" if not failed else "P3c INCOMPLETE (the env change is in place; see FAIL lines)")
    save()
    sys.exit(0 if not failed else 1)


if __name__ == "__main__":
    main()
