ALTER TABLE outbox_events
    ADD COLUMN recovery_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN acknowledged_deadletter BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN acknowledged_at TIMESTAMPTZ,
    ADD COLUMN acknowledged_by VARCHAR(200),
    ADD COLUMN acknowledged_reason TEXT,
    ADD COLUMN last_recovery_action VARCHAR(40),
    ADD COLUMN last_recovery_at TIMESTAMPTZ,
    ADD COLUMN last_recovery_by VARCHAR(200),
    ADD COLUMN last_recovery_reason TEXT;

CREATE INDEX idx_outbox_events_deadletter_open
    ON outbox_events (created_at, id)
    WHERE dead_lettered = true AND acknowledged_deadletter = false;

CREATE TABLE outbox_recovery_audit (
    id                  UUID PRIMARY KEY,
    outbox_event_id     UUID NOT NULL,
    event_id            UUID,
    action              VARCHAR(40) NOT NULL,
    operator_id         VARCHAR(200) NOT NULL,
    reason              TEXT,
    dry_run             BOOLEAN NOT NULL DEFAULT FALSE,
    previous_failure_count INTEGER,
    previous_dead_lettered BOOLEAN,
    previous_acknowledged BOOLEAN,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_recovery_audit_event
    ON outbox_recovery_audit (outbox_event_id, created_at);
