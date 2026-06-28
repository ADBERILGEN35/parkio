UPDATE user_preferences
SET smart_return_enabled = COALESCE(smart_return_enabled, FALSE),
    reminder_lead_minutes = COALESCE(reminder_lead_minutes, 15),
    smart_return_today_status = COALESCE(smart_return_today_status, 'UNKNOWN');

ALTER TABLE user_preferences
    ALTER COLUMN smart_return_enabled SET DEFAULT FALSE,
    ALTER COLUMN smart_return_enabled SET NOT NULL,
    ALTER COLUMN reminder_lead_minutes SET DEFAULT 15,
    ALTER COLUMN reminder_lead_minutes SET NOT NULL,
    ALTER COLUMN smart_return_today_status SET DEFAULT 'UNKNOWN',
    ALTER COLUMN smart_return_today_status SET NOT NULL;
