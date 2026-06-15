-- DLQ / poison-message handling for the outbox relay (kafka-transport.md).
-- A row that repeatedly fails to publish is retried per-row (no longer blocking the
-- batch) and, after `max-attempts` failures, marked dead_lettered: skipped by the
-- relay claim query but retained in-table for inspection and manual redrive.
ALTER TABLE outbox_events
    ADD COLUMN failure_count       INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN last_failure_reason TEXT,
    ADD COLUMN last_failed_at      TIMESTAMPTZ,
    ADD COLUMN dead_lettered       BOOLEAN     NOT NULL DEFAULT FALSE;

-- The relay now claims rows WHERE published = false AND dead_lettered = false,
-- oldest first. A partial index keeps that claim cheap as the table grows.
CREATE INDEX idx_outbox_events_relayable
    ON outbox_events (created_at, id)
    WHERE published = false AND dead_lettered = false;
