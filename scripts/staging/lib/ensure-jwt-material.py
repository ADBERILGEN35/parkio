#!/usr/bin/env python3
"""WP-06.2B ? ensure PARKIO_JWT_PRIVATE_KEY_PEM in an env file without printing secrets."""
from __future__ import annotations

import pathlib
import subprocess
import sys


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: ensure-jwt-material.py <env-file>", file=sys.stderr)
        return 2
    env_file = pathlib.Path(sys.argv[1])
    lines = env_file.read_text(encoding="utf-8").splitlines()
    current = ""
    for line in lines:
        if line.startswith("PARKIO_JWT_PRIVATE_KEY_PEM="):
            current = line.split("=", 1)[1].strip().strip('"').strip("'")
            break
    if current:
        print("jwt_signing_material=present")
        return 0

    pem = ""
    try:
        pem = subprocess.check_output(
            ["docker", "exec", "parkio-auth-service-1", "printenv", "PARKIO_JWT_PRIVATE_KEY_PEM"],
            text=True,
            stderr=subprocess.DEVNULL,
        )
    except Exception:
        pem = ""

    if not pem.strip():
        key_path = pathlib.Path("/tmp/parkio_wp062b_jwt.pem")
        subprocess.check_call(
            [
                "openssl",
                "genpkey",
                "-algorithm",
                "RSA",
                "-pkeyopt",
                "rsa_keygen_bits:2048",
                "-out",
                str(key_path),
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        pem = key_path.read_text(encoding="utf-8")
        key_path.unlink(missing_ok=True)

    if not pem.strip():
        print("ERROR: PARKIO_JWT_PRIVATE_KEY_PEM unavailable for isolated stack", file=sys.stderr)
        return 1

    escaped = pem.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
    out: list[str] = []
    replaced = False
    for line in lines:
        if line.startswith("PARKIO_JWT_PRIVATE_KEY_PEM="):
            out.append(f'PARKIO_JWT_PRIVATE_KEY_PEM="{escaped}"')
            replaced = True
        else:
            out.append(line)
    if not replaced:
        out.append(f'PARKIO_JWT_PRIVATE_KEY_PEM="{escaped}"')
    if not any(l.startswith("PARKIO_JWT_KEY_ID=") for l in out):
        out.append("PARKIO_JWT_KEY_ID=parkio-auth-rs256-wp062b")
    env_file.write_text("\n".join(out) + "\n", encoding="utf-8")
    print("jwt_signing_material=configured")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
