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
On top of these rules, the paths an image's build reads are collected from two sources:
  - the scanned images' Dockerfiles: COPY and ADD sources, RUN --mount sources, paths in RUN commands,
    and build-stage paths resolved against the stage's WORKDIR;
  - every backend Gradle build file: string paths, and names given to file()/files()/fileTree()/from().
A path counts in any form: a file or a directory, with or without a trailing slash, or with a glob (its
directory counts). Comments do not count. `COPY . .` in a build stage is the Gradle build's context,
which rules 2 and 3 already model.
A changed path under such a reference is handled in one of two ways (R2-1, owner decision 3):
  - if the reference lies outside the image trees of rules 1-5 (for example scripts/, docker/, web/,
    .github/, benchmarks/ or a top-level file), every image is selected. The selector has no rule for
    that path, so it fails closed instead of selecting nothing;
  - if the reference lies inside an image tree, the referencing images are added to that tree's rule.

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
import posixpath
import re
import shlex
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

GLOB = re.compile(r"[*?\[{]")
# Words of a RUN command or a Gradle string that may be paths.
WORD_SPLIT = re.compile(r"[\s;,|&()<>\"'`=]+")
GRADLE_STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
# In these Gradle calls a bare name is a path even without a slash: file("scripts").
GRADLE_FILE_CALL = re.compile(r'\b(?:file|files|fileTree|from)\(\s*"([^"/\s]+)"')


def services(root: Path = ROOT) -> list[str]:
    """Every backend service with a Dockerfile, which is what the container scan builds."""
    return sorted(p.parent.name for p in (root / "services").glob("*/Dockerfile"))


def top_level(root: Path = ROOT) -> set:
    """The repository's top-level names. A build-input path is a repository path only if it starts with one."""
    return {p.name for p in root.iterdir() if p.name != ".git"}


def _gradle_code(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"(^|\s)(//|#).*$", r"\1", text, flags=re.M)


def _dockerfile_instructions(text: str) -> list:
    """[(INSTRUCTION, arguments)], with comment lines removed and continuation lines joined."""
    instructions, current = [], ""
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.endswith("\\"):
            current += line[:-1] + " "
            continue
        word, _, rest = (current + line).strip().partition(" ")
        instructions.append((word.upper(), rest.strip()))
        current = ""
    if current.strip():
        word, _, rest = current.strip().partition(" ")
        instructions.append((word.upper(), rest.strip()))
    return instructions


def _repo_path(token: str, top: set, base: str = "", workdir: str = "") -> Optional[str]:
    """The repository path a build-input token names, "" for the whole build context, or None."""
    token = token.strip().strip("\"'")
    token = re.sub(r"^\$\{[^}]*\}/|^\$[A-Za-z_][A-Za-z0-9_]*/", "", token)
    if not token or "://" in token or token.startswith("-") or token.startswith("<<"):
        return None
    glob = GLOB.search(token)
    if glob:
        token = token[:glob.start()]
        token = token.rsplit("/", 1)[0] if "/" in token else "."
        token = token or "/"
    if token.startswith("/"):
        stage_root = workdir.rstrip("/")
        if stage_root and (token == stage_root or token.startswith(stage_root + "/")):
            token = token[len(stage_root):].lstrip("/") or "."
        else:
            parts = [part for part in token.split("/") if part]
            for index, part in enumerate(parts):
                if part in top:
                    return "/".join(parts[index:])
            return None
    if base and token.startswith("../"):
        token = posixpath.join(base, token)
    token = posixpath.normpath(token)
    if token == ".":
        return ""
    if token.startswith(".."):
        return None
    return token if token.split("/")[0] in top else None


def _copy_sources(args: str) -> tuple[dict, list]:
    """(flags, sources) of a COPY or ADD instruction, in shell or JSON form."""
    words = args.split()
    flags = {}
    while words and words[0].startswith("--"):
        name, _, value = words.pop(0)[2:].partition("=")
        flags[name.lower()] = value
    rest = " ".join(words)
    if rest.startswith("["):
        try:
            items = [str(item) for item in json.loads(rest)]
        except ValueError:
            items = rest.strip("[]").replace('"', " ").replace(",", " ").split()
    else:
        try:
            items = shlex.split(rest)
        except ValueError:
            items = rest.split()
    return flags, items[:-1]


def _dockerfile_refs(text: str, top: set, top_files: set) -> set:
    """Repository paths a Dockerfile's build reads; "" stands for the whole build context."""
    instructions = _dockerfile_instructions(text)
    final = max((index for index, (word, _) in enumerate(instructions) if word == "FROM"), default=0)
    refs: set = set()
    workdir, stage, stage_workdirs = "", "", {}
    for index, (word, args) in enumerate(instructions):
        if word == "FROM":
            named = re.search(r"\s[Aa][Ss]\s+(\S+)\s*$", args)
            stage, workdir = (named.group(1).lower() if named else ""), ""
        elif word == "WORKDIR":
            path = args.strip().strip("\"'")
            workdir = path if path.startswith("/") else posixpath.join(workdir or "/", path)
            if stage:
                stage_workdirs[stage] = workdir
        elif word in ("COPY", "ADD"):
            flags, sources = _copy_sources(args)
            source_stage = flags.get("from")
            for source in sources:
                if source_stage is None:
                    path = _repo_path(source, top)
                    if path == "" and index < final:
                        continue  # COPY . . in a build stage: the Gradle build's context (rules 2 and 3)
                else:
                    path = _repo_path(source, top, workdir=stage_workdirs.get(source_stage.lower(), ""))
                if path is not None:
                    refs.add(path)
        elif word == "RUN":
            for mount in re.findall(r"--mount=(\S+)", args):
                options = dict(item.partition("=")[::2] for item in mount.split(","))
                source = options.get("source") or options.get("src")
                if options.get("type", "bind") == "bind" and source and "from" not in options:
                    path = _repo_path(source, top)
                    if path is not None:
                        refs.add(path)
            for token in WORD_SPLIT.split(re.sub(r"--mount=\S+", " ", args)):
                if "/" in token or token in top_files:
                    path = _repo_path(token, top, workdir=workdir)
                    if path:
                        refs.add(path)
    return refs


def _gradle_refs(text: str, top: set, base: str) -> set:
    """Repository paths a Gradle build file names in its strings."""
    code = _gradle_code(text)
    refs: set = set()
    for literal in GRADLE_STRING.findall(code):
        for token in literal.split():
            if "/" in token:
                path = _repo_path(token, top, base=base)
                if path:
                    refs.add(path)
    for name in GRADLE_FILE_CALL.findall(code):
        if name in top:
            refs.add(name)
    return refs


def _in_image_tree(path: str, every_service: set, root: Path) -> bool:
    """Whether rules 1-5 already map changes at and under `path` to images."""
    if not path:
        return False
    probes = [path, f"{path}/x"] if (root / path).is_dir() else [path]
    for probe in probes:
        images, why = _classify_rules(probe, every_service)
        if not images or "fail closed" in why:
            return False
    return True


def image_input_refs(root: Path = ROOT, known_services: Optional[list] = None) -> list:
    """[(path, images whose build reads it, whether the path lies in an image tree)], sorted by path.

    "" stands for the whole build context."""
    names = services(root) if known_services is None else list(known_services)
    every_service = set(names)
    top = top_level(root)
    top_files = {name for name in top if (root / name).is_file()}
    found: dict = {}

    def add(paths, images):
        for path in paths:
            found.setdefault(path, set()).update(images)

    for name in names:
        dockerfile = root / "services" / name / "Dockerfile"
        if dockerfile.is_file():
            add(_dockerfile_refs(dockerfile.read_text(errors="replace"), top, top_files), {name})
        for build_file in sorted((root / "services" / name).glob("**/*.gradle.kts")):
            add(_gradle_refs(build_file.read_text(errors="replace"), top,
                             build_file.parent.relative_to(root).as_posix()), {name})
    web_dockerfile = root / "frontend" / "apps" / "web" / "Dockerfile"
    if web_dockerfile.is_file():
        add(_dockerfile_refs(web_dockerfile.read_text(errors="replace"), top, top_files), {WEB})
    shared = [*root.glob("*.gradle.kts"), *root.glob("gradle/*.toml"), *root.glob("buildSrc/**/*.kt*"),
              *root.glob("platform/**/*.gradle.kts"), *root.glob("tools/**/*.gradle.kts")]
    for build_file in sorted(shared):
        add(_gradle_refs(build_file.read_text(errors="replace"), top,
                         build_file.parent.relative_to(root).as_posix()), every_service)
    return [(path, owners, _in_image_tree(path, every_service, root)) for path, owners in sorted(found.items())]


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
    every_service = set(known_services)
    images, why = _classify_rules(path, every_service)
    named, outside = set(), []
    for prefix, owners, in_tree in refs:
        if prefix == "" or path == prefix or path.startswith(prefix + "/"):
            if in_tree:
                named |= owners
            else:
                outside.append(prefix or ".")
    if outside:
        return every_service | {WEB}, (f"{why}; read by an image build outside the image trees "
                                       f"({', '.join(sorted(outside))}): every image (fail closed)")
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
    for prefix, owners, in_tree in refs:
        scope = "in an image tree" if in_tree else "outside the image trees: every image"
        print(f"read by an image build: {prefix or '.'} -> {', '.join(sorted(owners))} ({scope})")
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
