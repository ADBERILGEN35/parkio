#!/usr/bin/env python3
"""Synthetic tests for off-host erasure recovery. No live host, no PII."""
from __future__ import annotations

import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))
import offhost_erasure as oe  # noqa: E402

A = "11111111-1111-4111-8111-111111111111"
B = "22222222-2222-4222-8222-222222222222"
C = "33333333-3333-4333-8333-333333333333"
T0 = "2026-09-23T03:30:00Z"
T1 = "2026-09-23T18:00:00Z"
T2 = "2026-09-24T03:30:00Z"
CUTOFF = "2026-09-24T04:00:00Z"


def stamp_entries():
    return [{"authUserId": A, "erasedAt": T0}]


class OffhostErasureRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.env = {"PARKIO_OFFHOST_ERASURE_ENABLED": "1"}

    def test_disabled_does_not_persist(self):
        store = oe.MemoryStore()
        with self.assertRaises(oe.DisabledError):
            oe.persist_complete_snapshot(store, stamp_entries(), T1, env={})
        self.assertEqual(store.list(), [])

    def test_account_present_in_backup_erased_afterward(self):
        store = oe.MemoryStore()
        later = stamp_entries() + [{"authUserId": B, "erasedAt": T1}]
        result = oe.persist_complete_snapshot(store, later, T1, self.env)
        self.assertEqual(result["coveredThrough"], T1)
        report = oe.recover(store, T1, stamp_entries())
        self.assertEqual(report["verdict"], "PASS")
        self.assertEqual(report["erasedAfterStamp"], 1)
        ids = {row["authUserId"] for row in report["merged"]}
        self.assertEqual(ids, {A, B})
        self.assertNotIn(C, ids)

    def test_unrelated_account_not_added(self):
        store = oe.MemoryStore()
        oe.persist_complete_snapshot(store, stamp_entries(), T1, self.env)
        report = oe.recover(store, T1, stamp_entries())
        ids = {row["authUserId"] for row in report["merged"]}
        self.assertEqual(ids, {A})
        self.assertNotIn(B, ids)
        self.assertNotIn(C, ids)

    def test_duplicates_and_out_of_order(self):
        messy = [
            {"authUserId": B.upper(), "erasedAt": T1},
            {"authUserId": A, "erasedAt": T0},
            {"authUserId": B, "erasedAt": "2026-09-23T19:00:00Z"},
            {"authUserId": A, "erasedAt": T0},
        ]
        normalized = oe.normalize_entries(messy)
        self.assertEqual([row["authUserId"] for row in normalized], [A, B])
        self.assertEqual(normalized[1]["erasedAt"], T1)
        first = oe.snapshot_sha256(messy)
        again = oe.snapshot_sha256(list(reversed(messy)))
        self.assertEqual(first, again)

    def test_remote_write_failure_does_not_advance_coverage(self):
        inner = oe.MemoryStore()
        failing = oe.FailingStore(inner, fail_times=2)
        later = stamp_entries() + [{"authUserId": B, "erasedAt": T1}]
        with self.assertRaises(oe.RemoteWriteError):
            oe.persist_complete_snapshot(failing, later, T1, self.env)
        report = oe.recover(inner, T1, stamp_entries())
        self.assertEqual(report["verdict"], "BLOCKED")
        self.assertIsNone(report["coverageThrough"])
        with self.assertRaises(oe.RemoteWriteError):
            oe.retry_pending(failing, self.env)
        retried = oe.retry_pending(failing, self.env)
        self.assertEqual(retried["coveredThrough"], T1)
        report = oe.recover(inner, T1, stamp_entries())
        self.assertEqual(report["verdict"], "PASS")
        self.assertEqual(report["erasedAfterStamp"], 1)

    def test_missing_offhost_store_blocks(self):
        report = oe.recover(oe.MemoryStore(), CUTOFF, stamp_entries())
        self.assertEqual(report["verdict"], "BLOCKED")
        self.assertIn("no verified off-host coverage", report["reason"])

    def test_corrupt_snapshot_fails(self):
        store = oe.MemoryStore()
        oe.persist_complete_snapshot(store, stamp_entries(), T1, self.env)
        covered = oe.latest_verified_coverage(store)
        key = f"snapshots/{covered['seal']['snapshotSha256']}.json"
        store.put(key, b'{"kind":"erasure-snapshot","entries":[')
        report = oe.recover(store, T1, stamp_entries())
        self.assertEqual(report["verdict"], "FAIL")

    def test_incomplete_seal_without_snapshot_fails(self):
        store = oe.MemoryStore()
        seal, digest, blob = oe.build_seal(stamp_entries(), T1)
        store.put(f"seals/{digest}.json", blob)
        report = oe.recover(store, T1, stamp_entries())
        self.assertEqual(report["verdict"], "FAIL")

    def test_disallowed_fields_rejected(self):
        with self.assertRaises(oe.OffhostError):
            oe.normalize_entries([{"authUserId": A, "erasedAt": T0, "email": "x@y.z"}])

    def test_restore_refused_when_cutoff_uncovered(self):
        store = oe.MemoryStore()
        oe.persist_complete_snapshot(store, stamp_entries() + [
            {"authUserId": B, "erasedAt": T1}
        ], T1, self.env)
        report = oe.recover(store, CUTOFF, stamp_entries())
        self.assertEqual(report["verdict"], "BLOCKED")
        self.assertGreater(report["uncoveredSeconds"], 0)
        self.assertEqual(report["coverageThrough"], T1)

    def test_persist_clock_is_not_coverage(self):
        store = oe.MemoryStore()
        oe.persist_complete_snapshot(store, stamp_entries(), T1, self.env)
        covered = oe.latest_verified_coverage(store)
        self.assertEqual(covered["seal"]["coveredThrough"], T1)
        self.assertNotEqual(covered["seal"]["coveredThrough"], CUTOFF)

    def test_newer_snapshot_must_keep_older_ids(self):
        store = oe.MemoryStore()
        oe.persist_complete_snapshot(store, stamp_entries(), T1, self.env)
        with self.assertRaises(oe.OffhostError):
            oe.persist_complete_snapshot(
                store, [{"authUserId": B, "erasedAt": T2}], T2, self.env
            )

    def test_file_store_and_cli_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ledger = root / "ledger.json"
            ledger.write_text(json.dumps(stamp_entries() + [
                {"authUserId": B, "erasedAt": T1}
            ]), encoding="utf-8")
            store_dir = root / "store"
            env = os.environ.copy()
            env["PARKIO_OFFHOST_ERASURE_ENABLED"] = "1"
            from subprocess import run
            script = Path(__file__).resolve().parent / "offhost-erasure-export.py"
            first = run(
                [sys.executable, str(script), "--from-ledger", str(ledger),
                 "--query-time", T1, "--store-dir", str(store_dir)],
                env=env, capture_output=True, text=True, check=False,
            )
            self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
            out = root / "merged.json"
            recover = Path(__file__).resolve().parent / "offhost-erasure-recover.py"
            stamp = root / "stamp"
            stamp.mkdir()
            (stamp / "erasure-tombstones.json").write_text(
                json.dumps(stamp_entries()), encoding="utf-8"
            )
            blocked = run(
                [sys.executable, str(recover), "--store-dir", str(store_dir),
                 "--recovery-cutoff", CUTOFF, "--data-stamp", str(stamp),
                 "--out", str(out)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(blocked.returncode, 3, blocked.stdout)
            self.assertFalse(out.exists())
            passed = run(
                [sys.executable, str(recover), "--store-dir", str(store_dir),
                 "--recovery-cutoff", T1, "--data-stamp", str(stamp),
                 "--out", str(out)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(passed.returncode, 0, passed.stdout)
            merged = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual({row["authUserId"] for row in merged}, {A, B})


if __name__ == "__main__":
    unittest.main()
