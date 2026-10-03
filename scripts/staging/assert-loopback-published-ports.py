#!/usr/bin/env python3
"""Fail when a rendered Compose model publishes a port on a non-loopback address.

Usage:
  docker compose ... config --format json | python3 scripts/staging/assert-loopback-published-ports.py

Docker's port publishing bypasses host firewall rules, so a stack that holds restored data
(the WP-06.2B verification overlay) must publish only on 127.0.0.1 or ::1 (CL-F29.1).
A mapping without host_ip binds every interface and is rejected.
"""
import json
import sys

LOOPBACK = {"127.0.0.1", "::1"}


def non_loopback_ports(model):
    findings = []
    for name, service in sorted((model.get("services") or {}).items()):
        for port in service.get("ports") or []:
            host_ip = port.get("host_ip") or ""
            if host_ip not in LOOPBACK:
                findings.append(
                    f"{name}: {host_ip or '0.0.0.0 (all interfaces)'}:{port.get('published')}"
                    f" -> {port.get('target')}/{port.get('protocol', 'tcp')}")
    return findings


def published_count(model):
    return sum(len(s.get("ports") or []) for s in (model.get("services") or {}).values())


def main():
    model = json.load(sys.stdin)
    findings = non_loopback_ports(model)
    if findings:
        print("FAIL: non-loopback published ports:", file=sys.stderr)
        for finding in findings:
            print(f"  {finding}", file=sys.stderr)
        return 1
    print(f"OK: {published_count(model)} published ports, all on loopback")
    return 0


if __name__ == "__main__":
    sys.exit(main())
