-- Records who opened a spot's detail view (lightweight analytics / abuse signal).
CREATE TABLE parking_spot_view_logs (
    id              UUID         NOT NULL,
    spot_id         UUID         NOT NULL,
    viewer_user_id  UUID         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_parking_spot_view_logs PRIMARY KEY (id)
);

CREATE INDEX idx_parking_spot_view_logs_spot ON parking_spot_view_logs (spot_id, created_at);
