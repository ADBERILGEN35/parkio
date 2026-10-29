#!/usr/bin/env python3
"""Stub-driven proof that the textfile-export unit runs the exporter atomically."""
from __future__ import annotations

import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXPORT = ROOT / "scripts/slack_biz/export_textfile_metrics.py"
SERVICE = ROOT / "scripts/slack_biz/deploy/civo/parkio-slack-biz-textfile-export.service"
TIMER = ROOT / "scripts/slack_biz/deploy/civo/parkio-slack-biz-textfile-export.timer"

if str(ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(ROOT / "scripts"))

from slack_biz.store import DeliveryStore  # noqa: E402


def _seed_store(db_path: Path) -> None:
    store = DeliveryStore(db_path)
    now = time.time()
    try:
        with store._lock:  # noqa: SLF001
            for i in range(2):
                store._conn.execute(
                    "INSERT INTO delivery_queue("
                    "event_id, dedup_key, route, payload_json, status, attempts, "
                    "next_attempt_at, created_at, updated_at) "
                    "VALUES (?, ?, 'biz-growth', '{}', 'delivery_unknown', 0, ?, ?, ?)",
                    (f"evt-unknown-{i}", f"dedup-unknown-{i}", now, now, now),
                )
            for i in range(3):
                store._conn.execute(
                    "INSERT INTO dlt(event_id, dedup_key, reason, payload_json, created_at) "
                    "VALUES (?, ?, 'test', '{}', ?)",
                    (f"evt-dlt-{i}", f"dedup-dlt-{i}", now),
                )
            store._conn.commit()
    finally:
        store.close()



def assert_mode_0644(path: Path) -> None:
    mode = path.stat().st_mode & 0o777
    if mode != 0o644:
        raise AssertionError(f"expected mode 0644 for {path}, got {oct(mode)}")

class TextfileExportTimerTest(unittest.TestCase):
    def test_unit_points_at_export_script(self) -> None:
        body = SERVICE.read_text(encoding="utf-8")
        self.assertIn("Type=oneshot", body)
        self.assertRegex(
            body,
            r"ExecStart=.*/scripts/slack_biz/export_textfile_metrics\.py",
        )
        # Exporter must read the delivery DB; only the textfile leaf is writable.
        self.assertRegex(
            body,
            r"(?m)^ReadWritePaths=/opt/parkio/docker/prometheus/textfile$",
        )
        self.assertRegex(
            body,
            r"(?m)^ReadOnlyPaths=.*(?:^|[\s=])/var/lib/parkio/slack-biz(?:\s|$)",
        )
        self.assertTrue(TIMER.is_file())
        timer = TIMER.read_text(encoding="utf-8")
        self.assertIn("Unit=parkio-slack-biz-textfile-export.service", timer)
        self.assertIn("OnUnitActiveSec=1min", timer)

    def test_readonly_store_rejects_writes_but_export_succeeds(self) -> None:
        """Unit ReadOnlyPaths requires URI mode=ro; WAL/schema open must not be used."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            data_dir = tmp_path / "data"
            data_dir.mkdir(parents=True, exist_ok=True)
            db_path = data_dir / "slack_biz.sqlite3"
            _seed_store(db_path)

            ro = DeliveryStore(db_path, readonly=True)
            try:
                self.assertEqual(ro.count_by_status("delivery_unknown"), 2)
                with self.assertRaises(sqlite3.OperationalError):
                    with ro._lock:  # noqa: SLF001
                        ro._conn.execute("CREATE TABLE IF NOT EXISTS _should_fail(x INT)")
            finally:
                ro.close()

            out = tmp_path / "textfile" / "parkio_slack_biz.prom"
            env = os.environ.copy()
            env.update(
                {
                    "PARKIO_SLACK_BIZ_DATA_DIR": str(data_dir),
                    "PARKIO_SLACK_BIZ_ENVIRONMENT": "unit-test",
                    "PARKIO_SLACK_BIZ_ENABLED": "false",
                    "PARKIO_SLACK_BIZ_WAITLIST_INBOX": str(tmp_path / "inbox"),
                    "PARKIO_SLACK_BIZ_TRUSTED_PRODUCERS": "gateway-waitlist-outbox",
                    "PYTHONDONTWRITEBYTECODE": "1",
                }
            )
            (tmp_path / "inbox").mkdir(parents=True, exist_ok=True)
            proc = subprocess.run(
                [sys.executable, str(EXPORT), "--output", str(out)],
                check=False,
                capture_output=True,
                text=True,
                env=env,
                cwd=str(ROOT),
            )
            self.assertEqual(proc.returncode, 0, proc.stderr or proc.stdout)
            self.assertTrue(out.is_file())
            assert_mode_0644(out)
            body = out.read_text(encoding="utf-8")
            self.assertIn("parkio_slack_biz_delivery_unknown 2", body)
            self.assertIn("parkio_slack_biz_dlt_rows 3", body)

    def test_export_writes_prom_atomically_with_freshness(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            data_dir = tmp_path / "data"
            data_dir.mkdir(parents=True, exist_ok=True)
            out = tmp_path / "textfile" / "parkio_slack_biz.prom"
            _seed_store(data_dir / "slack_biz.sqlite3")

            env = os.environ.copy()
            env.update(
                {
                    "PARKIO_SLACK_BIZ_DATA_DIR": str(data_dir),
                    "PARKIO_SLACK_BIZ_ENVIRONMENT": "unit-test",
                    "PARKIO_SLACK_BIZ_ENABLED": "false",
                    "PARKIO_SLACK_BIZ_WAITLIST_INBOX": str(tmp_path / "inbox"),
                    "PARKIO_SLACK_BIZ_TRUSTED_PRODUCERS": "gateway-waitlist-outbox",
                    "PYTHONDONTWRITEBYTECODE": "1",
                }
            )
            (tmp_path / "inbox").mkdir(parents=True, exist_ok=True)

            exec_line = next(
                line
                for line in SERVICE.read_text(encoding="utf-8").splitlines()
                if line.startswith("ExecStart=")
            )
            self.assertIn("export_textfile_metrics.py", exec_line)

            proc = subprocess.run(
                [sys.executable, str(EXPORT), "--output", str(out)],
                check=False,
                capture_output=True,
                text=True,
                env=env,
                cwd=str(ROOT),
            )
            self.assertEqual(proc.returncode, 0, proc.stderr or proc.stdout)
            self.assertTrue(out.is_file())
            assert_mode_0644(out)
            leftover = list(out.parent.glob(".parkio_slack_biz.*.tmp"))
            self.assertEqual(leftover, [], f"tmp left behind: {leftover}")

            body = out.read_text(encoding="utf-8")
            self.assertIn("parkio_slack_biz_delivery_unknown 2", body)
            self.assertIn("parkio_slack_biz_dlt_rows 3", body)
            match = re.search(
                r"parkio_slack_biz_textfile_last_success_unixtime (\d+)", body
            )
            self.assertIsNotNone(match)
            self.assertGreater(int(match.group(1)), 1_700_000_000)


    def _export(self, data_dir: Path, tmp_path: Path) -> tuple[subprocess.CompletedProcess, Path]:
        out = tmp_path / "textfile" / "parkio_slack_biz.prom"
        (tmp_path / "inbox").mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        env.update(
            {
                "PARKIO_SLACK_BIZ_DATA_DIR": str(data_dir),
                "PARKIO_SLACK_BIZ_ENVIRONMENT": "unit-test",
                "PARKIO_SLACK_BIZ_ENABLED": "false",
                "PARKIO_SLACK_BIZ_WAITLIST_INBOX": str(tmp_path / "inbox"),
                "PARKIO_SLACK_BIZ_TRUSTED_PRODUCERS": "gateway-waitlist-outbox",
                "PYTHONDONTWRITEBYTECODE": "1",
            }
        )
        proc = subprocess.run(
            [sys.executable, str(EXPORT), "--output", str(out)],
            check=False,
            capture_output=True,
            text=True,
            env=env,
            cwd=str(ROOT),
        )
        return proc, out

    def _tmp(self) -> Path:
        # Cleanups run last-in first-out, so a later chmod back to 0755 runs before this removal.
        tmp_path = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp_path)
        return tmp_path

    def _make_read_only(self, data_dir: Path) -> None:
        """chmod 0555 stands in for the unit's ReadOnlyPaths; root would ignore it."""
        os.chmod(data_dir, 0o555)
        self.addCleanup(os.chmod, data_dir, 0o755)
        probe = data_dir / ".write-probe"
        try:
            probe.touch()
        except PermissionError:
            return
        probe.unlink()
        self.skipTest("needs a non-root user: root ignores the read-only directory mode")

    def test_export_still_publishes_when_the_relay_stopped(self) -> None:
        """G-06 (#488): the last relay close removes the WAL side files, and a mode=ro reader under a
        read-only directory cannot create -shm. The export must still publish the counts, with the
        stopped relay visible as parkio_slack_biz_relay_db_open 0."""
        tmp_path = self._tmp()
        data_dir = tmp_path / "data"
        data_dir.mkdir(parents=True, exist_ok=True)
        _seed_store(data_dir / "slack_biz.sqlite3")
        self.assertEqual([p.name for p in data_dir.iterdir()], ["slack_biz.sqlite3"])
        self._make_read_only(data_dir)

        proc, out = self._export(data_dir, tmp_path)

        self.assertEqual(proc.returncode, 0, proc.stderr or proc.stdout)
        assert_mode_0644(out)
        body = out.read_text(encoding="utf-8")
        self.assertIn("parkio_slack_biz_delivery_unknown 2", body)
        self.assertIn("parkio_slack_biz_dlt_rows 3", body)
        self.assertIn("parkio_slack_biz_relay_db_open 0", body)
        self.assertEqual([p.name for p in data_dir.iterdir()], ["slack_biz.sqlite3"])

    def test_export_reports_the_relay_holding_the_db_open(self) -> None:
        tmp_path = self._tmp()
        data_dir = tmp_path / "data"
        data_dir.mkdir(parents=True, exist_ok=True)
        db_path = data_dir / "slack_biz.sqlite3"
        _seed_store(db_path)
        relay = DeliveryStore(db_path)
        self.addCleanup(relay.close)
        self.assertTrue(Path(f"{db_path}-wal").exists())
        self._make_read_only(data_dir)

        proc, out = self._export(data_dir, tmp_path)

        self.assertEqual(proc.returncode, 0, proc.stderr or proc.stdout)
        body = out.read_text(encoding="utf-8")
        self.assertIn("parkio_slack_biz_delivery_unknown 2", body)
        self.assertIn("parkio_slack_biz_relay_db_open 1", body)


if __name__ == "__main__":
    unittest.main()
