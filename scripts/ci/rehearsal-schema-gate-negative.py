#!/usr/bin/env python3
"""Negative control: empty images → schema gate exit 3 'records no images' (U13 option B R2)."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GATE = ROOT / "scripts" / "lib" / "rollback_schema_gate.py"


def main() -> int:
    pin = "ghcr.io/adberilgen35/parkio/web@sha256:" + ("a" * 64)
    target = {
        "schemaVersion": 1,
        "images": {},
        "pinnedImages": {"web": pin},
        "migrationVersions": {"web": []},
    }
    deployed = {
        "schemaVersion": 1,
        "images": {},
        "pinnedImages": {"web": pin},
        "migrationVersions": {"web": []},
    }
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp) / "target.json"
        s = Path(tmp) / "deployed.json"
        t.write_text(json.dumps(target), encoding="utf-8")
        s.write_text(json.dumps(deployed), encoding="utf-8")
        r = subprocess.run(
            [sys.executable, str(GATE), "--target", str(t), "--deployed", str(s)],
            capture_output=True,
            text=True,
            cwd=ROOT,
        )
        text = r.stdout + r.stderr
        print(text)
        if r.returncode != 3 or "records no images" not in text:
            print(
                f"REFUSED: expected exit 3 with 'records no images', got {r.returncode}",
                file=sys.stderr,
            )
            return 3
    print("schema-gate negative control PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
