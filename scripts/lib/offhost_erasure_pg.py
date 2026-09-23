#!/usr/bin/env python3
"""PostgreSQL lock-protocol helper for off-host erasure snapshots.

Does not log tombstone payloads. Does not change erasure-tombstones.sh.
"""
from __future__ import annotations

import json
import os
import subprocess
import time

from offhost_erasure import OffhostError, PROTOCOL_LOCK, PROTOCOL_ROWSET

TABLE_DDL = """
CREATE TABLE IF NOT EXISTS erased_user_tombstones (
    auth_user_id UUID NOT NULL,
    erased_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_erased_user_tombstones PRIMARY KEY (auth_user_id)
);
"""
PLAIN_SELECT = """
SELECT COALESCE(json_agg(json_build_object(
    'authUserId', auth_user_id,
    'erasedAt', to_char(erased_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
) ORDER BY auth_user_id), '[]'::json)
FROM erased_user_tombstones;
"""
PLAIN_CLOCK = """
SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"');
"""


class PostgresError(OffhostError):
    """psql or docker failed."""


def _psql_base(interactive=False):
    explicit = os.environ.get("PARKIO_OFFHOST_PG_PSQL")
    dsn = os.environ.get("PARKIO_OFFHOST_PG_DSN")
    container = os.environ.get("PARKIO_OFFHOST_PG_CONTAINER")
    if explicit:
        cmd = [explicit]
        if dsn:
            cmd.append(dsn)
        return cmd + ["-v", "ON_ERROR_STOP=1", "--no-psqlrc", "-X"]
    if container:
        cmd = ["docker", "exec"]
        if interactive:
            cmd.append("-i")
        cmd.extend([
            container,
            "psql", "-U", os.environ.get("PARKIO_OFFHOST_PG_USER", "postgres"),
            "-d", os.environ.get("PARKIO_OFFHOST_PG_DB", "postgres"),
            "-v", "ON_ERROR_STOP=1", "--no-psqlrc", "-X",
        ])
        return cmd
    raise PostgresError("set PARKIO_OFFHOST_PG_PSQL or PARKIO_OFFHOST_PG_CONTAINER")


def psql_async(sql):
    cmd = _psql_base() + ["-c", sql]
    try:
        return subprocess.Popen(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
        )
    except OSError as exc:
        raise PostgresError("psql async failed") from exc


def psql_one(sql, tuples_only=True):
    cmd = _psql_base()
    if tuples_only:
        cmd.extend(["-t", "-A"])
    cmd.extend(["-c", sql])
    try:
        result = subprocess.run(
            cmd, check=False, capture_output=True, text=True, timeout=60
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise PostgresError(f"psql failed: {type(exc).__name__}") from exc
    if result.returncode != 0:
        raise PostgresError("psql rejected the statement")
    return result.stdout.strip()


def ensure_table():
    psql_one(TABLE_DDL, tuples_only=False)


def unlocked_select_with_client_clock():
    """Reproduce the unsafe nightly-export pattern: SELECT then stamp now()."""
    raw = psql_one(PLAIN_SELECT)
    try:
        entries = json.loads(raw) if raw else []
    except ValueError as exc:
        raise PostgresError("unlocked select is not JSON") from exc
    query_time = psql_one(PLAIN_CLOCK)
    return {
        "visibilityProtocol": PROTOCOL_ROWSET,
        "coveredThroughClaim": query_time,
        "entries": entries,
    }


LOCKED_SNAPSHOT_C = """
LOCK TABLE erased_user_tombstones IN SHARE MODE;
SELECT json_build_object(
    'visibilityProtocol', 'table-share-lock',
    'coveredThrough', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
    'entries', COALESCE((
        SELECT json_agg(json_build_object(
            'authUserId', auth_user_id,
            'erasedAt', to_char(erased_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
        ) ORDER BY auth_user_id)
        FROM erased_user_tombstones
    ), '[]'::json)
);
"""


def locked_snapshot():
    """SHARE-lock the table, read it, watermark with lock-held clock_timestamp()."""
    cmd = _psql_base() + ["--single-transaction", "-t", "-A", "-c", LOCKED_SNAPSHOT_C]
    try:
        result = subprocess.run(
            cmd, check=False, capture_output=True, text=True, timeout=60
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise PostgresError(f"locked snapshot failed: {type(exc).__name__}") from exc
    if result.returncode != 0:
        raise PostgresError("locked snapshot rejected")
    line = ""
    for candidate in reversed(result.stdout.splitlines()):
        if candidate.strip().startswith("{"):
            line = candidate.strip()
            break
    try:
        body = json.loads(line)
    except ValueError as exc:
        raise PostgresError("locked snapshot is not JSON") from exc
    if body.get("visibilityProtocol") != PROTOCOL_LOCK:
        raise PostgresError("locked snapshot missing protocol")
    return body


def start_ephemeral_postgres():
    """Start an isolated postgres:16 for tests. Caller must stop it."""
    name = f"offhost-erasure-pg-{os.getpid()}-{int(time.time())}"
    image = os.environ.get("PARKIO_OFFHOST_PG_IMAGE", "postgres:16.10")
    run = subprocess.run(
        [
            "docker", "run", "-d", "--rm", "--name", name,
            "-e", "POSTGRES_HOST_AUTH_METHOD=trust",
            image,
        ],
        check=False, capture_output=True, text=True, timeout=120,
    )
    if run.returncode != 0:
        raise PostgresError("docker run postgres failed")
    inspect = subprocess.run(
        ["docker", "inspect", "-f", "{{.State.Status}}", name],
        check=False, capture_output=True, text=True, timeout=15,
    )
    if inspect.stdout.strip() != "running":
        started = subprocess.run(
            ["docker", "start", name],
            check=False, capture_output=True, text=True, timeout=20,
        )
        if started.returncode != 0:
            subprocess.run(["docker", "rm", "-f", name], check=False, capture_output=True)
            raise PostgresError("docker postgres did not start")
    os.environ["PARKIO_OFFHOST_PG_CONTAINER"] = name
    deadline = time.time() + 30
    last_error = "not ready"
    while time.time() < deadline:
        try:
            psql_one("SELECT 1")
            return name
        except PostgresError as exc:
            last_error = str(exc)
            time.sleep(1)
    stop_ephemeral_postgres(name)
    raise PostgresError(f"postgres did not become ready: {last_error}")


def stop_ephemeral_postgres(name):
    subprocess.run(
        ["docker", "rm", "-f", name],
        check=False, capture_output=True, text=True, timeout=30,
    )
    if os.environ.get("PARKIO_OFFHOST_PG_CONTAINER") == name:
        os.environ.pop("PARKIO_OFFHOST_PG_CONTAINER", None)
