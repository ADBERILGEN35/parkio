#!/usr/bin/env python3
"""Process the durable Slack-biz delivery queue (single-worker enforced)."""

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
from slack_biz.delivery import DeliveryWorker  # noqa: E402
from slack_biz.store import DeliveryStore, WorkerLockError  # noqa: E402
from slack_biz.transport import SlackWebhookTransport  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Parkio Slack-biz delivery worker")
    parser.add_argument("--once", action="store_true", help="Process one batch and exit")
    parser.add_argument("--loop", action="store_true", help="Poll forever")
    parser.add_argument("--interval", type=float, default=2.0)
    parser.add_argument("--worker-id", default="worker-1")
    parser.add_argument("--list-dlt", action="store_true")
    parser.add_argument("--list-unknown", action="store_true")
    parser.add_argument("--metrics", action="store_true")
    parser.add_argument(
        "--resolve-unknown",
        metavar="EVENT_ID",
        help="Operator resolve delivery_unknown event_id",
    )
    parser.add_argument(
        "--resolution",
        choices=["accept_as_delivered", "requeue", "discard"],
        default="accept_as_delivered",
    )
    parser.add_argument("--operator", default="operator")
    args = parser.parse_args(argv)

    config = load_config()
    if config.forbid_alertmanager_webhook:
        import os

        am = (os.environ.get("PARKIO_ALERT_SLACK_WEBHOOK_URL") or "").strip()
        for candidate in (config.webhook_url, config.webhook_biz, config.webhook_ops):
            if am and candidate and candidate == am:
                print(
                    "ERROR: PARKIO_SLACK_BIZ_* webhook must not equal "
                    "PARKIO_ALERT_SLACK_WEBHOOK_URL (Alertmanager ownership).",
                    file=sys.stderr,
                )
                return 2

    store = DeliveryStore(
        config.db_path,
        dedup_retention_hours=config.dedup_retention_hours,
        lease_seconds=config.lease_seconds,
        worker_stale_seconds=config.worker_stale_seconds,
    )
    worker = None
    try:
        if args.list_dlt:
            print(json.dumps(store.list_dlt(), indent=2, default=str))
            return 0
        if args.list_unknown:
            print(json.dumps(store.list_uncertain(), indent=2, default=str))
            return 0
        if args.metrics:
            print(json.dumps(store.snapshot_metrics(), indent=2))
            return 0
        if args.resolve_unknown:
            out = store.resolve_unknown(
                args.resolve_unknown,
                resolution=args.resolution,
                operator=args.operator,
            )
            print(json.dumps({"event_id": args.resolve_unknown, "result": out}))
            return 0 if out not in {"not_found", "invalid_resolution"} else 1

        if not config.enabled:
            print(
                json.dumps(
                    {
                        "enabled": False,
                        "message": "PARKIO_SLACK_BIZ_ENABLED is false; no outbound delivery",
                        "pending": store.pending_count(),
                        "delivery_unknown": store.count_by_status("delivery_unknown"),
                    }
                )
            )
            if args.once or not args.loop:
                return 0

        try:
            worker = DeliveryWorker(
                config,
                store,
                SlackWebhookTransport(timeout_seconds=config.http_timeout_seconds),
                worker_id=args.worker_id,
                acquire_lock=True,
            )
        except WorkerLockError as exc:
            print(json.dumps({"error": "worker_lock", "detail": str(exc)}), file=sys.stderr)
            return 3

        if args.once or not args.loop:
            stats = worker.process_once()
            print(
                json.dumps(
                    {
                        "enabled": config.enabled,
                        "activation_ready": config.activation_ready(),
                        "delivered": stats.delivered,
                        "retried": stats.retried,
                        "ambiguous_retried": stats.ambiguous_retried,
                        "delivery_unknown": stats.delivery_unknown,
                        "dead": stats.dead,
                        "pending": store.pending_count(),
                    }
                )
            )
            return 0

        while True:
            worker.process_once()
            time.sleep(args.interval)
    finally:
        if worker is not None:
            worker.close()
        store.close()


if __name__ == "__main__":
    raise SystemExit(main())
