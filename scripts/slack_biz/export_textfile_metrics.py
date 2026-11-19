#!/usr/bin/env python3
"""Export slack_biz JSON metrics to a Prometheus textfile for node-exporter.

Writes parkio_slack_biz_delivery_unknown, parkio_slack_biz_dlt_rows,
parkio_slack_biz_relay_db_open, parkio_slack_biz_worker_heartbeat_age_seconds,
parkio_slack_biz_delivery_enabled and parkio_slack_biz_textfile_last_success_unixtime
so alerts can scrape real series and detect a stopped or crashed relay or a
missing/stale exporter. Does not invent counters from absent data.
"""
from __future__ import annotations

import argparse
import os
import sys
import tempfile
import time
from pathlib import Path

# Allow `python3 scripts/slack_biz/export_textfile_metrics.py` from repo root.
_ROOT = Path(__file__).resolve().parent
if str(_ROOT.parent) not in sys.path:
    sys.path.insert(0, str(_ROOT.parent))

from slack_biz.config import load_config  # noqa: E402
from slack_biz.store import DeliveryStore  # noqa: E402


def render(store: DeliveryStore, when: float | None = None, *, delivery_enabled: bool) -> str:
    now = time.time() if when is None else when
    unknown = store.count_by_status("delivery_unknown")
    with store._lock:  # noqa: SLF001 — intentional for a bounded COUNT and a one-row read
        row = store._conn.execute("SELECT COUNT(*) AS c FROM dlt").fetchone()
        dlt_rows = int(row["c"])
        lock = store._conn.execute("SELECT heartbeat_at FROM worker_lock WHERE id=1").fetchone()
    # The worker refreshes heartbeat_at on every poll while delivery is enabled and deletes the row
    # when it stops cleanly. A crash or SIGKILL leaves the last heartbeat behind, so this age keeps
    # growing where relay_db_open stays 1 (#544). Without a row no worker runs: the age is unbounded.
    if lock is None:
        heartbeat_age = "+Inf"
    else:
        heartbeat_age = str(max(0, int(now - float(lock["heartbeat_at"]))))
    stamp = int(now)
    lines = [
        "# HELP parkio_slack_biz_delivery_unknown Slack biz deliveries in delivery_unknown",
        "# TYPE parkio_slack_biz_delivery_unknown gauge",
        f"parkio_slack_biz_delivery_unknown {unknown}",
        "# HELP parkio_slack_biz_dlt_rows Slack biz dead-letter table depth",
        "# TYPE parkio_slack_biz_dlt_rows gauge",
        f"parkio_slack_biz_dlt_rows {dlt_rows}",
    ]
    if store.relay_db_open is not None:  # known for read-only opens, the exporter's case
        lines += [
            "# HELP parkio_slack_biz_relay_db_open 1 while the delivery DB WAL side files exist: a relay holds it open, or the last one crashed (so 1 does not prove a running relay)",
            "# TYPE parkio_slack_biz_relay_db_open gauge",
            f"parkio_slack_biz_relay_db_open {int(store.relay_db_open)}",
        ]
    lines += [
        "# HELP parkio_slack_biz_worker_heartbeat_age_seconds Seconds since the delivery worker last refreshed its lock heartbeat; keeps growing after a crash, +Inf when no worker holds the lock",
        "# TYPE parkio_slack_biz_worker_heartbeat_age_seconds gauge",
        f"parkio_slack_biz_worker_heartbeat_age_seconds {heartbeat_age}",
        "# HELP parkio_slack_biz_delivery_enabled 1 when PARKIO_SLACK_BIZ_ENABLED is on; the worker heartbeats only while delivery is enabled",
        "# TYPE parkio_slack_biz_delivery_enabled gauge",
        f"parkio_slack_biz_delivery_enabled {int(delivery_enabled)}",
    ]
    lines += [
        "# HELP parkio_slack_biz_textfile_last_success_unixtime Unix time of last successful textfile export",
        "# TYPE parkio_slack_biz_textfile_last_success_unixtime gauge",
        f"parkio_slack_biz_textfile_last_success_unixtime {stamp}",
        "",
    ]
    return "\n".join(lines)


def atomic_write(path: Path, body: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w",
        dir=str(path.parent),
        delete=False,
        prefix=".parkio_slack_biz.",
        suffix=".tmp",
        encoding="utf-8",
    ) as tmp:
        tmp.write(body)
        tmp.flush()
        os.fchmod(tmp.fileno(), 0o644)
        os.fsync(tmp.fileno())
        tmp_path = Path(tmp.name)
    tmp_path.replace(path)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        default=os.environ.get(
            "PARKIO_SLACK_BIZ_TEXTFILE",
            "docker/prometheus/textfile/parkio_slack_biz.prom",
        ),
        help="Destination .prom path (atomic replace)",
    )
    args = parser.parse_args(argv)
    cfg = load_config()
    store = DeliveryStore(
        cfg.db_path,
        dedup_retention_hours=cfg.dedup_retention_hours,
        lease_seconds=cfg.lease_seconds,
        worker_stale_seconds=cfg.worker_stale_seconds,
        readonly=True,
    )
    try:
        # The export unit loads the non-secret conf only, so it sees the switch but not the webhook. A
        # worker enabled without a webhook never heartbeats either, and nothing can be delivered then.
        body = render(store, delivery_enabled=cfg.enabled)
    finally:
        # Close before writing: a reader still open when the last relay closes keeps that close
        # from checkpointing, and the side files (relay_db_open 1) would then outlive the relay.
        store.close()
    out = Path(args.output)
    atomic_write(out, body)
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
