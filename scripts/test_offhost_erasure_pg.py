#!/usr/bin/env python3
"""Isolated PostgreSQL tests for lock-held erasure visibility.

Proves that an unlocked SELECT plus a source-query / wall-clock timestamp
is not complete cutoff coverage when a concurrent requestDeletion-shaped
transaction starts before the snapshot and commits afterward.

auth.erased_at is the application clock at request start, committed later
in the same transaction. This harness reproduces that commit delay.
"""
from __future__ import annotations

import os
from pathlib import Path
import sys
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))
import offhost_erasure as oe  # noqa: E402
import offhost_erasure_pg as pg  # noqa: E402

A = "11111111-1111-4111-8111-111111111111"
B = "22222222-2222-4222-8222-222222222222"
C = "33333333-3333-4333-8333-333333333333"
EARLY = "2026-09-23T17:00:00Z"
COMMITTED = "2026-09-23T17:30:00Z"


def postgres_configured():
    return bool(
        os.environ.get("PARKIO_OFFHOST_PG_PSQL")
        or os.environ.get("PARKIO_OFFHOST_PG_CONTAINER")
        or os.environ.get("PARKIO_OFFHOST_PG_DSN")
    )


class OffhostErasurePostgresTest(unittest.TestCase):
    ephemeral = None

    @classmethod
    def setUpClass(cls):
        if postgres_configured():
            pg.ensure_table()
            return
        try:
            cls.ephemeral = pg.start_ephemeral_postgres()
            pg.ensure_table()
        except pg.PostgresError as exc:
            raise unittest.SkipTest(f"isolated postgres unavailable: {exc}") from exc

    @classmethod
    def tearDownClass(cls):
        if cls.ephemeral:
            pg.stop_ephemeral_postgres(cls.ephemeral)

    def setUp(self):
        pg.psql_one("TRUNCATE erased_user_tombstones", tuples_only=False)
        pg.psql_one(
            "INSERT INTO erased_user_tombstones (auth_user_id, erased_at) "
            f"VALUES ('{A}', '{COMMITTED}')",
            tuples_only=False,
        )

    def test_unlocked_select_misses_in_flight_earlier_erased_at(self):
        holder = pg.psql_async(
            "BEGIN; "
            "INSERT INTO erased_user_tombstones (auth_user_id, erased_at) "
            f"VALUES ('{B}', '{EARLY}'); "
            "SELECT pg_sleep(6); "
            "COMMIT;"
        )
        try:
            time.sleep(1)
            unlocked = pg.unlocked_select_with_client_clock()
            ids = {row["authUserId"] for row in unlocked["entries"]}
            self.assertEqual(ids, {A})
            self.assertNotIn(B, ids)
            self.assertLess(EARLY, unlocked["coveredThroughClaim"])
            self.assertEqual(unlocked["visibilityProtocol"], oe.PROTOCOL_ROWSET)

            locked = pg.locked_snapshot()
            locked_ids = {row["authUserId"] for row in locked["entries"]}
            self.assertEqual(locked_ids, {A, B})
            self.assertEqual(locked["visibilityProtocol"], oe.PROTOCOL_LOCK)
            self.assertGreaterEqual(locked["coveredThrough"], unlocked["coveredThroughClaim"])
        finally:
            holder.wait(timeout=20)
            self.assertEqual(holder.returncode, 0, holder.stderr.read() if holder.stderr else "")

        store = oe.MemoryStore()
        env = {"PARKIO_OFFHOST_ERASURE_ENABLED": "1"}
        unsafe = oe.persist_complete_snapshot(
            store, unlocked["entries"], unlocked["coveredThroughClaim"], env,
            visibility_protocol=oe.PROTOCOL_ROWSET,
        )
        self.assertFalse(unsafe["coverageAdvanced"])
        self.assertEqual(oe.recover(store, unlocked["coveredThroughClaim"])["verdict"], "BLOCKED")

        sealed = oe.persist_complete_snapshot(
            store, locked["entries"], locked["coveredThrough"], env,
            visibility_protocol=oe.PROTOCOL_LOCK,
        )
        self.assertTrue(sealed["coverageAdvanced"])
        report = oe.recover(store, locked["coveredThrough"], [{"authUserId": A, "erasedAt": COMMITTED}])
        self.assertEqual(report["verdict"], "PASS")
        self.assertEqual({row["authUserId"] for row in report["merged"]}, {A, B})
        self.assertNotIn(C, {row["authUserId"] for row in report["merged"]})

    def test_lock_watermark_does_not_include_later_commit(self):
        locked = pg.locked_snapshot()
        pg.psql_one(
            "INSERT INTO erased_user_tombstones (auth_user_id, erased_at) "
            f"VALUES ('{C}', '{EARLY}')",
            tuples_only=False,
        )
        ids = {row["authUserId"] for row in locked["entries"]}
        self.assertEqual(ids, {A})
        self.assertNotIn(C, ids)
        store = oe.MemoryStore()
        env = {"PARKIO_OFFHOST_ERASURE_ENABLED": "1"}
        oe.persist_complete_snapshot(
            store, locked["entries"], locked["coveredThrough"], env,
            visibility_protocol=oe.PROTOCOL_LOCK,
        )
        later = "2099-01-01T00:00:00Z"
        report = oe.recover(store, later)
        self.assertEqual(report["verdict"], "BLOCKED")
        self.assertGreater(report["uncoveredSeconds"], 0)


if __name__ == "__main__":
    unittest.main()
