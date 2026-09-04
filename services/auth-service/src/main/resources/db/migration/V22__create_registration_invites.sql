CREATE TABLE registration_invites (
    id UUID PRIMARY KEY,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(200) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_registration_invites_active
    ON registration_invites(expires_at)
    WHERE consumed_at IS NULL;
