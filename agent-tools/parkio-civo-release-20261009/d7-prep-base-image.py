#!/usr/bin/env python3
"""D7 preparation - pull the missing build base image and record the D7 base images (owner approval 2026-10-10).

Run as civo on the host (Python 3.8+):
  python3 -I /tmp/d7-prep-base-image.py

The six host-built services (Dockerfiles at 22f96990) build FROM eclipse-temurin:21-jdk and run FROM
eclipse-temurin:21-jre. P2 found eclipse-temurin:21-jre absent. This step pulls it once, before the window, and records
the image id, repository digest, platform and creation time of both base images, so D7 builds from known inputs.

Changes: one `docker pull eclipse-temurin:21-jre` (adds that tag; it is no service's and no rollback tag). Nothing else:
no build, no other pull, no container start or stop. It checks that every tag that existed before still points at the
same image (service images and the eleven rollback-pre-22f96990 tags included) and that every project container still
runs the same image. Report and JSON record go to ~/parkio-release-20261009 (mode 600). It prints no secret.
"""
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone

PIN = "22f9699038cdae15ffff5dd7433c4a64aed0a951"
REL = os.environ.get("D7P_RELEASE", "/opt/parkio-release")
PROJECT = os.environ.get("D7P_PROJECT", "parkio")
OUT = os.environ.get("D7P_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009"))
PULL = os.environ.get("D7P_PULL", "eclipse-temurin:21-jre")
OTHER = os.environ.get("D7P_OTHER", "eclipse-temurin:21-jdk")
ROLLBACK_SUFFIX = ":" + os.environ.get("D7P_ROLLBACK_TAG", "rollback-pre-22f96990")
BUILT = ["user-service", "gamification-service", "notification-service", "moderation-service",
         "ai-validation-service", "analytics-service"]

REPORT = []
RECORD = {"tool": "d7-prep-base-image", "release": PIN}
STAMP = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
URL = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://[^\s'\"]+")


def say(line=""):
    line = URL.sub("<url>", line)
    print(line, flush=True)
    REPORT.append(line)


def save(result):
    RECORD.update(stamp=STAMP, result=result)
    os.makedirs(OUT, mode=0o700, exist_ok=True)
    for suffix, text in ((".txt", "\n".join(REPORT) + "\n"), (".json", json.dumps(RECORD, indent=2, sort_keys=True) + "\n")):
        path = os.path.join(OUT, f"D7prep-base-image-{STAMP}{suffix}")
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"saved: {path}")


def stop(message):
    say(f"STOP: {message}")
    save("STOPPED")
    sys.exit(1)


def run(cmd, timeout=120):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError:
        return 127, "", ""
    except subprocess.TimeoutExpired:
        return 124, "", "timed out"
    return p.returncode, p.stdout, p.stderr


def first_error(err):
    return next((ln.strip() for ln in (err or "").splitlines() if ln.strip()), "")[:200]


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
    rc, out, _ = run(["docker", "ps", "-a", "--no-trunc", "--filter", f"label=com.docker.compose.project={PROJECT}",
                      "--format", "{{.ID}}"])
    result = {}
    for cid in out.split():
        rc, info, _ = run(["docker", "inspect", "--format",
                           '{{index .Config.Labels "com.docker.compose.service"}} {{.Image}} {{.State.Status}}', cid])
        parts = info.split()
        if len(parts) == 3:
            result[cid] = tuple(parts)
    return result


def describe(ref):
    rc, out, _ = run(["docker", "image", "inspect", ref])
    if rc != 0 or not out.strip().startswith("["):
        return None
    info = json.loads(out)[0]
    digest = next((d.split("@", 1)[1] for d in info.get("RepoDigests") or [] if "@" in d), None)
    return {"id": info.get("Id"), "repoDigest": digest, "platform": f"{info.get('Os')}/{info.get('Architecture')}",
            "created": (info.get("Created") or "")[:19]}


def main():
    say(f"D7 preparation: base images | stamp {STAMP} | release {REL} | project {PROJECT}")
    rc, head, _ = run(["git", "--no-optional-locks", "-C", REL, "rev-parse", "HEAD"])
    if head.strip() != PIN and not os.environ.get("D7P_PIN_ANY"):
        stop(f"{REL} is not at {PIN}")
    rc, status, _ = run(["git", "--no-optional-locks", "-C", REL, "status", "--porcelain"])
    if status.strip():
        stop(f"{REL} is not clean")
    for svc in BUILT:
        path = os.path.join(REL, "services", svc, "Dockerfile")
        try:
            froms = re.findall(r"(?m)^FROM\s+(\S+)", open(path, encoding="utf-8").read())
        except OSError:
            froms = []
        if froms != [OTHER, PULL]:
            stop(f"{svc}/Dockerfile FROM lines are not [{OTHER}, {PULL}]")
    say(f"  the six Dockerfiles build FROM {OTHER} and run FROM {PULL}")
    before_tags, before_ctr = tags(), containers()
    rollback = sorted(t for t in before_tags if t.endswith(ROLLBACK_SUFFIX))
    say(f"  before: {len(before_tags)} tags ({len(rollback)} rollback tags), {len(before_ctr)} project containers")
    existing = describe(PULL)
    if existing:
        say(f"  {PULL} already present ({existing['id'][7:19]}); not pulled again")
        action = "present"
    else:
        say(f"  pulling {PULL} ...")
        for attempt in (1, 2):
            rc, _, err = run(["docker", "pull", "--quiet", PULL], timeout=1800)
            if rc == 0:
                break
            say(f"  pull attempt {attempt} failed (exit {rc}) {first_error(err)}")
            if attempt == 1:
                time.sleep(10)
        action = "pulled"
    images = {}
    for ref in (PULL, OTHER):
        info = describe(ref)
        images[ref] = info
        if info is None:
            say(f"  {ref}: absent")
            continue
        say(f"  {ref}: image {info['id'][7:19]}, repo digest {info['repoDigest'] or 'none'}, {info['platform']}, "
            f"created {info['created']}")
    RECORD.update(baseImages=images, action=action)
    after_tags, after_ctr = tags(), containers()
    moved = sorted(t for t, i in before_tags.items() if after_tags.get(t) != i)
    added = sorted(t for t in after_tags if t not in before_tags)
    changed_ctr = sorted(before_ctr[c][0] for c in before_ctr if after_ctr.get(c) != before_ctr[c])
    gates = {
        f"{PULL} present, linux/amd64": bool(images.get(PULL)) and images[PULL]["platform"] == "linux/amd64",
        f"{OTHER} present, linux/amd64": bool(images.get(OTHER)) and images[OTHER]["platform"] == "linux/amd64",
        "no existing tag moved or removed (service and rollback tags included)": not moved,
        f"only {PULL} added": added in ([], [PULL]),
        "every project container unchanged": not changed_ctr and set(after_ctr) == set(before_ctr),
    }
    say(f"  tags added: {', '.join(added) or 'none'}; moved or removed: {', '.join(moved) or 'none'}")
    say(f"  rollback tags still on their images: {sum(1 for t in rollback if after_tags.get(t) == before_tags[t])}/{len(rollback)}")
    for name, ok in gates.items():
        say(f"  {'PASS' if ok else 'FAIL'} {name}")
    RECORD.update(gates=gates, tagsAdded=added, tagsMoved=moved, containersChanged=changed_ctr)
    failed = [n for n, ok in gates.items() if not ok]
    say("D7 PREP COMPLETE" if not failed else "D7 PREP INCOMPLETE: " + "; ".join(failed))
    save("PASS" if not failed else "FAIL")
    sys.exit(0 if not failed else 1)


if __name__ == "__main__":
    main()
