-- Operational (Slack) notification outbox for waitlist lifecycle events.
--
-- Rows are written in the SAME transaction as the waitlist state change, so a
-- rolled-back confirmation never produces a notification. The table holds no
-- subscriber data: no email, token, IP, subscriber id or provider payload.
-- dedup_key is an HMAC (waitlist hash secret) of the event type and the
-- subscriber row id, so it cannot be reversed or joined without the secret.
CREATE TABLE waitlist_ops_notification_outbox (
    id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    dedup_key VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error_category VARCHAR(32),
    exported_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_waitlist_ops_outbox_dedup_key UNIQUE (dedup_key),
    CONSTRAINT chk_waitlist_ops_outbox_status CHECK (status IN ('PENDING', 'EXPORTED', 'FAILED')),
    CONSTRAINT chk_waitlist_ops_outbox_event_type CHECK (event_type IN ('waitlist.subscription_confirmed'))
);

CREATE INDEX idx_waitlist_ops_outbox_due
    ON waitlist_ops_notification_outbox (status, next_attempt_at);
