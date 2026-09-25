#!/usr/bin/env python3
"""Enqueue Slack-biz events from JSON envelopes (stdin or file)."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# Allow running as script from repo root / scripts/
_ROOT = Path(__file__).resolve().parent
if str(_ROOT.parent) not in sys.path:
    sys.path.insert(0, str(_ROOT.parent))

from slack_biz.config import load_config  # noqa: E402
from slack_biz.adapters import (  # noqa: E402
    from_backup_status,
    from_incident_event,
    from_user_registered_envelope,
    refuse_non_completion,
)
from slack_biz.store import DeliveryStore  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Enqueue Parkio Slack-biz events")
    parser.add_argument(
        "--kind",
        required=True,
        choices=["registration", "incident", "backup"],
        help="Adapter family",
    )
    parser.add_argument("--file", help="JSON input file (default: stdin)")
    parser.add_argument(
        "--producer",
        default=None,
        help="Override producer id (must be trusted)",
    )
    args = parser.parse_args(argv)

    raw_text = Path(args.file).read_text(encoding="utf-8") if args.file else sys.stdin.read()
    data = json.loads(raw_text)
    config = load_config()
    store = DeliveryStore(config.db_path, dedup_retention_hours=config.dedup_retention_hours)
    try:
        results = []
        if args.kind == "registration":
            event_type = data.get("eventType") or data.get("event_type") or ""
            refuse_non_completion(str(event_type))
            producer = args.producer or "auth-outbox"
            event = from_user_registered_envelope(data, config, producer=producer)
            results.append(store.enqueue(event))
        elif args.kind == "incident":
            producer = args.producer or "incident-adapter"
            event = from_incident_event(data, config, producer=producer)
            if event.correlation_key:
                store.remember_incident(
                    event.correlation_key,
                    event.event_id,
                    event.context.get("phase", "opened"),
                )
            results.append(store.enqueue(event))
        else:
            producer = args.producer or "backup-script"
            for event in from_backup_status(data, config, producer=producer):
                results.append(store.enqueue(event))
        print(json.dumps({"results": results}, indent=2))
        return 0
    finally:
        store.close()


if __name__ == "__main__":
    raise SystemExit(main())
