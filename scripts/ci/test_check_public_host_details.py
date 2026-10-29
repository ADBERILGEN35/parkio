#!/usr/bin/env python3
"""Unit tests for scripts/ci/check_public_host_details.py (G-05, #487). Fixtures use documentation addresses."""
from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_public_host_details as check  # noqa: E402


def reasons(line: str) -> list[str]:
    return [problem.split(": ", 1)[1] for problem in check.problems_in("f.md", line)]


class Refused(unittest.TestCase):
    def test_a_login_at_a_raw_address(self):
        self.assertIn("login at a raw IP address", reasons("Probed as `deploy@203.0.113.7`."))

    def test_a_login_at_a_parkio_host_name(self):
        for line in ("Probed as `operator@api.parkio.dev` on the host.", "as ops@Staging.Parkio.Dev"):
            self.assertIn("login at a Parkio host name", reasons(line), line)

    def test_user_at_host_in_an_ssh_line(self):
        for line in ("ssh -L 3000:localhost:3000 user@vps", "SSH to admin@build-box failed",
                     "scp backup.tgz ops@files.example.org:/srv", "rsync -a ./x deploy@mirror:/y"):
            self.assertIn("user@host in an ssh/scp/sftp/rsync line", reasons(line), line)

    def test_an_address_of_a_personal_machine(self):
        for line in ("| Developer machine | `198.51.100.23` (TR residential) |", "my laptop at 203.0.113.9"):
            self.assertIn("IP address of a personal or residential machine", reasons(line), line)

    def test_an_address_next_to_ssh_access_or_the_production_host(self):
        for line in ("The VM (203.0.113.40) accepts SSH on port 22.", "Expected host: `parkio-civo-prod` / `198.51.100.4`"):
            self.assertIn("IP address next to SSH access or the production host", reasons(line), line)


class Allowed(unittest.TestCase):
    def test_mail_addresses_placeholders_and_roles(self):
        for line in ("Auth sends from `verify@parkio.dev`.", "./scripts/bootstrap-super-admin.sh admin@example.com",
                     "ssh -L 9090:localhost:9090 <user>@<host>", "SSH to the production host (owner access) works.",
                     "Probed on the production host `parkio-civo-prod` (owner access)."):
            self.assertEqual(reasons(line), [], line)

    def test_local_addresses_identify_no_one(self):
        for line in ("Open Grafana (`http://127.0.0.1:3000` via SSH tunnel on the VPS).",
                     "Android emulator `http://10.0.2.2:8080/api/v1` on my laptop",
                     "ssh tunnel to 192.168.1.20 and 172.16.0.5", "root@127.0.0.1 inside the container"):
            self.assertEqual(reasons(line), [], line)

    def test_addresses_without_a_personal_or_access_context(self):
        for line in ("dig +short A api.parkio.dev @8.8.8.8", "| Hosted-beta host | `203.0.113.50` | none |",
                     '"PURL": "pkg:apk/alpine/busybox@1.37.0.8.10-r1?arch=x86_64"', "version 1.2.3.4 shipped"):
            self.assertEqual(reasons(line), [], line)


class Report(unittest.TestCase):
    def test_the_report_names_file_and_line_but_not_the_value(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root, "notes.md")
            path.write_text("ok\nProbed as `deploy@203.0.113.7`.\n", encoding="utf-8")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(check.main([str(path)]), 1)
            self.assertIn("notes.md:2: login at a raw IP address", output.getvalue())
            self.assertNotIn("203.0.113.7", output.getvalue())
            self.assertNotIn("deploy@", output.getvalue())

    def test_binary_files_are_skipped(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root, "blob.bin")
            path.write_bytes(b"\0deploy@203.0.113.7")
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(check.main([str(path)]), 0)


class Repository(unittest.TestCase):
    def test_the_tracked_docs_and_agent_tools_pass(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(check.main([]), 0)


if __name__ == "__main__":
    unittest.main()
