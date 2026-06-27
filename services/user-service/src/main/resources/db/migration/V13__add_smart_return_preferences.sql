ALTER TABLE user_preferences
    ADD COLUMN smart_return_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN home_latitude DOUBLE PRECISION,
    ADD COLUMN home_longitude DOUBLE PRECISION,
    ADD COLUMN home_label VARCHAR(160),
    ADD COLUMN default_return_time TIME,
    ADD COLUMN reminder_lead_minutes INT NOT NULL DEFAULT 15,
    ADD COLUMN last_smart_return_prompt_date DATE,
    ADD COLUMN smart_return_today_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN today_expected_return_at TIMESTAMPTZ,
    ADD COLUMN today_return_check_claimed_at TIMESTAMPTZ,
    ADD COLUMN today_return_check_claim_expires_at TIMESTAMPTZ,
    ADD COLUMN today_return_check_completed_at TIMESTAMPTZ,
    ADD COLUMN today_notification_sent_at TIMESTAMPTZ;

ALTER TABLE user_preferences
    ADD CONSTRAINT chk_user_preferences_home_latitude
        CHECK (home_latitude IS NULL OR (home_latitude >= -90 AND home_latitude <= 90)),
    ADD CONSTRAINT chk_user_preferences_home_longitude
        CHECK (home_longitude IS NULL OR (home_longitude >= -180 AND home_longitude <= 180)),
    ADD CONSTRAINT chk_user_preferences_home_pair
        CHECK ((home_latitude IS NULL AND home_longitude IS NULL)
            OR (home_latitude IS NOT NULL AND home_longitude IS NOT NULL)),
    ADD CONSTRAINT chk_user_preferences_smart_return_lead
        CHECK (reminder_lead_minutes BETWEEN 5 AND 120),
    ADD CONSTRAINT chk_user_preferences_smart_return_status
        CHECK (smart_return_today_status IN ('UNKNOWN', 'LEFT_BY_CAR', 'RETURN_CHECK_IN_PROGRESS', 'NOT_BY_CAR', 'CANCELLED')),
    ADD CONSTRAINT chk_user_preferences_smart_return_claim_window
        CHECK (today_return_check_claim_expires_at IS NULL
            OR today_return_check_claimed_at IS NOT NULL),
    ADD CONSTRAINT chk_user_preferences_smart_return_claim_order
        CHECK (today_return_check_claimed_at IS NULL
            OR today_return_check_claim_expires_at IS NULL
            OR today_return_check_claim_expires_at > today_return_check_claimed_at);

CREATE INDEX idx_user_preferences_smart_return_prompt_due
    ON user_preferences (last_smart_return_prompt_date, user_profile_id)
    WHERE smart_return_enabled = true
      AND notifications_enabled = true
      AND home_latitude IS NOT NULL
      AND home_longitude IS NOT NULL;

CREATE INDEX idx_user_preferences_smart_return_check_due
    ON user_preferences (today_expected_return_at, user_profile_id)
    WHERE smart_return_enabled = true
      AND notifications_enabled = true
      AND smart_return_today_status IN ('LEFT_BY_CAR', 'RETURN_CHECK_IN_PROGRESS')
      AND today_return_check_completed_at IS NULL;
