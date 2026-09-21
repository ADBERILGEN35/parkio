#!/usr/bin/env python3
"""Tiny state-file Docker CLI double used only by synthetic source acceptance."""

from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path


STATE = Path(os.environ["PARKIO_FAKE_DOCKER_STATE"])


def load() -> dict:
    return json.loads(STATE.read_text(encoding="utf-8"))


def service_for_id(state: dict, container_id: str) -> str | None:
    for service, item in state["containers"].items():
        if item["id"].startswith(container_id):
            return service
    return None


def do_ps(args: list[str]) -> int:
    state = load()
    project = None
    service = None
    for index, value in enumerate(args):
        if value == "--filter" and index + 1 < len(args):
            item = args[index + 1]
            if item.startswith("label=com.docker.compose.project="):
                project = item.rsplit("=", 1)[1]
            if item.startswith("label=com.docker.compose.service="):
                service = item.rsplit("=", 1)[1]
    if project == state["project"] and service in state["containers"]:
        item = state["containers"][service]
        if item.get("running", True):
            print(item["id"][:12])
    return 0


def do_inspect(args: list[str]) -> int:
    container_id = args[-1]
    state = load()
    service = service_for_id(state, container_id)
    if service is None:
        print("no such container", file=sys.stderr)
        return 1
    item = state["containers"][service]
    if "--format" in args:
        template = args[args.index("--format") + 1]
        values = {
            "{{.Id}}": item["id"],
            "{{.State.Status}}": "running" if item.get("running", True) else "exited",
            '{{index .Config.Labels "com.docker.compose.project"}}': state["project"],
            '{{index .Config.Labels "com.docker.compose.service"}}': service,
            "{{.HostConfig.LogConfig.Type}}": item.get("driver", "json-file"),
            '{{index .HostConfig.LogConfig.Config "max-size"}}': item.get("max_size", "10m"),
            '{{index .HostConfig.LogConfig.Config "max-file"}}': item.get("max_file", "5"),
        }
        if template not in values:
            print(f"unsupported inspect template: {template}", file=sys.stderr)
            return 2
        print(values[template])
        return 0
    print(json.dumps([{
        "Id": item["id"],
        "Name": f"/{state['project']}-{service}-1",
        "State": {"Status": "running" if item.get("running", True) else "exited"},
        "Config": {"Labels": {
            "com.docker.compose.project": state["project"],
            "com.docker.compose.service": service,
        }},
        "HostConfig": {"LogConfig": {"Type": item.get("driver", "json-file")}},
    }]))
    return 0


def do_logs(args: list[str]) -> int:
    container_id = args[-1]
    since = args[args.index("--since") + 1]
    if "--follow" not in args and "--tail" in args and args[args.index("--tail") + 1] == "0":
        state = load()
        return 0 if state.get("connected", True) and service_for_id(state, container_id) else 1
    emitted = 0
    while True:
        state = load()
        service = service_for_id(state, container_id)
        if not state.get("connected", True) or service is None:
            print("synthetic Docker endpoint disconnected", file=sys.stderr, flush=True)
            return 1
        events = [event for event in state.get("events", []) if event["container_id"] == container_id]
        while emitted < len(events):
            event = events[emitted]
            emitted += 1
            if event["timestamp"] < since:
                continue
            target = sys.stderr if event.get("stream") == "stderr" else sys.stdout
            print(f"{event['timestamp']} {event['message']}", file=target, flush=True)
        time.sleep(0.05)


def main() -> int:
    args = sys.argv[1:]
    if not args:
        return 2
    if args[0] == "ps":
        return do_ps(args[1:])
    if args[0] == "inspect":
        return do_inspect(args[1:])
    if args[0] == "logs":
        return do_logs(args[1:])
    print(f"unsupported fake docker command: {args[0]}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
