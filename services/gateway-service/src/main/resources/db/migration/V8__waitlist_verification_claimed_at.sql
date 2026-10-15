-- R-08: claim timestamp so overlapping resends CAS-lose on cooldown while delivery is in flight.
-- verification_sent_at alone is too late (written only after provider I/O).
ALTER TABLE waitlist_interest
    ADD COLUMN verification_claimed_at TIMESTAMP WITH TIME ZONE;
