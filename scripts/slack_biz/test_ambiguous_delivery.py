#!/usr/bin/env python3
"""U07: ambiguous-response, crash/restart and lease-reclaim regressions.

Everything here is local: a raw-socket fake Slack endpoint on 127.0.0.1,
synthetic UserRegistered envelopes, and throwaway SQLite state. Crash/restart
cases run the real ``worker.py --once`` entry point as a subprocess so a
process death is a real process death, not a mocked one.

Run: python -m unittest discover -s scripts/slack_biz -p 'test_*.py'
"""

from __future__ import annotations

import os
import random
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path

_ROOT = Path(__file__).resolve().parent
if str(_ROOT.parent) not in sys.path:
    sys.path.insert(0, str(_ROOT.parent))

from slack_biz.adapters import from_user_registered_envelope  # noqa: E402
from slack_biz.config import load_config  # noqa: E402
from slack_biz.delivery import DeliveryWorker  # noqa: E402
from slack_biz.store import (  # noqa: E402
    STATUS_DEAD,
    STATUS_DELIVERED,
    STATUS_DELIVERY_UNKNOWN,
    STATUS_IN_FLIGHT,
    STATUS_QUEUED,
    STATUS_RETRY,
    DeliveryStore,
)
from slack_biz.transport import SlackWebhookTransport, TransportClass  # noqa: E402

MAX_ATTEMPTS = 3


class FakeSlackEndpoint:
    """Raw TCP fake webhook. Each request consumes the next scripted mode.

    Modes: ok | malformed (non-HTTP status line, i.e. http.client.BadStatusLine)
    | hang (read the request, never answer) | http_503.
    Every request is recorded *after* its body was fully read, i.e. at the
    point where a real Slack could already have posted the message.
    """

    def __init__(self, default: str = "ok"):
        self.default = default
        self.script: list[str] = []
        self.bodies: list[bytes] = []
        self._lock = threading.Lock()
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind(("127.0.0.1", 0))
        self._sock.listen(16)
        self.port = self._sock.getsockname()[1]
        self._stop = threading.Event()
        self._hung: list[socket.socket] = []
        self._thread = threading.Thread(target=self._serve, daemon=True)
        self._thread.start()

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}/services/fake/u07"

    @property
    def request_count(self) -> int:
        with self._lock:
            return len(self.bodies)

    def wait_for_requests(self, n: int, timeout: float = 10.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.request_count >= n:
                return True
            time.sleep(0.02)
        return False

    def close(self) -> None:
        self._stop.set()
        try:
            self._sock.close()
        except OSError:
            pass
        for c in self._hung:
            try:
                c.close()
            except OSError:
                pass

    def _serve(self) -> None:
        while not self._stop.is_set():
            try:
                conn, _ = self._sock.accept()
            except OSError:
                return
            threading.Thread(target=self._handle, args=(conn,), daemon=True).start()

    def _handle(self, conn: socket.socket) -> None:
        data = b""
        try:
            while b"\r\n\r\n" not in data:
                chunk = conn.recv(4096)
                if not chunk:
                    conn.close()
                    return
                data += chunk
            head, _, body = data.partition(b"\r\n\r\n")
            length = 0
            for line in head.split(b"\r\n")[1:]:
                k, _, v = line.partition(b":")
                if k.strip().lower() == b"content-length":
                    length = int(v.strip())
            while len(body) < length:
                chunk = conn.recv(4096)
                if not chunk:
                    break
                body += chunk
            with self._lock:
                self.bodies.append(body)
                mode = self.script.pop(0) if self.script else self.default
            if mode == "ok":
                conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
            elif mode == "malformed":
                conn.sendall(b"SLACK-GARBAGE \x00\x01 not http\r\n\r\n")
            elif mode == "http_503":
                conn.sendall(
                    b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 4\r\n"
                    b"Connection: close\r\n\r\ndown"
                )
            elif mode == "hang":
                self._hung.append(conn)
                return
            conn.close()
        except OSError:
            pass


def _envelope() -> dict:
    eid = str(uuid.uuid4())
    uid = str(uuid.uuid4())
    return {
        "eventId": eid,
        "eventType": "UserRegistered",
        "aggregateType": "AuthUser",
        "aggregateId": uid,
        "occurredAt": "2026-09-29T09:00:00Z",
        "version": 1,
        "payload": {
            "eventId": eid,
            "userId": uid,
            "email": "synthetic-u07@parkio.example",
            "occurredAt": "2026-09-29T09:00:00Z",
        },
    }


def _env(data_dir: Path, webhook: str, **extra) -> dict[str, str]:
    env = {
        "PARKIO_SLACK_BIZ_ENABLED": "true",
        "PARKIO_SLACK_BIZ_WEBHOOK_URL": webhook,
        "PARKIO_SLACK_BIZ_DATA_DIR": str(data_dir),
        "PARKIO_SLACK_BIZ_ENVIRONMENT": "u07-regression",
        "PARKIO_SLACK_BIZ_MAX_ATTEMPTS": str(MAX_ATTEMPTS),
        "PARKIO_SLACK_BIZ_HTTP_TIMEOUT": "0.5",
        "PARKIO_SLACK_BIZ_LEASE_SECONDS": "1",
        "PARKIO_SLACK_BIZ_WORKER_STALE_SECONDS": "1",
        "PARKIO_SLACK_BIZ_AMBIGUOUS_RETRY_BASE": "0",
        "PARKIO_SLACK_BIZ_TRUSTED_PRODUCERS": "acceptance-harness,auth-outbox",
        "PARKIO_SLACK_BIZ_FORBID_ALERTMANAGER_WEBHOOK": "1",
        "PARKIO_ALERT_SLACK_WEBHOOK_URL": "",
    }
    env.update({k: str(v) for k, v in extra.items()})
    return env


class RelayCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="u07-")
        self.tmp = Path(self._tmp.name)
        self.slack = FakeSlackEndpoint()
        self.env = _env(self.tmp / "state", self.slack.url)
        self.config = load_config(self.env)
        self.store = self._open_store()
        self.workers: list[DeliveryWorker] = []

    def tearDown(self) -> None:
        for w in self.workers:
            w.close()
        self.store.close()
        self.slack.close()
        self._tmp.cleanup()

    def _open_store(self) -> DeliveryStore:
        return DeliveryStore(
            self.config.db_path,
            dedup_retention_hours=self.config.dedup_retention_hours,
            lease_seconds=self.config.lease_seconds,
            worker_stale_seconds=self.config.worker_stale_seconds,
        )

    def worker(self, wid: str = "worker-1", transport=None, **kw) -> DeliveryWorker:
        w = DeliveryWorker(
            self.config,
            self.store,
            transport
            or SlackWebhookTransport(timeout_seconds=self.config.http_timeout_seconds),
            worker_id=wid,
            rng=random.Random(0),
            **kw,
        )
        self.workers.append(w)
        return w

    def enqueue(self, envelope: dict | None = None) -> str:
        ev = from_user_registered_envelope(envelope or _envelope(), self.config)
        self.assertEqual(self.store.enqueue(ev), "queued")
        return ev.event_id

    def force_due(self) -> None:
        with self.store._lock:
            self.store._conn.execute(
                "UPDATE delivery_queue SET next_attempt_at=0 WHERE status IN ('queued','retry')"
            )
            self.store._conn.commit()

    def expire_leases(self) -> None:
        with self.store._lock:
            self.store._conn.execute(
                "UPDATE delivery_queue SET lease_until=0 WHERE status='in_flight'"
            )
            self.store._conn.commit()

    def status(self, event_id: str) -> dict:
        row = self.store.get_status(event_id)
        self.assertIsNotNone(row)
        return row

    def run_worker_process(self, *, kill_after_request: int | None = None) -> int:
        """Run ``worker.py --once`` in a subprocess against the same state.

        With kill_after_request, SIGKILL the process once the fake endpoint
        has received that many requests in total (send reached "Slack", the
        response never came back to a live process).
        """
        env = {k: v for k, v in os.environ.items() if not k.startswith("PARKIO_")}
        env.update(self.env)
        proc = subprocess.Popen(
            [sys.executable, str(_ROOT / "worker.py"), "--once"],
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if kill_after_request is not None:
            reached = self.slack.wait_for_requests(kill_after_request, timeout=20)
            proc.send_signal(signal.SIGKILL)
            proc.communicate(timeout=20)
            self.assertTrue(reached, "worker never reached the fake endpoint")
            return proc.returncode
        out, err = proc.communicate(timeout=60)
        self.last_stdout, self.last_stderr = out.decode(), err.decode()
        return proc.returncode


class MalformedResponseTests(RelayCase):
    def test_transport_classifies_http_exception_as_ambiguous(self) -> None:
        self.slack.default = "malformed"
        result = SlackWebhookTransport(timeout_seconds=1).send(self.slack.url, {"text": "x"})
        self.assertEqual(self.slack.request_count, 1, "request body reached the endpoint")
        self.assertEqual(result.classification, TransportClass.AMBIGUOUS)
        self.assertTrue(result.ambiguous)

    def test_malformed_response_is_contained_and_unrelated_message_delivered(self) -> None:
        self.slack.script = ["malformed", "ok"]
        poisoned = self.enqueue()
        unrelated = self.enqueue()
        self.worker().process_once()  # must not raise

        row = self.status(poisoned)
        self.assertEqual(row["status"], STATUS_RETRY)
        self.assertIn("ambiguous", row["last_error"])
        self.assertEqual(row["attempts"], 1)
        self.assertEqual(self.status(unrelated)["status"], STATUS_DELIVERED)

    def test_always_malformed_reaches_delivery_unknown_after_max_attempts(self) -> None:
        self.slack.default = "malformed"
        eid = self.enqueue()
        w = self.worker()
        for _ in range(MAX_ATTEMPTS + 3):
            self.force_due()
            w.process_once()
        row = self.status(eid)
        self.assertEqual(row["status"], STATUS_DELIVERY_UNKNOWN)
        self.assertEqual(row["attempts"], MAX_ATTEMPTS)
        self.assertEqual(self.slack.request_count, MAX_ATTEMPTS)

    def test_unexpected_worker_exception_does_not_block_batch(self) -> None:
        class ExplodingTransport(SlackWebhookTransport):
            calls = 0

            def send(self, webhook_url, payload):
                type(self).calls += 1
                if type(self).calls == 1:
                    raise ValueError("synthetic parser bug")
                return super().send(webhook_url, payload)

        first = self.enqueue()
        second = self.enqueue()
        self.worker(transport=ExplodingTransport(timeout_seconds=1)).process_once()
        row = self.status(first)
        self.assertEqual(row["status"], STATUS_RETRY)
        self.assertNotEqual(row["status"], STATUS_DELIVERED)
        self.assertEqual(row["attempts"], 1)
        self.assertEqual(self.status(second)["status"], STATUS_DELIVERED)


class TimeoutTests(RelayCase):
    def test_timeout_after_request_received_is_never_confirmed(self) -> None:
        self.slack.default = "hang"
        eid = self.enqueue()
        w = self.worker()
        w.process_once()
        row = self.status(eid)
        self.assertEqual(self.slack.request_count, 1)
        self.assertEqual(row["status"], STATUS_RETRY)
        self.assertIn("ambiguous", row["last_error"])
        for _ in range(MAX_ATTEMPTS + 2):
            self.force_due()
            w.process_once()
        row = self.status(eid)
        self.assertEqual(row["status"], STATUS_DELIVERY_UNKNOWN)
        self.assertEqual(self.slack.request_count, MAX_ATTEMPTS)
        self.assertTrue(
            any(r["event_id"] == eid and r["reason"].startswith("delivery_unknown:")
                for r in self.store.list_dlt())
        )


class CrashRestartTests(RelayCase):
    def test_worker_process_survives_malformed_response(self) -> None:
        self.slack.script = ["malformed"]
        poisoned = self.enqueue()
        unrelated = self.enqueue()
        rc = self.run_worker_process()
        self.assertEqual(rc, 0, f"worker crashed:\n{self.last_stderr}")
        self.assertEqual(self.status(poisoned)["status"], STATUS_RETRY)
        self.assertEqual(self.status(unrelated)["status"], STATUS_DELIVERED)

    def test_repeated_crash_restart_malformed_is_bounded(self) -> None:
        """Audit lead: crash → restart → reclaim must not resend forever."""
        self.slack.default = "malformed"
        eid = self.enqueue()
        for _ in range(MAX_ATTEMPTS + 3):
            self.run_worker_process()
            self.expire_leases()
            self.force_due()
        row = self.status(eid)
        self.assertEqual(self.slack.request_count, MAX_ATTEMPTS)
        self.assertEqual(row["status"], STATUS_DELIVERY_UNKNOWN)
        self.assertEqual(row["attempts"], MAX_ATTEMPTS)

    def test_sigkill_mid_send_counts_attempts_across_restarts(self) -> None:
        """Hard crash after the request reached Slack; outcome unknowable."""
        self.slack.default = "hang"
        eid = self.enqueue()
        for n in range(1, MAX_ATTEMPTS + 1):
            self.run_worker_process(kill_after_request=n)
            row = self.status(eid)
            self.assertEqual(row["status"], STATUS_IN_FLIGHT)
            self.assertEqual(row["attempts"], n, "attempt must be durable before send")
            self.expire_leases()
            self.force_due()

        # Next start: reclaim sees an exhausted, possibly delivered row.
        self.slack.default = "ok"
        unrelated = self.enqueue()
        self.assertEqual(self.run_worker_process(), 0, self.last_stderr)
        row = self.status(eid)
        self.assertEqual(row["status"], STATUS_DELIVERY_UNKNOWN)
        self.assertEqual(row["attempts"], MAX_ATTEMPTS)
        self.assertEqual(self.status(unrelated)["status"], STATUS_DELIVERED)
        self.assertEqual(self.slack.request_count, MAX_ATTEMPTS + 1)


class LeaseTests(RelayCase):
    def test_duplicate_claim_within_lease_is_refused(self) -> None:
        eid = self.enqueue()
        a = self.store.claim_batch(worker_id="a", limit=10)
        b = self.store.claim_batch(worker_id="b", limit=10)
        self.assertEqual([i.event.event_id for i in a], [eid])
        self.assertEqual(b, [])
        self.assertEqual(self.status(eid)["lease_owner"], "a")

    def test_expired_lease_reclaim_counts_the_abandoned_attempt(self) -> None:
        eid = self.enqueue()
        for n in range(1, MAX_ATTEMPTS + 1):
            claimed = self.store.claim_batch(
                worker_id=f"w{n}", limit=10, max_attempts=MAX_ATTEMPTS
            )
            self.assertEqual([i.event.event_id for i in claimed], [eid])
            self.expire_leases()
            self.force_due()
        again = self.store.claim_batch(worker_id="late", limit=10, max_attempts=MAX_ATTEMPTS)
        self.assertEqual(again, [])
        row = self.status(eid)
        self.assertEqual(row["status"], STATUS_DELIVERY_UNKNOWN)
        self.assertEqual(row["attempts"], MAX_ATTEMPTS)
        self.assertIn("lease_expired", row["last_error"])


    def test_undecodable_row_is_dead_lettered_and_queue_continues(self) -> None:
        broken = self.enqueue()
        healthy = self.enqueue()
        with self.store._lock:
            self.store._conn.execute(
                "UPDATE delivery_queue SET payload_json='{\"not\":\"an event\"}' "
                "WHERE event_id=?",
                (broken,),
            )
            self.store._conn.commit()
        self.worker().process_once()
        self.assertEqual(self.status(broken)["status"], STATUS_DEAD)
        self.assertTrue(any(r["event_id"] == broken for r in self.store.list_dlt()))
        self.assertEqual(self.status(healthy)["status"], STATUS_DELIVERED)
        self.assertEqual(self.slack.request_count, 1)


class PreservedBehaviourTests(RelayCase):
    def test_success_is_single_attempt(self) -> None:
        eid = self.enqueue()
        self.worker().process_once()
        row = self.status(eid)
        self.assertEqual(row["status"], STATUS_DELIVERED)
        self.assertEqual(row["attempts"], 1)
        self.assertEqual(self.slack.request_count, 1)

    def test_transient_retry_then_success(self) -> None:
        self.slack.script = ["http_503"]
        eid = self.enqueue()
        w = self.worker()
        w.process_once()
        self.assertEqual(self.status(eid)["status"], STATUS_RETRY)
        self.force_due()
        w.process_once()
        row = self.status(eid)
        self.assertEqual(row["status"], STATUS_DELIVERED)
        self.assertEqual(row["attempts"], 2)

    def test_transient_exhaustion_is_dead_letter(self) -> None:
        self.slack.default = "http_503"
        eid = self.enqueue()
        w = self.worker()
        for _ in range(MAX_ATTEMPTS + 2):
            self.force_due()
            w.process_once()
        row = self.status(eid)
        self.assertEqual(row["status"], STATUS_DEAD)
        self.assertEqual(row["attempts"], MAX_ATTEMPTS)
        self.assertEqual(self.slack.request_count, MAX_ATTEMPTS)
        self.assertTrue(any(r["event_id"] == eid for r in self.store.list_dlt()))

    def test_delivery_unknown_redrive_needs_operator_and_gets_fresh_budget(self) -> None:
        self.slack.default = "malformed"
        envelope = _envelope()
        eid = self.enqueue(envelope)
        w = self.worker()
        for _ in range(MAX_ATTEMPTS):
            self.force_due()
            w.process_once()
        self.assertEqual(self.status(eid)["status"], STATUS_DELIVERY_UNKNOWN)

        # Upstream replay of the same event is suppressed; nothing resends it.
        replay = from_user_registered_envelope(envelope, self.config)
        self.assertEqual(self.store.enqueue(replay), "already_uncertain")
        self.force_due()
        w.process_once()
        self.assertEqual(self.slack.request_count, MAX_ATTEMPTS)

        self.assertEqual(
            self.store.resolve_unknown(eid, resolution="requeue", operator="u07-test"),
            "requeued",
        )
        row = self.status(eid)
        self.assertEqual((row["status"], row["attempts"]), (STATUS_QUEUED, 0))
        self.slack.default = "ok"
        w.process_once()
        row = self.status(eid)
        self.assertEqual((row["status"], row["attempts"]), (STATUS_DELIVERED, 1))


if __name__ == "__main__":
    unittest.main(verbosity=2)
