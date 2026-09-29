#!/usr/bin/env python3
"""Y03A fault-injection + registration-adapter reliability acceptance."""

from __future__ import annotations

import json
import os
import random
import sys
import tempfile
import traceback
import uuid
from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path

_ROOT = Path(__file__).resolve().parent
if str(_ROOT.parent) not in sys.path:
    sys.path.insert(0, str(_ROOT.parent))

from slack_biz.adapters import from_user_registered_envelope, refuse_non_completion  # noqa: E402
from slack_biz.config import load_config  # noqa: E402
from slack_biz.delivery import DeliveryWorker  # noqa: E402
from slack_biz.mock_slack import MockSlackServer, assert_no_real_slack_url  # noqa: E402
from slack_biz.registration_consumer import (  # noqa: E402
    FileInboxRegistrationConsumer,
    adapter_implementation_status,
    parse_and_enqueue,
)
from slack_biz.safety import sanitize_text  # noqa: E402
from slack_biz.store import (  # noqa: E402
    STATUS_DELIVERED,
    STATUS_DELIVERY_UNKNOWN,
    STATUS_DEAD,
    STATUS_IN_FLIGHT,
    STATUS_RETRY,
    DeliveryStore,
    WorkerLockError,
)
from slack_biz.transport import SlackWebhookTransport  # noqa: E402


@dataclass
class ScenarioResult:
    id: str
    name: str
    status: str
    detail: str = ""


@dataclass
class SuiteReport:
    results: list[ScenarioResult] = field(default_factory=list)

    def add(self, r: ScenarioResult) -> None:
        self.results.append(r)

    def summary(self) -> dict:
        c = {"PASS": 0, "FAIL": 0, "NOT_EXECUTED": 0}
        for r in self.results:
            c[r.status] = c.get(r.status, 0) + 1
        return c


def _envelope(event_id: str | None = None) -> dict:
    eid = event_id or str(uuid.uuid4())
    uid = str(uuid.uuid4())
    return {
        "eventId": eid,
        "eventType": "UserRegistered",
        "aggregateType": "AuthUser",
        "aggregateId": uid,
        "occurredAt": "2026-09-20T14:00:00Z",
        "version": 1,
        "payload": {
            "eventId": eid,
            "userId": uid,
            "email": "leak@parkio.example",
            "occurredAt": "2026-09-20T14:00:00Z",
        },
    }


def _env(data_dir: Path, webhook: str, **extra) -> dict[str, str]:
    base = {
        "PARKIO_SLACK_BIZ_ENABLED": "true",
        "PARKIO_SLACK_BIZ_WEBHOOK_URL": webhook,
        "PARKIO_SLACK_BIZ_DATA_DIR": str(data_dir),
        "PARKIO_SLACK_BIZ_ENVIRONMENT": "y03a-acceptance",
        "PARKIO_SLACK_BIZ_MAX_ATTEMPTS": "3",
        "PARKIO_SLACK_BIZ_HTTP_TIMEOUT": "1",
        "PARKIO_SLACK_BIZ_DEDUP_RETENTION_HOURS": "168",
        "PARKIO_SLACK_BIZ_LEASE_SECONDS": "2",
        "PARKIO_SLACK_BIZ_WORKER_STALE_SECONDS": "1",
        "PARKIO_SLACK_BIZ_AMBIGUOUS_RETRY_BASE": "0",
        "PARKIO_SLACK_BIZ_TRUSTED_PRODUCERS": (
            "auth-outbox,backup-script,incident-adapter,acceptance-harness"
        ),
        "PARKIO_SLACK_BIZ_FORBID_ALERTMANAGER_WEBHOOK": "1",
        "PARKIO_ALERT_SLACK_WEBHOOK_URL": "https://hooks.slack.com/services/PROD/NO",
        "PARKIO_SLACK_BIZ_KAFKA_AUTO_OFFSET_RESET": "latest",
    }
    base.update({k: str(v) for k, v in extra.items()})
    return base


def _force_due(store: DeliveryStore) -> None:
    with store._lock:
        store._conn.execute(
            "UPDATE delivery_queue SET next_attempt_at=0 WHERE status IN ('queued','retry')"
        )
        store._conn.commit()


@contextmanager
def fresh_stack(tmp: Path, mock: MockSlackServer, name: str):
    data_dir = tmp / name
    env = _env(data_dir, mock.webhook_url)
    old = {k: os.environ.get(k) for k in list(os.environ) if k.startswith("PARKIO_")}
    for k in list(os.environ):
        if k.startswith("PARKIO_"):
            del os.environ[k]
    os.environ.update(env)
    config = load_config()
    store = DeliveryStore(
        config.db_path,
        dedup_retention_hours=config.dedup_retention_hours,
        lease_seconds=config.lease_seconds,
        worker_stale_seconds=config.worker_stale_seconds,
    )
    transport = SlackWebhookTransport(timeout_seconds=config.http_timeout_seconds)
    workers: list[DeliveryWorker] = []

    def make_worker(wid: str = "w1") -> DeliveryWorker:
        w = DeliveryWorker(
            config, store, transport, worker_id=wid, rng=random.Random(0), acquire_lock=True
        )
        workers.append(w)
        return w

    try:
        yield config, store, transport, make_worker, env
    finally:
        for w in workers:
            try:
                w.close()
            except Exception:
                pass
        store.close()
        for k in list(os.environ):
            if k.startswith("PARKIO_"):
                del os.environ[k]
        for k, v in old.items():
            if v is not None:
                os.environ[k] = v


def run_suite() -> SuiteReport:
    report = SuiteReport()
    mock = MockSlackServer()
    mock.start()
    assert_no_real_slack_url(mock.webhook_url)

    with tempfile.TemporaryDirectory(prefix="parkio-y03a-") as tmp_s:
        tmp = Path(tmp_s)

        # 1. Registration commit vs rollback
        try:
            with fresh_stack(tmp, mock, "s1") as (config, store, transport, make_worker, env):
                mock.state.clear()
                inbox = tmp / "s1-inbox"
                consumer = FileInboxRegistrationConsumer(config, store, inbox)
                eid = str(uuid.uuid4())
                path = inbox / f"{eid}.json"
                path.write_text(json.dumps(_envelope(eid)), encoding="utf-8")
                cr = consumer.poll_once()
                assert cr.enqueued == 1 and cr.acked == 1
                for bad in ("RegistrationAttempted", "RegistrationFailed"):
                    try:
                        refuse_non_completion(bad)
                        raise AssertionError("expected refuse")
                    except ValueError:
                        pass
                bad_path = inbox / "bad.json"
                bad_path.write_text(
                    json.dumps({**_envelope(), "eventType": "RegistrationFailed"}),
                    encoding="utf-8",
                )
                cr2 = consumer.poll_once()
                assert cr2.invalid >= 1
                w = make_worker("w-reg")
                _force_due(store)
                w.process_once()
                texts = [(r.json_body or {}).get("text", "") for r in mock.state.requests]
                assert any("Registration completed" in t for t in texts)
                assert all("leak@" not in t for t in texts)
            report.add(ScenarioResult("1", "registration_commit_vs_rollback", "PASS"))
        except Exception as exc:
            report.add(
                ScenarioResult(
                    "1",
                    "registration_commit_vs_rollback",
                    "FAIL",
                    f"{exc}\n{traceback.format_exc()}",
                )
            )

        # 2. Crash after enqueue before ack
        try:
            with fresh_stack(tmp, mock, "s2") as (config, store, transport, make_worker, env):
                inbox = tmp / "s2-inbox"
                eid = str(uuid.uuid4())
                env_path = inbox / f"{eid}.json"
                inbox.mkdir(parents=True, exist_ok=True)
                env_path.write_text(json.dumps(_envelope(eid)), encoding="utf-8")
                outcome = parse_and_enqueue(
                    json.loads(env_path.read_text(encoding="utf-8")), config, store
                )
                assert outcome == "queued" and env_path.exists()
                cr = FileInboxRegistrationConsumer(config, store, inbox).poll_once()
                assert cr.suppressed >= 1 and cr.acked >= 1
            report.add(ScenarioResult("2", "crash_after_enqueue_before_ack", "PASS"))
        except Exception as exc:
            report.add(ScenarioResult("2", "crash_after_enqueue_before_ack", "FAIL", str(exc)))

        # 3. Queue write failure before ack
        try:
            with fresh_stack(tmp, mock, "s3") as (config, store, transport, make_worker, env):

                class BoomStore(DeliveryStore):
                    def enqueue(self, event):  # type: ignore[override]
                        raise OSError("queue_write_failed:disk_full")

                boom = BoomStore(tmp / "boom.sqlite3")
                fail_inbox = tmp / "fail-inbox"
                fc = FileInboxRegistrationConsumer(config, boom, fail_inbox)
                p = fail_inbox / "x.json"
                p.write_text(json.dumps(_envelope()), encoding="utf-8")
                cr = fc.poll_once()
                assert cr.write_failures >= 1 and cr.acked == 0 and p.exists()
                boom.close()
            report.add(ScenarioResult("3", "queue_write_fail_no_ack", "PASS"))
        except Exception as exc:
            report.add(ScenarioResult("3", "queue_write_fail_no_ack", "FAIL", str(exc)))

        # 4. Single worker enforced
        try:
            with fresh_stack(tmp, mock, "s4") as (config, store, transport, make_worker, env):
                w1 = make_worker("exclusive-1")
                try:
                    make_worker("exclusive-2")
                    raise AssertionError("second worker should be rejected")
                except WorkerLockError:
                    pass
                w1.close()
                workers_cleared = True
                # stale lock steal after heartbeat expiry
                import time as _t

                _t.sleep(1.2)
                w2 = make_worker("exclusive-2b")
                assert w2 is not None
            report.add(ScenarioResult("4", "single_worker_enforced", "PASS"))
        except Exception as exc:
            report.add(ScenarioResult("4", "single_worker_enforced", "FAIL", str(exc)))

        # 5. Crash after claim → lease reclaim
        try:
            with fresh_stack(tmp, mock, "s5") as (config, store, transport, make_worker, env):
                mock.state.clear()
                ev = from_user_registered_envelope(_envelope(), config)
                assert store.enqueue(ev) == "queued"
                items = store.claim_batch(worker_id="crash-claim", limit=10)
                assert len(items) == 1 and items[0].event.event_id == ev.event_id
                assert items[0].status == STATUS_IN_FLIGHT
                with store._lock:
                    store._conn.execute(
                        "UPDATE delivery_queue SET lease_until=0 WHERE event_id=?",
                        (ev.event_id,),
                    )
                    store._conn.commit()
                n = store.reclaim_expired_leases()
                assert n == 1
                st = store.get_status(ev.event_id)
                assert st and st["status"] == STATUS_RETRY
                w = make_worker("recover-claim")
                _force_due(store)
                mock.enqueue_response(200, "ok")
                w.process_once()
                assert store.get_status(ev.event_id)["status"] == STATUS_DELIVERED
            report.add(ScenarioResult("5", "crash_after_claim_reclaim", "PASS"))
        except Exception as exc:
            report.add(
                ScenarioResult(
                    "5", "crash_after_claim_reclaim", "FAIL", f"{exc}\n{traceback.format_exc()}"
                )
            )

        # 6. Accept then drop → ambiguous retry (not confirmed delivery)
        try:
            with fresh_stack(tmp, mock, "s6") as (config, store, transport, make_worker, env):
                mock.state.clear()
                mock.enqueue_drop_connection()
                mock.enqueue_response(200, "ok")
                ev = from_user_registered_envelope(_envelope(), config)
                assert store.enqueue(ev) == "queued"
                w = make_worker("amb-1")
                _force_due(store)
                w.process_once()
                st = store.get_status(ev.event_id)
                assert st["status"] == STATUS_RETRY
                assert "ambiguous" in (st["last_error"] or "")
                assert st["status"] != STATUS_DELIVERED
                _force_due(store)
                w.process_once()
                assert store.get_status(ev.event_id)["status"] == STATUS_DELIVERED
                assert len(mock.state.requests) >= 1
            report.add(ScenarioResult("6", "accept_then_drop_ambiguous_retry", "PASS"))
        except Exception as exc:
            report.add(
                ScenarioResult(
                    "6",
                    "accept_then_drop_ambiguous_retry",
                    "FAIL",
                    f"{exc}\n{traceback.format_exc()}",
                )
            )

        # 7. Reject / exhaust → dead, not success
        try:
            with fresh_stack(tmp, mock, "s7") as (config, store, transport, make_worker, env):
                mock.state.clear()
                for _ in range(5):
                    mock.enqueue_response(503, "down")
                ev = from_user_registered_envelope(_envelope(), config)
                store.enqueue(ev)
                w = make_worker("rej-1")
                for _ in range(5):
                    _force_due(store)
                    w.process_once()
                st = store.get_status(ev.event_id)
                assert st and st["status"] == STATUS_DEAD
            report.add(ScenarioResult("7", "reject_timeout_not_success", "PASS"))
        except Exception as exc:
            report.add(ScenarioResult("7", "reject_timeout_not_success", "FAIL", str(exc)))

        # 8. Restart preserves pending / unknown / dead
        try:
            with fresh_stack(tmp, mock, "s8") as (config, store, transport, make_worker, env):
                pend = from_user_registered_envelope(_envelope(), config)
                store.enqueue(pend)
                unk = from_user_registered_envelope(_envelope(), config)
                store.enqueue(unk)
                dead = from_user_registered_envelope(_envelope(), config)
                store.enqueue(dead)
                items = store.claim_batch(worker_id="seed", limit=10)
                by_id = {i.event.event_id: i for i in items}
                store.mark_delivery_unknown(by_id[unk.event_id], reason="test_seed")
                store.mark_dead(by_id[dead.event_id], reason="test_dead")
                # leave pend as in_flight → reclaim to pending via expire
                with store._lock:
                    store._conn.execute(
                        "UPDATE delivery_queue SET lease_until=0 WHERE event_id=?",
                        (pend.event_id,),
                    )
                    store._conn.commit()
                store.reclaim_expired_leases()
                db_path = config.db_path
                store.close()
                store2 = DeliveryStore(
                    db_path,
                    dedup_retention_hours=config.dedup_retention_hours,
                    lease_seconds=config.lease_seconds,
                    worker_stale_seconds=config.worker_stale_seconds,
                )
                assert store2.pending_count() >= 1
                assert store2.count_by_status(STATUS_DELIVERY_UNKNOWN) >= 1
                assert store2.count_by_status(STATUS_DEAD) >= 1
                assert (
                    store2.resolve_unknown(
                        unk.event_id, resolution="accept_as_delivered", operator="y03a"
                    )
                    == "accepted"
                )
                store2.close()
            report.add(ScenarioResult("8", "restart_preserves_states", "PASS"))
        except Exception as exc:
            report.add(
                ScenarioResult(
                    "8", "restart_preserves_states", "FAIL", f"{exc}\n{traceback.format_exc()}"
                )
            )

        # 9. Replay inside retention
        try:
            with fresh_stack(tmp, mock, "s9") as (config, store, transport, make_worker, env):
                eid = str(uuid.uuid4())
                assert store.enqueue(from_user_registered_envelope(_envelope(eid), config)) == "queued"
                r2 = store.enqueue(from_user_registered_envelope(_envelope(eid), config))
                assert r2 == "duplicate_suppressed"
            report.add(ScenarioResult("9", "replay_inside_retention", "PASS"))
        except Exception as exc:
            report.add(ScenarioResult("9", "replay_inside_retention", "FAIL", str(exc)))

        # 10. Replay outside retention → new admission (documented duplicate risk)
        try:
            with fresh_stack(tmp, mock, "s10") as (config, store, transport, make_worker, env):
                mock.state.clear()
                eid = str(uuid.uuid4())
                ev = from_user_registered_envelope(_envelope(eid), config)
                assert store.enqueue(ev) == "queued"
                w = make_worker("out-ret")
                _force_due(store)
                mock.enqueue_response(200, "ok")
                w.process_once()
                assert store.get_status(ev.event_id)["status"] == STATUS_DELIVERED
                store.force_expire_dedup(ev.dedup_key)
                again = store.enqueue(from_user_registered_envelope(_envelope(eid), config))
                assert again == "queued"
            report.add(ScenarioResult("10", "replay_outside_retention", "PASS"))
        except Exception as exc:
            report.add(
                ScenarioResult(
                    "10", "replay_outside_retention", "FAIL", f"{exc}\n{traceback.format_exc()}"
                )
            )

        # 11. Disabled — no outbound
        try:
            with fresh_stack(tmp, mock, "s11") as (config, store, transport, make_worker, env):
                mock.state.clear()
                before = len(mock.state.requests)
                disabled = load_config({**env, "PARKIO_SLACK_BIZ_ENABLED": "false"})
                ev = from_user_registered_envelope(_envelope(), config)
                store.enqueue(ev)
                dw = DeliveryWorker(
                    disabled, store, transport, worker_id="off", acquire_lock=True
                )
                try:
                    dw.process_once()
                    assert len(mock.state.requests) == before
                    assert store.pending_count() >= 1
                    assert adapter_implementation_status(config)["default_offset_reset"] == "latest"
                finally:
                    dw.close()
            report.add(ScenarioResult("11", "disabled_no_outbound", "PASS"))
        except Exception as exc:
            report.add(ScenarioResult("11", "disabled_no_outbound", "FAIL", str(exc)))

        # 12. Sensitive / mentions
        try:
            text = sanitize_text("hi @channel <!here> user@x.com")
            assert "user@x.com" not in text
            blob = json.dumps(
                from_user_registered_envelope(
                    _envelope(),
                    load_config(
                        _env(tmp / "s12", mock.webhook_url)
                    ),
                ).to_dict()
            )
            assert "leak@" not in blob and "email" not in blob
            report.add(ScenarioResult("12", "sensitive_mention_safety", "PASS"))
        except Exception as exc:
            report.add(ScenarioResult("12", "sensitive_mention_safety", "FAIL", str(exc)))

        # 13. Ambiguous exhaust → delivery_unknown (never confirmed delivered)
        try:
            with fresh_stack(tmp, mock, "s13") as (config, store, transport, make_worker, env):
                mock.state.clear()
                for _ in range(6):
                    mock.enqueue_drop_connection()
                ev = from_user_registered_envelope(_envelope(), config)
                store.enqueue(ev)
                w = make_worker("amb-ex")
                for _ in range(6):
                    _force_due(store)
                    w.process_once()
                st = store.get_status(ev.event_id)
                assert st and st["status"] == STATUS_DELIVERY_UNKNOWN
                assert (
                    store.enqueue(
                        from_user_registered_envelope(_envelope(ev.event_id), config)
                    )
                    == "already_uncertain"
                )
            report.add(ScenarioResult("13", "ambiguous_exhaust_delivery_unknown", "PASS"))
        except Exception as exc:
            report.add(
                ScenarioResult(
                    "13",
                    "ambiguous_exhaust_delivery_unknown",
                    "FAIL",
                    f"{exc}\n{traceback.format_exc()}",
                )
            )

        # 14. Live Kafka
        st = adapter_implementation_status(
            load_config(_env(tmp / "k", "http://127.0.0.1:9/x"))
        )
        report.add(
            ScenarioResult(
                "14",
                "kafka_live_consumer",
                "NOT_EXECUTED",
                st["kafka_live_consumer"],
            )
        )

    mock.stop()
    return report


def main() -> int:
    report = run_suite()
    summary = report.summary()
    print("=== PARKIO-Y03A Slack delivery reliability acceptance ===")
    for r in report.results:
        extra = f" — {r.detail}" if r.detail and r.status != "PASS" else ""
        print(f"[{r.status}] {r.id}. {r.name}{extra}")
    print("---")
    print(json.dumps(summary))
    evidence = os.environ.get("PARKIO_Y03A_EVIDENCE_DIR")
    if evidence:
        out = Path(evidence)
        out.mkdir(parents=True, exist_ok=True)
        (out / "fault-injection-results.json").write_text(
            json.dumps(
                {
                    "summary": summary,
                    "results": [r.__dict__ for r in report.results],
                    "real_slack_delivery": "NOT_EXECUTED",
                    "slack_messages_sent": 0,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
    return 0 if summary.get("FAIL", 0) == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
