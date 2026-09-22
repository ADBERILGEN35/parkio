#!/usr/bin/env python3
"""Persistent fail-closed byte budget in front of the New Relic Log API.

The gate reserves uncompressed serialized JSON bytes before every upstream
attempt. Retries consume budget again because any attempt could have reached the
vendor even when its acknowledgement was lost. An exhausted gate acknowledges
and drops later batches so Fluent Bit cannot create an unbounded retry storm;
health and counters remain visibly failed until the state is explicitly removed.
"""

from __future__ import annotations

import gzip
import hashlib
import io
import json
import os
import sqlite3
import ssl
import signal
import threading
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse


PORT = int(os.environ.get("PARKIO_NR_GATE_PORT", "8090"))
UPSTREAM = os.environ.get("PARKIO_NR_UPSTREAM_BASE_URI", "")
LICENSE_KEY = os.environ.get("PARKIO_NR_LOG_API_KEY", "")
STATE_DB = Path(os.environ.get("PARKIO_NR_BUDGET_STATE_DB", "/var/lib/parkio-nr-budget/budget.db"))
BUDGET_BYTES = int(os.environ.get("PARKIO_NR_BUDGET_BYTES", "0"))
DAILY_BUDGET_BYTES = int(os.environ.get("PARKIO_NR_DAILY_BUDGET_BYTES", "0"))
MONTHLY_BUDGET_BYTES = int(os.environ.get("PARKIO_NR_MONTHLY_BUDGET_BYTES", "0"))
MAX_REQUEST_BYTES = int(os.environ.get("PARKIO_NR_GATE_MAX_REQUEST_BYTES", "1048576"))
UPSTREAM_TIMEOUT_SECONDS = float(os.environ.get("PARKIO_NR_GATE_UPSTREAM_TIMEOUT_SECONDS", "10"))
TLS_VERIFY = os.environ.get("PARKIO_NR_UPSTREAM_TLS_VERIFY", "on").lower() == "on"
TEST_CONTROL = os.environ.get("PARKIO_NR_GATE_TEST_CONTROL", "off").lower() == "on"
INTERNAL_KEY = os.environ.get("PARKIO_NR_GATE_INTERNAL_KEY", "gate-internal-not-a-secret")

_lock = threading.Lock()
_test_now: datetime | None = None


def utc_now() -> datetime:
    if TEST_CONTROL and _test_now is not None:
        return _test_now
    return datetime.now(timezone.utc)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _record_count(payload: object) -> int:
    items = payload if isinstance(payload, list) else [payload]
    count = 0
    for item in items:
        if not isinstance(item, dict):
            continue
        logs = item.get("logs")
        count += len(logs) if isinstance(logs, list) else 1
    return count


class Budget:
    columns = (
        "max_bytes", "spent_bytes", "attempts", "retry_attempts",
        "forwarded_attempts", "rejected_attempts", "records_attempted",
        "records_forwarded", "records_rejected", "serialized_bytes_attempted",
        "wire_bytes_attempted", "exhausted", "last_digest",
    )

    window_columns = (
        "daily_limit", "daily_window", "daily_spent", "daily_exhausted",
        "monthly_limit", "monthly_window", "monthly_spent", "monthly_exhausted",
    )

    def __init__(self, path: Path, maximum: int, daily: int = 0, monthly: int = 0) -> None:
        if min(maximum, daily, monthly) < 0 or not any((maximum, daily, monthly)):
            raise ValueError("at least one byte budget must be positive and none may be negative")
        if daily and monthly and daily > monthly:
            raise ValueError("daily budget must not exceed monthly budget")
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        with self._connect() as db:
            db.execute("PRAGMA journal_mode=TRUNCATE")
            db.execute("PRAGMA synchronous=FULL")
            db.execute(
                """CREATE TABLE IF NOT EXISTS budget (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    max_bytes INTEGER NOT NULL,
                    spent_bytes INTEGER NOT NULL,
                    attempts INTEGER NOT NULL,
                    retry_attempts INTEGER NOT NULL,
                    forwarded_attempts INTEGER NOT NULL,
                    rejected_attempts INTEGER NOT NULL,
                    records_attempted INTEGER NOT NULL,
                    records_forwarded INTEGER NOT NULL,
                    records_rejected INTEGER NOT NULL,
                    serialized_bytes_attempted INTEGER NOT NULL,
                    wire_bytes_attempted INTEGER NOT NULL,
                    exhausted INTEGER NOT NULL,
                    last_digest TEXT NOT NULL
                )"""
            )
            row = db.execute("SELECT max_bytes FROM budget WHERE id = 1").fetchone()
            if row is None:
                db.execute(
                    "INSERT INTO budget VALUES (1, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, '')",
                    (maximum,),
                )
            elif int(row[0]) != maximum:
                raise RuntimeError(
                    f"persisted budget is {row[0]} bytes but configuration requests {maximum}; "
                    "refusing to reset accounting"
                )
            existing = {str(item[1]) for item in db.execute("PRAGMA table_info(budget)")}
            additions = {
                "daily_limit": "INTEGER NOT NULL DEFAULT 0",
                "daily_window": "TEXT NOT NULL DEFAULT ''",
                "daily_spent": "INTEGER NOT NULL DEFAULT 0",
                "daily_exhausted": "INTEGER NOT NULL DEFAULT 0",
                "monthly_limit": "INTEGER NOT NULL DEFAULT 0",
                "monthly_window": "TEXT NOT NULL DEFAULT ''",
                "monthly_spent": "INTEGER NOT NULL DEFAULT 0",
                "monthly_exhausted": "INTEGER NOT NULL DEFAULT 0",
            }
            for name, declaration in additions.items():
                if name not in existing:
                    db.execute(f"ALTER TABLE budget ADD COLUMN {name} {declaration}")
            configured = db.execute(
                "SELECT daily_limit, monthly_limit FROM budget WHERE id = 1"
            ).fetchone()
            assert configured is not None
            if configured == (0, 0):
                db.execute(
                    "UPDATE budget SET daily_limit = ?, monthly_limit = ? WHERE id = 1",
                    (daily, monthly),
                )
            elif tuple(map(int, configured)) != (daily, monthly):
                raise RuntimeError(
                    f"persisted daily/monthly budgets are {configured[0]}/{configured[1]} "
                    f"but configuration requests {daily}/{monthly}; refusing to reset accounting"
                )
            self._roll_windows(db, utc_now())

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self.path, timeout=5, isolation_level=None)

    @staticmethod
    def _window_keys(now: datetime) -> tuple[str, str]:
        current = now.astimezone(timezone.utc)
        return current.strftime("%Y-%m-%d"), current.strftime("%Y-%m")

    def _roll_windows(self, db: sqlite3.Connection, now: datetime) -> None:
        daily_key, monthly_key = self._window_keys(now)
        row = db.execute(
            "SELECT daily_window, monthly_window FROM budget WHERE id = 1"
        ).fetchone()
        assert row is not None
        if str(row[0]) != daily_key:
            db.execute(
                "UPDATE budget SET daily_window = ?, daily_spent = 0, daily_exhausted = 0 WHERE id = 1",
                (daily_key,),
            )
        if str(row[1]) != monthly_key:
            db.execute(
                "UPDATE budget SET monthly_window = ?, monthly_spent = 0, monthly_exhausted = 0 WHERE id = 1",
                (monthly_key,),
            )

    def snapshot(self) -> dict[str, int | str | bool]:
        with _lock, self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            self._roll_windows(db, utc_now())
            row = db.execute(
                f"SELECT {','.join(self.columns + self.window_columns)} FROM budget WHERE id = 1"
            ).fetchone()
            db.commit()
        assert row is not None
        result: dict[str, int | str | bool] = dict(zip(self.columns + self.window_columns, row))
        result["exhausted"] = bool(result["exhausted"])
        result["daily_exhausted"] = bool(result["daily_exhausted"])
        result["monthly_exhausted"] = bool(result["monthly_exhausted"])
        result["remaining_bytes"] = (
            max(0, int(result["max_bytes"]) - int(result["spent_bytes"]))
            if int(result["max_bytes"]) else -1
        )
        result["daily_remaining_bytes"] = (
            max(0, int(result["daily_limit"]) - int(result["daily_spent"]))
            if int(result["daily_limit"]) else -1
        )
        result["monthly_remaining_bytes"] = (
            max(0, int(result["monthly_limit"]) - int(result["monthly_spent"]))
            if int(result["monthly_limit"]) else -1
        )
        result.pop("last_digest", None)
        return result

    def reserve(self, serialized_bytes: int, wire_bytes: int, records: int, digest: str) -> bool:
        with _lock, self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            self._roll_windows(db, utc_now())
            row = db.execute(
                "SELECT max_bytes, spent_bytes, exhausted, last_digest, "
                "daily_limit, daily_spent, daily_exhausted, monthly_limit, monthly_spent, monthly_exhausted "
                "FROM budget WHERE id = 1"
            ).fetchone()
            assert row is not None
            maximum, spent, exhausted, last_digest = int(row[0]), int(row[1]), bool(row[2]), str(row[3])
            daily, daily_spent, daily_exhausted = int(row[4]), int(row[5]), bool(row[6])
            monthly, monthly_spent, monthly_exhausted = int(row[7]), int(row[8]), bool(row[9])
            retry = 1 if digest == last_digest and last_digest else 0
            total_fits = not maximum or serialized_bytes <= maximum - spent
            daily_fits = not daily or serialized_bytes <= daily - daily_spent
            monthly_fits = not monthly or serialized_bytes <= monthly - monthly_spent
            admitted = (
                not exhausted and not daily_exhausted and not monthly_exhausted
                and total_fits and daily_fits and monthly_fits
            )
            new_exhausted = exhausted or bool(maximum and (not total_fits or serialized_bytes == maximum - spent))
            new_daily_exhausted = daily_exhausted or bool(daily and (not daily_fits or serialized_bytes == daily - daily_spent))
            new_monthly_exhausted = monthly_exhausted or bool(monthly and (not monthly_fits or serialized_bytes == monthly - monthly_spent))
            db.execute(
                """UPDATE budget SET
                    spent_bytes = spent_bytes + ?,
                    attempts = attempts + 1,
                    retry_attempts = retry_attempts + ?,
                    forwarded_attempts = forwarded_attempts + ?,
                    rejected_attempts = rejected_attempts + ?,
                    records_attempted = records_attempted + ?,
                    records_forwarded = records_forwarded + ?,
                    records_rejected = records_rejected + ?,
                    serialized_bytes_attempted = serialized_bytes_attempted + ?,
                    wire_bytes_attempted = wire_bytes_attempted + ?,
                    exhausted = ?,
                    daily_spent = daily_spent + ?,
                    daily_exhausted = ?,
                    monthly_spent = monthly_spent + ?,
                    monthly_exhausted = ?,
                    last_digest = ?
                   WHERE id = 1""",
                (
                    serialized_bytes if admitted else 0,
                    retry,
                    1 if admitted else 0,
                    0 if admitted else 1,
                    records,
                    records if admitted else 0,
                    0 if admitted else records,
                    serialized_bytes,
                    wire_bytes,
                    1 if new_exhausted else 0,
                    serialized_bytes if admitted and daily else 0,
                    1 if new_daily_exhausted else 0,
                    serialized_bytes if admitted and monthly else 0,
                    1 if new_monthly_exhausted else 0,
                    digest,
                ),
            )
            db.commit()
            return admitted

    def reset_for_test(self, spent_bytes: int = 0) -> None:
        if not TEST_CONTROL or spent_bytes < 0 or spent_bytes >= BUDGET_BYTES:
            raise ValueError("test reset is disabled or invalid")
        with _lock, self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute(
                """UPDATE budget SET
                    spent_bytes = ?, attempts = 0, retry_attempts = 0,
                    forwarded_attempts = 0, rejected_attempts = 0,
                    records_attempted = 0, records_forwarded = 0,
                    records_rejected = 0, serialized_bytes_attempted = 0,
                    wire_bytes_attempted = 0, exhausted = 0, last_digest = '',
                    daily_spent = 0, daily_exhausted = 0,
                    monthly_spent = 0, monthly_exhausted = 0
                   WHERE id = 1""",
                (spent_bytes,),
            )
            db.commit()


budget = Budget(STATE_DB, BUDGET_BYTES, DAILY_BUDGET_BYTES, MONTHLY_BUDGET_BYTES)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        return

    def _json(self, status: int, payload: object, headers: dict[str, str] | None = None) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/stats":
            self._json(200, budget.snapshot())
            return
        if path == "/health":
            snapshot = budget.snapshot()
            failed = any(snapshot[name] for name in ("exhausted", "daily_exhausted", "monthly_exhausted"))
            self._json(507 if failed else 200, snapshot)
            return
        self._json(404, {"error": "not_found"})

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        if path == "/test/reset" and TEST_CONTROL:
            try:
                length = int(self.headers.get("Content-Length", "0"))
                payload = json.loads(self.rfile.read(length).decode())
                budget.reset_for_test(int(payload.get("spent_bytes", 0)))
            except (TypeError, ValueError, json.JSONDecodeError):
                self._json(400, {"error": "invalid_test_reset"})
                return
            self._json(200, budget.snapshot())
            return
        if path == "/test/clock" and TEST_CONTROL:
            global _test_now
            try:
                length = int(self.headers.get("Content-Length", "0"))
                value = json.loads(self.rfile.read(length).decode())["utc"]
                parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
                if parsed.tzinfo is None:
                    raise ValueError("timezone required")
                _test_now = parsed.astimezone(timezone.utc)
            except (KeyError, TypeError, ValueError, json.JSONDecodeError):
                self._json(400, {"error": "invalid_test_clock"})
                return
            self._json(200, budget.snapshot())
            return
        if path != "/log/v1":
            self._json(404, {"error": "not_found"})
            return

        inbound_key = self.headers.get("X-License-Key") or self.headers.get("License-Key") or self.headers.get("Api-Key")
        if inbound_key != INTERNAL_KEY:
            self._json(401, {"error": "invalid_internal_key"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._json(400, {"error": "invalid_content_length"})
            return
        if length <= 0 or length > MAX_REQUEST_BYTES:
            self._json(413, {"error": "request_too_large"})
            return
        wire = self.rfile.read(length)
        try:
            if self.headers.get("Content-Encoding", "").lower() == "gzip":
                with gzip.GzipFile(fileobj=io.BytesIO(wire)) as compressed:
                    serialized = compressed.read(MAX_REQUEST_BYTES + 1)
            else:
                serialized = wire
            if len(serialized) > MAX_REQUEST_BYTES:
                self._json(413, {"error": "request_too_large"})
                return
            payload = json.loads(serialized.decode("utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            self._json(400, {"error": "invalid_payload"})
            return

        records = _record_count(payload)
        digest = hashlib.sha256(serialized).hexdigest()
        if not budget.reserve(len(serialized), len(wire), records, digest):
            self._json(202, {"accepted": False, "budget_exhausted": True})
            return

        headers = {
            "Content-Type": self.headers.get("Content-Type", "application/json"),
            "X-License-Key": LICENSE_KEY,
        }
        if self.headers.get("Content-Encoding"):
            headers["Content-Encoding"] = self.headers["Content-Encoding"]
        request = urllib.request.Request(UPSTREAM, data=wire, headers=headers, method="POST")
        context = None if TLS_VERIFY else ssl._create_unverified_context()
        handlers: list[urllib.request.BaseHandler] = [NoRedirect()]
        if context is not None:
            handlers.append(urllib.request.HTTPSHandler(context=context))
        opener = urllib.request.build_opener(*handlers)
        try:
            with opener.open(request, timeout=UPSTREAM_TIMEOUT_SECONDS) as response:
                body = response.read()
                status = response.status
                retry_after = response.headers.get("Retry-After")
        except urllib.error.HTTPError as exc:
            body = exc.read()
            status = exc.code
            retry_after = exc.headers.get("Retry-After")
        except (OSError, urllib.error.URLError, TimeoutError):
            self._json(503, {"error": "upstream_unavailable"})
            return

        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        if retry_after:
            self.send_header("Retry-After", retry_after)
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    if not UPSTREAM.startswith("https://") and not TEST_CONTROL:
        raise SystemExit("production upstream must use https")
    if not LICENSE_KEY:
        raise SystemExit("PARKIO_NR_LOG_API_KEY is required")
    if MAX_REQUEST_BYTES <= 0 or MAX_REQUEST_BYTES > 1_048_576:
        raise SystemExit("PARKIO_NR_GATE_MAX_REQUEST_BYTES must be between 1 and 1048576")
    if UPSTREAM_TIMEOUT_SECONDS <= 0 or UPSTREAM_TIMEOUT_SECONDS > 30:
        raise SystemExit("PARKIO_NR_GATE_UPSTREAM_TIMEOUT_SECONDS must be between 0 and 30")
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = False
    server.block_on_close = True

    def stop(_signum: int, _frame: object) -> None:
        # BaseServer.shutdown() must be called from a different thread than
        # serve_forever() or it deadlocks. Existing request threads are allowed
        # to finish within the container's stop grace period.
        threading.Thread(target=server.shutdown, name="gate-shutdown", daemon=True).start()

    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, stop)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
