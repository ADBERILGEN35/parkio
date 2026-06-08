-- Per-user, per-metric rolling aggregate. One row per (user_id, metric_type).
-- `user_id` is the platform-wide authUserId (EXTERNAL reference, no FK).
CREATE TABLE user_analytics_snapshots (
    id           UUID         NOT NULL,
    user_id      UUID         NOT NULL,
    metric_type  VARCHAR(48)  NOT NULL,
    event_count  BIGINT       NOT NULL DEFAULT 0,
    sum_value    BIGINT       NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_analytics_snapshots PRIMARY KEY (id),
    CONSTRAINT uq_user_analytics_snapshots UNIQUE (user_id, metric_type)
);

CREATE INDEX idx_user_analytics_snapshots_user ON user_analytics_snapshots (user_id);
