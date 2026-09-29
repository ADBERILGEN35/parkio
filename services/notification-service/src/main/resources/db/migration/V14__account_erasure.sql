-- PRIV-001: local erasure tombstones (auth_user_id only — no PII).
CREATE TABLE erased_user_tombstones (
    auth_user_id UUID        NOT NULL,
    erased_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_erased_user_tombstones PRIMARY KEY (auth_user_id)
);

CREATE INDEX IF NOT EXISTS idx_nda_user ON notification_delivery_attempts (user_id);
