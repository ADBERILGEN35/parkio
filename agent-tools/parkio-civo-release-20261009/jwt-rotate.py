#!/usr/bin/env python3
"""JWT signing-key rotation on parkio-civo-prod (incident 2026-10-13: the signing key was in a world-readable rendered
model). Run as civo (Python 3.8+, openssl 3) after the Redis rotation; running it needs the independent review of
agent-tools/parkio-redis-credential-incident-20261013/JWT-ROTATION-PROCEDURE.md:

  python3 -I jwt-rotate.py pre
  python3 -I jwt-rotate.py rotate --yes
  python3 -I jwt-rotate.py rollback --yes     (brings the EXPOSED key back: only when the new key cannot be served)
  python3 -I jwt-rotate.py redact --yes       (after a passing rotation)
  python3 -I jwt-rotate.py status

Who uses the key:
- auth-service signs access tokens (PARKIO_JWT_PRIVATE_KEY_PEM, PARKIO_JWT_KEY_ID) and verifies its own;
- gateway-service verifies through auth-service's JWKS (cached 15 min, refreshed on an unknown kid);
- nothing else (2026-10-13 code survey).
Refresh tokens are opaque database rows, not signed with this key, so sessions survive: an access token signed with
the old key is refused, and the client refreshes it.

No key or token is ever printed, logged, passed as a process argument or written outside the env file:
- the new key comes from `openssl genpkey` on stdout;
- keys reach openssl on stdin or an inherited pipe;
- probe tokens live 60 s and stay in memory.

pre (read-only):
- host, docker, and the release checkout clean at R2 (the Redis rotation's checkout); outside 03:00-04:30 UTC;
- the env file's PEM and kid are the ones auth-service runs with, and no additional (previous) public keys are set;
- in the model and in the live containers of every project, only auth-service carries the PEM;
- auth-service and gateway-service are running, healthy and at the model's hash, their images resolving to the
  running ones;
- the public JWKS lists exactly one key: the current kid, with the current key's modulus;
- the probe works against the live gateway: a token signed with the current key, for a random subject with
  session_epoch -1, stops at the session-epoch check. That is the baseline for phase E, so an INVALID_TOKEN there
  cannot be a probe artifact (review J3).
rotate (--yes), within an hour of a passing pre, with the same env file and containers:
  A. generates a new RSA-2048 PKCS#8 key and a new kid, parkio-auth-rs256-<UTC minute>;
  B. backs the env file up, then atomically rewrites the PEM and kid lines in the same quoted single-line form. It
     checks that only those two lines changed, that the preflight passes, and that only auth-service's model hash
     changed;
  C. recreates auth-service. It must be healthy, with the new PEM and kid, at the model's hash, every other container
     unchanged, and the public JWKS must list exactly the new kid with the new modulus;
  D. recreates gateway-service (--force-recreate: an empty JWKS cache). It must be healthy, at the model's hash, every
     other container unchanged;
  E. probes the public API:
     - tokens signed with the old key, under the old kid or the new one, are refused with INVALID_TOKEN;
     - a token signed with the new key, for a random non-existent subject with session_epoch -1, passes the
       signature and stops at the session-epoch check (TOKEN_REVOKED or SESSION_EPOCH_UNAVAILABLE), so it is never
       forwarded.
rollback (--yes), after a failed or interrupted phase B to E:
- restores the backup;
- recreates auth-service when it was moved, or when it does not run the previous key and kid (live state);
- after any auth-service change, force-recreates gateway-service as well, emptying its JWKS cache and its
  negative cache for unknown kids (review J1, J2).
The original bullets follow:
- restores the backup;
- recreates auth-service, then gateway-service if it was recreated.
redact (--yes), after a passing rotation, while the JWKS lists only the new kid:
- replaces the old key's env form with CHANGE_ME_redacted_after_rotation_20261013 (the preflight refuses that marker),
  in:
  - every /opt/parkio/docker/.env.azure-hosted-beta.bak-* file;
  - the pr68 env artifact;
  - the recovery checkpoint's env copy;
- then scans /opt, /home, /usr/local, /tmp and /var/tmp for the old key's base64 body in any form, printing paths only;
- only then redacts this rotation's own backup, last.
Records: ~/parkio-release-20261009/jwt-rotation/jr-<stamp>/ (mode 700, files 600). Exit 0 = PASS, 1 = FAIL, 2 = usage.
"""
import base64
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
import uuid
from datetime import datetime, timezone

TEST = os.environ.get("JRT_TEST") == "1"


def cfg(name, default):
    """Overrides are honoured only by the self-test."""
    return os.environ.get(name, default) if TEST else default


R2 = cfg("JRT_R2", "c30b9ddbc960cac7e45566c940403dfed1739c7c")
HOST = cfg("JRT_HOST", "parkio-civo-prod")
REL = cfg("JRT_RELEASE", "/opt/parkio-release")
ENV_FILE = cfg("JRT_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
OUT = cfg("JRT_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009", "jwt-rotation"))
JWKS_URL = cfg("JRT_JWKS_URL", "https://api.parkio.dev/api/v1/auth/.well-known/jwks.json")
PROBE_URL = cfg("JRT_PROBE_URL", "https://api.parkio.dev/api/v1/users/me")
NOW = cfg("JRT_NOW", "")
HEALTH_WAIT = int(cfg("JRT_HEALTH_WAIT", "300"))
POLL = float(cfg("JRT_POLL", "5"))
REDACT_GLOB = cfg("JRT_REDACT_GLOB", "/opt/parkio/docker/.env.azure-hosted-beta.bak-*")
REDACT_EXTRA = cfg("JRT_REDACT_EXTRA", ":".join((
    "/opt/parkio/backup-artifacts/pr68-auth-email-20260922T114212Z/env.azure-hosted-beta.pre",
    "/opt/parkio-recovery/checkpoint-550848-postrestore/.env.azure-hosted-beta"))).split(":")
SCAN_ROOTS = cfg("JRT_SCAN_ROOTS", "/opt:/home:/usr/local:/tmp:/var/tmp").split(":")
PROJECT = "parkio"
SIGNER, VERIFIER = "auth-service", "gateway-service"
PEM, KID, ADD, ISS, AUD = ("PARKIO_JWT_PRIVATE_KEY_PEM", "PARKIO_JWT_KEY_ID", "PARKIO_JWT_ADDITIONAL_PUBLIC_KEYS_JSON",
                           "PARKIO_JWT_ISSUER", "PARKIO_JWT_AUDIENCE")
MARKER = "CHANGE_ME_redacted_after_rotation_20261013"   # the preflight's is_jwt_pem_placeholder refuses CHANGE_ME
ACCEPTED_AFTER_SIGNATURE = ("TOKEN_REVOKED", "SESSION_EPOCH_UNAVAILABLE")
FAULT = cfg("JRT_FAULT", "")                   # self-test only: simulate an interruption at a named point
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
    if TEST and "JRT_W" in os.environ:
        env["JRT_W"] = os.environ["JRT_W"]
    env.update(extra or {})
    return env


def run(cmd, cwd=None, extra_env=None, timeout=300, stdin=None, pass_fds=()):
    """Every child gets the allowlisted environment; `stdin` (bytes) is its only input, empty when None."""
    try:
        p = subprocess.run(cmd, cwd=cwd, env=base_env(extra_env), capture_output=True, timeout=timeout,
                           input=stdin if stdin is not None else b"", pass_fds=pass_fds)
    except FileNotFoundError:
        return 127, b"", b"not found"
    except subprocess.TimeoutExpired:
        return 124, b"", b"timed out"
    return p.returncode, p.stdout, p.stderr


def git(*args):
    rc, out, _ = run(["git", "--no-optional-locks", "-c", f"safe.directory={REL}", "-C", REL] + list(args))
    return rc, out.decode("utf-8", "replace")


INBOX_OVERLAY = "docker/docker-compose.waitlist-ops-inbox.yml"
_OVERLAYS = None


def overlays():
    """`-f INBOX_OVERLAY` when the live gateway was created with that activation-only overlay, else nothing. Every
    Compose call then renders and recreates what runs today; without it a gateway recreate would drop the relay inbox
    (first live pre, 2026-10-13)."""
    global _OVERLAYS
    if _OVERLAYS is None:
        rc, out, _ = run(["docker", "ps", "-q", "--no-trunc", "--filter", f"label=com.docker.compose.project={PROJECT}",
                          "--filter", "label=com.docker.compose.service=gateway-service"])
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
    d = os.path.join(OUT, f"jr-{STAMP}")
    os.makedirs(d, mode=0o700, exist_ok=True)
    return d


def save_progress(rec):
    text = json.dumps({k: rec.get(k) for k in ("stamp", "phase", "backup", "moved")}, sort_keys=True)
    fd, tmp = tempfile.mkstemp(dir=record_dir())
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(text)
    os.chmod(tmp, 0o600)
    os.replace(tmp, os.path.join(record_dir(), "rotate-progress.json"))


def finish(name, rec):
    rec["checks"] = CHECKS
    rec["verdict"] = "PASS" if CHECKS and all(c["pass"] for c in CHECKS) else "FAIL"
    text = json.dumps(rec, indent=1, sort_keys=True) + "\n"
    if any(v and v in text for v in SECRETS):
        raise SystemExit("internal error: a record would contain a secret value; nothing written")
    path = os.path.join(record_dir(), f"{name}.json")
    private_write(path, text)
    say(f"jwt rotation {name}: {rec['verdict']} | record {path}")
    return 0 if rec["verdict"] == "PASS" else 1


def latest(name):
    best = None
    try:
        for d in sorted(os.listdir(OUT)):
            p = os.path.join(OUT, d, f"{name}.json")
            if d.startswith("jr-") and os.path.isfile(p):
                best = json.load(open(p, encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return best


def now():
    return datetime.fromtimestamp(float(NOW), timezone.utc) if NOW else datetime.now(timezone.utc)


# ------------------------------------------------------------------ env file and keys (values stay in memory)
def env_values(data):
    """{key: value} for the JWT lines; one layer of surrounding double or single quotes removed."""
    out = {}
    for line in data.splitlines():
        for key in (PEM, KID, ADD, ISS, AUD):
            if line.startswith(key.encode() + b"="):
                v = line.split(b"=", 1)[1].decode("utf-8", "replace").strip()
                if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
                    v = v[1:-1]
                out[key] = v
    return out


def with_values(data, values):
    """data with each key's line replaced by KEY="value" (the form the live file uses), keeping line endings."""
    out = []
    for line in data.splitlines(keepends=True):
        for key, value in values.items():
            if line.startswith(key.encode() + b"="):
                end = b"\r\n" if line.endswith(b"\r\n") else (b"\n" if line.endswith(b"\n") else b"")
                line = key.encode() + b'="' + value.encode("ascii") + b'"' + end
        out.append(line)
    return b"".join(out)


def only_keys_changed(before, after, keys):
    strip = lambda d: [l for l in d.splitlines() if not any(l.startswith(k.encode() + b"=") for k in keys)]
    return strip(before) == strip(after)


# The live env file stores the PEM inside double quotes with every newline written as two backslashes and an n.
# Compose turns a doubled backslash into one, so the container receives one backslash and an n, which auth-service
# turns back into a newline. file_form, container_form and pem_from convert between the three forms.
def file_form(pem_text):
    return pem_text.replace("\n", "\\\\n")


def container_form(pem_text):
    return pem_text.replace("\n", "\\n")


def pem_from(value):
    """The PEM text from the env file's value (quotes removed) or from the container's value."""
    return value.replace("\\\\n", "\n").replace("\\n", "\n")


def body_needle(pem_text):
    """48 characters from the middle of the key's base64 body, found in any form (file, container, JSON, real PEM).
    Not the first body line: it encodes the DER header that every RSA-2048 PKCS#8 key shares."""
    lines = pem_text.strip().splitlines()
    mid = lines[len(lines) // 2] if len(lines) > 4 else ""
    return mid[:48] if len(mid) >= 48 and not mid.startswith("-----") else ""


def modulus_b64url(pem_text):
    """The RSA modulus of a private key PEM, as JWKS `n` (base64url, no padding); the key goes on stdin."""
    rc, pub, _ = run(["openssl", "pkey", "-pubout"], stdin=pem_text.encode())
    if rc != 0:
        return None
    rc, out, _ = run(["openssl", "rsa", "-pubin", "-modulus", "-noout"], stdin=pub)
    m = re.match(rb"Modulus=([0-9A-Fa-f]+)", out.strip())
    if rc != 0 or not m:
        return None
    return base64.urlsafe_b64encode(bytes.fromhex(m.group(1).decode())).rstrip(b"=").decode()


def b64url(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def sign_token(pem_text, kid, iss, aud):
    """A 60-second RS256 token for a random, non-existent subject with session_epoch -1. The key reaches openssl
    through an inherited pipe; the token stays in memory."""
    t = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT", "kid": kid}, separators=(",", ":")).encode())
    payload = b64url(json.dumps({"iss": iss, "aud": aud, "sub": str(uuid.uuid4()), "iat": t, "exp": t + 60, "roles": [],
                                 "status": "ACTIVE", "session_epoch": -1}, separators=(",", ":")).encode())
    r, w = os.pipe()
    try:
        os.write(w, pem_text.encode())
        os.close(w)
        rc, sig, _ = run(["openssl", "dgst", "-sha256", "-sign", f"/dev/fd/{r}"], stdin=f"{header}.{payload}".encode(), pass_fds=(r,))
    finally:
        os.close(r)
    if rc != 0 or not sig:
        return None
    token = f"{header}.{payload}.{b64url(sig)}"
    SECRETS.append(token)
    return token


def http_get(url, token=None):
    """(status, parsed JSON or None). Never prints the token."""
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"} if token else {})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            body, status = resp.read(), resp.status
    except urllib.error.HTTPError as e:
        body, status = e.read(), e.code
    except (urllib.error.URLError, OSError):
        return None, None
    try:
        return status, json.loads(body or b"null")
    except ValueError:
        return status, None


def jwks_keys():
    status, doc = http_get(JWKS_URL)
    keys = (doc or {}).get("keys") if isinstance(doc, dict) else None
    if status != 200 or not isinstance(keys, list):
        return None
    return [(k.get("kid"), k.get("n")) for k in keys if isinstance(k, dict)]


def probe(token):
    status, doc = http_get(PROBE_URL, token)
    return status, (doc or {}).get("code") if isinstance(doc, dict) else None


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


def release_ok():
    rc, head = git("rev-parse", "HEAD")
    rc2, status = git("status", "--porcelain")
    head = head.strip()
    return rc == 0 and rc2 == 0 and head == R2 and not status.strip(), head[:12] or "?"


def write_atomically(path, data, st):
    fd, tmp = tempfile.mkstemp(prefix=".jrt-", dir=os.path.dirname(path))
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


# ------------------------------------------------------------------ commands
def basics():
    check(f"host {HOST}", socket.gethostname() == HOST, socket.gethostname())
    rc, _, _ = run(["docker", "info", "--format", "{{.ServerVersion}}"])
    check("docker usable without sudo", rc == 0)
    ok, head = release_ok()
    check("the release checkout is clean at R2", ok, head)


def cmd_pre():
    say(f"jwt rotation pre | {STAMP} | read-only")
    rec = {"tool": "jwt-rotate", "command": "pre", "stamp": STAMP}
    basics()
    t = now()
    check("outside 03:00-04:30 UTC (the backup)", not (t.hour == 3 or (t.hour == 4 and t.minute < 30)), t.strftime("%H:%M UTC"))
    data = open(ENV_FILE, "rb").read()
    ev = env_values(data)
    pem, kid = ev.get(PEM, ""), ev.get(KID, "")
    key = pem_from(pem)
    needle = body_needle(key)
    SECRETS.extend([pem, key, container_form(key)])
    check("the env file holds a PKCS#8 PEM and a kid (values not shown)", key.startswith("-----BEGIN PRIVATE KEY-----") and bool(needle) and bool(kid))
    check("no additional (previous) public keys in the env file", not ev.get(ADD))
    ds = inspect_all()
    auth, gw = parkio(SIGNER, ds), parkio(VERIFIER, ds)
    aenv = env_of(auth) if auth else {}
    check("auth-service runs with the env file's PEM and kid, and no additional public keys",
          bool(auth) and aenv.get(PEM) == container_form(key) and aenv.get(KID) == kid and not aenv.get(ADD))
    carriers = sorted(name_of(d) for d in ds if needle and needle in json.dumps(d.get("Config") or {}))
    check("only auth-service carries the PEM, in any project's containers", carriers == [name_of(auth)] if auth else False, ", ".join(carriers))
    say(f"  NOTE Compose calls {'include ' + INBOX_OVERLAY + ' (the live gateway was created with it)' if overlays() else 'use the production file list only'}")
    m = model()
    if check("the release model renders", m is not None):
        holders = sorted(s for s in (m.get("services") or {}) if needle and needle in json.dumps(m["services"][s]))
        check("in the model, only auth-service's environment carries the PEM", holders == [SIGNER] and model_env(m, SIGNER).get(PEM) == container_form(key),
              ", ".join(holders))
        hashes = model_hashes() or {}
        for d, svc in ((auth, SIGNER), (gw, VERIFIER)):
            lab = ((d or {}).get("Config") or {}).get("Labels") or {}
            # a build-only service has no `image` in the model: Compose names it <project>-<service> (Redis review T1)
            ref = (((m.get("services") or {}).get(svc) or {}).get("image")) or f"{PROJECT}-{svc}"
            check(f"{svc} running, healthy, at the model's hash, its image resolving to the running one",
                  bool(d) and health(d) == "healthy" and lab.get("com.docker.compose.config-hash") == hashes.get(svc)
                  and (d.get("Config") or {}).get("Image") == ref and image_id(ref) == d.get("Image"), health(d) or "?")
    keys = jwks_keys()
    n = modulus_b64url(key) if needle else None
    check("the public JWKS lists exactly one key: the current kid with the current key's modulus",
          keys is not None and n is not None and keys == [(kid, n)], f"{len(keys) if keys is not None else '?'} key(s)")
    status, code = probe(sign_token(key, kid, ev.get(ISS, ""), ev.get(AUD, ""))) if needle else (None, None)
    check("the probe works: a token signed with the current key stops at the session-epoch check (baseline for phase E)",
          code in ACCEPTED_AFTER_SIGNATURE and status in (401, 503), f"{status} {code}")
    rec["baseline"] = baseline(ds)
    rec["envSha256"] = hashlib.sha256(data).hexdigest()
    return finish("pre", rec)


def cmd_rotate(yes):
    if not yes:
        print("usage: rotate needs --yes (after the procedure's review and gates)", file=sys.stderr)
        return 2
    say(f"jwt rotation rotate | {STAMP}")
    rec = {"tool": "jwt-rotate", "command": "rotate", "stamp": STAMP, "phase": "checks"}
    basics()
    pre = latest("pre")
    age = (time.time() - calendar.timegm(time.strptime(pre["stamp"][:15], "%Y%m%dT%H%M%S"))) if pre else None
    check("the latest pre passed within an hour", bool(pre) and pre.get("verdict") == "PASS" and age is not None and 0 <= age <= PRE_MAX_AGE,
          f"{pre['stamp']} {pre.get('verdict')}" if pre else "none")
    data = open(ENV_FILE, "rb").read()
    check("the env file is unchanged since that pre", bool(pre) and hashlib.sha256(data).hexdigest() == pre.get("envSha256"))
    check("the containers are the ones pre recorded", bool(pre) and baseline(inspect_all()) == pre.get("baseline"))
    if not all(c["pass"] for c in CHECKS):
        return finish("rotate", rec)
    ev = env_values(data)
    old_file, old_kid, iss, aud = ev.get(PEM, ""), ev.get(KID, ""), ev.get(ISS, ""), ev.get(AUD, "")
    old_key = pem_from(old_file)
    SECRETS.extend([old_file, old_key, container_form(old_key)])
    st = os.stat(ENV_FILE)

    say("A. generate the new key (openssl genpkey, stdout only)")
    rc, key, _ = run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"])
    new_pem = key.decode("ascii", "replace")
    SECRETS.extend([new_pem, file_form(new_pem), container_form(new_pem)])
    new_kid = "parkio-auth-rs256-" + now().strftime("%Y%m%d%H%M")
    new_n = modulus_b64url(new_pem) if rc == 0 else None
    if not check("a new RSA-2048 PKCS#8 key and a new kid", rc == 0 and new_pem.startswith("-----BEGIN PRIVATE KEY-----") and new_n is not None
                 and len(base64.urlsafe_b64decode(new_n + "==")) == 256 and new_kid != old_kid, new_kid):
        return finish("rotate", rec)
    rec["newKid"] = new_kid

    say("B. write the new PEM and kid into the env file")
    backup = f"{ENV_FILE}.bak-jrt-{STAMP}"
    fd = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, st.st_mode & 0o7777)
    with os.fdopen(fd, "wb") as fh:
        fh.write(data)
        fh.flush()
        os.fsync(fh.fileno())
        os.fchown(fh.fileno(), st.st_uid, st.st_gid)
    rec["backup"] = os.path.basename(backup)
    rec["phase"] = "B"
    save_progress(rec)
    if not check("backup identical to the original", open(backup, "rb").read() == data):
        return finish("rotate", rec)
    hashes_before = model_hashes() or {}
    newdata = with_values(data, {PEM: file_form(new_pem), KID: new_kid})
    write_atomically(ENV_FILE, newdata, st)
    if FAULT == "after-env-write":
        raise SystemExit(99)
    after = open(ENV_FILE, "rb").read()
    ok = check("only the PEM and kid lines changed, mode and owner kept", after == newdata and only_keys_changed(data, after, (PEM, KID))
               and env_values(after).get(PEM) == file_form(new_pem) and (os.stat(ENV_FILE).st_mode, os.stat(ENV_FILE).st_uid) == (st.st_mode, st.st_uid))
    ok &= check("the release preflight still passes", preflight_ok())
    hashes_after = model_hashes() or {}
    changed = sorted(s for s in set(hashes_before) | set(hashes_after) if hashes_before.get(s) != hashes_after.get(s))
    ok &= check("only auth-service's model hash changed", changed == [SIGNER], ", ".join(changed))
    if not ok:
        write_atomically(ENV_FILE, data, st)
        rec["phase"] = "restored"
        save_progress(rec)
        say("  the env file was restored; nothing else changed")
        return finish("rotate", rec)

    say("C. recreate auth-service with the new key")
    base = baseline(inspect_all())
    before = parkio(SIGNER)
    rec["phase"] = "C"
    rec["moved"] = [SIGNER]
    save_progress(rec)
    rc, _, _ = wrapper("up", "-d", "--no-build", "--no-deps", SIGNER)
    d = wait_container(SIGNER, (before or {}).get("Id"))
    lab = ((d or {}).get("Config") or {}).get("Labels") or {}
    good = check("auth-service: wrapper exit 0, a new healthy container", rc == 0 and bool(d) and d.get("Id") != (before or {}).get("Id") and health(d) == "healthy",
                 f"exit {rc}, {health(d) or '?'}")
    good &= check("auth-service: the new PEM and kid in its environment, at the model's hash",
                  bool(d) and env_of(d).get(PEM) == container_form(new_pem) and env_of(d).get(KID) == new_kid and
                  lab.get("com.docker.compose.config-hash") == (model_hashes() or {}).get(SIGNER))
    others = changed_since(base, inspect_all(), {f"{PROJECT}/{SIGNER}"})
    good &= check("auth-service: every other container unchanged", not others, ", ".join(others))
    keys = jwks_keys()
    good &= check("the public JWKS lists exactly the new kid with the new modulus", keys == [(new_kid, new_n)],
                  f"{len(keys) if keys is not None else '?'} key(s)")
    if not good:
        say("  STOP: run `rollback --yes` if auth-service cannot serve the new key")
        return finish("rotate", rec)

    say("D. recreate gateway-service (an empty JWKS cache)")
    before = parkio(VERIFIER)
    rec["phase"] = "D"
    rec["moved"] = [SIGNER, VERIFIER]
    save_progress(rec)
    rc, _, _ = wrapper("up", "-d", "--no-build", "--no-deps", "--force-recreate", VERIFIER)
    d = wait_container(VERIFIER, (before or {}).get("Id"))
    lab = ((d or {}).get("Config") or {}).get("Labels") or {}
    good = check("gateway-service: wrapper exit 0, a new healthy container, at the model's hash",
                 rc == 0 and bool(d) and d.get("Id") != (before or {}).get("Id") and health(d) == "healthy" and
                 lab.get("com.docker.compose.config-hash") == (model_hashes() or {}).get(VERIFIER), f"exit {rc}, {health(d) or '?'}")
    others = changed_since(base, inspect_all(), {f"{PROJECT}/{SIGNER}", f"{PROJECT}/{VERIFIER}"})
    good &= check("gateway-service: every other container unchanged", not others, ", ".join(others))
    if not good:
        say("  STOP: see the procedure's recovery section")
        return finish("rotate", rec)

    say("E. probe the public API with 60-second tokens (never printed)")
    rec["phase"] = "E"
    save_progress(rec)
    for label, pem_text, kid in (("the old key under the old kid", old_key, old_kid),
                                 ("the old key under the new kid", old_key, new_kid)):
        status, code = probe(sign_token(pem_text, kid, iss, aud))
        check(f"a token signed with {label} is refused INVALID_TOKEN", status == 401 and code == "INVALID_TOKEN", f"{status} {code}")
    status, code = probe(sign_token(new_pem, new_kid, iss, aud))
    check("a token signed with the new key passes the signature and stops at the session-epoch check (never forwarded)",
          code in ACCEPTED_AFTER_SIGNATURE and status in (401, 503), f"{status} {code}")
    rec["phase"] = "done" if all(c["pass"] for c in CHECKS) else "E"
    save_progress(rec)
    say(f"  the env backup {rec['backup']} holds the old, now revoked key; `redact --yes` replaces it after acceptance")
    return finish("rotate", rec)


def cmd_rollback(yes):
    if not yes:
        print("usage: rollback needs --yes", file=sys.stderr)
        return 2
    say(f"jwt rotation rollback | {STAMP} | brings the EXPOSED key back")
    rec = {"tool": "jwt-rotate", "command": "rollback", "stamp": STAMP}
    prog = latest("rotate-progress")
    phase = (prog or {}).get("phase")
    if not check("a rotation stopped in phase B, C, D or E (after the backup)", phase in ("B", "C", "D", "E"), phase or "none"):
        return finish("rollback", rec)
    backup = os.path.join(os.path.dirname(ENV_FILE), prog.get("backup") or "")
    old_data = open(backup, "rb").read() if os.path.isfile(backup) else None
    data = open(ENV_FILE, "rb").read()
    if not check("the backup exists and differs from the env file only in the PEM and kid lines",
                 old_data is not None and only_keys_changed(old_data, data, (PEM, KID))):
        return finish("rollback", rec)
    for v in (env_values(old_data).get(PEM, ""), env_values(data).get(PEM, "")):
        SECRETS.extend([v, pem_from(v), container_form(pem_from(v))])
    if data != old_data:
        write_atomically(ENV_FILE, old_data, os.stat(ENV_FILE))
    check("the env file holds the previous PEM and kid again", open(ENV_FILE, "rb").read() == old_data)
    old = env_values(old_data)
    a = env_of(parkio(SIGNER) or {})
    auth_changed = SIGNER in (prog.get("moved") or []) or a.get(PEM) != container_form(pem_from(old.get(PEM, ""))) or a.get(KID) != old.get(KID)
    todo = ([SIGNER] if auth_changed else []) + ([VERIFIER] if auth_changed or VERIFIER in (prog.get("moved") or []) else [])
    rec["recreated"] = todo
    for svc in todo:
        before = parkio(svc)
        rc, _, _ = wrapper("up", "-d", "--no-build", "--no-deps", "--force-recreate", svc)
        d = wait_container(svc, (before or {}).get("Id"))
        check(f"{svc}: recreated, healthy", rc == 0 and bool(d) and d.get("Id") != (before or {}).get("Id") and health(d) == "healthy")
    keys = jwks_keys()
    check("the public JWKS lists the previous kid again", bool(keys) and [k for k, _ in keys] == [old.get(KID)])
    a = env_of(parkio(SIGNER) or {})
    check("auth-service runs with the previous PEM and kid", a.get(PEM) == container_form(pem_from(old.get(PEM, ""))) and a.get(KID) == old.get(KID))
    if all(c["pass"] for c in CHECKS):
        path = os.path.join(OUT, f"jr-{prog['stamp']}", "rotate-progress.json")
        prog["phase"] = "rolled-back"
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path))
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(json.dumps(prog, sort_keys=True))
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
        say("  the rotation is marked rolled-back; run pre before another attempt")
    return finish("rollback", rec)


def redact_file(path, needle, current):
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
    if current.encode() in data:
        return False, "holds the current key; left alone"
    n = data.count(needle.encode())
    if n == 0:
        return True, "no occurrence"
    out = data.replace(needle.encode(), MARKER.encode())
    try:
        fd, tmp = tempfile.mkstemp(prefix=".jrt-redact-", dir=os.path.dirname(path))
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


def scan_for(needle):
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
                        if needle.encode() in fh.read():
                            found.append(p)
                except OSError:
                    continue
    return found


def cmd_redact(yes):
    if not yes:
        print("usage: redact needs --yes (after a passing rotation)", file=sys.stderr)
        return 2
    say(f"jwt rotation redact | {STAMP}")
    rec = {"tool": "jwt-rotate", "command": "redact", "stamp": STAMP}
    rot, prog = latest("rotate"), latest("rotate-progress")
    if not check("the latest rotation passed (phase done)", bool(rot) and rot.get("verdict") == "PASS" and (prog or {}).get("phase") == "done"
                 and rot.get("stamp") == (prog or {}).get("stamp")):
        return finish("redact", rec)
    backup = os.path.join(os.path.dirname(ENV_FILE), rot.get("backup") or "")
    old = env_values(open(backup, "rb").read()).get(PEM, "") if os.path.isfile(backup) else ""
    if old == MARKER:
        check("redaction already complete: the rotation's backup no longer holds the old key", True)
        return finish("redact", rec)
    cur = env_values(open(ENV_FILE, "rb").read())
    for v in (old, cur.get(PEM, "")):
        SECRETS.extend([v, pem_from(v), container_form(pem_from(v))])
    keys = jwks_keys()
    if not check("the old key is known (the rotation's backup), and the public JWKS lists only the env file's kid",
                 old.startswith("-----BEGIN PRIVATE KEY-----") and old != cur.get(PEM) and keys is not None and [k for k, _ in keys] == [cur.get(KID)]):
        return finish("redact", rec)
    needle = body_needle(pem_from(old))
    rec["files"] = {}
    paths = sorted(p for p in glob.glob(REDACT_GLOB) if os.path.realpath(p) != os.path.realpath(backup)) + REDACT_EXTRA
    for path in paths:
        ok, detail = redact_file(path, old, cur.get(PEM, ""))
        rec["files"][path] = detail
        check(f"{path}: redacted", ok, detail)
    left = [p for p in scan_for(needle) if os.path.realpath(p) != os.path.realpath(backup)] if needle else ["<no key body>"]
    check(f"apart from the rotation's backup, no readable file under {', '.join(SCAN_ROOTS)} holds the old key in any form", not left, ", ".join(left))
    if not all(c["pass"] for c in CHECKS):
        say("  the rotation's backup keeps the old key, so this can run again once the failures above are dealt with")
        return finish("redact", rec)
    ok, detail = redact_file(backup, old, cur.get(PEM, ""))
    rec["files"][backup] = detail
    check(f"{backup}: redacted last", ok, detail)
    return finish("redact", rec)


def cmd_status():
    try:
        for d in sorted(os.listdir(OUT))[-8:]:
            for f in sorted(os.listdir(os.path.join(OUT, d))):
                if f.endswith(".json") and f != "rotate-progress.json":
                    r = json.load(open(os.path.join(OUT, d, f), encoding="utf-8"))
                    say(f"{d} {r.get('command')}: {r.get('verdict')}")
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
