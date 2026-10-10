#!/usr/bin/env python3
"""S1 - pebble / Go stdlib findings in the release images and the D7 build inputs (read-only; owner request 2026-10-10).

Run as civo on parkio-civo-prod (Python 3.8+):
  python3 -I /tmp/s1-image-findings.py

Why: since 2026-10-09 Security CI fails for every Java service image it builds: Trivy finds Go stdlib v1.26.7 in
usr/bin/pebble (CVE-2026-78667, CVE-2026-78669, CVE-2026-97031; HIGH; fixed in 1.26.9 and 1.27.2). CI builds from the
floating eclipse-temurin tags. This check answers, for the images this release would actually run, whether that binary
is present and which Go version built it.

Images examined (all must already be present locally; nothing is pulled):
  - the five digest-pinned release images (gateway, media, parking, auth, web), identity checked against the pins;
  - the D7 build inputs as tagged on the host: eclipse-temurin:21-jre (runtime base of the six host-built services) and
    eclipse-temurin:21-jdk (build stage); the six Dockerfiles in /opt/parkio-release (at 22f96990, clean) must name
    exactly these two.
For each image: /etc/os-release, whether /usr/bin/pebble exists, and if so its size, SHA-256, Go version and main module
from the build information embedded in the binary (the file is copied out and parsed; it is never executed), plus the
image creation time and selected OCI labels.
How: one container per image is created and never started (docker create --pull never --network none, entrypoint set to
a path that does not exist, label parkio.release.probe=s1), files are copied out with docker cp -L, and the container is
removed (docker rm -v). Every image tag and every project container's image and state are compared before and after; the
check fails if anything changed or a probe container is left. No build, pull, tag, start, stop or recreate.
It prints no secret, environment value or URL. Report and JSON record go to ~/parkio-release-20261009 (mode 600).
Exit 0: the check completed (findings are reported, not judged). Exit 1: incomplete, or something changed.
"""
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import tarfile
from datetime import datetime, timezone

PIN = "22f9699038cdae15ffff5dd7433c4a64aed0a951"
REL = os.environ.get("S1_RELEASE", "/opt/parkio-release")
PROJECT = os.environ.get("S1_PROJECT", "parkio")
OUT = os.environ.get("S1_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009"))
JRE = os.environ.get("S1_JRE", "eclipse-temurin:21-jre")
JDK = os.environ.get("S1_JDK", "eclipse-temurin:21-jdk")
PEBBLE = "/usr/bin/pebble"
CVES = "CVE-2026-78667, CVE-2026-78669, CVE-2026-97031"
FIXED = {(1, 26): 9, (1, 27): 2}  # Trivy's fixed versions for those three findings: 1.26.9 and 1.27.2
JAVA_REV = "a658edcb0e4152b760ecafa3ef02d7410f9b0ef8"
WEB_REV = "843ae7cb461bbb66e688964bf2441b4442d23f27"
GHCR = "ghcr.io/adberilgen35/parkio/"
# Same pins as P2 (docker/docker-compose.{gmp-release-pins,auth-release-pin,web-release-pin}.yml at 22f96990):
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
if os.environ.get("S1_EXPECT"):  # self-test only: synthetic registry references
    with open(os.environ["S1_EXPECT"], encoding="utf-8") as _fh:
        RELEASE = {k: tuple(v) for k, v in json.load(_fh).items()}
BUILT = ["user-service", "gamification-service", "notification-service", "moderation-service",
         "ai-validation-service", "analytics-service"]
LABELS = ["org.opencontainers.image.ref.name", "org.opencontainers.image.version", "org.opencontainers.image.revision",
          "org.opencontainers.image.created", "org.opencontainers.image.base.name", "org.opencontainers.image.base.digest"]
GO_MAGIC = b"\xff Go buildinf:"
MAX_BINARY = 128 * 1024 * 1024

REPORT = []
RECORD = {"tool": "s1-image-findings", "release": PIN, "findingsChecked": CVES, "images": {}}
STAMP = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
URL = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://[^\s'\"]+")
GATES = []


def say(line=""):
    line = URL.sub("<url>", line)
    print(line, flush=True)
    REPORT.append(line)


def gate(name, ok, detail=""):
    GATES.append((name, ok))
    say(f"  {'PASS' if ok else 'FAIL'} {name}{': ' + detail if detail else ''}")


def save():
    os.makedirs(OUT, mode=0o700, exist_ok=True)
    for suffix, text in ((".txt", "\n".join(REPORT) + "\n"), (".json", json.dumps(RECORD, indent=2, sort_keys=True) + "\n")):
        path = os.path.join(OUT, f"S1-image-findings-{STAMP}{suffix}")
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"saved: {path}")


def finish():
    failed = [n for n, ok in GATES if not ok]
    RECORD.update(stamp=STAMP, gates={n: ok for n, ok in GATES}, result="COMPLETE" if not failed else "INCOMPLETE")
    say("")
    say(f"check gates passed {len(GATES) - len(failed)}/{len(GATES)}" + (f"; failed: {', '.join(failed)}" if failed else ""))
    say("S1 CHECK COMPLETE (findings above are facts for the owner's decision)" if not failed else "S1 CHECK INCOMPLETE")
    save()
    sys.exit(0 if not failed else 1)


def stop(message):
    say(f"STOP: {message}")
    GATES.append((message, False))
    finish()


def run(cmd, timeout=120, binary=False):
    try:
        p = subprocess.run(cmd, capture_output=True, text=not binary, timeout=timeout)
    except FileNotFoundError:
        return 127, (b"" if binary else ""), "not found"
    except subprocess.TimeoutExpired:
        return 124, (b"" if binary else ""), "timed out"
    err = p.stderr.decode("utf-8", "replace") if binary else p.stderr
    return p.returncode, p.stdout, err


def first_error(err):
    return next((ln.strip() for ln in (err or "").splitlines() if ln.strip()), "")[:200]


def short(image_id):
    return (image_id or "-").replace("sha256:", "")[:12]


def image_info(ref):
    rc, out, _ = run(["docker", "image", "inspect", ref])
    if rc != 0 or not out.strip().startswith("["):
        return None
    data = json.loads(out)
    return data[0] if data else None


def identity(info, pinned_ref, accepted, revision):
    """(ok, note) for a release image: content identity, platform and OCI revision (as in P2)."""
    repo, digest = pinned_ref.split("@", 1)
    image_id = info.get("Id")
    descriptor = info.get("Descriptor") or {}
    config_digest = (descriptor.get("annotations") or {}).get("config.digest")
    if image_id == accepted:
        how = "image id = accepted config digest"
    elif image_id == digest or descriptor.get("digest") == digest:
        if config_digest and config_digest != accepted:
            return False, f"config digest {short(config_digest)} != accepted {short(accepted)}"
        how = "manifest digest = pin"
    else:
        return False, f"identity mismatch: id {short(image_id)} is neither {short(accepted)} nor {short(digest)}"
    if f"{repo}@{digest}" not in (info.get("RepoDigests") or []):
        return False, "pinned repo digest missing from RepoDigests"
    platform = f"{info.get('Os')}/{info.get('Architecture')}"
    if platform != "linux/amd64":
        return False, f"platform {platform}"
    got = ((info.get("Config") or {}).get("Labels") or {}).get("org.opencontainers.image.revision", "")
    if got != revision:
        return False, f"OCI revision {got[:12] or 'absent'} != {revision[:12]}"
    return True, f"{how}; linux/amd64; revision {revision[:12]}"


def tags():
    rc, out, err = run(["docker", "images", "--no-trunc", "--format", "{{.Repository}}:{{.Tag}} {{.ID}}"])
    if rc != 0:
        stop(f"docker images failed: {first_error(err)}")
    result = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 2 and not parts[0].endswith(":<none>"):
            result[parts[0]] = parts[1]
    return result


def containers():
    rc, out, err = run(["docker", "ps", "-a", "--no-trunc", "--filter", f"label=com.docker.compose.project={PROJECT}",
                        "--format", "{{.ID}}"])
    if rc != 0:
        stop(f"docker ps failed: {first_error(err)}")
    result = {}
    for cid in out.split():
        rc, info, _ = run(["docker", "inspect", "--format",
                           '{{index .Config.Labels "com.docker.compose.service"}} {{.Image}} {{.State.Status}} {{.State.StartedAt}}',
                           cid])
        result[cid] = info.strip()
    return result


def probes_left():
    rc, out, _ = run(["docker", "ps", "-aq", "--filter", "label=parkio.release.probe=s1"])
    return [c for c in out.split() if re.fullmatch(r"[0-9a-f]{12,64}", c)]


def uvarint_string(data):
    n = shift = 0
    for idx, b in enumerate(data[:10]):
        n |= (b & 0x7F) << shift
        if b < 0x80:
            start = idx + 1
            if start + n > len(data):
                return None, b""
            return data[start:start + n], data[start + n:]
        shift += 7
    return None, b""


def go_build_info(binary):
    """{'go': 'go1.26.7', 'path': ..., 'module': ..., 'moduleVersion': ...} or None if no Go build information is found."""
    i = binary.find(GO_MAGIC)
    while i != -1:
        header = binary[i:i + 32]
        if len(header) == 32 and header[14] in (4, 8) and header[15] & 0x2:
            vers, rest = uvarint_string(binary[i + 32:])
            mod, _ = uvarint_string(rest)
            if vers and vers.startswith(b"go"):
                info = {"go": vers.decode("ascii", "replace"), "path": None, "module": None, "moduleVersion": None}
                if mod and len(mod) >= 33 and mod[len(mod) - 17:len(mod) - 16] == b"\n":
                    for line in mod[16:len(mod) - 16].decode("utf-8", "replace").splitlines():
                        cols = line.split("\t")
                        if cols[0] == "path" and len(cols) > 1:
                            info["path"] = cols[1]
                        elif cols[0] == "mod" and len(cols) > 2:
                            info["module"], info["moduleVersion"] = cols[1], cols[2]
                return info
        i = binary.find(GO_MAGIC, i + 1)
    return None


def classify(go):
    m = re.match(r"go(\d+)\.(\d+)(?:\.(\d+))?", go or "")
    if not m:
        return "unknown Go version"
    major, minor, patch = int(m.group(1)), int(m.group(2)), int(m.group(3) or 0)
    if (major, minor) in FIXED:
        need = FIXED[(major, minor)]
        return f"affected (< {major}.{minor}.{need})" if patch < need else f"fixed (>= {major}.{minor}.{need})"
    if (major, minor) < min(FIXED):
        return "affected (older than the fixed lines 1.26.9 / 1.27.2)"
    return "not affected (newer than the fixed lines)"


def copy_file(cid, path, limit):
    """('absent'|'file'|'other'|'error', bytes or reason) via docker cp -L to a tar stream."""
    rc, data, err = run(["docker", "cp", "-L", f"{cid}:{path}", "-"], binary=True, timeout=300)
    if rc != 0:
        text = (err or "").lower()
        if "could not find the file" in text or "no such container:path" in text or "no such file or directory" in text:
            return "absent", None
        return "error", first_error(err)
    try:
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as tar:
            member = tar.next()
            if member is None:
                return "absent", None
            if not member.isfile():
                return "other", "not a regular file"
            if member.size > limit:
                return "other", f"larger than {limit} bytes"
            return "file", tar.extractfile(member).read()
    except tarfile.TarError as exc:
        return "error", f"tar: {exc}"[:200]


def os_release(text):
    out = {}
    for line in text.splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            if key in ("ID", "VERSION_ID", "VERSION_CODENAME", "PRETTY_NAME"):
                out[key] = value.strip().strip('"')[:80]
    return out


def examine(role, ref, info):
    """Create a never-started container from ref, read /etc/os-release and /usr/bin/pebble, remove the container."""
    labels = (info.get("Config") or {}).get("Labels") or {}
    entry = {"role": role, "ref": ref, "imageId": info.get("Id"), "created": (info.get("Created") or "")[:19],
             "platform": f"{info.get('Os')}/{info.get('Architecture')}",
             "repoDigests": sorted(info.get("RepoDigests") or []),
             "labels": {k: labels[k][:120] for k in LABELS if k in labels}}
    name = f"s1-probe-{role}-{STAMP.lower()}"
    rc, cid, err = run(["docker", "create", "--pull", "never", "--network", "none", "--name", name,
                        "--label", "parkio.release.probe=s1", "--entrypoint", "/s1-probe-noop", ref])
    cid = cid.strip()
    if rc != 0 or not cid:
        entry["error"] = f"docker create failed: {first_error(err)}"
        return entry, False
    try:
        kind, data = copy_file(cid, "/etc/os-release", 65536)
        entry["osRelease"] = os_release(data.decode("utf-8", "replace")) if kind == "file" else {"state": kind}
        kind, data = copy_file(cid, PEBBLE, MAX_BINARY)
        if kind == "file":
            build = go_build_info(data)
            entry["pebble"] = {"present": True, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                               "goBuildInfo": build, "status": classify(build["go"]) if build else "no Go build information"}
        elif kind == "absent":
            entry["pebble"] = {"present": False}
        else:
            entry["pebble"] = {"present": None, "state": kind, "detail": data}
    finally:
        rc_rm, _, err_rm = run(["docker", "rm", "-v", cid])
    ok = (rc_rm == 0 and (entry.get("pebble") or {}).get("present") is not None
          and "state" not in (entry.get("osRelease") or {"state": "unread"}))
    if rc_rm != 0:
        entry["error"] = f"docker rm failed: {first_error(err_rm)}"
    return entry, ok


def describe(entry):
    osr = entry.get("osRelease") or {}
    os_text = (f"{osr.get('ID', '?')} {osr.get('VERSION_ID', '?')} ({osr.get('VERSION_CODENAME', '?')})"
               if "state" not in osr else f"os-release {osr['state']}")
    peb = entry.get("pebble") or {}
    if peb.get("present") is True:
        b = peb.get("goBuildInfo") or {}
        mod = (f"{b.get('module')} {b.get('moduleVersion')}" if b.get("module")
               else f"path {b.get('path')}" if b.get("path") else "module unknown")
        peb_text = (f"/usr/bin/pebble PRESENT ({peb['size']} bytes, sha256 {peb['sha256'][:12]}), "
                    f"built with {b.get('go', 'unknown Go')}, {mod} -> {peb['status']}")
    elif peb.get("present") is False:
        peb_text = "/usr/bin/pebble absent"
    else:
        peb_text = f"/usr/bin/pebble not read ({peb.get('state', 'error')}: {peb.get('detail', '')})"
    return f"{os_text}; created {entry['created']}; {peb_text}"


def main():
    say(f"S1 image findings | stamp {STAMP} | release {REL} | project {PROJECT} | {CVES} in usr/bin/pebble")
    say("")
    say("A. Inputs")
    rc, head, _ = run(["git", "--no-optional-locks", "-C", REL, "rev-parse", "HEAD"])
    if head.strip() != PIN and not os.environ.get("S1_PIN_ANY"):
        stop(f"{REL} is not at {PIN}")
    rc, status, _ = run(["git", "--no-optional-locks", "-C", REL, "status", "--porcelain"])
    if rc != 0 or status.strip():
        stop(f"{REL} is not clean")
    for svc in BUILT:
        try:
            froms = re.findall(r"(?m)^FROM\s+(\S+)", open(os.path.join(REL, "services", svc, "Dockerfile"), encoding="utf-8").read())
        except OSError:
            froms = []
        if froms != [JDK, JRE]:
            stop(f"{svc}/Dockerfile FROM lines are not [{JDK}, {JRE}]")
    say(f"  release checkout at {PIN[:12]}, clean; the six host-built Dockerfiles build FROM {JDK} and run FROM {JRE}")
    rc, version, _ = run(["docker", "version", "--format", "{{.Server.Version}}"])
    say(f"  docker {version.strip() or 'unknown'}")
    before_tags, before_ctr = tags(), containers()
    leftover = probes_left()
    if leftover:
        stop(f"{len(leftover)} container(s) labelled parkio.release.probe=s1 exist before the check")
    say(f"  before: {len(before_tags)} tags, {len(before_ctr)} project containers")
    say("")
    say("B. Release images (digest-pinned; present locally, nothing pulled)")
    for svc, (ref, accepted, revision) in RELEASE.items():
        info = image_info(ref)
        if info is None:
            gate(f"{svc} release image present", False, "absent locally (not pulled by this check)")
            continue
        ok, note = identity(info, ref, accepted, revision)
        gate(f"{svc} release image identity", ok, note)
        if not ok:
            continue
        entry, done = examine(svc, ref, info)
        RECORD["images"][svc] = entry
        gate(f"{svc} examined", done, describe(entry))
    say("")
    say("C. D7 build inputs (as tagged on the host; nothing pulled)")
    for role, ref in (("d7-runtime-base", JRE), ("d7-build-base", JDK)):
        info = image_info(ref)
        if info is None:
            gate(f"{ref} present", False, "absent locally (the D7 build would pull it)")
            continue
        entry, done = examine(role, ref, info)
        RECORD["images"][role] = entry
        digests = ", ".join(d.split("@", 1)[1][:19] for d in entry["repoDigests"]) or "none"
        gate(f"{ref} examined", done, f"image {short(entry['imageId'])}, repo digest {digests}; " + describe(entry))
    say("")
    say("D. Nothing changed")
    after_tags, after_ctr = tags(), containers()
    moved = sorted(t for t, i in before_tags.items() if after_tags.get(t) != i)
    added = sorted(t for t in after_tags if t not in before_tags)
    gate("no image tag added, moved or removed", not moved and not added,
         f"added {', '.join(added) or 'none'}; moved or removed {', '.join(moved) or 'none'}")
    gate("every project container unchanged (image, state, start time)", after_ctr == before_ctr,
         f"{len(after_ctr)} containers")
    left = probes_left()
    gate("no probe container left", not left, f"{len(left)} left" if left else "")
    say("")
    say("E. Findings")
    affected = [k for k, e in RECORD["images"].items() if (e.get("pebble") or {}).get("status", "").startswith("affected")]
    present = [k for k, e in RECORD["images"].items() if (e.get("pebble") or {}).get("present") is True]
    say(f"  /usr/bin/pebble present in: {', '.join(present) or 'none'}")
    say(f"  pebble built with a Go version Trivy reports as affected: {', '.join(affected) or 'none'}")
    d7 = (RECORD["images"].get("d7-runtime-base") or {}).get("pebble") or {}
    say("  D7: the six host-built services would inherit "
        + ("the runtime base's /usr/bin/pebble" if d7.get("present") else "no /usr/bin/pebble from the runtime base"
           if d7.get("present") is False else "an unknown state from the runtime base"))
    RECORD["findings"] = {"pebblePresent": present, "affected": affected}
    finish()


if __name__ == "__main__":
    main()
