"""Typed, versioned Slack-biz internal events (contract v1)."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from enum import Enum
from typing import Any
import hashlib
import re
import uuid


CONTRACT_VERSION = 1

# Event types implemented in Y03
FAMILY_REGISTRATION = "registration.completed"
FAMILY_INCIDENT_OPEN = "incident.opened"
FAMILY_INCIDENT_RECOVERY = "incident.recovered"
FAMILY_BACKUP_LOCAL = "backup.local"
FAMILY_BACKUP_OFFSITE = "backup.offsite"

IMPLEMENTED_FAMILIES = frozenset(
    {
        FAMILY_REGISTRATION,
        FAMILY_INCIDENT_OPEN,
        FAMILY_INCIDENT_RECOVERY,
        FAMILY_BACKUP_LOCAL,
        FAMILY_BACKUP_OFFSITE,
    }
)

# Documented as future adapters (Y01 catalog) — not implemented here
DEFERRED_FAMILIES = frozenset(
    {
        "invite.issued",
        "invite.consumed",
        "first_value",
        "contribution.digest",
        "moderation.backlog",
        "municipal.stale",
        "media.storage_failure",
        "ci.release",
        "security.finding",
        "budget.threshold",
        "ops.daily_digest",
        "growth.daily_digest",
    }
)


class Severity(str, Enum):
    INFO = "info"
    WARNING = "warning"
    CRITICAL = "critical"


class BackupOutcome(str, Enum):
    SUCCESS = "success"
    FAILED = "failed"
    UNKNOWN = "unknown"
    NOT_OBSERVED = "not_observed"
    STALE = "stale"


@dataclass(frozen=True)
class SlackBizEvent:
    """Internal normalized event after validation."""

    event_id: str
    event_type: str
    contract_version: int
    environment: str
    occurred_at: str  # ISO-8601 UTC
    severity: str
    producer: str
    dedup_key: str
    route: str  # logical route name, never a raw channel id from payload
    service: str
    # Safe display fields only
    title: str
    body_lines: tuple[str, ...]
    # Correlation for incident open/recovery (opaque fingerprint)
    correlation_key: str | None = None
    # Pseudonymous subject (never email / raw UUID if avoidable — hashed)
    subject_ref: str | None = None
    # Extra structured safe context
    context: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["body_lines"] = list(self.body_lines)
        return d

    @staticmethod
    def from_dict(data: dict[str, Any]) -> "SlackBizEvent":
        return SlackBizEvent(
            event_id=str(data["event_id"]),
            event_type=str(data["event_type"]),
            contract_version=int(data["contract_version"]),
            environment=str(data["environment"]),
            occurred_at=str(data["occurred_at"]),
            severity=str(data["severity"]),
            producer=str(data["producer"]),
            dedup_key=str(data["dedup_key"]),
            route=str(data["route"]),
            service=str(data["service"]),
            title=str(data["title"]),
            body_lines=tuple(data.get("body_lines") or ()),
            correlation_key=data.get("correlation_key"),
            subject_ref=data.get("subject_ref"),
            context=dict(data.get("context") or {}),
        )


_UUID_RE = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)


def pseudonymize_user_id(user_id: str, *, salt: str = "parkio-slack-biz-v1") -> str:
    """Approved pseudonymous ID: short hash, not email / full UUID in Slack."""
    raw = f"{salt}:{user_id}".encode("utf-8")
    digest = hashlib.sha256(raw).hexdigest()
    return f"u_{digest[:12]}"


def new_event_id() -> str:
    return str(uuid.uuid4())


def is_uuid(value: str) -> bool:
    return bool(_UUID_RE.match(value or ""))
