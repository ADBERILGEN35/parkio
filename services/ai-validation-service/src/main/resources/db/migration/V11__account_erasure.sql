-- PRIV-001: local erasure tombstones (auth_user_id only — no PII).
CREATE TABLE erased_user_tombstones (
    auth_user_id UUID        NOT NULL,
    erased_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_erased_user_tombstones PRIMARY KEY (auth_user_id)
);

CREATE INDEX IF NOT EXISTS idx_ai_validation_results_requested_by
    ON ai_validation_results (requested_by_user_id);
