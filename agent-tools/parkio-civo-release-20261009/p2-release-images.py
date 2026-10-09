#!/usr/bin/env python3
"""P2 - rollback image tags and release image pulls for the scoped release on parkio-civo-prod.

Run as civo on the host (Python 3.8+), after P1:
  python3 -I /tmp/p2-release-images.py

Release 22f96990 in /opt/parkio-release (must be clean); live checkout /opt/parkio (untouched); project parkio.

Changes it makes, and nothing else:
  1. One local tag per application image a running container uses, <repository>:rollback-pre-22f96990,
     pointing at that container's image id: the five digest-pinned services and the six host-built ones.
     All eleven are checked before the first tag is written. If a tag of that name exists and points at
     another image, the run stops before any change. A tag that already points at the same image is kept.
  2. docker pull of the five release images by digest (gateway, media, parking, auth, web), only when absent.
     A digest pull creates no repo:tag name, so no existing tag moves.
  3. Containers created from the new images and never started, removed again with their volumes
     (docker create/cp/rm): one from the new parking image to read /etc/timezone and /etc/localtime, and the
     ones the release wrapper's own web guards create when they inspect the new web image.
It does not build, pull base images, recreate, start or stop any service container, remove or move any
existing tag, or write into either checkout. The release Compose model is rendered in memory, and once into a
private temporary directory that is deleted again (the web guards read it from a file, as in the wrapper).
It prints no secret, environment value (except time-zone names), URL or e-mail. Report and JSON record go to
~/parkio-release-20261009 (mode 600).

A. Inputs: checkouts, Docker version and image store, free space.
B. Running images of the 14 services and the planned rollback tags (no change yet).
C. Rollback tags written and verified.
D. Release images: the five pins pulled and verified (identity, platform, OCI revision); the D2 images unchanged;
   the six host-built names before the D7 build.
E. D4 pre-check: the release wrapper's web map guard, conf.d check and API endpoint check on the new web image.
F. New parking image: effective JVM default time zone, which pgjdbc sends as the session TimeZone (V41).
G. D7 build inputs: base images present locally (not pulled), buildx, free space after.
"""
import io
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
import time
from datetime import datetime, timezone

PIN = "22f9699038cdae15ffff5dd7433c4a64aed0a951"
TAG = os.environ.get("P2_TAG", "rollback-pre-22f96990")
LIVE = os.environ.get("P2_LIVE", "/opt/parkio")
REL = os.environ.get("P2_RELEASE", "/opt/parkio-release")
ENV_FILE = os.environ.get("P2_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
PROJECT = os.environ.get("P2_PROJECT", "parkio")
OUT = os.environ.get("P2_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009"))
NR_BUDGET = os.environ.get("P2_NR_BUDGET", "/var/lib/parkio-nr-log-continuous/budget")
BASE_IMAGES = [b for b in os.environ.get("P2_BASE_IMAGES", "eclipse-temurin:21-jdk,eclipse-temurin:21-jre").split(",") if b]
CIVO_TRIGGER = "docker/docker-compose.azure-hosted-beta.yml"
CIVO_OVERLAY = "docker/docker-compose.civo-alertmanager.yml"

PINNED = ["gateway-service", "media-service", "parking-service", "auth-service", "web"]
BUILT = ["user-service", "gamification-service", "notification-service", "moderation-service",
         "ai-validation-service", "analytics-service"]
INFRA = ["alertmanager", "prometheus", "caddy"]
JAVA_REV = "a658edcb0e4152b760ecafa3ef02d7410f9b0ef8"
WEB_REV = "843ae7cb461bbb66e688964bf2441b4442d23f27"
GHCR = "ghcr.io/adberilgen35/parkio/"
# docker/docker-compose.{gmp-release-pins,auth-release-pin,web-release-pin}.yml at 22f96990:
# image reference, accepted image id (= registry config digest), OCI revision label.
RELEASE = {
    "gateway-service": (GHCR + "gateway-service@sha256:e061d7ed3ddc8ad10193f57283b8cb44dfcb66e480db3ca098742aac87bb8e3b",
                        "sha256:a2dbf7aae7b11a5d39bb3b274cc488f99b2f9aecc4b7b6338780b45ec54f936d", JAVA_REV),
    "media-service": (GHCR + "media-service@sha256:7a758cd6a0ced0acb80768266628a1fe49ba287892f07b0954c5dc5f1e5cdd5a",
                      "sha256:cfa7c3484af3a399952a4204775cb7a6fdf838053376c353087089b73af55c31", JAVA_REV),
    "parking-service": (GHCR + "parking-service@sha256:5c629fff91af92914d9355cf83c829d3175773d46bbe5eb190f431c88cdb53e6",
                        "sha256:236fe8dfcad46ba4e5dbff2e718bc390f776b5e8f9c33c489fb0f59da939dd0e", JAVA_REV),
    "auth-service": (GHCR + "auth-service@sha256:acc3669840ff47303e806725e3382331515af7d7cece3d3228c3135ad3462d2e",
                     "sha256:d33649be305cf8a8cd3ce0957172036d5150e0da55233ef1090407d2a2459ce3", JAVA_REV),
    "web": (GHCR + "web@sha256:4625a72e5dd3afd4cc6b2d2a62d8cdb999725942ed6471591d33b96cbd42b6a6",
            "sha256:8121516d6a8cc875e335e0b82721a5c089d4773a437e7eefbe93790ef777c00e", WEB_REV),
}
# The previous live pins the same files name as the rollback targets (informational comparison only).
PREVIOUS = {
    "gateway-service": GHCR + "gateway-service@sha256:866a7fe09ccc5fd2148db893fc8abb013a2c2e8ac9567f85a4eb6c7c11f9bb48",
    "media-service": GHCR + "media-service@sha256:62f49d04087fb7aa475db0a42c7f8d1e7d1511f84e6b496a58c5beda58f864ec",
    "parking-service": GHCR + "parking-service@sha256:85da26542fe6729b72183a3593df42f4f51482ffeb7a0e930a48b64e3b027fb2",
    "auth-service": GHCR + "auth-service@sha256:a4410a45634b73246e13c3054dc3b1d0c8069baa7c0738258ecf9774ca72514e",
    "web": GHCR + "web@sha256:aacf9dc9ab8ef412dee01429da2b2bbc33099c4560a7904f181f9fe6db381f6e",
}
if os.environ.get("P2_EXPECT"):  # self-test only: synthetic registry references
    with open(os.environ["P2_EXPECT"], encoding="utf-8") as _fh:
        _exp = json.load(_fh)
    RELEASE = {k: tuple(v) for k, v in _exp["release"].items()}
    PREVIOUS = _exp["previous"]

GUARD_MIN_FREE = 5368709120     # continuous_guard.sh PARKIO_NR_HOST_MIN_FREE_BYTES
WRAPPER_MIN_FREE = 12 * 1024 ** 3  # deploy capacity gate (parkio_require_free_disk /)
ZONE = re.compile(r"^[A-Za-z][A-Za-z0-9_+/-]{0,63}$")
UTC_NAMES = {"UTC", "Etc/UTC", "UCT", "Etc/UCT", "Universal", "Etc/Universal", "Zulu", "Etc/Zulu", "GMT", "Etc/GMT",
             "GMT0", "Etc/GMT0", "GMT+0", "Etc/GMT+0", "GMT-0", "Etc/GMT-0", "Greenwich", "Etc/Greenwich"}
UTC_FOOTERS = {"UTC0", "GMT0", "UCT0", "<+00>0"}

REPORT = []
RECORD = {"tool": "p2-release-images", "release": PIN, "rollbackTag": TAG, "services": {}}
STAMP = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
URL = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://[^\s'\"]+")
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
GATES = []


def say(line=""):
    line = EMAIL.sub("<email>", URL.sub("<url>", line))
    print(line, flush=True)
    REPORT.append(line)


def gate(name, ok, detail=""):
    GATES.append((name, ok))
    say(f"  {'PASS' if ok else 'FAIL'} {name}{': ' + detail if detail else ''}")


def save():
    os.makedirs(OUT, mode=0o700, exist_ok=True)
    for suffix, text in ((".txt", "\n".join(REPORT) + "\n"), (".json", json.dumps(RECORD, indent=2, sort_keys=True) + "\n")):
        path = os.path.join(OUT, f"P2-images-{STAMP}{suffix}")
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"saved: {path}")


def stop(message):
    say(f"STOP: {message}")
    say("P2 STOPPED (see the STOP line; tags written so far are listed above)")
    RECORD["result"] = "STOPPED"
    save()
    sys.exit(1)


def run(cmd, cwd=None, timeout=120, binary=False, env=None):
    try:
        p = subprocess.run(cmd, capture_output=True, text=not binary, cwd=cwd, timeout=timeout, env=env)
    except FileNotFoundError:
        return 127, (b"" if binary else ""), ""
    except subprocess.TimeoutExpired:
        return 124, (b"" if binary else ""), "timed out"
    err = p.stderr.decode("utf-8", "replace") if binary else p.stderr
    return p.returncode, p.stdout, err


def first_error(err):
    line = next((ln.strip() for ln in (err or "").splitlines() if ln.strip()), "")
    return line[:200]


def short(image_id):
    return (image_id or "-").replace("sha256:", "")[:12]


def git(*args, repo):
    rc, out, _ = run(["git", "--no-optional-locks", "-C", repo] + list(args))
    return rc, out.strip()


def gib(n):
    return f"{n / 1024 ** 3:.1f} GiB"


def free_bytes(path):
    while path and not os.path.exists(path):
        path = os.path.dirname(path)
    try:
        st = os.statvfs(path or "/")
    except OSError:
        return None
    return st.f_bavail * st.f_frsize


def image_info(ref):
    rc, out, _ = run(["docker", "image", "inspect", ref])
    if rc != 0 or not out.strip().startswith("["):
        return None
    data = json.loads(out)
    return data[0] if data else None


def repository(ref):
    """Repository part of an image reference, or None for an image id."""
    if not ref or ref.startswith("sha256:") or re.fullmatch(r"[0-9a-f]{12,64}", ref):
        return None
    name = ref.split("@", 1)[0]
    last = name.rsplit("/", 1)[-1]
    if ":" in last:
        name = name[: len(name) - len(last)] + last.split(":", 1)[0]
    return name


def identity(info, pinned_ref, accepted, revision):
    """(ok, note) for a pulled release image: content identity, platform and OCI revision."""
    repo, digest = pinned_ref.split("@", 1)
    image_id = info.get("Id")
    descriptor = info.get("Descriptor") or {}
    config_digest = (descriptor.get("annotations") or {}).get("config.digest")
    if image_id == accepted:
        how = "image id = accepted config digest"
    elif image_id == digest or descriptor.get("digest") == digest:
        if config_digest and config_digest != accepted:
            return False, f"config digest {short(config_digest)} != accepted {short(accepted)}"
        how = ("manifest digest = pin, config digest = accepted id" if config_digest
               else "manifest digest = pin (config digest not exposed by this store)")
    else:
        return False, f"identity mismatch: id {short(image_id)} is neither {short(accepted)} nor {short(digest)}"
    if f"{repo}@{digest}" not in (info.get("RepoDigests") or []):
        return False, "pinned repo digest missing from RepoDigests"
    platform = f"{info.get('Os')}/{info.get('Architecture')}"
    if platform != "linux/amd64":
        return False, f"platform {platform}"
    labels = (info.get("Config") or {}).get("Labels") or {}
    got = labels.get("org.opencontainers.image.revision", "")
    if got != revision:
        return False, f"OCI revision {got[:12] or 'absent'} != {revision[:12]}"
    return True, f"{how}; linux/amd64; revision {revision[:12]}"


def render_model():
    canonical = [ln.strip() for ln in open(os.path.join(REL, "docker", "compose.production.files"), encoding="utf-8")
                 if ln.strip() and not ln.strip().startswith("#")]
    files = [os.path.join(REL, f) for f in canonical]
    if CIVO_TRIGGER in canonical:
        files.append(os.path.join(REL, CIVO_OVERLAY))
    cmd = ["docker", "compose", "--env-file", ENV_FILE]
    for f in files:
        cmd += ["-f", f]
    rc, out, err = run(cmd + ["config", "--format", "json"], cwd=REL)
    if rc != 0:
        return None, None
    return json.loads(out), cmd


def container(service):
    rc, out, _ = run(["docker", "ps", "-q", "--no-trunc", "--filter", f"label=com.docker.compose.project={PROJECT}",
                      "--filter", f"label=com.docker.compose.service={service}", "--filter", "status=running"])
    ids = out.split()
    if len(ids) != 1:
        return None, len(ids)
    rc, out, _ = run(["docker", "inspect", ids[0]])
    return (json.loads(out)[0] if rc == 0 else None), 1


def zone_of(raw):
    """Zone id the JVM derives from a TZ value, a /etc/timezone line or a /etc/localtime link target."""
    value = (raw or "").strip()
    if value.startswith(":"):
        value = value[1:]
    if value.startswith("posix/"):
        value = value[6:]
    if "zoneinfo/" in value:
        value = value.split("zoneinfo/", 1)[1]
        if value.startswith("posix/"):
            value = value[6:]
    return value


def shown(zone):
    return zone if zone and ZONE.match(zone) else "<unexpected value>"


def user_timezone(options):
    m = re.search(r"-Duser\.timezone=(\S*)", options or "")
    return m.group(1).strip("'\"") if m else None


def env_map(pairs):
    out = {}
    for item in pairs or []:
        key, _, value = item.partition("=")
        out[key] = value
    return out


def copy_out(cid, path):
    """('absent'|'link'|'file', linkname or bytes) from a created container, via docker cp to a tar stream."""
    rc, data, _ = run(["docker", "cp", f"{cid}:{path}", "-"], binary=True, timeout=60)
    if rc != 0 or not data:
        return "absent", None
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as tar:
        member = tar.next()
        if member is None:
            return "absent", None
        if member.issym() or member.islnk():
            return "link", member.linkname
        if member.isfile():
            return "file", tar.extractfile(member).read(65536)
    return "absent", None


def section_a():
    say("A. Inputs")
    for path in (LIVE, REL):
        if not os.path.isdir(path):
            stop(f"missing directory {path}")
    if not os.path.isfile(ENV_FILE):
        stop("missing env file")
    rc, head = git("rev-parse", "HEAD", repo=REL)
    if head != PIN and not os.environ.get("P2_PIN_ANY"):
        stop(f"{REL} is not at {PIN}")
    rc, porcelain = git("status", "--porcelain", repo=REL)
    if porcelain:
        stop(f"{REL} has {len(porcelain.splitlines())} changes; it must stay clean")
    rc, live_head = git("rev-parse", "--short=8", "HEAD", repo=LIVE)
    rc, live_porcelain = git("status", "--porcelain", repo=LIVE)
    say(f"  P2 | stamp {STAMP} | release {REL} @ {head[:12]} (clean) | live {LIVE} @ {live_head} "
        f"({len(live_porcelain.splitlines())} changes, untouched) | project {PROJECT}")
    rc, info, _ = run(["docker", "info", "--format", "{{json .}}"])
    if rc != 0:
        stop("docker info failed")
    info = json.loads(info)
    store = "containerd" if "io.containerd.snapshotter" in json.dumps(info.get("DriverStatus") or []) else "classic"
    root = info.get("DockerRootDir") or "/var/lib/docker"
    say(f"  docker {info.get('ServerVersion')}; storage driver {info.get('Driver')}; image store {store}")
    RECORD.update(stamp=STAMP, store=store, dockerVersion=info.get("ServerVersion"))
    return store, root


def space(label, root):
    parts = []
    for name, path, bound in (("/", "/", WRAPPER_MIN_FREE), ("docker root", root, None),
                              ("NR budget fs", NR_BUDGET, GUARD_MIN_FREE)):
        n = free_bytes(path)
        if n is None:
            parts.append(f"{name} unknown")
            continue
        parts.append(f"{name} {gib(n)}" + ("" if bound is None else (" (>= bound)" if n >= bound else " (BELOW bound)")))
    say(f"  free space {label}: " + "; ".join(parts))
    return free_bytes(root)


def section_b(model):
    say("")
    say("B. Running images of the 14 services and the planned rollback tags (no change yet)")
    services = model.get("services") or {}
    running, plan, conflicts, orphans = {}, [], [], []
    for svc in PINNED + BUILT + INFRA:
        c, n = container(svc)
        if not c:
            stop(f"{svc}: {n} running containers in project {PROJECT} (need exactly one)")
        ref = (c.get("Config") or {}).get("Image") or ""
        image_id = c.get("Image")
        info = image_info(image_id)
        if info is None and svc not in INFRA:
            # containerd image store: once the only name of an image moves, the image record is gone and
            # the running container's image can no longer be tagged by id.
            orphans.append(f"{svc} runs {short(image_id)}, which has no image record any more")
        info = info or {}
        rev = ((info.get("Config") or {}).get("Labels") or {}).get("org.opencontainers.image.revision", "")
        running[svc] = {"container": c["Id"], "configImage": ref, "runningImageId": image_id,
                        "runningRevision": rev, "created": (c.get("Created") or "")[:19]}
        if svc in INFRA:
            continue
        repo = repository(ref)
        note = ""
        if repo is None:
            repo = RELEASE[svc][0].split("@", 1)[0] if svc in PINNED else f"{PROJECT}-{svc}"
            note = " (container names an image id; repository from the release model)"
        target = f"{repo}:{TAG}"
        existing = image_info(target)
        state = "to create" if existing is None else ("kept (same image)" if existing.get("Id") == image_id else "CONFLICT")
        if state == "CONFLICT":
            conflicts.append(f"{target} -> {short(existing.get('Id'))}, running {short(image_id)}")
        extra = ""
        if svc in PINNED:
            previous = PREVIOUS.get(svc)
            same_previous = previous and (ref == previous or previous in (info.get("RepoDigests") or []))
            extra = "; = previous pin named in the release file" if same_previous else (
                "; differs from the previous pin named in the release file" if previous else "")
            model_ref = (services.get(svc) or {}).get("image")
            if model_ref != RELEASE[svc][0]:
                stop(f"{svc}: release model image is not the expected 22f96990 pin")
        else:
            if (services.get(svc) or {}).get("image"):
                stop(f"{svc}: release model names an image; expected build-only (host-built)")
            latest = image_info(f"{PROJECT}-{svc}:latest")
            moved = "absent" if latest is None else ("= running" if latest.get("Id") == image_id else "MOVED since the container started")
            running[svc]["latestTag"] = moved
            extra = f"; {PROJECT}-{svc}:latest {moved}"
        running[svc]["rollbackRef"] = target
        plan.append((svc, image_id, target, state))
        say(f"  {svc}: running {short(image_id)} from {ref or '?'}{note}; revision {rev[:12] or 'absent'}{extra}")
        say(f"    rollback tag {target}: {state}")
    for svc in INFRA:
        r = running[svc]
        model_ref = (services.get(svc) or {}).get("image") or ""
        resolved = image_info(model_ref) if model_ref else None
        same = model_ref == r["configImage"] and resolved is not None and resolved.get("Id") == r["runningImageId"]
        r.update(releaseRef=model_ref, unchanged=same)
        say(f"  {svc}: running {short(r['runningImageId'])} from {r['configImage']}; release model {model_ref or 'none'} "
            f"-> {'same reference, resolves to the running image: D2 keeps the image' if same else 'DIFFERENT image: D2 would change it'}")
    if conflicts:
        stop("rollback tag name already used by another image (nothing was changed): " + "; ".join(conflicts))
    if orphans:
        stop("running image not taggable (nothing was changed): " + "; ".join(orphans)
             + ". Its name moved on an image store that drops unnamed images; a rollback image needs a decision")
    RECORD["services"] = running
    return running, plan


def section_c(plan, running):
    say("")
    say("C. Rollback tags")
    for svc, image_id, target, state in plan:
        if state == "to create":
            rc, _, err = run(["docker", "tag", image_id, target])
            if rc != 0:
                stop(f"docker tag for {svc} failed: {first_error(err)}")
            state = "created"
        info = image_info(target)
        if not info or info.get("Id") != image_id:
            stop(f"{target} does not point at the running image after tagging")
        running[svc]["rollbackTagState"] = "created" if state == "created" else "kept"
        say(f"  {target} -> {short(image_id)} ({'created' if state == 'created' else 'kept'}, verified)")
    gate("rollback tags", True, f"{len(plan)} of {len(plan)} point at the running images")


def section_d(running):
    say("")
    say("D. Release images")
    verified = {}
    for svc in PINNED:
        ref, accepted, revision = RELEASE[svc]
        info = image_info(ref)
        action = "present"
        if info is None:
            say(f"  pulling {svc} ...")
            for attempt in (1, 2):
                rc, _, err = run(["docker", "pull", "--quiet", ref], timeout=1800)
                if rc == 0:
                    break
                say(f"  {svc}: pull attempt {attempt} failed (exit {rc}) {first_error(err)}")
                if attempt == 1:
                    time.sleep(10)
            info = image_info(ref)
            action = "pulled"
        if info is None:
            running[svc]["release"] = {"ref": ref, "verified": False}
            gate(f"{svc} release image", False, "not available locally")
            continue
        ok, note = identity(info, ref, accepted, revision)
        verified[svc] = ok
        running[svc]["release"] = {"ref": ref, "imageId": info.get("Id"), "verified": ok, "action": action,
                                   "sizeBytes": info.get("Size")}
        gate(f"{svc} release image", ok, f"{action}; {ref.rsplit('@', 1)[1][:19]}; {note}")
    say("  D2 images: " + "; ".join(f"{s} {'unchanged' if running[s]['unchanged'] else 'CHANGES'}" for s in INFRA))
    gate("D2 recreates keep their images", all(running[s]["unchanged"] for s in INFRA))
    say(f"  D7 (host-built, not built by P2): "
        + "; ".join(f"{s} latest {running[s]['latestTag']}" for s in BUILT))
    return verified


def section_e(verified, compose_cmd):
    say("")
    say("E. D4 pre-check: the release wrapper's web guards on the new web image (docker create/cp/rm only)")
    if not verified.get("web"):
        gate("web guards", False, "skipped: the new web image is not verified")
        return
    tmp = tempfile.mkdtemp(prefix="p2-webguard-")
    results = {}
    try:
        model_path = os.path.join(tmp, "model.json")
        binding = os.path.join(tmp, "web-binding.yml")
        rc, out, err = run(compose_cmd + ["config", "--format", "json"], cwd=REL)
        if rc != 0:
            gate("web guards", False, "cannot render the release model")
            return
        fd = os.open(model_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(out)
        checks = (("map guard", "web-map-guard.sh", 'parkio_web_guard_bind "$2" "$3" --env-file "$4"'),
                  ("conf.d check", "web-conf-d-guard.sh", 'parkio_web_conf_d_check "$2" "$3"'),
                  ("API endpoint check", "web-api-endpoint-guard.sh", 'parkio_web_api_endpoint_check "$2" "$3"'))
        for label, lib, call in checks:
            script = f'set -uo pipefail; source "$1/scripts/lib/{lib}"; {call}'
            rc, out, err = run(["bash", "-c", script, "p2", REL, model_path, binding, ENV_FILE], cwd=REL, timeout=600,
                               env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"))
            text = [ln.strip() for ln in (out + "\n" + err).splitlines() if ln.strip()]
            verdict = next((ln for ln in reversed(text) if "PASS" in ln or "BLOCKED" in ln), text[-1] if text else "")
            results[label] = rc == 0
            gate(f"web {label}", rc == 0, verdict[:240])
            if rc != 0:
                break
    finally:
        for root, dirs, files in os.walk(tmp, topdown=False):
            for name in files:
                os.remove(os.path.join(root, name))
            for name in dirs:
                os.rmdir(os.path.join(root, name))
        os.rmdir(tmp)
    RECORD["webGuards"] = results


def section_f(verified, model):
    say("")
    say("F. New parking image: effective JVM default time zone (pgjdbc sends it as the session TimeZone; V41)")
    if not verified.get("parking-service"):
        gate("parking session time zone is UTC", False, "skipped: the new parking image is not verified")
        return
    ref = RELEASE["parking-service"][0]
    info = image_info(ref)
    cfg = info.get("Config") or {}
    image_env = env_map(cfg.get("Env"))
    args = " ".join((cfg.get("Entrypoint") or []) + (cfg.get("Cmd") or []))
    svc = (model.get("services") or {}).get("parking-service") or {}
    model_env = svc.get("environment") or {}
    if isinstance(model_env, list):
        model_env = env_map(model_env)
    model_args = " ".join(str(x) for x in (svc.get("entrypoint") or []) + (svc.get("command") or []))
    env = dict(image_env)
    env.update({k: ("" if v is None else str(v)) for k, v in model_env.items()})

    def describe(source):
        parts = [f"TZ {shown(zone_of(source['TZ'])) if source.get('TZ') else 'absent'}"]
        for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"):
            if key in source:
                tz = user_timezone(source[key])
                parts.append(f"{key} {'user.timezone=' + shown(tz) if tz is not None else 'without user.timezone'}")
            else:
                parts.append(f"{key} absent")
        return "; ".join(parts)

    say(f"  image config: {describe(image_env)}; entrypoint/cmd "
        f"{'with user.timezone' if user_timezone(args) is not None else 'without user.timezone'}")
    say(f"  release model (parking-service): {describe({k: ('' if v is None else str(v)) for k, v in model_env.items()})}; "
        f"{'entrypoint/command override present' if model_args else 'no entrypoint/command override'}")
    name = f"p2-tzprobe-{STAMP.lower()}"
    rc, cid, err = run(["docker", "create", "--pull", "never", "--network", "none", "--name", name,
                        "--label", "parkio.release.probe=p2", "--entrypoint", "/p2-tzprobe-noop", ref])
    cid = cid.strip()
    if rc != 0 or not cid:
        gate("parking session time zone is UTC", False, f"docker create failed: {first_error(err)}")
        return
    try:
        tz_kind, tz_data = copy_out(cid, "/etc/timezone")
        lt_kind, lt_data = copy_out(cid, "/etc/localtime")
    finally:
        rc_rm, _, err_rm = run(["docker", "rm", "-v", cid])
    tz_line = ""
    if tz_kind == "file":
        tz_line = tz_data.decode("utf-8", "replace").splitlines()[0].strip() if tz_data.strip() else ""
    localtime = "absent"
    lt_zone = None
    lt_utc = None
    if lt_kind == "link":
        lt_zone = zone_of(lt_data)
        localtime = f"-> {lt_data if re.match(r'^[A-Za-z0-9_./+-]{1,120}$', lt_data or '') else '<unexpected link>'}"
    elif lt_kind == "file":
        footer = lt_data.rstrip(b"\n").rsplit(b"\n", 1)[-1].decode("ascii", "replace") if lt_data.startswith(b"TZif") else ""
        lt_utc = footer in UTC_FOOTERS
        localtime = f"regular file, TZif footer {'UTC' if lt_utc else ('<' + footer + '>' if re.match(r'^[A-Za-z0-9<>+,./:-]{1,40}$', footer) else '<other>')}"
    say(f"  image files: /etc/timezone {shown(tz_line) if tz_line else ('empty' if tz_kind == 'file' else 'absent')}; "
        f"/etc/localtime {localtime}; probe container removed: {'yes' if rc_rm == 0 else 'NO - ' + first_error(err_rm)}")
    jvm_tz = user_timezone(env.get("JAVA_TOOL_OPTIONS")) or user_timezone(env.get("JDK_JAVA_OPTIONS")) \
        or user_timezone(args) or user_timezone(model_args)
    if jvm_tz:
        zone, source = zone_of(jvm_tz), "-Duser.timezone"
    elif env.get("TZ"):
        zone, source = zone_of(env["TZ"]), "TZ"
    elif tz_line:
        zone, source = zone_of(tz_line), "/etc/timezone"
    elif lt_zone:
        zone, source = lt_zone, "/etc/localtime link"
    elif lt_kind == "file":
        zone, source = ("UTC" if lt_utc else "unknown"), "/etc/localtime content"
    else:
        zone, source = "GMT", "JVM fallback (no zone configured)"
    utc = zone in UTC_NAMES
    RECORD["parkingZone"] = {"zone": shown(zone), "source": source, "utc": utc}
    say(f"  JVM resolution order user.timezone, TZ, /etc/timezone, /etc/localtime: {shown(zone)} from {source}")
    gate("parking session time zone is UTC", utc,
         "V41's UPDATE then changes no row by construction" if utc else "STOP before D6b: V41 decision needed")


def section_g(root, before):
    say("")
    say("G. D7 build inputs (nothing pulled or built)")
    for ref in BASE_IMAGES:
        info = image_info(ref)
        if info is None:
            say(f"  {ref}: absent locally (the D7 build would pull it)")
            RECORD.setdefault("baseImages", {})[ref] = None
            continue
        digest = next((d.split("@", 1)[1] for d in info.get("RepoDigests") or [] if "@" in d), "")
        RECORD.setdefault("baseImages", {})[ref] = {"id": info.get("Id"), "created": (info.get("Created") or "")[:19],
                                                    "repoDigest": digest}
        say(f"  {ref}: present {short(info.get('Id'))}, created {(info.get('Created') or '')[:10]}, repo digest {digest[:19] or 'none'}")
    rc, out, _ = run(["docker", "buildx", "version"])
    say(f"  docker buildx: {'present' if rc == 0 else 'absent (compose uses the classic builder)'}")
    after = space("after", root)
    if before is not None and after is not None:
        say(f"  docker root used by P2: {gib(max(before - after, 0))}")


def main():
    store, root = section_a()
    before = space("before", root)
    model, compose_cmd = render_model()
    if model is None:
        stop("release model render failed")
    if model.get("name") != PROJECT:
        stop(f"release model project is {model.get('name')!r}, expected {PROJECT!r}")
    running, plan = section_b(model)
    section_c(plan, running)
    verified = section_d(running)
    section_e(verified, compose_cmd)
    section_f(verified, model)
    section_g(root, before)
    rc, porcelain = git("status", "--porcelain", repo=REL)
    gate("release checkout still clean", not porcelain)
    failed = [name for name, ok in GATES if not ok]
    RECORD["gates"] = {name: ok for name, ok in GATES}
    RECORD["result"] = "PASS" if not failed else "FAIL"
    say("")
    say("SUMMARY")
    say(f"gates passed {len(GATES) - len(failed)}/{len(GATES)}" + (f"; failed: {', '.join(failed)}" if failed else ""))
    say("P2 COMPLETE" if not failed else "P2 INCOMPLETE (a gate failed; no service container was touched)")
    save()
    sys.exit(0 if not failed else 1)


if __name__ == "__main__":
    main()
