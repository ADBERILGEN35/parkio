-- R-07 / #416: bound transient broker deferral so a misclassified poison row cannot
-- block the outbox forever. first_transient_failure_at marks the start of the window;
-- the relay dead-letters once parkio.kafka.relay.max-transient-deferral-ms elapses.
ALTER TABLE outbox_events
    ADD COLUMN first_transient_failure_at TIMESTAMPTZ;
