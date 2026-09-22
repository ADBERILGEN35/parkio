-- Additive full name for new waitlist subscriptions. Legacy rows remain NULL.
ALTER TABLE waitlist_interest
    ADD COLUMN full_name VARCHAR(100);

COMMENT ON COLUMN waitlist_interest.full_name IS
    'Subscriber full name collected at signup; NULL for pre-V5 / legacy rows.';

-- Supports confirmed-today snapshot counts for ops Slack (Europe/Istanbul day bounds).
CREATE INDEX idx_waitlist_interest_status_confirmed_at
    ON waitlist_interest (status, confirmed_at);

-- Link ops outbox rows to the interest so exporters can load allowlisted fullName.
ALTER TABLE waitlist_ops_notification_outbox
    ADD COLUMN interest_id UUID;

COMMENT ON COLUMN waitlist_ops_notification_outbox.interest_id IS
    'waitlist_interest.id for subscription_confirmed; NULL on pre-V5 outbox rows.';
