#!/usr/bin/env python3
"""Stub HTTP proof for NR gate probe textfile export (#413)."""
from __future__ import annotations

import http.server
import socket
import threading
import unittest
from pathlib import Path
import sys
import os
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/newrelic_log_pilot/export_gate_probe_textfile.py"

if str(SCRIPT.parent) not in sys.path:
    sys.path.insert(0, str(SCRIPT.parent))

import export_gate_probe_textfile as probe  # noqa: E402


class _Handler(http.server.BaseHTTPRequestHandler):
    status_code = 200

    def do_GET(self) -> None:  # noqa: N802
        self.send_response(self.status_code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok":true}')

    def log_message(self, format: str, *args) -> None:  # noqa: A003
        return


def _serve(status: int) -> tuple[http.server.HTTPServer, str]:
    _Handler.status_code = status
    server = http.server.HTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address
    return server, f"http://{host}:{port}/health"



def assert_mode_0644(path: Path) -> None:
    mode = path.stat().st_mode & 0o777
    if mode != 0o644:
        raise AssertionError(f"expected mode 0644 for {path}, got {oct(mode)}")

class GateProbeTextfileTest(unittest.TestCase):
    def test_absent_clears_prom(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "parkio_nr_budget_gate.prom"
            out.write_text("parkio_nr_budget_gate_probe_success 0\n", encoding="utf-8")
            # Reserved closed port.
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", 0))
                port = sock.getsockname()[1]
            rc = probe.main(
                ["--output", str(out), "--http-url", f"http://127.0.0.1:{port}/health"]
            )
            self.assertEqual(rc, 0)
            self.assertFalse(out.exists())

    def test_http_200_writes_success(self) -> None:
        server, url = _serve(200)
        try:
            with tempfile.TemporaryDirectory() as tmp:
                out = Path(tmp) / "parkio_nr_budget_gate.prom"
                rc = probe.main(["--output", str(out), "--http-url", url])
                self.assertEqual(rc, 0)
                body = out.read_text(encoding="utf-8")
                self.assertIn("parkio_nr_budget_gate_probe_success 1", body)
                assert_mode_0644(out)
                leftover = list(out.parent.glob(".parkio_nr_budget_gate.*.tmp"))
                self.assertEqual(leftover, [])
        finally:
            server.shutdown()

    def test_http_507_writes_failure(self) -> None:
        server, url = _serve(507)
        try:
            with tempfile.TemporaryDirectory() as tmp:
                out = Path(tmp) / "parkio_nr_budget_gate.prom"
                rc = probe.main(["--output", str(out), "--http-url", url])
                self.assertEqual(rc, 0)
                body = out.read_text(encoding="utf-8")
                self.assertIn("parkio_nr_budget_gate_probe_success 0", body)
                assert_mode_0644(out)
        finally:
            server.shutdown()


if __name__ == "__main__":
    unittest.main()
