#!/usr/bin/env python3
"""RB1 - re-baseline the paused Civo release on the remediated PIN (owner approval 2026-10-11, packet steps 5-6).

Run as civo on parkio-civo-prod (Python 3.8+):
  python3 -I /tmp/rb1-release-rebaseline.py

Changes, and nothing else:
  1. /opt/parkio-release (clean; at the old PIN 22f96990, or already at the new PIN) moves to the new PIN: git fetch of that
     commit from origin, then git checkout --detach. Nothing is fetched into or changed in /opt/parkio.
  2. docker pull of the four replacement images by digest (gateway, media, parking, auth), only when absent. A digest pull
     creates no repo:tag name, so no existing tag moves.
  3. One container per examined image, created and never started (--pull never, --network none, an entrypoint that does
     not exist, label parkio.release.probe=rb1), to copy out /etc/os-release and /usr/bin/pebble; then docker rm -v.
It does not build, start, stop or recreate anything and executes nothing from the images.

Checks:
  A. before any change: the release checkout is clean; the live checkout's HEAD and status are recorded;
  B. the new PIN differs from 22f96990 in exactly these paths, and nothing else:
       - the marketing change already live as D1 (13 files: web/marketing/**, the validator, the deployment guide);
       - the ten services/<name>-service/Dockerfile, each adding exactly the pebble-removal block and removing nothing;
       - docker/docker-compose.gmp-release-pins.yml and docker/docker-compose.auth-release-pin.yml, where only comments
         and the four image lines change, and the image lines name exactly the four replacement digests;
     then the checkout moves, and it must be at the new PIN and clean;
  C. the four replacement images: registry digest = pin, config digest = the accepted image id, linux/amd64, OCI
     revision = the remediated source; final filesystem without /usr/bin/pebble;
  D. reported as still carrying pebble, by design: the local eclipse-temurin:21-jre (D7 runtime base) and 21-jdk (D7 build
     stage). The ten runtime stages at the new PIN must remove it, so D7's final images will not carry it; D7 checks
     those images before starting them;
  E. nothing else changed: /opt/parkio (HEAD and status), every image tag (the rollback-pre-22f96990 tags included),
     every project container (image, state, start time); no probe container left.
Report and JSON record go to ~/parkio-release-20261009 (mode 600). Exit 0 only when every gate passes.
"""
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import tarfile
import time
from datetime import datetime, timezone

OLD_PIN = "22f9699038cdae15ffff5dd7433c4a64aed0a951"
NEW_PIN = "f888a81b113c0c486ea0949067779443857e644d"
SOURCE = "504b9cb453aca31954800cb4f73a074f3b8f5ef6"  # api after #332: the replacement images' OCI revision
GHCR = "ghcr.io/adberilgen35/parkio/"
# Filled from published-digests.json of the candidate-publish run: reference, accepted image id (= config digest).
RELEASE = {
    "gateway-service": ("ghcr.io/adberilgen35/parkio/gateway-service@sha256:ff21f4a6dbcd6baba9b2a69527a2d6006016c4d00e5390e1bce01b94e86cf026", "sha256:5fe2150ac77085c8c152fd5212286770744ffd7ab96a855d958a096c1a0addc4", SOURCE),
    "media-service": ("ghcr.io/adberilgen35/parkio/media-service@sha256:db3065e39a7086e6c740980de0ad2b3c0abda14676e3398d65d43e720e9b7c72", "sha256:1996c62a4da0f1452cc27d9718dad74dfb87c7faaf7916407981369211ad2007", SOURCE),
    "parking-service": ("ghcr.io/adberilgen35/parkio/parking-service@sha256:3888df0564910477d376a7d8ed8f6c4991e02e66a643f67ca8f2de5a067f96a9", "sha256:1cefaabd02ed5377fcc00189022f6ac4dff0663c6ccc9cdb6446af69d410f1e3", SOURCE),
    "auth-service": ("ghcr.io/adberilgen35/parkio/auth-service@sha256:2091de2e7efed0b6814a312bf5d74690274d778e6438491eb3884d9d710f3616", "sha256:6b881a91bbfe9ecaacf73cee59b1c10bce073b5bd7c953d36302de1ec0d26eb7", SOURCE),
}
if os.environ.get("RB1_EXPECT"):  # self-test only: synthetic pins, registry and repository
    with open(os.environ["RB1_EXPECT"], encoding="utf-8") as _fh:
        _exp = json.load(_fh)
    OLD_PIN, NEW_PIN, SOURCE = _exp["old_pin"], _exp["new_pin"], _exp["source"]
    RELEASE = {k: tuple(v) for k, v in _exp["release"].items()}
REL = os.environ.get("RB1_RELEASE", "/opt/parkio-release")
LIVE = os.environ.get("RB1_LIVE", "/opt/parkio")
PROJECT = os.environ.get("RB1_PROJECT", "parkio")
OUT = os.environ.get("RB1_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009"))
JRE = os.environ.get("RB1_JRE", "eclipse-temurin:21-jre")
JDK = os.environ.get("RB1_JDK", "eclipse-temurin:21-jdk")
ROLLBACK_SUFFIX = ":" + os.environ.get("RB1_ROLLBACK_TAG", "rollback-pre-22f96990")
SERVICES = ["gateway-service", "auth-service", "media-service", "parking-service", "user-service", "gamification-service",
            "notification-service", "moderation-service", "ai-validation-service", "analytics-service"]
BUILT = ["user-service", "gamification-service", "notification-service", "moderation-service", "ai-validation-service",
         "analytics-service"]
MARKETING = ["docs/releases/MARKETING-SOURCE-DEPLOYMENT.md", "scripts/validate-marketing-site.mjs", "web/marketing/.htaccess",
             "web/marketing/404.html", "web/marketing/i18n.js", "web/marketing/index.html", "web/marketing/privacy/index.html",
             "web/marketing/styles.css", "web/marketing/terms/index.html", "web/marketing/waitlist.adapter.test.mjs",
             "web/marketing/waitlist.pages.i18n.test.mjs", "web/marketing/waitlist/confirm/index.html",
             "web/marketing/waitlist/unsubscribe/index.html"]
DOCKERFILES = [f"services/{s}/Dockerfile" for s in SERVICES]
PIN_FILES = {"docker/docker-compose.gmp-release-pins.yml": ["gateway-service", "media-service", "parking-service"],
             "docker/docker-compose.auth-release-pin.yml": ["auth-service"]}
BLOCK = ["# The Ubuntu 26.04 base layer (built with Rockcraft) ships /usr/bin/pebble, a Go service manager",
         "# that nothing here runs: the base image clears its entrypoint, the temurin entrypoint never calls",
         "# it, and this image starts java directly. Removing it keeps that binary's Go stdlib findings",
         "# (Security CI library gate) out of the image filesystem.",
         "RUN rm -f /usr/bin/pebble",
         ""]
PEBBLE = "/usr/bin/pebble"
GO_MAGIC = b"\xff Go buildinf:"

REPORT = []
RECORD = {"tool": "rb1-release-rebaseline", "oldPin": OLD_PIN, "newPin": NEW_PIN, "source": SOURCE, "images": {}}
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
        path = os.path.join(OUT, f"RB1-rebaseline-{STAMP}{suffix}")
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"saved: {path}")


def finish():
    failed = [n for n, ok in GATES if not ok]
    RECORD.update(stamp=STAMP, gates={n: ok for n, ok in GATES}, result="PASS" if not failed else "FAIL")
    say("")
    say(f"gates passed {len(GATES) - len(failed)}/{len(GATES)}" + (f"; failed: {', '.join(failed)}" if failed else ""))
    say("RB1 REBASELINE COMPLETE" if not failed else "RB1 REBASELINE NOT COMPLETE")
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


def git(repo, *args, timeout=120):
    rc, out, err = run(["git", "--no-optional-locks", "-C", repo] + list(args), timeout=timeout)
    return rc, out, err


def short(image_id):
    return (image_id or "-").replace("sha256:", "")[:12]


def image_info(ref):
    rc, out, _ = run(["docker", "image", "inspect", ref])
    if rc != 0 or not out.strip().startswith("["):
        return None
    data = json.loads(out)
    return data[0] if data else None


def identity(info, pinned_ref, accepted, revision):
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
    rc, out, _ = run(["docker", "ps", "-aq", "--filter", "label=parkio.release.probe=rb1"])
    return [c for c in out.split() if re.fullmatch(r"[0-9a-f]{12,64}", c)]


def live_state():
    rc, head, _ = git(LIVE, "rev-parse", "HEAD")
    rc2, status, _ = git(LIVE, "status", "--porcelain")
    return {"head": head.strip(), "statusSha256": hashlib.sha256(status.encode()).hexdigest(),
            "changes": len([ln for ln in status.splitlines() if ln.strip()])}


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


def go_version(binary):
    i = binary.find(GO_MAGIC)
    while i != -1:
        header = binary[i:i + 32]
        if len(header) == 32 and header[14] in (4, 8) and header[15] & 0x2:
            vers, _ = uvarint_string(binary[i + 32:])
            if vers and vers.startswith(b"go"):
                return vers.decode("ascii", "replace")
        i = binary.find(GO_MAGIC, i + 1)
    return None


def copy_file(cid, path, limit):
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
            if not member.isfile() or member.size > limit:
                return "other", "not a regular file or too large"
            return "file", tar.extractfile(member).read()
    except tarfile.TarError as exc:
        return "error", f"tar: {exc}"[:200]


def examine(role, ref):
    """{'pebble': 'absent'|'present'|..., 'go': ..., 'os': ...} from a never-started container."""
    name = f"rb1-probe-{role}-{STAMP.lower()}"
    rc, cid, err = run(["docker", "create", "--pull", "never", "--network", "none", "--name", name,
                        "--label", "parkio.release.probe=rb1", "--entrypoint", "/rb1-probe-noop", ref])
    cid = cid.strip()
    if rc != 0 or not cid:
        return {"error": f"docker create failed: {first_error(err)}"}
    result = {}
    try:
        kind, data = copy_file(cid, "/etc/os-release", 65536)
        if kind == "file":
            fields = dict(ln.split("=", 1) for ln in data.decode("utf-8", "replace").splitlines() if "=" in ln)
            result["os"] = f"{fields.get('ID', '?')} {fields.get('VERSION_ID', '?').strip(chr(34))}"
        kind, data = copy_file(cid, PEBBLE, 128 * 1024 * 1024)
        if kind == "file":
            result.update(pebble="present", size=len(data), sha256=hashlib.sha256(data).hexdigest(), go=go_version(data))
        else:
            result["pebble"] = kind if kind != "error" else f"error: {data}"
    finally:
        rc_rm, _, err_rm = run(["docker", "rm", "-v", cid])
        if rc_rm != 0:
            result["error"] = f"docker rm failed: {first_error(err_rm)}"
    return result


def diff_lines(path):
    rc, out, err = git(REL, "diff", "-U0", OLD_PIN, NEW_PIN, "--", path)
    if rc != 0:
        stop(f"git diff {path} failed: {first_error(err)}")
    plus, minus, in_hunk = [], [], False
    for line in out.splitlines():
        if line.startswith("@@"):
            in_hunk = True
        elif in_hunk and line.startswith("+"):
            plus.append(line[1:])
        elif in_hunk and line.startswith("-"):
            minus.append(line[1:])
    return plus, minus


def section_b():
    say("")
    say(f"B. The new PIN {NEW_PIN[:12]} against {OLD_PIN[:12]}, then the release checkout")
    rc, head, _ = git(REL, "rev-parse", "HEAD")
    head = head.strip()
    if head not in (OLD_PIN, NEW_PIN):
        stop(f"{REL} is at {head[:12] or '?'}, neither the old nor the new PIN")
    rc, have, _ = git(REL, "cat-file", "-e", f"{NEW_PIN}^{{commit}}")
    if rc != 0:
        rc, _, err = git(REL, "fetch", "--no-tags", "--quiet", "origin", NEW_PIN, timeout=600)
        if rc != 0:
            rc, _, err = git(REL, "fetch", "--no-tags", "--quiet", "origin", "api", timeout=600)
        rc2, _, _ = git(REL, "cat-file", "-e", f"{NEW_PIN}^{{commit}}")
        if rc2 != 0:
            stop(f"cannot fetch {NEW_PIN[:12]} into {REL}: {first_error(err)}")
        say(f"  fetched {NEW_PIN[:12]} from origin")
    rc, ancestor, _ = git(REL, "merge-base", "--is-ancestor", OLD_PIN, NEW_PIN)
    gate("the new PIN descends from the old PIN", rc == 0)
    rc, names, err = git(REL, "diff", "--name-only", OLD_PIN, NEW_PIN)
    changed = sorted(n for n in names.splitlines() if n.strip())
    expected = sorted(MARKETING + DOCKERFILES + list(PIN_FILES))
    extra = sorted(set(changed) - set(expected))
    missing = sorted(set(expected) - set(changed))
    gate("changed paths are exactly marketing (13), the ten Dockerfiles and the two pin files",
         not extra and not missing, f"{len(changed)} paths" + (f"; unexpected: {', '.join(extra[:8])}" if extra else "")
         + (f"; unchanged: {', '.join(missing[:8])}" if missing else ""))
    if extra:
        stop("the new PIN changes paths outside the approved scope; the checkout is not moved")
    bad = []
    for path in DOCKERFILES:
        plus, minus = diff_lines(path)
        if plus != BLOCK or minus:
            bad.append(path.split("/")[1])
    gate("every Dockerfile adds exactly the pebble-removal block and removes nothing", not bad,
         f"differs: {', '.join(bad)}" if bad else "10/10")
    pin_bad, refs = [], {}
    for path, services in PIN_FILES.items():
        plus, minus = diff_lines(path)
        for line in plus + minus:
            stripped = line.strip()
            if stripped and not stripped.startswith("#") and not re.fullmatch(r"image: \S+@sha256:[0-9a-f]{64}", stripped):
                pin_bad.append(f"{path.rsplit('/', 1)[-1]}: {stripped[:60]}")
        rc, text, _ = git(REL, "show", f"{NEW_PIN}:{path}")
        for svc in services:
            m = re.search(rf"(?m)^  {re.escape(svc)}:\n    image: (\S+)$", text)
            refs[svc] = m.group(1) if m else None
    gate("the pin files change only comments and image lines", not pin_bad, "; ".join(pin_bad[:4]))
    wrong = [s for s, (ref, _, _) in RELEASE.items() if refs.get(s) != ref]
    gate("the pin files name exactly the four replacement digests", not wrong,
         f"mismatch: {', '.join(wrong)}" if wrong else "gateway, media, parking, auth")
    if extra or bad or pin_bad or wrong:
        stop("the new PIN does not match the approved remediation; the checkout is not moved")
    if head != NEW_PIN:
        rc, status, _ = git(REL, "status", "--porcelain")
        if status.strip():
            stop(f"{REL} is not clean; the checkout is not moved")
        rc, _, err = git(REL, "checkout", "--quiet", "--detach", NEW_PIN)
        if rc != 0:
            stop(f"git checkout failed: {first_error(err)}")
        say(f"  {REL} moved {OLD_PIN[:12]} -> {NEW_PIN[:12]}")
    else:
        say(f"  {REL} already at the new PIN")
    rc, head, _ = git(REL, "rev-parse", "HEAD")
    rc2, status, _ = git(REL, "status", "--porcelain")
    gate("release checkout at the new PIN and clean", head.strip() == NEW_PIN and not status.strip())
    runtime_ok = []
    for path in DOCKERFILES:
        text = open(os.path.join(REL, path), encoding="utf-8").read()
        froms = re.findall(r"(?m)^FROM\s+(\S+)", text)
        runtime = text.split("AS runtime", 1)[1] if "AS runtime" in text else ""
        ok = "\nRUN rm -f /usr/bin/pebble\n" in runtime
        if path.split("/")[1] in BUILT:
            ok = ok and froms == [JDK, JRE]
        runtime_ok.append(ok)
    gate("every runtime stage at the new PIN removes /usr/bin/pebble (D7 builds FROM the same two bases)", all(runtime_ok),
         f"{sum(runtime_ok)}/10")
    RECORD["changedPaths"] = changed


def main():
    say(f"RB1 release re-baseline | stamp {STAMP} | {REL} {OLD_PIN[:12]} -> {NEW_PIN[:12]} | project {PROJECT}")
    if "__" in NEW_PIN or any("__" in v[0] or "__" in v[1] for v in RELEASE.values()):
        stop("this copy has unfilled constants")
    say("")
    say("A. Before any change")
    rc, status, _ = git(REL, "status", "--porcelain")
    if rc != 0:
        stop(f"{REL} is not a git checkout")
    if status.strip():
        stop(f"{REL} is not clean")
    live_before = live_state()
    before_tags, before_ctr = tags(), containers()
    rollback = sorted(t for t in before_tags if t.endswith(ROLLBACK_SUFFIX))
    if probes_left():
        stop("containers labelled parkio.release.probe=rb1 exist before the check")
    say(f"  release checkout clean; live checkout {live_before['head'][:12]} with {live_before['changes']} changes (kept as is)")
    say(f"  {len(before_tags)} tags ({len(rollback)} rollback tags), {len(before_ctr)} project containers")
    section_b()
    say("")
    say("C. The four replacement images")
    for svc, (ref, accepted, revision) in RELEASE.items():
        info = image_info(ref)
        action = "present"
        if info is None:
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
            gate(f"{svc} replacement image", False, "not available")
            continue
        ok, note = identity(info, ref, accepted, revision)
        gate(f"{svc} replacement image identity", ok, f"{action}; {ref.rsplit('@', 1)[1][:19]}; {note}")
        found = examine(svc, ref)
        RECORD["images"][svc] = dict(found, ref=ref, imageId=info.get("Id"), action=action)
        gate(f"{svc} final filesystem without /usr/bin/pebble", found.get("pebble") == "absent" and "error" not in found,
             f"{found.get('os', '?')}; /usr/bin/pebble {found.get('pebble')}" + (f"; {found['error']}" if "error" in found else ""))
    say("")
    say("D. Still carrying pebble by design (reported, not cleaned): the D7 bases")
    for role, ref in (("d7-runtime-base", JRE), ("d7-build-base", JDK)):
        info = image_info(ref)
        if info is None:
            gate(f"{ref} present (D7 does not pull it)", False, "absent")
            continue
        found = examine(role, ref)
        RECORD["images"][role] = dict(found, ref=ref, imageId=info.get("Id"))
        state = (f"/usr/bin/pebble {found.get('pebble')}" + (f" ({found.get('go')}, sha256 {found.get('sha256', '')[:12]})"
                                                             if found.get("pebble") == "present" else ""))
        gate(f"{ref} examined", "error" not in found and found.get("pebble") in ("present", "absent"),
             f"image {short(info.get('Id'))}; {found.get('os', '?')}; {state}"
             + ("; base not clean - expected; D7's runtime stage removes it" if found.get("pebble") == "present" else ""))
    say("  every runtime image also keeps the base layer's bytes under a whiteout (layer-level; verified by S2 on the "
        "published candidate artifacts)")
    say("")
    say("E. Nothing else changed")
    after_tags, after_ctr = tags(), containers()
    moved = sorted(t for t, i in before_tags.items() if after_tags.get(t) != i)
    added = sorted(t for t in after_tags if t not in before_tags)
    gate("no image tag added, moved or removed (rollback tags included)", not moved and not added,
         f"rollback tags {sum(1 for t in rollback if after_tags.get(t) == before_tags[t])}/{len(rollback)} unchanged"
         + (f"; added {', '.join(added)}" if added else "") + (f"; moved/removed {', '.join(moved)}" if moved else ""))
    gate("every project container unchanged (image, state, start time)", after_ctr == before_ctr, f"{len(after_ctr)} containers")
    live_after = live_state()
    gate("live checkout /opt/parkio unchanged (HEAD and status)", live_after == live_before,
         f"{live_after['head'][:12]}, {live_after['changes']} changes")
    left = probes_left()
    gate("no probe container left", not left, f"{len(left)} left" if left else "")
    RECORD.update(rollbackTags=len(rollback), live=live_after)
    finish()


if __name__ == "__main__":
    main()
