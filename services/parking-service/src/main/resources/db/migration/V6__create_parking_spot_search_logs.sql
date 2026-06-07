-- Records nearby-search requests (origin, radius, result count) for analytics.
CREATE TABLE parking_spot_search_logs (
    id                UUID             NOT NULL,
    searcher_user_id  UUID             NOT NULL,
    latitude          DOUBLE PRECISION NOT NULL,
    longitude         DOUBLE PRECISION NOT NULL,
    radius_meters     DOUBLE PRECISION NOT NULL,
    result_count      INTEGER          NOT NULL,
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT pk_parking_spot_search_logs PRIMARY KEY (id)
);

CREATE INDEX idx_parking_spot_search_logs_searcher ON parking_spot_search_logs (searcher_user_id, created_at);
