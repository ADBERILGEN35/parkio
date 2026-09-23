#!/usr/bin/env python3
"""Consume a real gateway-exported waitlist envelope through slack_biz to mock Slack."""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from pathlib import Path

_ROOT = Path(__file__).resolve().parent
if str(_ROOT.parent) not in sys.path:
    sys.path.insert(0, str(_ROOT.parent))

from slack_biz.config import load_config
from slack_biz.delivery import DeliveryWorker
from slack_biz.mock_slack import MockSlackServer
from slack_biz.store import DeliveryStore
from slack_biz.transport import SlackWebhookTransport
from slack_biz.waitlist_inbox import WaitlistInboxConsumer

PROHIBITED = (
    "pg.named@example.test",
    "pg.nameless@example.test",
    "198.51.100.40",
    "waitlist/confirm",
    "waitlist/unsubscribe",
)


def consume(envelope_path: Path) -> str:
    raw = json.loads(envelope_path.read_text(encoding="utf-8"))
    environment = raw["environment"]
    with tempfile.TemporaryDirectory(prefix="pr86-chain-") as tmp:
        tmp_path = Path(tmp)
        inbox = tmp_path / "inbox"
        inbox.mkdir()
        (inbox / envelope_path.name).write_bytes(envelope_path.read_bytes())
        mock = MockSlackServer()
        mock.start()
        os.environ.update(
            {
                "PARKIO_SLACK_BIZ_ENABLED": "true",
                "PARKIO_SLACK_BIZ_WEBHOOK_URL": mock.webhook_url,
                "PARKIO_SLACK_BIZ_DATA_DIR": str(tmp_path / "state"),
                "PARKIO_SLACK_BIZ_ENVIRONMENT": environment,
                "PARKIO_SLACK_BIZ_WAITLIST_INBOX": str(inbox),
                "PARKIO_SLACK_BIZ_MAX_ATTEMPTS": "3",
                "PARKIO_SLACK_BIZ_HTTP_TIMEOUT": "2",
            }
        )
        os.environ.pop("PARKIO_ENVIRONMENT", None)
        config = load_config()
        store = DeliveryStore(config.db_path, dedup_retention_hours=config.dedup_retention_hours)
        consumer = WaitlistInboxConsumer(config, store, inbox)
        worker = DeliveryWorker(
            config,
            store,
            SlackWebhookTransport(timeout_seconds=config.http_timeout_seconds),
            worker_id="pr86-chain",
            sleep_fn=lambda _s: None,
        )
        try:
            result = consumer.poll_once()
            if result.enqueued != 1 or result.rejected != 0:
                raise SystemExit("consume failed: %s" % (result,))
            worker.process_once()
            if len(mock.state.requests) != 1:
                raise SystemExit("expected 1 Slack post, got %s" % len(mock.state.requests))
            text = mock.state.requests[0].json_body["text"]
            for bad in PROHIBITED:
                if bad in text:
                    raise SystemExit("prohibited field leaked into Slack")
            return text
        finally:
            worker.close()
            store.close()
            mock.stop()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--envelope-dir", type=Path, required=True)
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args()
    named = args.envelope_dir / "named-v2.json"
    nameless = args.envelope_dir / "nameless-v2.json"
    named_text = consume(named)
    nameless_text = consume(nameless)
    named_env = json.loads(named.read_text(encoding="utf-8"))
    nameless_env = json.loads(nameless.read_text(encoding="utf-8"))
    if named_env.get("fullName") not in named_text:
        raise SystemExit("synthetic fullName missing from Slack body")
    if "Ad belirtilmemiş" in named_text:
        raise SystemExit("named envelope rendered fallback")
    if "Ad belirtilmemiş" not in nameless_text:
        raise SystemExit("nameless envelope lost fallback")
    if named_env.get("eventId") in named_text or named_env.get("dedupKey") in named_text:
        raise SystemExit("internal identifiers leaked")
    report = {
        "named_contract": named_env.get("contractVersion"),
        "named_fullName_type": type(named_env.get("fullName")).__name__,
        "named_fullName_present": bool(named_env.get("fullName")),
        "nameless_fullName": nameless_env.get("fullName"),
        "named_slack_has_name": named_env.get("fullName") in named_text,
        "nameless_slack_has_fallback": "Ad belirtilmemiş" in nameless_text,
        "named_preview": named_text,
        "nameless_preview": nameless_text,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.evidence:
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
