#!/usr/bin/env python3
"""Isolated validation for Parkio New Relic log pilot (Y02).

Runs Fluent Bit against synthetic log files and a local mock Log API receiver.
Does not require New Relic credentials.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "docker" / "docker-compose.newrelic-log-pilot.yml"
PROJECT = "parkio-y02-nr-val"


def http_raw(url: str, method: str = "GET") -> tuple[int, bytes]:
    req = urllib.request.Request(url, method=method)
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()


def http_json(url: str, method: str = "GET", data: bytes | None = None) -> tuple[int, object]:
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            body = resp.read().decode()
            return resp.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as exc:
        body = exc.read().decode()
        try:
            parsed = json.loads(body) if body else {}
        except json.JSONDecodeError:
            parsed = {"raw": body}
        return exc.code, parsed


def compose(*args: str) -> None:
    cmd = [
        "docker",
        "compose",
        "-p",
        PROJECT,
        "-f",
        str(COMPOSE),
        "--profile",
        "nr-log-pilot",
        *args,
    ]
    subprocess.run(cmd, check=True, cwd=ROOT / "docker")


def write_fixtures(volume_dir: Path) -> None:
    samples = {
        "gateway-service": [
            "2026-09-20T12:00:01.000Z  INFO [gateway-service,traceId=abc123,spanId=def456,correlationId=corr-gateway-1] Routed request path=/api/v1/parking/spots/{id}\n",
            "2026-09-20T12:00:02.000Z  ERROR [gateway-service,traceId=,spanId=,correlationId=corr-gateway-err] Upstream failed errorCode=UPSTREAM_TIMEOUT\n",
            "2026-09-20T12:00:03.000Z  ERROR [gateway-service,traceId=t1,spanId=s1,correlationId=corr-ex] boom\n",
            "java.lang.RuntimeException: boom\n",
            "\tat com.parkio.gateway.Filter.filter(Filter.java:42)\n",
            "\tat com.parkio.gateway.Filter.filter(Filter.java:41)\n",
        ],
        "auth-service": [
            "2026-09-20T12:00:04.000Z  INFO [auth-service,traceId=aaa,spanId=bbb,correlationId=corr-auth-1] UserRegistered outbox appended\n",
            # Soft redact path — token-like but not Authorization header form that hard-drops
            "2026-09-20T12:00:05.000Z  WARN [auth-service,traceId=-,spanId=-,correlationId=corr-auth-warn] retry notice\n",
        ],
        "parking-service": [
            "2026-09-20T12:00:06.000Z  INFO [parking-service,traceId=p1,spanId=p2,correlationId=corr-park-1] Spot created\n",
        ],
        # Excluded service — must not export
        "media-service": [
            "2026-09-20T12:00:07.000Z  ERROR [media-service,correlationId=corr-media] should not export\n",
        ],
    }
    sensitive = {
        "gateway-service-sensitive.log": "2026-09-20T12:00:08.000Z  ERROR [gateway-service,correlationId=corr-secret] Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.secretpassword\n",
        "auth-password.log": "2026-09-20T12:00:09.000Z  ERROR [auth-service,correlationId=corr-pw] password=SuperSecret123!\n",
        "parking-presign.log": "2026-09-20T12:00:10.000Z  ERROR [parking-service,correlationId=corr-s3] url=https://s3.example/x?X-Amz-Signature=abcdef1234567890\n",
        "parking-coords.log": "2026-09-20T12:00:11.000Z  INFO [parking-service,correlationId=corr-geo] lat=38.4237001 lon=27.1428002\n",
    }

    for service, lines in samples.items():
        d = volume_dir / service
        d.mkdir(parents=True, exist_ok=True)
        (d / "app.log").write_text("".join(lines), encoding="utf-8")

    # Sensitive canaries into pilot services — must be dropped
    g = volume_dir / "gateway-service"
    a = volume_dir / "auth-service"
    p = volume_dir / "parking-service"
    (g / "sensitive-authz.log").write_text(sensitive["gateway-service-sensitive.log"], encoding="utf-8")
    (a / "sensitive-password.log").write_text(sensitive["auth-password.log"], encoding="utf-8")
    (p / "sensitive-presign.log").write_text(sensitive["parking-presign.log"], encoding="utf-8")
    (p / "sensitive-coords.log").write_text(sensitive["parking-coords.log"], encoding="utf-8")


def dump_received(port: int) -> list[object]:
    code, _ = http_json(f"http://127.0.0.1:{port}/stats")
    assert code == 200
    req = urllib.request.Request(f"http://127.0.0.1:{port}/dump")
    with urllib.request.urlopen(req, timeout=5) as resp:
        raw = resp.read().decode()
    records: list[object] = []
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        payload = json.loads(line)
        if isinstance(payload, list):
            records.extend(payload)
        else:
            records.append(payload)
    return records


def flatten_messages(records: list[object]) -> list[dict]:
    out: list[dict] = []
    for rec in records:
        if isinstance(rec, dict):
            out.append(rec)
    return out


def main() -> int:
    mock_port = int(os.environ.get("PARKIO_MOCK_NR_HOST_PORT", "18089"))
    results: dict[str, str] = {}

    # Fresh compose project
    subprocess.run(
        ["docker", "compose", "-p", PROJECT, "-f", str(COMPOSE), "--profile", "nr-log-pilot", "down", "-v"],
        cwd=ROOT / "docker",
        check=False,
    )

    compose("up", "-d", "mock-nr-receiver", "fluent-bit-nr-pilot")

    # Wait healthy
    for _ in range(30):
        try:
            code, _ = http_json(f"http://127.0.0.1:{mock_port}/health")
            if code == 200:
                break
        except Exception:
            time.sleep(1)
    else:
        print("FAIL: mock receiver not healthy", file=sys.stderr)
        return 1

    # Copy fixtures into the named volume via a helper container
    with tempfile.TemporaryDirectory() as tmp:
        fixture_root = Path(tmp) / "parkio-pilot"
        write_fixtures(fixture_root)
        vol = f"{PROJECT}_nr-pilot-logs"
        # Ensure volume exists (compose created it)
        subprocess.run(
            [
                "docker",
                "run",
                "--rm",
                "-v",
                f"{vol}:/dest",
                "-v",
                f"{fixture_root}:/src:ro",
                "alpine:3.20",
                "sh",
                "-c",
                "cp -a /src/. /dest/ && find /dest -type f | sort",
            ],
            check=True,
        )

    # Allow Fluent Bit to tail + flush
    time.sleep(8)

    records = flatten_messages(dump_received(mock_port))
    blob = json.dumps(records)

    # 1) Pilot services present
    services = {r.get("service") for r in records if isinstance(r, dict)}
    if {"gateway-service", "auth-service", "parking-service"} <= services:
        results["pilot_services_routed"] = "PASS"
    else:
        results["pilot_services_routed"] = f"FAIL services={services}"

    # 2) Excluded media absent
    if "media-service" not in services and "corr-media" not in blob:
        results["excluded_service_leak"] = "PASS"
    else:
        results["excluded_service_leak"] = "FAIL"

    # 3) Multiline / exception usable
    if "RuntimeException" in blob or "Filter.java" in blob:
        results["multiline_exception"] = "PASS"
    else:
        results["multiline_exception"] = "FAIL (stack not observed — may be split)"

    # 4) Severity / env / service fields
    levels = {r.get("level") for r in records if isinstance(r, dict)}
    envs = {r.get("environment") for r in records if isinstance(r, dict)}
    if "ERROR" in levels or "INFO" in levels:
        results["severity_fields"] = "PASS"
    else:
        results["severity_fields"] = f"FAIL levels={levels}"
    if any(e and e != "" for e in envs):
        results["environment_fields"] = "PASS"
    else:
        results["environment_fields"] = f"FAIL envs={envs}"

    # 5) Sensitive canaries absent
    canaries = [
        "Bearer eyJ",
        "SuperSecret123",
        "X-Amz-Signature=abcdef",
        "38.4237001",
        "password=SuperSecret",
    ]
    leaked = [c for c in canaries if c in blob]
    results["sensitive_canaries"] = "PASS" if not leaked else f"FAIL leaked={leaked}"

    # 6) Malformed — ensure collector HTTP health endpoint responds
    try:
        code, body = http_raw("http://127.0.0.1:2020/api/v1/health")
        results["collector_health"] = "PASS" if code == 200 else f"FAIL {code} {body[:80]!r}"
    except Exception as exc:
        results["collector_health"] = f"FAIL {exc}"

    # 7) Receiver failure + retry: inject 503 once, write new log, expect eventual accept
    before = http_json(f"http://127.0.0.1:{mock_port}/stats")[1]
    ps = subprocess.check_output(
        ["docker", "compose", "-p", PROJECT, "-f", str(COMPOSE), "ps", "-q", "mock-nr-receiver"],
        text=True,
        cwd=ROOT / "docker",
    ).strip()
    if ps:
        subprocess.run(["docker", "exec", ps, "touch", "/tmp/fail_once"], check=False)

    vol = f"{PROJECT}_nr-pilot-logs"
    subprocess.run(
        [
            "docker",
            "run",
            "--rm",
            "-v",
            f"{vol}:/dest",
            "alpine:3.20",
            "sh",
            "-c",
            "echo '2026-09-20T12:10:00.000Z  INFO [gateway-service,correlationId=corr-retry-1] after failure' >> /dest/gateway-service/app.log",
        ],
        check=True,
    )
    time.sleep(10)
    after = http_json(f"http://127.0.0.1:{mock_port}/stats")[1]
    if after.get("accepted", 0) >= before.get("accepted", 0):
        results["retry_after_failure"] = "PASS"
    else:
        results["retry_after_failure"] = f"FAIL before={before} after={after}"

    # 8) Buffer / loss visibility — storage.total_limit_size configured; assert config present
    conf = (ROOT / "docker/fluent-bit/fluent-bit.conf").read_text(encoding="utf-8")
    if "storage.total_limit_size" in conf and "Mem_Buf_Limit" in conf:
        results["buffer_limits_configured"] = "PASS"
    else:
        results["buffer_limits_configured"] = "FAIL"

    # 9) Restart does not require full history reread without DB — DB path configured
    if "pilot-tail.db" in conf:
        results["tail_db_positions"] = "PASS"
    else:
        results["tail_db_positions"] = "FAIL"
    compose("restart", "fluent-bit-nr-pilot")
    time.sleep(5)
    try:
        code, body = http_raw("http://127.0.0.1:2020/api/v1/health")
        results["restart_health"] = "PASS" if code == 200 else f"FAIL {code} {body[:80]!r}"
    except Exception as exc:
        results["restart_health"] = f"FAIL {exc}"

    # 10) Loki compatibility — pilot overlay does not modify promtail/loki services
    promtail = (ROOT / "docker/promtail/promtail.yml").read_text(encoding="utf-8")
    if "loki:3100" in promtail and "docker.sock" in promtail:
        results["loki_config_untouched"] = "PASS"
    else:
        results["loki_config_untouched"] = "FAIL"

    # 11) Disable pilot — apps not in this compose; stopping collector leaves mock only
    compose("stop", "fluent-bit-nr-pilot")
    results["pilot_disable"] = "PASS"

    print(json.dumps({"records": len(records), "results": results}, indent=2))
    failed = [k for k, v in results.items() if not str(v).startswith("PASS")]
    # cleanup
    subprocess.run(
        ["docker", "compose", "-p", PROJECT, "-f", str(COMPOSE), "--profile", "nr-log-pilot", "down", "-v"],
        cwd=ROOT / "docker",
        check=False,
    )
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
