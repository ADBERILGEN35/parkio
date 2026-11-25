#!/usr/bin/env python3
"""Disposable user-status stub + optional auth epoch interceptor.

Not production. Synthetic isolated stack only.
"""
from __future__ import annotations

import http.client
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

AUTH_UPSTREAM = "http://auth-service:8081"
MODE = {"epoch": "proxy"}  # proxy|null|malformed|mismatch|empty|missing|http500
LOCK = threading.Lock()
# Not relayed from auth-service's response: the hop-by-hop headers, and the headers this stub writes
# itself (send_response writes Server and Date, _proxy computes Content-Length for the body it read, so
# a chunked answer goes out with a Content-Length and no Transfer-Encoding). A relayed Content-Length
# beside the computed one makes the gateway's Netty client refuse the response, even when both are
# equal. auth-service's Tomcat sends Date but no Server, so in the real stack only Date was duplicated.
NOT_RELAYED = {"connection", "keep-alive", "transfer-encoding", "content-length", "date", "server"}
# Not forwarded from the gateway's request: Host (http.client writes auth-service's), Content-Length
# (computed for the body _read returns) and the hop-by-hop headers. Every other line goes on as it
# came, so a repeated header stays repeated.
NOT_FORWARDED = {"host", "content-length", "connection", "keep-alive", "te", "transfer-encoding", "upgrade"}


def _read(handler: BaseHTTPRequestHandler) -> bytes:
    """The request body, by its Content-Length or, for a chunked request, its chunks."""
    if "chunked" in (handler.headers.get("Transfer-Encoding") or "").lower():
        chunks = []
        while size := int(handler.rfile.readline().split(b";", 1)[0], 16):
            chunks.append(handler.rfile.read(size))
            handler.rfile.readline()  # the CRLF after the chunk
        while handler.rfile.readline() not in (b"\r\n", b"\n", b""):  # trailer fields end at an empty line
            pass
        return b"".join(chunks)
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
        """Relay one request to auth-service and its answer back, whatever the status.

        http.client follows no redirect and raises nothing for a 4xx or 5xx, so a 3xx goes back as it
        came, and an error answer keeps its headers (Set-Cookie, WWW-Authenticate, Retry-After) and
        its full Content-Type.
        """
        body = _read(self)
        lines = [(k, v) for k, v in self.headers.items() if k.lower() not in NOT_FORWARDED]
        upstream = urlparse(AUTH_UPSTREAM)
        conn = http.client.HTTPConnection(upstream.hostname, upstream.port, timeout=15)
        try:
            conn.putrequest(self.command, self.path,
                            skip_accept_encoding=any(k.lower() == "accept-encoding" for k, _ in lines))
            for name, value in lines:
                conn.putheader(name, value)
            if body or self.command in ("POST", "PUT", "PATCH"):
                conn.putheader("Content-Length", str(len(body)))
            conn.putheader("Connection", "close")
            conn.endheaders(body or None)
            resp = conn.getresponse()
            status, headers, payload = resp.status, resp.getheaders(), resp.read()
        except (OSError, http.client.HTTPException):
            _send(self, 503, b'{"error":"auth upstream unavailable"}')
            return
        finally:
            conn.close()
        self.send_response(status)
        for name, value in headers:
            if name.lower() not in NOT_RELAYED:
                self.send_header(name, value)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if payload:
            self.wfile.write(payload)


def main():
    user = ThreadingHTTPServer(("0.0.0.0", 8082), UserStatusHandler)
    intercept = ThreadingHTTPServer(("0.0.0.0", 8081), AuthInterceptHandler)
    threading.Thread(target=user.serve_forever, daemon=True).start()
    print("user-status :8082  auth-intercept :8081", flush=True)
    intercept.serve_forever()


if __name__ == "__main__":
    main()
