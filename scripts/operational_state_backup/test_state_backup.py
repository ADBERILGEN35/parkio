#!/usr/bin/env python3
"""Offline fixtures only; no production state, containers, or network calls."""

from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import io
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import time
import tarfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from slack_biz.store import SCHEMA  # noqa: E402
with tempfile.TemporaryDirectory(prefix="parkio-nr-import-fixture-") as import_root, patch.dict(
    os.environ, {"PARKIO_NR_BUDGET_STATE_DB": str(Path(import_root) / "budget.db"),
                 "PARKIO_NR_BUDGET_BYTES": "100"}
):
    from newrelic_log_pilot import budget_gate  # noqa: E402
Budget = budget_gate.Budget
from operational_state_backup import state_backup as backup  # noqa: E402


@contextmanager
def test_passphrase():
    with patch.dict(os.environ, {"BACKUP_ENCRYPT_PASSPHRASE": "synthetic-fixture-only"}):
        yield


class BackupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="parkio-opstate-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.slack_db = self.root / "slack/slack_biz.sqlite3"
        self.slack_db.parent.mkdir()
        self.slack_writer = sqlite3.connect(self.slack_db, check_same_thread=False)
        self.addCleanup(self.slack_writer.close)
        self.slack_writer.execute("PRAGMA journal_mode=WAL")
        self.slack_writer.executescript(SCHEMA)
        self.slack_writer.execute("INSERT INTO schema_meta VALUES ('version','2')")
        self.slack_writer.commit()
        self.budget_db = self.root / "nr/budget.db"
        Budget(self.budget_db, 0, daily=100, monthly=1000)
        self.inbox = self.root / "inbox"
        self.inbox.mkdir()
        self.source_state = self.root / "source-state"
        self.source_state.mkdir()
        (self.source_state / "gateway.json").write_text('{"timestamp":"synthetic"}')
        self.spool = self.root / "spool"
        self.spool.mkdir()
        (self.spool / "source-json.log").write_text("synthetic log\n")
        self.collector = self.root / "collector"
        self.collector.mkdir()
        (self.collector / "chunk.0").write_bytes(b"synthetic chunk")
        self.destination = self.root / "backup"

    def args(self):
        from argparse import Namespace
        return Namespace(slack_db=self.slack_db, slack_inbox=self.inbox,
                         nr_budget_db=self.budget_db, nr_source_state=self.source_state,
                         nr_source_spool=self.spool, nr_collector_state=self.collector,
                         destination=self.destination, gateway_outbox_backup_id="fixture-pg-1",
                         staging_root=self.root)

    def make_snapshot(self):
        with test_passphrase():
            return backup.snapshot(self.args())

    def gateway(self, events=(), backup_id="fixture-pg-1"):
        file = self.root / "gateway-export.json"
        file.write_text(json.dumps({"backup_id": backup_id,
                                    "events": [{"eventId": item} for item in events]}))
        return file

    def recover(self, events=(), backup_id="fixture-pg-1"):
        from argparse import Namespace
        with test_passphrase():
            return backup.prepare_recovery(Namespace(snapshot=self.destination,
                gateway_outbox_export=self.gateway(events, backup_id),
                destination=self.root / "prepared", staging_root=self.root))

    def insert_queue(self, event_id, key=None, status="queued"):
        key = key or event_id
        self.slack_writer.execute(
            "INSERT INTO delivery_queue(event_id,dedup_key,route,payload_json,status,next_attempt_at,created_at,updated_at) "
            "VALUES (?,?, 'test','{}',?,0,0,0)", (event_id, key, status))
        self.slack_writer.execute("INSERT INTO dedup VALUES (?,?,?,0,9999999999)",
                                  (key, event_id, status))
        self.slack_writer.commit()

    def test_wal_concurrent_writes_and_encrypted_integrity(self):
        self.insert_queue("seed")
        stop = threading.Event()

        def writer():
            for i in range(50):
                if stop.is_set():
                    return
                self.insert_queue(f"event-{i}")
                time.sleep(0.001)

        worker = threading.Thread(target=writer)
        worker.start()
        try:
            result = self.make_snapshot()
        finally:
            stop.set()
            worker.join()
        self.assertEqual(result["status"], "complete")
        self.assertEqual(self.destination.stat().st_mode & 0o777, 0o700)
        self.assertEqual((self.destination / "snapshot.tar.gz.enc").stat().st_mode & 0o777, 0o600)
        self.assertNotIn(b"synthetic chunk", (self.destination / "snapshot.tar.gz.enc").read_bytes())
        with test_passphrase(), backup.opened_snapshot(self.destination, self.root) as (stage, manifest):
            with sqlite3.connect(stage / "slack/slack_biz.sqlite3") as db:
                self.assertGreaterEqual(db.execute("SELECT COUNT(*) FROM delivery_queue").fetchone()[0], 1)
                self.assertEqual(db.execute("PRAGMA integrity_check").fetchone(), ("ok",))
            self.assertIn("nr/collector-state/chunk.0", manifest["files"])
            self.assertEqual(manifest["sqlite_schemas"]["slack/slack_biz.sqlite3"]["schema_version"], "2")

    def test_missing_and_corrupt_state(self):
        self.budget_db.unlink()
        with self.assertRaises(backup.SnapshotError):
            self.make_snapshot()
        self.assertFalse(self.destination.exists())
        Budget(self.budget_db, 0, daily=100, monthly=1000)
        self.make_snapshot()
        archive = self.destination / "snapshot.tar.gz.enc"
        with archive.open("ab") as stream:
            stream.write(b"damage")
        with test_passphrase(), self.assertRaises(backup.SnapshotError):
            with backup.opened_snapshot(self.destination, self.root):
                pass
        with self.assertRaises(backup.SnapshotError):
            self.recover()
        self.assertFalse((self.root / "prepared").exists())

    def test_schema_mismatch_and_interrupted_snapshot(self):
        self.slack_writer.execute("UPDATE schema_meta SET value='999' WHERE key='version'")
        self.slack_writer.commit()
        with self.assertRaises(backup.SnapshotError):
            self.make_snapshot()
        self.assertFalse(self.destination.exists())
        self.assertFalse(list(self.root.glob(".parkio-opstate-incomplete-*")))
        self.slack_writer.execute("UPDATE schema_meta SET value='2' WHERE key='version'")
        self.slack_writer.commit()
        with patch.object(backup, "copy_tree", side_effect=InterruptedError):
            with self.assertRaises(InterruptedError):
                self.make_snapshot()
        self.assertFalse(self.destination.exists())
        self.assertFalse(list(self.root.glob(".parkio-opstate-incomplete-*")))
        self.assertFalse(list(self.root.glob("parkio-opstate-stage-*")))

    def test_nr_schema_mismatch_and_corrupt_sqlite(self):
        with sqlite3.connect(self.budget_db) as db:
            db.execute("ALTER TABLE budget RENAME TO obsolete_budget")
        with self.assertRaises(backup.SnapshotError):
            self.make_snapshot()
        self.assertFalse(self.destination.exists())
        with sqlite3.connect(self.budget_db) as db:
            db.execute("ALTER TABLE obsolete_budget RENAME TO budget")
        self.slack_writer.close()
        self.slack_db.write_bytes(b"not SQLite")
        with self.assertRaises(backup.SnapshotError):
            self.make_snapshot()
        self.assertFalse(self.destination.exists())

    def test_inbox_membership_and_content_mutation_abort(self):
        pending = self.inbox / "pending.json"
        pending.write_text('{"eventId":"pending"}')
        original_copy_tree = backup.copy_tree

        def add_after_copy(source, stage, prefix, schemas, **kwargs):
            original_copy_tree(source, stage, prefix, schemas, **kwargs)
            if prefix == "slack/inbox":
                (self.inbox / "late.json").write_text('{"eventId":"late"}')

        with patch.object(backup, "copy_tree", side_effect=add_after_copy):
            with self.assertRaisesRegex(backup.SnapshotError, "inbox changed"):
                self.make_snapshot()
        self.assertFalse(self.destination.exists())
        (self.inbox / "late.json").unlink()

        original_copy = backup.shutil.copyfileobj

        def mutate_while_copying(source, target, *args, **kwargs):
            target.write(source.read(8))
            with pending.open("ab") as stream:
                stream.write(b" ")
            return original_copy(source, target, *args, **kwargs)

        with patch.object(backup.shutil, "copyfileobj", side_effect=mutate_while_copying):
            with self.assertRaisesRegex(backup.SnapshotError, "file changed"):
                self.make_snapshot()
        self.assertFalse(self.destination.exists())

    def test_reconciliation_mismatch_duplicate_and_invalid_retention(self):
        self.insert_queue("queued", status="delivery_unknown")
        (self.inbox / "pending.json").write_text('{"eventId":"pending"}')
        invalid = self.inbox / ".invalid"
        invalid.mkdir()
        (invalid / "rejected.json").write_bytes(b"not JSON")
        self.make_snapshot()
        with self.assertRaises(backup.SnapshotError):
            self.recover(["queued"], backup_id="wrong-pg")
        with self.assertRaises(backup.SnapshotError):
            self.recover(["queued", "queued"])
        report = self.recover(["queued", "missing"])
        self.assertEqual(report["counts"]["gateway_without_queue_or_inbox"], 1)
        self.assertEqual(report["counts"]["slack_without_gateway"], 1)
        self.assertEqual(report["counts"]["delivery_unknown"], 1)
        self.assertFalse(report["resume_slack"])
        self.assertFalse(report["resume_new_relic"])

    def test_duplicate_pending_envelope_and_old_budget_periods_fail_closed(self):
        self.insert_queue("same")
        (self.inbox / "same.json").write_text('{"eventId":"same"}')
        with sqlite3.connect(self.budget_db) as db:
            db.execute("UPDATE budget SET daily_window='2020-01-01', monthly_window='2020-01', "
                       "daily_spent=90, monthly_spent=900, spent_bytes=900 WHERE id=1")
        self.make_snapshot()
        report = self.recover(["same"])
        self.assertEqual(report["counts"]["pending_inbox_already_queued"], 1)
        prepared = self.root / "prepared/nr/budget.db"
        gate = Budget(prepared, 0, daily=100, monthly=1000)
        self.assertTrue(gate.snapshot()["exhausted"])
        self.assertFalse(gate.reserve(1, 1, 1, "synthetic-digest"))
        with sqlite3.connect(prepared) as db:
            self.assertEqual(db.execute("SELECT exhausted FROM budget WHERE id=1").fetchone(), (1,))

    def test_real_gate_day_month_rollover_and_missing_ledger_limit(self):
        self.make_snapshot()
        self.recover()
        prepared = self.root / "prepared/nr/budget.db"
        for old_day, old_month, now in (
            ("2026-05-01", "2026-05", datetime(2026, 5, 2, tzinfo=timezone.utc)),
            ("2026-05-31", "2026-05", datetime(2026, 6, 1, tzinfo=timezone.utc)),
        ):
            with self.subTest(now=now), sqlite3.connect(prepared) as db:
                db.execute("UPDATE budget SET daily_window=?, monthly_window=?, "
                           "daily_spent=99, monthly_spent=999, exhausted=1 WHERE id=1",
                           (old_day, old_month))
            with patch.object(budget_gate, "utc_now", return_value=now):
                gate = Budget(prepared, 0, daily=100, monthly=1000)
                self.assertTrue(gate.snapshot()["exhausted"])
                self.assertFalse(gate.reserve(1, 1, 1, "synthetic"))
                self.assertEqual(gate.snapshot()["daily_spent"], 0)
        # This is the current integration gap: Budget creates a fresh ledger if
        # a restored DB was not installed at the actual runtime bind path.
        missing = self.root / "missing-mounted-ledger/budget.db"
        with patch.object(budget_gate, "utc_now", return_value=datetime(2026, 6, 1, tzinfo=timezone.utc)):
            self.assertTrue(Budget(missing, 0, daily=100, monthly=1000).reserve(1, 1, 1, "synthetic"))

    def test_archive_password_traversal_symlink_and_no_overwrite(self):
        self.make_snapshot()
        live = self.root / "live-state"
        live.mkdir()
        (live / "sentinel").write_text("untouched")
        with self.assertRaises(backup.SnapshotError):
            from argparse import Namespace
            with test_passphrase():
                backup.prepare_recovery(Namespace(snapshot=self.destination,
                    gateway_outbox_export=self.gateway(), destination=live, staging_root=self.root))
        self.assertEqual((live / "sentinel").read_text(), "untouched")
        dangling = self.root / "dangling-destination"
        dangling.symlink_to(self.root / "nonexistent")
        with self.assertRaises(backup.SnapshotError):
            backup.prepare_recovery(Namespace(snapshot=self.destination,
                gateway_outbox_export=self.gateway(), destination=dangling, staging_root=self.root))
        self.assertTrue(dangling.is_symlink())
        with patch.dict(os.environ, {"BACKUP_ENCRYPT_PASSPHRASE": "wrong-synthetic-password"}):
            with self.assertRaises(backup.SnapshotError):
                with backup.opened_snapshot(self.destination, self.root):
                    pass
        self.assertFalse(list(self.root.glob("parkio-opstate-verify-*")))

        for name, kind in (("../escape", "regular"), ("slack/link", "symlink")):
            with self.subTest(kind=kind):
                hostile = self.root / f"hostile-{kind}"
                hostile.mkdir()
                plain = self.root / f"hostile-{kind}.tar.gz"
                with tarfile.open(plain, "w:gz") as archive:
                    info = tarfile.TarInfo(name)
                    if kind == "symlink":
                        info.type = tarfile.SYMTYPE
                        info.linkname = str(live)
                        archive.addfile(info)
                    else:
                        payload = b"synthetic"
                        info.size = len(payload)
                        archive.addfile(info, io.BytesIO(payload))
                encrypted = hostile / "snapshot.tar.gz.enc"
                with test_passphrase(), encrypted.open("xb") as output:
                    backup.openssl(["-in", str(plain)], output_file=output)
                stamp = backup.digest(encrypted)
                backup.private_file(hostile / "SHA256SUMS", f"{stamp}  snapshot.tar.gz.enc\n".encode())
                backup.private_file(hostile / "COMPLETE", f"{stamp}\n".encode())
                with test_passphrase(), self.assertRaises(backup.SnapshotError):
                    with backup.opened_snapshot(hostile, self.root):
                        pass
                self.assertFalse((self.root / "escape").exists())
                self.assertEqual((live / "sentinel").read_text(), "untouched")

        report = self.recover()
        self.assertEqual(report["status"], "manual_reconciliation_required")
        prepared = self.root / "prepared"
        self.assertEqual(prepared.stat().st_mode & 0o777, 0o700)
        self.assertEqual((prepared / "nr/budget.db").stat().st_mode & 0o777, 0o600)
        self.assertEqual((prepared / "RECOVERY-PLAN.json").stat().st_mode & 0o777, 0o600)

    def test_secret_named_file_rejected(self):
        (self.source_state / ".env").write_text("synthetic")
        with self.assertRaises(backup.SnapshotError):
            self.make_snapshot()
        self.assertFalse(self.destination.exists())


if __name__ == "__main__":
    unittest.main()
