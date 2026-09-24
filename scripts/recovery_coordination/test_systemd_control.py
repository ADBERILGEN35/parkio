#!/usr/bin/env python3
"""Isolated systemd-adapter tests. Never invoke host systemctl or production units."""
from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "scripts" / "recovery_coordination"))

from coordinator import Coordinator, CoordinationError  # noqa: E402
from export_pause import ExportPauseHandshake  # noqa: E402
from writer_control import MissingCapability, isolated_control  # noqa: E402

FAKE = ROOT / "scripts" / "recovery_coordination" / "isolated" / "fake_systemctl.py"


class SystemdAdapterRefusalTest(unittest.TestCase):
    def test_refuses_live_systemctl_and_production_env(self):
        with self.assertRaises(MissingCapability):
            isolated_control(ROOT, "x", Path("pause"), env={"PARKIO_WRITER_CONTROL_ISOLATED": "1"}, backend="systemd")
        with self.assertRaises(MissingCapability):
            isolated_control(
                ROOT, "x", Path("pause"),
                env={
                    "PARKIO_WRITER_CONTROL_ISOLATED": "1",
                    "PARKIO_ENVIRONMENT": "production",
                    "PARKIO_WRITER_CONTROL_SYSTEMCTL": str(FAKE),
                },
                backend="systemd",
            )


class SystemdAdapterTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="parkio-sd-")
        self.root = Path(self.tmp.name)
        self.state = self.root / "fake-systemd"
        self.state.mkdir()
        self.pause = self.root / "export-pause"
        self.env = {
            "PARKIO_WRITER_CONTROL_ISOLATED": "1",
            "PARKIO_RECOVERY_COORDINATOR_ENABLED": "1",
            "PARKIO_WRITER_CONTROL_SYSTEMCTL": str(FAKE),
            "PARKIO_FAKE_SYSTEMD_STATE": str(self.state),
            "PARKIO_EXPORT_PAUSE_AUTO_ACK": "0",
        }
        self.control = isolated_control(
            ROOT, "parkio-writer-control-isolated-sd", self.pause,
            wait_seconds=3, env=self.env, backend="systemd",
        )
        self.control.require_capabilities()

    def tearDown(self):
        self.tmp.cleanup()

    def _commands(self):
        log = self.state / "commands.log"
        if not log.is_file():
            return []
        return [line.strip() for line in log.read_text(encoding="utf-8").splitlines() if line.strip()]

    def _ack_after(self, delay=0.05):
        def writer():
            handshake = ExportPauseHandshake(self.pause)
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                if handshake.request_path.is_file():
                    text = handshake.request_path.read_text(encoding="utf-8")
                    request_id = ""
                    for line in text.splitlines():
                        if line.startswith("requestId="):
                            request_id = line.split("=", 1)[1]
                    if request_id:
                        time.sleep(delay)
                        handshake.write_ack(request_id, "fake-gateway")
                        return
                time.sleep(0.02)
        thread = threading.Thread(target=writer, daemon=True)
        thread.start()
        return thread

    def test_timer_stops_before_continuous_and_slack_uses_systemctl_stop(self):
        self.control.record_pre_state()
        self.assertEqual(self.control.inventory()["slack_worker"], "running")
        self.assertEqual(self.control.inventory()["nr_guard_timer"], "running")
        self._ack_after()
        self.control.pause("slack_worker")
        self.control.pause("fluent_bit")
        self.control.pause("gateway_exporter")
        stops = [line for line in self._commands() if line.startswith("stop ")]
        self.assertIn("stop parkio-slack-biz-worker.service", stops)
        self.assertNotIn("kill", " ".join(self._commands()))
        nr_stops = [line for line in stops if "nr-log" in line]
        self.assertEqual(nr_stops[0], "stop parkio-nr-log-continuous-guard.timer")
        self.assertEqual(nr_stops[1], "stop parkio-nr-log-continuous.service")
        self.assertEqual(self.control.inventory()["nr_source"], "stopped")
        self.assertEqual(self.control.inventory()["nr_guard_timer"], "stopped")

    def test_resume_only_pre_active_and_partial_pause_cleanup(self):
        self.env["PARKIO_EXPORT_PAUSE_AUTO_ACK"] = "1"
        control = isolated_control(
            ROOT, "parkio-writer-control-isolated-sd3", self.pause,
            wait_seconds=3, env=self.env, backend="systemd",
        )
        control.require_capabilities()
        control.record_pre_state()
        control.pause("inbox_consumer")
        self.assertEqual(control.inventory()["inbox_consumer"], "stopped")
        control.resume("inbox_consumer")
        self.assertEqual(control.inventory()["inbox_consumer"], "running")

        def boom(_backup, args):
            raise CoordinationError("interrupted after partial pause")

        dest = self.root / "snap"
        coord = Coordinator(control, env=self.env, snapshot=boom)
        with self.assertRaises(CoordinationError):
            coord.ordinary_snapshot("iso-1", {"destination": dest})
        self.assertEqual(control.inventory()["slack_worker"], "running")
        self.assertEqual(control.inventory()["nr_guard_timer"], "running")

    def test_missing_ack_aborts_capture(self):
        self.control.wait_seconds = 0.2
        self.control.handshake.wait_seconds = 0.2
        dest = self.root / "noack"
        coord = Coordinator(
            self.control, env=self.env,
            snapshot=lambda *_: (_ for _ in ()).throw(AssertionError("must not capture")),
        )
        with self.assertRaises(CoordinationError) as raised:
            coord.ordinary_snapshot("iso-1", {"destination": dest})
        self.assertIn("acknowledgement", str(raised.exception))
        self.assertFalse(dest.exists())
        self.assertEqual(self.control.inventory()["slack_worker"], "running")

    def test_dr_gate_only_is_deployment_gate_unless_allowed(self):
        self.control.record_pre_state()
        with self.assertRaises(MissingCapability):
            self.control.start("nr_gate")
        self.env["PARKIO_WRITER_CONTROL_ALLOW_NR_GATE_ONLY"] = "1"
        allowed = isolated_control(
            ROOT, "parkio-writer-control-isolated-sd2", self.pause,
            wait_seconds=3, env=self.env, backend="systemd",
        )
        allowed.require_capabilities()
        allowed.record_pre_state()
        allowed.start("nr_gate")
        self.assertTrue(any("start nr-budget-gate.service" in line for line in
                            (self.state / "commands.log").read_text(encoding="utf-8").splitlines()))

    def test_stale_ack_is_not_drain(self):
        handshake = ExportPauseHandshake(self.pause, wait_seconds=0.3)
        handshake.control_dir.mkdir(parents=True, exist_ok=True)
        handshake.write_ack("old-request", "dead-jvm")
        request_id = handshake.request_pause()
        with self.assertRaises(CoordinationError):
            handshake.wait_acknowledged(request_id, timeout=0.25)
        handshake.write_ack(request_id, "new-jvm")
        self.assertEqual(handshake.wait_acknowledged(request_id)["requestId"], request_id)


if __name__ == "__main__":
    unittest.main()
