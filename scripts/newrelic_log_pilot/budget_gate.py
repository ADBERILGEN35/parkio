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
import json
import os
import sqlite3
import ssl
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse


PORT = int(os.environ.get("PARKIO_NR_GATE_PORT", "8090"))
UPSTREAM = os.environ.get("PARKIO_NR_UPSTREAM_BASE_URI", "")
LICENSE_KEY = os.environ.get("PARKIO_NR_LOG_API_KEY", "")
STATE_DB = Path(os.environ.get("PARKIO_NR_BUDGET_STATE_DB", "/var/lib/parkio-nr-budget/budget.db"))
BUDGET_BYTES = int(os.environ.get("PARKIO_NR_BUDGET_BYTES", "0"))
MAX_REQUEST_BYTES = int(os.environ.get("PARKIO_NR_GATE_MAX_REQUEST_BYTES", "1048576"))
TLS_VERIFY = os.environ.get("PARKIO_NR_UPSTREAM_TLS_VERIFY", "on").lower() == "on"
TEST_CONTROL = os.environ.get("PARKIO_NR_GATE_TEST_CONTROL", "off").lower() == "on"
INTERNAL_KEY = os.environ.get("PARKIO_NR_GATE_INTERNAL_KEY", "gate-internal-not-a-secret")

_lock = threading.Lock()


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

    def __init__(self, path: Path, maximum: int) -> None:
        if maximum <= 0:
            raise ValueError("PARKIO_NR_BUDGET_BYTES must be positive")
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

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self.path, timeout=5, isolation_level=None)

    def snapshot(self) -> dict[str, int | str | bool]:
        with self._connect() as db:
            row = db.execute(
                f"SELECT {','.join(self.columns)} FROM budget WHERE id = 1"
            ).fetchone()
        assert row is not None
        result: dict[str, int | str | bool] = dict(zip(self.columns, row))
        result["exhausted"] = bool(result["exhausted"])
        result["remaining_bytes"] = max(0, int(result["max_bytes"]) - int(result["spent_bytes"]))
        result.pop("last_digest", None)
        return result

    def reserve(self, serialized_bytes: int, wire_bytes: int, records: int, digest: str) -> bool:
        with _lock, self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT max_bytes, spent_bytes, exhausted, last_digest FROM budget WHERE id = 1"
            ).fetchone()
            assert row is not None
            maximum, spent, exhausted, last_digest = int(row[0]), int(row[1]), bool(row[2]), str(row[3])
            retry = 1 if digest == last_digest and last_digest else 0
            admitted = not exhausted and serialized_bytes <= maximum - spent
            new_exhausted = exhausted or not admitted or serialized_bytes == maximum - spent
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
            db.execute("DELETE FROM budget")
            db.execute(
                "INSERT INTO budget VALUES (1, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, '')",
                (BUDGET_BYTES, spent_bytes),
            )
            db.commit()


budget = Budget(STATE_DB, BUDGET_BYTES)


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
            self._json(507 if snapshot["exhausted"] else 200, snapshot)
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
            serialized = gzip.decompress(wire) if self.headers.get("Content-Encoding", "").lower() == "gzip" else wire
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
            with opener.open(request, timeout=10) as response:
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
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
