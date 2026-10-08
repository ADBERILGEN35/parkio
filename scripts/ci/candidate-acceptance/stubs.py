#!/usr/bin/env python3
"""Disposable user-status stub + optional auth epoch interceptor.

Not production. Synthetic isolated stack only.
"""
from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

AUTH_UPSTREAM = "http://auth-service:8081"
MODE = {"epoch": "proxy"}  # proxy|null|malformed|mismatch|empty|missing|http500
LOCK = threading.Lock()


def _read(handler: BaseHTTPRequestHandler) -> bytes:
    n = int(handler.headers.get("Content-Length") or 0)
    return handler.rfile.read(n) if n else b""


def _send(handler: BaseHTTPRequestHandler, code: int, body: bytes, content_type: str = "application/json"):
    handler.send_response(code)
    handler.send_header("Content-Type", content_type)
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    if body:
        handler.wfile.write(body)


class UserStatusHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        return

    def do_GET(self):
        parsed = urlparse(self.path)
        parts = parsed.path.strip("/").split("/")
        if parts[:2] == ["internal", "users"] and len(parts) == 4 and parts[3] == "status":
            user_id = parts[2]
            body = json.dumps({"userId": user_id, "status": "ACTIVE"}).encode()
            _send(self, 200, body)
            return
        if parsed.path == "/actuator/health/readiness":
            _send(self, 200, b'{"status":"UP"}')
            return
        _send(self, 404, b'{"error":"not found"}')

    def do_HEAD(self):
        self.do_GET()


class AuthInterceptHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        return

    def do_GET(self):
        parsed = urlparse(self.path)
        parts = parsed.path.strip("/").split("/")
        if parsed.path in ("/control/mode", "/actuator/health/readiness"):
            if parsed.path.endswith("readiness"):
                _send(self, 200, b'{"status":"UP"}')
                return
            with LOCK:
                body = json.dumps(MODE).encode()
            _send(self, 200, body)
            return
        if parts[:3] == ["internal", "auth", "users"] and len(parts) == 5 and parts[4] == "session-epoch":
            user_id = parts[3]
            with LOCK:
                mode = MODE["epoch"]
            if mode == "proxy":
                self._proxy()
                return
            if mode == "missing":
                _send(self, 404, b'{"error":"missing"}')
                return
            if mode == "http500":
                _send(self, 500, b'{"error":"upstream"}')
                return
            if mode == "empty":
                _send(self, 200, b"", "application/json")
                return
            if mode == "null":
                _send(self, 200, json.dumps({"userId": user_id, "sessionEpoch": None}).encode())
                return
            if mode == "malformed":
                _send(self, 200, b'{"userId":"%s","sessionEpoch":' % user_id.encode(), "application/json")
                return
            if mode == "mismatch":
                other = "22222222-2222-2222-2222-222222222222"
                _send(self, 200, json.dumps({"userId": other, "sessionEpoch": 0}).encode())
                return
            _send(self, 500, b'{"error":"unknown mode"}')
            return
        self._proxy()

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path == "/control/mode":
            raw = _read(self)
            data = json.loads(raw.decode() or "{}")
            with LOCK:
                if "epoch" in data:
                    MODE["epoch"] = str(data["epoch"])
                body = json.dumps(MODE).encode()
            _send(self, 200, body)
            return
        self._proxy()

    def do_DELETE(self):
        self._proxy()

    def do_PUT(self):
        self._proxy()

    def do_PATCH(self):
        self._proxy()

    def _proxy(self):
        url = AUTH_UPSTREAM + self.path
        body = _read(self)
        headers = {k: v for k, v in self.headers.items() if k.lower() not in {"host", "content-length"}}
        req = Request(url, data=body if body else None, method=self.command, headers=headers)
        try:
            with urlopen(req, timeout=15) as resp:
                payload = resp.read()
                self.send_response(resp.status)
                for hk, hv in resp.headers.items():
                    if hk.lower() in {"transfer-encoding", "connection"}:
                        continue
                    self.send_header(hk, hv)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                if payload:
                    self.wfile.write(payload)
        except HTTPError as ex:
            payload = ex.read()
            _send(self, ex.code, payload, ex.headers.get_content_type() if ex.headers else "application/json")
        except URLError:
            _send(self, 503, b'{"error":"auth upstream unavailable"}')


def main():
    user = ThreadingHTTPServer(("0.0.0.0", 8082), UserStatusHandler)
    intercept = ThreadingHTTPServer(("0.0.0.0", 8081), AuthInterceptHandler)
    threading.Thread(target=user.serve_forever, daemon=True).start()
    print("user-status :8082  auth-intercept :8081", flush=True)
    intercept.serve_forever()


if __name__ == "__main__":
    main()
