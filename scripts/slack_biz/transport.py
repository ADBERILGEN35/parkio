"""Slack incoming-webhook transport.

Incoming webhooks support posting text/attachments only — not chat.update /
thread replies that require bot tokens. Recovery correlation uses a separate
message referencing the same fingerprint (thread update = DEFERRED).
"""

from __future__ import annotations

import json
import socket
import urllib.error
import urllib.request
from dataclasses import dataclass
from enum import Enum
from typing import Any
from urllib.parse import urlparse


class TransportClass(str, Enum):
    SUCCESS = "success"
    TRANSIENT = "transient"
    PERMANENT = "permanent"
    RATE_LIMITED = "rate_limited"
    AMBIGUOUS = "ambiguous"
    REJECTED = "rejected"


@dataclass(frozen=True)
class TransportResult:
    classification: TransportClass
    http_status: int | None
    retry_after_seconds: float | None
    detail: str
    # True when we may have posted but lost the response
    ambiguous: bool = False


class SlackWebhookTransport:
    """HTTP POST to a configured incoming webhook URL."""

    def __init__(self, *, timeout_seconds: float = 5.0, opener=None):
        self.timeout_seconds = timeout_seconds
        self._opener = opener  # injectable for tests

    def send(self, webhook_url: str, payload: dict[str, Any]) -> TransportResult:
        parsed = urlparse(webhook_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            return TransportResult(
                TransportClass.PERMANENT,
                None,
                None,
                "invalid_webhook_url",
            )
        body = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            webhook_url,
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "User-Agent": "parkio-slack-biz/1.0",
            },
        )
        try:
            if self._opener is not None:
                resp = self._opener.open(req, timeout=self.timeout_seconds)
            else:
                resp = urllib.request.urlopen(req, timeout=self.timeout_seconds)
            with resp:
                status = getattr(resp, "status", None) or resp.getcode()
                raw = resp.read(4096).decode("utf-8", errors="replace")
            return self._classify_success(status, raw)
        except urllib.error.HTTPError as exc:
            raw = ""
            try:
                raw = exc.read(4096).decode("utf-8", errors="replace")
            except Exception:
                pass
            retry_after = self._parse_retry_after(exc.headers)
            return self._classify_http_error(exc.code, raw, retry_after)
        except (TimeoutError, socket.timeout) as exc:
            # Ambiguous: Slack may have accepted the message before we timed out.
            return TransportResult(
                TransportClass.AMBIGUOUS,
                None,
                None,
                f"timeout:{exc}",
                ambiguous=True,
            )
        except urllib.error.URLError as exc:
            reason = str(getattr(exc, "reason", exc))
            # Connection refused / DNS → transient
            return TransportResult(
                TransportClass.TRANSIENT,
                None,
                None,
                f"url_error:{reason}",
            )
        except OSError as exc:
            return TransportResult(
                TransportClass.TRANSIENT,
                None,
                None,
                f"os_error:{exc}",
            )

    @staticmethod
    def _parse_retry_after(headers) -> float | None:
        if headers is None:
            return None
        value = headers.get("Retry-After") if hasattr(headers, "get") else None
        if value is None:
            return None
        try:
            return float(value)
        except ValueError:
            return None

    @staticmethod
    def _classify_success(status: int, raw: str) -> TransportResult:
        # Incoming webhooks return "ok" body with 200 on success.
        if status == 200 and raw.strip().lower() in {"ok", ""}:
            return TransportResult(TransportClass.SUCCESS, status, None, "ok")
        if status == 200:
            # Non-ok body on 200 is still treated as success for classic webhooks
            # that return empty; if body is explicit error JSON treat as rejected.
            lowered = raw.strip().lower()
            if "invalid" in lowered or "error" in lowered:
                return TransportResult(
                    TransportClass.REJECTED, status, None, f"body:{raw[:200]}"
                )
            return TransportResult(TransportClass.SUCCESS, status, None, raw[:200] or "ok")
        return TransportResult(
            TransportClass.TRANSIENT, status, None, f"unexpected_status:{status}"
        )

    @staticmethod
    def _classify_http_error(
        status: int, raw: str, retry_after: float | None
    ) -> TransportResult:
        if status == 429:
            return TransportResult(
                TransportClass.RATE_LIMITED,
                status,
                retry_after if retry_after is not None else 30.0,
                f"rate_limited:{raw[:200]}",
            )
        if status in {408, 500, 502, 503, 504}:
            return TransportResult(
                TransportClass.TRANSIENT, status, retry_after, f"http_{status}:{raw[:200]}"
            )
        # 400 invalid_payload, 403/404 channel/token issues → permanent / rejected
        if status in {400, 401, 403, 404, 410}:
            return TransportResult(
                TransportClass.PERMANENT, status, None, f"http_{status}:{raw[:200]}"
            )
        return TransportResult(
            TransportClass.TRANSIENT, status, retry_after, f"http_{status}:{raw[:200]}"
        )


def build_webhook_payload(*, text: str, username: str = "Parkio") -> dict[str, Any]:
    """Minimal incoming-webhook payload. No channel override (webhook-bound)."""
    return {
        "text": text,
        "username": username,
        # mrkdwn enabled for monospace fields; untrusted text already escaped
        "mrkdwn": True,
    }
