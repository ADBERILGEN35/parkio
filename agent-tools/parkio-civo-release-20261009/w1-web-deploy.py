#!/usr/bin/env python3
"""W1 - the MapTiler web fix on parkio-civo-prod (MAPTILER-FIX-PLAN v2, step 8). Run as civo on the host (Python 3.8+):

  python3 -I w1-web-deploy.py pre      --r1 SHA40 --digest sha256:HEX64 --image-id sha256:HEX64
  python3 -I w1-web-deploy.py recreate --r1 SHA40 --digest sha256:HEX64 --image-id sha256:HEX64 --yes
  python3 -I w1-web-deploy.py rollback --yes
  python3 -I w1-web-deploy.py status

pre: nothing is started, stopped or recreated. It pulls one image and writes one local tag.
  - The host, `docker` and `sudo -n` are usable. The release checkout /opt/parkio-release is clean at the PIN f888a81b.
    No u05-priv001a-* unit exists. The web container runs the current pin (4625a72e) and is healthy.
  - It records a baseline of every parkio container: id, image, config hash and health.
  - It checks R1, the tip of origin/release/civo-web-maptiler-20261013 (equal to --r1):
    - its parent is the PIN, and its only change is docker/docker-compose.web-release-pin.yml;
    - that file's non-comment lines are exactly name/services/web/image = ghcr.io/adberilgen35/parkio/web@<digest>;
    - the file's blob equals origin/api's (the merged pin PR).
  - It writes the rollback tag <repo>:rollback-pre-maptiler-20261013 on the running web image. An existing tag must
    already point there.
  - It pulls <repo>@<digest> and checks: the RepoDigests hold the ref, linux/amd64, and the OCI revision is the PIN.
    The F-05 guard of the release checkout then runs offline (--expected-platform linux/amd64,
    --expected-map-key-fingerprint b9cf3e673752, --evidence-out). It must PASS, establish configDigest = --image-id
    (the accepted image id; on the host's containerd store `.Id` is the manifest digest, not the config digest), and
    match the fingerprint.
  - It compares the bundles of the old and the new image. Both images are created and copied, never started; the
    containers are removed. It requires:
    - /usr/share/nginx/html IDENTICAL after normalization (web-bundle-compare.py, SHA-256 pinned);
    - the nginx template, the CSP entrypoint and nginx.conf byte-identical, with equal modes;
    - apk packages equal, except libexpat and pcre2 (recorded).
recreate: the only live change.
  - It requires a `pre` that passed for the same arguments within the last 6 hours, and the web container must still be
    the baseline's.
  - It checks out R1 (detached, clean, verified), then runs exactly:
      cd /opt/parkio-release && PARKIO_ENV_FILE=/opt/parkio/docker/.env.azure-hosted-beta \
        PARKIO_WEB_EXPECTED_MAP_KEY_FINGERPRINT=b9cf3e673752 \
        ./scripts/parkio-prod-compose.sh up -d --no-build --no-deps --force-recreate web
  - Post-checks:
    - web is healthy within 180 s, on --image-id, as a new container;
    - its config hash equals the wrapper's `config --hash web` from the active checkout (T1's D4 method; the guard's
      binding pins the same image string, and pull_policy is not hashed);
    - every other container is unchanged against the baseline;
    - https://app.parkio.dev/, /login and /explore answer 200 with the app root.
rollback: checks out the PIN, runs the same wrapper command without the fingerprint variable, then runs the same
  post-checks against the baseline web image.
The wrapper and the guard get an allowlisted environment: PATH, HOME, LANG, USER, LOGNAME, PARKIO_ENV_FILE, plus the
fingerprint for the recreate. COMPOSE_*, DOCKER_*, PARKIO_SKIP_*, PARKIO_COMPOSE_FILES_LIST, PARKIO_GMP_RECOVERY and
everything else from the operator's shell are dropped.
If the wrapper fails and web is still the baseline container, the checkout goes back to the PIN.
Not covered by W1, and still required before step 8 is accepted, from the workstation:
  - headless Chromium render of /, /login and /explore (#root, no page error);
  - `u04-live-ownership.mjs --map-only … --map-fingerprint b9cf3e673752`;
  - the account-free CSRF/401 sign-in check.
Records go to ~/parkio-release-20261009/w1-maptiler/<stamp>/ (mode 700): pre.json, recreate.json, rollback.json and logs.
A failed check stops with exit 1 and changes nothing more. Key values are never printed.
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
import tempfile
import time
import urllib.error
import urllib.request

TEST = os.environ.get("W1_TEST") == "1"


def cfg(name, default):
    """Overrides are honoured only by the self-test."""
    return os.environ.get(name, default) if TEST else default


PIN = cfg("W1_PIN", "f888a81b113c0c486ea0949067779443857e644d")
OLD_IMAGE = cfg("W1_OLD_IMAGE", "sha256:4625a72e5dd3afd4cc6b2d2a62d8cdb999725942ed6471591d33b96cbd42b6a6")
REPO = "ghcr.io/adberilgen35/parkio/web"
BRANCH = "release/civo-web-maptiler-20261013"
PIN_FILE = "docker/docker-compose.web-release-pin.yml"
ROLLBACK_TAG = REPO + ":rollback-pre-maptiler-20261013"
KEY_FP = "b9cf3e673752"
HOST = cfg("W1_HOST", "parkio-civo-prod")
REL = cfg("W1_RELEASE", "/opt/parkio-release")
ENV_FILE = cfg("W1_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
PROJECT = "parkio"
OUT = cfg("W1_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009", "w1-maptiler"))
APP = cfg("W1_APP", "https://app.parkio.dev")
COMPARE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web-bundle-compare.py")
COMPARE_SHA256 = cfg("W1_COMPARE_SHA256", "ac742e62b4980e2372f21f1db7a924cc9f65a6f25fc334c08a505e723b6278e4")
HEALTH_WAIT = int(cfg("W1_HEALTH_WAIT", "180"))
PRE_MAX_AGE = 6 * 3600
NGINX_FILES = ("/etc/nginx/templates/default.conf.template", "/docker-entrypoint.d/15-parkio-web-csp.envsh",
               "/etc/nginx/nginx.conf")
APK_ALLOWED = {"libexpat", "pcre2"}
PIN_LINES = (r"name: parkio", r"services:", r"  web:", r"    image: ghcr\.io/adberilgen35/parkio/web@(sha256:[0-9a-f]{64})")
STAMP = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
CHECKS = []
DOCKER = ["docker"]


def say(line=""):
    print(line, flush=True)


def check(what, ok, detail=""):
    CHECKS.append({"check": what, "pass": bool(ok), "detail": detail})
    say(("  PASS " if ok else "  FAIL ") + what + (f" ({detail})" if detail else ""))
    return bool(ok)


def run(cmd, cwd=None, env=None, timeout=120, stdin=None):
    try:
        p = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True, timeout=timeout, input=stdin)
    except FileNotFoundError:
        return 127, "", "not found"
    except subprocess.TimeoutExpired:
        return 124, "", "timed out"
    return p.returncode, p.stdout, p.stderr


def docker(*args, timeout=300):
    return run(DOCKER + list(args), timeout=timeout)


def git(*args, timeout=120):
    return run(["git", "--no-optional-locks", "-c", f"safe.directory={REL}", "-C", REL] + list(args), timeout=timeout)


def record_dir():
    d = os.path.join(OUT, STAMP)
    os.makedirs(d, mode=0o700, exist_ok=True)
    return d


def write_json(name, data):
    path = os.path.join(record_dir(), name)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=1, sort_keys=True)
    return path


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def containers():
    rc, out, _ = docker("ps", "-a", "--no-trunc", "--filter", f"label=com.docker.compose.project={PROJECT}",
                        "--format", "{{.ID}}")
    if rc != 0:
        return None
    ids = out.split()
    if not ids:
        return {}
    rc, info, _ = docker("inspect", *ids)
    if rc != 0:
        return None
    result = {}
    for d in json.loads(info):
        labels = (d.get("Config") or {}).get("Labels") or {}
        svc = labels.get("com.docker.compose.service") or d.get("Name", "").lstrip("/")
        state = d.get("State") or {}
        entry = {"id": d.get("Id"), "image": d.get("Image"), "status": state.get("Status"),
                 "health": (state.get("Health") or {}).get("Status"), "hash": labels.get("com.docker.compose.config-hash"),
                 "labels": labels}
        result.setdefault(svc, []).append(entry)
    return result


def one(cs, svc):
    entries = (cs or {}).get(svc) or []
    return entries[0] if len(entries) == 1 else None


def pin_file_image(text):
    lines = [l for l in text.splitlines() if l.strip() and not l.lstrip().startswith("#")]
    if len(lines) != len(PIN_LINES):
        return None
    m = None
    for pattern, line in zip(PIN_LINES, lines):
        m = re.fullmatch(pattern, line)
        if not m:
            return None
    return m.group(1)


def args_of(argv):
    a, i = {}, 0
    while i < len(argv):
        if argv[i] in ("--r1", "--digest", "--image-id") and i + 1 < len(argv):
            a[argv[i]] = argv[i + 1]
            i += 2
        elif argv[i] == "--yes":
            a["--yes"] = True
            i += 1
        else:
            raise SystemExit(f"usage: unknown or incomplete argument {argv[i]!r}")
    return a


def want_target(a):
    r1, digest, iid = a.get("--r1", ""), a.get("--digest", ""), a.get("--image-id", "")
    if not (re.fullmatch(r"[0-9a-f]{40}", r1) and re.fullmatch(r"sha256:[0-9a-f]{64}", digest)
            and re.fullmatch(r"sha256:[0-9a-f]{64}", iid)):
        raise SystemExit("usage: --r1 takes 40 hex, --digest and --image-id take sha256:<64 hex>")
    return r1, digest, iid


def basics():
    check(f"host {HOST}", socket.gethostname() == HOST, socket.gethostname())
    rc, _, _ = run(["sudo", "-n", "true"])
    check("sudo -n works", rc == 0)
    rc, out, _ = docker("info", "--format", "{{.ServerVersion}}")
    check("docker usable without sudo (civo is in the docker group)", rc == 0, out.strip()[:20])
    rc, units, _ = run(["systemctl", "list-units", "--all", "--plain", "--no-legend", "u05-priv001a-*"])
    check("no u05-priv001a-* unit", rc == 0 and not units.strip())


def checkout_state():
    rc, head, _ = git("rev-parse", "HEAD")
    rc2, dirty, _ = git("status", "--porcelain")
    return (head.strip() if rc == 0 else None), (rc2 == 0 and not dirty.strip())


def verify_r1(r1, digest):
    rc, _, err = git("fetch", "--quiet", "origin", f"+refs/heads/{BRANCH}:refs/remotes/origin/{BRANCH}",
                     "+refs/heads/api:refs/remotes/origin/api", timeout=300)
    if not check("fetched the release branch and api", rc == 0, err.strip()[:120]):
        return False
    rc, tip, _ = git("rev-parse", f"refs/remotes/origin/{BRANCH}")
    ok = check("R1 is the release branch tip", rc == 0 and tip.strip() == r1, tip.strip()[:12])
    rc, parents, _ = git("rev-list", "--parents", "-n", "1", r1)
    ok &= check("R1's only parent is the PIN", rc == 0 and parents.split()[1:] == [PIN], " ".join(p[:12] for p in parents.split()[1:]))
    rc, names, _ = git("diff", "--no-renames", "-z", "--name-only", PIN, r1)
    ok &= check(f"R1 changes only {PIN_FILE}", rc == 0 and [n for n in names.split("\0") if n] == [PIN_FILE])
    rc, text, _ = git("show", f"{r1}:{PIN_FILE}")
    ok &= check("the R1 pin file pins only the web image, at --digest", rc == 0 and pin_file_image(text) == digest)
    rc, b1, _ = git("rev-parse", f"{r1}:{PIN_FILE}")
    rc2, b2, _ = git("rev-parse", f"refs/remotes/origin/api:{PIN_FILE}")
    ok &= check("the pin file blob equals origin/api's (the merged pin PR)", rc == 0 and rc2 == 0 and b1.strip() == b2.strip(),
                b1.strip()[:12])
    return ok


def extract(image, dest, paths):
    """docker create (never started) + docker cp of each path into dest; the container is removed."""
    name = f"w1-cmp-{os.getpid()}-{hashlib.sha256(dest.encode()).hexdigest()[:8]}"
    rc, cid, err = docker("create", "--name", name, image, timeout=120)
    if rc != 0:
        return False
    ok = True
    try:
        for p in paths:
            target = os.path.join(dest, p.lstrip("/"))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            rc, _, _ = docker("cp", f"{name}:{p}", target, timeout=300)
            ok &= rc == 0
    finally:
        docker("rm", "-v", name, timeout=120)
    return ok


def apk_packages(path):
    try:
        text = open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return None
    return dict(re.findall(r"(?m)^P:(\S+)\nV:(\S+)", text))


def compare_bundles(old, new, rec):
    tmp = tempfile.mkdtemp(prefix="w1-cmp-")
    try:
        paths = ["/usr/share/nginx/html", "/lib/apk/db/installed"] + list(NGINX_FILES)
        a, b = os.path.join(tmp, "old"), os.path.join(tmp, "new")
        os.makedirs(a)
        os.makedirs(b)
        if not check("both images copied (created, never started, removed)", extract(old, a, paths) and extract(new, b, paths)):
            return False
        ok = check("web-bundle-compare.py matches its pinned SHA-256", sha256_file(COMPARE) == COMPARE_SHA256)
        if ok:
            rc, out, err = run([sys.executable, "-I", COMPARE, "--a-dir", os.path.join(a, "usr/share/nginx/html"),
                                "--b-dir", os.path.join(b, "usr/share/nginx/html")], timeout=300)
            rec["bundleCompare"] = out.strip().splitlines()[-1:] if out.strip() else [err.strip()[:200]]
            ok &= check("bundle IDENTICAL after normalizing the key, hashed names and CACHE_VERSION", rc == 0 and "IDENTICAL" in out,
                        (out.strip().splitlines() or ["?"])[-1][:120])
        for f in NGINX_FILES:
            fa, fb = os.path.join(a, f.lstrip("/")), os.path.join(b, f.lstrip("/"))
            same = os.path.isfile(fa) and os.path.isfile(fb) and open(fa, "rb").read() == open(fb, "rb").read()
            modes = (oct(os.stat(fa).st_mode & 0o7777), oct(os.stat(fb).st_mode & 0o7777)) if same else ("?", "?")
            ok &= check(f"{f} byte-identical with equal modes", same and modes[0] == modes[1], modes[0])
        pa = apk_packages(os.path.join(a, "lib/apk/db/installed"))
        pb = apk_packages(os.path.join(b, "lib/apk/db/installed"))
        changed = sorted(k for k in set(pa or {}) | set(pb or {}) if (pa or {}).get(k) != (pb or {}).get(k))
        rec["apkChanged"] = {k: [(pa or {}).get(k), (pb or {}).get(k)] for k in changed}
        ok &= check("apk packages equal except libexpat/pcre2", pa is not None and pb is not None and set(changed) <= APK_ALLOWED,
                    json.dumps(rec["apkChanged"]))
        return ok
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def cmd_pre(a):
    r1, digest, iid = want_target(a)
    say(f"W1 pre | {STAMP} | read-only apart from one pull and one local tag")
    rec = {"tool": "w1-web-deploy", "command": "pre", "stamp": STAMP, "r1": r1, "digest": digest, "imageId": iid, "pin": PIN}
    basics()
    head, clean = checkout_state()
    check("the release checkout is clean at the PIN", head == PIN and clean, (head or "?")[:12])
    cs = containers()
    web = one(cs, "web")
    check("one web container, on the current pin, healthy", bool(web) and web["image"] == OLD_IMAGE and web["health"] == "healthy",
          f"{(web or {}).get('image', '?')[:19]} {(web or {}).get('health')}")
    rec["baseline"] = {svc: [{k: e[k] for k in ("id", "image", "hash", "status", "health")} for e in entries]
                       for svc, entries in (cs or {}).items()}
    if not all(c["pass"] for c in CHECKS):
        return finish("pre", rec)
    if not verify_r1(r1, digest):
        return finish("pre", rec)
    rc, existing, _ = docker("image", "inspect", "--format", "{{.Id}}", ROLLBACK_TAG)
    if rc == 0:
        check(f"rollback tag {ROLLBACK_TAG} already on the running image", existing.strip() == web["image"], existing.strip()[:19])
    else:
        rc, _, err = docker("tag", web["image"], ROLLBACK_TAG)
        check(f"rollback tag {ROLLBACK_TAG} written on the running image", rc == 0, err.strip()[:120])
    ref = f"{REPO}@{digest}"
    rc, _, err = docker("pull", "--quiet", ref, timeout=900)
    check("pulled the new pin by digest", rc == 0, err.strip()[:120])
    rc, info, _ = docker("image", "inspect", ref)
    d = (json.loads(info) or [{}])[0] if rc == 0 else {}
    check("RepoDigests hold the ref", ref in (d.get("RepoDigests") or []))
    check("linux/amd64", d.get("Os") == "linux" and d.get("Architecture") == "amd64", f"{d.get('Os')}/{d.get('Architecture')}")
    rev = ((d.get("Config") or {}).get("Labels") or {}).get("org.opencontainers.image.revision")
    check("OCI revision is the PIN", rev == PIN, (rev or "?")[:12])
    grc, gdata, gline = guard(ref, rec)
    check("F-05 guard PASS offline (platform, key fingerprint b9cf3e673752)", grc == 0 and gdata.get("fingerprintCheck") == "matched",
          gline[0][:160])
    check("guard-established config digest = --image-id (the accepted image id)", gdata.get("configDigest") == iid,
          (gdata.get("configDigest") or "?")[:19])
    if all(c["pass"] for c in CHECKS):
        compare_bundles(web["image"], ref, rec)
    return finish("pre", rec)


def latest_pre(r1, digest, iid):
    """The most recent pre for exactly these arguments, passed or not (a later failure overrides an earlier PASS)."""
    best = None
    try:
        for stamp in sorted(os.listdir(OUT)):
            p = os.path.join(OUT, stamp, "pre.json")
            if os.path.isfile(p):
                r = json.load(open(p, encoding="utf-8"))
                if (r.get("r1"), r.get("digest"), r.get("imageId")) == (r1, digest, iid):
                    best = r
    except OSError:
        return None
    return best


ENV_ALLOW = ("PATH", "HOME", "LANG", "USER", "LOGNAME")


def clean_env(fingerprint=None):
    """The wrapper's and the guard's environment: an allowlist, never the operator's shell as a whole."""
    env = {k: os.environ[k] for k in ENV_ALLOW if k in os.environ}
    env["PARKIO_ENV_FILE"] = ENV_FILE
    if TEST and "W1ST_W" in os.environ:
        env["W1ST_W"] = os.environ["W1ST_W"]     # the self-test's fakes find their state here
    if fingerprint:
        env["PARKIO_WEB_EXPECTED_MAP_KEY_FINGERPRINT"] = fingerprint
    return env


def model_hash():
    """The wrapper's config hash for web from the active checkout (T1's model_hash)."""
    rc, out, _ = run(["./scripts/parkio-prod-compose.sh", "config", "--hash", "web"], cwd=REL, env=clean_env(), timeout=180)
    for line in out.splitlines():
        parts = line.split()
        if rc == 0 and len(parts) == 2 and parts[0] == "web":
            return parts[1]
    return None


def guard(ref, rec):
    """The release checkout's F-05 guard, offline: identity, configDigest and the key fingerprint of the pulled image."""
    ev = os.path.join(record_dir(), "guard-evidence.json")
    rc, out, err = run(["bash", "./scripts/guard-web-synthetic-map-deploy.sh", "--image", ref, "--expected-platform", "linux/amd64",
                        "--expected-map-key-fingerprint", KEY_FP, "--evidence-out", ev], cwd=REL, env=clean_env(), timeout=600)
    try:
        data = json.load(open(ev, encoding="utf-8"))
    except (OSError, ValueError):
        data = {}
    rec["guard"] = {k: data.get(k) for k in ("configDigest", "configDigestStatus", "fingerprintCheck", "platform")}
    return rc, data, (out + err).strip().splitlines()[-1:] or [""]


def wrapper(fingerprint):
    env = clean_env(fingerprint)
    args = ["./scripts/parkio-prod-compose.sh", "up", "-d", "--no-build", "--no-deps", "--force-recreate", "web"]
    say(f"  Run: cd {REL} && PARKIO_ENV_FILE={ENV_FILE}{' PARKIO_WEB_EXPECTED_MAP_KEY_FINGERPRINT=' + fingerprint if fingerprint else ''} "
        + " ".join(args))
    rc, out, err = run(args, cwd=REL, env=env, timeout=900)
    log = os.path.join(record_dir(), "wrapper.log")
    fd = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(out + err)
    return rc, sha256_file(log)


def post_checks(rec, baseline, before_id, want_ref):
    deadline = time.time() + HEALTH_WAIT
    web = None
    while time.time() < deadline:
        web = one(containers(), "web")
        if web and web["health"] == "healthy":
            break
        time.sleep(3)
    check("web is healthy", bool(web) and web["health"] == "healthy", (web or {}).get("health") or "?")
    check("web is a new container", bool(web) and web["id"] != before_id, (web or {}).get("id", "?")[:12])
    rc, want_id, _ = docker("image", "inspect", "--format", "{{.Id}}", want_ref)
    check("web runs the expected image", bool(web) and rc == 0 and web["image"] == want_id.strip(), (web or {}).get("image", "?")[:19])
    h = model_hash()
    check("web config hash = the wrapper's model hash from the active checkout", bool(web) and h is not None and h == web["hash"],
          f"model {(h or '?')[:12]} container {((web or {}).get('hash') or '?')[:12]}")
    now_cs = containers() or {}
    changed = []
    for svc, entries in baseline.items():
        if svc == "web":
            continue
        cur = [{k: e[k] for k in ("id", "image", "hash")} for e in now_cs.get(svc, [])]
        was = [{k: e[k] for k in ("id", "image", "hash")} for e in entries]
        if sorted(map(json.dumps, cur)) != sorted(map(json.dumps, was)):
            changed.append(svc)
    added = sorted(set(now_cs) - set(baseline))
    check("every other container unchanged against the baseline", not changed and not added, ", ".join(changed + added))
    for path in ("/", "/login", "/explore"):
        try:
            with urllib.request.urlopen(urllib.request.Request(APP + path, headers={"User-Agent": "w1-web-deploy"}), timeout=20) as r:
                body = r.read(200000).decode("utf-8", "replace")
                check(f"{APP}{path} answers 200 with the app root", r.status == 200 and 'id="root"' in body, str(r.status))
        except (urllib.error.URLError, OSError) as e:
            check(f"{APP}{path} answers 200 with the app root", False, str(e)[:80])
    rec["webAfter"] = {k: (web or {}).get(k) for k in ("id", "image", "hash", "health")}


def cmd_recreate(a):
    r1, digest, iid = want_target(a)
    if not a.get("--yes"):
        raise SystemExit("usage: recreate needs --yes (the owner's written go for this step)")
    say(f"W1 recreate | {STAMP} | the only live change: web")
    rec = {"tool": "w1-web-deploy", "command": "recreate", "stamp": STAMP, "r1": r1, "digest": digest, "imageId": iid}
    basics()
    pre = latest_pre(r1, digest, iid)
    age = (time.time() - calendar.timegm(time.strptime(pre["stamp"], "%Y%m%dT%H%M%SZ"))) if pre else None
    check("a passing pre for these arguments within 6 hours (the latest one)",
          bool(pre) and pre.get("verdict") == "PASS" and age is not None and 0 <= age <= PRE_MAX_AGE,
          f"{pre['stamp']} {pre.get('verdict')}" if pre else "none")
    web = one(containers(), "web")
    base_web = ((pre or {}).get("baseline") or {}).get("web") or [{}]
    check("web is still the baseline container", bool(web) and web["id"] == base_web[0].get("id"), (web or {}).get("id", "?")[:12])
    rc, _, _ = docker("image", "inspect", "--format", "{{.Id}}", f"{REPO}@{digest}")
    check("the new pin is present locally", rc == 0)
    head, clean = checkout_state()
    check("the release checkout is clean at the PIN or already at R1", clean and head in (PIN, r1), (head or "?")[:12])
    if not all(c["pass"] for c in CHECKS):
        return finish("recreate", rec)
    if head != r1:
        rc, _, err = git("checkout", "--quiet", "--detach", r1)
        head, clean = checkout_state()
        if not check("checked out R1 (clean)", rc == 0 and head == r1 and clean, err.strip()[:120]):
            return finish("recreate", rec)
    rc, logsha = wrapper(KEY_FP)
    check("wrapper exit 0 (F-05 with the fingerprint, conf.d and API endpoint checks ran)", rc == 0, f"exit {rc}; log {logsha[:16]}")
    if rc == 0:
        post_checks(rec, pre["baseline"], web["id"], f"{REPO}@{digest}")
    else:
        now_web = one(containers(), "web")
        if now_web and now_web["id"] == web["id"]:
            grc, _, gerr = git("checkout", "--quiet", "--detach", PIN)
            head, clean = checkout_state()
            rec["checkoutRestored"] = grc == 0 and head == PIN and clean
            say(f"  web is unchanged; the checkout went back to the PIN: {rec['checkoutRestored']}")
        else:
            rec["checkoutRestored"] = False
            say("  web changed although the wrapper failed: the checkout stays at R1; see the wrapper log, then rollback")
    return finish("recreate", rec)


def cmd_rollback(a):
    if not a.get("--yes"):
        raise SystemExit("usage: rollback needs --yes")
    say(f"W1 rollback | {STAMP} | web back to the f888a81b pin")
    rec = {"tool": "w1-web-deploy", "command": "rollback", "stamp": STAMP}
    basics()
    pres = []
    try:
        pres = [os.path.join(OUT, s, "pre.json") for s in sorted(os.listdir(OUT)) if os.path.isfile(os.path.join(OUT, s, "pre.json"))]
    except OSError:
        pass
    baseline = json.load(open(pres[-1], encoding="utf-8")).get("baseline") if pres else None
    check("a recorded baseline exists", bool(baseline))
    web = one(containers(), "web")
    check("one web container", bool(web))
    if not all(c["pass"] for c in CHECKS):
        return finish("rollback", rec)
    rc, _, err = git("checkout", "--quiet", "--detach", PIN)
    head, clean = checkout_state()
    if not check("checked out the PIN (clean)", rc == 0 and head == PIN and clean, err.strip()[:120]):
        return finish("rollback", rec)
    rc, logsha = wrapper(None)
    check("wrapper exit 0", rc == 0, f"exit {rc}; log {logsha[:16]}")
    if rc == 0:
        post_checks(rec, baseline, web["id"], OLD_IMAGE)
    return finish("rollback", rec)


def finish(name, rec):
    rec["checks"] = CHECKS
    rec["verdict"] = "PASS" if CHECKS and all(c["pass"] for c in CHECKS) else "FAIL"
    path = write_json(f"{name}.json", rec)
    say(f"W1 {name}: {rec['verdict']} | record {path}")
    return 0 if rec["verdict"] == "PASS" else 1


def cmd_status():
    try:
        for stamp in sorted(os.listdir(OUT))[-6:]:
            for f in sorted(os.listdir(os.path.join(OUT, stamp))):
                if f.endswith(".json"):
                    r = json.load(open(os.path.join(OUT, stamp, f), encoding="utf-8"))
                    say(f"{stamp} {r.get('command')}: {r.get('verdict')}")
    except OSError:
        say("no records")
    return 0


def main(argv):
    if not argv:
        raise SystemExit(__doc__)
    cmd, rest = argv[0], argv[1:]
    if cmd == "status":
        return cmd_status()
    a = args_of(rest)
    if cmd == "pre":
        return cmd_pre(a)
    if cmd == "recreate":
        return cmd_recreate(a)
    if cmd == "rollback":
        return cmd_rollback(a)
    raise SystemExit(f"usage: unknown command {cmd!r}")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
