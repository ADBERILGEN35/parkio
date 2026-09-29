#!/usr/bin/env python3
"""Poll the gateway waitlist ops inbox into the durable Slack-biz queue.

The gateway (parkio.waitlist.ops-notifications.export-dir) writes one JSON
envelope per committed waitlist confirmation. This consumer validates each file
against a closed allow-list, durable-enqueues it (dedup by dedupKey) and only
then acks it. Rejections are counted by bounded category; rejected files are
deleted unless PARKIO_SLACK_BIZ_WAITLIST_RETAIN_REJECTED=true. Delivery is done
by worker.py as for every other slack_biz family.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
import time
from pathlib import Path

_ROOT = Path(__file__).resolve().parent
if str(_ROOT.parent) not in sys.path:
    sys.path.insert(0, str(_ROOT.parent))

from slack_biz.config import load_config  # noqa: E402
from slack_biz.store import DeliveryStore  # noqa: E402
from slack_biz.waitlist_inbox import WaitlistInboxConsumer  # noqa: E402

PRUNE_EVERY_SECONDS = 600


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--loop", action="store_true")
    parser.add_argument("--interval", type=float, default=5.0)
    parser.add_argument("--prune", action="store_true", help="Apply inbox retention and exit")
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
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
        consumer = WaitlistInboxConsumer(config, store, config.waitlist_inbox_dir)
        if args.prune:
            print(json.dumps(consumer.prune()))
            return 0
        if args.once or not args.loop:
            print(json.dumps(consumer.poll_once().__dict__))
            return 0
        last_prune = 0.0
        while True:
            result = consumer.poll_once()
            if result.processed:
                print(json.dumps(result.__dict__), flush=True)
            if time.time() - last_prune >= PRUNE_EVERY_SECONDS:
                consumer.prune()
                last_prune = time.time()
            time.sleep(args.interval)
    finally:
        store.close()


if __name__ == "__main__":
    raise SystemExit(main())
