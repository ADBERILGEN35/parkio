"""Delivery worker: claim → transport (outside DB) → ack/retry/unknown/DLT.

Ambiguous outcomes (request may have been accepted, response lost) use a
bounded retry policy with documented duplicate risk. They are NEVER recorded
as confirmed delivery. After exhaustion → delivery_unknown (operator-visible).
"""

from __future__ import annotations

import random
import time
from dataclasses import dataclass
from typing import Callable

from .config import SlackBizConfig
from .store import DeliveryStore, QueueItem, WorkerLockError
from .templates import render_message
from .transport import (
    SlackWebhookTransport,
    TransportClass,
    build_webhook_payload,
)


@dataclass
class DeliveryStats:
    delivered: int = 0
    retried: int = 0
    dead: int = 0
    ambiguous_retried: int = 0
    delivery_unknown: int = 0
    skipped_disabled: int = 0


class DeliveryWorker:
    def __init__(
        self,
        config: SlackBizConfig,
        store: DeliveryStore,
        transport: SlackWebhookTransport | None = None,
        *,
        worker_id: str = "worker-1",
        rng: random.Random | None = None,
        sleep_fn: Callable[[float], None] | None = None,
        acquire_lock: bool = True,
    ):
        self.config = config
        self.store = store
        self.transport = transport or SlackWebhookTransport(
            timeout_seconds=config.http_timeout_seconds
        )
        self.worker_id = worker_id
        self.rng = rng or random.Random()
        self.sleep_fn = sleep_fn or time.sleep
        self.stats = DeliveryStats()
        self._lock_held = False
        if acquire_lock:
            self.store.acquire_worker_lock(self.worker_id)
            self._lock_held = True

    def close(self) -> None:
        if self._lock_held:
            try:
                self.store.release_worker_lock(self.worker_id)
            except Exception:
                pass
            self._lock_held = False

    def process_once(self, *, limit: int = 20) -> DeliveryStats:
        if not self.config.enabled:
            self.stats.skipped_disabled += 1
            return self.stats
        if not self.config.activation_ready():
            return self.stats

        if self._lock_held:
            self.store.heartbeat_worker_lock(self.worker_id)

        # Claim under short DB transaction; network I/O happens after return
        items = self.store.claim_batch(worker_id=self.worker_id, limit=limit)
        for item in items:
            self._process_item(item)
        return self.stats

    def _process_item(self, item: QueueItem) -> None:
        webhook = self.config.webhook_for_route(item.event.route)
        if not webhook:
            self.store.mark_dead(item, reason="no_webhook_for_route")
            self.stats.dead += 1
            return

        text = render_message(item.event, self.config)
        payload = build_webhook_payload(text=text)
        # Network I/O outside DB lock/transaction (claim already committed)
        result = self.transport.send(webhook, payload)

        if result.classification == TransportClass.SUCCESS:
            self.store.mark_delivered(item)
            self.stats.delivered += 1
            return

        if result.classification == TransportClass.AMBIGUOUS:
            self._handle_ambiguous(item, result.detail)
            return

        if result.classification in {
            TransportClass.PERMANENT,
            TransportClass.REJECTED,
        }:
            self.store.mark_dead(item, reason=result.detail)
            self.stats.dead += 1
            return

        # Transient / rate-limited — no duplicate risk beyond at-least-once
        attempts_after = item.attempts + 1
        if attempts_after >= self.config.max_attempts:
            self.store.mark_dead(item, reason=f"exhausted:{result.detail}")
            self.stats.dead += 1
            return

        delay = self._compute_delay(attempts_after, result.retry_after_seconds)
        self.store.mark_retry(item, error=result.detail, delay_seconds=delay)
        self.stats.retried += 1

    def _handle_ambiguous(self, item: QueueItem, detail: str) -> None:
        """
        Request potentially accepted; response lost.

        Bounded retry with duplicate risk: Slack may already have the message.
        Never classify as confirmed delivery. After max attempts → delivery_unknown.
        """
        attempts_after = item.attempts + 1
        if attempts_after >= self.config.max_attempts:
            self.store.mark_delivery_unknown(
                item,
                reason=f"ambiguous_exhausted:{detail}",
            )
            self.stats.delivery_unknown += 1
            return

        delay = self._compute_delay(
            attempts_after, self.config.ambiguous_retry_base_seconds
        )
        self.store.mark_retry(
            item,
            error=f"ambiguous:{detail}",
            delay_seconds=delay,
            ambiguous=True,
        )
        self.stats.ambiguous_retried += 1
        self.stats.retried += 1

    def _compute_delay(self, attempt: int, retry_after: float | None) -> float:
        if retry_after is not None and retry_after >= 0:
            return float(retry_after) + self.rng.uniform(0, 0.5)
        base = min(60.0, (2 ** max(0, attempt - 1)))
        jitter = self.rng.uniform(0, base * 0.25)
        return base + jitter


def try_create_worker(
    config: SlackBizConfig,
    store: DeliveryStore,
    *,
    worker_id: str,
    transport: SlackWebhookTransport | None = None,
) -> DeliveryWorker:
    """Create worker or raise WorkerLockError if another worker is active."""
    return DeliveryWorker(
        config, store, transport, worker_id=worker_id, acquire_lock=True
    )
