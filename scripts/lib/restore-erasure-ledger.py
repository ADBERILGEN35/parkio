#!/usr/bin/env python3
"""Build the authoritative erasure set for a restore, through a declared cutoff.

A ledger bundled with an old stamp only knows erasures up to that stamp. The
restore must replay every erasure up to the recovery cutoff, which is the
latest moment whose erasure requests must be honoured. The inputs for this
set are:

  * the erasure ledger of the data stamp being restored;
  * the ledgers of every NEWER stamp that can be retrieved, even if their dumps
    are unusable (each ledger is the full erased_user_tombstones table);
  * optionally, an operator-compiled supplemental ledger that covers erasures
    after the newest stamp, with an explicit covered-through time.

If the evidence does not reach the cutoff, the verdict is BLOCKED (exit 3). The
restored data must not be exposed until the gap is closed.

Usage:
  restore-erasure-ledger.py --data-stamp DIR [--ledger-stamp DIR ...]
      [--supplemental FILE --supplemental-covered-through TS]
      --recovery-cutoff TS --out MERGED.json

TS is ISO-8601 UTC (2026-09-21T00:00:00Z) or stamp form (2026-09-21T00-00-00Z).
The merged ledger (identifiers) is written 0600 to --out and must stay on the
drill host. The report on stdout has counts only.

Exit: 0 = PASS, 1 = FAIL (invalid or inconsistent evidence), 2 = usage, 3 = BLOCKED.
"""
import argparse
import calendar
import json
import os
from pathlib import Path
import re
import sys
import time

UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.I)
TS_RE = re.compile(r"^(\d{4})-(\d{2})-(\d{2})T(\d{2})[:-](\d{2})[:-](\d{2})(?:\.\d+)?Z$")


class EvidenceError(Exception):
    pass


def parse_ts(value, label):
    match = TS_RE.match(value or "")
    if not match:
        raise EvidenceError(f"{label}: not an ISO-8601 UTC timestamp")
    return calendar.timegm(tuple(int(g) for g in match.groups()) + (0, 0, 0))


def iso(epoch):
    return None if epoch is None else time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(epoch))


def load_entries(path, label):
    try:
        data = json.loads(Path(path).read_text())
    except (OSError, ValueError):
        raise EvidenceError(f"{label}: ledger missing or not JSON")
    if not isinstance(data, list):
        raise EvidenceError(f"{label}: ledger is not an array")
    entries = {}
    for item in data:
        if (not isinstance(item, dict) or not set(item) <= {"authUserId", "erasedAt"}
                or not UUID_RE.match(str(item.get("authUserId", "")))):
            raise EvidenceError(f"{label}: ledger entry is not {{authUserId: uuid, erasedAt}}")
        entries[item["authUserId"].lower()] = item.get("erasedAt")
    return entries


def load_stamp(directory, label):
    stamp = Path(directory)
    try:
        manifest = json.loads((stamp / "backup-manifest.json").read_text())
    except (OSError, ValueError):
        raise EvidenceError(f"{label}: backup-manifest.json missing or not JSON")
    taken = parse_ts(manifest.get("timestamp"), f"{label} manifest timestamp")
    return {"label": label, "stamp": manifest["timestamp"], "epoch": taken,
            "entries": load_entries(stamp / "erasure-tombstones.json", label)}


def build(data_stamp, ledger_stamps, cutoff, supplemental=None, supplemental_through=None):
    data = load_stamp(data_stamp, "data-stamp")
    ledgers = [data] + [load_stamp(d, f"ledger-stamp-{i + 1}") for i, d in enumerate(ledger_stamps)]
    ledgers.sort(key=lambda item: item["epoch"])

    # Tombstones are append-only: a newer ledger must contain every older identifier.
    for older, newer in zip(ledgers, ledgers[1:]):
        missing = set(older["entries"]) - set(newer["entries"])
        if missing:
            raise EvidenceError(f"{newer['label']} lacks {len(missing)} tombstones present in "
                                f"{older['label']}; ledger history is inconsistent")

    merged = {}
    for ledger in ledgers:
        for user_id, erased_at in ledger["entries"].items():
            merged.setdefault(user_id, erased_at)
    coverage = ledgers[-1]["epoch"]
    supplemental_count = 0
    if supplemental:
        if not supplemental_through:
            raise EvidenceError("--supplemental requires --supplemental-covered-through")
        through = parse_ts(supplemental_through, "supplemental covered-through")
        if through < coverage:
            raise EvidenceError("supplemental covered-through precedes the newest stamp ledger")
        extra = load_entries(supplemental, "supplemental")
        supplemental_count = len(extra)
        for user_id, erased_at in extra.items():
            merged.setdefault(user_id, erased_at)
        coverage = through

    gap = cutoff - coverage
    report = {
        "dataStamp": data["stamp"],
        "ledgers": [{"stamp": l["stamp"], "tombstones": len(l["entries"])} for l in ledgers],
        "supplementalTombstones": supplemental_count,
        "mergedTombstones": len(merged),
        "erasedAfterDataStamp": len(set(merged) - set(data["entries"])),
        "coverageThrough": iso(coverage),
        "recoveryCutoff": iso(cutoff),
        "uncoveredSeconds": max(gap, 0),
        "verdict": "BLOCKED" if gap > 0 else "PASS",
    }
    if gap > 0:
        report["blockedReason"] = ("erasures between coverageThrough and recoveryCutoff are unknown; "
                                   "privacy-safe recovery is BLOCKED until they are supplied")
    ledger = [{"authUserId": k, "erasedAt": v} if v else {"authUserId": k} for k, v in sorted(merged.items())]
    return report, ledger


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--data-stamp", required=True)
    parser.add_argument("--ledger-stamp", action="append", default=[])
    parser.add_argument("--supplemental")
    parser.add_argument("--supplemental-covered-through")
    parser.add_argument("--recovery-cutoff", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    try:
        cutoff = parse_ts(args.recovery_cutoff, "--recovery-cutoff")
        report, ledger = build(args.data_stamp, args.ledger_stamp, cutoff,
                               args.supplemental, args.supplemental_covered_through)
    except EvidenceError as error:
        report, ledger = {"verdict": "FAIL", "reason": str(error)}, None
    if ledger is not None:
        fd = os.open(args.out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as handle:
            json.dump(ledger, handle)
    json.dump(dict({"tool": "restore-erasure-ledger", "schemaVersion": 1}, **report),
              sys.stdout, indent=2)
    sys.stdout.write("\n")
    return {"PASS": 0, "FAIL": 1, "BLOCKED": 3}[report["verdict"]]


if __name__ == "__main__":
    sys.exit(main())
