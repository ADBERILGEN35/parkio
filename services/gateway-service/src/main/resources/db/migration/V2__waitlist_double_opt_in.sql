ALTER TABLE waitlist_interest
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PENDING';
ALTER TABLE waitlist_interest
    ADD COLUMN locale VARCHAR(8) NOT NULL DEFAULT 'tr';
ALTER TABLE waitlist_interest
    ADD COLUMN verification_token_hash VARCHAR(64);
ALTER TABLE waitlist_interest
    ADD COLUMN withdraw_token_hash VARCHAR(64);
ALTER TABLE waitlist_interest
    ADD COLUMN verification_expires_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE waitlist_interest
    ADD COLUMN verification_sent_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE waitlist_interest
    ADD COLUMN resend_count INT NOT NULL DEFAULT 0;
ALTER TABLE waitlist_interest
    ADD COLUMN confirmed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE waitlist_interest
    ADD COLUMN withdrawn_at TIMESTAMP WITH TIME ZONE;

-- Legacy V1 rows stay PENDING without verification tokens. Do not invent
-- confirmed consent for pre-double-opt-in records; visitors must re-submit
-- (or use a controlled re-invite) to obtain a confirmation token.

ALTER TABLE waitlist_interest
    ADD CONSTRAINT chk_waitlist_interest_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'WITHDRAWN'));
ALTER TABLE waitlist_interest
    ADD CONSTRAINT chk_waitlist_interest_locale
        CHECK (locale IN ('tr', 'en'));

CREATE UNIQUE INDEX uk_waitlist_interest_verification_token_hash
    ON waitlist_interest (verification_token_hash);
CREATE UNIQUE INDEX uk_waitlist_interest_withdraw_token_hash
    ON waitlist_interest (withdraw_token_hash);
CREATE INDEX idx_waitlist_interest_status_created_at
    ON waitlist_interest (status, created_at);
