#!/usr/bin/env python3
"""Poll the gateway waitlist ops inbox into the durable Slack-biz queue.

The gateway (parkio.waitlist.ops-notifications.export-dir) writes one sanitized
JSON envelope per committed waitlist confirmation. This consumer validates each
file, durable-enqueues it (dedup by dedupKey) and only then moves it to .acked/.
Delivery is done by worker.py as for every other slack_biz family.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

_ROOT = Path(__file__).resolve().parent
if str(_ROOT.parent) not in sys.path:
    sys.path.insert(0, str(_ROOT.parent))

from slack_biz.adapters import WAITLIST_PRODUCER, parse_and_enqueue_waitlist  # noqa: E402
from slack_biz.config import load_config  # noqa: E402
from slack_biz.registration_consumer import FileInboxRegistrationConsumer  # noqa: E402
from slack_biz.store import DeliveryStore  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--loop", action="store_true")
    parser.add_argument("--interval", type=float, default=5.0)
    args = parser.parse_args(argv)
    config = load_config()
    if not config.waitlist_inbox_dir:
        print("ERROR: set PARKIO_SLACK_BIZ_WAITLIST_INBOX", file=sys.stderr)
        return 2
    store = DeliveryStore(
        config.db_path,
        dedup_retention_hours=config.dedup_retention_hours,
        lease_seconds=config.lease_seconds,
        worker_stale_seconds=config.worker_stale_seconds,
    )
    try:
        consumer = FileInboxRegistrationConsumer(
            config,
            store,
            config.waitlist_inbox_dir,
            producer=WAITLIST_PRODUCER,
            enqueue_fn=parse_and_enqueue_waitlist,
        )
        if args.once or not args.loop:
            print(json.dumps(consumer.poll_once().__dict__))
            return 0
        while True:
            print(json.dumps(consumer.poll_once().__dict__), flush=True)
            time.sleep(args.interval)
    finally:
        store.close()


if __name__ == "__main__":
    raise SystemExit(main())
