ALTER TABLE refresh_tokens
    ADD COLUMN token_family_id UUID,
    ADD COLUMN parent_token_id UUID,
    ADD COLUMN reused_detected BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN revoked_reason VARCHAR(32),
    ADD COLUMN revoked_at TIMESTAMPTZ,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Existing tokens become roots of independent families. This preserves their
-- current validity and revocation state without linking unrelated sessions.
UPDATE refresh_tokens
SET token_family_id = id
WHERE token_family_id IS NULL;

ALTER TABLE refresh_tokens
    ALTER COLUMN token_family_id SET NOT NULL,
    ADD CONSTRAINT fk_refresh_tokens_parent
        FOREIGN KEY (parent_token_id) REFERENCES refresh_tokens (id),
    ADD CONSTRAINT ck_refresh_tokens_revoked_reason
        CHECK (revoked_reason IS NULL OR revoked_reason IN (
            'ROTATED',
            'LOGOUT',
            'REUSE_DETECTED',
            'EXPIRED_CLEANUP',
            'ADMIN_REVOKED'
        ));

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user
    ON refresh_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family
    ON refresh_tokens (token_family_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_token_hash
    ON refresh_tokens (token_hash);
