-- Inbox / processed-messages table (ai-context/06) for idempotent consumption.
-- `id` holds the consumed event's eventId so redeliveries can be skipped.
-- No consumer is implemented yet; the table is provisioned for future use.
CREATE TABLE inbox_events (
    id           UUID         NOT NULL,
    event_type   VARCHAR(128) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_inbox_events PRIMARY KEY (id)
);
