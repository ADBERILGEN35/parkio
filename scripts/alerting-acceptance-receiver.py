#!/usr/bin/env python3
"""Isolated alerting-acceptance metrics source and webhook catcher.

Modes:
  metrics  — controllable Prometheus gauges on :8081
  probe    — always-up /metrics on :8082 (parking-service scrape target)
  webhook  — Alertmanager webhook catcher on :8080

Never logs Authorization headers, webhook URLs, tokens, or passwords.
"""

from __future__ import annotations

import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

STATE_LOCK = threading.Lock()
STATE: dict[str, Any] = {
    "synthetic": 0,
    "backup_success": 1,
    "backup_ts": int(time.time()),
    "production_mode": 0,
    "offsite_success": 1,
    "encryption": 1,
    "probe_redis": 1,
    "probe_minio": 1,
    "probe_postgres_auth": 1,
    "kafka_brokers": 1,
    "disk_avail": 80_000_000_000,
    "disk_size": 100_000_000_000,
}
RECEIPTS: list[dict[str, Any]] = []
RECEIPTS_DIR = Path(os.environ.get("PARKIO_ALERT_ACCEPT_RECEIPTS_DIR", "/receipts"))


def _utcnow() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _metrics_body() -> bytes:
    with STATE_LOCK:
        s = dict(STATE)
    scope = "hosted-beta"
    lines = [
        "# HELP parkio_alerting_acceptance_test 1 arms the synthetic acceptance alert.",
        "# TYPE parkio_alerting_acceptance_test gauge",
        f"parkio_alerting_acceptance_test {s['synthetic']}",
        "# HELP parkio_backup_last_success 1 when the last backup succeeded.",
        "# TYPE parkio_backup_last_success gauge",
        f'parkio_backup_last_success{{scope="{scope}"}} {s["backup_success"]}',
        "# HELP parkio_backup_last_timestamp_seconds Unix epoch of the last backup attempt.",
        "# TYPE parkio_backup_last_timestamp_seconds gauge",
        f'parkio_backup_last_timestamp_seconds{{scope="{scope}"}} {s["backup_ts"]}',
        "# HELP parkio_backup_offsite_last_success 1 when the last offsite upload succeeded.",
        "# TYPE parkio_backup_offsite_last_success gauge",
        f'parkio_backup_offsite_last_success{{scope="{scope}"}} {s["offsite_success"]}',
        "# HELP parkio_backup_encryption_enabled 1 when DB dumps were encrypted.",
        "# TYPE parkio_backup_encryption_enabled gauge",
        f'parkio_backup_encryption_enabled{{scope="{scope}"}} {s["encryption"]}',
        "# HELP parkio_backup_production_mode 1 when BACKUP_PRODUCTION_MODE was set.",
        "# TYPE parkio_backup_production_mode gauge",
        f'parkio_backup_production_mode{{scope="{scope}"}} {s["production_mode"]}',
        "# HELP parkio_backup_last_bytes Approximate local stamp size in bytes.",
        "# TYPE parkio_backup_last_bytes gauge",
        f'parkio_backup_last_bytes{{scope="{scope}"}} 1',
        "# HELP probe_success Blackbox-style probe result (isolated fixture).",
        "# TYPE probe_success gauge",
        f'probe_success{{component="redis"}} {s["probe_redis"]}',
        f'probe_success{{component="minio"}} {s["probe_minio"]}',
        f'probe_success{{component="postgres-auth"}} {s["probe_postgres_auth"]}',
        "# HELP kafka_brokers Brokers visible to kafka-exporter (isolated fixture).",
        "# TYPE kafka_brokers gauge",
        f"kafka_brokers {s['kafka_brokers']}",
        "# HELP node_filesystem_avail_bytes Isolated disk fixture.",
        "# TYPE node_filesystem_avail_bytes gauge",
        f'node_filesystem_avail_bytes{{fstype="ext4",mountpoint="/",instance="isolated"}} {s["disk_avail"]}',
        "# HELP node_filesystem_size_bytes Isolated disk fixture.",
        "# TYPE node_filesystem_size_bytes gauge",
        f'node_filesystem_size_bytes{{fstype="ext4",mountpoint="/",instance="isolated"}} {s["disk_size"]}',
        "",
    ]
    return "\n".join(lines).encode("utf-8")


def _sanitize_alert(alert: dict[str, Any]) -> dict[str, Any]:
    labels = alert.get("labels") or {}
    annotations = alert.get("annotations") or {}
    return {
        "status": alert.get("status"),
        "alertname": labels.get("alertname"),
        "severity": labels.get("severity"),
        "service": labels.get("service"),
        "component": labels.get("component"),
        "scope": labels.get("scope"),
        "summary": annotations.get("summary"),
        "runbook_url": annotations.get("runbook_url"),
        "startsAt": alert.get("startsAt"),
        "endsAt": alert.get("endsAt"),
    }


def _persist_receipt(entry: dict[str, Any]) -> None:
    RECEIPTS_DIR.mkdir(parents=True, exist_ok=True)
    path = RECEIPTS_DIR / "receipts.jsonl"
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry, separators=(",", ":")) + "\n")


class MetricsHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: Any) -> None:
        sys.stderr.write("%s - %s\n" % (_utcnow(), fmt % args))

    def _send(self, code: int, body: bytes, content_type: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path == "/health":
            self._send(200, b"ok\n", "text/plain")
            return
        if path == "/metrics":
            self._send(200, _metrics_body(), "text/plain; version=0.0.4")
            return
        if path == "/state":
            with STATE_LOCK:
                payload = json.dumps(STATE).encode("utf-8")
            self._send(200, payload, "application/json")
            return
        self._send(404, b"not found\n", "text/plain")

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length else b"{}"
        if path not in ("/control", "/arm", "/disarm"):
            self._send(404, b"not found\n", "text/plain")
            return
        try:
            patch = json.loads(raw.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            patch = {}
        if path == "/arm":
            patch = {"synthetic": 1}
        elif path == "/disarm":
            patch = {"synthetic": 0}
        if not isinstance(patch, dict):
            self._send(400, b"invalid json\n", "text/plain")
            return
        with STATE_LOCK:
            for key, value in patch.items():
                if key in STATE:
                    STATE[key] = value
            snapshot = dict(STATE)
        self._send(200, json.dumps(snapshot).encode("utf-8"), "application/json")


class ProbeHandler(MetricsHandler):
    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path == "/health":
            self._send(200, b"ok\n", "text/plain")
            return
        if path == "/metrics":
            body = (
                "# HELP parkio_alerting_probe Isolated parking-service probe.\n"
                "# TYPE parkio_alerting_probe gauge\n"
                "parkio_alerting_probe 1\n"
            ).encode("utf-8")
            self._send(200, body, "text/plain; version=0.0.4")
            return
        self._send(404, b"not found\n", "text/plain")

    def do_POST(self) -> None:  # noqa: N802
        self._send(405, b"method not allowed\n", "text/plain")


class WebhookHandler(MetricsHandler):
    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        query = parse_qs(urlparse(self.path).query)
        if path == "/health":
            self._send(200, b"ok\n", "text/plain")
            return
        if path == "/received":
            alertname = (query.get("alertname") or [None])[0]
            status = (query.get("status") or [None])[0]
            with STATE_LOCK:
                items = list(RECEIPTS)
            if alertname:
                items = [item for item in items if item.get("alertname") == alertname]
            if status:
                items = [item for item in items if item.get("group_status") == status]
            self._send(200, json.dumps(items).encode("utf-8"), "application/json")
            return
        self._send(404, b"not found\n", "text/plain")

    def do_POST(self) -> None:  # noqa: N802
        if self.headers.get("Authorization"):
            sys.stderr.write("%s - webhook received (authorization present, not logged)\n" % _utcnow())
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length else b"{}"
        try:
            payload = json.loads(raw.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self._send(400, b"invalid json\n", "text/plain")
            return
        alerts = payload.get("alerts") or []
        sanitized_alerts = [_sanitize_alert(alert) for alert in alerts if isinstance(alert, dict)]
        entry = {
            "receivedAt": _utcnow(),
            "group_status": payload.get("status"),
            "receiver": payload.get("receiver"),
            "alertname": (payload.get("commonLabels") or {}).get("alertname"),
            "severity": (payload.get("commonLabels") or {}).get("severity"),
            "alerts": sanitized_alerts,
        }
        with STATE_LOCK:
            RECEIPTS.append(entry)
        _persist_receipt(entry)
        names = ",".join(sorted({a.get("alertname") or "?" for a in sanitized_alerts})) or "none"
        sys.stderr.write(
            "%s - webhook status=%s receiver=%s alerts=%s count=%s\n"
            % (_utcnow(), payload.get("status"), payload.get("receiver"), names, len(sanitized_alerts))
        )
        self._send(200, b'{"ok":true}\n', "application/json")


def _serve(handler: type[BaseHTTPRequestHandler], port: int) -> None:
    server = ThreadingHTTPServer(("0.0.0.0", port), handler)
    sys.stderr.write("alerting-acceptance %s listening on %s\n" % (handler.__name__, port))
    server.serve_forever()


def main() -> int:
    mode = (sys.argv[1] if len(sys.argv) > 1 else "").strip()
    if mode == "metrics":
        _serve(MetricsHandler, 8081)
    elif mode == "probe":
        _serve(ProbeHandler, 8082)
    elif mode == "webhook":
        RECEIPTS_DIR.mkdir(parents=True, exist_ok=True)
        _serve(WebhookHandler, 8080)
    else:
        sys.stderr.write("usage: alerting-acceptance-receiver.py {metrics|probe|webhook}\n")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
