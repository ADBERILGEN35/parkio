-- PRIV-001: local erasure tombstones (auth_user_id only — no PII).
CREATE TABLE erased_user_tombstones (
    auth_user_id UUID        NOT NULL,
    erased_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_erased_user_tombstones PRIMARY KEY (auth_user_id)
);

-- Community spots may drop media after owner erasure.
ALTER TABLE parking_spots ALTER COLUMN media_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_parking_spot_view_logs_viewer
    ON parking_spot_view_logs (viewer_user_id);

CREATE INDEX IF NOT EXISTS idx_parking_spot_verifications_verifier
    ON parking_spot_verifications (verifier_user_id);
