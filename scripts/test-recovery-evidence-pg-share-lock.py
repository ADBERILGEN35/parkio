#!/usr/bin/env python3
"""Real PostgreSQL SHARE-lock test for the recovery evidence contract.

Disposable docker postgres:16.10 (or PARKIO_RECOVERY_PG_*). Coordinated
concurrent sessions. Does not import #104. Does not enable production
off-host capture.

Classification: real PostgreSQL locking test.
"""
from __future__ import annotations

import os
import subprocess
import time
import unittest
import uuid

IMAGE = os.environ.get("PARKIO_RECOVERY_PG_IMAGE", "postgres:16.10")
TABLE_DDL = """
CREATE TABLE IF NOT EXISTS erased_user_tombstones (
    auth_user_id UUID NOT NULL,
    erased_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_erased_user_tombstones PRIMARY KEY (auth_user_id)
);
"""
A = "11111111-1111-4111-8111-111111111111"
B = "22222222-2222-4222-8222-222222222222"


class PostgresError(Exception):
    """psql or docker failed."""


def docker_available():
    try:
        result = subprocess.run(
            ["docker", "info"], capture_output=True, text=True, timeout=20
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    return result.returncode == 0


class RecoveryShareLockPostgresTest(unittest.TestCase):
    container = None

    @classmethod
    def setUpClass(cls):
        if os.environ.get("PARKIO_RECOVERY_PG_CONTAINER"):
            cls.container = os.environ["PARKIO_RECOVERY_PG_CONTAINER"]
            cls.owned = False
            cls.psql(["-c", TABLE_DDL])
            return
        if not docker_available():
            raise unittest.SkipTest("docker unavailable for disposable PostgreSQL")
        name = f"parkio-recov-pg-{os.getpid()}-{uuid.uuid4().hex[:8]}"
        run = subprocess.run(
            [
                "docker", "run", "-d", "--name", name,
                "-e", "POSTGRES_PASSWORD=parkio-isolated-not-prod",
                IMAGE,
            ],
            capture_output=True, text=True, timeout=120,
        )
        if run.returncode != 0:
            raise unittest.SkipTest(f"could not start {IMAGE}: {run.stderr.strip()}")
        cls.container = name
        cls.owned = True
        deadline = time.time() + 45
        while time.time() < deadline:
            ready = subprocess.run(
                ["docker", "exec", name, "pg_isready", "-U", "postgres"],
                capture_output=True, text=True,
            )
            if ready.returncode == 0:
                cls.psql(["-c", TABLE_DDL])
                return
            time.sleep(1)
        cls.tearDownClass()
        raise unittest.SkipTest("disposable PostgreSQL did not become ready")

    @classmethod
    def tearDownClass(cls):
        if getattr(cls, "owned", False) and cls.container:
            subprocess.run(
                ["docker", "rm", "-f", cls.container],
                capture_output=True, text=True, timeout=30,
            )

    @classmethod
    def psql(cls, extra, timeout=30):
        cmd = [
            "docker", "exec", cls.container,
            "psql", "-U", "postgres", "-d", "postgres",
            "-v", "ON_ERROR_STOP=1", "--no-psqlrc", "-X",
        ] + extra
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        if result.returncode != 0:
            raise PostgresError(result.stderr or result.stdout)
        return result.stdout.strip()

    @classmethod
    def psql_async(cls, sql):
        cmd = [
            "docker", "exec", cls.container,
            "psql", "-U", "postgres", "-d", "postgres",
            "-v", "ON_ERROR_STOP=1", "--no-psqlrc", "-X", "-c", sql,
        ]
        return subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def setUp(self):
        self.psql(["-c", "TRUNCATE erased_user_tombstones"])
        self.psql([
            "-c",
            "INSERT INTO erased_user_tombstones (auth_user_id, erased_at) "
            f"VALUES ('{A}', '2026-09-27T09:00:00Z')",
        ])

    def _share_held(self):
        held = self.psql([
            "-t", "-A", "-c",
            "SELECT count(*) FROM pg_locks l "
            "JOIN pg_class c ON c.oid = l.relation "
            "WHERE c.relname = 'erased_user_tombstones' "
            "AND l.mode = 'ShareLock' AND NOT l.granted IS FALSE",
        ])
        return held == "1" or (held.isdigit() and int(held) >= 1)

    def test_share_lock_blocks_concurrent_insert_until_release(self):
        holder = self.psql_async(
            "BEGIN; "
            "SET TRANSACTION ISOLATION LEVEL READ COMMITTED; "
            "SET LOCAL lock_timeout = '12s'; "
            "LOCK TABLE erased_user_tombstones IN SHARE MODE; "
            "SELECT json_agg(auth_user_id), clock_timestamp() "
            "FROM erased_user_tombstones; "
            "SELECT pg_sleep(8); "
            "COMMIT;"
        )
        try:
            deadline = time.time() + 6
            while time.time() < deadline and not self._share_held():
                time.sleep(0.2)
            self.assertTrue(self._share_held(), "SHARE lock was not acquired")
            with self.assertRaises(PostgresError) as ctx:
                self.psql([
                    "-c",
                    "SET lock_timeout = '2s'; "
                    "INSERT INTO erased_user_tombstones (auth_user_id, erased_at) "
                    f"VALUES ('{B}', '2026-09-27T10:00:01Z')",
                ], timeout=15)
            self.assertIn("lock", (ctx.exception.args[0] or "").lower())
        finally:
            stdout, stderr = holder.communicate(timeout=20)
            self.assertEqual(holder.returncode, 0, stderr)
        self.psql([
            "-c",
            "INSERT INTO erased_user_tombstones (auth_user_id, erased_at) "
            f"VALUES ('{B}', '2026-09-27T10:00:01Z')",
        ])
        ids = self.psql([
            "-t", "-A", "-c",
            "SELECT string_agg(auth_user_id::text, ',' ORDER BY auth_user_id) "
            "FROM erased_user_tombstones",
        ])
        self.assertIn(A, ids)
        self.assertIn(B, ids)

    def test_aborted_share_capture_is_not_publishable(self):
        with self.assertRaises(PostgresError):
            self.psql([
                "-c",
                "BEGIN; "
                "SET TRANSACTION ISOLATION LEVEL READ COMMITTED; "
                "LOCK TABLE erased_user_tombstones IN SHARE MODE; "
                "SELECT count(*) FROM erased_user_tombstones; "
                "DO $$ BEGIN RAISE EXCEPTION 'capture aborted'; END $$; "
                "COMMIT;",
            ])
        count = self.psql(["-t", "-A", "-c", "SELECT count(*) FROM erased_user_tombstones"])
        self.assertEqual(count, "1")


if __name__ == "__main__":
    unittest.main()