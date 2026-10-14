"""Event-level Slack / gateway reconciliation. No delivery, no replay.

Backup identity and aggregate counts are not sufficient. Equal-size event
sets can still be disjoint. delivery_unknown and in_flight are ambiguous
and must never be automatically replayed.
"""
from __future__ import annotations

DEDUP_HOURS = 168
AMBIGUOUS_STATUSES = frozenset({"delivery_unknown", "in_flight"})
DUPLICATE_WINDOWS = (
    "gateway export and Slack snapshot are not one atomic cut",
    "queue admission before inbox ack can duplicate the same eventId",
    f"dedup expires after {DEDUP_HOURS}h by default; a later replay can duplicate Slack messages",
    "HTTP success after the snapshot may be absent from the captured queue",
)
LOSS_WINDOWS = (
    "an event committed to the outbox after the Slack snapshot is not in this archive",
    "HTTP timeout or crash after Slack accepted a message can leave delivery_unknown",
    "in_flight rows are not proof of delivery or of loss",
    "gateway outbox retention can drop rows that Slack still holds",
)


class ReconciliationError(ValueError):
    """Event-level reconciliation cannot produce a safe automatic action."""


def _ids(values):
    return [item for item in values if isinstance(item, str) and item]


def reconcile_slack_events(gateway_ids, queue_rows, pending_ids=(), acked_ids=(),
                          dedup_rows=None):
    """Classify each eventId. Never returns an automatic replay list.

    queue_rows: iterable of (event_id, dedup_key, status)
    dedup_rows: optional iterable of (event_id, dedup_key) from the dedup table
    """
    gateway = _ids(gateway_ids)
    if len(gateway) != len(set(gateway)):
        raise ReconciliationError("duplicate gateway event IDs")
    gateway_set = set(gateway)

    queue = list(queue_rows)
    queue_ids = [row[0] for row in queue]
    if any(not isinstance(event_id, str) or not event_id for event_id in queue_ids):
        raise ReconciliationError("Slack queue event IDs must be non-empty strings")
    if len(queue_ids) != len(set(queue_ids)):
        raise ReconciliationError("duplicate Slack queue event IDs")
    queue_set = set(queue_ids)

    pending = set(_ids(pending_ids))
    acked = set(_ids(acked_ids))
    slack_set = queue_set | pending | acked

    missing = sorted(gateway_set - slack_set)
    extra = sorted(slack_set - gateway_set)
    pending_already_queued = sorted(pending & queue_set)
    delivery_unknown = sorted(row[0] for row in queue if row[2] == "delivery_unknown")
    in_flight = sorted(row[0] for row in queue if row[2] == "in_flight")
    delivered = {row[0] for row in queue if row[2] == "delivered"}
    conflicting_states = sorted((pending & delivered) | (acked & set(delivery_unknown + in_flight)))
    dedup = {}
    for event_id, key in (dedup_rows or ()):
        prior = dedup.get(key)
        if prior is not None and prior != event_id:
            raise ReconciliationError("Slack queue/dedup conflict; manual forensic review required")
        dedup[key] = event_id
    collisions = sum(1 for event_id, key, _status in queue if key in dedup and dedup[key] != event_id)
    if collisions:
        raise ReconciliationError("Slack queue/dedup conflict; manual forensic review required")

    replay_refused = sorted(set(delivery_unknown) | set(in_flight) | set(conflicting_states))
    return {
        "identity_and_counts_sufficient": False,
        "equal_count_different_sets": len(gateway_set) == len(slack_set) and gateway_set != slack_set,
        "counts": {
            "gateway_without_queue_or_inbox": len(missing),
            "slack_without_gateway": len(extra),
            "pending_inbox_already_queued": len(pending_already_queued),
            "dedup_conflicts": 0,
            "delivery_unknown": len(delivery_unknown),
            "in_flight": len(in_flight),
            "delivered": sum(1 for _event, _key, status in queue if status == "delivered"),
            "conflicting_states": len(conflicting_states),
        },
        "event_ids": {
            "gateway_without_queue_or_inbox": missing,
            "slack_without_gateway": extra,
            "pending_inbox_already_queued": pending_already_queued,
            "delivery_unknown": delivery_unknown,
            "in_flight": in_flight,
            "conflicting_states": conflicting_states,
        },
        "auto_replay": [],
        "replay_refused": replay_refused,
        "replay_policy": "never-automatic; delivery-ambiguous events require manual review",
        "limits": {
            "dedup_hours": DEDUP_HOURS,
            "atomic": False,
            "duplicate_windows": list(DUPLICATE_WINDOWS),
            "loss_windows": list(LOSS_WINDOWS),
        },
    }


def assert_no_automatic_replay(report):
    if report.get("auto_replay"):
        raise ReconciliationError("automatic Slack replay is refused")
    if report.get("replay_policy", "").startswith("auto"):
        raise ReconciliationError("automatic Slack replay is refused")
