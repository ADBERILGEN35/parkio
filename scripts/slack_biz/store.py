"""SQLite durable queue, admission/dedup store, DLT, and worker lock.

Delivery outcomes are separate from admission:
  admitted (queued|retry|in_flight) ≠ delivered ≠ delivery_unknown ≠ dead

Ambiguous transport outcomes are NEVER recorded as confirmed delivery.
"""

from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .events import SlackBizEvent

# Queue / dedup status vocabulary
STATUS_QUEUED = "queued"
STATUS_RETRY = "retry"
STATUS_IN_FLIGHT = "in_flight"
STATUS_DELIVERED = "delivered"
STATUS_DEAD = "dead"
STATUS_DELIVERY_UNKNOWN = "delivery_unknown"

# Dedup admission statuses that block a second queue insert
ADMISSION_BLOCKING = frozenset(
    {
        STATUS_QUEUED,
        STATUS_RETRY,
        STATUS_IN_FLIGHT,
        STATUS_DELIVERED,
        STATUS_DEAD,
        STATUS_DELIVERY_UNKNOWN,
    }
)

SCHEMA = """
CREATE TABLE IF NOT EXISTS delivery_queue (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL,
  dedup_key TEXT NOT NULL,
  route TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  status TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at REAL NOT NULL,
  last_error TEXT,
  created_at REAL NOT NULL,
  updated_at REAL NOT NULL,
  lease_owner TEXT,
  lease_until REAL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_queue_event_id ON delivery_queue(event_id);

CREATE TABLE IF NOT EXISTS dedup (
  dedup_key TEXT PRIMARY KEY,
  event_id TEXT NOT NULL,
  status TEXT NOT NULL,
  created_at REAL NOT NULL,
  expires_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS dlt (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL,
  dedup_key TEXT NOT NULL,
  reason TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS incident_threads (
  correlation_key TEXT PRIMARY KEY,
  open_event_id TEXT NOT NULL,
  last_status TEXT NOT NULL,
  updated_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS metrics (
  name TEXT NOT NULL,
  labels_json TEXT NOT NULL,
  value REAL NOT NULL,
  updated_at REAL NOT NULL,
  PRIMARY KEY (name, labels_json)
);

CREATE TABLE IF NOT EXISTS worker_lock (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  worker_id TEXT NOT NULL,
  heartbeat_at REAL NOT NULL,
  started_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS schema_meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
"""


@dataclass
class QueueItem:
    id: int
    event: SlackBizEvent
    status: str
    attempts: int
    next_attempt_at: float
    last_error: str | None


class WorkerLockError(RuntimeError):
    """Raised when a second worker attempts to claim the exclusive lock."""


class DeliveryStore:
    """Thread-safe SQLite store for queue + dedup + DLT + single-worker lock."""

    def __init__(
        self,
        db_path: Path,
        *,
        dedup_retention_hours: int = 168,
        lease_seconds: float = 30.0,
        worker_stale_seconds: float = 60.0,
    ):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.dedup_retention_hours = dedup_retention_hours
        self.lease_seconds = lease_seconds
        self.worker_stale_seconds = worker_stale_seconds
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL;")
        self._conn.execute("PRAGMA synchronous=NORMAL;")
        self._conn.executescript(SCHEMA)
        self._conn.execute(
            "INSERT OR IGNORE INTO schema_meta(key, value) VALUES ('version', '2')"
        )
        self._conn.commit()
        # Best-effort restrictive perms on POSIX; no-op / soft-fail on Windows
        try:
            os.chmod(self.db_path, 0o600)
        except OSError:
            pass

    def close(self) -> None:
        with self._lock:
            try:
                self._conn.close()
            except sqlite3.Error:
                pass

    # ------------------------------------------------------------------ lock
    def acquire_worker_lock(self, worker_id: str) -> None:
        """Enforce single active worker. Stale locks (crashed worker) are stolen."""
        now = time.time()
        with self._lock:
            row = self._conn.execute(
                "SELECT worker_id, heartbeat_at FROM worker_lock WHERE id=1"
            ).fetchone()
            if row is None:
                self._conn.execute(
                    "INSERT INTO worker_lock(id, worker_id, heartbeat_at, started_at) "
                    "VALUES (1, ?, ?, ?)",
                    (worker_id, now, now),
                )
                self._conn.commit()
                return
            if row["worker_id"] == worker_id:
                self._conn.execute(
                    "UPDATE worker_lock SET heartbeat_at=? WHERE id=1", (now,)
                )
                self._conn.commit()
                return
            if now - float(row["heartbeat_at"]) > self.worker_stale_seconds:
                self._conn.execute(
                    "UPDATE worker_lock SET worker_id=?, heartbeat_at=?, started_at=? WHERE id=1",
                    (worker_id, now, now),
                )
                self._conn.commit()
                return
            raise WorkerLockError(
                f"another worker holds the lock: {row['worker_id']} "
                f"(heartbeat_age={now - float(row['heartbeat_at']):.1f}s)"
            )

    def heartbeat_worker_lock(self, worker_id: str) -> None:
        now = time.time()
        with self._lock:
            updated = self._conn.execute(
                "UPDATE worker_lock SET heartbeat_at=? WHERE id=1 AND worker_id=?",
                (now, worker_id),
            )
            self._conn.commit()
            if updated.rowcount != 1:
                raise WorkerLockError("lost worker lock")

    def release_worker_lock(self, worker_id: str) -> None:
        with self._lock:
            self._conn.execute(
                "DELETE FROM worker_lock WHERE id=1 AND worker_id=?", (worker_id,)
            )
            self._conn.commit()

    # ------------------------------------------------------------------ enqueue
    def enqueue(self, event: SlackBizEvent) -> str:
        """
        Admit event for delivery. Returns:
          queued | duplicate_suppressed | already_delivered | already_uncertain | already_dead

        Admission is independent of final delivery outcome. Within the dedup
        retention window, Kafka/outbox redelivery must not create a second row.
        Outside the window, expired terminal dedup rows are purged and a new
        admission is allowed — operators must treat that as a new notification
        risk (replay window = dedup_retention_hours).
        """
        now = time.time()
        expires = now + self.dedup_retention_hours * 3600
        with self._lock:
            try:
                self._purge_expired_dedup(now)
                existing = self._conn.execute(
                    "SELECT status, event_id FROM dedup WHERE dedup_key = ?",
                    (event.dedup_key,),
                ).fetchone()
                if existing is not None:
                    st = existing["status"]
                    if st == STATUS_DELIVERED:
                        return "already_delivered"
                    if st == STATUS_DELIVERY_UNKNOWN:
                        return "already_uncertain"
                    if st == STATUS_DEAD:
                        return "already_dead"
                    if st in {STATUS_QUEUED, STATUS_RETRY, STATUS_IN_FLIGHT}:
                        return "duplicate_suppressed"
                    return "duplicate_suppressed"

                try:
                    self._conn.execute(
                        "INSERT INTO dedup(dedup_key, event_id, status, created_at, expires_at) "
                        "VALUES (?, ?, ?, ?, ?)",
                        (event.dedup_key, event.event_id, STATUS_QUEUED, now, expires),
                    )
                except sqlite3.IntegrityError:
                    return "duplicate_suppressed"

                try:
                    self._conn.execute(
                        "INSERT INTO delivery_queue("
                        "event_id, dedup_key, route, payload_json, status, attempts, "
                        "next_attempt_at, last_error, created_at, updated_at) "
                        "VALUES (?, ?, ?, ?, ?, 0, ?, NULL, ?, ?)",
                        (
                            event.event_id,
                            event.dedup_key,
                            event.route,
                            json.dumps(event.to_dict(), separators=(",", ":")),
                            STATUS_QUEUED,
                            now,
                            now,
                            now,
                        ),
                    )
                except sqlite3.IntegrityError:
                    self._conn.rollback()
                    return "duplicate_suppressed"
                self._bump_metric("slack_biz_enqueued_total", {"route": event.route})
                self._conn.commit()
                return "queued"
            except sqlite3.OperationalError as exc:
                self._conn.rollback()
                self._bump_metric("slack_biz_enqueue_write_fail_total", {"route": event.route})
                raise OSError(f"queue_write_failed:{exc}") from exc

    # ------------------------------------------------------------------ claim
    def reclaim_expired_leases(self) -> int:
        """Return expired in_flight rows to retry (worker crash recovery)."""
        now = time.time()
        with self._lock:
            rows = self._conn.execute(
                "SELECT id, dedup_key FROM delivery_queue "
                "WHERE status=? AND lease_until IS NOT NULL AND lease_until < ?",
                (STATUS_IN_FLIGHT, now),
            ).fetchall()
            for row in rows:
                self._conn.execute(
                    "UPDATE delivery_queue SET status=?, lease_owner=NULL, lease_until=NULL, "
                    "next_attempt_at=?, updated_at=?, last_error=? WHERE id=?",
                    (
                        STATUS_RETRY,
                        now,
                        now,
                        "lease_expired_reclaimed",
                        row["id"],
                    ),
                )
                self._conn.execute(
                    "UPDATE dedup SET status=? WHERE dedup_key=?",
                    (STATUS_QUEUED, row["dedup_key"]),
                )
            if rows:
                self._bump_metric("slack_biz_lease_reclaim_total", {}, value=float(len(rows)))
                self._conn.commit()
            return len(rows)

    def claim_batch(self, *, worker_id: str, limit: int = 10) -> list[QueueItem]:
        """Claim work. Network I/O must happen AFTER this returns (outside DB lock)."""
        now = time.time()
        with self._lock:
            self.reclaim_expired_leases()
            rows = self._conn.execute(
                "SELECT * FROM delivery_queue "
                "WHERE status IN (?, ?) "
                "AND next_attempt_at <= ? "
                "AND (lease_until IS NULL OR lease_until < ?) "
                "ORDER BY id ASC LIMIT ?",
                (STATUS_QUEUED, STATUS_RETRY, now, now, limit),
            ).fetchall()
            items: list[QueueItem] = []
            for row in rows:
                lease_until = now + self.lease_seconds
                updated = self._conn.execute(
                    "UPDATE delivery_queue SET status=?, lease_owner=?, "
                    "lease_until=?, updated_at=? "
                    "WHERE id=? AND status IN (?, ?) "
                    "AND (lease_until IS NULL OR lease_until < ?)",
                    (
                        STATUS_IN_FLIGHT,
                        worker_id,
                        lease_until,
                        now,
                        row["id"],
                        STATUS_QUEUED,
                        STATUS_RETRY,
                        now,
                    ),
                )
                if updated.rowcount != 1:
                    continue
                self._conn.execute(
                    "UPDATE dedup SET status=? WHERE dedup_key=?",
                    (STATUS_IN_FLIGHT, row["dedup_key"]),
                )
                event = SlackBizEvent.from_dict(json.loads(row["payload_json"]))
                items.append(
                    QueueItem(
                        id=row["id"],
                        event=event,
                        status=STATUS_IN_FLIGHT,
                        attempts=row["attempts"],
                        next_attempt_at=row["next_attempt_at"],
                        last_error=row["last_error"],
                    )
                )
            self._conn.commit()
            return items

    def mark_delivered(self, item: QueueItem) -> None:
        """Confirmed transport success only."""
        now = time.time()
        with self._lock:
            self._conn.execute(
                "UPDATE delivery_queue SET status=?, attempts=attempts+1, "
                "lease_owner=NULL, lease_until=NULL, updated_at=?, last_error=NULL "
                "WHERE id=?",
                (STATUS_DELIVERED, now, item.id),
            )
            self._conn.execute(
                "UPDATE dedup SET status=? WHERE dedup_key=?",
                (STATUS_DELIVERED, item.event.dedup_key),
            )
            self._bump_metric(
                "slack_biz_delivered_total", {"route": item.event.route}
            )
            self._conn.commit()

    def mark_delivery_unknown(self, item: QueueItem, *, reason: str) -> None:
        """
        Terminal uncertain outcome after bounded ambiguous retries.
        Visible for operator resolution; NOT confirmed delivery.
        Further automatic retries stop; re-enqueue suppressed within retention.
        """
        now = time.time()
        with self._lock:
            payload = json.dumps(item.event.to_dict(), separators=(",", ":"))
            self._conn.execute(
                "INSERT INTO dlt(event_id, dedup_key, reason, payload_json, created_at) "
                "VALUES (?, ?, ?, ?, ?)",
                (
                    item.event.event_id,
                    item.event.dedup_key,
                    f"delivery_unknown:{reason}"[:500],
                    payload,
                    now,
                ),
            )
            self._conn.execute(
                "UPDATE delivery_queue SET status=?, attempts=attempts+1, "
                "last_error=?, lease_owner=NULL, lease_until=NULL, updated_at=? WHERE id=?",
                (STATUS_DELIVERY_UNKNOWN, reason[:1000], now, item.id),
            )
            self._conn.execute(
                "UPDATE dedup SET status=? WHERE dedup_key=?",
                (STATUS_DELIVERY_UNKNOWN, item.event.dedup_key),
            )
            self._bump_metric(
                "slack_biz_delivery_unknown_total", {"route": item.event.route}
            )
            self._conn.commit()

    def mark_retry(
        self,
        item: QueueItem,
        *,
        error: str,
        delay_seconds: float,
        ambiguous: bool = False,
    ) -> None:
        now = time.time()
        with self._lock:
            self._conn.execute(
                "UPDATE delivery_queue SET status=?, attempts=attempts+1, "
                "next_attempt_at=?, last_error=?, lease_owner=NULL, lease_until=NULL, "
                "updated_at=? WHERE id=?",
                (
                    STATUS_RETRY,
                    now + delay_seconds,
                    error[:1000],
                    now,
                    item.id,
                ),
            )
            self._conn.execute(
                "UPDATE dedup SET status=? WHERE dedup_key=?",
                (STATUS_QUEUED, item.event.dedup_key),
            )
            labels = {"route": item.event.route, "ambiguous": str(ambiguous).lower()}
            self._bump_metric("slack_biz_retry_total", labels)
            if ambiguous:
                self._bump_metric("slack_biz_ambiguous_retry_total", {"route": item.event.route})
            self._conn.commit()

    def mark_dead(self, item: QueueItem, *, reason: str) -> None:
        now = time.time()
        with self._lock:
            payload = json.dumps(item.event.to_dict(), separators=(",", ":"))
            self._conn.execute(
                "INSERT INTO dlt(event_id, dedup_key, reason, payload_json, created_at) "
                "VALUES (?, ?, ?, ?, ?)",
                (item.event.event_id, item.event.dedup_key, reason[:500], payload, now),
            )
            self._conn.execute(
                "UPDATE delivery_queue SET status=?, attempts=attempts+1, "
                "last_error=?, lease_owner=NULL, lease_until=NULL, updated_at=? WHERE id=?",
                (STATUS_DEAD, reason[:1000], now, item.id),
            )
            self._conn.execute(
                "UPDATE dedup SET status=? WHERE dedup_key=?",
                (STATUS_DEAD, item.event.dedup_key),
            )
            self._bump_metric("slack_biz_dlt_total", {"route": item.event.route})
            self._conn.commit()

    def resolve_unknown(
        self, event_id: str, *, resolution: str, operator: str
    ) -> str:
        """
        Operator resolution for delivery_unknown.
        resolution: accept_as_delivered | requeue | discard
        Audited via metrics + last_error annotation.
        """
        now = time.time()
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM delivery_queue WHERE event_id=? AND status=?",
                (event_id, STATUS_DELIVERY_UNKNOWN),
            ).fetchone()
            if row is None:
                return "not_found"
            note = f"operator:{operator}:{resolution}:{int(now)}"
            if resolution == "accept_as_delivered":
                self._conn.execute(
                    "UPDATE delivery_queue SET status=?, last_error=?, updated_at=? WHERE id=?",
                    (STATUS_DELIVERED, note, now, row["id"]),
                )
                self._conn.execute(
                    "UPDATE dedup SET status=? WHERE dedup_key=?",
                    (STATUS_DELIVERED, row["dedup_key"]),
                )
                self._bump_metric(
                    "slack_biz_operator_resolve_total", {"resolution": resolution}
                )
                self._conn.commit()
                return "accepted"
            if resolution == "discard":
                self._conn.execute(
                    "UPDATE delivery_queue SET status=?, last_error=?, updated_at=? WHERE id=?",
                    (STATUS_DEAD, note, now, row["id"]),
                )
                self._conn.execute(
                    "UPDATE dedup SET status=? WHERE dedup_key=?",
                    (STATUS_DEAD, row["dedup_key"]),
                )
                self._bump_metric(
                    "slack_biz_operator_resolve_total", {"resolution": resolution}
                )
                self._conn.commit()
                return "discarded"
            if resolution == "requeue":
                # Explicit operator-approved replay; may duplicate Slack message
                self._conn.execute(
                    "UPDATE delivery_queue SET status=?, attempts=0, next_attempt_at=?, "
                    "last_error=?, lease_owner=NULL, lease_until=NULL, updated_at=? WHERE id=?",
                    (STATUS_QUEUED, now, note, now, row["id"]),
                )
                self._conn.execute(
                    "UPDATE dedup SET status=? WHERE dedup_key=?",
                    (STATUS_QUEUED, row["dedup_key"]),
                )
                self._bump_metric(
                    "slack_biz_operator_resolve_total", {"resolution": resolution}
                )
                self._conn.commit()
                return "requeued"
            return "invalid_resolution"

    # ------------------------------------------------------------ retention
    def bump_metric(self, name: str, labels: dict[str, str], value: float = 1.0) -> None:
        with self._lock:
            self._bump_metric(name, labels, value)
            self._conn.commit()

    def prune(self, *, dlt_retention_hours: float, now: float | None = None) -> dict[str, int]:
        """
        Bounded retention for relay state. Never touches queued / retry /
        in_flight rows. Terminal queue + dedup rows go only after the dedup
        window (expires_at), so the documented replay-suppression window holds.
        DLT rows (payload copies of dead events) go after dlt_retention_hours.
        """
        now = time.time() if now is None else now
        with self._lock:
            before = self._conn.total_changes
            self._purge_expired_dedup(now)
            purged_terminal = self._conn.total_changes - before
            cur = self._conn.execute(
                "DELETE FROM dlt WHERE created_at < ?",
                (now - dlt_retention_hours * 3600,),
            )
            purged_dlt = cur.rowcount
            self._conn.commit()
        return {"terminal_rows": purged_terminal, "dlt_rows": purged_dlt}

    def discard_backlog(self, event_type: str, *, operator: str) -> dict[str, int]:
        """
        Operator backlog discard for one event family: queued/retry rows become
        dead (no Slack send). Their dedup rows stay (status dead) until the
        dedup window ends, so a re-exported envelope is suppressed, not resent.
        in_flight rows are left to their worker lease and reported.
        """
        now = time.time()
        note = f"operator:{operator[:64]}:discard_backlog:{int(now)}"
        with self._lock:
            rows = self._conn.execute(
                "SELECT id, dedup_key, status, payload_json FROM delivery_queue "
                "WHERE status IN (?, ?, ?)",
                (STATUS_QUEUED, STATUS_RETRY, STATUS_IN_FLIGHT),
            ).fetchall()
            discarded = 0
            in_flight = 0
            for row in rows:
                try:
                    etype = json.loads(row["payload_json"]).get("event_type")
                except (ValueError, AttributeError):
                    continue
                if etype != event_type:
                    continue
                if row["status"] == STATUS_IN_FLIGHT:
                    in_flight += 1
                    continue
                self._conn.execute(
                    "UPDATE delivery_queue SET status=?, last_error=?, updated_at=? WHERE id=?",
                    (STATUS_DEAD, note, now, row["id"]),
                )
                self._conn.execute(
                    "UPDATE dedup SET status=? WHERE dedup_key=?",
                    (STATUS_DEAD, row["dedup_key"]),
                )
                discarded += 1
            if discarded:
                self._bump_metric(
                    "slack_biz_backlog_discarded_total", {"event_type": event_type}, discarded
                )
            self._conn.commit()
        return {"discarded": discarded, "in_flight_skipped": in_flight}

    def get_status(self, event_id: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM delivery_queue WHERE event_id=?", (event_id,)
            ).fetchone()
            return dict(row) if row else None

    def list_uncertain(self, *, limit: int = 50) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM delivery_queue WHERE status=? ORDER BY id DESC LIMIT ?",
                (STATUS_DELIVERY_UNKNOWN, limit),
            ).fetchall()
            return [dict(r) for r in rows]

    def list_dlt(self, *, limit: int = 50) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM dlt ORDER BY id DESC LIMIT ?", (limit,)
            ).fetchall()
            return [dict(r) for r in rows]

    def pending_count(self) -> int:
        with self._lock:
            row = self._conn.execute(
                "SELECT COUNT(*) AS c FROM delivery_queue "
                "WHERE status IN (?, ?, ?)",
                (STATUS_QUEUED, STATUS_RETRY, STATUS_IN_FLIGHT),
            ).fetchone()
            return int(row["c"])

    def count_by_status(self, status: str) -> int:
        with self._lock:
            row = self._conn.execute(
                "SELECT COUNT(*) AS c FROM delivery_queue WHERE status=?", (status,)
            ).fetchone()
            return int(row["c"])

    def remember_incident(self, correlation_key: str, event_id: str, status: str) -> None:
        now = time.time()
        with self._lock:
            self._conn.execute(
                "INSERT INTO incident_threads(correlation_key, open_event_id, last_status, updated_at) "
                "VALUES (?, ?, ?, ?) "
                "ON CONFLICT(correlation_key) DO UPDATE SET "
                "last_status=excluded.last_status, updated_at=excluded.updated_at",
                (correlation_key, event_id, status, now),
            )
            self._conn.commit()

    def get_incident(self, correlation_key: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM incident_threads WHERE correlation_key=?",
                (correlation_key,),
            ).fetchone()
            return dict(row) if row else None

    def force_expire_dedup(self, dedup_key: str) -> None:
        """Test helper: expire a dedup row immediately."""
        with self._lock:
            self._conn.execute(
                "UPDATE dedup SET expires_at=0 WHERE dedup_key=?", (dedup_key,)
            )
            self._conn.commit()

    def _purge_expired_dedup(self, now: float) -> None:
        # Never purge admitted-in-progress rows. Terminal only after retention.
        # Purging terminal dedup ALSO removes matching terminal queue rows so a
        # same event_id may be re-admitted. That is a deliberate duplicate-risk
        # window boundary — not a promise that old Kafka replays are "safe".
        rows = self._conn.execute(
            "SELECT dedup_key, event_id FROM dedup WHERE expires_at < ? "
            "AND status IN (?, ?, ?)",
            (now, STATUS_DELIVERED, STATUS_DEAD, STATUS_DELIVERY_UNKNOWN),
        ).fetchall()
        for row in rows:
            self._conn.execute(
                "DELETE FROM delivery_queue WHERE event_id=? AND status IN (?, ?, ?)",
                (
                    row["event_id"],
                    STATUS_DELIVERED,
                    STATUS_DEAD,
                    STATUS_DELIVERY_UNKNOWN,
                ),
            )
            self._conn.execute(
                "DELETE FROM dedup WHERE dedup_key=?", (row["dedup_key"],)
            )

    def _bump_metric(self, name: str, labels: dict[str, str], value: float = 1.0) -> None:
        labels_json = json.dumps(labels, sort_keys=True, separators=(",", ":"))
        now = time.time()
        row = self._conn.execute(
            "SELECT value FROM metrics WHERE name=? AND labels_json=?",
            (name, labels_json),
        ).fetchone()
        if row is None:
            self._conn.execute(
                "INSERT INTO metrics(name, labels_json, value, updated_at) VALUES (?, ?, ?, ?)",
                (name, labels_json, value, now),
            )
        else:
            self._conn.execute(
                "UPDATE metrics SET value=value+?, updated_at=? WHERE name=? AND labels_json=?",
                (value, now, name, labels_json),
            )

    def snapshot_metrics(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute("SELECT * FROM metrics").fetchall()
            return [dict(r) for r in rows]
