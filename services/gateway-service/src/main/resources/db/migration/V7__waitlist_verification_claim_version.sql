-- F-33: monotonic claim version so concurrent resends CAS-lose and completion
-- binds to the winning claim. Existing rows start at 0.
ALTER TABLE waitlist_interest
    ADD COLUMN verification_claim_version BIGINT NOT NULL DEFAULT 0;
