#!/usr/bin/env python3
"""Drift check: rehearsal-authored manifest key set/types vs a hosted-beta fixture (U13 F4)."""
from __future__ import annotations

import json
import sys
from pathlib import Path

REQUIRED_KEYS = {
    "schemaVersion": int,
    "action": str,
    "gitSha": str,
    "imageTag": str,
    "deploymentProfile": str,
    "images": dict,
    "pinnedImages": dict,
}


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: assert-rehearsal-manifest-shape.py MANIFEST FIXTURE_KEYS_JSON", file=sys.stderr)
        return 2
    manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    fixture = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
    problems = []
    for key, typ in REQUIRED_KEYS.items():
        if key not in manifest:
            problems.append(f"missing {key}")
        elif not isinstance(manifest[key], typ):
            problems.append(f"{key} type {type(manifest[key]).__name__}, want {typ.__name__}")
    for key in fixture.get("requiredKeys", []):
        if key not in manifest:
            problems.append(f"fixture required key missing: {key}")
    if manifest.get("evidenceLabel") != "rehearsal-authored manifest":
        problems.append("evidenceLabel must be 'rehearsal-authored manifest'")
    if manifest.get("deploymentProfile") != "ci-disposable":
        problems.append("deploymentProfile must be ci-disposable")
    if manifest.get("images") != {}:
        problems.append("images must be empty for rehearsal")
    if not isinstance(manifest.get("pinnedImages"), dict) or "web" not in manifest["pinnedImages"]:
        problems.append("pinnedImages.web required")
    if problems:
        print("REFUSED: " + "; ".join(problems), file=sys.stderr)
        return 3
    print("rehearsal manifest shape OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
