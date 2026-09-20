#!/usr/bin/env python3
"""Poll registration file-inbox (or optional Kafka) into the durable queue."""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

_ROOT = Path(__file__).resolve().parent
if str(_ROOT.parent) not in sys.path:
    sys.path.insert(0, str(_ROOT.parent))

from slack_biz.config import load_config  # noqa: E402
from slack_biz.registration_consumer import (  # noqa: E402
    FileInboxRegistrationConsumer,
    KafkaRegistrationConsumer,
    adapter_implementation_status,
)
from slack_biz.store import DeliveryStore  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--loop", action="store_true")
    parser.add_argument("--interval", type=float, default=2.0)
    parser.add_argument("--status", action="store_true")
    args = parser.parse_args(argv)
    config = load_config()
    if args.status:
        print(json.dumps(adapter_implementation_status(config), indent=2))
        return 0
    store = DeliveryStore(
        config.db_path,
        dedup_retention_hours=config.dedup_retention_hours,
        lease_seconds=config.lease_seconds,
        worker_stale_seconds=config.worker_stale_seconds,
    )
    consumer = None
    try:
        if config.kafka_bootstrap:
            try:
                consumer = KafkaRegistrationConsumer(config, store)
            except RuntimeError as exc:
                print(json.dumps({"error": str(exc)}), file=sys.stderr)
                return 2
        elif config.registration_inbox_dir:
            consumer = FileInboxRegistrationConsumer(
                config, store, config.registration_inbox_dir
            )
        else:
            print(
                "ERROR: set PARKIO_SLACK_BIZ_REGISTRATION_INBOX or "
                "PARKIO_SLACK_BIZ_KAFKA_BOOTSTRAP",
                file=sys.stderr,
            )
            return 2

        if args.once or not args.loop:
            print(json.dumps(consumer.poll_once().__dict__))
            return 0
        while True:
            print(json.dumps(consumer.poll_once().__dict__), flush=True)
            time.sleep(args.interval)
    finally:
        if consumer is not None and hasattr(consumer, "close"):
            consumer.close()
        store.close()


if __name__ == "__main__":
    raise SystemExit(main())
