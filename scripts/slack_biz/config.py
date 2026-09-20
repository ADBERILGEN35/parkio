"""Configuration for the Slack business notification relay.

External destinations come ONLY from controlled config — never from event
payload fields (channel IDs / webhook URLs in events are rejected).
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _truthy(value: str | None) -> bool:
    if value is None:
        return False
    return value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class SlackBizConfig:
    """Runtime configuration. All Slack destinations are config-owned."""

    enabled: bool
    environment: str
    webhook_url: str | None
    # Optional per-route webhooks; fall back to webhook_url
    webhook_biz: str | None
    webhook_ops: str | None
    data_dir: Path
    max_attempts: int
    http_timeout_seconds: float
    dedup_retention_hours: int
    # Hard-coded approved route names (not Slack channel IDs from events)
    route_biz: str
    route_ops: str
    # Diagnostic link base (optional; empty = no links rendered)
    diagnostic_base_url: str | None
    # Trusted producer allow-list
    trusted_producers: frozenset[str]
    # Never inherit Alertmanager webhook accidentally in tests
    forbid_alertmanager_webhook: bool
    # Ambiguous timeout: base delay before duplicate-risk retry
    ambiguous_retry_base_seconds: float
    # Lease / single-worker
    lease_seconds: float
    worker_stale_seconds: float
    # Kafka registration consumer (optional)
    kafka_bootstrap: str | None
    kafka_topic: str
    kafka_group: str
    kafka_auto_offset_reset: str
    # File inbox for registration envelopes (local/CI)
    registration_inbox_dir: Path | None

    @property
    def db_path(self) -> Path:
        return self.data_dir / "slack_biz.sqlite3"

    @property
    def dlt_path(self) -> Path:
        return self.data_dir / "dlt"

    @property
    def metrics_path(self) -> Path:
        return self.data_dir / "metrics.jsonl"

    def webhook_for_route(self, route: str) -> str | None:
        if route == "biz-growth":
            return self.webhook_biz or self.webhook_url
        if route == "ops-alerts":
            return self.webhook_ops or self.webhook_url
        return self.webhook_url

    def activation_ready(self) -> bool:
        """Enabled AND at least one destination configured."""
        if not self.enabled:
            return False
        return bool(self.webhook_url or self.webhook_biz or self.webhook_ops)


def load_config(environ: dict[str, str] | None = None) -> SlackBizConfig:
    env = environ if environ is not None else dict(os.environ)
    data_dir = Path(
        env.get("PARKIO_SLACK_BIZ_DATA_DIR")
        or env.get("PARKIO_SLACK_BIZ_STATE_DIR")
        or ".parkio/slack-biz"
    )
    trusted = env.get(
        "PARKIO_SLACK_BIZ_TRUSTED_PRODUCERS",
        "auth-outbox,backup-script,incident-adapter,acceptance-harness",
    )
    return SlackBizConfig(
        enabled=_truthy(env.get("PARKIO_SLACK_BIZ_ENABLED")),
        environment=env.get("PARKIO_ENVIRONMENT")
        or env.get("PARKIO_SLACK_BIZ_ENVIRONMENT")
        or "local",
        webhook_url=(env.get("PARKIO_SLACK_BIZ_WEBHOOK_URL") or "").strip() or None,
        webhook_biz=(env.get("PARKIO_SLACK_BIZ_WEBHOOK_URL_BIZ") or "").strip() or None,
        webhook_ops=(env.get("PARKIO_SLACK_BIZ_WEBHOOK_URL_OPS") or "").strip() or None,
        data_dir=data_dir,
        max_attempts=int(env.get("PARKIO_SLACK_BIZ_MAX_ATTEMPTS", "5")),
        http_timeout_seconds=float(env.get("PARKIO_SLACK_BIZ_HTTP_TIMEOUT", "5")),
        dedup_retention_hours=int(env.get("PARKIO_SLACK_BIZ_DEDUP_RETENTION_HOURS", "168")),
        route_biz=env.get("PARKIO_SLACK_BIZ_ROUTE_BIZ", "biz-growth"),
        route_ops=env.get("PARKIO_SLACK_BIZ_ROUTE_OPS", "ops-alerts"),
        diagnostic_base_url=(env.get("PARKIO_SLACK_BIZ_DIAGNOSTIC_BASE_URL") or "").strip()
        or None,
        trusted_producers=frozenset(p.strip() for p in trusted.split(",") if p.strip()),
        forbid_alertmanager_webhook=_truthy(
            env.get("PARKIO_SLACK_BIZ_FORBID_ALERTMANAGER_WEBHOOK", "1")
        ),
        ambiguous_retry_base_seconds=float(
            env.get("PARKIO_SLACK_BIZ_AMBIGUOUS_RETRY_BASE", "2")
        ),
        lease_seconds=float(env.get("PARKIO_SLACK_BIZ_LEASE_SECONDS", "30")),
        worker_stale_seconds=float(
            env.get("PARKIO_SLACK_BIZ_WORKER_STALE_SECONDS", "60")
        ),
        kafka_bootstrap=(env.get("PARKIO_SLACK_BIZ_KAFKA_BOOTSTRAP") or "").strip()
        or None,
        kafka_topic=env.get("PARKIO_SLACK_BIZ_KAFKA_TOPIC", "parkio.auth.user"),
        kafka_group=env.get(
            "PARKIO_SLACK_BIZ_KAFKA_GROUP", "parkio-slack-biz-registration"
        ),
        # earliest only for disposable/local; production must use latest or committed offsets
        kafka_auto_offset_reset=env.get(
            "PARKIO_SLACK_BIZ_KAFKA_AUTO_OFFSET_RESET", "latest"
        ),
        registration_inbox_dir=(
            Path(env["PARKIO_SLACK_BIZ_REGISTRATION_INBOX"])
            if env.get("PARKIO_SLACK_BIZ_REGISTRATION_INBOX")
            else None
        ),
    )
