"""Checks for the U03 acceptance stubs (stubs.py): loopback sockets only, no Docker.

Run: python3 scripts/ci/candidate-acceptance/test_stubs.py (scripts/ci/test-candidate-scripts.sh runs it).
"""
from __future__ import annotations

import importlib.util
import socket
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import NamedTuple

sys.dont_write_bytecode = True  # no __pycache__ beside the stubs in the checkout
_SPEC = importlib.util.spec_from_file_location("candidate_acceptance_stubs", Path(__file__).with_name("stubs.py"))
stubs = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(stubs)

USER_ID = "11111111-1111-1111-1111-111111111111"
PROBLEM = "application/problem+json;charset=UTF-8"


class Answer(NamedTuple):
    status: int
    body: bytes
    headers: tuple[tuple[str, str], ...] = ()
    chunked: bool = False
    content_type: str | None = "application/json"


# What the fake auth-service answers, by (method, path). One Spring Boot 4.1 auth-service answers both
# ways in one flow (candidate run 45262420375): verify-email, a plain return value, came with a
# Content-Length; register, a ResponseEntity, came chunked.
UPSTREAM = {
    ("POST", "/api/v1/auth/verify-email"): Answer(200, b'{"verified":true}'),
    ("GET", f"/internal/auth/users/{USER_ID}/session-epoch"):
        Answer(200, b'{"userId":"%s","sessionEpoch":0}' % USER_ID.encode()),
    ("POST", "/api/v1/auth/register"): Answer(201, b'{"userId":"%s","status":"PENDING"}' % USER_ID.encode(),
                                              chunked=True),
    ("POST", "/api/v1/auth/refresh-token"): Answer(200, b'{"accessToken":"fixture-access"}', (
        ("Set-Cookie", "parkio_refresh=fixture-refresh; Path=/api/v1/auth; HttpOnly"),
        ("Set-Cookie", "parkio_device=fixture-device; Path=/; Secure"),
    ), chunked=True),
    ("POST", "/api/v1/auth/login"): Answer(401, b'{"code":"INVALID_CREDENTIALS"}', (
        ("WWW-Authenticate", 'Bearer error="invalid_token"'),
        ("Set-Cookie", "parkio_refresh=; Max-Age=0; Path=/api/v1/auth"),
    ), content_type=PROBLEM),
    ("POST", "/api/v1/auth/resend-verification"): Answer(429, b'{"code":"RATE_LIMITED"}', (
        ("Retry-After", "30"),
    ), chunked=True, content_type=PROBLEM),
    ("POST", "/api/v1/auth/logout"): Answer(503, b'{"code":"UNAVAILABLE"}', (("Retry-After", "5"),),
                                            content_type=PROBLEM),
    ("GET", "/api/v1/auth/fixture-redirect"): Answer(302, b"", (("Location", "/api/v1/auth/fixture-target"),),
                                                     content_type=None),
    ("GET", "/api/v1/auth/fixture-target"): Answer(200, b'{"followed":true}'),
}
RECEIVED: list[tuple[str, str, list[tuple[str, str]], bytes]] = []  # what the fake auth-service was sent


class FakeAuthService(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # as Tomcat speaks it, so that an answer can be chunked

    def log_message(self, fmt, *args):
        return

    def _answer(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        RECEIVED.append((self.command, self.path, self.headers.items(), body))
        answer = UPSTREAM[(self.command, self.path)]
        self.send_response(answer.status)  # writes Server and Date; auth-service's Tomcat writes only Date
        if answer.content_type:
            self.send_header("Content-Type", answer.content_type)
        for name, value in answer.headers:
            self.send_header(name, value)
        if not answer.chunked:
            self.send_header("Content-Length", str(len(answer.body)))
            self.end_headers()
            self.wfile.write(answer.body)
            return
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        self.wfile.write(chunked(answer.body))

    do_GET = do_POST = _answer


def chunked(body: bytes) -> bytes:
    """``body`` in two chunks and the last, empty one."""
    half = len(body) // 2
    return b"".join(b"%x\r\n%s\r\n" % (len(part), part) for part in (body[:half], body[half:]) if part) + b"0\r\n\r\n"


def values(headers: list[tuple[str, str]], name: str) -> list[str]:
    return [value for key, value in headers if key.lower() == name.lower()]


class AuthInterceptRelayTest(unittest.TestCase):
    """The stub relays auth-service's answers the way the gateway would get them from auth-service itself.

    The gateway's Netty client refuses a response with two Content-Length headers, even equal ones
    ("Multiple Content-Length values found: [127, 127]", candidate run 45262420375).
    """

    @classmethod
    def setUpClass(cls):
        cls.servers = [ThreadingHTTPServer(("127.0.0.1", 0), FakeAuthService),
                       ThreadingHTTPServer(("127.0.0.1", 0), stubs.AuthInterceptHandler)]
        for server in cls.servers:
            threading.Thread(target=server.serve_forever, daemon=True).start()
        cls.upstream_port = cls.servers[0].server_address[1]
        cls.intercept_port = cls.servers[1].server_address[1]
        stubs.AUTH_UPSTREAM = "http://127.0.0.1:%d" % cls.upstream_port

    @classmethod
    def tearDownClass(cls):
        for server in cls.servers:
            server.shutdown()
            server.server_close()

    def setUp(self):
        RECEIVED.clear()

    def exchange(self, port, method, path, headers=(), body=b"", is_chunked=False):
        """One request on a raw socket, read to the end: the status, every header line as sent, the body."""
        lines = [f"{method} {path} HTTP/1.1", "Host: stubs:8081", *(f"{k}: {v}" for k, v in headers)]
        if is_chunked:
            lines.append("Transfer-Encoding: chunked")
            body = chunked(body)
        elif method == "POST":
            lines.append(f"Content-Length: {len(body)}")
        with socket.create_connection(("127.0.0.1", port), timeout=10) as sock:
            sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode("latin-1") + body)
            data = b""
            while chunk := sock.recv(65536):
                data += chunk
        head, separator, payload = data.partition(b"\r\n\r\n")
        self.assertTrue(separator, data)
        status_line, *header_lines = head.decode("latin-1").split("\r\n")
        headers = [tuple(part.strip() for part in line.split(":", 1)) for line in header_lines]
        return int(status_line.split(" ", 2)[1]), headers, payload

    def relay(self, method, path, headers=(), body=None, is_chunked=False):
        if body is None:
            body = b"{}" if method == "POST" else b""
        if method == "POST":
            headers = (("Content-Type", "application/json"), *headers)
        return self.exchange(self.intercept_port, method, path, headers, body, is_chunked)

    def test_relayed_answers_carry_one_content_length_and_the_upstream_body(self):
        for (method, path), answer in UPSTREAM.items():
            with self.subTest(method=method, path=path):
                status, headers, payload = self.relay(method, path)
                self.assertEqual(status, answer.status)
                self.assertEqual(values(headers, "Content-Length"), [str(len(answer.body))])
                self.assertEqual(values(headers, "Transfer-Encoding"), [])
                self.assertEqual(payload, answer.body)

    def test_relayed_answers_carry_one_date_and_one_server(self):
        # send_response writes both itself, so the upstream's copies must not be relayed beside them.
        for method, path in UPSTREAM:
            with self.subTest(method=method, path=path):
                _, headers, _ = self.relay(method, path)
                names = [k.lower() for k, _ in headers]
                self.assertEqual((names.count("date"), names.count("server")), (1, 1))

    def test_a_chunked_answer_goes_out_with_one_content_length_and_no_transfer_encoding(self):
        """#614: a relayed Transfer-Encoding beside the computed Content-Length would frame the body twice."""
        _, upstream_headers, _ = self.exchange(self.upstream_port, "POST", "/api/v1/auth/register",
                                               (("Connection", "close"),))
        self.assertEqual(values(upstream_headers, "Transfer-Encoding"), ["chunked"])  # the fake really chunks it
        answer = UPSTREAM[("POST", "/api/v1/auth/register")]
        status, headers, payload = self.relay("POST", "/api/v1/auth/register")
        self.assertEqual((status, payload), (201, answer.body))
        self.assertEqual(values(headers, "Content-Length"), [str(len(answer.body))])
        self.assertEqual(values(headers, "Transfer-Encoding"), [])

    def test_two_set_cookie_lines_are_relayed_one_by_one_in_order(self):
        """#614: Set-Cookie cannot be combined into one line; each cookie keeps its own."""
        _, headers, _ = self.relay("POST", "/api/v1/auth/refresh-token")
        self.assertEqual(values(headers, "Set-Cookie"), [value for name, value in
                                                         UPSTREAM[("POST", "/api/v1/auth/refresh-token")].headers])

    def test_error_answers_keep_the_upstream_headers_and_content_type(self):
        """#614: a 4xx or 5xx keeps Set-Cookie, WWW-Authenticate, Retry-After and the Content-Type parameters."""
        for path in ("/api/v1/auth/login", "/api/v1/auth/resend-verification", "/api/v1/auth/logout"):
            answer = UPSTREAM[("POST", path)]
            with self.subTest(status=answer.status):
                status, headers, payload = self.relay("POST", path)
                self.assertEqual((status, payload), (answer.status, answer.body))
                self.assertEqual(values(headers, "Content-Type"), [PROBLEM])
                for name in {name for name, _ in answer.headers}:
                    self.assertEqual(values(headers, name), [v for n, v in answer.headers if n == name], name)

    def test_a_redirect_goes_back_to_the_gateway_and_is_not_followed(self):
        status, headers, payload = self.relay("GET", "/api/v1/auth/fixture-redirect")
        self.assertEqual((status, values(headers, "Location"), payload), (302, ["/api/v1/auth/fixture-target"], b""))
        self.assertEqual([path for _, path, _, _ in RECEIVED], ["/api/v1/auth/fixture-redirect"])

    def test_repeated_request_headers_reach_auth_service_unmerged(self):
        """#614: each request header line is forwarded on its own; only Host, the length and hop-by-hop go."""
        sent = (("X-Parkio-Trace", "first"), ("Accept", "application/json"), ("X-Parkio-Trace", "second"),
                ("Connection", "keep-alive"), ("Keep-Alive", "timeout=5"), ("TE", "trailers"))
        self.relay("POST", "/api/v1/auth/verify-email", sent)
        ((method, path, headers, body),) = RECEIVED
        self.assertEqual((method, path, body), ("POST", "/api/v1/auth/verify-email", b"{}"))
        self.assertEqual(values(headers, "X-Parkio-Trace"), ["first", "second"])
        self.assertEqual(values(headers, "Host"), ["127.0.0.1:%d" % self.upstream_port])
        self.assertEqual(values(headers, "Content-Length"), ["2"])
        self.assertEqual(values(headers, "Connection"), ["close"])
        self.assertEqual(values(headers, "Keep-Alive") + values(headers, "TE"), [])

    def test_a_chunked_request_reaches_auth_service_whole_with_a_content_length(self):
        self.relay("POST", "/api/v1/auth/verify-email", body=b'{"token":"fixture-token"}', is_chunked=True)
        ((_, _, headers, body),) = RECEIVED
        self.assertEqual(body, b'{"token":"fixture-token"}')
        self.assertEqual(values(headers, "Content-Length"), [str(len(body))])
        self.assertEqual(values(headers, "Transfer-Encoding"), [])


if __name__ == "__main__":
    unittest.main()
