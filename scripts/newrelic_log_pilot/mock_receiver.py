#!/usr/bin/env python3
"""Controllable local mock for the New Relic Log API contract.

The receiver validates the license-key header, JSON content type, gzip handling,
and JSON syntax. Tests can inject bounded 401/404/429/503 responses through the
loopback-only /control endpoint. Header values are never persisted or returned.
"""

from __future__ import annotations

import gzip
import json
import os
import ssl
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse


PORT = int(os.environ.get("PARKIO_MOCK_NR_PORT", "8089"))
STORE = Path(os.environ.get("PARKIO_MOCK_NR_STORE", "/tmp/received.jsonl"))
EXPECTED_KEY = os.environ.get("PARKIO_MOCK_NR_EXPECTED_KEY", "mock-not-a-real-license")
TLS_CERT = os.environ.get("PARKIO_MOCK_NR_TLS_CERT", "")
TLS_KEY = os.environ.get("PARKIO_MOCK_NR_TLS_KEY", "")

_lock = threading.Lock()
_stats: dict[str, object] = {
    "attempts": 0,
    "accepted": 0,
    "rejected": 0,
    "wire_bytes": 0,
    "json_bytes": 0,
    "gzip_requests": 0,
    "status_counts": {},
    "auth_header_names": {},
}
_failure = {"status": 202, "remaining": 0, "persistent": False, "retry_after": 1}


def _count_status(status: int) -> None:
    counts = _stats["status_counts"]
    assert isinstance(counts, dict)
    key = str(status)
    counts[key] = int(counts.get(key, 0)) + 1


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        return

    def _read(self) -> bytes:
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length) if length else b""

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/health":
            with _lock:
                payload = {"ok": True, **_stats}
            self._json(200, payload)
            return
        if path == "/stats":
            with _lock:
                payload = json.loads(json.dumps(_stats))
            self._json(200, payload)
            return
        if path == "/dump":
            body = STORE.read_bytes() if STORE.exists() else b""
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self._json(404, {"error": "not_found"})

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        if path == "/control":
            self._control()
            return
        if path != "/log/v1":
            self._json(404, {"error": "not_found"})
            return

        wire = self._read()
        with _lock:
            _stats["attempts"] = int(_stats["attempts"]) + 1
            _stats["wire_bytes"] = int(_stats["wire_bytes"]) + len(wire)

        auth_headers = ("Api-Key", "License-Key", "X-License-Key", "X-Insert-Key")
        header_name = next((name for name in auth_headers if self.headers.get(name)), None)
        key = self.headers.get(header_name) if header_name else None
        if not key or key != EXPECTED_KEY:
            self._reject(401, "invalid_license_key")
            return
        with _lock:
            names = _stats["auth_header_names"]
            assert isinstance(names, dict)
            assert header_name is not None
            names[header_name] = int(names.get(header_name, 0)) + 1

        content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        if content_type not in {"application/json", "json"}:
            self._reject(415, "invalid_content_type")
            return

        raw = wire
        if self.headers.get("Content-Encoding", "").lower() == "gzip":
            try:
                raw = gzip.decompress(wire)
            except (OSError, EOFError):
                self._reject(400, "invalid_gzip")
                return
            with _lock:
                _stats["gzip_requests"] = int(_stats["gzip_requests"]) + 1

        try:
            json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._reject(400, "invalid_json")
            return

        with _lock:
            status = int(_failure["status"])
            active = bool(_failure["persistent"]) or int(_failure["remaining"]) > 0
            if active and not _failure["persistent"]:
                _failure["remaining"] = int(_failure["remaining"]) - 1
        if active and status != 202:
            headers = {"Retry-After": str(_failure["retry_after"])} if status == 429 else None
            self._reject(status, "injected_failure", headers)
            return

        STORE.parent.mkdir(parents=True, exist_ok=True)
        with _lock:
            with STORE.open("ab") as handle:
                handle.write(raw)
                handle.write(b"\n")
            _stats["accepted"] = int(_stats["accepted"]) + 1
            _stats["json_bytes"] = int(_stats["json_bytes"]) + len(raw)
            _count_status(202)
            request_id = int(_stats["accepted"])
        self._json(202, {"requestId": f"mock-{request_id}"})

    def do_DELETE(self) -> None:
        if urlparse(self.path).path != "/reset":
            self._json(404, {"error": "not_found"})
            return
        with _lock:
            STORE.unlink(missing_ok=True)
            _stats.update(
                attempts=0,
                accepted=0,
                rejected=0,
                wire_bytes=0,
                json_bytes=0,
                gzip_requests=0,
                status_counts={},
                auth_header_names={},
            )
            _failure.update(status=202, remaining=0, persistent=False, retry_after=1)
        self._json(200, {"reset": True})

    def _control(self) -> None:
        try:
            body = json.loads(self._read().decode("utf-8"))
            status = int(body.get("status", 202))
            remaining = int(body.get("count", 0))
            persistent = bool(body.get("persistent", False))
            retry_after = max(1, int(body.get("retry_after", 1)))
            if status not in {202, 401, 404, 429, 503} or remaining < 0:
                raise ValueError("unsupported control")
        except (UnicodeDecodeError, json.JSONDecodeError, TypeError, ValueError):
            self._json(400, {"error": "invalid_control"})
            return
        with _lock:
            _failure.update(
                status=status,
                remaining=remaining,
                persistent=persistent,
                retry_after=retry_after,
            )
        self._json(200, {"status": status, "count": remaining, "persistent": persistent})

    def _reject(self, status: int, reason: str, headers: dict[str, str] | None = None) -> None:
        with _lock:
            _stats["rejected"] = int(_stats["rejected"]) + 1
            _count_status(status)
        self._json(status, {"error": reason}, headers)

    def _json(self, status: int, payload: dict, headers: dict[str, str] | None = None) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        if headers:
            for key, value in headers.items():
                self.send_header(key, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    STORE.parent.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    if TLS_CERT and TLS_KEY:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(TLS_CERT, TLS_KEY)
        server.socket = context.wrap_socket(server.socket, server_side=True)
    print(f"mock-nr-receiver listening on {PORT} tls={bool(TLS_CERT and TLS_KEY)}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
