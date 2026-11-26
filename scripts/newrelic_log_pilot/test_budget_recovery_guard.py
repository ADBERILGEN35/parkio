#!/usr/bin/env python3
"""Real budget-gate subprocesses with a localhost-only synthetic upstream."""

from __future__ import annotations

from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import signal
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request


GATE = Path(__file__).with_name("budget_gate.py")
SYNTHETIC_KEY = "synthetic-test-key"


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def request(port: int, path: str, payload: object | None = None) -> tuple[int, dict]:
    body = None if payload is None else json.dumps(payload).encode()
    headers = {"Content-Type": "application/json", "X-License-Key": "gate-internal-not-a-secret"}
    target = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=body, headers=headers,
                                    method="POST" if body is not None else "GET")
    try:
        with urllib.request.urlopen(target, timeout=2) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as exc:
        return exc.code, json.load(exc)


@contextmanager
def mock_upstream():
    received = []

    class Upstream(BaseHTTPRequestHandler):
        def log_message(self, _format: str, *_args: object) -> None:
            pass

        def do_POST(self) -> None:
            received.append(self.rfile.read(int(self.headers["Content-Length"])))
            self.send_response(202)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"{}")

    server = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        yield server.server_port, received
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)


class RecoveryGuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="parkio-nr-guard-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def env(self, ledger: Path, upstream_port: int, *, guard: bool) -> dict[str, str]:
        return {**os.environ,
                "PARKIO_NR_BUDGET_STATE_DB": str(ledger),
                "PARKIO_NR_BUDGET_BYTES": "0",
                "PARKIO_NR_DAILY_BUDGET_BYTES": "100",
                "PARKIO_NR_MONTHLY_BUDGET_BYTES": "250",
                "PARKIO_NR_BUDGET_RECOVERY_MODE": "on" if guard else "off",
                "PARKIO_NR_LOG_API_KEY": SYNTHETIC_KEY,
                "PARKIO_NR_GATE_TEST_CONTROL": "on",
                "PARKIO_NR_UPSTREAM_BASE_URI": f"http://127.0.0.1:{upstream_port}/log/v1"}

    def make_ledger(self, ledger: Path, upstream_port: int, *, exhausted: bool = True) -> None:
        script = "import runpy; runpy.run_path(" + repr(str(GATE)) + ")"
        result = subprocess.run([sys.executable, "-c", script], env=self.env(ledger, upstream_port, guard=False),
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        if exhausted:
            with sqlite3.connect(ledger) as db:
                db.execute("UPDATE budget SET exhausted=1 WHERE id=1")

    def start(self, ledger: Path, upstream_port: int, *, guard: bool) -> tuple[subprocess.Popen, int]:
        port = free_port()
        env = self.env(ledger, upstream_port, guard=guard)
        env["PARKIO_NR_GATE_PORT"] = str(port)
        process = subprocess.Popen([sys.executable, str(GATE)], env=env,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if process.poll() is not None:
                self.fail(f"gate exited before readiness: {process.communicate()[1]}")
            try:
                request(port, "/stats")
                return process, port
            except (OSError, urllib.error.URLError):
                time.sleep(0.05)
        process.kill()
        process.wait(timeout=2)
        self.fail("gate did not start")

    def stop(self, process: subprocess.Popen) -> None:
        if process.poll() is None:
            process.send_signal(signal.SIGTERM)
        process.communicate(timeout=5)

    def assert_start_rejected(self, ledger: Path, upstream_port: int) -> None:
        result = subprocess.run([sys.executable, str(GATE)],
                                env=self.env(ledger, upstream_port, guard=True),
                                capture_output=True, text=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)

    def test_normal_first_install_still_creates_ledger_and_forwards(self):
        with mock_upstream() as (upstream_port, received):
            ledger = self.root / "normal/budget.db"
            process, port = self.start(ledger, upstream_port, guard=False)
            try:
                self.assertTrue(ledger.is_file())
                status, _ = request(port, "/log/v1", [{"logs": [{"message": "synthetic"}]}])
                self.assertEqual(status, 202)
                self.assertEqual(len(received), 1)
            finally:
                self.stop(process)

    def test_guard_rejects_missing_unreadable_corrupt_incompatible_and_unexhausted(self):
        with mock_upstream() as (upstream_port, received):
            missing = self.root / "missing/budget.db"
            self.assert_start_rejected(missing, upstream_port)
            self.assertFalse(missing.exists())
            self.assertFalse(missing.parent.exists())

            unreadable = self.root / "unreadable.db"
            self.make_ledger(unreadable, upstream_port)
            unreadable.chmod(0)
            self.assert_start_rejected(unreadable, upstream_port)

            corrupt = self.root / "corrupt.db"
            corrupt.write_bytes(b"synthetic invalid SQLite")
            self.assert_start_rejected(corrupt, upstream_port)

            incompatible = self.root / "incompatible.db"
            with sqlite3.connect(incompatible) as db:
                db.execute("CREATE TABLE budget(id INTEGER PRIMARY KEY)")
                db.execute("INSERT INTO budget VALUES (1)")
            self.assert_start_rejected(incompatible, upstream_port)

            unexhausted = self.root / "unexhausted.db"
            self.make_ledger(unexhausted, upstream_port, exhausted=False)
            self.assert_start_rejected(unexhausted, upstream_port)
            symlink = self.root / "symlink.db"
            symlink.symlink_to(unexhausted)
            self.assert_start_rejected(symlink, upstream_port)
            self.assertEqual(received, [])

    def test_exhausted_recovery_stays_closed_across_restart_day_and_month(self):
        with mock_upstream() as (upstream_port, received):
            ledger = self.root / "exhausted.db"
            self.make_ledger(ledger, upstream_port)
            for iteration in range(2):
                process, port = self.start(ledger, upstream_port, guard=True)
                try:
                    self.assertEqual(request(port, "/health")[0], 507)
                    for instant in ("2026-09-24T00:01:00Z", "2026-10-01T00:01:00Z"):
                        self.assertEqual(request(port, "/test/clock", {"utc": instant})[0], 200)
                        status, body = request(port, "/log/v1", [{"logs": [{"message": "synthetic"}]}])
                        self.assertEqual((status, body["accepted"]), (202, False))
                        self.assertEqual(request(port, "/health")[0], 507)
                    self.assertEqual(received, [])
                finally:
                    self.stop(process)
            with sqlite3.connect(ledger) as db:
                self.assertEqual(db.execute("SELECT exhausted FROM budget WHERE id=1").fetchone(), (1,))

    def test_disappearing_or_replaced_ledger_returns_503_without_upstream(self):
        with mock_upstream() as (upstream_port, received):
            for mode in ("missing", "replaced"):
                with self.subTest(mode=mode):
                    ledger = self.root / f"{mode}.db"
                    self.make_ledger(ledger, upstream_port)
                    process, port = self.start(ledger, upstream_port, guard=True)
                    try:
                        ledger.unlink()
                        if mode == "replaced":
                            replacement = self.root / "replacement.db"
                            self.make_ledger(replacement, upstream_port, exhausted=False)
                            os.replace(replacement, ledger)
                        status, body = request(port, "/log/v1", [{"logs": [{"message": "synthetic"}]}])
                        self.assertEqual((status, body["error"]), (503, "recovery_ledger_unavailable"))
                        self.assertEqual(request(port, "/health")[0], 503)
                        if mode == "missing":
                            self.assertFalse(ledger.exists())
                        self.assertEqual(received, [])
                    finally:
                        self.stop(process)

    def test_guard_refuses_external_release_and_test_reset(self):
        with mock_upstream() as (upstream_port, received):
            ledger = self.root / "externally-cleared.db"
            self.make_ledger(ledger, upstream_port)
            process, port = self.start(ledger, upstream_port, guard=True)
            try:
                self.assertEqual(request(port, "/test/reset", {"spent_bytes": 0})[0], 400)
                with sqlite3.connect(ledger) as db:
                    db.execute("UPDATE budget SET exhausted=0 WHERE id=1")
                status, body = request(port, "/log/v1", [{"logs": [{"message": "synthetic"}]}])
                self.assertEqual((status, body["error"]), (503, "recovery_ledger_unavailable"))
                self.assertEqual(request(port, "/health")[0], 503)
                self.assertEqual(received, [])
            finally:
                self.stop(process)


if __name__ == "__main__":
    unittest.main()
