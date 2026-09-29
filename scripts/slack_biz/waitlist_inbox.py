"""Gateway waitlist ops inbox → durable Slack-biz queue.

Privacy rules (the inbox directory is an untrusted input boundary):
  * rejections are reported as a bounded category metric + log line only —
    never file names, JSON key names or values;
  * rejected files are deleted by default; opt-in forensic retention keeps them
    0600 under a hashed name and prunes them after a bounded period;
  * acked files are pruned after a bounded period (the durable queue already
    holds the event);
  * a file is acked only after durable enqueue (or deterministic dedup
    suppression); a queue write failure leaves it in place for the next poll;
  * backpressure: at max_pending queued rows or below min_free_mb free space
    the poll admits nothing — files stay in the inbox (the gateway then stops
    exporting and keeps rows PENDING). Pending work is never deleted.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import shutil
import time
from dataclasses import dataclass
from pathlib import Path

from .adapters import WAITLIST_PRODUCER, WaitlistEnvelopeRejected, from_waitlist_ops_envelope
from .config import SlackBizConfig
from .store import DeliveryStore

log = logging.getLogger("parkio.slack_biz.waitlist_inbox")

MAX_ENVELOPE_BYTES = 4096
REJECTED_METRIC = "slack_biz_waitlist_rejected_total"
DEFERRED_METRIC = "slack_biz_waitlist_consume_deferred_total"


@dataclass
class WaitlistPollResult:
    processed: int = 0
    enqueued: int = 0
    suppressed: int = 0
    rejected: int = 0
    write_failures: int = 0
    acked: int = 0
    deferred: str | None = None


class WaitlistInboxConsumer:
    def __init__(
        self,
        config: SlackBizConfig,
        store: DeliveryStore,
        inbox: Path,
        *,
        max_files_per_poll: int = 200,
    ):
        self.config = config
        self.store = store
        self.inbox = Path(inbox)
        self.acked = self.inbox / ".acked"
        self.invalid = self.inbox / ".invalid"
        self.max_files_per_poll = max_files_per_poll
        self._last_deferral: str | None = None
        self.acked.mkdir(parents=True, exist_ok=True)
        if config.waitlist_retain_rejected:
            self.invalid.mkdir(parents=True, exist_ok=True)
            _chmod(self.invalid, 0o700)

    def poll_once(self) -> WaitlistPollResult:
        result = WaitlistPollResult()
        deferral = self._backpressure()
        if deferral:
            result.deferred = deferral
            self.store.bump_metric(DEFERRED_METRIC, {"reason": deferral})
            if deferral != self._last_deferral:
                log.warning("waitlist consume deferred reason=%s (files kept in inbox)", deferral)
            self._last_deferral = deferral
            return result
        if self._last_deferral:
            log.info("waitlist consume resumed after deferral reason=%s", self._last_deferral)
            self._last_deferral = None
        for path in sorted(self.inbox.glob("*.json"))[: self.max_files_per_poll]:
            if not path.is_file():
                continue
            result.processed += 1
            try:
                event = from_waitlist_ops_envelope(
                    self._read(path), self.config, producer=WAITLIST_PRODUCER
                )
            except WaitlistEnvelopeRejected as exc:
                result.rejected += 1
                self._reject(path, exc.category)
                continue
            try:
                outcome = self.store.enqueue(event)
            except OSError:
                result.write_failures += 1
                log.error("waitlist queue write failed; envelope left in inbox for retry")
                continue
            if outcome == "queued":
                result.enqueued += 1
            else:
                result.suppressed += 1
            self._ack(path)
            result.acked += 1
        return result

    def prune(self, now: float | None = None) -> dict[str, int]:
        now = time.time() if now is None else now
        removed = {"acked": 0, "rejected": 0}
        removed["acked"] = _prune_dir(
            self.acked, now - self.config.waitlist_acked_retention_hours * 3600
        )
        if self.invalid.exists():
            # Also clears anything left behind if retention was switched off.
            cutoff = (
                now - self.config.waitlist_rejected_retention_hours * 3600
                if self.config.waitlist_retain_rejected
                else now + 1
            )
            removed["rejected"] = _prune_dir(self.invalid, cutoff)
        return removed

    # ------------------------------------------------------------------ helpers
    def _backpressure(self) -> str | None:
        if self.store.pending_count() >= self.config.max_pending:
            return "queue_full"
        try:
            free = shutil.disk_usage(self.config.data_dir).free
        except OSError:
            return None
        if free < self.config.min_free_mb * 1024 * 1024:
            return "low_disk"
        return None

    @staticmethod
    def _read(path: Path) -> object:
        try:
            if path.stat().st_size > MAX_ENVELOPE_BYTES:
                raise WaitlistEnvelopeRejected("too_large")
            raw = path.read_bytes()
        except OSError:
            raise WaitlistEnvelopeRejected("unreadable") from None
        try:
            return json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise WaitlistEnvelopeRejected("invalid_json") from None

    def _reject(self, path: Path, category: str) -> None:
        self.store.bump_metric(REJECTED_METRIC, {"category": category})
        log.warning("waitlist envelope rejected category=%s", category)
        if self.config.waitlist_retain_rejected:
            digest = hashlib.sha256(path.name.encode("utf-8", "replace")).hexdigest()[:16]
            dest = self.invalid / f"rejected-{int(time.time())}-{digest}.json"
            try:
                os.replace(path, dest)
                _chmod(dest, 0o600)
                return
            except OSError:
                pass
        _unlink(path)

    def _ack(self, path: Path) -> None:
        if self.config.waitlist_acked_retention_hours <= 0:
            _unlink(path)
            return
        try:
            os.replace(path, self.acked / path.name)
        except OSError:
            _unlink(path)


def _prune_dir(directory: Path, cutoff: float) -> int:
    removed = 0
    if not directory.exists():
        return 0
    for entry in directory.iterdir():
        try:
            if entry.is_file() and entry.stat().st_mtime < cutoff:
                entry.unlink()
                removed += 1
        except OSError:
            continue
    return removed


def _unlink(path: Path) -> None:
    try:
        path.unlink()
    except FileNotFoundError:
        pass
    except OSError:
        log.error("waitlist inbox file could not be removed; check inbox permissions")


def _chmod(path: Path, mode: int) -> None:
    try:
        os.chmod(path, mode)
    except OSError:
        pass
