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
  4. the shared Docker build context filter (.dockerignore), and a scripts/
     path that an image Dockerfile or the Gradle build names              -> every image
  5. the web image: frontend/** (sources, lockfile, Dockerfile, nginx and
     CSP templates) and the release bake profiles                           -> web
  6. paths that no image contains or builds from (docs, other workflows,
     scripts, Compose and observability configuration, ...)                -> none
  7. anything else                                                          -> every image (fail closed)

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


def services(root: Path = ROOT) -> list[str]:
    """Every backend service with a Dockerfile, which is what the container scan builds."""
    return sorted(p.parent.name for p in (root / "services").glob("*/Dockerfile"))


def build_scripts(root: Path = ROOT) -> set[str]:
    """scripts/ paths an image build reads: named in an image Dockerfile or in the Gradle build."""
    sources = [*root.glob("services/*/Dockerfile"), root / "frontend" / "apps" / "web" / "Dockerfile",
               *root.glob("*.gradle.kts"), *root.glob("buildSrc/**/*.kt*"), *root.glob("gradle/*.toml")]
    found: set[str] = set()
    for source in sources:
        if source.is_file():
            found |= set(re.findall(r"(?<![A-Za-z0-9_./-])scripts/[A-Za-z0-9_./-]+", source.read_text(errors="replace")))
    return {name.rstrip("/.") for name in found}


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


def classify(path: str, known_services: list[str], scripts: set[str] = frozenset()) -> tuple[set[str], str]:
    """The images one changed path can affect, and why. `scripts` are the build-read scripts/ paths."""
    every_service = set(known_services)
    if matches(path, SCAN_INPUTS):
        return every_service | {WEB}, "scan input"
    if path.startswith("services/"):
        name = path.split("/")[1] if path.count("/") >= 1 else ""
        if name in every_service:
            return {name}, f"service {name}"
        return every_service | {WEB}, "unknown service path (fail closed)"
    if matches(path, BACKEND_SHARED):
        return every_service, "shared backend build input"
    if path == ".dockerignore":
        return every_service | {WEB}, "shared Docker build context filter"
    if any(path == name or path.startswith(name + "/") for name in scripts):
        return every_service | {WEB}, "script an image build reads"
    if matches(path, WEB_INPUTS):
        return {WEB}, "web image input"
    if matches(path, NO_IMAGE):
        return set(), "not part of any image"
    return every_service | {WEB}, "unclassified path (fail closed)"


def select(event: str, changed: list[str] | None, known_services: list[str],
           scripts: set[str] = frozenset()) -> tuple[list[str], list[str]]:
    """(images to scan, reasons). Anything but a pull request, or an unknown file list, scans all."""
    everything = sorted(set(known_services) | {WEB})
    if event != "pull_request":
        return everything, [f"event {event}: full scan"]
    if changed is None:
        return everything, ["changed files unknown: full scan (fail closed)"]
    chosen: set[str] = set()
    reasons = []
    for path in changed:
        images, why = classify(path, known_services, scripts)
        chosen |= images
        reasons.append(f"{path}: {why} -> {', '.join(sorted(images)) or 'none'}")
    return sorted(chosen), reasons


def pull_request_changes(root: Path = ROOT) -> list[str] | None:
    """Files the pull request's merge commit changes against its base (first parent)."""
    def git(*args: str) -> subprocess.CompletedProcess:
        return subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)
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
    scripts = build_scripts()
    if scripts:
        print(f"scripts read by image builds: {', '.join(sorted(scripts))}")
    images, reasons = select(args.event, changed, known, scripts)
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
