#!/usr/bin/env python3
"""Export slack_biz JSON metrics to a Prometheus textfile for node-exporter.

Writes parkio_slack_biz_delivery_unknown, parkio_slack_biz_dlt_rows,
parkio_slack_biz_relay_db_open and parkio_slack_biz_textfile_last_success_unixtime
so alerts can scrape real series and detect a stopped relay or a missing/stale
exporter. Does not invent counters from absent data.
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


def render(store: DeliveryStore, when: int | None = None) -> str:
    unknown = store.count_by_status("delivery_unknown")
    with store._lock:  # noqa: SLF001 — intentional for a bounded COUNT
        row = store._conn.execute("SELECT COUNT(*) AS c FROM dlt").fetchone()
        dlt_rows = int(row["c"])
    stamp = int(time.time() if when is None else when)
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
            "# HELP parkio_slack_biz_relay_db_open 1 while the delivery DB WAL side files exist: a relay holds it open, or the last one crashed",
            "# TYPE parkio_slack_biz_relay_db_open gauge",
            f"parkio_slack_biz_relay_db_open {int(store.relay_db_open)}",
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
        body = render(store)
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
