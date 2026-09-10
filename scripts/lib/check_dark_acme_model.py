#!/usr/bin/env python3
"""Merged-Compose-model half of the PROD-DEPLOY-01A-R4 dark ACME guard.

Reads a `docker compose config --format json` model and proves two things about
the services the dark deploy will actually start:

  1. none of them is an ACME client aimed at a real Parkio hostname, and
  2. none of them publishes a port beyond loopback.

Config inspection only: no DNS, no sockets, no ACME directory contact.

Env:
  PARKIO_START_SET     space-separated services the deploy will start
  PARKIO_PUBLIC_HOSTS  space-separated real production hostnames
"""

from __future__ import annotations

import json
import os
import sys


# An image whose job is to terminate public TLS. Matched loosely on the repo
# component so a tag or registry change cannot slip past.
ACME_IMAGE_MARKERS = ("caddy", "traefik", "certbot", "lego", "nginx-proxy/acme")


def load_services(path: str) -> dict:
    model = json.load(open(path))
    services = model.get("services", {})
    if isinstance(services, list):  # older schema
        services = {s["name"]: s for s in services}
    return services


def main() -> int:
    services = load_services(sys.argv[1])
    start_set = set(os.environ.get("PARKIO_START_SET", "").split())
    public_hosts = [h for h in os.environ.get("PARKIO_PUBLIC_HOSTS", "").split() if h]
    errors: list[str] = []

    if not start_set:
        errors.append("empty start set: the deploy would start every service, including the ACME edge")

    for name in sorted(start_set):
        cfg = services.get(name)
        if cfg is None:
            # Not in the merged model (e.g. profile-gated); it cannot start.
            continue

        image = str(cfg.get("image") or "")
        if any(marker in image.lower() for marker in ACME_IMAGE_MARKERS):
            errors.append(f"{name}: image '{image}' is a public-TLS edge and is in the dark start set")

        # A started service must not carry the real hostnames into its
        # environment, which is how the Caddyfile learns what to request.
        env = cfg.get("environment") or {}
        if isinstance(env, list):
            env = dict(
                (item.split("=", 1) + [""])[:2] for item in env if isinstance(item, str)
            )
        for key, value in env.items():
            for host in public_hosts:
                if value and host == str(value).strip():
                    errors.append(
                        f"{name}: environment {key} is the production hostname {host}; "
                        "a started service must not be able to request a certificate for it"
                    )

        for port in cfg.get("ports") or []:
            if not isinstance(port, dict):
                continue
            host_ip = port.get("host_ip") or port.get("hostIp") or ""
            if host_ip != "127.0.0.1":
                errors.append(
                    f"{name}: publishes {port.get('published')}/{port.get('protocol')} on "
                    f"'{host_ip or '0.0.0.0'}'; the dark runtime is loopback-only"
                )

    for err in errors:
        print(f"  {err}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
