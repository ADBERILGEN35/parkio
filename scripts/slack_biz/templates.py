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


def render_waitlist_message(event: SlackBizEvent, config: SlackBizConfig) -> str:
    """Readable waitlist confirmation — no cluttered type/severity/service lines."""
    ctx = event.context or {}
    full_name = ctx.get("full_name")
    if isinstance(full_name, str) and full_name.strip():
        name_line = f"Ad soyad: {sanitize_text(full_name.strip())}"
    else:
        name_line = "Ad soyad: Ad belirtilmemiş"

    lines = [
        f"*{sanitize_text(event.title)}*",
        name_line,
        f"Onay zamanı: {_format_istanbul(event.occurred_at)}",
        f"Yönetim: {_ADMIN_WAITLIST_URL}",
    ]

    total = ctx.get("confirmed_total")
    today = ctx.get("confirmed_today_istanbul")
    if isinstance(total, int) and isinstance(today, int):
        lines.append(
            f"Anlık özet (veritabanı anlık görüntüsü): onaylı toplam={total}, "
            f"bugün (İstanbul günü)={today}"
        )
        lines.append("_Sayımlar olay anına kilitli değildir; her gönderimde yeniden okunur._")

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
