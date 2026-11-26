#!/usr/bin/env python3
"""Opt-in complete-table export of erasure tombstones to an off-host store.

Disabled unless PARKIO_OFFHOST_ERASURE_ENABLED=1. Does not start from cron.
Does not read names, emails, tokens, or dump contents. Does not print
tombstone payloads.

Coverage advances only after a table-share-lock snapshot AND its seal both
persist. --query-time is the lock-held commit watermark from
offhost-erasure-locked-snapshot.sql (operator attestation), not a
source-query or wall-clock stamp. Default row-set-only never advances
coverage. The persist clock is not coverage. The database transaction
must already be committed before this process writes to the store.

Usage:
  PARKIO_OFFHOST_ERASURE_ENABLED=1 \\
    python3 scripts/offhost-erasure-export.py --from-ledger FILE --query-time TS \\
      --visibility-protocol table-share-lock --store-dir DIR

  PARKIO_OFFHOST_ERASURE_ENABLED=1 \\
    python3 scripts/offhost-erasure-export.py --retry --store-dir DIR
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts" / "lib"))
from offhost_erasure import (  # noqa: E402
    DisabledError,
    FileStore,
    OffhostError,
    PROTOCOL_LOCK,
    PROTOCOL_ROWSET,
    RemoteWriteError,
    StaleSnapshotError,
    persist_complete_snapshot,
    public_result,
    retry_pending,
)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--from-ledger", help="JSON array of {authUserId, erasedAt}")
    parser.add_argument(
        "--query-time",
        help="lock-held commit watermark (not an unlocked SELECT query time)",
    )
    parser.add_argument(
        "--visibility-protocol",
        choices=(PROTOCOL_ROWSET, PROTOCOL_LOCK),
        default=PROTOCOL_ROWSET,
        help="row-set-only never advances cutoff coverage",
    )
    parser.add_argument("--store-dir", required=True)
    parser.add_argument("--retry", action="store_true")
    args = parser.parse_args(argv)
    store = FileStore(args.store_dir)
    env = os.environ
    try:
        if args.retry:
            result = retry_pending(store, env)
        else:
            if not args.from_ledger or not args.query_time:
                print("ERROR: --from-ledger and --query-time are required unless --retry",
                      file=sys.stderr)
                return 2
            entries = json.loads(Path(args.from_ledger).read_text(encoding="utf-8"))
            result = persist_complete_snapshot(
                store, entries, args.query_time, env,
                visibility_protocol=args.visibility_protocol,
            )
    except DisabledError as exc:
        print(json.dumps({"verdict": "DISABLED", "reason": str(exc)}))
        return 2
    except RemoteWriteError as exc:
        print(json.dumps({"verdict": "RETRY", "reason": str(exc), "coverageAdvanced": False}))
        return 4
    except StaleSnapshotError as exc:
        print(json.dumps({"verdict": "STALE", "reason": str(exc), "coverageAdvanced": False}))
        return 1
    except (OffhostError, ValueError, OSError) as exc:
        print(json.dumps({"verdict": "FAIL", "reason": type(exc).__name__}))
        return 1
    print(json.dumps(dict({"verdict": "PASS"}, **public_result(result)), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
