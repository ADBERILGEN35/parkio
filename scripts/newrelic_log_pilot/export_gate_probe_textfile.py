#!/usr/bin/env python3
"""Probe NR budget-gate /health into the node-exporter textfile collector.

When the gate/pilot is not running the previous .prom file is removed so
NrBudgetGateExhausted stays silent (series absent). HTTP 507 (exhausted) and
other non-2xx responses write parkio_nr_budget_gate_probe_success 0.

Does not edit budget_gate.py (#104 / #413).
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path


DEFAULT_OUTPUT = os.environ.get(
    "PARKIO_NR_GATE_TEXTFILE",
    "docker/prometheus/textfile/parkio_nr_budget_gate.prom",
)


def probe_http(url: str, timeout: float) -> int | None:
    """Return HTTP status code, or None if the target is unreachable."""
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            return int(resp.status)
    except urllib.error.HTTPError as exc:
        return int(exc.code)
    except (urllib.error.URLError, TimeoutError, ConnectionError, OSError):
        return None


def probe_compose(project: str, timeout: float) -> int | None:
    """Probe /health inside the compose service. None if the container is absent."""
    ps = subprocess.run(
        ["docker", "compose", "-p", project, "ps", "-q", "nr-budget-gate"],
        check=False,
        capture_output=True,
        text=True,
    )
    if ps.returncode != 0 or not ps.stdout.strip():
        return None
    snippet = (
        "import urllib.request, urllib.error\n"
        "try:\n"
        "  urllib.request.urlopen('http://127.0.0.1:8090/health', timeout=%s)\n"
        "  print(200)\n"
        "except urllib.error.HTTPError as e:\n"
        "  print(int(e.code))\n"
        "except Exception:\n"
        "  print('UNREACHABLE')\n" % (timeout,)
    )
    proc = subprocess.run(
        [
            "docker",
            "compose",
            "-p",
            project,
            "exec",
            "-T",
            "nr-budget-gate",
            "python",
            "-c",
            snippet,
        ],
        check=False,
        capture_output=True,
        text=True,
        timeout=max(timeout + 5.0, 15.0),
    )
    if proc.returncode != 0:
        return None
    line = (proc.stdout or "").strip().splitlines()
    if not line:
        return None
    token = line[-1].strip()
    if token == "UNREACHABLE":
        return None
    try:
        return int(token)
    except ValueError:
        return None


def render(success: int, when: int) -> str:
    return "\n".join(
        [
            "# HELP parkio_nr_budget_gate_probe_success 1 when NR budget-gate /health is 2xx",
            "# TYPE parkio_nr_budget_gate_probe_success gauge",
            f"parkio_nr_budget_gate_probe_success {success}",
            "# HELP parkio_nr_budget_gate_probe_last_run_unixtime Unix time of last successful probe write",
            "# TYPE parkio_nr_budget_gate_probe_last_run_unixtime gauge",
            f"parkio_nr_budget_gate_probe_last_run_unixtime {when}",
            "",
        ]
    )


def atomic_write(path: Path, body: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w",
        dir=str(path.parent),
        delete=False,
        prefix=".parkio_nr_budget_gate.",
        suffix=".tmp",
        encoding="utf-8",
    ) as tmp:
        tmp.write(body)
        tmp.flush()
        os.fsync(tmp.fileno())
        tmp_path = Path(tmp.name)
    tmp_path.replace(path)


def clear_output(path: Path) -> None:
    try:
        path.unlink()
    except FileNotFoundError:
        pass


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        default=DEFAULT_OUTPUT,
        help="Destination .prom path (atomic replace; removed when gate absent)",
    )
    parser.add_argument(
        "--http-url",
        default=os.environ.get("PARKIO_NR_GATE_PROBE_URL", ""),
        help="Direct /health URL (preferred for stub tests / published host port)",
    )
    parser.add_argument(
        "--compose-project",
        default=os.environ.get("PARKIO_NR_GATE_COMPOSE_PROJECT", ""),
        help="docker compose project that runs nr-budget-gate (production continuous pilot)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=float(os.environ.get("PARKIO_NR_GATE_PROBE_TIMEOUT", "3")),
        help="Probe timeout seconds",
    )
    args = parser.parse_args(argv)
    out = Path(args.output)

    status: int | None
    if args.http_url:
        status = probe_http(args.http_url, args.timeout)
    elif args.compose_project:
        status = probe_compose(args.compose_project, args.timeout)
    else:
        # Default production continuous project when neither flag/env is set.
        status = probe_compose(
            os.environ.get("PARKIO_NR_PILOT_PROJECT", "parkio-nr-log-continuous"),
            args.timeout,
        )

    if status is None:
        clear_output(out)
        print(f"gate absent; cleared {out}")
        return 0

    success = 1 if 200 <= status < 300 else 0
    when = int(time.time())
    atomic_write(out, render(success, when))
    print(f"wrote {out} status={status} success={success}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
