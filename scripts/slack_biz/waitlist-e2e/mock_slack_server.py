#!/usr/bin/env python3
"""Test-only mock Slack incoming webhook for the waitlist e2e stack.

Records every POST (headers minus none, JSON body) as one line in
$MOCK_SLACK_LOG and answers with statuses from $MOCK_SLACK_SEQUENCE
(comma list, e.g. "503,200"), then 200 "ok". Never forwards anywhere.
"""

from __future__ import annotations

import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = os.environ.get("MOCK_SLACK_LOG", "/data/requests.jsonl")
SEQUENCE = [int(x) for x in os.environ.get("MOCK_SLACK_SEQUENCE", "").split(",") if x.strip()]
_lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # noqa: A003
        return

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8", "replace")
        with _lock:
            status = SEQUENCE.pop(0) if SEQUENCE else 200
            with open(LOG, "a", encoding="utf-8") as fh:
                fh.write(json.dumps({"path": self.path, "status": status, "body": body}) + "\n")
        payload = b"ok" if status == 200 else b"mock_error"
        self.send_response(status)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):  # noqa: N802
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"mock-slack-ok")


if __name__ == "__main__":
    open(LOG, "a").close()
    port = int(os.environ.get("MOCK_SLACK_PORT", "8080"))
    ThreadingHTTPServer((os.environ.get("MOCK_SLACK_BIND", "0.0.0.0"), port), Handler).serve_forever()
