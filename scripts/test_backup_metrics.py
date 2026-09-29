#!/usr/bin/env python3
"""Deterministic metric writer, permissions, path and release-rotation tests."""
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
WRITER = ROOT / "scripts/lib/backup-metrics.py"
SPEC = importlib.util.spec_from_file_location("backup_metrics", WRITER)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
HOST = "/var/lib/parkio/observability/textfile"


class BackupMetricsTest(unittest.TestCase):
    def publish(self, directory, success=1, stamp=100):
        MODULE.publish(directory, "invite-production", success, stamp, 0, 2, 1, 1, 512, 1, "parkio-media")

    def test_exact_metric_names_read_permissions_and_no_secret_environment(self):
        with tempfile.TemporaryDirectory() as work, patch.dict(os.environ, {"DATABASE_PASSWORD": "NEVER_EMIT_THIS"}):
            directory = Path(work) / "metrics"
            old_umask = os.umask(0o077)
            try:
                self.publish(directory)
            finally:
                os.umask(old_umask)
            output = directory / "parkio_backup.prom"
            self.assertEqual(output.stat().st_mode & 0o777, 0o644)
            self.assertEqual(directory.stat().st_mode & 0o777, 0o755)
            content = output.read_text()
            names = set(re.findall(r"^(parkio_backup_\w+)\{", content, re.M))
            self.assertEqual(names, {"parkio_backup_" + name for name in (
                "last_success", "last_timestamp_seconds", "databases_failed", "minio_objects",
                "offsite_last_success", "encryption_enabled", "last_bytes", "production_mode")})
            self.assertNotIn("NEVER_EMIT_THIS", content)
            self.assertIn('parkio_backup_last_timestamp_seconds{scope="invite-production"} 100', content)

    def test_publication_is_fsynced_closed_and_renamed_without_partial_prom(self):
        with tempfile.TemporaryDirectory() as work:
            directory = Path(work)
            self.publish(directory)
            before = (directory / "parkio_backup.prom").read_bytes()
            replace = os.replace

            def inspect_before_rename(source, target):
                self.assertEqual((directory / "parkio_backup.prom").read_bytes(), before)
                self.assertEqual(Path(source).suffix, ".tmp")
                self.assertEqual(Path(source).parent, directory)
                self.assertIn('last_success{scope="invite-production"} 0', Path(source).read_text())
                return replace(source, target)

            with patch.object(MODULE.os, "replace", side_effect=inspect_before_rename) as rename, \
                    patch.object(MODULE.os, "fsync", wraps=os.fsync) as sync:
                self.publish(directory, success=0, stamp=101)
            self.assertEqual(rename.call_count, 1)
            self.assertEqual(sync.call_count, 2)  # file before rename; directory after
            self.assertEqual(list(directory.iterdir()), [directory / "parkio_backup.prom"])

    def test_failed_rename_retains_previous_complete_file_and_cleans_temp(self):
        with tempfile.TemporaryDirectory() as work:
            self.publish(work)
            output = Path(work) / "parkio_backup.prom"
            before = output.read_bytes()
            with patch.object(MODULE.os, "replace", side_effect=OSError("fixture")), self.assertRaises(OSError):
                self.publish(work, success=0)
            self.assertEqual(output.read_bytes(), before)
            self.assertEqual(len(list(Path(work).iterdir())), 1)

    def test_early_failure_replaces_stale_green_but_preserves_detailed_current_failure(self):
        with tempfile.TemporaryDirectory() as work:
            self.publish(work)
            started = time.time()
            MODULE.publish_failure(work, started)
            output = Path(work) / "parkio_backup.prom"
            self.assertIn('last_success{scope="invite-production"} 0', output.read_text())
            stamp = int(re.search(r'last_timestamp_seconds\{[^}]+\} (\d+)', output.read_text())[1])
            self.assertGreaterEqual(stamp, int(started))
            MODULE.publish(work, "invite-production", 0, int(time.time()), 10, 0, 0, 1, 0, 1, "parkio-media")
            before = output.read_bytes()
            MODULE.publish_failure(work, started)
            self.assertEqual(output.read_bytes(), before)

    def test_invalid_labels_or_values_cannot_emit_arbitrary_data(self):
        with tempfile.TemporaryDirectory() as work:
            for value in ('1\nsecret', '-1', 'NaN'):
                with self.assertRaises(ValueError):
                    MODULE.publish(work, "invite-production", value, 100, 0, 0, 0, 0, 0, 1, "parkio-media")
            with self.assertRaises(ValueError):
                MODULE.publish(work, "unreviewed", 1, 100, 0, 0, 0, 0, 0, 1, "parkio-media")
            self.assertEqual(list(Path(work).iterdir()), [])

    def test_writer_mount_unit_and_packaging_have_one_release_independent_path(self):
        for overlay in ("invite-dark", "invite-public"):
            text = (ROOT / f"docker/docker-compose.{overlay}.yml").read_text()
            self.assertIn(f"{HOST}:/textfile-collector:ro", text)
        for path in ("infra/systemd/parkio-invite-backup.service",
                     "scripts/azure/invite-production-backup-run.sh", "scripts/lib/backup-common.sh"):
            self.assertIn(HOST, (ROOT / path).read_text())
        self.assertIn('"scripts/lib/backup-metrics.py"',
                      (ROOT / "scripts/azure/install-invite-production-backup-scheduler.sh").read_text())
        self.assertIn("--collector.textfile.directory=/textfile-collector",
                      (ROOT / "docker/docker-compose.yml").read_text())
        self.assertNotRegex(HOST, r"current|releases|[a-f0-9]{40}|_work")

    def test_common_writer_honors_absolute_directory(self):
        with tempfile.TemporaryDirectory() as work:
            env = {**os.environ, "PARKIO_PROMETHEUS_TEXTFILE_DIR": work, "BACKUP_PRODUCTION_MODE": "1"}
            result = subprocess.run(["bash", "-c", "source scripts/lib/backup-common.sh; "
                                     "parkio_backup_write_metrics invite-production 1 100 0 0 1 1 100"],
                                    cwd=ROOT, env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue((Path(work) / "parkio_backup.prom").is_file())

    def test_real_compose_keeps_one_read_only_mount_across_release_roots(self):
        docker = next((path for name in ("docker", "docker.exe")
                       if (path := shutil.which(name)) and subprocess.run(
                           [path, "compose", "version"], capture_output=True).returncode == 0), None)
        self.assertIsNotNone(docker, "Docker Compose config is required; no daemon is used")
        for overlay, project in [("invite-public", "."), ("invite-public", "frontend"),
                                 ("invite-dark", ".")]:
            files = ["docker-compose.yml", "docker-compose.apps.yml", "docker-compose.images.yml",
                     "docker-compose.hosted-beta.yml", "docker-compose.managed-db.yml",
                     f"docker-compose.{overlay}.yml"]
            values = {"PARKIO_IMAGE_TAG": "sha-backup-path-test", "PARKIO_GIT_SHA": "0" * 40,
                      "PARKIO_IMAGE_CREATED": "2026-09-05T00:00:00Z"}
            env = {**os.environ, **values}
            env["WSLENV"] = ":".join(filter(None, [env.get("WSLENV", ""), *values]))
            args = [docker, "compose", "--env-file", "docker/.env.invite-production.example",
                    "--project-directory", project]
            for file in files:
                args += ["-f", "docker/" + file]
            result = subprocess.run(args + ["config", "--format", "json"], cwd=ROOT,
                                    env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            service = json.loads(result.stdout)["services"]["node-exporter"]
            mounts = [item for item in service["volumes"] if item["target"] == "/textfile-collector"]
            self.assertEqual(len(mounts), 1)
            self.assertEqual(mounts[0]["source"], HOST)
            self.assertTrue(mounts[0]["read_only"])


if __name__ == "__main__":
    unittest.main()
