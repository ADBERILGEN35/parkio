-- Global parking-funnel aggregate: one row per parking metric type
-- (PARKING_CREATED / PARKING_VERIFIED / PARKING_CLAIMED / PARKING_REJECTED). A
-- denormalised fast path for the parking analytics endpoint.
CREATE TABLE parking_analytics_snapshots (
    id           UUID         NOT NULL,
    metric_type  VARCHAR(48)  NOT NULL,
    event_count  BIGINT       NOT NULL DEFAULT 0,
    sum_value    BIGINT       NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_parking_analytics_snapshots PRIMARY KEY (id),
    CONSTRAINT uq_parking_analytics_snapshots UNIQUE (metric_type)
);
