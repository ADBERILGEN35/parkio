-- One row per user verification/report of a spot. The unique constraint enforces
-- "a user may verify a given spot only once". FK is intra-service (same database).
CREATE TABLE parking_spot_verifications (
    id                UUID         NOT NULL,
    spot_id           UUID         NOT NULL,
    verifier_user_id  UUID         NOT NULL,
    result            VARCHAR(32)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_parking_spot_verifications PRIMARY KEY (id),
    CONSTRAINT uq_parking_spot_verifications_spot_user UNIQUE (spot_id, verifier_user_id),
    CONSTRAINT fk_parking_spot_verifications_spot
        FOREIGN KEY (spot_id) REFERENCES parking_spots (id)
);

CREATE INDEX idx_parking_spot_verifications_spot ON parking_spot_verifications (spot_id);
