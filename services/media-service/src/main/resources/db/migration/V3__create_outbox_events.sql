-- Transactional outbox (ai-context/06). Domain events (MediaUploaded,
-- MediaRejected) are written here in the same transaction as the state change; a
-- relay (not implemented yet) will publish unpublished rows to Kafka and mark them
-- published.
CREATE TABLE outbox_events (
    id             UUID         NOT NULL,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(128) NOT NULL,
    payload        TEXT         NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    published      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_outbox_events PRIMARY KEY (id)
);

-- Supports the relay query "unpublished events, oldest first".
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (published, occurred_at);
