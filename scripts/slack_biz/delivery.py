"""Delivery worker: claim → transport → ack/retry/DLT with backoff + jitter."""

from __future__ import annotations

import random
import time
from dataclasses import dataclass
from typing import Callable

from .config import SlackBizConfig
from .store import DeliveryStore, QueueItem
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
    ambiguous: int = 0
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

    def process_once(self, *, limit: int = 20) -> DeliveryStats:
        if not self.config.enabled:
            self.stats.skipped_disabled += 1
            return self.stats
        if not self.config.activation_ready():
            # Enabled but misconfigured: do not deliver; leave queue intact
            return self.stats

        items = self.store.claim_batch(worker_id=self.worker_id, limit=limit)
        for item in items:
            self._process_item(item)
        return self.stats

    def drain(self, *, max_loops: int = 50) -> DeliveryStats:
        for _ in range(max_loops):
            before = self.store.pending_count()
            self.process_once()
            after = self.store.pending_count()
            if after == 0:
                break
            if after >= before:
                # waiting on next_attempt_at — advance by sleeping smallest delay in tests
                # caller's responsibility to advance time via next_attempt manipulation
                break
        return self.stats

    def _process_item(self, item: QueueItem) -> None:
        webhook = self.config.webhook_for_route(item.event.route)
        if not webhook:
            self.store.mark_dead(item, reason="no_webhook_for_route")
            self.stats.dead += 1
            return

        text = render_message(item.event, self.config)
        payload = build_webhook_payload(text=text)
        result = self.transport.send(webhook, payload)

        if result.classification == TransportClass.SUCCESS:
            self.store.mark_delivered(item, ambiguous=False)
            self.stats.delivered += 1
            return

        if result.classification == TransportClass.AMBIGUOUS:
            # Do not retry identical dedup_key within retention — Slack may have accepted.
            self.store.mark_delivered(item, ambiguous=True)
            self.stats.ambiguous += 1
            return

        if result.classification in {
            TransportClass.PERMANENT,
            TransportClass.REJECTED,
        }:
            self.store.mark_dead(item, reason=result.detail)
            self.stats.dead += 1
            return

        # Transient / rate-limited
        attempts_after = item.attempts + 1
        if attempts_after >= self.config.max_attempts:
            self.store.mark_dead(item, reason=f"exhausted:{result.detail}")
            self.stats.dead += 1
            return

        delay = self._compute_delay(attempts_after, result.retry_after_seconds)
        self.store.mark_retry(item, error=result.detail, delay_seconds=delay)
        self.stats.retried += 1

    def _compute_delay(self, attempt: int, retry_after: float | None) -> float:
        if retry_after is not None and retry_after >= 0:
            # Honor Retry-After, add small jitter
            return float(retry_after) + self.rng.uniform(0, 0.5)
        base = min(60.0, (2 ** max(0, attempt - 1)))
        jitter = self.rng.uniform(0, base * 0.25)
        return base + jitter
