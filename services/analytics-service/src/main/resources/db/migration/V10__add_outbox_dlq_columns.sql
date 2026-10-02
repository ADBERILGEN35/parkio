-- U05: analytics-service gains an outbox relay (it now publishes participant erasure ACKs).
-- Relay DLQ metadata mirrors the other services (e.g. notification-service V13): after
-- max-attempts publish failures a row is dead_lettered so it no longer blocks later rows.
-- Purely additive; the table held no relayed rows before this version.
ALTER TABLE outbox_events
    ADD COLUMN failure_count       INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN last_failure_reason TEXT,
    ADD COLUMN last_failed_at      TIMESTAMPTZ,
    ADD COLUMN dead_lettered       BOOLEAN     NOT NULL DEFAULT FALSE;

DROP INDEX IF EXISTS idx_outbox_events_unpublished;

CREATE INDEX idx_outbox_events_unpublished ON outbox_events (published, dead_lettered, created_at);
