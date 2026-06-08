-- Raw, append-only audit log of every analytics event ingested from upstream domain
-- events. Stored so snapshots can be recomputed (ai-context: analytics is
-- projection-only; it never modifies source business data). `source_event_id` is the
-- originating event's id and is UNIQUE (belt-and-suspenders with the inbox dedup).
-- `user_id` / `related_entity_id` are EXTERNAL references (no cross-service FK).
CREATE TABLE analytics_events (
    id                UUID         NOT NULL,
    source_event_id   UUID         NOT NULL,
    metric_type       VARCHAR(48)  NOT NULL,
    user_id           UUID,
    related_entity_id UUID,
    metric_value      BIGINT       NOT NULL DEFAULT 0,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_analytics_events PRIMARY KEY (id),
    CONSTRAINT uq_analytics_events_source_event_id UNIQUE (source_event_id)
);

CREATE INDEX idx_analytics_events_metric ON analytics_events (metric_type, occurred_at);
CREATE INDEX idx_analytics_events_user ON analytics_events (user_id);
