"""Templates and routing for Slack biz messages."""

from __future__ import annotations

from datetime import datetime, timezone
from zoneinfo import ZoneInfo

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

_ISTANBUL = None  # resolved lazily in _format_istanbul
_ADMIN_WAITLIST_URL = "https://app.parkio.dev/admin/waitlist"


def route_for_event_type(event_type: str, config: SlackBizConfig) -> str:
    logical = ROUTE_BY_TYPE.get(event_type, "ops-alerts")
    if logical == "biz-growth":
        return config.route_biz
    return config.route_ops


def _format_istanbul(iso_utc: str) -> str:
    """Render occurred_at as Türkiye saati (Europe/Istanbul)."""
    try:
        raw = iso_utc.rstrip("Z")
        dt = datetime.fromisoformat(raw).replace(tzinfo=timezone.utc)
        try:
            local = dt.astimezone(ZoneInfo("Europe/Istanbul"))
        except Exception:
            # Fallback when tzdata is unavailable (rare Windows/minimal images).
            from datetime import timedelta

            local = dt.astimezone(timezone(timedelta(hours=3)))
        return local.strftime("%Y-%m-%d %H:%M") + " (Türkiye saati)"
    except Exception:
        return sanitize_text(iso_utc)


def _waitlist_display_name(event: SlackBizEvent) -> str | None:
    """Allowlisted name only. Dual-read queued context + body for older rows."""
    ctx = event.context or {}
    for key in ("waitlist_display_name", "full_name"):
        raw = ctx.get(key)
        if isinstance(raw, str) and raw.strip():
            return raw.strip()
    for line in event.body_lines or ():
        if not isinstance(line, str) or not line.startswith("name="):
            continue
        value = line[5:].strip()
        if value and value != "Ad belirtilmemiş":
            return value
    return None


def render_waitlist_message(event: SlackBizEvent, config: SlackBizConfig) -> str:
    """Readable waitlist confirmation — name, counts, time, admin link only."""
    ctx = event.context or {}
    display_name = _waitlist_display_name(event)
    if display_name:
        name_line = f"Ad soyad: {sanitize_text(display_name)}"
    else:
        name_line = "Ad soyad: Ad belirtilmemiş"

    lines = [
        sanitize_text(event.title),
        name_line,
    ]

    total = ctx.get("confirmed_total")
    today = ctx.get("confirmed_today_istanbul")
    if isinstance(total, int) and isinstance(today, int):
        lines.append(f"Bugün onaylanan: {today} kişi")
        lines.append(f"Toplam onaylı: {total} kişi")

    lines.append(f"Onay zamanı: {_format_istanbul(event.occurred_at)}")
    lines.append(f"Bekleme listesini aç → {_ADMIN_WAITLIST_URL}")

    if isinstance(total, int) and isinstance(today, int):
        snap = ctx.get("counts_snapshot_at")
        if isinstance(snap, str) and snap.strip():
            lines.append(f"Sayımlar: {_format_istanbul(snap)} itibarıyla")

    if event.environment and event.environment != "production":
        lines.append(f"Ortam: `{sanitize_text(event.environment)}` (üretim dışı)")

    return "\n".join(lines)


def render_message(event: SlackBizEvent, config: SlackBizConfig) -> str:
    if event.event_type == FAMILY_WAITLIST_CONFIRMED:
        return render_waitlist_message(event, config)

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
