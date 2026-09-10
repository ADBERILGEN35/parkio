#!/usr/bin/env python3
"""Merged-Compose-model checks for invite-production public cutover (03E-A1).

Proves the effective start set for acme=true public cutover:
  1. caddy is started and is the only wildcard public listener on 80/443,
  2. gateway-service has no host-published ports,
  3. no other service publishes beyond loopback.
"""

from __future__ import annotations

import json
import os
import sys


def load_services(path: str) -> dict:
    model = json.load(open(path))
    services = model.get("services", {})
    if isinstance(services, list):
        services = {s["name"]: s for s in services}
    return services


def published_ports(cfg: dict) -> list[dict]:
    ports = []
    for port in cfg.get("ports") or []:
        if not isinstance(port, dict):
            continue
        ports.append(
            {
                "host_ip": port.get("host_ip") or port.get("hostIp") or "",
                "published": str(port.get("published") or ""),
                "protocol": str(port.get("protocol") or "tcp"),
            }
        )
    return ports


def main() -> int:
    services = load_services(sys.argv[1])
    start_set = set(os.environ.get("PARKIO_START_SET", "").split())
    errors: list[str] = []

    if not start_set:
        errors.append("empty start set: cutover deploy would start every service")

    if "caddy" not in start_set:
        errors.append("caddy must be in the public-cutover runtime start set")

    caddy = services.get("caddy")
    if caddy is None:
        errors.append("caddy service is missing from the merged compose model")
    else:
        caddy_ports = published_ports(caddy)
        published_tcp = sorted(
            p["published"]
            for p in caddy_ports
            if p["protocol"] in ("tcp", "") and p["host_ip"] in ("", "0.0.0.0")
        )
        if "80" not in published_tcp or "443" not in published_tcp:
            errors.append(
                f"caddy must publish public 80/tcp and 443/tcp, got {caddy_ports!r}"
            )

    gateway = services.get("gateway-service")
    if gateway is not None:
        gateway_ports = published_ports(gateway)
        if gateway_ports:
            errors.append(
                f"gateway-service must not publish host ports during cutover, got {gateway_ports!r}"
            )

    forbidden_publishers = []
    for name in sorted(start_set):
        if name in {"caddy", "gateway-service"}:
            continue
        cfg = services.get(name)
        if cfg is None:
            continue
        for port in published_ports(cfg):
            host_ip = port["host_ip"]
            if host_ip != "127.0.0.1" and port["published"]:
                forbidden_publishers.append(f"{name}:{port['published']}")

    if forbidden_publishers:
        errors.append(
            "non-loopback host publishes in cutover start set: " + ", ".join(forbidden_publishers)
        )

    for err in errors:
        print(f"  {err}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
