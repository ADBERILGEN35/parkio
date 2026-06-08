-- Immutable ledger of every point change. `idempotency_key` is UNIQUE so the same
-- logical award/penalty can never be applied twice (ai-context/06). Source event /
-- spot are external references kept for audit only.
CREATE TABLE point_transactions (
    id               UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    idempotency_key  VARCHAR(200) NOT NULL,
    source_type      VARCHAR(48)  NOT NULL,
    direction        VARCHAR(16)  NOT NULL,
    points           BIGINT       NOT NULL,
    related_event_id UUID,
    related_spot_id  UUID,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_point_transactions PRIMARY KEY (id),
    CONSTRAINT uq_point_transactions_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_point_transactions_user ON point_transactions (user_id, created_at DESC);
