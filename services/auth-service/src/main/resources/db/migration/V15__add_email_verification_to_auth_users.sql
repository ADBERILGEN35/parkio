-- Email verification is owned by auth-service because it gates credential/session
-- issuance. Raw verification tokens are never stored; only a SHA-256 hash is kept.
ALTER TABLE auth_users
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN email_verified_at TIMESTAMPTZ,
    ADD COLUMN email_verification_token_hash VARCHAR(64),
    ADD COLUMN email_verification_expires_at TIMESTAMPTZ,
    ADD COLUMN email_verification_sent_at TIMESTAMPTZ;

UPDATE auth_users
    SET email_verified = TRUE,
        email_verified_at = COALESCE(status_changed_at, created_at)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_auth_users_email_verification_token_hash
    ON auth_users (email_verification_token_hash)
    WHERE email_verification_token_hash IS NOT NULL;
