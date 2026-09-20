"""SQLite durable queue, deduplication store, and dead-letter visibility."""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .events import SlackBizEvent


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
"""


@dataclass
class QueueItem:
    id: int
    event: SlackBizEvent
    status: str
    attempts: int
    next_attempt_at: float
    last_error: str | None


class DeliveryStore:
    """Thread-safe SQLite store for queue + dedup + DLT."""

    def __init__(self, db_path: Path, *, dedup_retention_hours: int = 168):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.dedup_retention_hours = dedup_retention_hours
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL;")
        self._conn.executescript(SCHEMA)
        self._conn.commit()

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    def enqueue(self, event: SlackBizEvent) -> str:
        """
        Enqueue for delivery. Returns one of:
          queued | duplicate_suppressed | already_delivered
        Dedup retention is intentionally long so replayed Kafka/outbox
        messages within the window do not double-post. We do NOT delete
        dedup rows while an event may still be replayed (retention window).
        """
        now = time.time()
        expires = now + self.dedup_retention_hours * 3600
        with self._lock:
            self._purge_expired_dedup(now)
            existing = self._conn.execute(
                "SELECT status, event_id FROM dedup WHERE dedup_key = ?",
                (event.dedup_key,),
            ).fetchone()
            if existing is not None:
                if existing["status"] in {"delivered", "ambiguous", "queued", "in_flight"}:
                    return (
                        "already_delivered"
                        if existing["status"] in {"delivered", "ambiguous"}
                        else "duplicate_suppressed"
                    )
            # Insert dedup first (unique) to win races across concurrent consumers
            try:
                self._conn.execute(
                    "INSERT INTO dedup(dedup_key, event_id, status, created_at, expires_at) "
                    "VALUES (?, ?, 'queued', ?, ?)",
                    (event.dedup_key, event.event_id, now, expires),
                )
            except sqlite3.IntegrityError:
                return "duplicate_suppressed"

            try:
                self._conn.execute(
                    "INSERT INTO delivery_queue("
                    "event_id, dedup_key, route, payload_json, status, attempts, "
                    "next_attempt_at, last_error, created_at, updated_at) "
                    "VALUES (?, ?, ?, ?, 'queued', 0, ?, NULL, ?, ?)",
                    (
                        event.event_id,
                        event.dedup_key,
                        event.route,
                        json.dumps(event.to_dict(), separators=(",", ":")),
                        now,
                        now,
                        now,
                    ),
                )
            except sqlite3.IntegrityError:
                # Same event_id retried — treat as duplicate
                self._conn.rollback()
                return "duplicate_suppressed"
            self._conn.commit()
            self._bump_metric("slack_biz_enqueued_total", {"route": event.route})
            return "queued"

    def claim_batch(self, *, worker_id: str, limit: int = 10, lease_seconds: float = 30) -> list[QueueItem]:
        now = time.time()
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM delivery_queue "
                "WHERE status IN ('queued', 'retry') "
                "AND next_attempt_at <= ? "
                "AND (lease_until IS NULL OR lease_until < ?) "
                "ORDER BY id ASC LIMIT ?",
                (now, now, limit),
            ).fetchall()
            items: list[QueueItem] = []
            for row in rows:
                lease_until = now + lease_seconds
                updated = self._conn.execute(
                    "UPDATE delivery_queue SET status='in_flight', lease_owner=?, "
                    "lease_until=?, updated_at=? "
                    "WHERE id=? AND (lease_until IS NULL OR lease_until < ?)",
                    (worker_id, lease_until, now, row["id"], now),
                )
                if updated.rowcount != 1:
                    continue
                self._conn.execute(
                    "UPDATE dedup SET status='in_flight' WHERE dedup_key=?",
                    (row["dedup_key"],),
                )
                event = SlackBizEvent.from_dict(json.loads(row["payload_json"]))
                items.append(
                    QueueItem(
                        id=row["id"],
                        event=event,
                        status="in_flight",
                        attempts=row["attempts"],
                        next_attempt_at=row["next_attempt_at"],
                        last_error=row["last_error"],
                    )
                )
            self._conn.commit()
            return items

    def mark_delivered(self, item: QueueItem, *, ambiguous: bool = False) -> None:
        now = time.time()
        status = "ambiguous" if ambiguous else "delivered"
        with self._lock:
            self._conn.execute(
                "UPDATE delivery_queue SET status=?, attempts=attempts+1, "
                "lease_owner=NULL, lease_until=NULL, updated_at=?, last_error=? "
                "WHERE id=?",
                (status, now, "ambiguous_timeout" if ambiguous else None, item.id),
            )
            self._conn.execute(
                "UPDATE dedup SET status=? WHERE dedup_key=?",
                (status, item.event.dedup_key),
            )
            self._bump_metric(
                "slack_biz_delivered_total",
                {"route": item.event.route, "ambiguous": str(ambiguous).lower()},
            )
            self._conn.commit()

    def mark_retry(self, item: QueueItem, *, error: str, delay_seconds: float) -> None:
        now = time.time()
        with self._lock:
            self._conn.execute(
                "UPDATE delivery_queue SET status='retry', attempts=attempts+1, "
                "next_attempt_at=?, last_error=?, lease_owner=NULL, lease_until=NULL, "
                "updated_at=? WHERE id=?",
                (now + delay_seconds, error[:1000], now, item.id),
            )
            self._conn.execute(
                "UPDATE dedup SET status='queued' WHERE dedup_key=?",
                (item.event.dedup_key,),
            )
            self._bump_metric("slack_biz_retry_total", {"route": item.event.route})
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
                "UPDATE delivery_queue SET status='dead', attempts=attempts+1, "
                "last_error=?, lease_owner=NULL, lease_until=NULL, updated_at=? WHERE id=?",
                (reason[:1000], now, item.id),
            )
            # Keep dedup as dead so replays within retention do not re-spam Slack
            self._conn.execute(
                "UPDATE dedup SET status='dead' WHERE dedup_key=?",
                (item.event.dedup_key,),
            )
            self._bump_metric("slack_biz_dlt_total", {"route": item.event.route})
            self._conn.commit()

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
                "WHERE status IN ('queued', 'retry', 'in_flight')"
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

    def _purge_expired_dedup(self, now: float) -> None:
        # Only purge expired rows that are terminal AND past retention.
        # Never purge while status is queued/in_flight (replay risk).
        self._conn.execute(
            "DELETE FROM dedup WHERE expires_at < ? "
            "AND status IN ('delivered', 'dead', 'ambiguous')",
            (now,),
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
