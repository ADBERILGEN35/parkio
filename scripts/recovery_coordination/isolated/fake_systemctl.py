#!/usr/bin/env python3
"""Disposable systemctl for isolated writer-control tests.

Never talks to the host systemd. State lives under PARKIO_FAKE_SYSTEMD_STATE.
Stopping parkio-nr-log-continuous.service also stops the source helper, matching
the documented ExecStop -> stop_pilot.sh behavior.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import sys

UNITS = {
    "parkio-slack-biz-worker.service": {"type": "simple", "restart": "on-failure"},
    "parkio-slack-biz-waitlist-consumer.service": {"type": "simple", "restart": "on-failure"},
    "parkio-nr-log-source.service": {"type": "exec", "restart": "on-failure"},
    "parkio-nr-log-continuous.service": {"type": "oneshot", "restart": "no", "remain": True},
    "parkio-nr-log-continuous-guard.timer": {"type": "timer", "restart": "no"},
    "parkio-nr-log-continuous-guard.service": {"type": "oneshot", "restart": "no"},
    "nr-budget-gate.service": {"type": "simple", "restart": "no"},
}

CONTINUOUS = "parkio-nr-log-continuous.service"
SOURCE = "parkio-nr-log-source.service"
TIMER = "parkio-nr-log-continuous-guard.timer"
GATE = "nr-budget-gate.service"


def state_path() -> Path:
    root = os.environ.get("PARKIO_FAKE_SYSTEMD_STATE")
    if not root:
        raise SystemExit("PARKIO_FAKE_SYSTEMD_STATE is required")
    return Path(root) / "units.json"


def load() -> dict:
    path = state_path()
    if not path.is_file():
        data = {
            name: {"active": "active" if name != "parkio-nr-log-continuous-guard.service" else "inactive"}
            for name in UNITS
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data), encoding="utf-8")
        return data
    return json.loads(path.read_text(encoding="utf-8"))


def save(data: dict) -> None:
    path = state_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")


def log_command(args: list[str]) -> None:
    root = os.environ.get("PARKIO_FAKE_SYSTEMD_STATE")
    if not root:
        return
    path = Path(root) / "commands.log"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(" ".join(args) + "\n")


def require(name: str) -> None:
    if name not in UNITS:
        raise SystemExit(f"unknown unit {name}")


def is_active(data: dict, name: str) -> bool:
    return data.get(name, {}).get("active") == "active"


def main(argv: list[str]) -> int:
    if not argv:
        return 2
    data = load()
    log_command(argv)
    if argv[0] == "is-active":
        name = argv[1]
        require(name)
        print("active" if is_active(data, name) else "inactive")
        return 0 if is_active(data, name) else 3
    if argv[0] == "show":
        name = argv[-1]
        require(name)
        spec = UNITS[name]
        print(f"Id={name}")
        print(f"ActiveState={'active' if is_active(data, name) else 'inactive'}")
        print(f"Type={spec['type']}")
        print(f"Restart={spec['restart']}")
        return 0
    if argv[0] == "stop":
        name = argv[1]
        require(name)
        data.setdefault(name, {})["active"] = "inactive"
        if name == CONTINUOUS:
            data.setdefault(SOURCE, {})["active"] = "inactive"
            data.setdefault(GATE, {})["active"] = "inactive"
        save(data)
        return 0
    if argv[0] == "start":
        name = argv[1]
        require(name)
        data.setdefault(name, {})["active"] = "active"
        if name == CONTINUOUS:
            data.setdefault(SOURCE, {})["active"] = "active"
            data.setdefault(GATE, {})["active"] = "active"
        save(data)
        return 0
    if argv[0] == "list-unit-files":
        for name in UNITS:
            print(f"{name} enabled")
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
