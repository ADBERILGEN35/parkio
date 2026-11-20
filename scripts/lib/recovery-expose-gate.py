#!/usr/bin/env python3
"""The expose gate of an isolated recovery (U02 stage 4).

  recovery-expose-gate.py init   --recovery-dir DIR
  recovery-expose-gate.py open   --recovery-dir DIR --verdict VERDICT --operator NAME
  recovery-expose-gate.py status --recovery-dir DIR

DIR holds the trusted-set file (trusted-erasure-set.json) the isolated restore wrote before it
decrypted anything, and the gate state (expose-gate.json). The gate is CLOSED from the moment the
restore verified the evidence. It opens only on the recovery-replay command's verdict for the same
attempt, dataset and erasure set: status COMPLETE, exit code 0, and no participant or auth
acknowledgement missing. Anything else leaves it CLOSED and exits 3.

OPEN is a recorded decision, not a switch: it starts no service and publishes no port. Exposing a
restored copy to traffic is outside the isolated recovery and is not authorised here; the
production restore refusal is unchanged.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

GATE_FORMAT = "parkio-recovery-expose-gate"
TRUSTED_SET = "trusted-erasure-set.json"
GATE = "expose-gate.json"
VERDICT_FORMAT = "parkio-recovery-replay-verdict"


class Refused(Exception):
    pass


def now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def read_json(path, label):
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise Refused(f"{label} is missing or not JSON") from exc
    if not isinstance(data, dict):
        raise Refused(f"{label} is not a JSON object")
    return data


def write_gate(directory, gate):
    path = Path(directory) / GATE
    temporary = path.with_name(f".{GATE}.tmp")
    temporary.write_text(json.dumps(gate, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.chmod(temporary, 0o600)
    temporary.replace(path)


def binding(directory):
    trusted = read_json(Path(directory) / TRUSTED_SET, "trusted-set file")
    try:
        return {
            "recoveryAttemptId": trusted["recoveryAttemptId"],
            "restoredDatasetId": trusted["restoredDatasetId"],
            "erasureSetDigest": trusted["erasureSet"]["erasureSetDigest"],
            "coverage": trusted["coverage"]["statement"],
        }
    except (KeyError, TypeError) as exc:
        raise Refused("trusted-set file is incomplete") from exc


def init(directory):
    bound = binding(directory)
    write_gate(directory, {
        "format": GATE_FORMAT,
        "version": 1,
        "state": "CLOSED",
        "since": now(),
        "reason": "restored copy not yet verified by a COMPLETE recovery replay",
        **bound,
    })
    return bound


def check_verdict(bound, verdict):
    if verdict.get("format") != VERDICT_FORMAT or verdict.get("version") != 1:
        raise Refused("verdict is not a recovery-replay verdict")
    if verdict.get("status") != "COMPLETE" or verdict.get("exitCode") != 0:
        raise Refused(f"verdict is {verdict.get('status')} (exit {verdict.get('exitCode')}), not COMPLETE")
    for field in ("recoveryAttemptId", "restoredDatasetId", "erasureSetDigest"):
        if verdict.get(field) != bound[field]:
            raise Refused(f"verdict {field} does not match the trusted set")
    if (verdict.get("coverage") or {}).get("statement") != bound["coverage"]:
        raise Refused("verdict coverage does not match the trusted set")
    participants = verdict.get("participants")
    auth = verdict.get("auth")
    if not isinstance(participants, dict) or not participants or not isinstance(auth, dict):
        raise Refused("verdict does not count every participant and auth")
    for name, row in list(participants.items()) + [("auth", auth)]:
        if not isinstance(row, dict) or row.get("missing") != 0 or row.get("failed") != 0:
            raise Refused(f"verdict shows {name} acknowledgements missing or failed")


def open_gate(directory, verdict_path, operator):
    if not operator or not operator.strip():
        raise Refused("--operator is required")
    gate = read_json(Path(directory) / GATE, "expose gate")
    if gate.get("format") != GATE_FORMAT:
        raise Refused("expose gate state is not recognised")
    bound = binding(directory)
    for field in ("recoveryAttemptId", "restoredDatasetId", "erasureSetDigest"):
        if gate.get(field) != bound[field]:
            raise Refused("expose gate belongs to another recovery")
    raw = Path(verdict_path).read_bytes() if Path(verdict_path).is_file() else b""
    verdict = read_json(verdict_path, "verdict")
    check_verdict(bound, verdict)
    write_gate(directory, {
        "format": GATE_FORMAT,
        "version": 1,
        "state": "OPEN",
        "since": now(),
        "operator": operator.strip(),
        "verdictSha256": hashlib.sha256(raw).hexdigest(),
        "note": "OPEN records that the replay completed; it starts no service and publishes no port",
        **bound,
    })


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    for name in ("init", "open", "status"):
        command = sub.add_parser(name)
        command.add_argument("--recovery-dir", required=True)
        if name == "open":
            command.add_argument("--verdict", required=True)
            command.add_argument("--operator", required=True)
    args = parser.parse_args(argv)
    try:
        if args.cmd == "init":
            init(args.recovery_dir)
            print("expose gate CLOSED")
        elif args.cmd == "open":
            open_gate(args.recovery_dir, args.verdict, args.operator)
            print("expose gate OPEN (recorded; no service started, no port published)")
        else:
            gate = read_json(Path(args.recovery_dir) / GATE, "expose gate")
            print(f"expose gate {gate.get('state')}")
            return 0 if gate.get("state") == "OPEN" else 3
    except Refused as refusal:
        print(f"REFUSED: {refusal}; expose gate stays CLOSED", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
