#!/usr/bin/env python3
"""Focused non-container validation for continuous gate/source behavior."""

from __future__ import annotations

import importlib.util
import gzip
import json
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GATE = ROOT / "scripts/newrelic_log_pilot/budget_gate.py"


def load_gate(state: Path):
    values = {
        "PARKIO_NR_BUDGET_STATE_DB": str(state),
        "PARKIO_NR_BUDGET_BYTES": "0",
        "PARKIO_NR_DAILY_BUDGET_BYTES": "100",
        "PARKIO_NR_MONTHLY_BUDGET_BYTES": "250",
        "PARKIO_NR_LOG_API_KEY": "unit-not-a-secret",
        "PARKIO_NR_GATE_TEST_CONTROL": "on",
    }
    os.environ.update(values)
    spec = importlib.util.spec_from_file_location("parkio_continuous_budget_gate", GATE)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def wait_health(port: int) -> None:
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=0.2):
                return
        except Exception:
            time.sleep(0.05)
    raise RuntimeError("gate did not become healthy")


def main() -> int:
    results: dict[str, str] = {}
    with tempfile.TemporaryDirectory(prefix="parkio-nr-continuous-") as directory:
        root = Path(directory)
        module = load_gate(root / "module.db")
        budget = module.Budget(root / "windows.db", 0, 100, 250)

        module._test_now = datetime(2026, 9, 22, 23, 59, tzinfo=timezone.utc)
        first = budget.reserve(60, 30, 1, "one")
        rejected = budget.reserve(50, 25, 1, "two")
        day_one = budget.snapshot()
        module._test_now = datetime(2026, 9, 23, 0, 1, tzinfo=timezone.utc)
        day_two_before = budget.snapshot()
        second_day = budget.reserve(100, 50, 1, "three")
        restarted = module.Budget(root / "windows.db", 0, 100, 250).snapshot()
        results["daily_rollover_utc"] = "PASS" if (
            first and not rejected and day_one["daily_exhausted"]
            and day_two_before["daily_spent"] == 0 and second_day
            and restarted["daily_spent"] == 100 and restarted["monthly_spent"] == 160
        ) else "FAIL"

        module._test_now = datetime(2026, 9, 24, 0, 1, tzinfo=timezone.utc)
        monthly_exact = budget.reserve(90, 45, 1, "four")
        blocked = budget.reserve(1, 1, 1, "five")
        month_end = budget.snapshot()
        module._test_now = datetime(2026, 10, 1, 0, 1, tzinfo=timezone.utc)
        next_month = budget.snapshot()
        results["monthly_rollover_utc"] = "PASS" if (
            monthly_exact and not blocked and month_end["monthly_spent"] == 250
            and month_end["monthly_exhausted"] and next_month["monthly_spent"] == 0
            and not next_month["monthly_exhausted"]
        ) else "FAIL"

        mismatch = False
        try:
            module.Budget(root / "windows.db", 0, 101, 250)
        except RuntimeError:
            mismatch = True
        results["restart_safe_config_mismatch"] = "PASS" if mismatch else "FAIL"

        total = module.Budget(root / "total.db", 100, 0, 0)
        results["legacy_total_budget"] = "PASS" if (
            total.reserve(100, 40, 1, "total") and total.snapshot()["exhausted"]
            and not total.reserve(1, 1, 1, "over")
        ) else "FAIL"

        port = 18191
        upstream_port = 18192
        request_entered = threading.Event()

        class SlowUpstream(BaseHTTPRequestHandler):
            def log_message(self, _format: str, *_args: object) -> None:
                return

            def do_POST(self) -> None:
                request_entered.set()
                time.sleep(0.75)
                self.send_response(202)
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"{}")

        upstream = ThreadingHTTPServer(("127.0.0.1", upstream_port), SlowUpstream)
        upstream_thread = threading.Thread(target=upstream.serve_forever, daemon=True)
        upstream_thread.start()
        env = os.environ.copy()
        env.update({
            "PARKIO_NR_GATE_PORT": str(port),
            "PARKIO_NR_BUDGET_STATE_DB": str(root / "signal.db"),
            "PARKIO_NR_BUDGET_BYTES": "0",
            "PARKIO_NR_DAILY_BUDGET_BYTES": "1000",
            "PARKIO_NR_MONTHLY_BUDGET_BYTES": "2000",
            "PARKIO_NR_LOG_API_KEY": "unit-not-a-secret",
            "PARKIO_NR_GATE_TEST_CONTROL": "on",
            "PARKIO_NR_GATE_MAX_REQUEST_BYTES": "128",
            "PARKIO_NR_UPSTREAM_BASE_URI": f"http://127.0.0.1:{upstream_port}/log/v1",
        })
        process = subprocess.Popen(
            [sys.executable, str(GATE)], env=env,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        try:
            wait_health(port)
            expanded = json.dumps([{"logs": [{"message": "x" * 256}]}]).encode()
            compressed = gzip.compress(expanded)
            oversized_request = urllib.request.Request(
                f"http://127.0.0.1:{port}/log/v1", data=compressed, method="POST",
                headers={
                    "Content-Type": "application/json", "Content-Encoding": "gzip",
                    "X-License-Key": "gate-internal-not-a-secret",
                },
            )
            oversized_status = 0
            try:
                urllib.request.urlopen(oversized_request, timeout=2)
            except urllib.error.HTTPError as exc:
                oversized_status = exc.code
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/stats", timeout=2) as reply:
                oversized_stats = json.load(reply)
            results["gzip_expansion_request_bound"] = "PASS" if (
                oversized_status == 413 and oversized_stats["spent_bytes"] == 0
            ) else "FAIL"
            response: dict[str, int] = {}

            def send_request() -> None:
                body = json.dumps([{"logs": [{"message": "graceful-stop"}]}]).encode()
                request = urllib.request.Request(
                    f"http://127.0.0.1:{port}/log/v1", data=body, method="POST",
                    headers={"Content-Type": "application/json", "X-License-Key": "gate-internal-not-a-secret"},
                )
                with urllib.request.urlopen(request, timeout=3) as reply:
                    response["status"] = reply.status

            request_thread = threading.Thread(target=send_request)
            request_thread.start()
            if not request_entered.wait(2):
                raise RuntimeError("upstream request did not start")
            started = time.monotonic()
            process.send_signal(signal.SIGTERM)
            process.wait(timeout=3)
            elapsed = time.monotonic() - started
            request_thread.join(timeout=1)
            results["gate_graceful_sigterm"] = "PASS" if (
                process.returncode == 0 and 0.5 < elapsed < 3 and response.get("status") == 202
            ) else "FAIL"
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            upstream.shutdown()
            upstream.server_close()

        guard_unit = (
            ROOT / "scripts/newrelic_log_pilot/systemd/parkio-nr-log-continuous-guard.service"
        ).read_text(encoding="utf-8")
        guard_script = (
            ROOT / "scripts/newrelic_log_pilot/continuous_guard.sh"
        ).read_text(encoding="utf-8")
        results["guard_does_not_restart_stopped_transport"] = "PASS" if (
            "Requires=parkio-nr-log-continuous.service" not in guard_unit
            and "ExecCondition=/usr/bin/systemctl is-active --quiet parkio-nr-log-continuous.service" in guard_unit
            and "systemctl --no-block stop \"$transport_unit\"" in guard_script
        ) else "FAIL"

        report = {
            "results": results,
            "passed": sum(value == "PASS" for value in results.values()),
            "total": len(results),
        }
        print(json.dumps(report, sort_keys=True))
        return 0 if report["passed"] == report["total"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
