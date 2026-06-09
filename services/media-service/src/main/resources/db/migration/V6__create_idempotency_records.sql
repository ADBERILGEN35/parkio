CREATE TABLE idempotency_records (
    id                  UUID         NOT NULL,
    user_id             UUID         NOT NULL,
    http_method         VARCHAR(16)  NOT NULL,
    operation_path      VARCHAR(512) NOT NULL,
    idempotency_key     VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    response_status     INTEGER,
    response_body       TEXT,
    created_at          TIMESTAMPTZ  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_idempotency_records PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_scope
        UNIQUE (user_id, http_method, operation_path, idempotency_key),
    CONSTRAINT uq_idempotency_user_method_key
        UNIQUE (user_id, http_method, idempotency_key)
);

CREATE INDEX idx_idempotency_records_expires_at ON idempotency_records (expires_at);
