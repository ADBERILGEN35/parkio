#!/usr/bin/env python3
"""Write a minimal disposable web compose file for the U13 rollback rehearsal."""
from __future__ import annotations

import os
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: write-rehearsal-web-compose.py OUT.yml", file=sys.stderr)
        return 2
    ref = os.environ.get("WEB_REF", "")
    if not ref.startswith("ghcr.io/") or "@sha256:" not in ref:
        print("WEB_REF must be ghcr.io/...@sha256:...", file=sys.stderr)
        return 3
    Path(sys.argv[1]).write_text(
        "name: parkio-rollback-rehearsal\n"
        "services:\n"
        "  web:\n"
        f"    image: {ref}\n"
        "    ports:\n"
        '      - "8080:80"\n'
        "    environment:\n"
        "      PARKIO_WEB_API_BASE_URL: https://api.example.invalid\n"
        "      PARKIO_WEB_APP_ORIGIN: https://app.example.invalid\n"
        "      PARKIO_WEB_CSP_CONNECT_SRC: \"'self' https://api.example.invalid\"\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
