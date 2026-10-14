"""Production-shaped systemd writer control for isolated fixtures only.

Uses documented unit names. Live host execution is refused: this adapter
requires PARKIO_WRITER_CONTROL_ISOLATED=1 and PARKIO_WRITER_CONTROL_SYSTEMCTL
pointing at the disposable fake_systemctl (or an equally isolated binary).
It never invokes the host systemd.

NR pause stops the guard timer before parkio-nr-log-continuous.service.
Slack uses systemctl stop only. Resume restores only components that were
active. Starting nr_gate without fluent-bit is an explicit deployment gate:
the production oneshot unit cannot do that.
"""
from __future__ import annotations

import os
from pathlib import Path
import socket
import subprocess
import sys
import time

try:
    from coordinator import CoordinationError, WRITERS  # type: ignore
    from export_pause import ExportPauseHandshake  # type: ignore
    from writer_catalog import catalog_entry  # type: ignore
except ImportError:
    from recovery_coordination.coordinator import CoordinationError, WRITERS
    from recovery_coordination.export_pause import ExportPauseHandshake
    from recovery_coordination.writer_catalog import catalog_entry

try:
    from writer_control import MissingCapability  # type: ignore
except ImportError:
    from recovery_coordination.writer_control import MissingCapability


SLACK_UNITS = {
    "slack_worker": "parkio-slack-biz-worker.service",
    "inbox_consumer": "parkio-slack-biz-waitlist-consumer.service",
}
NR_BUNDLE = frozenset({"fluent_bit", "nr_source", "nr_gate"})
CONTINUOUS_UNIT = "parkio-nr-log-continuous.service"
SOURCE_UNIT = "parkio-nr-log-source.service"
TIMER_UNIT = "parkio-nr-log-continuous-guard.timer"
PRODUCTION_HOSTNAMES = frozenset({"parkio-civo-prod"})


class SystemdWriterControl:
    def __init__(self, *, pause_dir, env=None, wait_seconds=20, systemctl_bin=None):
        self.env = os.environ if env is None else env
        if self.env.get("PARKIO_WRITER_CONTROL_ISOLATED") != "1":
            raise MissingCapability("production writer control is disabled")
        if self.env.get("PARKIO_ENVIRONMENT", "").lower() == "production":
            raise MissingCapability("refusing writer control when PARKIO_ENVIRONMENT=production")
        host = socket.gethostname().split(".")[0].lower()
        if host in PRODUCTION_HOSTNAMES:
            raise MissingCapability("refusing writer control on the production hostname")
        self.systemctl_bin = systemctl_bin or self.env.get("PARKIO_WRITER_CONTROL_SYSTEMCTL")
        if not self.systemctl_bin:
            raise MissingCapability("live host systemctl is refused")
        if Path(self.systemctl_bin).name in {"systemctl", "systemctl.exe"}:
            raise MissingCapability("refusing the host systemctl binary")
        self.handshake = ExportPauseHandshake(pause_dir, wait_seconds=wait_seconds)
        self.wait_seconds = wait_seconds
        self.pre_timer_active = None
        self.nr_bundle_stopped = False
        self.paused = []
        self.resumed = []
        self._recorded = {}

    def require_capabilities(self, names=None):
        names = list(names or WRITERS)
        self.handshake.control_dir.mkdir(parents=True, exist_ok=True)
        try:
            self._systemctl(["is-active", TIMER_UNIT], check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise MissingCapability("isolated systemctl is unavailable") from exc
        for name in names:
            catalog_entry(name)
        return True

    def inventory(self):
        states = {}
        for name in WRITERS:
            spec = catalog_entry(name)
            if spec["mechanism"] == "export_pause_file" or name == "gateway_exporter":
                states[name] = "stopped" if self.handshake.requested() else "running"
                continue
            if name in SLACK_UNITS:
                states[name] = "running" if self._active(SLACK_UNITS[name]) else "stopped"
                continue
            if name == "nr_source":
                states[name] = "running" if self._active(SOURCE_UNIT) else "stopped"
                continue
            if name in {"fluent_bit", "nr_gate"}:
                states[name] = "running" if self._active(CONTINUOUS_UNIT) else "stopped"
                continue
            states[name] = "stopped"
        states["nr_guard_timer"] = "running" if self._active(TIMER_UNIT) else "stopped"
        return states

    def record_pre_state(self):
        self._recorded = self.inventory()
        self.pre_timer_active = self._recorded.get("nr_guard_timer") == "running"
        return dict(self._recorded)

    def pause(self, name):
        spec = catalog_entry(name)
        if name == "gateway_exporter" or spec["mechanism"] == "export_pause_file":
            request_id = self.handshake.request_pause()
            self.handshake.wait_acknowledged(request_id)
            self.paused.append(name)
            return
        if name in SLACK_UNITS:
            self._systemctl_stop(SLACK_UNITS[name])
            self._wait_inactive(SLACK_UNITS[name])
            self.paused.append(name)
            return
        if name in NR_BUNDLE:
            self._pause_nr_bundle()
            self.paused.append(name)
            return
        raise CoordinationError(f"no systemd identity for {name}")

    def resume(self, name):
        spec = catalog_entry(name)
        if name == "gateway_exporter" or spec["mechanism"] == "export_pause_file":
            self.handshake.resume()
            self.resumed.append(name)
            return
        if name in SLACK_UNITS:
            if self._recorded.get(name) != "running":
                return
            self._systemctl(["start", SLACK_UNITS[name]], check=True)
            self._wait_active(SLACK_UNITS[name])
            self.resumed.append(name)
            return
        if name in NR_BUNDLE:
            self._resume_nr_bundle()
            self.resumed.append(name)
            return
        raise CoordinationError(f"no systemd identity for {name}")

    def start(self, name):
        if name == "nr_gate":
            if self.env.get("PARKIO_WRITER_CONTROL_ALLOW_NR_GATE_ONLY") != "1":
                raise MissingCapability(
                    "production oneshot cannot start nr_gate without fluent-bit; deployment gate"
                )
            self._systemctl(["start", "nr-budget-gate.service"], check=True)
            self._wait_active("nr-budget-gate.service")
            return
        if name == "fluent_bit":
            if self._recorded.get("fluent_bit") != "running" and self.env.get(
                "PARKIO_WRITER_CONTROL_ALLOW_NR_GATE_ONLY"
            ) != "1":
                raise CoordinationError("Fluent Bit stays stopped until reconciliation release")
            self._systemctl(["start", CONTINUOUS_UNIT], check=True)
            if self.pre_timer_active:
                self._systemctl(["start", TIMER_UNIT], check=True)
            return
        return self.resume(name)

    def _pause_nr_bundle(self):
        if self.nr_bundle_stopped:
            return
        if self.pre_timer_active is None:
            self.pre_timer_active = self._active(TIMER_UNIT)
        if self._active(TIMER_UNIT):
            self._systemctl_stop(TIMER_UNIT)
            self._wait_inactive(TIMER_UNIT)
        if self._active(CONTINUOUS_UNIT):
            self._systemctl_stop(CONTINUOUS_UNIT)
            self._wait_inactive(CONTINUOUS_UNIT)
        self.nr_bundle_stopped = True

    def _resume_nr_bundle(self):
        wanted = any(self._recorded.get(name) == "running" for name in NR_BUNDLE)
        if not wanted:
            return
        if not self._active(CONTINUOUS_UNIT):
            self._systemctl(["start", CONTINUOUS_UNIT], check=True)
            self._wait_active(CONTINUOUS_UNIT)
        if self.pre_timer_active and not self._active(TIMER_UNIT):
            self._systemctl(["start", TIMER_UNIT], check=True)
            self._wait_active(TIMER_UNIT)
        self.nr_bundle_stopped = False

    def _systemctl_stop(self, unit):
        # Documented production path: systemctl stop. Never kill(1).
        self._systemctl(["stop", unit], check=True)

    def _active(self, unit):
        result = self._systemctl(["is-active", unit], check=False)
        return result.returncode == 0 and result.stdout.strip() == "active"

    def _wait_inactive(self, unit):
        self._wait_unit(unit, False)

    def _wait_active(self, unit):
        self._wait_unit(unit, True)

    def _wait_unit(self, unit, wanted_active):
        deadline = time.monotonic() + self.wait_seconds
        while time.monotonic() < deadline:
            if self._active(unit) == wanted_active:
                return
            time.sleep(0.05)
        state = "active" if wanted_active else "inactive"
        raise CoordinationError(f"timed out waiting for {unit} to be {state}")

    def _systemctl(self, args, check=True):
        command = [self.systemctl_bin, *args]
        if str(self.systemctl_bin).endswith(".py"):
            command = [sys.executable, self.systemctl_bin, *args]
        merged = os.environ.copy()
        if isinstance(self.env, dict):
            merged.update({key: str(value) for key, value in self.env.items()})
        return subprocess.run(
            command,
            check=check,
            capture_output=True,
            text=True,
            timeout=20,
            env=merged,
        )
