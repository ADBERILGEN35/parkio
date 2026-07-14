-- RC2B: SUPER_ADMIN role, administrative audit trail, refresh-token reason fix.

INSERT INTO roles (id, name)
VALUES ('00000000-0000-0000-0000-000000000004', 'SUPER_ADMIN')
ON CONFLICT (id) DO NOTHING;

-- Allow PASSWORD_CHANGED (used since password-reset landed; V11 CHECK omitted it).
-- V11 named the constraint ck_refresh_tokens_revoked_reason; drop both spellings
-- so re-runs against partially migrated databases stay safe.
ALTER TABLE refresh_tokens DROP CONSTRAINT IF EXISTS ck_refresh_tokens_revoked_reason;
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
            'PASSWORD_CHANGED'
        )
    );

CREATE TABLE admin_audit_events (
    id                   UUID         NOT NULL,
    occurred_at          TIMESTAMPTZ  NOT NULL,
    actor_user_id        UUID         NOT NULL,
    actor_roles          VARCHAR(256) NOT NULL,
    action_type          VARCHAR(64)  NOT NULL,
    target_resource_type VARCHAR(64)  NOT NULL,
    target_resource_id   UUID,
    result               VARCHAR(16)  NOT NULL,
    reason               VARCHAR(512),
    correlation_id       VARCHAR(64),
    trace_id             VARCHAR(64),
    metadata_json        TEXT,
    CONSTRAINT pk_admin_audit_events PRIMARY KEY (id),
    CONSTRAINT chk_admin_audit_events_result CHECK (result IN ('SUCCESS', 'FAILURE'))
);

CREATE INDEX idx_admin_audit_events_occurred_at ON admin_audit_events (occurred_at DESC);
CREATE INDEX idx_admin_audit_events_actor ON admin_audit_events (actor_user_id, occurred_at DESC);
CREATE INDEX idx_admin_audit_events_action ON admin_audit_events (action_type, occurred_at DESC);
CREATE INDEX idx_admin_audit_events_target ON admin_audit_events (target_resource_type, target_resource_id);
CREATE INDEX idx_admin_audit_events_correlation ON admin_audit_events (correlation_id)
    WHERE correlation_id IS NOT NULL;

-- Support admin user search / filters.
CREATE INDEX idx_auth_users_created_at ON auth_users (created_at DESC);
CREATE INDEX idx_auth_users_status ON auth_users (status);
CREATE INDEX idx_auth_users_email_verified ON auth_users (email_verified);

-- idx_refresh_tokens_user_active (user_id WHERE revoked = false) already exists
-- from V13; recreating it here without IF NOT EXISTS would abort the migration.
