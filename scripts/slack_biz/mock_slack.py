"""Local mock Slack incoming-webhook endpoint for isolated acceptance."""

from __future__ import annotations

import json
import threading
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Any
from urllib.parse import urlparse


@dataclass
class CapturedRequest:
    method: str
    path: str
    headers: dict[str, str]
    body: bytes
    json_body: dict[str, Any] | None


@dataclass
class MockSlackState:
    requests: list[CapturedRequest] = field(default_factory=list)
    # (status, body, headers, mode) mode: normal | drop_connection | hang
    status_sequence: list[tuple[int, str, dict[str, str], str]] = field(
        default_factory=list
    )
    default_status: int = 200
    default_body: str = "ok"
    lock: threading.Lock = field(default_factory=threading.Lock)

    def next_response(self) -> tuple[int, str, dict[str, str], str]:
        with self.lock:
            if self.status_sequence:
                return self.status_sequence.pop(0)
            return self.default_status, self.default_body, {}, "normal"

    def record(self, req: CapturedRequest) -> None:
        with self.lock:
            self.requests.append(req)

    def clear(self) -> None:
        with self.lock:
            self.requests.clear()
            self.status_sequence.clear()


class MockSlackServer:
    def __init__(self, host: str = "127.0.0.1", port: int = 0):
        self.state = MockSlackState()
        parent = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, fmt: str, *args) -> None:  # noqa: A003
                return

            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("Content-Length", "0"))
                body = self.rfile.read(length) if length else b""
                try:
                    parsed = json.loads(body.decode("utf-8")) if body else None
                except json.JSONDecodeError:
                    parsed = None
                parent.state.record(
                    CapturedRequest(
                        method="POST",
                        path=self.path,
                        headers={k: v for k, v in self.headers.items()},
                        body=body,
                        json_body=parsed if isinstance(parsed, dict) else None,
                    )
                )
                status, resp_body, extra_headers, mode = parent.state.next_response()
                if mode == "drop_connection":
                    # Accept/read request then close without HTTP response → client ambiguous
                    try:
                        self.connection.close()
                    except OSError:
                        pass
                    return
                if mode == "hang":
                    time.sleep(30)
                    return
                payload = resp_body.encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(payload)))
                for hk, hv in extra_headers.items():
                    self.send_header(hk, hv)
                self.end_headers()
                self.wfile.write(payload)

            def do_GET(self) -> None:  # noqa: N802
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"mock-slack-ok")

        self._httpd = HTTPServer((host, port), Handler)
        self.host, self.port = self._httpd.server_address[0], self._httpd.server_address[1]
        self._thread: threading.Thread | None = None

    @property
    def webhook_url(self) -> str:
        return f"http://{self.host}:{self.port}/services/mock/webhook"

    def start(self) -> None:
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._httpd.shutdown()
        self._httpd.server_close()
        if self._thread:
            self._thread.join(timeout=5)

    def enqueue_response(
        self,
        status: int,
        body: str = "ok",
        headers: dict[str, str] | None = None,
        *,
        mode: str = "normal",
    ) -> None:
        self.state.status_sequence.append((status, body, headers or {}, mode))

    def enqueue_drop_connection(self) -> None:
        """Accept/read body then close without HTTP response (ambiguous)."""
        self.enqueue_response(200, "ok", mode="drop_connection")


def assert_no_real_slack_url(url: str) -> None:
    host = urlparse(url).hostname or ""
    if host.endswith("slack.com") or host.endswith("slack-msgs.com"):
        raise RuntimeError(f"refusing real Slack destination in acceptance: {url}")
