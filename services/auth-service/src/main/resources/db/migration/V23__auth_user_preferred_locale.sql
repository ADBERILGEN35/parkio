-- Persist registration locale for verification (and resend) email + SPA lang.
ALTER TABLE auth_users
    ADD COLUMN preferred_locale VARCHAR(8) NOT NULL DEFAULT 'tr';

ALTER TABLE auth_users
    ADD CONSTRAINT auth_users_preferred_locale_chk
        CHECK (preferred_locale IN ('tr', 'en'));
