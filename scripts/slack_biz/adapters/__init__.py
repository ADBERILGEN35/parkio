"""Adapters: map authoritative sources → SlackBizEvent."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from ..config import SlackBizConfig
from ..events import (
    CONTRACT_VERSION,
    FAMILY_BACKUP_LOCAL,
    FAMILY_BACKUP_OFFSITE,
    FAMILY_INCIDENT_OPEN,
    FAMILY_INCIDENT_RECOVERY,
    FAMILY_REGISTRATION,
    BackupOutcome,
    Severity,
    SlackBizEvent,
    is_uuid,
    new_event_id,
    pseudonymize_user_id,
)
from ..safety import reject_event_supplied_destination, strip_sensitive_dict
from ..templates import route_for_event_type


def _iso_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _require_trusted(producer: str, config: SlackBizConfig) -> None:
    if producer not in config.trusted_producers:
        raise ValueError(f"untrusted producer: {producer}")


# ---------------------------------------------------------------------------
# Registration completed — authoritative UserRegistered Kafka/outbox envelope
# ---------------------------------------------------------------------------

def from_user_registered_envelope(
    envelope: dict[str, Any],
    config: SlackBizConfig,
    *,
    producer: str = "auth-outbox",
) -> SlackBizEvent:
    """
    Accept only committed UserRegistered transport envelopes.

    Distinguishes:
      - registration.completed  ← this adapter (post-commit outbox)
      - attempt / verification  ← NOT handled (no Slack from those)
    Email and raw user objects are stripped; subject is a pseudonym hash.
    """
    _require_trusted(producer, config)
    reject_event_supplied_destination(envelope)
    event_type = envelope.get("eventType") or envelope.get("event_type")
    if event_type != "UserRegistered":
        raise ValueError(
            f"not a completed registration event: {event_type!r} "
            "(attempts/verification must not use this adapter)"
        )
    payload = envelope.get("payload") or {}
    if not isinstance(payload, dict):
        raise ValueError("payload must be an object")
    reject_event_supplied_destination(payload)
    safe_payload = strip_sensitive_dict(payload)

    event_id = str(envelope.get("eventId") or envelope.get("event_id") or new_event_id())
    user_id = str(payload.get("userId") or payload.get("user_id") or "")
    if not user_id or not is_uuid(user_id):
        raise ValueError("UserRegistered requires UUID userId")
    # Explicitly refuse if email sneaks into our output context
    if "email" in safe_payload:
        safe_payload.pop("email", None)

    occurred = str(
        envelope.get("occurredAt")
        or envelope.get("occurred_at")
        or payload.get("occurredAt")
        or _iso_now()
    )
    subject = pseudonymize_user_id(user_id)
    dedup = f"biz:UserRegistered:{event_id}"
    route = route_for_event_type(FAMILY_REGISTRATION, config)

    return SlackBizEvent(
        event_id=event_id,
        event_type=FAMILY_REGISTRATION,
        contract_version=CONTRACT_VERSION,
        environment=config.environment,
        occurred_at=occurred,
        severity=Severity.INFO.value,
        producer=producer,
        dedup_key=dedup,
        route=route,
        service="auth-service",
        title="Registration completed",
        body_lines=(
            "outcome=`completed`",
            "source=`auth-outbox/UserRegistered`",
        ),
        correlation_key=None,
        subject_ref=subject,
        context={"aggregate_type": "AuthUser"},
    )


def refuse_non_completion(event_type: str) -> None:
    """Guard: registration attempt / verification must not enqueue completion Slack."""
    blocked = {
        "RegistrationAttempted",
        "RegistrationFailed",
        "EmailVerificationRequested",
        "EmailVerified",
        "UserRegisteredAttempt",
    }
    if event_type in blocked:
        raise ValueError(f"refused non-completion registration signal: {event_type}")


# ---------------------------------------------------------------------------
# Incident open / recovery — internal contract (synthetic producer OK)
# ---------------------------------------------------------------------------

def from_incident_event(
    raw: dict[str, Any],
    config: SlackBizConfig,
    *,
    producer: str = "incident-adapter",
) -> SlackBizEvent:
    """
    Internal incident contract.

    Required: fingerprint, service, phase in {opened, recovered}, occurred_at.
    Grouping key = fingerprint. Recovery is an EXPLICIT signal — never inferred
    from log silence.

    Production producer gap: no runtime AM→biz fan-in wired yet; use synthetic
    / adapter enqueue until an explicit producer exists. Alertmanager retains
    ownership of existing metric alerts.
    """
    _require_trusted(producer, config)
    reject_event_supplied_destination(raw)
    phase = str(raw.get("phase") or "").lower()
    if phase not in {"opened", "recovered"}:
        raise ValueError("incident phase must be opened|recovered")
    fingerprint = str(raw.get("fingerprint") or "").strip()
    if not fingerprint or len(fingerprint) > 128:
        raise ValueError("fingerprint required (1..128)")
    service = str(raw.get("service") or "unknown-service")
    severity = str(raw.get("severity") or Severity.WARNING.value)
    occurred = str(raw.get("occurred_at") or _iso_now())
    count = raw.get("count")
    error_code = raw.get("error_code")
    event_id = str(raw.get("event_id") or new_event_id())

    if phase == "opened":
        etype = FAMILY_INCIDENT_OPEN
        title = f"INC open — {service}"
        dedup = f"incident:{service}:{fingerprint}:open"
        body = [
            f"fingerprint=`{fingerprint}`",
            "signal=`explicit_open`",
        ]
    else:
        etype = FAMILY_INCIDENT_RECOVERY
        title = f"INC recovered — {service}"
        dedup = f"incident:{service}:{fingerprint}:recovered"
        body = [
            f"fingerprint=`{fingerprint}`",
            "signal=`explicit_recovery`",
            "note=`correlated message (webhook has no thread update)`",
        ]

    if count is not None:
        body.append(f"count=`{int(count)}`")
    if error_code:
        body.append(f"error_code=`{error_code}`")

    route = route_for_event_type(etype, config)
    return SlackBizEvent(
        event_id=event_id,
        event_type=etype,
        contract_version=CONTRACT_VERSION,
        environment=config.environment,
        occurred_at=occurred,
        severity=severity,
        producer=producer,
        dedup_key=dedup,
        route=route,
        service=service,
        title=title,
        body_lines=tuple(body),
        correlation_key=f"{service}:{fingerprint}",
        subject_ref=None,
        context=strip_sensitive_dict({"phase": phase}),
    )


# ---------------------------------------------------------------------------
# Backup local / offsite — separate events; never collapse
# ---------------------------------------------------------------------------

def from_backup_status(
    raw: dict[str, Any],
    config: SlackBizConfig,
    *,
    producer: str = "backup-script",
) -> list[SlackBizEvent]:
    """
    Emit separate events for local and offsite scopes.

    local success MUST NOT imply offsite success.
    Missing evidence → UNKNOWN / NOT_OBSERVED.
    Distinguish failed upload vs stale/missing freshness.
    """
    _require_trusted(producer, config)
    reject_event_supplied_destination(raw)
    scope = str(raw.get("scope") or "default")
    date = str(raw.get("date") or datetime.now(timezone.utc).strftime("%Y-%m-%d"))
    occurred = str(raw.get("occurred_at") or _iso_now())
    events: list[SlackBizEvent] = []

    def _one(
        family: str,
        outcome_key: str,
        reason_key: str,
        label: str,
    ) -> SlackBizEvent:
        raw_outcome = raw.get(outcome_key)
        if raw_outcome is None or raw_outcome == "":
            outcome = BackupOutcome.NOT_OBSERVED.value
        else:
            outcome = str(raw_outcome).lower()
            allowed = {o.value for o in BackupOutcome}
            if outcome not in allowed:
                raise ValueError(f"invalid {outcome_key}: {outcome}")
        reason = str(raw.get(reason_key) or "")
        # Map reason semantics
        if outcome == BackupOutcome.FAILED.value and reason == "stale":
            body_reason = "reason=`stale_freshness`"
        elif outcome == BackupOutcome.FAILED.value and reason == "upload":
            body_reason = "reason=`upload_failed`"
        elif outcome == BackupOutcome.NOT_OBSERVED.value:
            body_reason = "reason=`not_observed`"
        elif outcome == BackupOutcome.UNKNOWN.value:
            body_reason = "reason=`unknown`"
        elif reason:
            body_reason = f"reason=`{reason}`"
        else:
            body_reason = "reason=`n/a`"

        severity = (
            Severity.INFO.value
            if outcome == BackupOutcome.SUCCESS.value
            else Severity.WARNING.value
            if outcome
            in {
                BackupOutcome.FAILED.value,
                BackupOutcome.STALE.value,
            }
            else Severity.INFO.value
        )
        event_id = str(raw.get(f"{label}_event_id") or new_event_id())
        dedup = f"backup:{label}:{scope}:{date}:{outcome}"
        route = route_for_event_type(family, config)
        return SlackBizEvent(
            event_id=event_id,
            event_type=family,
            contract_version=CONTRACT_VERSION,
            environment=config.environment,
            occurred_at=occurred,
            severity=severity,
            producer=producer,
            dedup_key=dedup,
            route=route,
            service="backup",
            title=f"backup.{label}={outcome}",
            body_lines=(
                f"scope=`{scope}`",
                f"date=`{date}`",
                f"outcome=`{outcome}`",
                body_reason,
                "note=`local and offsite are independent`",
            ),
            correlation_key=f"backup:{scope}:{date}",
            subject_ref=None,
            context={"scope": scope, "date": date, "plane": label},
        )

    # Always emit both planes when requested; callers may pass only one via emit_planes
    planes = raw.get("emit_planes") or ["local", "offsite"]
    if "local" in planes:
        events.append(
            _one(FAMILY_BACKUP_LOCAL, "local_outcome", "local_reason", "local")
        )
    if "offsite" in planes:
        events.append(
            _one(FAMILY_BACKUP_OFFSITE, "offsite_outcome", "offsite_reason", "offsite")
        )
    return events
