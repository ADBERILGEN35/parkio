#!/usr/bin/env python3
"""Rollback schema gate (CL-F12 F-INV-3, owner decision 2026-10-05).

    rollback_schema_gate.py --target TARGET_MANIFEST --deployed DEPLOYED_MANIFEST

An image/config rollback is not a database restore: Flyway never un-applies a migration. A rollback is
compatible only when the live schema holds no migration that the target release lacks.

- The live schema is read from the deployed release's recorded manifest, which a deploy or a rollback
  writes outside any checkout before it starts a release (parkio_record_deployed_manifest). Its
  migrationVersions lists, per service, the Flyway scripts (V<version>__<description>.sql) of the
  commit that release was built from. It is never this checkout's deploy-artifacts/current.json.
- The rollback re-points the services in the target manifest's `images` map. Only those services'
  schemas can change; a digest-pinned service keeps its image, so it is not compared.
- For each re-pointed service, every script the deployed manifest records must also be in the
  target's list. File names are compared, not version numbers: a target with a higher maximum
  version is still refused when it lacks one of the deployed scripts, and a script that kept its
  version under another description counts as different.

The gate fails closed (exit 3) in each of these cases:
- either manifest is missing or unreadable;
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


def check(target: dict, deployed: dict) -> str:
    """The PASS message, or raises Refused."""
    target_migrations = migrations(target, "target")
    deployed_migrations = migrations(deployed, "deployed release's")
    images = target.get("images")
    if not isinstance(images, dict) or not images:
        raise Refused("the target manifest records no images, so the services this rollback re-points are unknown")
    ahead = {}
    for service in sorted(images):
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
    compared = sum(len(deployed_migrations[service]) for service in images)
    return (f"{PREFIX}: compatible: every migration the deployed release recorded for the {len(images)} "
            f"re-pointed services ({compared} scripts) is in the target")


def main(argv: list) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--target", required=True)
    parser.add_argument("--deployed", required=True)
    args = parser.parse_args(argv)
    try:
        print(check(load(args.target, "target"), load(args.deployed, "deployed release's")))
        return 0
    except Refused as refused:
        print(f"ERROR: {PREFIX}: {refused}.", file=sys.stderr)
        print("       Refusing the rollback before it changes anything (F-INV-3). There is no override.", file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
