#!/usr/bin/env python3
"""Mock New Relic Log API receiver for Parkio Y02 isolated validation.

Accepts POST /log/v1 (Api-Key header required, value not validated beyond presence)
and stores JSON bodies under PARKIO_MOCK_NR_STORE for assertions.
"""

from __future__ import annotations

import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse


PORT = int(os.environ.get("PARKIO_MOCK_NR_PORT", "8089"))
STORE = Path(os.environ.get("PARKIO_MOCK_NR_STORE", "/tmp/received.jsonl"))
FAIL_ONCE = Path(os.environ.get("PARKIO_MOCK_NR_FAIL_FLAG", "/tmp/fail_once"))

_lock = threading.Lock()
_stats = {"accepted": 0, "rejected": 0, "bytes": 0}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:  # quiet
        return

    def _read(self) -> bytes:
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length) if length else b""

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/health":
            self._json(200, {"ok": True, **_stats})
            return
        if path == "/stats":
            self._json(200, dict(_stats))
            return
        if path == "/dump":
            if STORE.exists():
                body = STORE.read_bytes()
            else:
                body = b""
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self._json(404, {"error": "not_found"})

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        if path != "/log/v1":
            self._json(404, {"error": "not_found"})
            return
        api_key = self.headers.get("Api-Key") or self.headers.get("X-Insert-Key")
        if not api_key:
            with _lock:
                _stats["rejected"] += 1
            self._json(401, {"error": "missing_api_key"})
            return
        raw = self._read()
        if FAIL_ONCE.exists():
            FAIL_ONCE.unlink(missing_ok=True)
            with _lock:
                _stats["rejected"] += 1
            self._json(503, {"error": "injected_failure"})
            return
        STORE.parent.mkdir(parents=True, exist_ok=True)
        with _lock:
            with STORE.open("ab") as fh:
                fh.write(raw)
                fh.write(b"\n")
            _stats["accepted"] += 1
            _stats["bytes"] += len(raw)
        self._json(202, {"accepted": True})

    def do_DELETE(self) -> None:
        if urlparse(self.path).path == "/reset":
            with _lock:
                STORE.unlink(missing_ok=True)
                _stats["accepted"] = 0
                _stats["rejected"] = 0
                _stats["bytes"] = 0
            self._json(200, {"reset": True})
            return
        self._json(404, {"error": "not_found"})

    def _json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    STORE.parent.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"mock-nr-receiver listening on {PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
