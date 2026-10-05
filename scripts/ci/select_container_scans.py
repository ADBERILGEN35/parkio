#!/usr/bin/env python3
"""Choose which images Security CI's container scan builds and scans (U08, owner decision 2026-10-05).

A pull request scans only the images its changed files can affect. Every other event (a push to api
or master, a workflow_dispatch, which includes master's scheduled dispatch, or a schedule) scans
every image.

For a pull request each changed path is classified by the first matching rule:
  1. scan inputs (this script, security-ci.yml, .trivyignore.yaml)          -> every image
  2. services/<service>/**                                                  -> that service
  3. shared backend build inputs: root Gradle files, gradlew, gradle/**,
     buildSrc/**, platform/**, tools/** (all in the Gradle build)           -> every service
  4. the shared Docker build context filter (.dockerignore)                 -> every image
  5. the web image: frontend/** (sources, lockfile, Dockerfile, nginx and
     CSP templates) and the release bake profiles                           -> web
  6. paths that no image contains or builds from (docs, other workflows,
     scripts, Compose and observability configuration, ...)                -> none
  7. anything else                                                          -> every image (fail closed)
On top of these rules, a path under scripts/ or docker/ that an image's build names also selects
that image: a reference in a service's Dockerfile or in its own Gradle build file selects that
service, one in the web Dockerfile selects web, and one in the shared Gradle build (root files,
gradle/, buildSrc/, platform/, tools/) selects every service. References are found in any form: a file
or a directory, with or without a trailing slash, with a glob (its directory counts), relative to the
build context or under a build stage's path (/workspace/scripts/...). Comments do not count.

Selection fails closed: when the changed files cannot be listed (no merge commit, a git error) every
image is scanned.

Usage (CI):
    select_container_scans.py --event EVENT [--github-output FILE]
Prints the selection and its reasons. With --github-output it also writes
`images=<JSON list>` and `any=true|false` there.
"""
from __future__ import annotations

import argparse
import fnmatch
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Callable, Optional

ROOT = Path(__file__).resolve().parents[2]
WEB = "web"

SCAN_INPUTS = (
    "scripts/ci/select_container_scans.py",
    ".github/workflows/security-ci.yml",
    ".trivyignore.yaml",
)
BACKEND_SHARED = (
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
    "gradle/**", "buildSrc/**", "platform/**", "tools/**",
)
WEB_INPUTS = ("frontend/**", "docker/web-hosted-beta.release-bake.env", "docker/web-hosted-beta.municipal-on.bake.env")
NO_IMAGE = (
    "docs/**", "agent-tools/**", "artifacts/**", "backup-artifacts/**", "deploy-artifacts/**",
    "benchmarks/**", "dist/**", "test-results/**", "tmp/**", "infra/**", "web/**",
    ".github/**", "scripts/**", "docker/**",
    "*.md", "LICENSE", "CODEOWNERS", ".gitignore", ".gitattributes", ".gitleaks.toml",
    ".node-version", "untitled.pen", "parkio-hostinger-landing.zip",
)

# A scripts/ or docker/ reference. In a Dockerfile it may be a bare directory (`COPY scripts /opt/`); in
# Gradle code it needs a path, because "docker" there is also a command (ProcessBuilder("docker", "info")).
DOCKERFILE_REF = re.compile(r"(?<![A-Za-z0-9_.-])(scripts|docker)(?:/([^\s\"',;\]]*))?(?![A-Za-z0-9_.-])")
GRADLE_REF = re.compile(r"(?<![A-Za-z0-9_.-])(scripts|docker)/([^\s\"',;)\]]+)")
GLOB = re.compile(r"[*?\[{]")


def services(root: Path = ROOT) -> list[str]:
    """Every backend service with a Dockerfile, which is what the container scan builds."""
    return sorted(p.parent.name for p in (root / "services").glob("*/Dockerfile"))


def _dockerfile_code(text: str) -> str:
    return "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("#"))


def _gradle_code(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"(^|\s)(//|#).*$", r"\1", text, flags=re.M)


def _prefix(directory: str, rest: Optional[str]) -> str:
    """The repository path a reference covers: the path itself, or the directory before a glob."""
    path = f"{directory}/{rest}" if rest else directory
    glob = GLOB.search(path)
    if glob:
        path = path[:glob.start()]
        path = path.rsplit("/", 1)[0] if "/" in path else directory
    while path.endswith("/.") or path.endswith("/"):
        path = path[:-2] if path.endswith("/.") else path[:-1]
    return path or directory


def image_input_refs(root: Path = ROOT, known_services: Optional[list] = None) -> list:
    """[(path under scripts/ or docker/, images whose build names it)], sorted by path."""
    names = services(root) if known_services is None else list(known_services)
    every_service = set(names)
    sources = []
    for name in names:
        sources.append((root / "services" / name / "Dockerfile", {name}, DOCKERFILE_REF, _dockerfile_code))
        for build_file in sorted((root / "services" / name).glob("**/*.gradle.kts")):
            sources.append((build_file, {name}, GRADLE_REF, _gradle_code))
    sources.append((root / "frontend" / "apps" / "web" / "Dockerfile", {WEB}, DOCKERFILE_REF, _dockerfile_code))
    shared = [*root.glob("*.gradle.kts"), *root.glob("gradle/*.toml"), *root.glob("buildSrc/**/*.kt*"),
              *root.glob("platform/**/*.gradle.kts"), *root.glob("tools/**/*.gradle.kts")]
    for build_file in sorted(shared):
        sources.append((build_file, every_service, GRADLE_REF, _gradle_code))
    refs: dict = {}
    for path, images, pattern, code in sources:
        if path.is_file():
            for directory, rest in pattern.findall(code(path.read_text(errors="replace"))):
                refs.setdefault(_prefix(directory, rest), set()).update(images)
    return sorted(refs.items())


def matches(path: str, patterns: tuple[str, ...]) -> bool:
    for pattern in patterns:
        if pattern.endswith("/**"):
            if path.startswith(pattern[:-2]):
                return True
        elif "/" not in pattern:
            if "/" not in path and fnmatch.fnmatchcase(path, pattern):
                return True
        elif path == pattern:
            return True
    return False


def _classify_rules(path: str, every_service: set) -> tuple[set[str], str]:
    if matches(path, SCAN_INPUTS):
        return every_service | {WEB}, "scan input"
    if path.startswith("services/"):
        name = path.split("/")[1] if path.count("/") >= 1 else ""
        if name in every_service:
            return {name}, f"service {name}"
        return every_service | {WEB}, "unknown service path (fail closed)"
    if matches(path, BACKEND_SHARED):
        return set(every_service), "shared backend build input"
    if path == ".dockerignore":
        return every_service | {WEB}, "shared Docker build context filter"
    if matches(path, WEB_INPUTS):
        return {WEB}, "web image input"
    if matches(path, NO_IMAGE):
        return set(), "not part of any image"
    return every_service | {WEB}, "unclassified path (fail closed)"


def classify(path: str, known_services: list[str], refs=()) -> tuple[set[str], str]:
    """The images one changed path can affect, and why. `refs` come from image_input_refs()."""
    images, why = _classify_rules(path, set(known_services))
    named = set()
    for prefix, owners in refs:
        if path == prefix or path.startswith(prefix + "/"):
            named |= owners
    if named - images:
        return images | named, f"{why}; named by the build of {', '.join(sorted(named))}"
    return images, why


def select(event: str, changed: list[str] | None, known_services: list[str],
           refs=()) -> tuple[list[str], list[str]]:
    """(images to scan, reasons). Anything but a pull request, or an unknown file list, scans all."""
    everything = sorted(set(known_services) | {WEB})
    if event != "pull_request":
        return everything, [f"event {event}: full scan"]
    if changed is None:
        return everything, ["changed files unknown: full scan (fail closed)"]
    chosen: set[str] = set()
    reasons = []
    for path in changed:
        images, why = classify(path, known_services, refs)
        chosen |= images
        reasons.append(f"{path}: {why} -> {', '.join(sorted(images)) or 'none'}")
    return sorted(chosen), reasons


GitRunner = Callable[[list], subprocess.CompletedProcess]


def pull_request_changes(root: Path = ROOT, run: Optional[GitRunner] = None) -> list[str] | None:
    """Files the pull request's merge commit changes against its base (first parent); None if unknown."""
    def git(*args: str) -> subprocess.CompletedProcess:
        command = ["git", "-C", str(root), *args]
        return run(command) if run else subprocess.run(command, capture_output=True, text=True)
    parents = git("rev-list", "--parents", "-n", "1", "HEAD")
    if parents.returncode != 0 or len(parents.stdout.split()) != 3:
        return None  # not a two-parent merge commit: the base is unknown
    # --no-renames lists a moved file under its old path as well, so a move out of an image counts.
    diff = git("diff", "--name-only", "--no-renames", "HEAD^1", "HEAD")
    if diff.returncode != 0:
        return None
    return [line for line in diff.stdout.splitlines() if line]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--event", required=True)
    parser.add_argument("--github-output")
    args = parser.parse_args(argv)
    known = services()
    if not known:
        print("FAIL: no services/*/Dockerfile found", file=sys.stderr)
        return 1
    changed = pull_request_changes() if args.event == "pull_request" else None
    refs = image_input_refs(ROOT, known)
    for prefix, owners in refs:
        print(f"named by an image build: {prefix} -> {', '.join(sorted(owners))}")
    images, reasons = select(args.event, changed, known, refs)
    for reason in reasons:
        print(reason)
    print(f"selected ({len(images)}): {', '.join(images) or 'none'}")
    if args.github_output:
        with open(args.github_output, "a", encoding="utf-8") as out:
            out.write(f"images={json.dumps(images)}\n")
            out.write(f"any={'true' if images else 'false'}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
