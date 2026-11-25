#!/usr/bin/env python3
"""Stub-driven proof that the textfile-export unit runs the exporter atomically."""
from __future__ import annotations

import math
import os
import re
import shutil
import signal
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

from slack_biz.export_textfile_metrics import render  # noqa: E402
from slack_biz.store import DeliveryStore  # noqa: E402

_HEARTBEAT_AGE = re.compile(r"(?m)^parkio_slack_biz_worker_heartbeat_age_seconds (\S+)$")

# Runs worker.py as __main__ with a hook that sends SIGTERM to the worker's own process at one point of
# the window between the lock commit and `worker = DeliveryWorker(...)` in main() (#618). A signal that a
# single-threaded process sends itself is delivered before kill() returns, and Python runs the handler as
# soon as os.kill returns, so the stop lands exactly at that point, with no timing involved.
_SIGTERM_IN_WORKER_CONSTRUCTOR = r"""
import os, runpy, signal, sys

scripts, worker_py, point, *argv = sys.argv[1:]
sys.path.insert(0, scripts)
from slack_biz.delivery import DeliveryWorker
from slack_biz.store import DeliveryStore


def stop(store):
    holder = store._conn.execute("SELECT worker_id FROM worker_lock WHERE id=1").fetchone()
    holder = holder[0] if holder else None
    print(f"hook: SIGTERM at {point}, lock held by {holder}", file=sys.stderr, flush=True)
    os.kill(os.getpid(), signal.SIGTERM)


if point == "lock-committed":  # inside DeliveryWorker.__init__, right after the lock row commits
    acquire = DeliveryStore.acquire_worker_lock

    def acquire_worker_lock(self, worker_id):
        acquire(self, worker_id)
        stop(self)

    DeliveryStore.acquire_worker_lock = acquire_worker_lock
elif point == "constructed":  # DeliveryWorker.__init__ has finished; main() has not bound it yet
    init = DeliveryWorker.__init__

    def __init__(self, *args, **kwargs):
        init(self, *args, **kwargs)
        stop(self.store)

    DeliveryWorker.__init__ = __init__
else:
    raise SystemExit(f"unknown hook point {point}")

sys.argv = [worker_py, *argv]
runpy.run_path(worker_py, run_name="__main__")
"""


def heartbeat_age(body: str) -> float:
    match = _HEARTBEAT_AGE.search(body)
    if match is None:
        raise AssertionError(f"export has no worker heartbeat age:\n{body}")
    return float(match.group(1))


def worker_lock_row(db_path: Path) -> tuple[str, float] | None:
    """The worker_lock row, read like the export does, so the read adds no WAL side files."""
    store = DeliveryStore(db_path, readonly=True)
    try:
        with store._lock:  # noqa: SLF001
            row = store._conn.execute(
                "SELECT worker_id, heartbeat_at FROM worker_lock WHERE id=1"
            ).fetchone()
    finally:
        store.close()
    return None if row is None else (row["worker_id"], float(row["heartbeat_at"]))


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


    def _env(self, data_dir: Path, tmp_path: Path) -> dict[str, str]:
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
        return env

    def _export(
        self, data_dir: Path, tmp_path: Path, extra_env: dict[str, str] | None = None
    ) -> tuple[subprocess.CompletedProcess, Path]:
        out = tmp_path / "textfile" / "parkio_slack_biz.prom"
        proc = subprocess.run(
            [sys.executable, str(EXPORT), "--output", str(out)],
            check=False,
            capture_output=True,
            text=True,
            env={**self._env(data_dir, tmp_path), **(extra_env or {})},
            cwd=str(ROOT),
        )
        return proc, out

    def _export_body(self, data_dir: Path, tmp_path: Path, extra_env: dict[str, str]) -> str:
        proc, out = self._export(data_dir, tmp_path, extra_env)
        self.assertEqual(proc.returncode, 0, proc.stderr or proc.stdout)
        return out.read_text(encoding="utf-8")

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


    def test_a_relay_stopped_with_sigterm_is_reported_as_stopped(self) -> None:
        """systemctl stop sends SIGTERM. Each relay entrypoint must still close the store, so the WAL
        side files go and the export reads parkio_slack_biz_relay_db_open 0 (#488 review). No worker
        holds the lock afterwards (the worker releases it), so the heartbeat age reads +Inf (#544)."""
        for script, args, takes_lock in (
            ("worker.py", ["--loop", "--interval", "0.2", "--worker-id", "sigterm-test"], True),
            ("consume_waitlist.py", ["--loop", "--interval", "0.2"], False),
        ):
            with self.subTest(script=script):
                tmp_path = self._tmp()
                data_dir = tmp_path / "data"
                data_dir.mkdir(parents=True, exist_ok=True)
                db_path = data_dir / "slack_biz.sqlite3"
                _seed_store(db_path)
                relay = subprocess.Popen(
                    [sys.executable, str(ROOT / "scripts/slack_biz" / script), *args],
                    env=self._env(data_dir, tmp_path),
                    cwd=str(ROOT),
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.PIPE,
                    text=True,
                )
                self.addCleanup(relay.stderr.close)
                self.addCleanup(relay.kill)
                # Stop a running relay. The worker counts as running once it holds the lock, so the stop
                # has a lock to release; a SIGTERM that lands before the lock would release nothing (#618).
                deadline = time.time() + 30
                while not Path(f"{db_path}-wal").exists() or (
                    takes_lock and worker_lock_row(db_path) is None
                ):
                    if relay.poll() is not None:  # read stderr only once the relay has exited
                        self.fail(f"{script} exited early: {relay.stderr.read()}")
                    if time.time() > deadline:
                        self.fail(f"{script} never opened the store or took the lock")
                    time.sleep(0.05)

                relay.send_signal(signal.SIGTERM)

                # Wait for the exit before anything reads the database or the export.
                _, stderr = relay.communicate(timeout=30)
                self.assertEqual(relay.returncode, 0, stderr)
                self.assertEqual([p.name for p in data_dir.iterdir()], ["slack_biz.sqlite3"])
                # The close path ran: worker.close() deleted the lock row with its heartbeat.
                self.assertIsNone(worker_lock_row(db_path))
                proc, out = self._export(data_dir, tmp_path)
                self.assertEqual(proc.returncode, 0, proc.stderr or proc.stdout)
                body = out.read_text(encoding="utf-8")
                self.assertIn("parkio_slack_biz_relay_db_open 0", body)
                self.assertIn("parkio_slack_biz_delivery_unknown 2", body)
                self.assertEqual(heartbeat_age(body), math.inf)
                self.assertIn("parkio_slack_biz_delivery_enabled 0", body)

    def test_a_sigterm_inside_the_worker_constructor_still_releases_the_lock(self) -> None:
        """#618: DeliveryWorker takes the lock inside its constructor. A SIGTERM handled after the lock
        row commits but before main() binds `worker` used to skip worker.close(), so the lock and its
        fresh heartbeat outlived the worker and the export read age 0 instead of +Inf. A hook stops the
        worker at each end of that window, deterministically."""
        for point in ("lock-committed", "constructed"):
            with self.subTest(point=point):
                tmp_path = self._tmp()
                data_dir = tmp_path / "data"
                data_dir.mkdir(parents=True, exist_ok=True)
                db_path = data_dir / "slack_biz.sqlite3"
                _seed_store(db_path)

                proc = subprocess.run(
                    [
                        sys.executable,
                        "-c",
                        _SIGTERM_IN_WORKER_CONSTRUCTOR,
                        str(ROOT / "scripts"),
                        str(ROOT / "scripts/slack_biz/worker.py"),
                        point,
                        *("--loop", "--interval", "0.2", "--worker-id", "sigterm-window"),
                    ],
                    env=self._env(data_dir, tmp_path),
                    cwd=str(ROOT),
                    capture_output=True,
                    text=True,
                    timeout=30,
                )

                self.assertEqual(proc.returncode, 0, proc.stderr)
                # The signal went out with the lock row committed, i.e. inside the window.
                self.assertIn(f"hook: SIGTERM at {point}, lock held by sigterm-window\n", proc.stderr)
                self.assertEqual([p.name for p in data_dir.iterdir()], ["slack_biz.sqlite3"])
                self.assertIsNone(
                    worker_lock_row(db_path), f"SIGTERM at {point} left the worker lock behind"
                )
                body = self._export_body(data_dir, tmp_path, {})
                self.assertIn("parkio_slack_biz_relay_db_open 0", body)
                self.assertEqual(heartbeat_age(body), math.inf)

    def test_a_crashed_relay_keeps_its_heartbeat_age_growing(self) -> None:
        """#544: SIGKILL skips every close, so the WAL side files stay and parkio_slack_biz_relay_db_open
        stays 1 after the crash. The worker's lock heartbeat is what a crash cannot keep fresh: small
        while the worker polls, growing from the kill on. The export reads a read-only directory, as
        under its unit."""
        tmp_path = self._tmp()
        data_dir = tmp_path / "data"
        data_dir.mkdir(parents=True, exist_ok=True)
        db_path = data_dir / "slack_biz.sqlite3"
        _seed_store(db_path)  # delivery_unknown and DLT rows only: no row is due, so nothing is sent
        worker_env = self._env(data_dir, tmp_path)
        # Delivery on, so the worker heartbeats on every poll. The address is local and refuses
        # connections; it is never called because no row is due.
        worker_env.update(
            {
                "PARKIO_SLACK_BIZ_ENABLED": "true",
                "PARKIO_SLACK_BIZ_WEBHOOK_URL": "http://127.0.0.1:9/slack-biz-unit-test",
                "PARKIO_SLACK_BIZ_WEBHOOK_URL_BIZ": "",
                "PARKIO_SLACK_BIZ_WEBHOOK_URL_OPS": "",
            }
        )
        # The export unit reads the non-secret conf only: delivery on, no webhook.
        delivery_on = {"PARKIO_SLACK_BIZ_ENABLED": "true"}
        relay = subprocess.Popen(
            [
                sys.executable,
                str(ROOT / "scripts/slack_biz/worker.py"),
                *("--loop", "--interval", "0.2", "--worker-id", "crash-test"),
            ],
            env=worker_env,
            cwd=str(ROOT),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.addCleanup(relay.stderr.close)
        self.addCleanup(relay.kill)
        deadline = time.time() + 30
        while not Path(f"{db_path}-wal").exists():
            if relay.poll() is not None:  # read stderr only once the relay has exited
                self.fail(f"worker.py exited early: {relay.stderr.read()}")
            if time.time() > deadline:
                self.fail("worker.py never opened the store")
            time.sleep(0.05)
        self._make_read_only(data_dir)

        deadline = time.time() + 30
        while not math.isfinite(heartbeat_age(self._export_body(data_dir, tmp_path, delivery_on))):
            if relay.poll() is not None:
                self.fail(f"worker.py exited early: {relay.stderr.read()}")
            if time.time() > deadline:
                self.fail("worker.py never took the lock")
            time.sleep(0.1)
        time.sleep(3)
        body = self._export_body(data_dir, tmp_path, delivery_on)
        # Without a heartbeat after the first one the age would be 3 or more by now.
        self.assertLess(heartbeat_age(body), 3)
        self.assertIn("parkio_slack_biz_delivery_enabled 1", body)

        relay.kill()  # SIGKILL: no finally, no lock release, no store close
        relay.wait(timeout=30)
        self.assertTrue(Path(f"{db_path}-wal").exists(), "the crash left the WAL side files behind")
        first = self._export_body(data_dir, tmp_path, delivery_on)
        time.sleep(2.2)
        second = self._export_body(data_dir, tmp_path, delivery_on)
        for body in (first, second):
            self.assertIn("parkio_slack_biz_relay_db_open 1", body)
            self.assertIn("parkio_slack_biz_delivery_unknown 2", body)
        self.assertGreaterEqual(heartbeat_age(second), heartbeat_age(first) + 2)

    def test_render_reports_the_worker_heartbeat_age(self) -> None:
        """#544: the age comes from worker_lock.heartbeat_at through the read-only open. It follows the
        clock, never goes negative, and is +Inf once no worker holds the lock."""
        db_path = self._tmp() / "slack_biz.sqlite3"
        _seed_store(db_path)
        relay = DeliveryStore(db_path)
        try:
            relay.acquire_worker_lock("render-test")
            with relay._lock:  # noqa: SLF001
                beat = float(
                    relay._conn.execute("SELECT heartbeat_at FROM worker_lock WHERE id=1").fetchone()[0]
                )
        finally:
            relay.close()

        ro = DeliveryStore(db_path, readonly=True)
        try:
            body = render(ro, when=beat + 600.9, delivery_enabled=True)
            self.assertEqual(heartbeat_age(body), 600)
            self.assertIn("parkio_slack_biz_delivery_enabled 1\n", body)
            self.assertEqual(heartbeat_age(render(ro, when=beat + 1200, delivery_enabled=True)), 1200)
            # A clock step back reads 0, not a negative age.
            body = render(ro, when=beat - 30, delivery_enabled=False)
            self.assertEqual(heartbeat_age(body), 0)
            self.assertIn("parkio_slack_biz_delivery_enabled 0\n", body)
        finally:
            ro.close()

        relay = DeliveryStore(db_path)
        try:
            relay.release_worker_lock("render-test")
        finally:
            relay.close()
        ro = DeliveryStore(db_path, readonly=True)
        try:
            body = render(ro, when=beat + 600, delivery_enabled=True)
        finally:
            ro.close()
        self.assertIn("parkio_slack_biz_worker_heartbeat_age_seconds +Inf\n", body)


if __name__ == "__main__":
    unittest.main()
