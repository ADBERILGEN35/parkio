#!/usr/bin/env python3
"""Unit tests for scripts/staging/assert-loopback-published-ports.py."""
import importlib.util
import io
import json
import pathlib
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest import mock

SCRIPT = pathlib.Path(__file__).with_name("assert-loopback-published-ports.py")
spec = importlib.util.spec_from_file_location("assert_loopback", SCRIPT)
assert_loopback = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assert_loopback)


def model(*ports_by_service):
    return {"services": {name: {"ports": ports} for name, ports in ports_by_service}}


def run(m):
    out, err = io.StringIO(), io.StringIO()
    with mock.patch("sys.stdin", io.StringIO(json.dumps(m))), redirect_stdout(out), redirect_stderr(err):
        rc = assert_loopback.main()
    return rc, out.getvalue(), err.getvalue()


class AssertLoopbackPublishedPortsTest(unittest.TestCase):

    def test_all_loopback_passes(self):
        rc, out, _ = run(model(
            ("postgres-auth", [{"host_ip": "127.0.0.1", "published": "15432", "target": 5432}]),
            ("gateway-service", [{"host_ip": "::1", "published": "18080", "target": 8080}]),
            ("kafka", [])))
        self.assertEqual(rc, 0)
        self.assertIn("2 published ports", out)

    def test_missing_host_ip_binds_every_interface_and_fails(self):
        rc, _, err = run(model(("redis", [{"published": "16379", "target": 6379}])))
        self.assertEqual(rc, 1)
        self.assertIn("redis: 0.0.0.0 (all interfaces):16379 -> 6379/tcp", err)

    def test_explicit_non_loopback_address_fails(self):
        for host_ip in ("0.0.0.0", "10.0.0.5", "::"):
            with self.subTest(host_ip=host_ip):
                rc, _, err = run(model(("minio", [{"host_ip": host_ip, "published": "19000", "target": 9000}])))
                self.assertEqual(rc, 1)
                self.assertIn(f"minio: {host_ip}:19000", err)

    def test_reports_every_offender(self):
        rc, _, err = run(model(
            ("a", [{"published": "1", "target": 1}, {"host_ip": "127.0.0.1", "published": "2", "target": 2}]),
            ("b", [{"host_ip": "0.0.0.0", "published": "3", "target": 3}])))
        self.assertEqual(rc, 1)
        self.assertEqual(err.count("\n  "), 2)


if __name__ == "__main__":
    unittest.main()
