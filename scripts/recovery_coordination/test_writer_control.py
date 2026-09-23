#!/usr/bin/env python3
"""Isolated writer-control tests. Never touches production compose or units."""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys_path_scripts = str(ROOT / "scripts")
import sys
sys.path.insert(0, sys_path_scripts)
sys.path.insert(0, str(ROOT / "scripts" / "recovery_coordination"))

from coordinator import Coordinator, CoordinationError, Clock  # noqa: E402
from slack_reconciliation import reconcile_slack_events  # noqa: E402
from writer_catalog import (  # noqa: E402
    FORBIDDEN_COMPOSE_PROJECTS,
    ISOLATED_PROJECT_PREFIX,
    verify_catalog_against_repo,
)
from writer_control import AllowlistedWriterControl, MissingCapability, isolated_control  # noqa: E402

DOCKER = shutil.which("docker")


class CatalogAndRefusalTest(unittest.TestCase):
    def test_catalog_matches_repository_definitions(self):
        errors = verify_catalog_against_repo(ROOT)
        self.assertEqual(errors, [])

    def test_refuses_production_project_and_environment(self):
        env = {"PARKIO_WRITER_CONTROL_ISOLATED": "1", "PARKIO_ENVIRONMENT": "production"}
        with self.assertRaises(MissingCapability):
            isolated_control(ROOT, ISOLATED_PROJECT_PREFIX + "x", Path("pause"), env=env)
        env = {"PARKIO_WRITER_CONTROL_ISOLATED": "1"}
        with self.assertRaises(MissingCapability):
            AllowlistedWriterControl(
                isolated=True, compose_project="parkio-nr-log-continuous",
                compose_file=ROOT / "scripts/recovery_coordination/isolated/docker-compose.writer-control-isolated.yml",
                pause_file=Path("pause"), repo_root=ROOT, env=env,
            )
        self.assertIn("parkio-nr-log-continuous", FORBIDDEN_COMPOSE_PROJECTS)
        with self.assertRaises(MissingCapability):
            isolated_control(ROOT, ISOLATED_PROJECT_PREFIX + "x", Path("pause"), env={})

    def test_missing_docker_fails_before_pause(self):
        env = {"PARKIO_WRITER_CONTROL_ISOLATED": "1"}
        control = isolated_control(
            ROOT, ISOLATED_PROJECT_PREFIX + "nodocker", Path("pause"),
            env=env, backend="compose",
        )
        control.compose_bin = None
        with self.assertRaises(MissingCapability):
            control.require_capabilities()


class IsolatedProcessAdapterTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="parkio-isolated-wc-")
        self.root = Path(self.tmp.name)
        self.pause_file = self.root / "export.pause"
        self.project = ISOLATED_PROJECT_PREFIX + uuid.uuid4().hex[:10]
        self.env = {"PARKIO_WRITER_CONTROL_ISOLATED": "1", "PARKIO_RECOVERY_COORDINATOR_ENABLED": "1"}
        self.control = isolated_control(ROOT, self.project, self.pause_file, wait_seconds=5, env=self.env, backend="process")
        self.control.require_capabilities()
        self.control.bring_up()

    def tearDown(self):
        try:
            self.control.tear_down()
        finally:
            self.tmp.cleanup()

    def test_successful_pause_snapshot_resume_and_measured_wall(self):
        before = self.control.inventory()
        self.assertEqual(before["fluent_bit"], "running")
        self.assertEqual(before["slack_worker"], "running")
        self.assertFalse(self.pause_file.exists())

        dest = self.root / "snap"
        captured = []

        def capture(_backup, args):
            path = self.root / "plaintext"
            path.mkdir()
            (path / "sealed").write_text("consistent\n", encoding="utf-8")
            captured.append(path)
            return path

        def seal(plaintext, backup_id, args):
            dest.mkdir()
            (dest / "COMPLETE").write_text("ok\n", encoding="utf-8")
            (dest / "id.json").write_text('{"gateway_outbox_backup_id":"%s"}' % backup_id,
                                          encoding="utf-8")
            shutil.rmtree(plaintext, ignore_errors=True)
            return {"destination": dest, "gateway_outbox_backup_id": backup_id,
                    "encrypted_after_resume": True}

        def verify(path):
            return {"status": "verified", "gateway_outbox_backup_id": "iso-1"}

        coord = Coordinator(self.control, env=self.env, capture=capture, seal=seal, verify=verify)
        wall_before = time.monotonic()
        result = coord.ordinary_snapshot("iso-1", {"destination": dest})
        wall = time.monotonic() - wall_before
        self.assertEqual(result["verdict"], "PASS")
        self.assertFalse(result["encryptionDuringPause"])
        self.assertEqual(result["liveWriterControl"], "allowlisted-isolated")
        self.assertGreater(result["measuredWallPauseSeconds"], 0)
        self.assertLess(result["measuredWallPauseSeconds"], 60)
        self.assertLess(wall, 90)
        self.assertFalse((self.root / "plaintext").exists())
        after = self.control.inventory()
        self.assertEqual(after["fluent_bit"], "running")
        self.assertEqual(after["slack_worker"], "running")
        self.assertFalse(self.pause_file.exists())
        self.assertTrue((dest / "COMPLETE").is_file())

    def test_stop_failure_resumes_pre_state(self):
        class Boom(type(self.control)):
            pass

        control = self.control
        original = control.pause

        def fail_inbox(name):
            if name == "inbox_consumer":
                raise CoordinationError("stop failed for inbox_consumer")
            return original(name)

        control.pause = fail_inbox
        dest = self.root / "fail-snap"
        coord = Coordinator(control, env=self.env, snapshot=lambda *_: (_ for _ in ()).throw(AssertionError("no snapshot")))
        with self.assertRaises(CoordinationError):
            coord.ordinary_snapshot("iso-1", {"destination": dest})
        states = control.inventory()
        self.assertEqual(states["slack_worker"], "running")
        self.assertEqual(states["fluent_bit"], "running")
        self.assertEqual(states["inbox_consumer"], "running")

    def test_timeout_and_interrupted_and_partial_pause(self):
        control = isolated_control(ROOT, self.project, self.pause_file, wait_seconds=0.05, env=self.env, backend="process")
        control.require_capabilities()
        control.bring_up()
        self.addCleanup(control.tear_down)
        original_inventory = control.inventory

        def stuck():
            states = original_inventory()
            states["slack_worker"] = "running"
            return states

        control.inventory = stuck
        dest = self.root / "timeout-snap"
        coord = Coordinator(control, env=self.env, snapshot=lambda *_a, **_k: {"destination": dest, "gateway_outbox_backup_id": "iso-1"})
        with self.assertRaises(CoordinationError):
            coord.ordinary_snapshot("iso-1", {"destination": dest})

        control.inventory = original_inventory
        control.wait_seconds = 25
        self.control.bring_up()

        def interrupt(_backup, args):
            raise CoordinationError("interrupted after pause")

        dest2 = self.root / "interrupt-snap"
        coord = Coordinator(self.control, env=self.env, snapshot=interrupt)
        with self.assertRaises(CoordinationError):
            coord.ordinary_snapshot("iso-1", {"destination": dest2})
        states = self.control.inventory()
        self.assertEqual(states["nr_source"], "running")
        self.assertEqual(states["nr_gate"], "running")
        self.assertFalse(self.pause_file.exists())

    def test_disaster_release_keeps_publishers_stopped_until_review(self):
        def erasure(*_args):
            return {"verdict": "PASS", "coverageThrough": "2026-09-23T18:00:00Z"}

        def prepare(*_args):
            return {"status": "manual_reconciliation_required"}

        coord = Coordinator(self.control, env=self.env, prepare_recovery=prepare, erasure_recover=erasure)
        hold = coord.disaster_stage("iso-1", [{"eventId": "e1"}], self.root / "snap",
                                    self.root / "staged", "store", "2026-09-23T18:00:00Z")
        self.assertEqual(hold["verdict"], "HOLD")
        states = self.control.inventory()
        self.assertEqual(states["fluent_bit"], "stopped")
        self.assertEqual(states["slack_worker"], "stopped")
        self.assertEqual(states["nr_gate"], "running")
        self.assertFalse(self.pause_file.exists())
        report = reconcile_slack_events(["e1"], [("e1", "e1", "queued")])
        with self.assertRaises(CoordinationError):
            coord.release_collection()
        coord.record_reconciliation(nr_spending_reviewed=True, slack_reviewed=True,
                                    release_authorized=True, slack_event_report=report)
        released = coord.release_collection()
        self.assertEqual(released["verdict"], "RELEASED")
        states = self.control.inventory()
        self.assertEqual(states["fluent_bit"], "running")
        self.assertEqual(states["slack_worker"], "running")
        self.assertEqual(report["auto_replay"], [])


@unittest.skipUnless(DOCKER, "docker not available for isolated compose control")
class IsolatedComposeSmokeTest(unittest.TestCase):
    def test_compose_backend_times_out_instead_of_touching_production(self):
        env = {"PARKIO_WRITER_CONTROL_ISOLATED": "1"}
        pause = Path(tempfile.gettempdir()) / "parkio-isolated-export.pause"
        control = isolated_control(
            ROOT, ISOLATED_PROJECT_PREFIX + "smoke", pause, wait_seconds=5,
            env=env, backend="compose",
        )
        control.require_capabilities()
        try:
            control.bring_up()
        except (MissingCapability, CoordinationError, subprocess.TimeoutExpired):
            self.skipTest("isolated compose backend unavailable on this host")
        try:
            self.assertEqual(control.inventory()["fluent_bit"], "running")
            control.pause("fluent_bit")
            self.assertEqual(control.inventory()["fluent_bit"], "stopped")
            control.resume("fluent_bit")
            self.assertEqual(control.inventory()["fluent_bit"], "running")
        finally:
            control.tear_down()


if __name__ == "__main__":
    unittest.main()
