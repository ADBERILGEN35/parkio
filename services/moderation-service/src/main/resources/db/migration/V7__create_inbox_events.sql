-- Inbox / processed-messages table (ai-context/06) for idempotent consumption of
-- upstream events (parking rejections, media rejections, AI validation results).
-- `id` holds the consumed event's eventId so redeliveries are skipped. The Kafka
-- consumer is not wired yet; handlers are invoked directly.
CREATE TABLE inbox_events (
    id           UUID         NOT NULL,
    event_type   VARCHAR(128) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_inbox_events PRIMARY KEY (id)
);
