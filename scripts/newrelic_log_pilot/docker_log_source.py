#!/usr/bin/env python3
"""Bounded Parkio log source using Docker's supported logs interface.

This helper runs on the Docker host. It validates and follows exactly one
container for each approved Compose service through ``docker logs``. It never
opens Docker's private logging-driver files and does not expose docker.sock to
the collector. The caller must grant this process narrowly controlled Docker
CLI access; membership in the Docker group is effectively root-equivalent.
"""

from __future__ import annotations

import argparse
import errno
import json
import os
import selectors
import signal
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path


SERVICES = ("gateway-service", "auth-service", "parking-service")
STOP = threading.Event()


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


def atomic_json(path: Path, value: object) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, separators=(",", ":")) + "\n", encoding="utf-8")
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)


@dataclass(frozen=True)
class Container:
    service: str
    container_id: str
    name: str
    driver: str


class DockerClient:
    def __init__(self, binary: str, project: str, timeout: int) -> None:
        self.binary = binary
        self.project = project
        self.timeout = timeout

    def _run(self, *args: str) -> str:
        completed = subprocess.run(
            [self.binary, *args], check=True, text=True, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, timeout=self.timeout,
        )
        return completed.stdout

    def resolve(self, service: str) -> Container:
        output = self._run(
            "ps", "--filter", f"label=com.docker.compose.project={self.project}",
            "--filter", f"label=com.docker.compose.service={service}",
            "--filter", "status=running", "--format", "{{.ID}}",
        )
        ids = [line.strip() for line in output.splitlines() if line.strip()]
        if len(ids) != 1:
            raise RuntimeError(f"{service}: expected exactly one running container, found {len(ids)}")
        raw = json.loads(self._run("inspect", ids[0]))
        if not isinstance(raw, list) or len(raw) != 1:
            raise RuntimeError(f"{service}: docker inspect returned an unexpected result")
        item = raw[0]
        labels = item.get("Config", {}).get("Labels", {}) or {}
        status = item.get("State", {}).get("Status")
        driver = item.get("HostConfig", {}).get("LogConfig", {}).get("Type")
        container_id = str(item.get("Id", ""))
        name = str(item.get("Name", "")).lstrip("/")
        if labels.get("com.docker.compose.project") != self.project:
            raise RuntimeError(f"{service}: project label mismatch")
        if labels.get("com.docker.compose.service") != service:
            raise RuntimeError(f"{service}: service label mismatch")
        if status != "running" or not container_id:
            raise RuntimeError(f"{service}: container is not running or has no identity")
        if driver not in {"json-file", "local", "journald"}:
            raise RuntimeError(f"{service}: logging driver {driver!r} is not approved")
        return Container(service, container_id, name, str(driver))

    def follow(self, container: Container, since: str) -> subprocess.Popen[bytes]:
        return subprocess.Popen(
            [self.binary, "logs", "--follow", "--timestamps", "--since", since, container.container_id],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0,
        )


class BoundedWriter:
    def __init__(self, root: Path, service: str, max_bytes: int, max_files: int) -> None:
        self.directory = root / service
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o750)
        os.chmod(self.directory, 0o750)
        self.path = self.directory / "source-json.log"
        self.max_bytes = max_bytes
        self.max_files = max_files
        self._lock = threading.Lock()
        fd = os.open(self.path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o640)
        os.close(fd)
        os.chmod(self.path, 0o640)

    def _rotate(self) -> None:
        oldest = self.path.with_name(f"{self.path.name}.{self.max_files - 1}")
        oldest.unlink(missing_ok=True)
        for index in range(self.max_files - 2, 0, -1):
            source = self.path.with_name(f"{self.path.name}.{index}")
            if source.exists():
                os.replace(source, self.path.with_name(f"{self.path.name}.{index + 1}"))
        if self.path.exists():
            os.replace(self.path, self.path.with_name(f"{self.path.name}.1"))

    def write(self, timestamp: str, stream: str, message: str) -> bool:
        envelope = json.dumps(
            {"log": message + ("" if message.endswith("\n") else "\n"), "stream": stream, "time": timestamp},
            separators=(",", ":"), ensure_ascii=False,
        ).encode("utf-8") + b"\n"
        if len(envelope) > self.max_bytes:
            print(f"source-helper DROP service={self.directory.name} reason=record-too-large bytes={len(envelope)}", file=sys.stderr, flush=True)
            return False
        try:
            with self._lock:
                current = self.path.stat().st_size if self.path.exists() else 0
                if current + len(envelope) > self.max_bytes:
                    self._rotate()
                fd = os.open(self.path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o640)
                try:
                    os.write(fd, envelope)
                    os.fsync(fd)
                finally:
                    os.close(fd)
            return True
        except OSError as exc:
            reason = "storage-full" if exc.errno in {errno.ENOSPC, errno.EDQUOT} else f"io-{exc.errno}"
            print(f"source-helper DROP service={self.directory.name} reason={reason}", file=sys.stderr, flush=True)
            return False


class ServiceFollower(threading.Thread):
    def __init__(self, docker: DockerClient, root: Path, state_root: Path, service: str,
                 max_bytes: int, max_files: int, poll_seconds: float) -> None:
        super().__init__(name=f"source-{service}", daemon=True)
        self.docker = docker
        self.service = service
        self.writer = BoundedWriter(root, service, max_bytes, max_files)
        self.state = state_root / f"{service}.json"
        self.status = state_root / f"{service}.status.json"
        self.poll_seconds = poll_seconds

    def load_cursor(self, container_id: str) -> str:
        try:
            state = json.loads(self.state.read_text(encoding="utf-8"))
            if state.get("container_id") == container_id and isinstance(state.get("timestamp"), str):
                return state["timestamp"]
        except (FileNotFoundError, OSError, json.JSONDecodeError):
            pass
        return utc_now()

    def save_cursor(self, container_id: str, timestamp: str) -> None:
        atomic_json(self.state, {"container_id": container_id, "timestamp": timestamp})

    def save_status(self, status: str, container_id: str = "") -> None:
        atomic_json(
            self.status,
            {"service": self.service, "status": status, "container_id": container_id, "updated_at": utc_now()},
        )

    @staticmethod
    def split_timestamp(line: bytes) -> tuple[str, str] | None:
        decoded = line.decode("utf-8", errors="replace")
        timestamp, separator, message = decoded.partition(" ")
        if not separator or "T" not in timestamp or not timestamp.endswith("Z"):
            return None
        return timestamp, message.rstrip("\n")

    def follow_once(self, container: Container) -> None:
        since = self.load_cursor(container.container_id)
        process = self.docker.follow(container, since)
        assert process.stdout is not None and process.stderr is not None
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ, "stdout")
        selector.register(process.stderr, selectors.EVENT_READ, "stderr")
        print(
            f"source-helper ATTACHED service={self.service} id={container.container_id[:12]} "
            f"driver={container.driver} since={since}", file=sys.stderr, flush=True,
        )
        self.save_status("attached", container.container_id)
        try:
            while not STOP.is_set() and process.poll() is None:
                events = selector.select(timeout=self.poll_seconds)
                if not events:
                    current = self.docker.resolve(self.service)
                    if current.container_id != container.container_id:
                        print(
                            f"source-helper REPLACED service={self.service} old={container.container_id[:12]} "
                            f"new={current.container_id[:12]}", file=sys.stderr, flush=True,
                        )
                        self.save_status("replaced", current.container_id)
                        return
                    continue
                for key, _ in events:
                    line = key.fileobj.readline()
                    if not line:
                        continue
                    parsed = self.split_timestamp(line)
                    if parsed is None:
                        print(f"source-helper DROP service={self.service} reason=missing-timestamp", file=sys.stderr, flush=True)
                        continue
                    timestamp, message = parsed
                    if self.writer.write(timestamp, str(key.data), message):
                        self.save_cursor(container.container_id, timestamp)
        finally:
            selector.close()
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        if not STOP.is_set() and process.returncode not in {0, None}:
            try:
                current = self.docker.resolve(self.service)
                if current.container_id != container.container_id:
                    print(
                        f"source-helper REPLACED service={self.service} old={container.container_id[:12]} "
                        f"new={current.container_id[:12]}", file=sys.stderr, flush=True,
                    )
                    self.save_status("replaced", current.container_id)
                    return
            except (OSError, subprocess.SubprocessError, RuntimeError, json.JSONDecodeError):
                pass
            raise RuntimeError(f"docker logs exited with status {process.returncode}")

    def run(self) -> None:
        while not STOP.is_set():
            try:
                self.follow_once(self.docker.resolve(self.service))
            except (OSError, subprocess.SubprocessError, RuntimeError, json.JSONDecodeError) as exc:
                self.save_status("disconnected")
                print(f"source-helper DISCONNECTED service={self.service} reason={type(exc).__name__}", file=sys.stderr, flush=True)
            STOP.wait(self.poll_seconds)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--docker", default=os.environ.get("PARKIO_NR_DOCKER_BIN", "docker"))
    parser.add_argument("--project", default=os.environ.get("PARKIO_NR_COMPOSE_PROJECT", "parkio"))
    parser.add_argument("--output", type=Path, default=Path("/var/lib/parkio-nr-source/logs"))
    parser.add_argument("--state", type=Path, default=Path("/var/lib/parkio-nr-source/state"))
    parser.add_argument("--max-file-bytes", type=int, default=2 * 1024 * 1024)
    parser.add_argument("--max-files", type=int, default=3)
    parser.add_argument("--poll-seconds", type=float, default=2.0)
    parser.add_argument("--docker-timeout", type=int, default=10)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.max_file_bytes < 4096 or not 2 <= args.max_files <= 10 or args.poll_seconds <= 0:
        raise SystemExit("invalid source bounds")
    args.output.mkdir(parents=True, exist_ok=True, mode=0o750)
    args.state.mkdir(parents=True, exist_ok=True, mode=0o750)
    os.chmod(args.output, 0o750)
    os.chmod(args.state, 0o750)
    docker = DockerClient(args.docker, args.project, args.docker_timeout)
    followers = [
        ServiceFollower(docker, args.output, args.state, service, args.max_file_bytes, args.max_files, args.poll_seconds)
        for service in SERVICES
    ]
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda _signum, _frame: STOP.set())
    for follower in followers:
        follower.start()
    while not STOP.wait(0.5):
        if any(not follower.is_alive() for follower in followers):
            print("source-helper FATAL follower exited", file=sys.stderr, flush=True)
            STOP.set()
            return 1
    for follower in followers:
        follower.join(timeout=10)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
