-- Per-channel delivery attempts for notifications (push delivery foundation).
-- One row tracks an attempt to deliver a notification to a single device token.
-- The raw token value is NEVER stored here; only device_token_id is referenced,
-- so delivery history carries no secrets (ai-context/07).
CREATE TABLE notification_delivery_attempts (
    id                  UUID         NOT NULL,
    notification_id     UUID         NOT NULL,
    user_id             UUID         NOT NULL,
    channel             VARCHAR(16)  NOT NULL,
    device_token_id     UUID,
    status              VARCHAR(16)  NOT NULL,
    provider_message_id VARCHAR(255),
    failure_reason      VARCHAR(255),
    attempt_count       INT          NOT NULL DEFAULT 0,
    attempted_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_notification_delivery_attempts PRIMARY KEY (id),
    CONSTRAINT fk_nda_device_token FOREIGN KEY (device_token_id)
        REFERENCES device_tokens (id)
);

-- Worker scan: oldest PENDING first.
CREATE INDEX idx_nda_status_created ON notification_delivery_attempts (status, created_at);
-- Idempotency / lookup by notification.
CREATE INDEX idx_nda_notification ON notification_delivery_attempts (notification_id);
