"""Correlated exporter pause handshake.

A request file is not an acknowledgement. Drain is certified only when the
gateway writes an ack that matches the current requestId after in-flight
export work finishes. Stale acks from a prior request or JVM instance are
ignored. Coordinator startup must not unlink leftover control files.
"""
from __future__ import annotations

from pathlib import Path
import time
import uuid

try:
    from coordinator import CoordinationError  # type: ignore
except ImportError:
    from recovery_coordination.coordinator import CoordinationError


def parse_fields(text: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    for line in text.splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        fields[key.strip()] = value.strip()
    return fields


class ExportPauseHandshake:
    REQUEST = "request"
    ACK = "ack"

    def __init__(self, control_dir, wait_seconds=20):
        self.control_dir = Path(control_dir)
        self.request_path = self.control_dir / self.REQUEST
        self.ack_path = self.control_dir / self.ACK
        self.wait_seconds = wait_seconds

    def requested(self) -> bool:
        return self.request_path.is_file()

    def request_pause(self) -> str:
        self.control_dir.mkdir(parents=True, exist_ok=True)
        request_id = uuid.uuid4().hex
        issued = str(int(time.time() * 1000))
        tmp = self.control_dir / ".request.tmp"
        tmp.write_text(f"requestId={request_id}\nissuedAt={issued}\n", encoding="utf-8")
        tmp.replace(self.request_path)
        return request_id

    def write_ack(self, request_id: str, exporter_instance_id: str) -> None:
        if not request_id:
            raise CoordinationError("refusing to acknowledge an empty pause request")
        self.control_dir.mkdir(parents=True, exist_ok=True)
        tmp = self.control_dir / ".ack.tmp"
        now = str(int(time.time() * 1000))
        tmp.write_text(
            f"requestId={request_id}\nexporterInstanceId={exporter_instance_id}\n"
            f"acknowledgedAt={now}\n",
            encoding="utf-8",
        )
        tmp.replace(self.ack_path)

    def wait_acknowledged(self, request_id: str, timeout=None) -> dict[str, str]:
        deadline = time.monotonic() + (self.wait_seconds if timeout is None else timeout)
        last = "missing acknowledgement"
        while time.monotonic() < deadline:
            try:
                text = self.ack_path.read_text(encoding="utf-8")
            except FileNotFoundError:
                time.sleep(0.05)
                continue
            except OSError as exc:
                raise CoordinationError("unreadable export pause acknowledgement") from exc
            fields = parse_fields(text)
            if fields.get("requestId") == request_id:
                return fields
            last = "stale acknowledgement"
            time.sleep(0.05)
        raise CoordinationError(f"export pause handshake failed: {last}")

    def resume(self) -> None:
        self.request_path.unlink(missing_ok=True)
        self.ack_path.unlink(missing_ok=True)
