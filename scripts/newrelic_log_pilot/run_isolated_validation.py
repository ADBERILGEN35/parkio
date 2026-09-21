#!/usr/bin/env python3
"""Isolated acceptance for the exact Parkio New Relic collector candidate.

Only task-scoped Compose resources are created. Synthetic Docker json-file logs
are sent to a local controllable Log API mock; no New Relic credential or user
data is used.
"""

from __future__ import annotations

import json
import os
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable
from unittest import mock

from docker_log_source import BoundedWriter, SERVICES


ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "docker" / "docker-compose.newrelic-log-pilot.yml"
DOCKER = shutil.which("docker") or shutil.which("docker.exe") or "docker"
PROJECT = os.environ.get("PARKIO_NR_VALIDATION_PROJECT", f"parkio-p02-nr-val-{os.getpid()}")
HELPER_IMAGE = os.environ.get("PARKIO_NR_VALIDATION_HELPER_IMAGE", "alpine:3.20")
API_KEY = "mock-not-a-real-license"


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


MOCK_PORT = int(os.environ.get("PARKIO_MOCK_NR_HOST_PORT", str(free_port())))
FLUENT_PORT = int(os.environ.get("PARKIO_NR_FLUENT_HTTP_PORT", str(free_port())))
GATE_PORT = int(os.environ.get("PARKIO_NR_GATE_HTTP_PORT", str(free_port())))
ENV = {
    **os.environ,
    "PARKIO_MOCK_NR_HOST_PORT": str(MOCK_PORT),
    "PARKIO_NR_FLUENT_HTTP_PORT": str(FLUENT_PORT),
    "PARKIO_NR_GATE_HTTP_PORT": str(GATE_PORT),
    "PARKIO_NR_LOG_API_KEY": API_KEY,
    "PARKIO_NR_GATE_TEST_CONTROL": "on",
    "PARKIO_NR_BUDGET_BYTES": str(256 * 1024 * 1024),
    "PARKIO_ENVIRONMENT": "isolated-p02-validation",
    "PARKIO_RELEASE_ID": "synthetic-app-release",
    "PARKIO_COLLECTOR_SOURCE_SHA": "synthetic-under-test",
    "PARKIO_NR_PILOT_MARKER": "p02-harness-startup",
}
_forwarded = [
    "PARKIO_MOCK_NR_HOST_PORT", "PARKIO_NR_FLUENT_HTTP_PORT", "PARKIO_NR_GATE_HTTP_PORT",
    "PARKIO_NR_LOG_API_KEY", "PARKIO_NR_GATE_TEST_CONTROL", "PARKIO_NR_BUDGET_BYTES",
    "PARKIO_ENVIRONMENT", "PARKIO_RELEASE_ID", "PARKIO_COLLECTOR_SOURCE_SHA",
    "PARKIO_NR_PILOT_MARKER",
]
ENV["WSLENV"] = ":".join(filter(None, [os.environ.get("WSLENV", ""), *_forwarded]))


def run(args: list[str], *, check: bool = True, capture: bool = False, data: str | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=ROOT,
        env=ENV,
        check=check,
        text=True,
        input=data,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )


def compose(*args: str, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess[str]:
    return run(
        [
            DOCKER,
            "compose",
            "-p",
            PROJECT,
            "-f",
            str(COMPOSE),
            "--profile",
            "nr-log-pilot",
            "--profile",
            "nr-log-pilot-mock",
            *args,
        ],
        check=check,
        capture=capture,
    )


def http_json(path: str, *, method: str = "GET", payload: dict | None = None, port: int | None = None,
              headers: dict[str, str] | None = None) -> tuple[int, object]:
    target_port = MOCK_PORT if port is None else port
    scheme = "https" if port is None else "http"
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(f"{scheme}://127.0.0.1:{target_port}{path}", data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    try:
        context = ssl._create_unverified_context() if scheme == "https" else None
        with urllib.request.urlopen(request, timeout=5, context=context) as response:
            body = response.read().decode()
            if not body:
                parsed: object = {}
            else:
                try:
                    parsed = json.loads(body)
                except json.JSONDecodeError:
                    parsed = body
            return response.status, parsed
    except urllib.error.HTTPError as exc:
        body = exc.read().decode()
        return exc.code, json.loads(body) if body else {}


def gate_json(path: str, *, method: str = "GET", payload: dict | None = None) -> tuple[int, object]:
    headers = {"X-License-Key": "gate-internal-not-a-secret"} if path == "/log/v1" else None
    return http_json(path, method=method, payload=payload, port=GATE_PORT, headers=headers)


def wait_until(predicate: Callable[[], bool], timeout: float, label: str) -> None:
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            if predicate():
                return
        except Exception as exc:  # transient while containers/retries settle
            last_error = exc
        time.sleep(0.5)
    suffix = f": {last_error}" if last_error else ""
    raise AssertionError(f"timeout waiting for {label}{suffix}")


def helper(script: str, *, data: str | None = None, state: bool = False, capture: bool = False) -> subprocess.CompletedProcess[str]:
    volume = f"{PROJECT}_{'nr-pilot-state' if state else 'nr-pilot-logs'}"
    target = "/state" if state else "/dest"
    return run(
        [DOCKER, "run", "--rm", "--network", "none", "-i", "-v", f"{volume}:{target}", HELPER_IMAGE, "sh", "-ec", script],
        capture=capture,
        data=data,
    )


def docker_entry(message: str, timestamp: str, stream: str = "stdout") -> str:
    return json.dumps({"log": message + "\n", "stream": stream, "time": timestamp}, separators=(",", ":")) + "\n"


def append(service: str, entries: list[tuple[str, str, str]], filename: str = "container-json.log") -> float:
    assert service in {"gateway-service", "auth-service", "parking-service", "media-service"}
    assert filename.replace("-", "").replace(".", "").isalnum()
    payload = "".join(docker_entry(message, timestamp, stream) for message, timestamp, stream in entries)
    start = time.monotonic()
    helper(f"mkdir -p /dest/{service}; cat >> /dest/{service}/{filename}", data=payload)
    return time.monotonic() - start


def flatten_payload(payload: object) -> list[dict]:
    records: list[dict] = []
    items = payload if isinstance(payload, list) else [payload]
    for item in items:
        if not isinstance(item, dict):
            continue
        if isinstance(item.get("logs"), list):
            common = item.get("common", {})
            common_attrs = common.get("attributes", {}) if isinstance(common, dict) else {}
            for log in item["logs"]:
                if not isinstance(log, dict):
                    continue
                record = dict(common_attrs) if isinstance(common_attrs, dict) else {}
                attrs = log.get("attributes", {})
                if isinstance(attrs, dict):
                    record.update(attrs)
                record.update({key: value for key, value in log.items() if key != "attributes"})
                records.append(record)
        else:
            records.append(item)
    return records


def dump_records() -> list[dict]:
    request = urllib.request.Request(f"https://127.0.0.1:{MOCK_PORT}/dump")
    with urllib.request.urlopen(request, timeout=5, context=ssl._create_unverified_context()) as response:
        body = response.read().decode()
    records: list[dict] = []
    for line in body.splitlines():
        if line.strip():
            records.extend(flatten_payload(json.loads(line)))
    return records


def marker_count(marker: str) -> int:
    return sum(1 for record in dump_records() if record.get("pilot_marker") == marker)


def wait_marker(marker: str, timeout: float = 35) -> None:
    wait_until(lambda: marker_count(marker) >= 1, timeout, f"marker {marker}")


def inject(status: int, *, count: int = 0, persistent: bool = False, retry_after: int = 1) -> None:
    code, _ = http_json(
        "/control",
        method="POST",
        payload={"status": status, "count": count, "persistent": persistent, "retry_after": retry_after},
    )
    assert code == 200


def add_result(results: dict[str, str], name: str, condition: bool, detail: str = "") -> None:
    results[name] = "PASS" if condition else f"FAIL{': ' + detail if detail else ''}"


def atomic_state(path: Path, state: dict) -> None:
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(state), encoding="utf-8")
    os.replace(temporary, path)


def source_helper_acceptance(results: dict[str, str], metrics: dict[str, object]) -> None:
    fake = ROOT / "scripts" / "newrelic_log_pilot" / "fake_docker.py"
    helper_path = ROOT / "scripts" / "newrelic_log_pilot" / "docker_log_source.py"
    with tempfile.TemporaryDirectory(prefix="parkio-p02-source-") as directory:
        base = Path(directory)
        output = base / "logs"
        source_state = base / "cursor"
        docker_state = base / "docker.json"
        ids = {
            "gateway-service": "a" * 64,
            "auth-service": "b" * 64,
            "parking-service": "c" * 64,
            "media-service": "d" * 64,
        }
        state = {
            "project": "parkio-test",
            "connected": True,
            "containers": {name: {"id": value, "running": True, "driver": "json-file"} for name, value in ids.items()},
            "events": [],
        }
        atomic_state(docker_state, state)
        wrapper = base / "docker"
        wrapper.write_text(
            "#!/bin/sh\nexec " + json.dumps(sys.executable) + " " + json.dumps(str(fake)) + " \"$@\"\n",
            encoding="utf-8",
        )
        wrapper.chmod(0o700)
        process_env = {**os.environ, "PARKIO_FAKE_DOCKER_STATE": str(docker_state)}
        process = subprocess.Popen(
            [
                sys.executable, str(helper_path), "--docker", str(wrapper), "--project", "parkio-test",
                "--output", str(output), "--state", str(source_state), "--max-file-bytes", "4096",
                "--max-files", "3", "--poll-seconds", "0.1", "--docker-timeout", "2",
            ],
            cwd=ROOT, env=process_env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True,
        )

        def add_event(service: str, marker: str, stream: str = "stdout", seconds: int = 1) -> None:
            current = json.loads(docker_state.read_text(encoding="utf-8"))
            timestamp = (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat(timespec="microseconds").replace("+00:00", "Z")
            current["events"].append({
                "container_id": current["containers"][service]["id"],
                "timestamp": timestamp,
                "stream": stream,
                "message": f"INFO synthetic {marker}",
            })
            atomic_state(docker_state, current)

        def source_blob() -> str:
            pieces = []
            for path in output.glob("*/*"):
                if path.is_file():
                    pieces.append(path.read_text(encoding="utf-8", errors="replace"))
            return "".join(pieces)

        try:
            wait_until(lambda: all((source_state / f"{service}.json").parent.exists() for service in SERVICES), 3, "source helper startup")
            add_event("gateway-service", "source-initial")
            add_event("auth-service", "source-stderr", "stderr")
            wait_until(lambda: "source-initial" in source_blob() and "source-stderr" in source_blob(), 10, "source initial records")

            current = json.loads(docker_state.read_text(encoding="utf-8"))
            current["connected"] = False
            atomic_state(docker_state, current)
            time.sleep(0.5)
            current = json.loads(docker_state.read_text(encoding="utf-8"))
            current["connected"] = True
            atomic_state(docker_state, current)
            add_event("parking-service", "source-reconnected", seconds=2)
            wait_until(lambda: "source-reconnected" in source_blob(), 10, "source reconnect")

            current = json.loads(docker_state.read_text(encoding="utf-8"))
            current["containers"]["gateway-service"]["id"] = "e" * 64
            atomic_state(docker_state, current)
            add_event("gateway-service", "source-replaced", seconds=3)
            wait_until(lambda: "source-replaced" in source_blob(), 10, "source replacement")
            blob = source_blob()
            add_result(results, "supported_source_disconnect_reconnect", "source-reconnected" in blob)
            add_result(results, "supported_source_container_replacement", "source-replaced" in blob)
            add_result(
                results, "supported_source_scope_and_envelope",
                not (output / "media-service").exists() and '"stream":"stderr"' in blob and '"time":"' in blob,
            )

            for index in range(180):
                add_event("auth-service", f"rotation-{index}-" + "x" * 80, seconds=4 + index)
            wait_until(lambda: (output / "auth-service" / "source-json.log.1").exists(), 15, "source spool rotation")
            files = list((output / "auth-service").glob("source-json.log*"))
            total = sum(path.stat().st_size for path in files)
            add_result(results, "bounded_source_spool", len(files) <= 3 and total <= 3 * 4096, f"files={len(files)} bytes={total}")
            metrics["source_spool_test_bytes"] = total

            writer = BoundedWriter(base / "full-test", "gateway-service", 4096, 3)
            with mock.patch("os.write", side_effect=OSError(28, "No space left on device")):
                full_safe = writer.write(utc_timestamp(), "stdout", "synthetic full storage") is False
            add_result(results, "source_full_storage_safe_drop", full_safe)
        finally:
            process.terminate()
            try:
                _, stderr = process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                _, stderr = process.communicate(timeout=5)
            metrics["source_helper_exit"] = process.returncode
            metrics["source_helper_events"] = {
                event: stderr.count(f"source-helper {event}")
                for event in ("ATTACHED", "DISCONNECTED", "REPLACED", "DROP")
            }
            add_result(results, "supported_source_disconnect_visible", "source-helper DISCONNECTED" in stderr)
            add_result(results, "supported_source_replacement_visible", "source-helper REPLACED" in stderr)


def utc_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


def main() -> int:
    results: dict[str, str] = {}
    metrics: dict[str, object] = {
        "project": PROJECT,
        "mock_port": MOCK_PORT,
        "fluent_port": FLUENT_PORT,
        "gate_port": GATE_PORT,
    }
    tls_temp: tempfile.TemporaryDirectory[str] | None = None
    compose("down", "-v", check=False)
    try:
        source_helper_acceptance(results, metrics)
        compose("create", "mock-nr-receiver")
        tls_temp = tempfile.TemporaryDirectory(prefix="parkio-p02-nr-tls-")
        tls_path = Path(tls_temp.name)
        run(
            [
                "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                "-subj", "/CN=mock-nr-receiver", "-addext", "subjectAltName=DNS:mock-nr-receiver",
                "-keyout", str(tls_path / "tls.key"), "-out", str(tls_path / "tls.crt"),
            ],
            capture=True,
        )
        run(
            [
                DOCKER, "run", "--rm", "--network", "none",
                "-v", f"{PROJECT}_nr-mock-tls:/dest",
                "-v", f"{tls_path}:/src:ro",
                HELPER_IMAGE, "sh", "-ec", "cp /src/tls.crt /src/tls.key /dest/; chmod 600 /dest/tls.key",
            ]
        )
        compose("up", "-d", "mock-nr-receiver")
        wait_until(lambda: http_json("/health")[0] == 200, 30, "mock health")

        compose("create", "fluent-bit-nr-pilot")
        helper(
            "for s in gateway-service auth-service parking-service media-service; do "
            "mkdir -p /dest/$s; : > /dest/$s/container-json.log; done"
        )
        compose("up", "-d", "fluent-bit-nr-pilot")
        wait_until(lambda: gate_json("/health")[0] == 200, 30, "budget gate health")
        wait_until(lambda: http_json("/api/v1/health", port=FLUENT_PORT)[0] == 200, 30, "collector health")

        append(
            "gateway-service",
            [
                ("2026-09-21T12:00:01.000Z  INFO [gateway-service,traceId=abc123,spanId=def456] NR-log-pilot synthetic marker pilotMarker=p02-base", "2026-09-21T12:00:01.000000000Z", "stdout"),
                ("2026-09-21T12:00:02.000Z  WARN [gateway-service,correlationId=corr-waitlist] Waitlist confirmation delivery failed after durable write; emailHash=abcdef123456", "2026-09-21T12:00:02.000000000Z", "stderr"),
                ("2026-09-21T12:00:03.000Z  ERROR [gateway-service,correlationId=corr-stack] Upstream failed errorCode=UPSTREAM_TIMEOUT", "2026-09-21T12:00:03.000000000Z", "stderr"),
                ("java.lang.RuntimeException: synthetic boom", "2026-09-21T12:00:03.100000000Z", "stderr"),
                ("\tat com.parkio.gateway.Filter.filter(Filter.java:42)", "2026-09-21T12:00:03.200000000Z", "stderr"),
                ("Caused by: java.io.IOException: downstream unavailable for stack-user@example.com access_token=stack-access-secret", "2026-09-21T12:00:03.300000000Z", "stderr"),
                ("\tat com.parkio.gateway.Client.call(Client.java:21)", "2026-09-21T12:00:03.400000000Z", "stderr"),
                ("2026-09-21T12:00:04.000Z  INFO [gateway-service] stack flush marker pilotMarker=p02-stack-flush", "2026-09-21T12:00:04.000000000Z", "stdout"),
            ],
        )
        append(
            "auth-service",
            [
                ("2026-09-21T12:00:05.000Z  WARN [auth-service,correlationId=corr-redact] delivery failed for subscriber@example.com confirm=https://app.example/waitlist/confirm/token-123?email=subscriber@example.com resend_key=re_secret_123", "2026-09-21T12:00:05.000000000Z", "stderr"),
                ("2026-09-21T12:00:05.500Z  WARN [auth-service] credential cleanup pilotMarker=p02-withdraw-redact withdraw=https://app.example/waitlist/withdraw/withdraw-path-secret?email=withdraw@example.com confirmationToken=confirm-assignment-secret withdrawalToken=withdraw-assignment-secret password=password-secret client_secret=client-secret api_key=api-secret", "2026-09-21T12:00:05.500000000Z", "stderr"),
                ("2026-09-21T12:00:06.000Z  INFO [auth-service] Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc.signature PARKIO_PROHIBITED_CANARY_AUTH", "2026-09-21T12:00:06.000000000Z", "stdout"),
                ("2026-09-21T12:00:07.000Z  INFO [auth-service] Cookie: session=PARKIO_PROHIBITED_CANARY_COOKIE", "2026-09-21T12:00:07.000000000Z", "stdout"),
                ("2026-09-21T12:00:07.500Z  INFO [auth-service] unexpected raw token aaaaaaaaaa.bbbbbbbbbb.cccccccc pilotMarker=p02-raw-jwt", "2026-09-21T12:00:07.500000000Z", "stdout"),
            ],
        )
        append(
            "parking-service",
            [
                ("2026-09-21T12:00:08.000Z  INFO [parking-service] credential resendKey=re_PARKIO_PROHIBITED_CANARY_RESEND", "2026-09-21T12:00:08.000000000Z", "stdout"),
                ("2026-09-21T12:00:09.000Z  INFO [parking-service] query=https://api.example/search?q=PARKIO_PROHIBITED_CANARY_QUERY&token=secret", "2026-09-21T12:00:09.000000000Z", "stdout"),
                ("2026-09-21T12:00:10.000Z  INFO [parking-service] lat=38.4237001 lon=27.1428002 PARKIO_PROHIBITED_CANARY_GEO", "2026-09-21T12:00:10.000000000Z", "stdout"),
                ("2026-09-21T12:00:10.500Z  INFO [parking-service] source identity marker pilotMarker=p02-parking", "2026-09-21T12:00:10.500000000Z", "stdout"),
            ],
        )
        append("media-service", [("2026-09-21T12:00:11.000Z ERROR should not export pilotMarker=p02-media", "2026-09-21T12:00:11.000000000Z", "stderr")])
        wait_marker("p02-stack-flush")
        time.sleep(3)

        records = dump_records()
        blob = json.dumps(records, sort_keys=True)
        services = {record.get("service") for record in records}
        add_result(results, "pilot_services", {"gateway-service", "auth-service", "parking-service"} <= services, str(services))
        add_result(results, "excluded_service", "media-service" not in services and "p02-media" not in blob)
        startup = [
            record for record in records
            if record.get("pilot_marker") == "p02-harness-startup"
            and record.get("source_kind") == "synthetic"
            and record.get("event_name") == "nr_log_pilot.synthetic_probe"
        ]
        add_result(results, "synthetic_startup_marker", len(startup) == 1, f"records={len(startup)}")
        stack_records = [record for record in records if record.get("correlation_id") == "corr-stack"]
        add_result(
            results,
            "docker_rotation_multiline_parser",
            len(stack_records) == 1
            and "RuntimeException" in stack_records[0].get("message", "")
            and "Caused by:" in stack_records[0].get("message", "")
            and "[EMAIL_REDACTED]" in stack_records[0].get("message", "")
            and "access_token=[REDACTED]" in stack_records[0].get("message", ""),
            f"records={len(stack_records)}",
        )
        waitlist = [record for record in records if record.get("event_name") == "waitlist.confirmation.delivery_failed"]
        add_result(results, "waitlist_event_context", len(waitlist) == 1 and waitlist[0].get("severity") == "WARN" and "abcdef123456" not in waitlist[0].get("message", ""))
        withdrawal = [record for record in records if record.get("pilot_marker") == "p02-withdraw-redact"]
        add_result(
            results,
            "withdrawal_and_credentials_redaction",
            len(withdrawal) == 1
            and "/withdraw/[REDACTED]?[REDACTED_QUERY]" in withdrawal[0].get("message", "")
            and "confirmation_token=[REDACTED]" in withdrawal[0].get("message", "")
            and "withdrawal_token=[REDACTED]" in withdrawal[0].get("message", "")
            and "password=[REDACTED]" in withdrawal[0].get("message", "")
            and "client_secret=[REDACTED]" in withdrawal[0].get("message", "")
            and "api_key=[REDACTED]" in withdrawal[0].get("message", ""),
            f"records={len(withdrawal)}",
        )
        add_result(results, "raw_jwt_drop", "p02-raw-jwt" not in blob)
        canaries = [
            "PARKIO_PROHIBITED_CANARY", "subscriber@example.com", "token-123", "re_secret_123",
            "stack-user@example.com", "stack-access-secret", "withdraw-path-secret",
            "withdraw@example.com", "confirm-assignment-secret", "withdraw-assignment-secret", "password-secret",
            "client-secret", "api-secret", "aaaaaaaaaa.bbbbbbbbbb.cccccccc",
            "38.4237001", "27.1428002",
        ]
        leaked = [value for value in canaries if value in blob]
        add_result(results, "redaction_canaries", not leaked, f"leaked={leaked}")
        allowed = {
            "timestamp", "service", "severity", "level", "message", "parse_status", "redaction",
            "source_kind", "source_stream", "correlation_id", "trace_id", "span_id", "error_code",
            "pilot_marker", "event_name", "environment", "release_id", "collector_source_sha",
            "pipeline", "schema_version", "collector", "logtype", "plugin",
        }
        unexpected = sorted({key for record in records for key in record if key not in allowed})
        add_result(results, "allowlisted_fields_only", not unexpected, f"unexpected={unexpected}")

        stats = http_json("/stats")[1]
        add_result(results, "new_relic_http_contract", int(stats.get("accepted", 0)) > 0 and int(stats.get("gzip_requests", 0)) > 0, str(stats))

        failure_cases = [(401, "p02-auth-recovery", "authentication_failure"), (404, "p02-config-recovery", "configuration_failure"), (429, "p02-rate-recovery", "rate_limit_recovery")]
        for status, marker, result_name in failure_cases:
            inject(status, count=1, retry_after=1)
            append("gateway-service", [(f"2026-09-21T12:01:00.000Z ERROR NR-log-pilot synthetic marker pilotMarker={marker}", "2026-09-21T12:01:00.000000000Z", "stderr")])
            try:
                wait_marker(marker, 45)
                recovered = True
            except AssertionError:
                recovered = False
            status_counts = http_json("/stats")[1].get("status_counts", {})
            add_result(results, result_name, int(status_counts.get(str(status), 0)) >= 1 and recovered, f"status_counts={status_counts} recovered={recovered}")

        network = f"{PROJECT}_parkio-nr-pilot"
        collector_id = compose("ps", "-q", "fluent-bit-nr-pilot", capture=True).stdout.strip()
        run([DOCKER, "network", "disconnect", network, collector_id])
        append("gateway-service", [("2026-09-21T12:02:00.000Z ERROR NR-log-pilot synthetic marker pilotMarker=p02-network-recovery", "2026-09-21T12:02:00.000000000Z", "stderr")])
        time.sleep(3)
        absent_during_disconnect = marker_count("p02-network-recovery") == 0
        run([DOCKER, "network", "connect", network, collector_id])
        wait_marker("p02-network-recovery", 45)
        add_result(results, "network_interruption_recovery", absent_during_disconnect)

        append("gateway-service", [("2026-09-21T12:05:00.000Z INFO NR-log-pilot synthetic marker pilotMarker=p02-checkpoint", "2026-09-21T12:05:00.000000000Z", "stdout")])
        wait_marker("p02-checkpoint", 45)
        before_restart = marker_count("p02-checkpoint")
        compose("restart", "fluent-bit-nr-pilot")
        wait_until(lambda: http_json("/api/v1/health", port=FLUENT_PORT)[0] == 200, 30, "collector restart health")
        time.sleep(5)
        after_restart = marker_count("p02-checkpoint")
        append("gateway-service", [("2026-09-21T12:05:01.000Z INFO NR-log-pilot synthetic marker pilotMarker=p02-after-restart", "2026-09-21T12:05:01.000000000Z", "stdout")])
        wait_marker("p02-after-restart", 45)
        add_result(results, "restart_checkpoint", before_restart == after_restart == 1, f"before={before_restart} after={after_restart}")

        append("auth-service", [("2026-09-21T12:06:00.000Z INFO NR-log-pilot synthetic marker pilotMarker=p02-before-rotate", "2026-09-21T12:06:00.000000000Z", "stdout")], filename="rotate-json.log")
        wait_marker("p02-before-rotate")
        helper("mv /dest/auth-service/rotate-json.log /dest/auth-service/rotate-json.log.1; : > /dest/auth-service/rotate-json.log")
        append("auth-service", [("2026-09-21T12:06:01.000Z INFO NR-log-pilot synthetic marker pilotMarker=p02-after-rotate", "2026-09-21T12:06:01.000000000Z", "stdout")], filename="rotate-json.log")
        wait_marker("p02-after-rotate")
        add_result(results, "rotation_checkpoint", marker_count("p02-before-rotate") == 1 and marker_count("p02-after-rotate") == 1)

        inject(503, persistent=True)
        large = "x" * 6144
        entries = [
            (f"2026-09-21T12:03:{i % 60:02d}.000Z INFO bounded backlog sequence={i} {large}", f"2026-09-21T12:03:{i % 60:02d}.{i % 1_000_000_000:09d}Z", "stdout")
            for i in range(3600)
        ]
        backpressure_source_bytes = sum(len(docker_entry(message, timestamp, stream).encode()) for message, timestamp, stream in entries)
        producer_seconds = append("parking-service", entries, filename="backpressure-json.log")
        time.sleep(12)
        state_probe = helper("du -sb /state | cut -f1", state=True, capture=True)
        state_bytes = int(state_probe.stdout.strip().splitlines()[-1])
        running = compose("ps", "--status", "running", "-q", "fluent-bit-nr-pilot", capture=True).stdout.strip() != ""
        add_result(results, "bounded_filesystem_buffer", running and backpressure_source_bytes > 20_000_000 and state_bytes <= 24_000_000, f"source_bytes={backpressure_source_bytes} state_bytes={state_bytes}")
        add_result(results, "source_write_not_blocked", producer_seconds < 15, f"producer_seconds={producer_seconds:.3f}")
        metrics.update(buffer_state_bytes=state_bytes, backpressure_source_bytes=backpressure_source_bytes, backpressure_source_write_seconds=round(producer_seconds, 3))
        inject(202)
        append("gateway-service", [("2026-09-21T12:04:00.000Z INFO NR-log-pilot synthetic marker pilotMarker=p02-buffer-recovery", "2026-09-21T12:04:00.000000000Z", "stdout")])
        wait_marker("p02-buffer-recovery", 90)
        add_result(results, "backpressure_recovery", True)

        final_records = dump_records()
        final_blob = json.dumps(final_records, separators=(",", ":"), sort_keys=True)
        final_stats = http_json("/stats")[1]
        record_sizes = [len(json.dumps(record, separators=(",", ":")).encode()) for record in final_records]
        metrics.update(
            exported_records=len(final_records),
            accepted_requests=int(final_stats.get("accepted", 0)),
            auth_header_names=final_stats.get("auth_header_names", {}),
            uncompressed_json_bytes=int(final_stats.get("json_bytes", 0)),
            mean_exported_record_bytes=round(sum(record_sizes) / len(record_sizes), 1) if record_sizes else 0,
            max_exported_record_bytes=max(record_sizes) if record_sizes else 0,
            duplicate_note="No duplicate in graceful restart/rotation sample; at-least-once delivery can duplicate after crash or lost acknowledgement.",
        )
        final_leaks = [value for value in canaries if value in final_blob]
        add_result(results, "no_prohibited_canary_outbound", not final_leaks, f"leaked={final_leaks}")

        budget_max = int(ENV["PARKIO_NR_BUDGET_BYTES"])
        budget_payload = [{"logs": [{"timestamp": 1789980000000, "message": "synthetic budget probe"}]}]
        budget_serialized = len(json.dumps(budget_payload).encode())
        reset_code, _ = gate_json(
            "/test/reset", method="POST",
            payload={"spent_bytes": budget_max - budget_serialized - 1},
        )
        healthy_one, _ = gate_json("/log/v1", method="POST", payload=budget_payload)
        healthy_two, healthy_two_body = gate_json("/log/v1", method="POST", payload=budget_payload)
        healthy_budget = gate_json("/stats")[1]
        add_result(
            results, "budget_exhaustion_healthy_delivery",
            reset_code == 200 and healthy_one == 202 and healthy_two == 202
            and bool(healthy_two_body.get("budget_exhausted"))
            and healthy_budget.get("exhausted") is True
            and int(healthy_budget.get("forwarded_attempts", -1)) == 1
            and int(healthy_budget.get("rejected_attempts", -1)) == 1
            and int(healthy_budget.get("spent_bytes", budget_max + 1)) <= budget_max,
            str(healthy_budget),
        )

        gate_json(
            "/test/reset", method="POST",
            payload={"spent_bytes": budget_max - (2 * budget_serialized) - 1},
        )
        inject(503, persistent=True)
        retry_statuses = [gate_json("/log/v1", method="POST", payload=budget_payload)[0] for _ in range(3)]
        retry_budget = gate_json("/stats")[1]
        add_result(
            results, "budget_exhaustion_retry_storm",
            retry_statuses == [503, 503, 202]
            and retry_budget.get("exhausted") is True
            and int(retry_budget.get("retry_attempts", 0)) >= 2
            and int(retry_budget.get("spent_bytes", budget_max + 1)) <= budget_max,
            f"statuses={retry_statuses} stats={retry_budget}",
        )
        inject(202)

        exhausted_before = gate_json("/stats")[1]
        compose("restart", "nr-budget-gate")
        wait_until(lambda: gate_json("/health")[0] == 507, 30, "persisted exhausted gate")
        exhausted_after = gate_json("/stats")[1]
        add_result(
            results, "budget_restart_fail_closed",
            exhausted_after.get("exhausted") is True
            and exhausted_after.get("spent_bytes") == exhausted_before.get("spent_bytes")
            and exhausted_after.get("attempts") == exhausted_before.get("attempts"),
            f"before={exhausted_before} after={exhausted_after}",
        )
        metrics["budget_gate_final"] = exhausted_after

    except Exception as exc:
        results["harness_exception"] = f"FAIL: {type(exc).__name__}: {exc}"
        logs = compose("logs", "--no-color", "--tail", "200", check=False, capture=True).stdout
        print(logs, file=sys.stderr)
    finally:
        compose("down", "-v", check=False)
        if tls_temp is not None:
            tls_temp.cleanup()

    print(json.dumps({"results": results, "measurements": metrics}, indent=2, sort_keys=True))
    return 1 if any(not value.startswith("PASS") for value in results.values()) else 0


if __name__ == "__main__":
    raise SystemExit(main())
