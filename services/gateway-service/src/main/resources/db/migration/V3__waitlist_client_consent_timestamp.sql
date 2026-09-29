-- W01L: preserve client-asserted consent event time separately from server receipt.
-- consent_timestamp remains NOT NULL; going forward it stores server receipt time.
-- Existing rows keep prior consent_timestamp values; client_consent_timestamp stays NULL.
ALTER TABLE waitlist_interest
    ADD COLUMN client_consent_timestamp TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN waitlist_interest.consent_timestamp IS
    'Server-recorded time when the consented waitlist signup was accepted at the gateway.';
COMMENT ON COLUMN waitlist_interest.client_consent_timestamp IS
    'Client-asserted consent event time after bounded clock-skew validation; optional for legacy rows.';
