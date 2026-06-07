-- Refresh tokens. Only the SHA-256 hash of the opaque token is stored; the raw
-- value is returned to the client once and never persisted.
CREATE TABLE refresh_tokens (
    id         UUID         NOT NULL,
    user_id    UUID         NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES auth_users (id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
