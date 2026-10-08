-- CL-F18: record which consent text a subscriber accepted. The server already stamps the consent
-- time (consent_timestamp = gateway receipt time) and keeps the client-asserted time separately
-- (client_consent_timestamp). Rows recorded before this change, or by a path that sent no version,
-- are 'legacy-unversioned': they completed double opt-in for the same purpose, but which wording
-- they saw is not recorded. Whether they may receive anything, and whether they must re-consent,
-- are open product and legal decisions; nothing here grants either.
ALTER TABLE waitlist_interest
    ADD COLUMN consent_text_version VARCHAR(64) NOT NULL DEFAULT 'legacy-unversioned';

COMMENT ON COLUMN waitlist_interest.consent_text_version IS
    'Version id of the consent text the subscriber accepted (for example waitlist-consent-v1); legacy-unversioned for rows recorded before CL-F18; unversioned-client when a compatibility-mode submission carried no version.';
