#!/usr/bin/env python3
"""BuildKit/Gradle build-cache trial for two service images (owner decision 6, 2026-10-05).

The trial Dockerfiles (user-service, gamification-service) mount a BuildKit cache at the Gradle user
home for their bootJar step:

    RUN --mount=type=cache,id=parkio-gradle-home,target=/root/.gradle ./gradlew ... --no-daemon

For each service, on one daemon, this script builds four images and compares them:

  1. baseline-cold  the Dockerfile with the cache mount removed, without the layer cache: today's build;
  2. cache-cold     the trial Dockerfile, without the layer cache, its cache mounts emptied first;
  3. cache-warm     the trial Dockerfile after a one-line change to the service's application.yml,
                    with the layer cache and the Gradle cache that build 2 left;
  4. baseline-warm  the Dockerfile without the mount after the same change, with the layer cache.

Builds 1 and 2 are the equivalent cold builds, and builds 3 and 4 the equivalent warm ones: in each
pair, everything but the cache mount is the same. The base images are pulled before build 1, so no
build pays for a pull. The script requires:
  * the same source gives a bootJar with the same contents with and without the cache (1 = 2 and
    3 = 4): the same entries with the same CRC-32, and for each nested jar the same inner entries.
    Contents, not bytes, because two builds of the same source differ anyway, cache or not:
      - the bootJar is a reproducible archive, but the nested platform jar keeps its class files'
        timestamps;
      - the embedded SBOM (META-INF/sbom/bom.json) gets a new serialNumber and metadata.timestamp
        on every build, and lists its components and dependencies in a varying order. It is
        compared without those two fields, in a canonical order.
    Whether the bytes match is reported too;
  * the changed input is in the jar of both warm builds and not in the cold ones, and between the
    cold and the warm cached build only that entry differs: the cache never hides a change, and
    nothing else moves;
  * with --baseline-ref, the trial Dockerfile without its cache mount has the same instructions as
    that revision's Dockerfile, so the mount is the only change.
It reports the four build times and does not judge them: whether to expand the cache beyond two
services is decided on these numbers. Nothing is pushed or published, and the changed file is
restored. Cache mounts are only emptied with --prune-cache-mounts, which prunes every BuildKit cache
mount of the daemon, so it is meant for a disposable CI runner; without it, build 2 is not known to be
cold and the report says so.

Usage:
    build_cache_trial.py --service SVC [--service SVC ...] --out DIR [--context DIR]
                         [--revision SHA] [--baseline-ref REF] [--prune-cache-mounts]

Exit codes: 0 = pass, 1 = failed check, 2 = usage or setup error.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path
from typing import Callable, Optional

PREFIX = "build-cache-trial"
SBOM = "META-INF/sbom/bom.json"
CACHE_MOUNT = re.compile(r"--mount=type=cache,\S+")
RESOURCE = "src/main/resources/application.yml"
JAR_RESOURCE = "BOOT-INF/classes/application.yml"
APP_JAR = "/app/app.jar"
BUILDS = ("baseline-cold", "cache-cold", "cache-warm", "baseline-warm")

Runner = Callable[[list], subprocess.CompletedProcess]


def run_docker(args: list) -> subprocess.CompletedProcess:
    return subprocess.run(["docker", *args], capture_output=True, text=True, check=False)


def instructions(text: str) -> list:
    """The Dockerfile's instructions: comments dropped, continuations joined, whitespace collapsed."""
    result, current = [], ""
    for line in text.splitlines():
        stripped = line.strip()
        if not current and (not stripped or stripped.startswith("#")):
            continue
        if stripped.startswith("#"):
            continue  # a comment inside a continued instruction
        if stripped.endswith("\\"):
            current += stripped[:-1] + " "
            continue
        result.append(" ".join((current + stripped).split()))
        current = ""
    if current.strip():
        result.append(" ".join(current.split()))
    return result


def without_cache_mounts(text: str) -> str:
    """The Dockerfile with every BuildKit cache mount option removed, otherwise unchanged."""
    return CACHE_MOUNT.sub("", text)


def base_images(text: str) -> list:
    """The external images of the FROM lines (not earlier stages), in order."""
    stages, images = set(), []
    for instruction in instructions(text):
        words = instruction.split()
        if not words or words[0].upper() != "FROM":
            continue
        args = [w for w in words[1:] if not w.startswith("--")]
        if not args:
            continue
        if args[0].lower() not in stages and args[0] not in images and args[0].lower() != "scratch":
            images.append(args[0])
        if len(args) >= 3 and args[1].upper() == "AS":
            stages.add(args[2].lower())
    return images


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _canonical(value):
    if isinstance(value, dict):
        return {key: _canonical(item) for key, item in sorted(value.items())}
    if isinstance(value, list):
        return sorted((_canonical(item) for item in value), key=lambda item: json.dumps(item, sort_keys=True))
    return value


def sbom_digest(data: bytes) -> str:
    """A digest of a CycloneDX SBOM without its per-build fields, in a canonical order; of the bytes if not JSON."""
    try:
        bom = json.loads(data)
    except ValueError:
        return "bytes:" + sha256(data)
    if isinstance(bom, dict):
        bom.pop("serialNumber", None)
        if isinstance(bom.get("metadata"), dict):
            bom["metadata"].pop("timestamp", None)
    return "sbom:" + sha256(json.dumps(_canonical(bom), sort_keys=True).encode())


def jar_contents(jar: bytes) -> Optional[dict]:
    """{entry: CRC-32} of a jar, with each nested jar's entries as {"<jar>!<entry>": CRC-32}; None if unreadable.

    Timestamps are left out on purpose: the nested platform jar keeps its class files' times. The
    embedded SBOM is represented by sbom_digest, without its per-build fields.
    """
    try:
        contents = {}
        with zipfile.ZipFile(io.BytesIO(jar)) as archive:
            for info in archive.infolist():
                if info.is_dir():
                    continue
                if info.filename.endswith(".jar"):
                    with zipfile.ZipFile(io.BytesIO(archive.read(info))) as nested:
                        for inner in nested.infolist():
                            if not inner.is_dir():
                                contents[f"{info.filename}!{inner.filename}"] = inner.CRC
                elif info.filename == SBOM:
                    contents[info.filename] = sbom_digest(archive.read(info))
                else:
                    contents[info.filename] = info.CRC
        return contents
    except zipfile.BadZipFile:
        return None


def differing_entries(first: dict, second: dict) -> list:
    return sorted(name for name in set(first) | set(second) if first.get(name) != second.get(name))


def jar_has_marker(jar: bytes, marker: str) -> Optional[bool]:
    """Whether the jar's application.yml holds the marker; None when the jar or the entry is unreadable."""
    try:
        with zipfile.ZipFile(io.BytesIO(jar)) as archive:
            return marker in archive.read(JAR_RESOURCE).decode("utf-8", errors="replace")
    except (KeyError, zipfile.BadZipFile):
        return None


def evaluate(jars: dict, marker: str) -> list:
    """Problems with one service's four jars ({build: bytes}); empty when the cache changes nothing."""
    problems = []
    contents = {build: jar_contents(jar) for build, jar in jars.items()}
    unreadable = [build for build, content in contents.items() if content is None]
    if unreadable:
        return [f"the {build} jar is not readable" for build in unreadable]
    if contents["baseline-cold"] != contents["cache-cold"]:
        problems.append("the cold builds give jars with different contents for the same source: "
                        + ", ".join(differing_entries(contents["baseline-cold"], contents["cache-cold"])[:5]))
    if contents["cache-warm"] != contents["baseline-warm"]:
        problems.append("the warm builds give jars with different contents for the same source: "
                        + ", ".join(differing_entries(contents["cache-warm"], contents["baseline-warm"])[:5]))
    for build in ("cache-warm", "baseline-warm"):
        if jar_has_marker(jars[build], marker) is not True:
            problems.append(f"the changed input is not in the {build} jar: the cache hid the change")
    for build in ("baseline-cold", "cache-cold"):
        if jar_has_marker(jars[build], marker) is not False:
            problems.append(f"the {build} jar holds the change it was built before, or has no readable application.yml")
    changed = differing_entries(contents["cache-cold"], contents["cache-warm"])
    if changed != [JAR_RESOURCE]:
        problems.append(f"between the cold and the warm cached build, {changed or 'no entry'} changed, not only {JAR_RESOURCE}")
    return problems


def check_baseline(trial_text: str, baseline_text: Optional[str]) -> Optional[str]:
    """A problem when the trial Dockerfile differs from the baseline by more than its cache mounts."""
    if baseline_text is None:
        return None
    if instructions(without_cache_mounts(trial_text)) != instructions(baseline_text):
        return "the trial Dockerfile differs from the baseline by more than its cache mount"
    if not CACHE_MOUNT.search(trial_text):
        return "the trial Dockerfile has no cache mount"
    return None


class Trial:
    def __init__(self, context: Path, out: Path, revision: str, prune: bool, run: Runner = run_docker):
        self.context, self.out, self.revision, self.prune, self.run = context, out, revision, prune, run
        self.tags: list = []

    def build(self, service: str, label: str, dockerfile: Path, no_cache: bool) -> dict:
        tag = f"{PREFIX}-{service}:{label}"
        args = ["build", "--progress=plain", "-f", str(dockerfile), "--build-arg", f"IMAGE_REVISION={self.revision}",
                "-t", tag]
        if no_cache:
            args.append("--no-cache")
        started = time.monotonic()
        done = self.run([*args, str(self.context)])
        seconds = round(time.monotonic() - started, 1)
        (self.out / f"{service}-{label}.log").write_text((done.stdout or "") + (done.stderr or ""))
        self.tags.append(tag)
        return {"tag": tag, "exit": done.returncode, "seconds": seconds, "no_cache": no_cache}

    def jar(self, tag: str) -> Optional[bytes]:
        name = f"{PREFIX}-{int(time.time() * 1000)}"
        if self.run(["create", "--name", name, tag]).returncode != 0:
            return None
        try:
            with tempfile.TemporaryDirectory() as tmp:
                target = Path(tmp) / "app.jar"
                if self.run(["cp", f"{name}:{APP_JAR}", str(target)]).returncode != 0 or not target.is_file():
                    return None
                return target.read_bytes()
        finally:
            self.run(["rm", name])

    def service(self, service: str, baseline_text: Optional[str]) -> dict:
        report: dict = {"service": service, "builds": {}, "problems": []}
        trial_file = self.context / "services" / service / "Dockerfile"
        trial_text = trial_file.read_text()
        problem = check_baseline(trial_text, baseline_text)
        report["baseline_dockerfile_checked"] = baseline_text is not None
        if problem:
            report["problems"].append(problem)
            return report
        baseline_file = self.out / f"{service}.baseline.Dockerfile"
        baseline_file.write_text(without_cache_mounts(trial_text))
        for image in base_images(trial_text):
            if self.run(["pull", image]).returncode != 0:
                report["problems"].append(f"cannot pull the base image {image}")
                return report
        resource = self.context / "services" / service / RESOURCE
        original = resource.read_bytes()
        marker = f"# {PREFIX}: changed input {self.revision}"
        try:
            report["builds"]["baseline-cold"] = self.build(service, "baseline-cold", baseline_file, True)
            if self.prune:
                self.run(["builder", "prune", "--force", "--filter", "type=exec.cachemount"])
            report["cache_cold_is_cold"] = self.prune
            report["builds"]["cache-cold"] = self.build(service, "cache-cold", trial_file, True)
            resource.write_bytes(original + f"\n{marker}\n".encode())
            report["builds"]["cache-warm"] = self.build(service, "cache-warm", trial_file, False)
            report["builds"]["baseline-warm"] = self.build(service, "baseline-warm", baseline_file, False)
        finally:
            resource.write_bytes(original)
        failed = [label for label, build in report["builds"].items() if build["exit"] != 0]
        if failed:
            report["problems"].append("build failed: " + ", ".join(failed) + f" (see {service}-<build>.log)")
            return report
        jars = {}
        for label, build in report["builds"].items():
            jar = self.jar(build["tag"])
            if jar is None:
                report["problems"].append(f"cannot read {APP_JAR} from {build['tag']}")
                return report
            jars[label] = jar
            build["jar_sha256"] = sha256(jar)
            build["jar_entries"] = len(jar_contents(jar) or {})
            self.keep_sbom(service, label, jar)
        report["byte_identical_cold"] = jars["baseline-cold"] == jars["cache-cold"]
        report["byte_identical_warm"] = jars["cache-warm"] == jars["baseline-warm"]
        report["problems"].extend(evaluate(jars, marker))
        times = {label: build["seconds"] for label, build in report["builds"].items()}
        report["seconds_saved_cold"] = round(times["baseline-cold"] - times["cache-cold"], 1)
        report["seconds_saved_warm"] = round(times["baseline-warm"] - times["cache-warm"], 1)
        return report

    def keep_sbom(self, service: str, label: str, jar: bytes) -> None:
        """Keeps each build's embedded SBOM next to the report, so a difference can be read."""
        try:
            with zipfile.ZipFile(io.BytesIO(jar)) as archive:
                (self.out / f"{service}-{label}.bom.json").write_bytes(archive.read(SBOM))
        except (KeyError, zipfile.BadZipFile):
            pass

    def cleanup(self) -> None:
        for tag in self.tags:
            self.run(["image", "rm", "--force", tag])


def summary(reports: list) -> str:
    lines = ["| Service | Baseline cold | Cache cold | Cache warm | Baseline warm | Same contents, change reached | Result |",
             "|---|---|---|---|---|---|---|"]
    for report in reports:
        builds = report.get("builds", {})
        cells = [f"{builds[b]['seconds']} s" if b in builds else "-" for b in BUILDS]
        ok = "yes" if not report["problems"] and len(builds) == 4 else "no"
        result = "PASS" if not report["problems"] else "FAIL: " + "; ".join(report["problems"])
        lines.append(f"| {report['service']} | " + " | ".join(cells) + f" | {ok} | {result} |")
    return "\n".join(lines) + "\n"


def main(argv: list) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--service", action="append", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--context", default=".")
    parser.add_argument("--revision", default="build-cache-trial")
    parser.add_argument("--baseline-ref")
    parser.add_argument("--prune-cache-mounts", action="store_true")
    args = parser.parse_args(argv)

    context = Path(args.context).resolve()
    out = Path(args.out).resolve()
    if out == context or context in out.parents:
        print(f"{PREFIX}: FAIL: --out must be outside the build context: a file there would change COPY . .",
              file=sys.stderr)
        return 2
    out.mkdir(parents=True, exist_ok=True)
    trial = Trial(context, out, args.revision, args.prune_cache_mounts)
    reports = []
    try:
        for service in args.service:
            baseline_text = None
            if args.baseline_ref:
                shown = subprocess.run(["git", "-C", str(context), "show", f"{args.baseline_ref}:services/{service}/Dockerfile"],
                                       capture_output=True, text=True, check=False)
                if shown.returncode != 0:
                    print(f"{PREFIX}: FAIL: cannot read the baseline Dockerfile of {service} at {args.baseline_ref}",
                          file=sys.stderr)
                    return 2
                baseline_text = shown.stdout
            reports.append(trial.service(service, baseline_text))
    finally:
        trial.cleanup()
    (out / "report.json").write_text(json.dumps({"revision": args.revision, "services": reports}, indent=2) + "\n")
    (out / "summary.md").write_text(summary(reports))
    print(summary(reports))
    failed = [r["service"] for r in reports if r["problems"]]
    if failed:
        print(f"{PREFIX}: FAIL: {', '.join(failed)}", file=sys.stderr)
        return 1
    print(f"{PREFIX}: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
