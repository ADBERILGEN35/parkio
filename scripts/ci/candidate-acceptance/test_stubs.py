"""Checks for the U03 acceptance stubs (stubs.py): loopback sockets only, no Docker.

Run: python3 scripts/ci/candidate-acceptance/test_stubs.py (scripts/ci/test-candidate-scripts.sh runs it).
"""
from __future__ import annotations

import http.client
import importlib.util
import sys
import threading
import unittest
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.dont_write_bytecode = True  # no __pycache__ beside the stubs in the checkout
_SPEC = importlib.util.spec_from_file_location("candidate_acceptance_stubs", Path(__file__).with_name("stubs.py"))
stubs = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(stubs)

USER_ID = "11111111-1111-1111-1111-111111111111"
# What the fake auth-service answers, by (method, path): fixed-length JSON with its own Content-Length,
# as Spring Boot 4.1 sends it. Spring Boot 3 sent these bodies chunked, which hid the duplicate header.
UPSTREAM = {
    ("POST", "/api/v1/auth/verify-email"): (200, b'{"verified":true}'),
    ("GET", f"/internal/auth/users/{USER_ID}/session-epoch"): (200, b'{"userId":"%s","sessionEpoch":0}' % USER_ID.encode()),
    ("POST", "/api/v1/auth/login"): (401, b'{"code":"INVALID_CREDENTIALS"}'),
}


class FakeAuthService(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        return

    def _answer(self):
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        code, body = UPSTREAM[(self.command, self.path)]
        self.send_response(code)  # writes Server and Date, as auth-service's server does
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST = _answer


class AuthInterceptRelayTest(unittest.TestCase):
    """The gateway's Netty client refuses a response with two Content-Length headers, even equal ones
    ("Multiple Content-Length values found: [127, 127]", candidate run 45262420375)."""

    @classmethod
    def setUpClass(cls):
        urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))  # never an environment proxy
        cls.servers = [ThreadingHTTPServer(("127.0.0.1", 0), FakeAuthService),
                       ThreadingHTTPServer(("127.0.0.1", 0), stubs.AuthInterceptHandler)]
        for server in cls.servers:
            threading.Thread(target=server.serve_forever, daemon=True).start()
        stubs.AUTH_UPSTREAM = "http://127.0.0.1:%d" % cls.servers[0].server_address[1]
        cls.intercept_port = cls.servers[1].server_address[1]

    @classmethod
    def tearDownClass(cls):
        for server in cls.servers:
            server.shutdown()
            server.server_close()
        urllib.request.install_opener(None)

    def relay(self, method, path):
        conn = http.client.HTTPConnection("127.0.0.1", self.intercept_port, timeout=10)
        try:
            conn.request(method, path, body=b"{}" if method == "POST" else None, headers={"Content-Type": "application/json"})
            resp = conn.getresponse()
            return resp.status, resp.getheaders(), resp.read()
        finally:
            conn.close()

    def test_relayed_answers_carry_one_content_length_and_the_upstream_body(self):
        for (method, path), (code, body) in UPSTREAM.items():
            with self.subTest(method=method, path=path):
                status, headers, payload = self.relay(method, path)
                self.assertEqual(status, code)
                self.assertEqual([v for k, v in headers if k.lower() == "content-length"], [str(len(body))])
                self.assertEqual(payload, body)

    def test_relayed_answers_carry_one_date_and_one_server(self):
        # send_response writes both itself, so the upstream's copies must not be relayed beside them.
        for method, path in UPSTREAM:
            with self.subTest(method=method, path=path):
                _, headers, _ = self.relay(method, path)
                names = [k.lower() for k, _ in headers]
                self.assertEqual((names.count("date"), names.count("server")), (1, 1))


if __name__ == "__main__":
    unittest.main()
