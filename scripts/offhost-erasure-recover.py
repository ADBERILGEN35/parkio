#!/usr/bin/env python3
"""Verify off-host erasure coverage through a recovery cutoff.

Writes a supplemental ledger compatible with restore-erasure-ledger.py.
The supplemental covered-through value MUST come from a verified seal,
never from the current clock or a persist timestamp.

Exit: 0 PASS, 1 FAIL, 2 usage, 3 BLOCKED.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts" / "lib"))
from offhost_erasure import FileStore, recover, write_supplement  # noqa: E402


def load_stamp_ledger(directory):
    if not directory:
        return []
    path = Path(directory) / "erasure-tombstones.json"
    return json.loads(path.read_text(encoding="utf-8"))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--store-dir", required=True)
    parser.add_argument("--recovery-cutoff", required=True)
    parser.add_argument("--data-stamp", help="optional restored stamp directory")
    parser.add_argument("--out", required=True, help="0600 supplemental ledger")
    args = parser.parse_args(argv)
    try:
        stamp_entries = load_stamp_ledger(args.data_stamp) if args.data_stamp else []
        report = recover(FileStore(args.store_dir), args.recovery_cutoff, stamp_entries)
    except Exception as exc:
        print(json.dumps({"verdict": "FAIL", "reason": type(exc).__name__}))
        return 1
    public = {k: v for k, v in report.items() if k != "merged"}
    print(json.dumps(dict({"tool": "offhost-erasure-recover", "schemaVersion": 1}, **public),
                     indent=2))
    if report["verdict"] == "PASS":
        write_supplement(args.out, report["merged"])
    return {"PASS": 0, "FAIL": 1, "BLOCKED": 3}[report["verdict"]]


if __name__ == "__main__":
    sys.exit(main())
