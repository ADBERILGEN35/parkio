-- PRIV-001: account erasure ledger + tombstones (no PII).

ALTER TABLE refresh_tokens DROP CONSTRAINT IF EXISTS chk_refresh_tokens_revoked_reason;
ALTER TABLE refresh_tokens
    ADD CONSTRAINT chk_refresh_tokens_revoked_reason
    CHECK (
        revoked_reason IS NULL
        OR revoked_reason IN (
            'ROTATED',
            'LOGOUT',
            'REUSE_DETECTED',
            'EXPIRED_CLEANUP',
            'ADMIN_REVOKED',
            'PASSWORD_CHANGED',
            'ACCOUNT_ERASURE'
        )
    );

CREATE TABLE erasure_requests (
    id              UUID         NOT NULL,
    auth_user_id    UUID         NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    requested_at    TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_erasure_requests PRIMARY KEY (id),
    CONSTRAINT chk_erasure_requests_status CHECK (
        status IN ('REQUESTED', 'IN_PROGRESS', 'COMPLETE', 'FAILED_RETRYING')
    )
);

CREATE INDEX idx_erasure_requests_auth_user_id ON erasure_requests (auth_user_id);
CREATE INDEX idx_erasure_requests_status ON erasure_requests (status);

CREATE TABLE erasure_service_acks (
    erasure_request_id UUID        NOT NULL,
    service_name       VARCHAR(64) NOT NULL,
    status             VARCHAR(16) NOT NULL,
    acked_at           TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_erasure_service_acks PRIMARY KEY (erasure_request_id, service_name),
    CONSTRAINT fk_erasure_service_acks_request
        FOREIGN KEY (erasure_request_id) REFERENCES erasure_requests (id),
    CONSTRAINT chk_erasure_service_acks_status CHECK (status IN ('SUCCESS', 'FAILED'))
);

CREATE TABLE erased_user_tombstones (
    auth_user_id UUID        NOT NULL,
    erased_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_erased_user_tombstones PRIMARY KEY (auth_user_id)
);
