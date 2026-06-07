-- Append-only audit of spot status transitions (who/what triggered the change is
-- captured via `reason`). Useful for moderation/analytics later.
CREATE TABLE parking_spot_status_history (
    id               UUID         NOT NULL,
    spot_id          UUID         NOT NULL,
    previous_status  VARCHAR(32),
    new_status       VARCHAR(32)  NOT NULL,
    reason           VARCHAR(128) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_parking_spot_status_history PRIMARY KEY (id),
    CONSTRAINT fk_parking_spot_status_history_spot
        FOREIGN KEY (spot_id) REFERENCES parking_spots (id)
);

CREATE INDEX idx_parking_spot_status_history_spot ON parking_spot_status_history (spot_id, created_at);
