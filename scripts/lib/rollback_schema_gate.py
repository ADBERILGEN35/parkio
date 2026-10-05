#!/usr/bin/env python3
"""Rollback schema gate (CL-F12 F-INV-3, owner decision 2026-10-05).

    rollback_schema_gate.py --target TARGET_MANIFEST --deployed DEPLOYED_MANIFEST [--image-plan PLAN]

An image/config rollback is not a database restore: Flyway never un-applies a migration. A rollback is
compatible only when the live schema holds no migration that the target release lacks.

- The live schema is read from the deployed release's recorded manifest, which a deploy or a rollback
  writes outside any checkout before it starts a release (parkio_record_deployed_manifest). Its
  migrationVersions lists, per service, the Flyway scripts (V<version>__<description>.sql) of the
  commit that release was built from. It is never this checkout's deploy-artifacts/current.json.
- The rollback re-points the services in the target manifest's `images` map. With --image-plan
  (hosted-beta and azure-hosted-beta: parkio_hosted_beta_image_plan's lines,
  "kind<TAB>service<TAB>image<TAB>platform"), it re-points the plan's built services instead, and
  starts the plan's digest pins. A pinned image's migrations are recorded nowhere: the lists describe
  a checkout, not a pinned image. So the plan's pins must equal the deployed release's recorded
  pinnedImages exactly: the same services, the same digests (#290 review B1). Each service appears
  once in a plan, so no re-pointed service can then be one the deployed release ran as a pin, and
  no pinned service's list is ever compared.
- For each re-pointed service, every script the deployed manifest records must also be in the
  target's list. File names are compared, not version numbers: a target with a higher maximum
  version is still refused when it lacks one of the deployed scripts, and a script that kept its
  version under another description counts as different.

The gate fails closed (exit 3) in each of these cases:
- either manifest is missing or unreadable;
- with --image-plan: the plan is unreadable or malformed, the record has no pinnedImages, or the
  plan's pins differ from them;
- `migrationVersions` is absent or malformed. It must be an object mapping each service to a list of
  distinct V-script names, one per version;
- the target records no images;
- a re-pointed service is missing from either list.

Exit 0 means the rollback is compatible; exit 2 is a usage error.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

PREFIX = "rollback schema gate"
SCRIPT = re.compile(r"V(?P<version>\d+(?:[._]\d+)*)__(?P<description>[^/\\]+)\.sql")


class Refused(Exception):
    """The rollback is refused; the message says why."""


def load(path: str, role: str) -> dict:
    try:
        with open(path, encoding="utf-8") as fh:
            manifest = json.load(fh)
    except FileNotFoundError:
        raise Refused(f"the {role} manifest is missing: {path}")
    except (OSError, ValueError) as error:
        raise Refused(f"the {role} manifest is unreadable: {path}: {error}")
    if not isinstance(manifest, dict):
        raise Refused(f"the {role} manifest is not a JSON object: {path}")
    return manifest


def version_key(name: str) -> tuple:
    return tuple(int(part) for part in re.split(r"[._]", SCRIPT.fullmatch(name).group("version")))


def migrations(manifest: dict, role: str) -> dict:
    """The manifest's migrationVersions as {service: set of script names}, validated."""
    if "migrationVersions" not in manifest:
        raise Refused(f"the {role} manifest records no migrationVersions, so the schema it describes is unknown")
    recorded = manifest["migrationVersions"]
    if not isinstance(recorded, dict) or not recorded:
        raise Refused(f"the {role} manifest's migrationVersions is not an object of services")
    result = {}
    for service, scripts in recorded.items():
        if not isinstance(scripts, list) or not all(isinstance(s, str) and SCRIPT.fullmatch(s) for s in scripts):
            raise Refused(f"the {role} manifest's migrationVersions for {service} is not a list of "
                          f"V<version>__<description>.sql names: {json.dumps(scripts)[:300]}")
        versions = [version_key(s) for s in scripts]
        if len(set(scripts)) != len(scripts) or len(set(versions)) != len(versions):
            raise Refused(f"the {role} manifest's migrationVersions for {service} repeats a script or a version")
        result[service] = set(scripts)
    return result


def read_plan(text: str) -> tuple:
    """(built services, {pinned service: image}) of an image plan; each service appears once."""
    built, pins, seen = [], {}, set()
    for line in text.splitlines():
        if not line.strip():
            continue
        fields = line.split("\t")
        if len(fields) < 3 or fields[0] not in ("built", "pinned") or not fields[1] or not fields[2]:
            raise Refused(f"the image plan has a malformed line: {line[:200]!r}")
        if fields[1] in seen:
            raise Refused(f"the image plan names {fields[1]} more than once")
        seen.add(fields[1])
        if fields[0] == "built":
            built.append(fields[1])
        else:
            pins[fields[1]] = fields[2]
    if not seen:
        raise Refused("the image plan is empty")
    return built, pins


def check_pins(deployed: dict, pins: dict) -> None:
    """The plan's pins must be exactly the deployed release's recorded pinnedImages."""
    recorded = deployed.get("pinnedImages")
    if not isinstance(recorded, dict) or not all(
            isinstance(k, str) and isinstance(v, str) and v for k, v in recorded.items()):
        raise Refused("the deployed release's manifest records no readable pinnedImages, so the images, and the "
                      "schema, of the digest-pinned services this rollback starts are unknown")
    if pins != recorded:
        changes = [f"{svc}: {recorded.get(svc, '<not pinned>')} -> {pins.get(svc, '<not pinned>')}"
                   for svc in sorted(set(pins) | set(recorded)) if pins.get(svc) != recorded.get(svc)]
        raise Refused("this rollback would change digest pins the deployed release runs "
                      f"({'; '.join(changes)}). A pinned image's migrations are recorded nowhere, so this check "
                      "cannot cover that change. Roll pins through a deploy, then roll back")


def check(target: dict, deployed: dict, plan: str = None) -> str:
    """The PASS message, or raises Refused."""
    target_migrations = migrations(target, "target")
    deployed_migrations = migrations(deployed, "deployed release's")
    images = target.get("images")
    if not isinstance(images, dict) or not images:
        raise Refused("the target manifest records no images, so the services this rollback re-points are unknown")
    repointed = sorted(images)
    if plan is not None:
        built, pins = read_plan(plan)
        check_pins(deployed, pins)
        repointed = sorted(built)
    ahead = {}
    for service in repointed:
        for role, recorded in (("target", target_migrations), ("deployed release's", deployed_migrations)):
            if service not in recorded:
                raise Refused(f"the {role} manifest records no migrationVersions for {service}, which this rollback re-points")
        extra = sorted(deployed_migrations[service] - target_migrations[service], key=version_key)
        if extra:
            ahead[service] = extra
    if ahead:
        detail = "; ".join(f"{service}: {', '.join(scripts)}" for service, scripts in ahead.items())
        raise Refused(f"the live schema is ahead of the rollback target ({detail}). The deployed release "
                      f"{str(deployed.get('gitSha'))[:12]} applied these migrations, and the target "
                      f"{str(target.get('gitSha'))[:12]} does not have them. An image/config rollback would leave "
                      f"an incompatible database; it is not a database restore")
    compared = sum(len(deployed_migrations[service]) for service in repointed)
    pinned = "" if plan is None else "; the digest pins equal the deployed release's"
    return (f"{PREFIX}: compatible: every migration the deployed release recorded for the {len(repointed)} "
            f"re-pointed services ({compared} scripts) is in the target{pinned}")


def main(argv: list) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--target", required=True)
    parser.add_argument("--deployed", required=True)
    parser.add_argument("--image-plan", help="hosted-beta: a file with parkio_hosted_beta_image_plan's lines")
    args = parser.parse_args(argv)
    try:
        plan = None
        if args.image_plan is not None:
            try:
                plan = Path(args.image_plan).read_text(encoding="utf-8")
            except OSError as error:
                raise Refused(f"the image plan is unreadable: {error}")
        print(check(load(args.target, "target"), load(args.deployed, "deployed release's"), plan))
        return 0
    except Refused as refused:
        print(f"ERROR: {PREFIX}: {refused}.", file=sys.stderr)
        print("       Refusing the rollback before it changes anything (F-INV-3). There is no override.", file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
