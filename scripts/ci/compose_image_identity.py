#!/usr/bin/env python3
"""Prove that a CI Compose stack runs the images its job built (CI audit 2026-10-04, item 3).

The runtime, chaos and performance workflows build their service images and then start the stack.
No build cache is shared between jobs or workflows: they run concurrently, and every service
Dockerfile copies the whole repository before its Gradle build, so a shared cache would gain almost
nothing. What this script adds is identity:

  record MODEL.json --out RECORD.json --override OVERRIDE.json [--expect-revision SHA]
      MODEL.json is `docker compose config --format json`. For every service that the model builds,
      it records the image reference Compose uses (`image`, or `<project>-<service>` when the service
      only has `build`), the image id, the RootFS layer digests and the OCI revision label.
      It fails when such an image is missing. With --expect-revision, it also fails when a revision
      label is anything other than SHA. OVERRIDE.json is a Compose file that sets
      `pull_policy: never` on exactly those services, so `up --no-build -f OVERRIDE.json` can
      neither build nor pull them.

  verify RECORD.json
      After `up`, it checks three things for every recorded service:
        * the reference still names the recorded image id;
        * the service has at least one container in the recorded project;
        * every such container runs the recorded image id.

  probe --dockerfile PATH --input FILE [--context DIR] [--revision SHA] --out REPORT.json
      It builds one Dockerfile on the job's own daemon three times: twice with the same inputs, then
      once after appending a comment line to FILE (restored afterwards). It then requires:
        * the repeat gives identical RootFS layers, which is what a cache hit must give;
        * the changed build keeps the base image's layers and changes a later one, so the cache
          cannot hide a changed input.
      Byte-identical clean rebuilds are not claimed: jars carry build timestamps and the runtime
      stage installs packages from apt.

Exit codes: 0 = pass, 1 = failed check, 2 = usage or unreadable input.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Callable, Optional

REVISION = "org.opencontainers.image.revision"
PREFIX = "compose-image-identity"

Runner = Callable[[list], subprocess.CompletedProcess]


def run_docker(args: list) -> subprocess.CompletedProcess:
    return subprocess.run(["docker", *args], capture_output=True, text=True, check=False)


def built_services(model: dict) -> dict:
    """{service: image reference} for every service the model builds, named the way Compose names it."""
    project = model.get("name")
    refs = {}
    for name, service in sorted((model.get("services") or {}).items()):
        if isinstance(service, dict) and service.get("build"):
            refs[name] = service.get("image") or f"{project}-{name}"
    return refs


def inspect_image(ref: str, run: Runner) -> Optional[dict]:
    done = run(["image", "inspect", ref])
    if done.returncode != 0:
        return None
    try:
        return json.loads(done.stdout)[0]
    except (ValueError, IndexError, TypeError):
        return None


def layers_of(image: dict) -> list:
    return list((image.get("RootFS") or {}).get("Layers") or [])


def record(model: dict, expect_revision: Optional[str], run: Runner = run_docker) -> tuple:
    """(exit code, message, record, override)."""
    refs = built_services(model)
    if not model.get("name"):
        return 1, f"{PREFIX}: FAIL: the model has no project name", None, None
    if not refs:
        return 1, f"{PREFIX}: FAIL: the model builds no service", None, None
    entries, problems = {}, []
    for name, ref in refs.items():
        image = inspect_image(ref, run)
        if image is None:
            problems.append(f"{name}: image {ref} is not present")
            continue
        revision = ((image.get("Config") or {}).get("Labels") or {}).get(REVISION)
        entries[name] = {"image": ref, "id": image.get("Id"), "layers": layers_of(image), "revision": revision}
        if expect_revision and revision is not None and revision != expect_revision:
            problems.append(f"{name}: revision label {revision!r}, expected {expect_revision}")
    rec = {"project": model["name"], "expected_revision": expect_revision, "services": entries}
    override = {"services": {name: {"pull_policy": "never"} for name in refs}}
    if problems:
        return 1, f"{PREFIX}: FAIL: " + "; ".join(problems), rec, override
    return 0, f"{PREFIX}: recorded {len(entries)} built image(s) of project {model['name']}", rec, override


def containers(project: str, service: str, run: Runner) -> Optional[list]:
    done = run(["ps", "-a", "--filter", f"label=com.docker.compose.project={project}",
                "--filter", f"label=com.docker.compose.service={service}", "--format", "{{.ID}}"])
    if done.returncode != 0:
        return None
    return [line.strip() for line in done.stdout.splitlines() if line.strip()]


def verify(rec: dict, run: Runner = run_docker) -> tuple:
    """(exit code, message) after `up`."""
    services = rec.get("services") or {}
    if not services:
        return 1, f"{PREFIX}: FAIL: the record lists no built image"
    problems, checked = [], 0
    for name, entry in sorted(services.items()):
        image = inspect_image(entry["image"], run)
        current = image.get("Id") if image else None
        if current != entry["id"]:
            problems.append(f"{name}: {entry['image']} now names {current or 'nothing'}, not {entry['id']}")
        ids = containers(rec["project"], name, run)
        if ids is None:
            problems.append(f"{name}: cannot list its containers")
            continue
        if not ids:
            problems.append(f"{name}: no container in project {rec['project']}")
        for cid in ids:
            done = run(["inspect", "--format", "{{.Image}}", cid])
            served = done.stdout.strip() if done.returncode == 0 else None
            checked += 1
            if served != entry["id"]:
                problems.append(f"{name}: container {cid[:12]} runs {served or 'an unknown image'}, not {entry['id']}")
    if problems:
        return 1, f"{PREFIX}: FAIL: " + "; ".join(problems)
    return 0, f"{PREFIX}: {checked} container(s) of {len(services)} built service(s) run the recorded images"


def compare_layers(base: list, first: list, repeat: list, changed: list) -> list:
    """Problems with the probe's three builds; empty when the cache is keyed by the inputs."""
    problems = []
    k = len(base)
    if not base:
        problems.append("the base image has no layers")
    if first != repeat:
        problems.append("a repeat build with the same inputs gave different layers")
    for label, layers in (("first", first), ("changed", changed)):
        if layers[:k] != base:
            problems.append(f"the {label} build does not start with the base image's layers")
    if changed == first:
        problems.append("changing the input did not change any layer: the cache hid the change")
    return problems


def last_base(dockerfile: Path) -> Optional[str]:
    """The image of the Dockerfile's last FROM line, which is the final stage's base."""
    base = None
    for line in dockerfile.read_text().splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[0].upper() == "FROM":
            base = parts[1]
    return base


def probe(dockerfile: Path, context: Path, input_file: Path, revision: str, out: Path,
          run: Runner = run_docker) -> int:
    base_ref = last_base(dockerfile)
    report = {"dockerfile": str(dockerfile), "input": str(input_file), "base": base_ref, "builds": {}}
    # Nothing is written until every build is done: the output may lie inside the build context, and a
    # new file there would change `COPY . .` between the builds.
    logs = {}

    def finish(code: int, message: str) -> int:
        report["result"] = "PASS" if code == 0 else "FAIL"
        report["message"] = message
        out.parent.mkdir(parents=True, exist_ok=True)
        for label, text in logs.items():
            (out.parent / f"probe-build-{label}.log").write_text(text)
        out.write_text(json.dumps(report, indent=2) + "\n")
        print(message, file=sys.stderr if code else sys.stdout)
        return code

    if not base_ref or base_ref.lower() == "scratch":
        return finish(2, f"{PREFIX}: FAIL: no base image in {dockerfile}")
    pulled = run(["pull", base_ref])
    base = inspect_image(base_ref, run) if pulled.returncode == 0 else None
    if base is None:
        return finish(1, f"{PREFIX}: FAIL: cannot pull or inspect the base image {base_ref}")
    report["base_layers"] = layers_of(base)
    tag = f"{PREFIX}-probe:{int(time.time())}"

    def build(label: str) -> Optional[list]:
        started = time.monotonic()
        done = run(["build", "--progress=plain", "-f", str(dockerfile), "--build-arg", f"IMAGE_REVISION={revision}",
                    "-t", f"{tag}-{label}", str(context)])
        logs[label] = (done.stdout or "") + (done.stderr or "")
        image = inspect_image(f"{tag}-{label}", run) if done.returncode == 0 else None
        report["builds"][label] = {"exit": done.returncode, "seconds": round(time.monotonic() - started, 1),
                                   "id": image.get("Id") if image else None,
                                   "layers": layers_of(image) if image else None}
        return layers_of(image) if image else None

    original = input_file.read_bytes()
    try:
        first = build("first")
        repeat = build("repeat") if first is not None else None
        if first is None or repeat is None:
            return finish(1, f"{PREFIX}: FAIL: a probe build failed (see probe-build-*.log)")
        input_file.write_bytes(original + b"\n# compose-image-identity probe: changed input\n")
        changed = build("changed")
    finally:
        input_file.write_bytes(original)
        for label in ("first", "repeat", "changed"):
            run(["image", "rm", f"{tag}-{label}"])
    if changed is None:
        return finish(1, f"{PREFIX}: FAIL: the build after the input change failed (see probe-build-changed.log)")
    problems = compare_layers(report["base_layers"], first, repeat, changed)
    differing = [i for i, (a, b) in enumerate(zip(first, changed)) if a != b]
    report["changed_layer_indexes"] = differing + list(range(min(len(first), len(changed)),
                                                             max(len(first), len(changed))))
    if problems:
        return finish(1, f"{PREFIX}: FAIL: " + "; ".join(problems))
    return finish(0, f"{PREFIX}: probe PASS: the repeat build kept all {len(first)} layers; the changed input "
                     f"kept the {len(report['base_layers'])} base layers and changed layer(s) "
                     f"{report['changed_layer_indexes']}")


def main(argv: list) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    rec = sub.add_parser("record")
    rec.add_argument("model")
    rec.add_argument("--out", required=True)
    rec.add_argument("--override", required=True)
    rec.add_argument("--expect-revision")
    ver = sub.add_parser("verify")
    ver.add_argument("record")
    prb = sub.add_parser("probe")
    prb.add_argument("--dockerfile", required=True)
    prb.add_argument("--input", required=True)
    prb.add_argument("--context", default=".")
    prb.add_argument("--revision", default="compose-image-identity-probe")
    prb.add_argument("--out", required=True)
    args = parser.parse_args(argv)

    if args.command == "probe":
        return probe(Path(args.dockerfile), Path(args.context), Path(args.input), args.revision, Path(args.out))
    try:
        data = json.loads(Path(args.model if args.command == "record" else args.record).read_text())
    except (OSError, ValueError) as error:
        print(f"{PREFIX}: FAIL: cannot read input: {error}", file=sys.stderr)
        return 2
    if args.command == "record":
        code, message, rec_data, override = record(data, args.expect_revision)
        if rec_data is not None:
            Path(args.out).parent.mkdir(parents=True, exist_ok=True)
            Path(args.out).write_text(json.dumps(rec_data, indent=2) + "\n")
            # The job log keeps the identity even when the record file is not uploaded.
            for name, entry in sorted(rec_data["services"].items()):
                print(f"  {name}: {entry['image']} {entry['id']} revision={entry['revision']} layers={len(entry['layers'])}")
        if override is not None:
            Path(args.override).write_text(json.dumps(override, indent=2) + "\n")
    else:
        code, message = verify(data)
    print(message, file=sys.stderr if code else sys.stdout)
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
