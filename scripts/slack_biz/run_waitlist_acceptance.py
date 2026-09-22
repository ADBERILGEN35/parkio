#!/usr/bin/env python3
"""Mock-Slack acceptance for waitlist subscription-confirmed notifications.

Synthetic envelopes in the exact shape written by gateway-service
(WaitlistOpsNotificationExporter) flow through the real file-inbox consumer,
durable SQLite queue, delivery worker and HTTP transport into a local mock
Slack receiver. Never targets real Slack; never sends email.

    python scripts/slack_biz/run_waitlist_acceptance.py [--evidence-dir DIR]
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import logging
import os
import sys
import tempfile
import traceback
import uuid
from contextlib import redirect_stdout
from pathlib import Path

_ROOT = Path(__file__).resolve().parent
if str(_ROOT.parent) not in sys.path:
    sys.path.insert(0, str(_ROOT.parent))

from slack_biz.adapters import (  # noqa: E402
    WAITLIST_PRODUCER,
    from_waitlist_ops_envelope,
    parse_and_enqueue_waitlist,
)
from slack_biz.config import load_config  # noqa: E402
from slack_biz.delivery import DeliveryWorker  # noqa: E402
from slack_biz.mock_slack import MockSlackServer, assert_no_real_slack_url  # noqa: E402
from slack_biz.registration_consumer import FileInboxRegistrationConsumer  # noqa: E402
from slack_biz.store import DeliveryStore  # noqa: E402
from slack_biz.templates import render_message  # noqa: E402
from slack_biz.transport import SlackWebhookTransport  # noqa: E402

ENV_NAME = "acceptance"

# Synthetic subscriber data that must NEVER reach Slack, logs or queue state.
SYNTHETIC_EMAIL = "sentetik.abone@example.test"
SYNTHETIC_NAME = "Sentetik Abone"
SYNTHETIC_IP = "198.51.100.77"
SYNTHETIC_TOKEN = "SyntheticVerificationToken-0123456789abcdefABCDEF"
SYNTHETIC_SUBSCRIBER_ID = "5b0c1d2e-3f40-4a5b-8c6d-7e8f90a1b2c3"
SYNTHETIC_CONFIRM_URL = "https://parkio.dev/waitlist/confirm/" + SYNTHETIC_TOKEN
SYNTHETIC_PROVIDER_PAYLOAD = "re_synthetic_provider_message_id"
PROHIBITED = (
    SYNTHETIC_EMAIL,
    SYNTHETIC_NAME,
    SYNTHETIC_IP,
    SYNTHETIC_TOKEN,
    SYNTHETIC_SUBSCRIBER_ID,
    "waitlist/confirm",
    "waitlist/unsubscribe",
    SYNTHETIC_PROVIDER_PAYLOAD,
)


def gateway_envelope(**overrides) -> dict:
    """Same keys/format as WaitlistOpsNotificationExporter#envelope."""
    digest = hashlib.sha256(uuid.uuid4().bytes).hexdigest()
    env = {
        "contractVersion": 1,
        "eventId": str(uuid.uuid4()),
        "eventType": "waitlist.subscription_confirmed",
        "occurredAt": "2026-09-22T11:04:05Z",
        "environment": ENV_NAME,
        "producer": WAITLIST_PRODUCER,
        "dedupKey": f"waitlist:subscription_confirmed:{digest}",
    }
    env.update(overrides)
    return env


class Harness:
    def __init__(self, root: Path, mock: MockSlackServer, *, enabled: bool = True):
        self.root = root
        self.mock = mock
        self.inbox = root / "inbox"
        self.data_dir = root / "state"
        os.environ.update(
            {
                "PARKIO_SLACK_BIZ_ENABLED": "true" if enabled else "false",
                "PARKIO_SLACK_BIZ_WEBHOOK_URL": mock.webhook_url,
                "PARKIO_SLACK_BIZ_DATA_DIR": str(self.data_dir),
                "PARKIO_SLACK_BIZ_ENVIRONMENT": ENV_NAME,
                "PARKIO_SLACK_BIZ_WAITLIST_INBOX": str(self.inbox),
                "PARKIO_SLACK_BIZ_MAX_ATTEMPTS": "3",
                "PARKIO_SLACK_BIZ_HTTP_TIMEOUT": "1",
                "PARKIO_SLACK_BIZ_AMBIGUOUS_RETRY_BASE": "0",
            }
        )
        os.environ.pop("PARKIO_ENVIRONMENT", None)
        self.config = load_config()
        self.store = DeliveryStore(
            self.config.db_path,
            dedup_retention_hours=self.config.dedup_retention_hours,
        )
        self.consumer = FileInboxRegistrationConsumer(
            self.config,
            self.store,
            self.inbox,
            producer=WAITLIST_PRODUCER,
            enqueue_fn=parse_and_enqueue_waitlist,
        )
        self.worker = DeliveryWorker(
            self.config,
            self.store,
            SlackWebhookTransport(timeout_seconds=self.config.http_timeout_seconds),
            worker_id="waitlist-acceptance",
            sleep_fn=lambda _s: None,
        )

    def drop(self, envelope: dict | str, name: str | None = None) -> Path:
        path = self.inbox / (name or f"waitlist-{uuid.uuid4()}.json")
        body = envelope if isinstance(envelope, str) else json.dumps(envelope)
        path.write_text(body, encoding="utf-8")
        return path

    def make_due(self) -> None:
        self.store._conn.execute(
            "UPDATE delivery_queue SET next_attempt_at=0 WHERE status IN ('queued','retry')"
        )
        self.store._conn.commit()

    def status_of(self, event_id: str) -> str | None:
        row = self.store.get_status(event_id)
        return row["status"] if row else None

    def state_dump(self) -> str:
        """Everything the relay persisted: queue, DLT, dedup, metrics, inbox side files."""
        parts = []
        for table in ("delivery_queue", "dlt", "dedup"):
            rows = self.store._conn.execute(f"SELECT * FROM {table}").fetchall()
            parts.extend(json.dumps(dict(r), default=str) for r in rows)
        parts.append(json.dumps(self.store.snapshot_metrics()))
        for p in self.inbox.rglob("*"):
            if p.is_file():
                parts.append(p.read_text(encoding="utf-8", errors="replace"))
        return "\n".join(parts)

    def close(self) -> None:
        self.worker.close()
        self.store.close()


def run(evidence_dir: Path | None) -> dict:
    results: list[dict] = []
    log_buffer = io.StringIO()
    handler = logging.StreamHandler(log_buffer)
    handler.setLevel(logging.DEBUG)
    logging.getLogger().addHandler(handler)
    logging.getLogger().setLevel(logging.DEBUG)
    stdout_buffer = io.StringIO()
    mock = MockSlackServer()
    mock.state.hang_seconds = 2.0
    mock.start()
    assert_no_real_slack_url(mock.webhook_url)
    saved_env = dict(os.environ)
    messages: dict[str, str] = {}

    def scenario(sid: str, name: str):
        def deco(fn):
            with tempfile.TemporaryDirectory(prefix="parkio-waitlist-slack-") as tmp:
                mock.state.clear()
                h = None
                try:
                    with redirect_stdout(stdout_buffer):
                        h = Harness(Path(tmp), mock, enabled=getattr(fn, "enabled", True))
                        detail = fn(h) or ""
                        dump = h.state_dump()
                    for text in [dump] + [r.body.decode("utf-8", "replace") for r in mock.state.requests]:
                        for bad in PROHIBITED:
                            assert bad not in text, f"prohibited value leaked into state/payload: {bad[:12]}..."
                    results.append({"id": sid, "name": name, "status": "PASS", "detail": detail})
                except Exception as exc:  # noqa: BLE001
                    results.append(
                        {
                            "id": sid,
                            "name": name,
                            "status": "FAIL",
                            "detail": f"{type(exc).__name__}: {exc}",
                            "trace": traceback.format_exc()[-1500:],
                        }
                    )
                finally:
                    if h is not None:
                        h.close()
                    os.environ.clear()
                    os.environ.update(saved_env)
            return fn

        return deco

    def disabled(fn):
        fn.enabled = False
        return fn

    @scenario("W01", "committed confirmation → one Turkish Slack message")
    def _w01(h: Harness):
        env = gateway_envelope()
        h.drop(env)
        r = h.consumer.poll_once()
        assert r.enqueued == 1 and r.acked == 1, r
        h.worker.process_once()
        assert h.status_of(env["eventId"]) == "delivered"
        assert len(mock.state.requests) == 1
        body = mock.state.requests[0].json_body
        assert set(body) == {"text", "username", "mrkdwn"}, body
        text = body["text"]
        assert "Bekleme listesi aboneliği onaylandı" in text
        assert "type=`waitlist.subscription_confirmed`" in text
        assert f"env=`{ENV_NAME}`" in text and "at=`2026-09-22T11:04:05Z`" in text
        assert env["dedupKey"].split(":")[-1] not in text, "dedup hash must not reach Slack"
        assert env["eventId"] not in text
        messages["confirmed"] = text
        return "delivered=1 posts=1"

    @scenario("W02", "rolled-back confirmation → no envelope → nothing sent")
    def _w02(h: Harness):
        # Gateway contract: rollback leaves no outbox row, hence no inbox file.
        r = h.consumer.poll_once()
        h.worker.process_once()
        assert r.processed == 0 and not mock.state.requests
        return "gateway rollback is proven in WaitlistOpsNotificationOutboxTest; relay idle"

    @scenario("W03", "duplicate envelope (re-export after crash) → suppressed")
    def _w03(h: Harness):
        env = gateway_envelope()
        h.drop(env, "waitlist-a.json")
        h.consumer.poll_once()
        h.worker.process_once()
        dup = dict(env, eventId=str(uuid.uuid4()))  # same dedupKey, new file
        h.drop(env, "waitlist-a-again.json")
        h.drop(dup, "waitlist-b.json")
        r = h.consumer.poll_once()
        h.worker.process_once()
        assert r.suppressed == 2 and r.enqueued == 0, r
        assert len(mock.state.requests) == 1
        return "posts=1 suppressed=2"

    @scenario("W04", "integration disabled → queued, nothing sent")
    @disabled
    def _w04(h: Harness):
        env = gateway_envelope()
        h.drop(env)
        h.consumer.poll_once()
        stats = h.worker.process_once()
        assert stats.skipped_disabled == 1
        assert not mock.state.requests
        assert h.status_of(env["eventId"]) == "queued"
        return "posts=0 pending=1"

    @scenario("W05", "timeout (ambiguous) → bounded retry, never confirmed early")
    def _w05(h: Harness):
        env = gateway_envelope()
        h.drop(env)
        h.consumer.poll_once()
        mock.enqueue_response(200, mode="hang")
        s1 = h.worker.process_once()
        assert s1.ambiguous_retried == 1, s1
        assert h.status_of(env["eventId"]) == "retry"
        import time as _t

        _t.sleep(mock.state.hang_seconds + 0.2)  # let the stalled handler finish
        h.make_due()
        h.worker.process_once()
        assert h.status_of(env["eventId"]) == "delivered"
        return "ambiguous→retry→delivered (duplicate risk documented)"

    @scenario("W06", "timeouts exhausted → delivery_unknown (operator-visible)")
    def _w06(h: Harness):
        env = gateway_envelope()
        h.drop(env)
        h.consumer.poll_once()
        import time as _t

        for _ in range(3):
            mock.enqueue_response(200, mode="hang")
            h.worker.process_once()
            _t.sleep(mock.state.hang_seconds + 0.2)
            h.make_due()
        assert h.status_of(env["eventId"]) == "delivery_unknown"
        h.worker.process_once()
        assert len(mock.state.requests) == 3
        return "attempts=3 → delivery_unknown, no further sends"

    @scenario("W07", "429 honours Retry-After, then delivers")
    def _w07(h: Harness):
        env = gateway_envelope()
        h.drop(env)
        h.consumer.poll_once()
        mock.enqueue_response(429, "rate_limited", {"Retry-After": "7"})
        h.worker.process_once()
        row = h.store.get_status(env["eventId"])
        assert row["status"] == "retry"
        import time as _t

        delay = row["next_attempt_at"] - _t.time()
        assert 5.5 <= delay <= 8.0, delay
        h.worker.process_once()  # not yet due → no send
        assert len(mock.state.requests) == 1
        h.make_due()
        h.worker.process_once()
        assert h.status_of(env["eventId"]) == "delivered"
        return f"retry_after≈{delay:.1f}s then delivered"

    @scenario("W08", "5xx transient → retry then success")
    def _w08(h: Harness):
        env = gateway_envelope()
        h.drop(env)
        h.consumer.poll_once()
        mock.enqueue_response(503, "unavailable")
        h.worker.process_once()
        assert h.status_of(env["eventId"]) == "retry"
        h.make_due()
        h.worker.process_once()
        assert h.status_of(env["eventId"]) == "delivered"
        return "503→retry→delivered"

    @scenario("W09", "5xx persistent → bounded attempts → dead (DLT)")
    def _w09(h: Harness):
        env = gateway_envelope()
        h.drop(env)
        h.consumer.poll_once()
        for _ in range(5):
            mock.enqueue_response(500, "boom")
        for _ in range(5):
            h.worker.process_once()
            h.make_due()
        assert h.status_of(env["eventId"]) == "dead"
        assert len(mock.state.requests) == 3, len(mock.state.requests)
        return "max_attempts=3 → dead"

    @scenario("W10", "permanent failure (404 no_service / 400) → dead, no retry")
    def _w10(h: Harness):
        a, b = gateway_envelope(), gateway_envelope()
        h.drop(a)
        h.drop(b)
        h.consumer.poll_once()
        mock.enqueue_response(404, "no_service")
        mock.enqueue_response(400, "invalid_payload")
        h.worker.process_once()
        h.make_due()
        h.worker.process_once()
        assert h.status_of(a["eventId"]) == "dead" and h.status_of(b["eventId"]) == "dead"
        assert len(mock.state.requests) == 2
        return "posts=2 dead=2 retries=0"

    @scenario("W11", "envelope carrying prohibited fields → rejected, nothing sent")
    def _w11(h: Harness):
        bad = [
            gateway_envelope(email=SYNTHETIC_EMAIL),
            gateway_envelope(name=SYNTHETIC_NAME),
            gateway_envelope(ip=SYNTHETIC_IP),
            gateway_envelope(verificationToken=SYNTHETIC_TOKEN),
            gateway_envelope(confirmUrl=SYNTHETIC_CONFIRM_URL),
            gateway_envelope(subscriberId=SYNTHETIC_SUBSCRIBER_ID),
            gateway_envelope(providerPayload={"id": SYNTHETIC_PROVIDER_PAYLOAD}),
            gateway_envelope(webhook_url="https://hooks.slack.com/services/X/Y/Z"),
        ]
        for i, env in enumerate(bad):
            h.drop(env, f"bad-{i}.json")
        r = h.consumer.poll_once()
        assert r.invalid == len(bad) and r.enqueued == 0, r
        h.worker.process_once()
        assert not mock.state.requests
        # Rejected originals are quarantined as-is for operators, outside the
        # scanned state: remove them before the leak scan of relay state.
        for p in (h.inbox / ".invalid").glob("bad-*.json"):
            p.unlink()
        return f"rejected={r.invalid}"

    @scenario("W12", "non-confirmation signals / env mismatch / bad shape → rejected")
    def _w12(h: Harness):
        cases = [
            gateway_envelope(eventType="waitlist.subscription_submitted"),
            gateway_envelope(eventType="waitlist.email_accepted"),
            gateway_envelope(eventType="UserRegistered"),
            gateway_envelope(environment="production"),
            gateway_envelope(producer="auth-outbox"),
            gateway_envelope(dedupKey="waitlist:subscription_confirmed:not-a-hash"),
            gateway_envelope(occurredAt="yesterday"),
            "{not json",
        ]
        for i, env in enumerate(cases):
            h.drop(env, f"case-{i}.json")
        r = h.consumer.poll_once()
        assert r.invalid == len(cases) and r.enqueued == 0, r
        assert not mock.state.requests
        return f"rejected={r.invalid}"

    @scenario("W13", "webhook URL and secrets never logged or persisted")
    def _w13(h: Harness):
        env = gateway_envelope()
        h.drop(env)
        h.consumer.poll_once()
        mock.enqueue_response(403, "invalid_token")
        h.worker.process_once()
        dump = h.state_dump()
        logs = log_buffer.getvalue() + stdout_buffer.getvalue()
        for text in (dump, logs):
            assert mock.webhook_url not in text
            assert "/services/" not in text
        return "webhook URL absent from queue/DLT/metrics/logs"

    logging.getLogger().removeHandler(handler)
    mock.stop()

    all_logs = log_buffer.getvalue() + stdout_buffer.getvalue()
    leaks = [p[:12] for p in PROHIBITED if p in all_logs]
    results.append(
        {
            "id": "W14",
            "name": "captured relay logs contain no prohibited values",
            "status": "PASS" if not leaks else "FAIL",
            "detail": "log_bytes=%d" % len(all_logs) if not leaks else f"leaks={leaks}",
        }
    )

    # Turkish example messages rendered from synthetic data with production-like env.
    example_cfg = load_config({"PARKIO_SLACK_BIZ_ENVIRONMENT": "production"})
    example = from_waitlist_ops_envelope(
        gateway_envelope(environment="production"), example_cfg
    )
    messages["confirmed_production_example"] = render_message(example, example_cfg)

    summary = {"PASS": 0, "FAIL": 0}
    for r in results:
        summary[r["status"]] = summary.get(r["status"], 0) + 1
    report = {"suite": "waitlist-slack-notifications", "summary": summary, "results": results,
              "example_messages": messages}
    if evidence_dir:
        evidence_dir.mkdir(parents=True, exist_ok=True)
        (evidence_dir / "waitlist-acceptance.json").write_text(
            json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-dir", type=Path)
    args = parser.parse_args(argv)
    report = run(args.evidence_dir)
    for r in report["results"]:
        print(f"{r['id']} {r['status']:4} {r['name']} — {r['detail']}")
        if r["status"] == "FAIL" and r.get("trace"):
            print(r["trace"])
    print(json.dumps(report["summary"]))
    print("--- example message (synthetic) ---")
    print(report["example_messages"].get("confirmed_production_example", ""))
    return 0 if report["summary"].get("FAIL", 0) == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
