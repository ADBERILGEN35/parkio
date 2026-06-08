-- Per-day, per-metric rolling aggregate (the time series). One row per
-- (snapshot_date, metric_type). `event_count`/`sum_value` are accumulated as events
-- are ingested; the global overview/metrics aggregate across these rows.
CREATE TABLE daily_analytics_snapshots (
    id            UUID         NOT NULL,
    snapshot_date DATE         NOT NULL,
    metric_type   VARCHAR(48)  NOT NULL,
    event_count   BIGINT       NOT NULL DEFAULT 0,
    sum_value     BIGINT       NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_daily_analytics_snapshots PRIMARY KEY (id),
    CONSTRAINT uq_daily_analytics_snapshots UNIQUE (snapshot_date, metric_type)
);

CREATE INDEX idx_daily_analytics_snapshots_date ON daily_analytics_snapshots (snapshot_date);
