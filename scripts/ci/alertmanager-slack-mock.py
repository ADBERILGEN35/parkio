#!/usr/bin/env python3
"""Local Slack-compatible catcher for Alertmanager template render tests.

Never logs Authorization, webhook URLs, or tokens. Writes receipt JSON only.
"""
from __future__ import annotations

import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

RECEIPTS = Path(os.environ.get("PARKIO_AM_RENDER_RECEIPTS", "/tmp/parkio-am-render-receipts"))
RECEIPTS.mkdir(parents=True, exist_ok=True)
HOST = os.environ.get("PARKIO_AM_RENDER_HOST", "127.0.0.1")
PORT = int(os.environ.get("PARKIO_AM_RENDER_PORT", "18080"))


def _utcnow() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        return

    def _write(self, code: int, body: bytes, content_type: str = "text/plain") -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path in ("/health", "/"):
            self._write(200, b"ok")
            return
        self._write(404, b"not found")

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length") or "0")
        raw = self.rfile.read(length) if length else b""
        try:
            payload = json.loads(raw.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self._write(400, b"invalid json")
            return
        attachments = payload.get("attachments") or []
        title = str(payload.get("title") or "")
        text = str(payload.get("text") or "")
        if attachments:
            first = attachments[0] if isinstance(attachments[0], dict) else {}
            title = str(first.get("title") or title)
            text = str(first.get("text") or text)
        receipt = {
            "receivedAt": _utcnow(),
            "path": self.path,
            "title": title,
            "text": text,
            "attachmentCount": len(attachments),
        }
        name = f"{int(time.time() * 1000)}-{len(list(RECEIPTS.glob('*.json')))}.json"
        (RECEIPTS / name).write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
        self._write(200, b"ok")


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
