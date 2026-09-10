#!/usr/bin/env python3
"""Fail if deploy evidence contains values sourced from a production env file."""

from __future__ import annotations

import argparse
import codecs
import os
from pathlib import Path


SENSITIVE_KEY_PARTS = (
    "PASSWORD",
    "PASSPHRASE",
    "PRIVATE_KEY",
    "SECRET",
    "TOKEN",
    "WEBHOOK",
    "API_KEY",
    "MAPTILER_KEY",
)

SENSITIVE_EXACT_KEYS = {
    "PARKIO_ACME_EMAIL",
    "KAFKA_CLUSTER_ID",
}

FORBIDDEN_ARTIFACT_NAMES = {"compose-config.rendered.yml", "compose-config.rendered.yaml"}


def dotenv_entries(path: Path) -> dict[str, str]:
    entries: dict[str, str] = {}
    for line in path.read_text().splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
            value = value[1:-1]
        try:
            value = codecs.decode(value, "unicode_escape")
        except UnicodeDecodeError:
            pass
        entries[key] = value
    return entries


def evidence_files(paths: list[Path]) -> list[Path]:
    files: list[Path] = []
    for path in paths:
        if path.is_file():
            files.append(path)
        elif path.is_dir():
            files.extend(candidate for candidate in path.rglob("*") if candidate.is_file())
        else:
            raise FileNotFoundError(f"evidence path does not exist: {path}")
    return files


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", required=True, type=Path)
    parser.add_argument("paths", nargs="+", type=Path)
    args = parser.parse_args()

    if not args.env_file.is_file():
        print(f"ERROR: env file not found: {args.env_file}")
        return 2

    sensitive = {
        key: value.encode()
        for key, value in dotenv_entries(args.env_file).items()
        if value
        and (
            key in SENSITIVE_EXACT_KEYS
            or any(part in key.upper() for part in SENSITIVE_KEY_PARTS)
        )
    }
    files = evidence_files(args.paths)
    failures: list[tuple[Path, str]] = []

    for path in files:
        if path.name in FORBIDDEN_ARTIFACT_NAMES:
            failures.append((path, "forbidden resolved Compose artifact"))
            continue
        content = path.read_bytes()
        for key, value in sensitive.items():
            if len(value) >= 8 and value in content:
                failures.append((path, f"contains value from {key}"))

    if failures:
        for path, reason in failures:
            print(f"FAIL {path}: {reason}")
        return 1

    print(f"Secret-safe artifact scan passed: files={len(files)} sensitiveValues={len(sensitive)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
