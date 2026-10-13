#!/usr/bin/env python3
"""U06 heartbeat, part B1: write the external heartbeat ping URL into the live env file on parkio-civo-prod.
Prepared 2026-10-13, revised after the independent review (U06-TOOLS-REVIEW.md, L1, L3, L4, L5, M6). Running it needs
the owner's authorization for the activation window.

Run as civo (the env file's owner) in an interactive terminal on the host, after the owner created the check and stored
its ping URL in the DR secrets store:
  python3 -I u06-heartbeat-env.py [--replace-url]

What it changes, and nothing else:
  /opt/parkio/docker/.env.azure-hosted-beta gets PARKIO_ALERT_HEARTBEAT_URL=<the value entered at the hidden prompt>.
  A line of that key is rewritten in place; a missing key is appended at the end under one comment line. Every other line
  stays byte-identical and in place. The original is first copied beside it to <env file>.bak-<UTC stamp>, with the same
  mode, owner, group and timestamps. The new content goes to a temporary file in the same directory, with the original
  mode, owner and group, then is renamed over the original.
The URL is read twice through a hidden terminal prompt (getpass), never from arguments, the environment or a pipe. It is
never printed or logged; preflight and Compose read it from the file. No container is created, started or recreated:
Alertmanager keeps its current routing until the separate, authorized recreate (u06-alertmanager-step.py).
The entry must be printable ASCII, https, with a host and a path, without spaces, quotes, '#', '$' or a backslash, not
a placeholder and not the value of another key. These are this tool's own checks: the release preflight under the
azure-hosted-beta profile does not inspect the heartbeat at all (review L1).

The env file is read again right before the backup; if it changed while the prompt was open, the run stops with
nothing changed (review L3).
Checks after the write:
  - only that key differs, and mode, owner and group are unchanged;
  - the live checkout's git status is unchanged;
  - the release preflight (azure-hosted-beta, --skip-compose) still passes;
  - the release model gives alertmanager exactly the entered URL, and alertmanager is the only service whose config
    hash changes;
  - the running alertmanager is the same container.
Any failed check restores the original content at once (atomically, same mode and owner), so a FAIL never leaves the URL
in the env file (review L5); the backup stays as the record.

The release checkout must be clean at the release PIN f888a81b, or at the MapTiler deploy commit R1. R1 is a descendant of
the PIN whose only change is docker/docker-compose.web-release-pin.yml, and that file must pin the web image alone.
Option --replace-url allows replacing an existing URL (rotation); without it, a set URL stops the run before any change.
Report: ~/parkio-release-20261009/u06/U06-heartbeat-env-<stamp>.txt (mode 600). To undo: `cp -p <backup> <env file>`.
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

TEST = os.environ.get("U06H_TEST") == "1"


def cfg(name, default):
    """Overrides are honoured only by the self-test."""
    return os.environ.get(name, default) if TEST else default


def fault(name):
    """Test-only fault injection (review M6): honoured only by the self-test."""
    return TEST and name in os.environ.get("U06H_FAULT", "").split(",")


PIN = cfg("U06H_PIN", "f888a81b113c0c486ea0949067779443857e644d")
LIVE = cfg("U06H_LIVE", "/opt/parkio")
REL = cfg("U06H_RELEASE", "/opt/parkio-release")
ENV_FILE = cfg("U06H_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
PROJECT = "parkio"
OUT = cfg("U06H_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009", "u06"))
CIVO_TRIGGER = "docker/docker-compose.azure-hosted-beta.yml"
CIVO_OVERLAY = "docker/docker-compose.civo-alertmanager.yml"
WEB_PIN_FILE = "docker/docker-compose.web-release-pin.yml"
WEB_PIN_LINES = (r"name: parkio", r"services:", r"  web:", r"    image: ghcr\.io/adberilgen35/parkio/web@sha256:[0-9a-f]{64}")
KEY = "PARKIO_ALERT_HEARTBEAT_URL"
SERVICE = "alertmanager"
COMMENT = b"# External heartbeat / dead-man's switch (U06, owner-authorized activation)"
# scripts/preflight-hosted-beta.sh is_placeholder
PLACEHOLDER = re.compile(r"CHANGE_ME|CHANGEME|CHANGE-ME|PLACEHOLDER|REPLACE_ME|REPLACEME|YOUR_|<[A-Za-z_-]+>|DUMMY|"
                         r"SAMPLE_|TODO|FIXME|00000000-0000-0000-0000-000000000000|example\.(com|org|net|invalid)", re.I)
# https only; no whitespace, quotes, '#', '$' or backslash, so compose's env-file parser and preflight read the same value.
URL_SHAPE = re.compile(r"^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?/[^\s'\"#$\\]{6,480}$")
SUBJECT = re.compile(r"^[A-Za-z0-9_.:/-]{1,80}$")

REPORT = []
GATES = []
SECRET = None
STAMP = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
URL = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://[^\s'\"]+")


def say(line=""):
    if SECRET:
        line = line.replace(SECRET, "<redacted>")
    line = URL.sub("<url>", line)
    print(line, flush=True)
    REPORT.append(line)


def gate(name, ok, detail=""):
    GATES.append((name, ok))
    say(f"  {'PASS' if ok else 'FAIL'} {name}{': ' + detail if detail else ''}")


def save():
    os.makedirs(OUT, mode=0o700, exist_ok=True)
    path = os.path.join(OUT, f"U06-heartbeat-env-{STAMP}.txt")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write("\n".join(REPORT) + "\n")
    print(f"report saved: {path}")


def stop(message, changed=False):
    say(f"STOP: {message}")
    say("U06 heartbeat env STOPPED; " + ("see above for the state of the env file" if changed else "the env file was not changed"))
    save()
    sys.exit(1)


def base_env():
    env = {k: os.environ[k] for k in ("PATH", "HOME", "LANG", "USER", "LOGNAME") if k in os.environ}
    if TEST and "U06H_W" in os.environ:
        env["U06H_W"] = os.environ["U06H_W"]
    return env


def run(cmd, cwd=None, timeout=300):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, cwd=cwd, timeout=timeout, stdin=subprocess.DEVNULL, env=base_env())
    except FileNotFoundError:
        return 127, "", ""
    except subprocess.TimeoutExpired:
        return 124, "", "timed out"
    return p.returncode, p.stdout, p.stderr


def first_error(err):
    return URL.sub("<url>", next((ln.strip() for ln in (err or "").splitlines() if ln.strip()), ""))[:200]


def git(*args, repo):
    rc, out, _ = run(["git", "--no-optional-locks", "-c", f"safe.directory={repo}", "-C", repo] + list(args))
    return rc, out


def release_ok():
    """The release checkout is clean, at the PIN or at a descendant that changes only the web pin (web image alone)."""
    rc, head = git("rev-parse", "HEAD", repo=REL)
    rc2, status = git("status", "--porcelain", repo=REL)
    head = head.strip()
    if rc != 0 or rc2 != 0 or status.strip():
        return False, f"{REL} is not clean ({head[:12] or '?'})"
    if head == PIN:
        return True, f"at the PIN {PIN[:12]}"
    rc3, _ = git("merge-base", "--is-ancestor", PIN, head, repo=REL)
    rc4, names = git("diff", "--no-renames", "-z", "--name-only", PIN, head, repo=REL)
    if rc3 == 0 and rc4 == 0 and [n for n in names.split("\0") if n] == [WEB_PIN_FILE]:
        rc5, text = git("show", f"{head}:{WEB_PIN_FILE}", repo=REL)
        lines = [l for l in text.splitlines() if l.strip() and not l.lstrip().startswith("#")]
        if rc5 == 0 and len(lines) == 4 and all(re.fullmatch(p, l) for p, l in zip(WEB_PIN_LINES, lines)):
            return True, f"{head[:12]}: the PIN plus only the web pin (web image alone)"
    return False, f"{REL} is at {head[:12]}, which is neither the PIN nor the PIN plus only the web pin"


def key_of(line):
    m = re.match(rb"^([A-Za-z_][A-Za-z0-9_]*)=", line)
    return m.group(1).decode("ascii") if m else None


def value_of(line):
    raw = line.split(b"=", 1)[1].rstrip(b"\r\n").decode("utf-8", "surrogateescape")
    raw = re.sub(r'^"(.*)"$', r"\1", raw)
    return re.sub(r"^'(.*)'$", r"\1", raw)


def ending_of(line):
    return b"\r\n" if line.endswith(b"\r\n") else (b"\n" if line.endswith(b"\n") else b"")


def classify(value):
    if value is None:
        return "absent"
    if value == "":
        return "empty"
    if PLACEHOLDER.search(value):
        return "placeholder-like"
    return "set (https)" if value.startswith("https://") else "set (not https)"


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
    fd, tmp = tempfile.mkstemp(prefix=".u06h-", dir=directory)
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
    rc, out, err = run(["sh", script, "--env-file", ENV_FILE, "--deployment-profile", "azure-hosted-beta", "--skip-compose"], cwd=REL)
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
    replace = "--replace-url" in sys.argv[1:]
    unknown = [a for a in sys.argv[1:] if a != "--replace-url"]
    if unknown:
        stop(f"{len(unknown)} unknown argument(s), not shown; the only option is --replace-url and the URL is never an "
             "argument (if one was typed on the command line, remove it from the shell history)")
    say(f"U06 heartbeat env | stamp {STAMP} | env file {ENV_FILE} | release {REL}")
    say("")
    say("A. Preconditions (nothing changes here)")
    if not os.isatty(0):
        stop("standard input is not a terminal: run it interactively (the URL is read only from a hidden prompt)")
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
        stop(f"run it as the env file's owner ({owner})")
    if not os.access(os.path.dirname(ENV_FILE), os.W_OK | os.X_OK):
        stop("the env file's directory is not writable (needed for the backup and the atomic rename)")
    ok, detail = release_ok()
    if not ok:
        stop(detail)
    say(f"  release checkout: {detail}")
    rc, live_status = git("status", "--porcelain", repo=LIVE)
    say(f"  live checkout {LIVE}: {len(live_status.splitlines())} changes (must stay the same)")
    with open(ENV_FILE, "rb") as fh:
        original = fh.read()
    lines = original.splitlines(keepends=True)
    for line in lines:
        if re.match(rb"^\s*(export\s+)?" + KEY.encode() + rb"\s*=", line) and key_of(line) != KEY:
            stop(f"a non-standard line for {KEY} (leading space, export or space before '='); edit it by hand first")
    count = sum(1 for ln in lines if key_of(ln) == KEY)
    before = last_values(lines)
    say(f"  {KEY}: {classify(before.get(KEY))}" + (f" ({count} lines; every one is rewritten)" if count > 1 else ""))
    if before.get(KEY) and not replace:
        stop(f"{KEY} is already set; to replace it (rotation) run with --replace-url")
    container_before = running_container()
    say(f"  running {SERVICE}: {'one container, recorded' if container_before else 'not exactly one running'}")
    hashes_before, err = model_hashes()
    if hashes_before is None:
        stop(f"the release model does not render: {err}")
    say(f"  release model renders: {len(hashes_before)} services")

    say("")
    say("B. URL entry (hidden; paste the check's ping URL twice, from the DR secrets store)")
    first = getpass.getpass("  ping URL (hidden): ").strip()
    second = getpass.getpass("  same URL again (hidden): ").strip()
    if first != second:
        stop("the two entries differ")
    if not first:
        stop("the entry is empty")
    if not (first.isascii() and first.isprintable()):
        stop("the entry has a non-ASCII or non-printable character")
    if not URL_SHAPE.match(first):
        stop("the entry is not an https URL of the expected shape (no spaces, quotes, '#', '$' or backslash; host and path)")
    if PLACEHOLDER.search(first):
        stop("the entry looks like a placeholder")
    same_as = sorted(k for k, v in before.items() if v == first and k != KEY)
    if same_as:
        stop(f"the entry equals the value of {', '.join(same_as)}; paste the heartbeat ping URL (not the Slack webhook)")
    if before.get(KEY) == first:
        stop("the entry equals the URL already in the file; nothing to change")
    SECRET = first
    say("  entries match; shape accepted")

    say("")
    say("C. Backup and write")
    with open(ENV_FILE, "rb") as fh:
        if fh.read() != original:
            stop("the env file changed while the prompt was open; run the tool again")
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
    if fault("backup-differs"):
        with open(backup, "ab") as fh:
            fh.write(b"#")
    bst = os.stat(backup)
    with open(backup, "rb") as fh:
        same = fh.read() == original
    if not same or (bst.st_mode, bst.st_uid, bst.st_gid) != (st.st_mode, st.st_uid, st.st_gid):
        stop("the backup does not equal the original (content, mode, owner or group); nothing else was changed")
    say(f"  backup {os.path.basename(backup)}: identical content, mode {oct(bst.st_mode & 0o7777)[2:]}, same owner/group")
    nl = b"\r\n" if original.count(b"\r\n") * 2 > original.count(b"\n") else b"\n"
    new_lines = [(KEY.encode() + b"=" + first.encode("ascii") + ending_of(line)) if key_of(line) == KEY else line for line in lines]
    if count == 0:
        if new_lines and not ending_of(new_lines[-1]):
            new_lines[-1] = new_lines[-1] + nl
        new_lines += [COMMENT + nl, KEY.encode() + b"=" + first.encode("ascii") + nl]
    data = b"".join(new_lines)
    write_atomically(data, st)
    if fault("after-write"):
        with open(ENV_FILE, "ab") as fh:
            fh.write(b"#")
    say(f"  {'rewritten in place' if count else 'appended'}: {KEY}")

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
        if key_of(line) == KEY:
            continue
        if i >= len(after_lines) or (after_lines[i] != line and not (i == len(lines) - 1 and count == 0 and after_lines[i] == line + nl)):
            problems.append(f"line {i + 1} changed")
            break
        unchanged += 1
    if (nst.st_mode, nst.st_uid, nst.st_gid) != (st.st_mode, st.st_uid, st.st_gid):
        problems.append("mode, owner or group changed")
    if last_values(after_lines).get(KEY) != first:
        problems.append("the key does not hold the entered value")
    if problems:
        write_atomically(original, st)
        stop("structural check failed (" + "; ".join(problems) + "); the backup content was restored at once", True)
    say(f"  other lines byte-identical and in place: {unchanged}; mode {oct(nst.st_mode & 0o7777)[2:]}, owner and group unchanged")
    say(f"  {KEY}: set, equals the entered value")
    gate("env file holds exactly the intended change", True)
    rc, live_after = git("status", "--porcelain", repo=LIVE)
    gate("live checkout git status unchanged", live_after == live_status, f"{len(live_after.splitlines())} changes")
    ok, detail = release_ok()
    gate("release checkout unchanged and clean", ok, detail)

    say("")
    say("E. Preflight and release model (nothing started)")
    preflight()
    env, err = model_service_env()
    if env is None:
        gate(f"release model gives {SERVICE} the entered URL", False, f"render failed: {err}")
    else:
        gate(f"release model gives {SERVICE} the entered URL", env.get(KEY) == first,
             "equals the entered value" if env.get(KEY) == first else classify(env.get(KEY)))
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
    if failed:
        write_atomically(original, st)
        with open(ENV_FILE, "rb") as fh:
            undone = fh.read() == original
        say("the env change was UNDONE: the file holds its previous content again" if undone else
            f"the env change could NOT be undone automatically; restore it now: cp -p {backup} {ENV_FILE}")
        say("U06 heartbeat env FAILED (see FAIL lines); nothing was restarted")
    else:
        say(f"to undo the env change later (the backup holds the previous state): cp -p {backup} {ENV_FILE}")
        say(f"{SERVICE} keeps its current routing until the authorized recreate (u06-alertmanager-step.py); nothing was restarted")
        say("U06 heartbeat env COMPLETE")
    save()
    sys.exit(0 if not failed else 1)


if __name__ == "__main__":
    main()
