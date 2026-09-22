"""Registration envelope consumer: durable enqueue BEFORE offset/ack.

Statuses:
  - File-inbox adapter: IMPLEMENTED (local/CI acceptance path)
  - Live Kafka consumer: IMPLEMENTED_NOT_EXECUTED unless
    PARKIO_SLACK_BIZ_KAFKA_BOOTSTRAP is set and kafka-python is installed

Contract:
  successful parse + durable enqueue → then ack (file rename / Kafka commit).
  If enqueue fails, do NOT ack → redelivery safe via dedup.
  Disabled integration: still durable-enqueues when ingesting (so backlog is
  visible) but worker performs no outbound Slack. Operators must not enable
  with auto.offset.reset=earliest against a populated topic.
"""

from __future__ import annotations

import json
import logging
import shutil
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from .adapters import from_user_registered_envelope, refuse_non_completion
from .config import SlackBizConfig
from .store import DeliveryStore

log = logging.getLogger("parkio.slack_biz.registration_consumer")


@dataclass
class ConsumeResult:
    processed: int = 0
    enqueued: int = 0
    suppressed: int = 0
    invalid: int = 0
    write_failures: int = 0
    acked: int = 0


def parse_and_enqueue(
    envelope: dict[str, Any],
    config: SlackBizConfig,
    store: DeliveryStore,
    *,
    producer: str = "auth-outbox",
) -> str:
    """
    Validate UserRegistered envelope and durable-enqueue.
    Returns enqueue result string or raises ValueError for invalid events.
    """
    event_type = envelope.get("eventType") or envelope.get("event_type") or ""
    refuse_non_completion(str(event_type))
    event = from_user_registered_envelope(envelope, config, producer=producer)
    return store.enqueue(event)


class FileInboxRegistrationConsumer:
    """
    Process *.json envelopes from an inbox directory.

    Flow per file:
      1. read + parse
      2. durable enqueue (or suppress duplicate)
      3. only then move to .acked/ (offset analogue)
    Invalid files → .invalid/ (bounded visible handling, not silent drop).
    Write failure before ack → leave file in inbox for retry.
    """

    def __init__(
        self,
        config: SlackBizConfig,
        store: DeliveryStore,
        inbox: Path,
        *,
        producer: str = "auth-outbox",
        enqueue_fn: Callable[..., str] | None = None,
    ):
        self.config = config
        self.store = store
        self.enqueue_fn = enqueue_fn or parse_and_enqueue
        self.inbox = Path(inbox)
        self.acked = self.inbox / ".acked"
        self.invalid = self.inbox / ".invalid"
        self.producer = producer
        self.inbox.mkdir(parents=True, exist_ok=True)
        self.acked.mkdir(parents=True, exist_ok=True)
        self.invalid.mkdir(parents=True, exist_ok=True)

    def poll_once(self) -> ConsumeResult:
        result = ConsumeResult()
        for path in sorted(self.inbox.glob("*.json")):
            result.processed += 1
            try:
                envelope = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as exc:
                result.invalid += 1
                self._move(path, self.invalid, suffix=f".bad-{int(time.time())}")
                log.warning("invalid envelope file %s: %s", path.name, exc)
                continue
            try:
                outcome = self.enqueue_fn(
                    envelope, self.config, self.store, producer=self.producer
                )
            except ValueError as exc:
                result.invalid += 1
                meta = path.with_suffix(path.suffix + ".error.txt")
                try:
                    meta.write_text(str(exc), encoding="utf-8")
                except OSError:
                    pass
                self._move(path, self.invalid)
                log.warning("rejected envelope %s: %s", path.name, exc)
                continue
            except OSError as exc:
                # Queue write failure — do NOT ack
                result.write_failures += 1
                log.error("queue write failed for %s (not acked): %s", path.name, exc)
                continue

            if outcome == "queued":
                result.enqueued += 1
            else:
                result.suppressed += 1

            # Ack only after durable acceptance (or deterministic suppress)
            self._move(path, self.acked)
            result.acked += 1
        return result

    @staticmethod
    def _move(src: Path, dest_dir: Path, suffix: str = "") -> None:
        dest = dest_dir / (src.name + suffix)
        if dest.exists():
            dest = dest_dir / f"{src.stem}-{int(time.time())}{src.suffix}{suffix}"
        shutil.move(str(src), str(dest))


class KafkaRegistrationConsumer:
    """
    Optional live Kafka consumer for parkio.auth.user.

    Requires kafka-python. Commit only after durable enqueue succeeds.
    Default auto_offset_reset=latest to avoid historical flood on first enable.
    """

    def __init__(
        self,
        config: SlackBizConfig,
        store: DeliveryStore,
        *,
        producer: str = "auth-outbox",
    ):
        if not config.kafka_bootstrap:
            raise RuntimeError("PARKIO_SLACK_BIZ_KAFKA_BOOTSTRAP not set")
        try:
            from kafka import KafkaConsumer  # type: ignore
        except ImportError as exc:
            raise RuntimeError(
                "kafka-python not installed; live Kafka consumer unavailable"
            ) from exc

        self.config = config
        self.store = store
        self.producer = producer
        self._KafkaConsumer = KafkaConsumer
        self._consumer = KafkaConsumer(
            config.kafka_topic,
            bootstrap_servers=config.kafka_bootstrap.split(","),
            group_id=config.kafka_group,
            enable_auto_commit=False,
            auto_offset_reset=config.kafka_auto_offset_reset,
            value_deserializer=lambda b: b.decode("utf-8"),
            consumer_timeout_ms=1000,
        )

    def poll_once(self, *, max_records: int = 50) -> ConsumeResult:
        result = ConsumeResult()
        for i, record in enumerate(self._consumer):
            if i >= max_records:
                break
            result.processed += 1
            try:
                envelope = json.loads(record.value)
                outcome = parse_and_enqueue(
                    envelope, self.config, self.store, producer=self.producer
                )
            except (ValueError, json.JSONDecodeError) as exc:
                result.invalid += 1
                # Commit invalid to avoid poison-pill loops; visible via metrics/logs
                log.warning("invalid kafka envelope offset=%s: %s", record.offset, exc)
                self._consumer.commit()
                continue
            except OSError as exc:
                result.write_failures += 1
                log.error(
                    "queue write failed; NOT committing offset=%s: %s",
                    record.offset,
                    exc,
                )
                # Do not commit — stop processing this poll
                break

            if outcome == "queued":
                result.enqueued += 1
            else:
                result.suppressed += 1
            self._consumer.commit()
            result.acked += 1
        return result

    def close(self) -> None:
        self._consumer.close()


def adapter_implementation_status(config: SlackBizConfig) -> dict[str, str]:
    """Honest status for Y03A reporting."""
    kafka_status = "ABSENT"
    if config.kafka_bootstrap:
        try:
            import kafka  # noqa: F401

            kafka_status = "IMPLEMENTED_NOT_EXECUTED"
        except ImportError:
            kafka_status = "PARTIALLY_IMPLEMENTED"  # config present, dep missing
    return {
        "envelope_adapter": "IMPLEMENTED",
        "file_inbox_consumer": "IMPLEMENTED",
        "kafka_live_consumer": kafka_status,
        "topic": config.kafka_topic,
        "consumer_group": config.kafka_group,
        "default_offset_reset": config.kafka_auto_offset_reset,
        "note": (
            "Production path: auth outbox → parkio.auth.user → this consumer "
            "→ SQLite enqueue → worker. File inbox is the isolated acceptance path."
        ),
    }
