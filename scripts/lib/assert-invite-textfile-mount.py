#!/usr/bin/env python3
"""Exact invite overlay addition and merged node-exporter mount contract."""
import argparse
import json
from pathlib import Path

import yaml

HOST = "/var/lib/parkio/observability/textfile"
TARGET = "/textfile-collector"
MOUNT = f"{HOST}:{TARGET}:ro"


class ComposeLoader(yaml.SafeLoader):
    pass


for tag in ("!override", "!reset"):
    ComposeLoader.add_constructor(tag, lambda loader, node: loader.construct_sequence(node))


def validate_overlay(model, public=False):
    services = model["services"]
    expected = {"auth-service", "gateway-service", "parking-service", "tempo", "node-exporter"}
    if public:
        expected.add("caddy")
    if set(services) != expected:
        raise ValueError("unexpected invite overlay service")
    if services["node-exporter"] != {"volumes": [MOUNT]}:
        raise ValueError("node-exporter overlay must contain only the exact read-only textfile mount")
    for name, service in services.items():
        if name != "node-exporter" and "volumes" in service:
            raise ValueError("no additional mounts are allowed in the invite overlay")


def validate_model(model):
    services = model["services"]
    exporter = services["node-exporter"]
    expected = {("/proc", "/host/proc"), ("/sys", "/host/sys"),
                ("/", "/host/root"), (HOST, TARGET)}
    mounts = exporter.get("volumes", [])
    identities = {(m.get("source"), m.get("target")) for m in mounts}
    if len(mounts) != 4 or identities != expected:
        raise ValueError("unexpected merged node-exporter mount")
    for mount in mounts:
        if mount.get("type") != "bind" or mount.get("read_only") is not True:
            raise ValueError("node-exporter mounts must be read-only binds")
    flags = [flag for flag in exporter.get("command", [])
             if flag.startswith("--collector.textfile.directory")]
    if flags != [f"--collector.textfile.directory={TARGET}"]:
        raise ValueError("textfile collector must use the approved container path")
    for name, service in services.items():
        if name == "node-exporter":
            continue
        for mount in service.get("volumes", []):
            if mount.get("source") == HOST or mount.get("target") == TARGET:
                raise ValueError("textfile mount attached to another service")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("overlay", type=Path)
    parser.add_argument("--public", action="store_true")
    parser.add_argument("--model", type=Path)
    args = parser.parse_args()
    validate_overlay(yaml.load(args.overlay.read_text(), Loader=ComposeLoader), args.public)
    if args.model:
        validate_model(json.loads(args.model.read_text()))
    print("PASS: exact invite textfile mount contract")
