"""Templates and routing for Slack biz messages."""

from __future__ import annotations

from .config import SlackBizConfig
from .events import (
    FAMILY_BACKUP_LOCAL,
    FAMILY_BACKUP_OFFSITE,
    FAMILY_INCIDENT_OPEN,
    FAMILY_INCIDENT_RECOVERY,
    FAMILY_REGISTRATION,
    FAMILY_WAITLIST_CONFIRMED,
    SlackBizEvent,
)
from .safety import sanitize_text


ROUTE_BY_TYPE = {
    FAMILY_REGISTRATION: "biz-growth",
    FAMILY_INCIDENT_OPEN: "ops-alerts",
    FAMILY_INCIDENT_RECOVERY: "ops-alerts",
    FAMILY_BACKUP_LOCAL: "ops-alerts",
    FAMILY_BACKUP_OFFSITE: "ops-alerts",
    FAMILY_WAITLIST_CONFIRMED: "biz-growth",
}


def route_for_event_type(event_type: str, config: SlackBizConfig) -> str:
    logical = ROUTE_BY_TYPE.get(event_type, "ops-alerts")
    if logical == "biz-growth":
        return config.route_biz
    return config.route_ops


def render_message(event: SlackBizEvent, config: SlackBizConfig) -> str:
    lines = [
        f"*{sanitize_text(event.title)}*",
        f"type=`{sanitize_text(event.event_type)}` severity=`{sanitize_text(event.severity)}`",
        f"env=`{sanitize_text(event.environment)}` service=`{sanitize_text(event.service)}`",
        f"at=`{sanitize_text(event.occurred_at)}`",
    ]
    if event.subject_ref:
        lines.append(f"subject=`{sanitize_text(event.subject_ref)}`")
    if event.correlation_key:
        lines.append(f"fingerprint=`{sanitize_text(event.correlation_key)}`")
    for body in event.body_lines:
        lines.append(sanitize_text(body))
    # Diagnostic link only when a real base URL is configured
    if config.diagnostic_base_url and event.correlation_key:
        base = config.diagnostic_base_url.rstrip("/")
        # No credentials / query tokens; opaque fingerprint only
        lines.append(f"diag: {base}/fingerprint/{sanitize_text(event.correlation_key)}")
    return "\n".join(lines)
