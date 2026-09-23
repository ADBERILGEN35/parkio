"""Allowlisted writer control for isolated non-production targets only.

Supported mechanisms, taken from repository deployment definitions:
- docker compose stop/start of allowlisted service names
- exporter pause file (gateway export loop only)
- isolated child processes using those same allowlisted names (CI/local)

Production compose projects, production systemd units, and unresolved
identities are refused. Fail before the first pause if a required
capability is missing.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

try:
    from coordinator import CoordinationError, WRITERS  # type: ignore
    from writer_catalog import (  # type: ignore
        CATALOG,
        FORBIDDEN_COMPOSE_PROJECTS,
        ISOLATED_COMPOSE_FILE,
        ISOLATED_PROJECT_PREFIX,
        catalog_entry,
    )
except ImportError:
    from recovery_coordination.coordinator import CoordinationError, WRITERS
    from recovery_coordination.writer_catalog import (
        CATALOG,
        FORBIDDEN_COMPOSE_PROJECTS,
        ISOLATED_COMPOSE_FILE,
        ISOLATED_PROJECT_PREFIX,
        catalog_entry,
    )


class MissingCapability(CoordinationError):
    """A required control mechanism is absent. Do not start a partial pause."""


class AllowlistedWriterControl:
    def __init__(self, *, isolated, compose_project, compose_file, pause_file,
                 repo_root, wait_seconds=20, compose_bin=None, env=None):
        self.env = os.environ if env is None else env
        if not isolated or self.env.get("PARKIO_WRITER_CONTROL_ISOLATED") != "1":
            raise MissingCapability("production writer control is disabled")
        if self.env.get("PARKIO_ENVIRONMENT", "").lower() == "production":
            raise MissingCapability("refusing writer control when PARKIO_ENVIRONMENT=production")
        if not compose_project.startswith(ISOLATED_PROJECT_PREFIX):
            raise MissingCapability("compose project is not the isolated allowlist prefix")
        if compose_project in FORBIDDEN_COMPOSE_PROJECTS:
            raise MissingCapability("compose project is a documented production identity")
        self.compose_project = compose_project
        self.compose_file = Path(compose_file)
        self.pause_file = Path(pause_file)
        self.repo_root = Path(repo_root)
        self.wait_seconds = wait_seconds
        self.compose_bin = compose_bin or shutil.which("docker")
        self.paused = []
        self.resumed = []

    def require_capabilities(self, names=None):
        names = list(names or WRITERS)
        if not self.compose_bin:
            raise MissingCapability("docker is required for isolated compose control")
        if not self.compose_file.is_file():
            raise MissingCapability("isolated compose file is missing")
        expected = self.repo_root / ISOLATED_COMPOSE_FILE
        if self.compose_file.resolve() != expected.resolve() and ISOLATED_COMPOSE_FILE not in self.compose_file.as_posix():
            raise MissingCapability("compose file is not the isolated allowlisted fixture")
        try:
            self._compose(["version"], check=True)
        except (OSError, subprocess.CalledProcessError) as exc:
            raise MissingCapability("docker compose is unavailable") from exc
        self.pause_file.parent.mkdir(parents=True, exist_ok=True)
        for name in names:
            spec = catalog_entry(name)
            if spec["mechanism"] == "export_pause_file":
                continue
            if not spec.get("isolated_compose_service"):
                raise MissingCapability(f"{name} has no isolated compose identity")
        return True

    def inventory(self):
        states = {}
        compose_states = self._compose_states()
        for name, spec in CATALOG.items():
            if spec["mechanism"] == "export_pause_file":
                states[name] = "stopped" if self.pause_file.is_file() else "running"
                continue
            service = spec["isolated_compose_service"]
            states[name] = compose_states.get(service, "stopped")
        return states

    def pause(self, name):
        spec = catalog_entry(name)
        if spec["mechanism"] == "export_pause_file":
            self.pause_file.write_text("export_paused\n", encoding="utf-8")
            self.paused.append(name)
            return
        service = spec["isolated_compose_service"]
        result = self._compose(["stop", "-t", "8", service], check=False)
        if result.returncode != 0:
            raise CoordinationError(f"stop failed for {name}")
        self._wait_until(name, "stopped")
        self.paused.append(name)

    def resume(self, name):
        spec = catalog_entry(name)
        if spec["mechanism"] == "export_pause_file":
            self.pause_file.unlink(missing_ok=True)
            self.resumed.append(name)
            return
        service = spec["isolated_compose_service"]
        result = self._compose(["start", service], check=False)
        if result.returncode != 0:
            raise CoordinationError(f"start failed for {name}")
        self._wait_until(name, "running")
        self.resumed.append(name)

    def start(self, name):
        """Explicit start used by DR release. Still allowlisted and isolated."""
        return self.resume(name)

    def bring_up(self):
        result = self._compose(["up", "-d"], check=False)
        if result.returncode != 0:
            raise MissingCapability(result.stderr or result.stdout or "compose up failed")
        deadline = time.monotonic() + 90
        needed = [name for name, spec in CATALOG.items() if spec.get("isolated_compose_service")]
        while time.monotonic() < deadline:
            states = self.inventory()
            if all(states.get(name) == "running" for name in needed):
                return states
            time.sleep(0.4)
        raise CoordinationError("isolated writers did not become running")

    def tear_down(self):
        self.pause_file.unlink(missing_ok=True)
        self._compose(["down", "-v", "--remove-orphans"], check=False)

    def _wait_until(self, name, wanted):
        deadline = time.monotonic() + self.wait_seconds
        while time.monotonic() < deadline:
            if self.inventory().get(name) == wanted:
                return
            time.sleep(0.2)
        raise CoordinationError(f"timed out waiting for {name} to be {wanted}")

    def _compose_states(self):
        result = self._compose(["ps", "-a", "--format", "json"], check=False)
        if result.returncode != 0:
            raise MissingCapability("cannot read isolated compose state")
        states = {}
        raw = result.stdout.strip()
        if not raw:
            return states
        # docker compose may emit a JSON array or NDJSON.
        try:
            payload = json.loads(raw)
            rows = payload if isinstance(payload, list) else [payload]
        except json.JSONDecodeError:
            rows = [json.loads(line) for line in raw.splitlines() if line.strip()]
        for row in rows:
            service = row.get("Service") or row.get("Name", "").split("-")[-1]
            state = (row.get("State") or row.get("Status") or "").lower()
            if "running" in state:
                states[row.get("Service") or service] = "running"
            else:
                states[row.get("Service") or service] = "stopped"
        return states

    def _compose(self, args, check=True):
        command = [self.compose_bin, "compose", "-p", self.compose_project,
                   "-f", str(self.compose_file), *args]
        return subprocess.run(command, check=check, capture_output=True, text=True, timeout=90)


class IsolatedProcessControl:
    """Allowlisted disposable OS processes. Not systemd units and not production."""

    def __init__(self, *, pause_file, env=None, wait_seconds=10):
        self.env = os.environ if env is None else env
        if self.env.get("PARKIO_WRITER_CONTROL_ISOLATED") != "1":
            raise MissingCapability("production writer control is disabled")
        if self.env.get("PARKIO_ENVIRONMENT", "").lower() == "production":
            raise MissingCapability("refusing writer control when PARKIO_ENVIRONMENT=production")
        self.pause_file = Path(pause_file)
        self.wait_seconds = wait_seconds
        self.procs: dict[str, subprocess.Popen] = {}
        self.paused = []
        self.resumed = []

    def require_capabilities(self, names=None):
        self.pause_file.parent.mkdir(parents=True, exist_ok=True)
        for name in names or WRITERS:
            catalog_entry(name)
        return True

    def inventory(self):
        states = {}
        for name, spec in CATALOG.items():
            if spec["mechanism"] == "export_pause_file":
                states[name] = "stopped" if self.pause_file.is_file() else "running"
                continue
            proc = self.procs.get(name)
            states[name] = "running" if proc is not None and proc.poll() is None else "stopped"
        return states

    def pause(self, name):
        spec = catalog_entry(name)
        if spec["mechanism"] == "export_pause_file":
            self.pause_file.write_text("export_paused\n", encoding="utf-8")
            self.paused.append(name)
            return
        proc = self.procs.get(name)
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=self.wait_seconds)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
        deadline = time.monotonic() + self.wait_seconds
        while time.monotonic() < deadline:
            if self.inventory().get(name) == "stopped":
                self.paused.append(name)
                return
            time.sleep(0.05)
        raise CoordinationError(f"timed out waiting for {name} to be stopped")

    def resume(self, name):
        spec = catalog_entry(name)
        if spec["mechanism"] == "export_pause_file":
            self.pause_file.unlink(missing_ok=True)
            self.resumed.append(name)
            return
        current = self.procs.get(name)
        if current is None or current.poll() is not None:
            self._spawn(name)
        self.resumed.append(name)

    def start(self, name):
        return self.resume(name)

    def bring_up(self):
        for name, spec in CATALOG.items():
            if spec["mechanism"] == "export_pause_file":
                self.pause_file.unlink(missing_ok=True)
                continue
            self._spawn(name)
        return self.inventory()

    def tear_down(self):
        self.pause_file.unlink(missing_ok=True)
        for name in list(self.procs):
            proc = self.procs[name]
            if proc.poll() is None:
                proc.kill()
                proc.wait(timeout=5)
        self.procs.clear()

    def _spawn(self, name):
        catalog_entry(name)
        self.procs[name] = subprocess.Popen(
            [sys.executable, "-c", "import time; time.sleep(3600)"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )


def isolated_control(repo_root, project, pause_file, wait_seconds=20, env=None, backend=None):
    env = os.environ if env is None else env
    backend = backend or env.get("PARKIO_WRITER_CONTROL_BACKEND", "process")
    if backend == "compose":
        root = Path(repo_root)
        return AllowlistedWriterControl(
            isolated=True,
            compose_project=project,
            compose_file=root / ISOLATED_COMPOSE_FILE,
            pause_file=pause_file,
            repo_root=root,
            wait_seconds=wait_seconds,
            env=env,
        )
    return IsolatedProcessControl(pause_file=pause_file, env=env, wait_seconds=wait_seconds)
