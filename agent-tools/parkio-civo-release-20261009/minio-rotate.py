#!/usr/bin/env python3
"""MinIO root password rotation on parkio-civo-prod (incident 2026-10-13, priority 3: MINIO_ROOT_PASSWORD was in the
world-readable rendered model; media-service uses the same value). Run as civo (Python 3.8+); running it needs the
independent review of agent-tools/parkio-redis-credential-incident-20261013/MINIO-ROTATION-PROCEDURE.md:

  python3 -I minio-rotate.py pre
  python3 -I minio-rotate.py rotate --yes
  python3 -I minio-rotate.py rollback --yes [--unhardened]   (back to the EXPOSED password: only when the rotation
                                                              cannot complete)
  python3 -I minio-rotate.py redact --yes                    (after a passing rotation)
  python3 -I minio-rotate.py status

How the password is used (code and host at R2, MINIO-ROTATION-PROCEDURE.md):
- minio reads MINIO_ROOT_USER and MINIO_ROOT_PASSWORD when it starts. It keeps one root password, and the root
  credentials cannot change while it runs;
- media-service gets the same pair as PARKIO_MEDIA_STORAGE_ACCESS_KEY and PARKIO_MEDIA_STORAGE_SECRET_KEY and builds
  its MinIO clients from them when it starts. Its presigned URLs are signed with them, so URLs issued before the
  cutover stop working;
- minio-setup (one-shot) passes the pair to `mc alias set` on its command line;
- the nightly backup loads the env file when it runs (scripts/lib/backup-common.sh) and logs in with mc.
The owner decided on 2026-10-13 that minio and minio-setup take the release model's reviewed hardening in this
rotation: read-only root, cap_drop ALL, no-new-privileges, a /tmp tmpfs, and MC_CONFIG_DIR for mc. The model differs
from the running minio in exactly that: the model plus minio-emergency-override.yml renders the running container's
hash, which pre checks. The cutover:
  1. the env file gets the new password;
  2. minio is recreated from the model: same image, same minio-data volume, the hardening, the new password;
  3. media-service is recreated with the new value;
  4. minio-setup is recreated without starting (--no-start). Its container no longer holds the old value, and its
     command line never carries the new one.
Media storage calls fail between steps 2 and 3, for about a minute.
Logins are checked the way media-service and the backup reach MinIO: from a short-lived container of the release's mc
image on minio's network, to http://minio:9000. The user and password go on stdin, where `mc alias set ALIAS URL`
reads them; no value reaches a process argument, the output or a record.

pre (read-only):
- host, docker, dockerd debug off, the release checkout clean at R2; outside 03:00-04:30 UTC (the backup);
- the env file's password is at least 32 characters; minio, minio-setup and media-service carry it, in the model and
  live (any project, inside Env only), and nothing else does;
- minio runs the model's image on the minio-data volume, and its hash equals the model's plus the emergency override
  (pinned by SHA-256): the hardening is the only change a recreate brings;
- media-service is healthy, at the model's hash, runs the model's image and minio's user and password, and its
  aggregate health answers 200;
- the login discriminates: the current password logs in, a random wrong one is refused;
- the nightly backup's own env loader yields minio's user and password;
- the objects of the media bucket are listed (the count is recorded); the public API answers 200.
rotate (--yes), within an hour of a passing pre, with the same env file and containers: the four steps above.
- After minio's recreate: a new healthy container, at the model's hash, on the same image and volume, with the
  hardening and the new value. The new password logs in, the old one is refused, and every object listed before is
  there with the same size and ETag; up to three sample objects read back with the same content hash.
- After media-service's recreate: healthy, at the model's hash, with the new pair. Its aggregate health answers 200,
  its own credentials (read from its live env) log in, and its log since the start has no MinIO authentication error.
- minio-setup: a new container, created and not started, at the model's hash, with the hardening and the new value.
- The backup's env loader yields the new password, and it logs in.
- At the end: no container carries the old value (any project); the three carriers are at the model's hash; the
  public API answers 200.
rollback (--yes), after a failed or interrupted rotation:
- restores the env file from the rotation's backup;
- recreates each carrier whose live value is not the original, judged by live state: minio first, then media-service,
  then minio-setup (not started). --unhardened adds minio-emergency-override.yml to minio's recreate, which renders
  the pre-rotation definition; use it when minio does not come back healthy under the hardening;
- verifies that the original password logs in, the new one is refused, the bucket keeps at least the object count pre
  recorded, every carrier runs the original value, media-service's aggregate health answers 200, the backup's loader
  yields the original, and the public API answers 200.
This brings the EXPOSED password back; the rollback says so, and the rotation should be repeated once the cause is
fixed.
redact (--yes), after a passing rotation, while the old password is refused:
- replaces the old value with CHANGE_ME_redacted_after_rotation_20261013 in every
  /opt/parkio/docker/.env.azure-hosted-beta.bak-* file, the pr68 artifact and the checkpoint env copy;
- scans /opt, /home, /usr/local, /tmp and /var/tmp in memory, paths only;
- redacts its own backup last.
Records: ~/parkio-release-20261009/minio-rotation/mr-<stamp>/ (mode 700, files 600). Exit 0 = PASS, 1 = FAIL,
2 = usage.
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

TEST = os.environ.get("MRT_TEST") == "1"


def cfg(name, default):
    """Overrides are honoured only by the self-test."""
    return os.environ.get(name, default) if TEST else default


R2 = cfg("MRT_R2", "c30b9ddbc960cac7e45566c940403dfed1739c7c")
HOST = cfg("MRT_HOST", "parkio-civo-prod")
REL = cfg("MRT_RELEASE", "/opt/parkio-release")
ENV_FILE = cfg("MRT_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
OUT = cfg("MRT_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009", "minio-rotation"))
PUBLIC_PROBE = cfg("MRT_PUBLIC_PROBE", "https://api.parkio.dev/api/v1/auth/registration-mode")
NOW = cfg("MRT_NOW", "")
HEALTH_WAIT = int(cfg("MRT_HEALTH_WAIT", "300"))
LOGIN_WAIT = int(cfg("MRT_LOGIN_WAIT", "120"))
POLL = float(cfg("MRT_POLL", "5"))
REDACT_GLOB = cfg("MRT_REDACT_GLOB", "/opt/parkio/docker/.env.azure-hosted-beta.bak-*")
REDACT_EXTRA = cfg("MRT_REDACT_EXTRA", ":".join((
    "/opt/parkio/backup-artifacts/pr68-auth-email-20260922T114212Z/env.azure-hosted-beta.pre",
    "/opt/parkio-recovery/checkpoint-550848-postrestore/.env.azure-hosted-beta"))).split(":")
SCAN_ROOTS = cfg("MRT_SCAN_ROOTS", "/opt:/home:/usr/local:/tmp:/var/tmp").split(":")
# the nightly backup: /etc/cron.d/parkio-backup runs /opt/parkio/scripts/run-production-backup.sh, which exports
# PARKIO_ENV_FILE=docker/.env.azure-hosted-beta from /opt/parkio; backup-minio.sh loads it with this library
BACKUP_CWD = cfg("MRT_BACKUP_CWD", "/opt/parkio")
BACKUP_LIB = cfg("MRT_BACKUP_LIB", "/opt/parkio/scripts/lib/backup-common.sh")
BACKUP_ENV = "docker/.env.azure-hosted-beta"
OVERRIDE = cfg("MRT_OVERRIDE", os.path.join(os.path.dirname(os.path.abspath(__file__)), "minio-emergency-override.yml"))
OVERRIDE_SHA256 = cfg("MRT_OVERRIDE_SHA256", "52c73e059409f730c5244af7cfd1fdfee35c588d90f02e92a11d2ce04a3ef148")
FAULT = cfg("MRT_FAULT", "")                    # self-test only: simulate an interruption at a named point
PROJECT = "parkio"
KEY, USER_KEY = "MINIO_ROOT_PASSWORD", "MINIO_ROOT_USER"
MEDIA_KEY, MEDIA_USER_KEY = "PARKIO_MEDIA_STORAGE_SECRET_KEY", "PARKIO_MEDIA_STORAGE_ACCESS_KEY"
MINIO, SETUP, MEDIA, GATEWAY = "minio", "minio-setup", "media-service", "gateway-service"
CARRIERS = (MINIO, SETUP, MEDIA)
MEDIA_PORT = 8084
# the release model's reviewed hardening, as docker inspect reports it
HARDENING = {MINIO: {"ReadonlyRootfs": True, "CapDrop": ["ALL"], "SecurityOpt": ["no-new-privileges:true"], "Tmpfs": {"/tmp": "size=16m"}},
             SETUP: {"ReadonlyRootfs": True, "CapDrop": ["ALL"], "SecurityOpt": ["no-new-privileges:true"], "Tmpfs": {"/tmp": "size=8m"}}}
DATA_VOLUME = f"{PROJECT}_minio-data"
REFUSED = re.compile(r"signature we calculated does not match|Access Key Id you provided does not exist|InvalidAccessKeyId|SignatureDoesNotMatch")
AUTH_ERRORS = re.compile(rb"SignatureDoesNotMatch|InvalidAccessKeyId|signature we calculated does not match|Access Key Id you provided does not exist")
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
    if TEST and "MRT_W" in os.environ:
        env["MRT_W"] = os.environ["MRT_W"]
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


def wrapper(*args, extra_files=(), timeout=900):
    files = [x for f in extra_files for x in ("-f", f)]
    return run(["./scripts/parkio-prod-compose.sh"] + overlays() + files + list(args), cwd=REL, extra_env={"PARKIO_ENV_FILE": ENV_FILE}, timeout=timeout)


def private_write(path, text):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(text)


def record_dir():
    d = os.path.join(OUT, f"mr-{STAMP}")
    os.makedirs(d, mode=0o700, exist_ok=True)
    return d


def save_progress(rec, path=None):
    text = json.dumps({k: rec.get(k) for k in ("stamp", "phase", "backup")}, sort_keys=True)
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
    say(f"minio rotation {name}: {rec['verdict']} | record {path}")
    return 0 if rec["verdict"] == "PASS" else 1


def latest_path(name):
    try:
        found = [os.path.join(OUT, d, f"{name}.json") for d in sorted(os.listdir(OUT)) if d.startswith("mr-")]
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
def env_value(data, key=KEY):
    for line in data.splitlines():
        if line.startswith(key.encode() + b"="):
            v = line.split(b"=", 1)[1].decode("utf-8", "replace").strip()
            if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
                v = v[1:-1]
            return v
    return ""


def with_value(data, value):
    """data with the password line set to KEY="value" (the live file's form)."""
    out = []
    for line in data.splitlines(keepends=True):
        if line.startswith(KEY.encode() + b"="):
            end = b"\r\n" if line.endswith(b"\r\n") else (b"\n" if line.endswith(b"\n") else b"")
            line = KEY.encode() + b'="' + value.encode("ascii") + b'"' + end
        out.append(line)
    return b"".join(out)


def only_key_changed(before, after):
    strip = lambda d: [l for l in d.splitlines() if not l.startswith(KEY.encode() + b"=")]
    return strip(before) == strip(after)


def write_atomically(path, data, st):
    fd, tmp = tempfile.mkstemp(prefix=".mrt-", dir=os.path.dirname(path))
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


# ------------------------------------------------------------------ containers and the model
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
    return dict(e.split("=", 1) for e in (d or {}).get("Config", {}).get("Env") or [] if "=" in e)


def parkio(svc, ds=None):
    found = [d for d in (ds if ds is not None else inspect_all()) if service_of(d) == (PROJECT, svc)]
    return found[0] if len(found) == 1 else None


def health(d):
    return ((d or {}).get("State") or {}).get("Health", {}).get("Status")


def status(d):
    return ((d or {}).get("State") or {}).get("Status")


def name_of(d):
    return (d or {}).get("Name", "").lstrip("/")


def label(d, key):
    return (((d or {}).get("Config") or {}).get("Labels") or {}).get(key)


def model():
    rc, out, _ = wrapper("config", "--format", "json", timeout=300)
    try:
        return json.loads(out) if rc == 0 else None
    except ValueError:
        return None


def model_hashes(extra_files=()):
    rc, out, _ = wrapper("config", "--hash", "*", extra_files=extra_files, timeout=300)
    if rc != 0:
        return None
    return dict(l.split() for l in out.decode().splitlines() if len(l.split()) == 2)


def model_service(m, svc):
    return ((m or {}).get("services") or {}).get(svc) or {}


def image_id(ref):
    rc, out, _ = run(["docker", "image", "inspect", "--format", "{{.Id}}", ref])
    return out.decode().strip() if rc == 0 else None


def image_ok(m, d, svc):
    ref = model_service(m, svc).get("image") or f"{PROJECT}-{svc}"
    return bool(d) and (d.get("Config") or {}).get("Image") == ref and image_id(ref) == d.get("Image")


def data_volume(d):
    return [x.get("Name") for x in (d or {}).get("Mounts") or [] if x.get("Destination") == "/data"]


def hardened(d, svc):
    hc = (d or {}).get("HostConfig") or {}
    return all(hc.get(k) == v for k, v in HARDENING[svc].items())


def baseline(ds):
    return {f"{p}/{s}": d.get("Id") for d in ds for p, s in [service_of(d)] if p}


def changed_since(base, ds, allowed):
    cur = baseline(ds)
    return sorted(k for k in set(base) | set(cur) if k not in allowed and base.get(k) != cur.get(k))


def wait_container(svc, before_id, want="healthy"):
    """A new container of svc that is running and healthy (want="healthy") or just created (want="created")."""
    deadline, d = time.time() + HEALTH_WAIT, None
    while time.time() < deadline:
        d = parkio(svc)
        if d and d.get("Id") != before_id:
            if want == "created" and status(d) == "created":
                return d
            if want == "healthy" and status(d) == "running" and health(d) == "healthy":
                return d
        time.sleep(POLL)
    return d


def carriers(value, ds):
    """(containers carrying value anywhere in their config, those carrying it outside Env) by name, any project."""
    live = sorted(name_of(d) for d in ds if value and value in json.dumps(d.get("Config") or {}))
    outside = sorted(name_of(d) for d in ds if value and value in json.dumps({k: v for k, v in (d.get("Config") or {}).items() if k != "Env"}))
    return live, outside


# ------------------------------------------------------------------ MinIO, media-service and the backup
_MC = None


def mc_target():
    """(the network minio is on, the image id of the model's mc image)."""
    global _MC
    if _MC is None:
        d = parkio(MINIO)
        nets = sorted(((d or {}).get("NetworkSettings") or {}).get("Networks", {}).keys())
        ref = model_service(model(), SETUP).get("image")
        _MC = (nets[0] if len(nets) == 1 else None, image_id(ref) if ref else None)
    return _MC


def mc(user, password, script, timeout=120):
    """`script` in a short-lived container of the model's mc image on minio's network, after `mc alias set t
    http://minio:9000` read the user and password from stdin. (exit code, output)."""
    net, img = mc_target()
    if not net or not img:
        return 125, "no network or mc image"
    rc, out, err = run(["docker", "run", "--rm", "-i", "--network", net, "--read-only", "--cap-drop", "ALL",
                        "--security-opt", "no-new-privileges:true", "--tmpfs", "/tmp:size=8m", "-e", "MC_CONFIG_DIR=/tmp/.mc",
                        "--entrypoint", "/bin/sh", img, "-c", "mc alias set t http://minio:9000 >/dev/null && { " + script + "; }"],
                       stdin=f"{user}\n{password}\n".encode(), timeout=timeout)
    return rc, (out + err).decode("utf-8", "replace")


def login(user, password):
    """True: the pair logs in; False: MinIO refuses it; None: anything else (minio unreachable, no mc image, ...).
    A freshly started minio answers "server not initialized" for a moment, so an undecided answer is retried for up
    to LOGIN_WAIT seconds."""
    deadline = time.time() + LOGIN_WAIT
    while True:
        rc, out = mc(user, password, "mc ls t >/dev/null && echo LOGIN-OK")
        if rc == 0 and "LOGIN-OK" in out:
            return True
        if REFUSED.search(out):
            return False
        if time.time() >= deadline:
            return None
        time.sleep(POLL)


def objects(user, password, bucket):
    """{sha256(key): (size, etag)} of every object in the bucket and {sha256(key): content sha256} of up to three of
    the smallest, or (None, None). Keys and content never leave the mc container unhashed."""
    rc, out = mc(user, password, f'mc ls --recursive --json "t/{bucket}"', timeout=900)
    if rc != 0:
        return None, None
    listing = {}
    for line in out.splitlines():
        try:
            o = json.loads(line)
        except ValueError:
            continue
        if o.get("status") == "success" and o.get("type") == "file" and o.get("key"):
            listing[o["key"]] = (o.get("size"), o.get("etag"))
    sample = sorted(listing, key=lambda k: (listing[k][0] or 0, k))[:3]
    hashes = {}
    for key in sample:
        if "'" in key:
            continue
        rc, h = mc(user, password, f"mc cat 't/{bucket}/{key}' | sha256sum | cut -d' ' -f1")
        hashes[hashlib.sha256(key.encode()).hexdigest()] = h.strip() if rc == 0 else None
    return {hashlib.sha256(k.encode()).hexdigest(): v for k, v in listing.items()}, hashes


def bucket_of(m):
    env = model_service(m, SETUP).get("environment") or {}
    return (env.get("MINIO_BUCKET") if isinstance(env, dict) else None) or "parkio-media"


def media_health(d):
    """The HTTP status of media-service's aggregate /actuator/health."""
    rc, out, _ = run(["docker", "exec", name_of(d), "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                      f"http://localhost:{MEDIA_PORT}/actuator/health"], timeout=60)
    return out.decode().strip() if rc == 0 else f"exit {rc}"


def auth_errors_since_start(d):
    rc, out, err = run(["docker", "logs", "--since", ((d or {}).get("State") or {}).get("StartedAt", "0"), name_of(d)], timeout=120)
    return len(AUTH_ERRORS.findall(out + err)) if rc == 0 else None


def backup_loader():
    """(user, password) as the nightly backup loads them: its own library and env-file path, from /opt/parkio. The
    pair comes back on the child's stdout, into memory only."""
    script = (f'. "{BACKUP_LIB}" && parkio_backup_load_env "{BACKUP_ENV}" && '
              'printf "MRT-USER:%s\\nMRT-PASS:%s\\n" "${MINIO_ROOT_USER:-minioadmin}" "${MINIO_ROOT_PASSWORD:-}"')
    rc, out, _ = run(["bash", "-c", script], cwd=BACKUP_CWD, timeout=60)
    got = dict(l.split(":", 1) for l in out.decode("utf-8", "replace").splitlines() if l.startswith(("MRT-USER:", "MRT-PASS:")))
    return (got.get("MRT-USER"), got.get("MRT-PASS")) if rc == 0 else (None, None)


def public_ok():
    try:
        with urllib.request.urlopen(urllib.request.Request(PUBLIC_PROBE), timeout=20) as resp:
            return resp.status == 200
    except (urllib.error.URLError, OSError):
        return False


def release_ok():
    rc, head = git("rev-parse", "HEAD")
    rc2, st = git("status", "--porcelain")
    head = head.strip()
    return rc == 0 and rc2 == 0 and head == R2 and not st.strip(), head[:12] or "?"


def override_ok():
    try:
        return hashlib.sha256(open(OVERRIDE, "rb").read()).hexdigest() == OVERRIDE_SHA256
    except OSError:
        return False


def basics():
    check(f"host {HOST}", socket.gethostname() == HOST, socket.gethostname())
    rc, _, _ = run(["docker", "info", "--format", "{{.ServerVersion}}"])
    check("docker usable without sudo", rc == 0)
    rc, out, _ = run(["docker", "info", "--format", "{{.Debug}}"])
    check("dockerd debug logging is off (it would log exec and run requests)", rc == 0 and out.decode().strip() == "false", out.decode().strip())
    ok, head = release_ok()
    check("the release checkout is clean at R2", ok, head)


# ------------------------------------------------------------------ commands
def cmd_pre():
    say(f"minio rotation pre | {STAMP} | read-only")
    rec = {"tool": "minio-rotate", "command": "pre", "stamp": STAMP}
    basics()
    t = now()
    check("outside 03:00-04:30 UTC (the backup)", not (t.hour == 3 or (t.hour == 4 and t.minute < 30)), t.strftime("%H:%M UTC"))
    say(f"  NOTE Compose calls {'include ' + INBOX_OVERLAY + ' (the live gateway was created with it)' if overlays() else 'use the production file list only'}")
    data = open(ENV_FILE, "rb").read()
    cur = env_value(data)
    SECRETS.append(cur)
    check("the env file's password is at least 32 characters", len(cur) >= 32)
    ds = inspect_all()
    m = model()
    if not check("the release model renders", m is not None):
        return finish("pre", rec)
    in_model = sorted(s for s, sv in (m.get("services") or {}).items() if cur and cur in json.dumps(sv))
    check("in the model, exactly minio, minio-setup and media-service carry the value", in_model == sorted(CARRIERS), ", ".join(in_model))
    live, outside = carriers(cur, ds)
    want = sorted(name_of(parkio(s, ds)) for s in CARRIERS if parkio(s, ds))
    check("the live containers carrying the value are exactly minio, minio-setup and media-service, inside Env only (any project)",
          live == want and len(want) == len(CARRIERS) and not outside, ", ".join(live))
    mi, se, me = (parkio(s, ds) for s in CARRIERS)
    user = env_of(mi).get(USER_KEY, "")
    check("minio runs the env file's password, is healthy, runs the model's image, on the minio-data volume",
          bool(mi) and env_of(mi).get(KEY) == cur and bool(user) and health(mi) == "healthy" and image_ok(m, mi, MINIO)
          and data_volume(mi) == [DATA_VOLUME], f"{health(mi)}, {data_volume(mi)}")
    check("the emergency override is the reviewed file", override_ok())
    em = model_hashes(extra_files=(OVERRIDE,)) or {}
    check("minio's hash equals the model's plus the emergency override: the hardening is all a recreate changes",
          bool(mi) and label(mi, "com.docker.compose.config-hash") == em.get(MINIO))
    check("minio-setup runs the model's image", image_ok(m, se, SETUP), status(se) or "?")
    hashes = model_hashes() or {}
    check("media-service: healthy, at the model's hash, running the model's image, with minio's user and password",
          bool(me) and health(me) == "healthy" and label(me, "com.docker.compose.config-hash") == hashes.get(MEDIA) and image_ok(m, me, MEDIA)
          and env_of(me).get(MEDIA_KEY) == cur and env_of(me).get(MEDIA_USER_KEY) == user, health(me) or "?")
    check("media-service's aggregate health answers 200", bool(me) and media_health(me) == "200")
    wrong = secrets.token_urlsafe(33)
    check("the login discriminates: the current password logs in, a random wrong one is refused",
          login(user, cur) is True and login(user, wrong) is False)
    bu, bp = backup_loader()
    check("the nightly backup's env loader yields minio's user and password", bu == user and bp == cur)
    listing, _ = objects(user, cur, bucket_of(m))
    check("the media bucket's objects are listed", listing is not None, f"{len(listing or {})} object(s)")
    rec["objects"] = len(listing or {})
    check("the public API answers 200 through the gateway", public_ok())
    rec["baseline"] = baseline(ds)
    rec["envSha256"] = hashlib.sha256(data).hexdigest()
    return finish("pre", rec)


def set_env(data, value, st, rec):
    """Write the new password into the env file; checks: only that line changed, the preflight passes, exactly the
    three carriers' model hashes changed. On a failure the env file is restored to `data`."""
    hb = model_hashes() or {}
    new = with_value(data, value)
    rec["phase"] = "env"
    save_progress(rec)
    write_atomically(ENV_FILE, new, st)
    if FAULT == "after-env-write":
        raise SystemExit(99)
    after = open(ENV_FILE, "rb").read()
    ok = check(f"only {KEY} changed in the env file, mode and owner kept",
               after == new and only_key_changed(data, after) and (os.stat(ENV_FILE).st_mode, os.stat(ENV_FILE).st_uid) == (st.st_mode, st.st_uid))
    ok &= check("the release preflight passes", preflight_ok())
    ha = model_hashes() or {}
    changed = sorted(s for s in set(hb) | set(ha) if hb.get(s) != ha.get(s))
    ok &= check("exactly minio, minio-setup and media-service changed in the model", changed == sorted(CARRIERS), ", ".join(changed))
    if not ok:
        write_atomically(ENV_FILE, data, st)
        say("  the env file was restored; nothing was recreated")
    return ok


def cmd_rotate(yes):
    if not yes:
        print("usage: rotate needs --yes (after the procedure's review and gates)", file=sys.stderr)
        return 2
    say(f"minio rotation rotate | {STAMP}")
    rec = {"tool": "minio-rotate", "command": "rotate", "stamp": STAMP, "phase": "checks"}
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
    old = env_value(data)
    new = secrets.token_urlsafe(33)
    while PLACEHOLDER.search(new):
        new = secrets.token_urlsafe(33)
    SECRETS.extend([old, new])
    st = os.stat(ENV_FILE)
    m = model()
    bucket = bucket_of(m)
    user = env_of(parkio(MINIO)).get(USER_KEY, "")
    backup = f"{ENV_FILE}.bak-mrt-{STAMP}"
    fd = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, st.st_mode & 0o7777)
    with os.fdopen(fd, "wb") as fh:
        fh.write(data)
        fh.flush()
        os.fsync(fh.fileno())
        os.fchown(fh.fileno(), st.st_uid, st.st_gid)
    rec["backup"] = os.path.basename(backup)
    if not check("backup identical to the original", open(backup, "rb").read() == data):
        return finish("rotate", rec)
    before_list, before_content = objects(user, old, bucket)
    if not check("the media bucket's objects are listed before the cutover", before_list is not None, f"{len(before_list or {})} object(s)"):
        return finish("rotate", rec)
    rec["objects"] = len(before_list)
    base = baseline(inspect_all())

    say("1. the env file gets the new password")
    if not set_env(data, new, st, rec):
        return finish("rotate", rec)

    say("2. minio is recreated from the model (the hardening, the same volume, the new password)")
    rec["phase"] = "minio"
    save_progress(rec)
    mi = parkio(MINIO)
    rc, _, _ = wrapper("up", "-d", "--no-build", "--no-deps", MINIO)
    if FAULT == "after-up:minio":
        raise SystemExit(99)
    d = wait_container(MINIO, (mi or {}).get("Id"))
    good = check("minio: a new healthy container, at the model's hash, on the same image and volume",
                 rc == 0 and bool(d) and d.get("Id") != (mi or {}).get("Id") and health(d) == "healthy"
                 and label(d, "com.docker.compose.config-hash") == (model_hashes() or {}).get(MINIO)
                 and d.get("Image") == (mi or {}).get("Image") and data_volume(d) == [DATA_VOLUME], f"exit {rc}, {health(d) or '?'}, {data_volume(d)}")
    good &= check("minio: the reviewed hardening and the new password", hardened(d, MINIO) and env_of(d).get(KEY) == new)
    good &= check("minio: the new password logs in; the old one is refused", login(user, new) is True and login(user, old) is False)
    after_list, after_content = objects(user, new, bucket)
    missing = [k for k, v in (before_list or {}).items() if (after_list or {}).get(k) != v]
    good &= check("minio: every object listed before is there with the same size and ETag; the samples read back the same",
                  after_list is not None and not missing and after_content == before_content and all(before_content.values()),
                  f"{len(after_list or {})} object(s), {len(missing)} missing or changed, {len(before_content)} sample(s)")
    others = changed_since(base, inspect_all(), {f"{PROJECT}/{MINIO}"})
    good &= check("minio: every other container unchanged", not others, ", ".join(others))
    if not good:
        say("  STOP: run `rollback --yes` (add --unhardened when minio does not come back healthy under the hardening)")
        return finish("rotate", rec)

    say("3. media-service is recreated with the new value")
    rec["phase"] = "media"
    save_progress(rec)
    me = parkio(MEDIA)
    rc, _, _ = wrapper("up", "-d", "--no-build", "--no-deps", MEDIA)
    if FAULT == "after-up:media-service":
        raise SystemExit(99)
    d = wait_container(MEDIA, (me or {}).get("Id"))
    good = check("media-service: a new healthy container at the model's hash",
                 rc == 0 and bool(d) and d.get("Id") != (me or {}).get("Id") and health(d) == "healthy"
                 and label(d, "com.docker.compose.config-hash") == (model_hashes() or {}).get(MEDIA), f"exit {rc}, {health(d) or '?'}")
    e = env_of(d)
    good &= check("media-service: runs the new password and minio's user", e.get(MEDIA_KEY) == new and e.get(MEDIA_USER_KEY) == user)
    good &= check("media-service: its aggregate health answers 200", bool(d) and media_health(d) == "200")
    good &= check("media-service: its own credentials log in", bool(d) and login(e.get(MEDIA_USER_KEY, ""), e.get(MEDIA_KEY, "")) is True)
    good &= check("media-service: no MinIO authentication error in its log since it started", bool(d) and auth_errors_since_start(d) == 0)
    others = changed_since(base, inspect_all(), {f"{PROJECT}/{s}" for s in (MINIO, MEDIA)})
    good &= check("media-service: every other container unchanged", not others, ", ".join(others))
    if not good:
        say("  STOP: run `rollback --yes`")
        return finish("rotate", rec)

    say("4. minio-setup is recreated without starting")
    rec["phase"] = "setup"
    save_progress(rec)
    se = parkio(SETUP)
    rc, _, _ = wrapper("up", "--no-start", "--no-deps", SETUP)
    d = wait_container(SETUP, (se or {}).get("Id"), want="created")
    good = check("minio-setup: a new container, created and not started, at the model's hash, with the hardening and the new password",
                 rc == 0 and bool(d) and d.get("Id") != (se or {}).get("Id") and status(d) == "created" and hardened(d, SETUP)
                 and label(d, "com.docker.compose.config-hash") == (model_hashes() or {}).get(SETUP) and env_of(d).get(KEY) == new,
                 f"exit {rc}, {status(d) or '?'}")
    bu, bp = backup_loader()
    good &= check("the nightly backup's env loader yields the new password, and it logs in", bu == user and bp == new and login(bu, bp) is True)
    if not good:
        say("  STOP: run `rollback --yes`")
        return finish("rotate", rec)

    check("the public API answers 200 through the gateway", public_ok())
    ds = inspect_all()
    holders, _ = carriers(old, ds)
    check("no container carries the old value anywhere (any project)", not holders, ", ".join(holders))
    hashes = model_hashes() or {}
    off = [s for s in CARRIERS if label(parkio(s, ds), "com.docker.compose.config-hash") != hashes.get(s)]
    check("minio, minio-setup and media-service are at the model's hash", not off, ", ".join(off))
    rec["phase"] = "done" if all(c["pass"] for c in CHECKS) else "setup"
    save_progress(rec)
    say(f"  the env backup {rec['backup']} holds the old, now refused value; `redact --yes` replaces it after acceptance")
    return finish("rotate", rec)


def cmd_rollback(yes, unhardened):
    if not yes:
        print("usage: rollback needs --yes", file=sys.stderr)
        return 2
    say(f"minio rotation rollback | {STAMP} | brings the EXPOSED password back" + (" | minio without the hardening (emergency override)" if unhardened else ""))
    rec = {"tool": "minio-rotate", "command": "rollback", "stamp": STAMP, "unhardened": unhardened}
    path = latest_path("rotate-progress")
    prog = json.load(open(path, encoding="utf-8")) if path else None
    phase = (prog or {}).get("phase")
    if not check("a rotation stopped after its backup (phase env, minio, media or setup)", phase in ("env", "minio", "media", "setup"), phase or "none"):
        return finish("rollback", rec)
    if unhardened and not check("the emergency override is the reviewed file", override_ok()):
        return finish("rollback", rec)
    try:
        old_data = open(os.path.join(os.path.dirname(ENV_FILE), prog.get("backup") or ""), "rb").read() if prog.get("backup") else None
    except OSError:
        old_data = None
    if not check("the rotation's backup exists and is readable", old_data is not None):
        return finish("rollback", rec)
    data = open(ENV_FILE, "rb").read()
    if not check(f"the backup differs from the env file only in the {KEY} line", only_key_changed(old_data, data)):
        return finish("rollback", rec)
    old = env_value(old_data)
    ds = inspect_all()
    found = [env_value(data)] + [env_of(parkio(s, ds)).get(k, "") for s in CARRIERS for k in (KEY, MEDIA_KEY)]
    new = next((v for v in found if v and v != old), "")
    SECRETS.extend([old, new])
    if data != old_data:
        write_atomically(ENV_FILE, old_data, os.stat(ENV_FILE))
    check("the env file is byte-identical to the backup", open(ENV_FILE, "rb").read() == old_data)
    pre = latest("pre") or {}
    user = env_of(parkio(MINIO, ds)).get(USER_KEY, "") or (backup_loader()[0] or "")

    mi = parkio(MINIO, ds)
    if not mi or env_of(mi).get(KEY) != old or health(mi) != "healthy" or unhardened:
        rc, _, _ = wrapper("up", "-d", "--no-build", "--no-deps", "--force-recreate", MINIO, extra_files=(OVERRIDE,) if unhardened else ())
        d = wait_container(MINIO, (mi or {}).get("Id"))
        check("minio: recreated and healthy on the minio-data volume" + (" (emergency override)" if unhardened else ""),
              rc == 0 and bool(d) and d.get("Id") != (mi or {}).get("Id") and health(d) == "healthy" and data_volume(d) == [DATA_VOLUME])
    me = parkio(MEDIA)
    if not me or env_of(me).get(MEDIA_KEY) != old:
        rc, _, _ = wrapper("up", "-d", "--no-build", "--no-deps", "--force-recreate", MEDIA)
        d = wait_container(MEDIA, (me or {}).get("Id"))
        check("media-service: recreated and healthy", rc == 0 and bool(d) and d.get("Id") != (me or {}).get("Id") and health(d) == "healthy")
    se = parkio(SETUP)
    if not se or env_of(se).get(KEY) != old:
        rc, _, _ = wrapper("up", "--no-start", "--no-deps", "--force-recreate", SETUP)
        d = wait_container(SETUP, (se or {}).get("Id"), want="created")
        check("minio-setup: recreated, not started", rc == 0 and bool(d) and d.get("Id") != (se or {}).get("Id") and status(d) == "created")

    ds = inspect_all()
    mi, se, me = (parkio(s, ds) for s in CARRIERS)
    check("every carrier runs the original password", bool(mi and se and me) and env_of(mi).get(KEY) == old and env_of(se).get(KEY) == old
          and env_of(me).get(MEDIA_KEY) == old)
    check("minio and media-service are healthy; media-service's aggregate health answers 200",
          health(mi) == "healthy" and health(me) == "healthy" and media_health(me) == "200")
    check("the original password logs in; the rotation's password is refused", login(user, old) is True and (not new or login(user, new) is False))
    listing, _ = objects(user, old, bucket_of(model()))
    check("the media bucket keeps at least the object count pre recorded", listing is not None and len(listing) >= (pre.get("objects") or 0),
          f"{len(listing or {})} object(s), pre {pre.get('objects')}")
    bu, bp = backup_loader()
    check("the nightly backup's env loader yields the original password", bu == user and bp == old)
    check("the public API answers 200 through the gateway", public_ok())
    if all(c["pass"] for c in CHECKS):
        prog["phase"] = "rolled-back"
        save_progress(prog, path)
        say("  the rotation is marked rolled-back. The EXPOSED password is live again: fix the cause, run pre, rotate again")
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
        fd, tmp = tempfile.mkstemp(prefix=".mrt-redact-", dir=os.path.dirname(path))
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
    say(f"minio rotation redact | {STAMP}")
    rec = {"tool": "minio-rotate", "command": "redact", "stamp": STAMP}
    rot, prog = latest("rotate"), latest("rotate-progress")
    if not check("the latest rotation passed (phase done)", bool(rot) and rot.get("verdict") == "PASS" and (prog or {}).get("phase") == "done"
                 and rot.get("stamp") == (prog or {}).get("stamp")):
        return finish("redact", rec)
    backup = os.path.join(os.path.dirname(ENV_FILE), rot.get("backup") or "")
    old = env_value(open(backup, "rb").read()) if os.path.isfile(backup) else ""
    if old == MARKER:
        check("redaction already complete: the rotation's backup no longer holds the old value", True)
        return finish("redact", rec)
    new = env_value(open(ENV_FILE, "rb").read())
    SECRETS.extend([old, new])
    user = env_of(parkio(MINIO)).get(USER_KEY, "")
    if not check("the old value is known (the rotation's backup) and refused; the current one logs in",
                 len(old) >= 32 and old != new and login(user, old) is False and login(user, new) is True):
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
    yes = "--yes" in rest
    unhardened = "--unhardened" in rest
    allowed = {"rollback": {"--yes", "--unhardened"}, "rotate": {"--yes"}, "redact": {"--yes"}}.get(cmd, set())
    if len(set(rest)) != len(rest) or not set(rest) <= allowed:
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
    return cmd_rollback(yes, unhardened)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
