#!/usr/bin/env python3
"""Publish only the existing non-secret backup gauges, atomically and durably."""
import os
from pathlib import Path
import re
import sys
import tempfile


def publish(directory, scope, success, stamp, failed, objects, offsite, encryption,
            size, production, bucket):
    if scope not in ("hosted-beta", "azure-hosted-beta", "managed-postgres", "invite-production"):
        raise ValueError("unsupported backup metric scope")
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,63}", bucket):
        raise ValueError("invalid backup metric bucket label")
    values = [success, stamp, failed, objects, offsite, encryption, size, production]
    if any(not re.fullmatch(r"[0-9]+", str(value)) for value in values):
        raise ValueError("backup metric values must be non-negative integers")
    if any(int(value) not in (0, 1) for value in (success, offsite, encryption, production)):
        raise ValueError("backup status metrics must be boolean integers")
    entries = [
        ("last_success", success, "1 when the last hosted-beta backup succeeded.", ""),
        ("last_timestamp_seconds", stamp, "Unix epoch of the last completed backup attempt.", ""),
        ("databases_failed", failed, "Number of database dumps that failed in the last run.", ""),
        ("minio_objects", objects, "Object count in the mirrored MinIO bucket when known.", f',bucket="{bucket}"'),
        ("offsite_last_success", offsite, "1 when the last offsite upload succeeded.", ""),
        ("encryption_enabled", encryption, "1 when DB dumps were encrypted.", ""),
        ("last_bytes", size, "Approximate local stamp size in bytes.", ""),
        ("production_mode", production, "1 when BACKUP_PRODUCTION_MODE was set for the last run.", ""),
    ]
    content = "".join(f"# HELP parkio_backup_{name} {help_text}\n"
                      f"# TYPE parkio_backup_{name} gauge\n"
                      f'parkio_backup_{name}{{scope="{scope}"{labels}}} {value}\n'
                      for name, value, help_text, labels in entries)
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True, mode=0o755)
    # The backup unit has UMask=0077; the non-secret collector leaf must still
    # be traversable by node-exporter's unprivileged UID through its RO bind.
    directory.chmod(0o755)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=directory,
                                         prefix=".parkio_backup.", suffix=".tmp", delete=False) as output:
            temporary = output.name
            output.write(content)
            output.flush()
            os.fchmod(output.fileno(), 0o644)
            os.fsync(output.fileno())
        os.replace(temporary, directory / "parkio_backup.prom")
        temporary = None
        descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        if temporary is not None:
            os.unlink(temporary)


def publish_failure(directory, started):
    """Cover early wrapper failures without replacing this run's detailed failure."""
    import time
    previous = Path(directory) / "parkio_backup.prom"
    if previous.is_file() and previous.stat().st_mtime >= float(started):
        if 'parkio_backup_last_success{scope="invite-production"} 0\n' in previous.read_text():
            return
    publish(directory, "invite-production", 0, int(time.time()), 0, 0, 0, 0, 0, 1, "parkio-media")


if __name__ == "__main__":
    try:
        if len(sys.argv) == 4 and sys.argv[1] == "--failure":
            publish_failure(sys.argv[2], sys.argv[3])
        elif len(sys.argv) == 12:
            publish(*sys.argv[1:])
        else:
            raise ValueError("expected directory, scope and nine non-secret metric arguments")
    except (ValueError, OSError):
        # Never echo arbitrary argv, filesystem errors, or environment secrets.
        print("ERROR: backup metrics publication failed", file=sys.stderr)
        raise SystemExit(1)
