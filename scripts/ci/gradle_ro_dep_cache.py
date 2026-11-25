#!/usr/bin/env python3
"""Gradle's read-only dependency cache for the service image builds (#638).

Every service Dockerfile bind-mounts the build context `gradle-ro-dep-cache` read-only into its Gradle
step and, when the mount holds a modules-2 directory, points GRADLE_RO_DEP_CACHE at it. Gradle then
takes each dependency it finds there from it, downloads the others into the build's own Gradle home
as before, and never writes to it. A dependency that no repository has still fails the build ("Could
not find"). A bind mount is in no layer. A build that does not pass the context (local, release,
deploy) gets the Dockerfile's empty stage and downloads everything, as before.

CI fills the context from Backend CI's Gradle dependency cache in the image builds that only test or
scan: Security CI's container scans and the Compose jobs. The images that get published (the candidate
builds and release.yml) never read it: any job on api can save a cache entry, and nothing verifies the
jars, so they resolve every dependency themselves (#638 review). The job restores the entry with
actions/cache/restore (only Backend CI saves it, on api) and then runs:

  stage DEST
      Hard-links ${GRADLE_USER_HOME:-~/.gradle}/caches/modules-2 to DEST/modules-2, without the lock
      files and gc.properties that a read-only cache must not carry. After a cache miss there is no
      modules-2: DEST stays empty and the image builds download every dependency, as before. DEST must
      be outside the repository, so that no `COPY . .` can copy it.

  compose-override MODEL.json DEST --out OVERRIDE.json
      MODEL.json is `docker compose config --format json`. OVERRIDE.json makes DEST the
      gradle-ro-dep-cache build context (`additional_contexts`) of every service that the model builds
      from a Dockerfile with that stage. Pass it to `docker compose ... build` with -f.

  probe --dockerfile PATH --out REPORT.json [--context DIR] [--revision SHA]
      Builds one service Dockerfile twice on the job's own daemon, both times without the layer cache:
      first without the read-only cache, then with one staged from the first build's own Gradle home.
      The base images are pulled first. For each build it records the time, the bytes the runner
      received and the artifacts Gradle downloaded into the build's Gradle home. It requires that:
        * the first build downloaded artifacts and the second downloaded none;
        * both images carry the same application: the same final-stage history and image
          configuration, and the same app.jar entries. Entries compare by CRC-32, nested jars by their
          own entries, and the embedded SBOM without the two fields that differ between any two builds
          (serialNumber, metadata.timestamp) and without list order (#284).
      The runtime stage's apt layer is rebuilt in both builds and is not compared: it does not depend
      on the cache.

Exit codes: 0 = pass, 1 = failed check, 2 = usage or unreadable input.
"""
from __future__ import annotations

import argparse
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path
from typing import Callable, Optional

ROOT = Path(__file__).resolve().parents[2]
PREFIX = "gradle-ro-dep-cache"
STAGE = "gradle-ro-dep-cache"
STAGE_LINE = re.compile(r"^\s*FROM\s+scratch\s+AS\s+" + re.escape(STAGE) + r"\s*$", re.I | re.M)
BUILD_STAGE = "build"
BUILD_GRADLE_HOME = "/root/.gradle"
APP_JAR = "/app/app.jar"
SBOM_PREFIX = "META-INF/sbom/"
# The two SBOM fields that differ between any two builds of the same source (#284).
SBOM_PER_BUILD = ("serialNumber", "metadata.timestamp")
# Image configuration fields that describe the application; Image and the creation time do not.
CONFIG_FIELDS = ("Cmd", "Entrypoint", "Env", "ExposedPorts", "Healthcheck", "Labels", "OnBuild", "StopSignal",
                 "User", "Volumes", "WorkingDir")

Runner = Callable[[list], subprocess.CompletedProcess]


def run_docker(args: list) -> subprocess.CompletedProcess:
    return subprocess.run(["docker", *args], capture_output=True, text=True, check=False)


def run_docker_logged(args: list) -> subprocess.CompletedProcess:
    """Like run_docker, with docker's output in the job log (the builds take minutes)."""
    done = subprocess.run(["docker", *args], check=False)
    return subprocess.CompletedProcess(done.args, done.returncode, "", "")


class ProbeError(Exception):
    pass


# --- stage ---------------------------------------------------------------------------------------

def _not_in_a_read_only_cache(_directory: str, names: list) -> list:
    return [name for name in names if name.endswith(".lock") or name == "gc.properties"]


def _link_or_copy(source: str, target: str) -> None:
    try:
        os.link(source, target)
    except OSError:
        shutil.copy2(source, target)


def artifacts(modules: Path) -> tuple:
    """(files, bytes) under modules-2/files-2.1: the artifacts a Gradle home holds."""
    count = size = 0
    for directory, _dirs, files in os.walk(modules / "files-2.1"):
        for name in files:
            count += 1
            size += os.path.getsize(os.path.join(directory, name))
    return count, size


def stage(source: Path, dest: Path, repository: Path = ROOT) -> tuple:
    """Copies the modules-2 directory `source` to DEST/modules-2 as a read-only cache.

    Returns (artifacts, bytes) staged, (0, 0) when `source` does not exist."""
    dest = Path(os.path.abspath(dest))
    repository = Path(os.path.abspath(repository))
    if dest == repository or repository in dest.parents:
        raise ValueError(f"{dest} is inside the repository; a `COPY . .` would copy it into the build stage")
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    if not source.is_dir():
        return 0, 0
    shutil.copytree(source, dest / "modules-2", ignore=_not_in_a_read_only_cache, copy_function=_link_or_copy)
    return artifacts(dest / "modules-2")


# --- compose-override ------------------------------------------------------------------------------

def declares_stage(dockerfile: Path) -> bool:
    try:
        return bool(STAGE_LINE.search(dockerfile.read_text(encoding="utf-8")))
    except OSError:
        return False


def compose_override(model: dict, dest: str) -> dict:
    """A Compose file that gives every service the model builds with the stage `dest` as that context."""
    services = {}
    for name, service in sorted((model.get("services") or {}).items()):
        build = service.get("build") if isinstance(service, dict) else None
        if not isinstance(build, dict):
            continue
        context = Path(build.get("context") or ".")
        dockerfile = Path(build.get("dockerfile") or "Dockerfile")
        if declares_stage(dockerfile if dockerfile.is_absolute() else context / dockerfile):
            services[name] = {"build": {"additional_contexts": {STAGE: dest}}}
    return {"services": services}


# --- probe: comparing two images -------------------------------------------------------------------

def _normalized(value):
    if isinstance(value, dict):
        return {key: _normalized(item) for key, item in value.items()}
    if isinstance(value, list):
        return sorted((_normalized(item) for item in value), key=lambda item: json.dumps(item, sort_keys=True))
    return value


def sbom_view(data: bytes) -> dict:
    """The SBOM without the fields that differ between any two builds, with lists in a fixed order."""
    bom = json.loads(data)
    bom.pop("serialNumber", None)
    if isinstance(bom.get("metadata"), dict):
        bom["metadata"].pop("timestamp", None)
    return _normalized(bom)


def _first_difference(a, b, path: str = "") -> str:
    if isinstance(a, dict) and isinstance(b, dict):
        for key in sorted(set(a) | set(b)):
            if a.get(key, "<absent>") != b.get(key, "<absent>"):
                return _first_difference(a.get(key, "<absent>"), b.get(key, "<absent>"), f"{path}.{key}")
    elif isinstance(a, list) and isinstance(b, list) and len(a) == len(b):
        for index, (left, right) in enumerate(zip(a, b)):
            if left != right:
                return _first_difference(left, right, f"{path}[{index}]")
    return path.lstrip(".") or "<root>"


def jar_entries(data: bytes, prefix: str = "") -> dict:
    """{entry: fingerprint}: CRC-32 for files, nested jars expanded as `outer!/inner`, SBOMs by content."""
    entries = {}
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        for info in jar.infolist():
            name = prefix + info.filename
            if info.is_dir():
                entries[name] = ("directory",)
            elif info.filename.startswith(SBOM_PREFIX) and info.filename.endswith(".json"):
                entries[name] = ("sbom", sbom_view(jar.read(info)))
            elif info.filename.endswith(".jar"):
                try:
                    nested = jar_entries(jar.read(info), name + "!/")
                except zipfile.BadZipFile:
                    entries[name] = ("crc", info.CRC)  # not a zip after all: compare its bytes
                else:
                    entries[name] = ("jar",)
                    entries.update(nested)
            else:
                entries[name] = ("crc", info.CRC)
    return entries


def compare_jars(without: bytes, with_cache: bytes) -> list:
    a, b = jar_entries(without), jar_entries(with_cache)
    problems = [f"{name}: only in the build without the cache" for name in sorted(set(a) - set(b))]
    problems += [f"{name}: only in the build with the cache" for name in sorted(set(b) - set(a))]
    for name in sorted(set(a) & set(b)):
        if a[name] == b[name]:
            continue
        if a[name][0] == "sbom" and b[name][0] == "sbom":
            problems.append(f"{name}: the SBOM differs beyond {', '.join(SBOM_PER_BUILD)} "
                            f"(first at {_first_difference(a[name][1], b[name][1])})")
        else:
            problems.append(f"{name}: different contents")
    return problems


def compare_images(without: dict, with_cache: dict, history_without: list, history_with: list) -> list:
    problems = []
    config_a, config_b = without.get("Config") or {}, with_cache.get("Config") or {}
    for field in CONFIG_FIELDS:
        if config_a.get(field) != config_b.get(field):
            problems.append(f"image configuration {field} differs: {config_a.get(field)!r} != {config_b.get(field)!r}")
    for field in ("Os", "Architecture"):
        if without.get(field) != with_cache.get(field):
            problems.append(f"image {field} differs: {without.get(field)!r} != {with_cache.get(field)!r}")
    if history_without != history_with:
        problems.append("the final stage's history differs")
    return problems


# --- probe: running the builds ---------------------------------------------------------------------

def base_images(dockerfile_text: str) -> list:
    """The images the Dockerfile's stages start from: not scratch, not an earlier stage."""
    images, stages = [], set()
    for line in dockerfile_text.splitlines():
        words = line.split()
        if len(words) < 2 or words[0].upper() != "FROM":
            continue
        words = [word for word in words[1:] if not word.startswith("--")]
        if not words:
            continue
        image = words[0]
        if len(words) >= 3 and words[1].upper() == "AS":
            stages.add(words[2].lower())
        if image.lower() != "scratch" and image.lower() not in stages and image not in images:
            images.append(image)
    return images


def received_bytes(proc_net_dev: str = "/proc/net/dev", proc_net_route: str = "/proc/net/route") -> int:
    """The bytes received so far by the interfaces of the runner's default route.

    Builds, pulls and downloads leave the runner through them; container bridges and lo only relay."""
    outward = {line.split()[0] for line in Path(proc_net_route).read_text(encoding="utf-8").splitlines()[1:]
               if len(line.split()) > 1 and line.split()[1] == "00000000"}
    if not outward:
        raise ProbeError(f"{proc_net_route} has no default route")
    total = 0
    for line in Path(proc_net_dev).read_text(encoding="utf-8").splitlines()[2:]:
        name, _, counters = line.partition(":")
        if counters and name.strip() in outward:
            total += int(counters.split()[0])
    return total


def _check(done: subprocess.CompletedProcess, what: str) -> subprocess.CompletedProcess:
    if done.returncode != 0:
        detail = (done.stderr or done.stdout or "").strip().splitlines()[-1:] or ["no output"]
        raise ProbeError(f"{what} failed (exit {done.returncode}): {detail[0]}")
    return done


def copy_out(run: Runner, image: str, path: str, dest: Path) -> bool:
    """Copies `path` out of `image` to `dest`; False when the image has no such path."""
    created = _check(run(["create", "--entrypoint", "sh", image]), f"docker create {image}")
    container = created.stdout.strip()
    try:
        return run(["cp", f"{container}:{path}", str(dest)]).returncode == 0
    finally:
        run(["rm", "-f", container])


def build(run: Runner, log_run: Runner, args: list, what: str,
          clock: Callable[[], float], counter: Callable[[], int]) -> dict:
    received, start = counter(), clock()
    _check(log_run(["build", "--progress=plain", *args]), what)
    return {"seconds": round(clock() - start, 1), "received_bytes": counter() - received}


def probe(dockerfile: str, context: str, revision: str, run: Runner = run_docker,
          log_run: Runner = run_docker_logged, clock: Callable[[], float] = time.monotonic,
          counter: Callable[[], int] = received_bytes, workdir: Optional[Path] = None) -> dict:
    text = Path(dockerfile).read_text(encoding="utf-8")  # docker reads -f relative to the working directory
    if not STAGE_LINE.search(text):
        raise ProbeError(f"{dockerfile} has no `FROM scratch AS {STAGE}` stage")
    if not re.search(rf"^\s*FROM\s+\S+\s+AS\s+{BUILD_STAGE}\s*$", text, re.I | re.M):
        raise ProbeError(f"{dockerfile} has no `{BUILD_STAGE}` stage")
    for image in base_images(text):
        _check(run(["pull", "-q", image]), f"docker pull {image}")
    common = ["-f", dockerfile, "--build-arg", f"IMAGE_REVISION={revision}"]
    tags = {"without": f"{PREFIX}-probe:without", "with": f"{PREFIX}-probe:with"}
    report = {"dockerfile": dockerfile, "revision": revision, "builds": {}, "differences": []}
    with tempfile.TemporaryDirectory(prefix=f"{PREFIX}-probe-", dir=workdir) as tmp:
        work = Path(tmp)
        ro = work / "ro"
        variants = (("without", []), ("with", ["--build-context", f"{STAGE}={ro}"]))
        for name, extra in variants:
            if name == "with":
                ro_files, ro_bytes = stage(work / "without-home" / "modules-2", ro, Path(context))
                report["builds"]["without"]["read_only_cache"] = {"artifacts": ro_files, "bytes": ro_bytes}
            result = build(run, log_run, ["--no-cache", *common, *extra, "-t", tags[name], context],
                           f"the build {name} the read-only cache", clock, counter)
            # The build stage is in the layer cache now, so this only tags it.
            build_tag = f"{tags[name]}-build"
            _check(log_run(["build", "--progress=plain", "--target", BUILD_STAGE, *common, *extra,
                            "-t", build_tag, context]), f"tagging the build stage {name} the cache")
            home = work / f"{name}-home"
            home.mkdir()
            copy_out(run, build_tag, f"{BUILD_GRADLE_HOME}/caches/modules-2", home)
            files, size = artifacts(home / "modules-2")
            result.update(downloaded_artifacts=files, downloaded_bytes=size)
            report["builds"][name] = result
        jars = {}
        for name in ("without", "with"):
            if not copy_out(run, tags[name], APP_JAR, work / f"{name}.jar"):
                raise ProbeError(f"the image built {name} the cache has no {APP_JAR}")
            jars[name] = (work / f"{name}.jar").read_bytes()
        inspected = {name: json.loads(_check(run(["image", "inspect", tags[name]]), "docker image inspect").stdout)[0]
                     for name in tags}
        history = {name: _check(run(["image", "history", "--no-trunc", "--format", "{{.CreatedBy}}", tags[name]]),
                                "docker image history").stdout.splitlines() for name in tags}
    report["differences"] = compare_jars(jars["without"], jars["with"]) + compare_images(
        inspected["without"], inspected["with"], history["without"], history["with"])
    without, with_cache = report["builds"]["without"], report["builds"]["with"]
    report["saved"] = {"seconds": round(without["seconds"] - with_cache["seconds"], 1),
                       "received_bytes": without["received_bytes"] - with_cache["received_bytes"]}
    problems = list(report["differences"])
    if without["downloaded_artifacts"] == 0:
        problems.insert(0, "the build without the cache downloaded no artifact, so it measured nothing")
    if with_cache["downloaded_artifacts"] != 0:
        problems.insert(0, f"the build with the cache still downloaded {with_cache['downloaded_artifacts']} artifact(s)")
    report["problems"] = problems
    report["result"] = "fail" if problems else "pass"
    return report


def summary(report: dict) -> str:
    without, with_cache = report["builds"]["without"], report["builds"]["with"]

    def side(build: dict) -> str:
        return (f"{build['seconds']} s, {build['received_bytes'] / 1e6:.1f} MB received, "
                f"{build['downloaded_artifacts']} artifacts ({build['downloaded_bytes'] / 1e6:.1f} MB) downloaded")

    return (f"{PREFIX} probe: without the cache {side(without)}; with it {side(with_cache)}; saved "
            f"{report['saved']['seconds']} s and {report['saved']['received_bytes'] / 1e6:.1f} MB; "
            f"same application: {'yes' if not report['differences'] else 'no'}")


# --- command line --------------------------------------------------------------------------------

def main(argv: list) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    staging = sub.add_parser("stage")
    staging.add_argument("dest")
    override = sub.add_parser("compose-override")
    override.add_argument("model")
    override.add_argument("dest")
    override.add_argument("--out", required=True)
    probing = sub.add_parser("probe")
    probing.add_argument("--dockerfile", required=True)
    probing.add_argument("--context", default=".")
    probing.add_argument("--revision", default="unknown")
    probing.add_argument("--out", required=True)
    args = parser.parse_args(argv)

    if args.command == "stage":
        home = Path(os.environ.get("GRADLE_USER_HOME") or Path.home() / ".gradle")
        source = home / "caches" / "modules-2"
        try:
            files, size = stage(source, Path(args.dest))
        except (OSError, ValueError) as error:
            print(f"{PREFIX}: FAIL: {error}", file=sys.stderr)
            return 2
        if files:
            print(f"{PREFIX}: {files} artifacts ({size / 1e6:.1f} MB) from {source} in {args.dest}")
        else:
            print(f"{PREFIX}: no artifacts in {source} (the cache was not restored); the image builds "
                  "download every dependency")
        return 0

    if args.command == "compose-override":
        try:
            model = json.loads(Path(args.model).read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            print(f"{PREFIX}: FAIL: cannot read {args.model}: {error}", file=sys.stderr)
            return 2
        override_file = compose_override(model, os.path.abspath(args.dest))
        Path(args.out).write_text(json.dumps(override_file, indent=2) + "\n", encoding="utf-8")
        print(f"{PREFIX}: {args.out} gives {len(override_file['services'])} service build(s) the {STAGE} context: "
              f"{' '.join(override_file['services'])}")
        return 0

    try:
        report = probe(args.dockerfile, args.context, args.revision)
    except (OSError, ValueError, zipfile.BadZipFile, ProbeError) as error:
        print(f"{PREFIX}: FAIL: {error}", file=sys.stderr)
        return 1
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(summary(report))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as step_summary:
            step_summary.write(f"### Read-only dependency cache probe ({args.dockerfile})\n\n{summary(report)}\n")
    for problem in report["problems"]:
        print(f"{PREFIX}: FAIL: {problem}")
    return 0 if report["result"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
